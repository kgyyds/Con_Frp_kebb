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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.java_websocket.WebSocket
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ClientSession(
    val id: String,
    private val connection: WebSocket,
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
    private val textMessages = Channel<String>(Channel.UNLIMITED)
    private val binaryMessages = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    var lastTransferError: TransferError? = null
        private set

    fun start() {
        appendOutput("[register] $id")
    }

    fun onTextMessage(text: String) {
        if (!textMessages.trySend(text).isSuccess) {
            logWarn("[ClientSession] text queue full, drop message")
        }
    }

    fun onBinaryMessage(payload: ByteArray) {
        if (!binaryMessages.trySend(payload).isSuccess) {
            logWarn("[ClientSession] binary queue full, drop message")
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
                connection.send("CMD $command")
                val response = awaitText(timeoutMs)
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
                connection.send("UPLOAD $safeRemotePath")
                val ack = awaitText(FILE_TRANSFER_TIMEOUT_MS)
                if (!ack.equals("ok", ignoreCase = true)) {
                    reportTransferError(TransferErrorCode.PROTOCOL_MISMATCH, "UPLOAD ack invalid: $ack")
                    return@runCatching false
                }

                val data = localFile.readBytes()
                val total = data.size.toLong()
                onProgress?.invoke(0, total)
                connection.send(data)
                onProgress?.invoke(total, total)

                val done = awaitText(FILE_TRANSFER_TIMEOUT_MS)
                if (done != "UPLOAD_SUCCESS") {
                    reportTransferError(TransferErrorCode.PROTOCOL_MISMATCH, "UPLOAD result invalid: $done")
                    return@runCatching false
                }
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
                connection.send("DOWNLOAD $safeRemotePath")
                val payload = awaitBinary(FILE_TRANSFER_TIMEOUT_MS) ?: return@runCatching DownloadResult.Failed

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
        val result = runManagedCommand("ls -la $safePath", DEFAULT_MANAGED_TIMEOUT_MS) ?: return ListFilesResult.Failed("empty response")
        if (result.startsWith("[ERROR]")) return ListFilesResult.Error(result)

        val items = result.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("total") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 9)
                if (parts.size < 9) return@mapNotNull null
                val name = parts[8]
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
        runCatching { connection.close() }
        textMessages.close()
        binaryMessages.close()
        scope.cancel()
        val detail = cause?.message?.let { ": $it" } ?: ""
        onClosed(id, "$reason$detail")
    }

    private fun ensureOpen(): Boolean = connection.isOpen

    private suspend fun awaitText(timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) { textMessages.receive() }

    private suspend fun awaitBinary(timeoutMs: Long): ByteArray? = withTimeoutOrNull(timeoutMs) { binaryMessages.receive() }

    private fun appendOutput(line: String) {
        _output.value += "$line\n"
    }

    private fun sanitizeRemotePath(path: String): String? {
        if (path.isBlank() || !path.startsWith('/')) return null
        if (path.length > MAX_REMOTE_PATH) return null
        if (path.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        return path
    }

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
        private const val LOG_TAG = "ClientSession"
    }
}
