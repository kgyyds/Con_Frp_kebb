package com.kgapp.frpshellpro.server

import android.util.Log
import com.kgapp.frpshellpro.frp.FrpLogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class ClientSession(
    val id: String,
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val onClosed: (String, String) -> Unit
) {
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _shellEvents = MutableSharedFlow<ShellEvent>(extraBufferCapacity = 256)
    val shellEvents: SharedFlow<ShellEvent> = _shellEvents.asSharedFlow()

    @Volatile
    var registrationInfo: RegistrationInfo? = RegistrationInfo(id, id, "unknown")
        private set

    private val closed = AtomicBoolean(false)
    private val ioMutex = Mutex()
    private val writerMutex = Mutex()
    private val shellMessages = Channel<String>(Channel.UNLIMITED)
    private val fileMessages = Channel<ByteArray>(Channel.UNLIMITED)
    private val input = BufferedInputStream(socket.getInputStream())
    private val outputStream = BufferedOutputStream(socket.getOutputStream())

    @Volatile
    var lastTransferError: TransferError? = null
        private set

    fun start() {
        appendOutput("[register] $id")
        scope.launch(Dispatchers.IO) {
            runReadLoop()
        }
    }

    fun send(command: String) {
        if (command.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val result = runManagedCommand(command, DEFAULT_MANAGED_TIMEOUT_MS)
            if (!result.isNullOrBlank()) {
                result.lines().forEach { line ->
                    if (line.isBlank()) return@forEach
                    appendOutput(line)
                    _shellEvents.tryEmit(ShellEvent.OutputLine(line))
                }
            }
            _shellEvents.tryEmit(ShellEvent.CommandEnd)
        }
    }

    suspend fun runManagedCommand(command: String, timeoutMs: Long = DEFAULT_MANAGED_TIMEOUT_MS): String? {
        if (command.isBlank()) return ""
        return ioMutex.withLock {
            if (!ensureOpen()) return@withLock "[ERROR] connection closed"
            runCatching {
                sendMessage(TYPE_SHELL_CMD, command.toByteArray())
                val response = awaitShell(timeoutMs)
                response ?: "[ERROR] command timeout"
            }.getOrElse {
                logError("[ClientSession] runManagedCommand failed", it)
                "[ERROR] ${it.message ?: "unknown error"}"
            }
        }
    }

    suspend fun uploadFile(remotePath: String, localFile: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        val safeRemotePath = sanitizeRemotePath(remotePath) ?: return false
        if (!localFile.exists() || !localFile.isFile) return false

        return ioMutex.withLock {
            lastTransferError = null
            if (!ensureOpen()) return@withLock false
            runCatching {
                val data = localFile.readBytes()
                val total = data.size.toLong()
                onProgress?.invoke(0, total)
                sendMessage(TYPE_UPLOAD_REQ, safeRemotePath.toByteArray())
                sendMessage(TYPE_FILE_DATA, data)
                onProgress?.invoke(total, total)
                true
            }.getOrElse {
                reportTransferError(TransferErrorCode.IO_INTERRUPTED, it.message ?: "upload failed")
                false
            }
        }
    }

    suspend fun downloadFile(remotePath: String, targetFile: File, onProgress: ((Long, Long) -> Unit)? = null): DownloadResult {
        val safeRemotePath = sanitizeRemotePath(remotePath) ?: return DownloadResult.Failed

        return ioMutex.withLock {
            lastTransferError = null
            if (!ensureOpen()) return@withLock DownloadResult.Failed
            runCatching {
                sendMessage(TYPE_DOWNLOAD_REQ, safeRemotePath.toByteArray())
                val payload = awaitDownloadPayload(FILE_TRANSFER_TIMEOUT_MS) ?: return@runCatching DownloadResult.Failed

                targetFile.parentFile?.mkdirs()
                val total = payload.size.toLong()
                onProgress?.invoke(0, total)
                targetFile.writeBytes(payload)
                onProgress?.invoke(total, total)
                DownloadResult.Success
            }.getOrElse {
                reportTransferError(TransferErrorCode.IO_INTERRUPTED, it.message ?: "download failed")
                DownloadResult.Failed
            }
        }
    }

    suspend fun listFiles(path: String): ListFilesResult {
        val safePath = sanitizeRemotePath(path) ?: return ListFilesResult.Failed("invalid path")
        val result = runManagedCommand("ls -la ${shellEscape(safePath)}", DEFAULT_MANAGED_TIMEOUT_MS)
            ?: return ListFilesResult.Failed("empty response")
        if (result.startsWith("[ERROR]")) return ListFilesResult.Error(result)

        val items = result.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !it.startsWith("total") && !it.startsWith("ls:") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 9)
                if (parts.size < 9) return@mapNotNull null
                val name = parts[8].trimStart()
                if (name == "." || name == "..") return@mapNotNull null
                val fullPath = if (safePath == "/") "/$name" else "$safePath/$name"
                RemoteFileEntry(path = fullPath, file = !parts[0].startsWith("d"))
            }
            .toList()
        return ListFilesResult.Success(items)
    }

    suspend fun requestDeviceInfo(timeoutMs: Long = 5000L): JSONObject {
        val uname = runManagedCommand("uname -a", timeoutMs) ?: "unknown"
        return JSONObject().apply {
            put("type", "info")
            put("device", id)
            put("uname", uname)
        }
    }

    fun close(reason: String = "manual close", cause: Throwable? = null) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        shellMessages.close()
        fileMessages.close()
        scope.cancel()
        val detail = cause?.message?.let { ": $it" } ?: ""
        onClosed(id, "$reason$detail")
    }

    private fun ensureOpen(): Boolean = !socket.isClosed && socket.isConnected

    private suspend fun awaitShell(timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) { shellMessages.receive() }

    private suspend fun awaitFile(timeoutMs: Long): ByteArray? = withTimeoutOrNull(timeoutMs) { fileMessages.receive() }

    private suspend fun awaitDownloadPayload(timeoutMs: Long): ByteArray? {
        return withTimeoutOrNull(timeoutMs) {
            while (ensureOpen()) {
                val next = select<Any?> {
                    fileMessages.onReceive { it }
                    shellMessages.onReceive { it }
                }

                when (next) {
                    is ByteArray -> return@withTimeoutOrNull next
                    is String -> {
                        if (next.contains("not found", ignoreCase = true)) {
                            reportTransferError(TransferErrorCode.PROTOCOL_MISMATCH, next)
                            return@withTimeoutOrNull null
                        }
                        logWarn("[ClientSession] download ignore shell message: $next")
                    }
                }
            }
            null
        }
    }

    private suspend fun sendMessage(type: Byte, payload: ByteArray) {
        writerMutex.withLock {
            val header = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            header.putInt(payload.size)
            header.put(type)
            outputStream.write(header.array())
            if (payload.isNotEmpty()) {
                outputStream.write(payload)
            }
            outputStream.flush()
        }
    }

    private suspend fun runReadLoop() {
        try {
            while (scope.isActive && ensureOpen()) {
                val length = readInt32BE()
                if (length <= 0 || length > MAX_PAYLOAD_SIZE) {
                    reportTransferError(TransferErrorCode.INVALID_LENGTH, "invalid payload length: $length")
                    break
                }

                val type = readByte()
                val payload = readExact(length)
                when (type) {
                    TYPE_SHELL_CMD -> shellMessages.trySend(payload.toString(Charsets.UTF_8))
                    TYPE_FILE_DATA -> fileMessages.trySend(payload)
                    else -> logWarn("[ClientSession] unknown message type: 0x${type.toUByte().toString(16)}")
                }
            }
        } catch (_: EOFException) {
            // disconnected normally
        } catch (e: Exception) {
            logError("[ClientSession] read loop failed", e)
        } finally {
            close("read loop end")
        }
    }

    private fun readInt32BE(): Int {
        val bytes = readExact(4)
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun readByte(): Byte {
        val value = input.read()
        if (value < 0) throw EOFException("stream closed")
        return value.toByte()
    }

    private fun readExact(length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read <= 0) throw EOFException("stream closed")
            offset += read
        }
        return data
    }

    private fun appendOutput(line: String) {
        _output.value += "$line\n"
    }

    private fun sanitizeRemotePath(path: String): String? {
        if (path.isBlank() || !path.startsWith('/')) return null
        if (path.length > MAX_REMOTE_PATH) return null
        if (path.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        return path
    }

    private fun shellEscape(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun reportTransferError(code: TransferErrorCode, message: String) {
        lastTransferError = TransferError(code, message)
        logWarn("[ClientSession][$code] $message")
    }

    private fun logWarn(message: String) {
        FrpLogBus.append(message)
        Log.w(LOG_TAG, message)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        FrpLogBus.append(message)
        Log.e(LOG_TAG, message, throwable)
    }

    data class RegistrationInfo(
        val deviceName: String,
        val deviceId: String,
        val arch: String
    )

    data class TransferError(val code: TransferErrorCode, val message: String)

    enum class TransferErrorCode {
        TIMEOUT,
        INVALID_LENGTH,
        IO_INTERRUPTED,
        PROTOCOL_MISMATCH
    }

    sealed interface ShellEvent {
        data class OutputLine(val line: String) : ShellEvent
        data object CommandEnd : ShellEvent
    }

    enum class DownloadResult {
        Success,
        NotFound,
        Failed
    }

    sealed interface ListFilesResult {
        data class Success(val items: List<RemoteFileEntry>) : ListFilesResult
        data class Error(val message: String) : ListFilesResult
        data class Failed(val message: String) : ListFilesResult
    }

    data class RemoteFileEntry(
        val path: String,
        val file: Boolean
    )

    companion object {
        private const val DEFAULT_MANAGED_TIMEOUT_MS = 10_000L
        private const val FILE_TRANSFER_TIMEOUT_MS = 180_000L
        private const val MAX_REMOTE_PATH = 4096
        private const val MAX_PAYLOAD_SIZE = 20 * 1024 * 1024

        private const val TYPE_SHELL_CMD: Byte = 0x1
        private const val TYPE_UPLOAD_REQ: Byte = 0x2
        private const val TYPE_DOWNLOAD_REQ: Byte = 0x3
        private const val TYPE_FILE_DATA: Byte = 0x8

        private const val LOG_TAG = "ClientSession"
    }
}
