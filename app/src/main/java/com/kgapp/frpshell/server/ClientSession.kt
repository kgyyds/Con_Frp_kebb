package com.kgapp.frpshellpro.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class ClientSession(
    val id: String,
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val onClosed: (String) -> Unit,
    private val maxBinaryFrameSize: Int = DEFAULT_MAX_BINARY_FRAME_SIZE
) {
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _shellEvents = MutableSharedFlow<ShellEvent>(extraBufferCapacity = 256)
    val shellEvents: SharedFlow<ShellEvent> = _shellEvents.asSharedFlow()

    @Volatile
    var registrationInfo: RegistrationInfo? = null
        private set

    private val closed = AtomicBoolean(false)
    private val ioMutex = Mutex()

    private var recvJob: Job? = null

    private val input by lazy { DataInputStream(BufferedInputStream(socket.getInputStream())) }
    private val outputStream by lazy { DataOutputStream(BufferedOutputStream(socket.getOutputStream())) }

    fun start() {
        recvJob = scope.launch(Dispatchers.IO) {
            receiveRegistration()
        }
    }

    fun send(command: String) {
        if (command.isBlank()) return
        scope.launch(Dispatchers.IO) {
            if (!isActive) return@launch
            val result = executeExec(command, DEFAULT_MANAGED_TIMEOUT_MS)
            when {
                result.error != null -> {
                    appendOutput("[ERROR] ${result.error}")
                    _shellEvents.tryEmit(ShellEvent.OutputLine("[ERROR] ${result.error}"))
                }

                !result.output.isNullOrEmpty() -> {
                    result.output.lines().forEach { line ->
                        if (line.isBlank()) return@forEach
                        appendOutput(line)
                        _shellEvents.tryEmit(ShellEvent.OutputLine(line))
                    }
                }
            }
            _shellEvents.tryEmit(ShellEvent.CommandEnd)
        }
    }

    suspend fun runManagedCommand(command: String, timeoutMs: Long = DEFAULT_MANAGED_TIMEOUT_MS): String? {
        if (command.isBlank()) return ""
        val result = executeExec(command, timeoutMs)
        return result.output ?: result.error?.let { "[ERROR] $it" }
    }

    suspend fun uploadFile(remotePath: String, localFile: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        val safeRemotePath = sanitizeRemotePath(remotePath) ?: return false
        if (!localFile.exists() || !localFile.isFile) return false
        if (localFile.length() > Int.MAX_VALUE) {
            reportTransferError(TransferErrorCode.INVALID_LENGTH, "upload file too large: ${localFile.length()}")
            return false
        }

        return ioMutex.withLock {
            lastTransferError = null
            val total = localFile.length()
            withSocketTimeout(FILE_TRANSFER_TIMEOUT_MS) {
                sendJson(
                    JSONObject()
                        .put("type", "uploadfile")
                        .put("path", safeRemotePath)
                )

                localFile.inputStream().use { fileInput ->
                    sendBinary(fileInput, total) { done -> onProgress?.invoke(done, total) }
                }

                val response = readJsonFrame() ?: return@withSocketTimeout false
                if (response.optString("type") != "uploadfile") {
                    reportTransferError(TransferErrorCode.PROTOCOL_MISMATCH, "upload response type mismatch")
                    return@withSocketTimeout false
                }
                response.optString("error").isBlank()
            } ?: run {
                reportTransferError(TransferErrorCode.TIMEOUT, "upload timeout")
                false
            }
        }
    }

    suspend fun downloadFile(remotePath: String, targetFile: File, onProgress: ((Long, Long) -> Unit)? = null): DownloadResult {
        val safeRemotePath = sanitizeRemotePath(remotePath) ?: return DownloadResult.Failed

        return ioMutex.withLock {
            lastTransferError = null
            withSocketTimeout(FILE_TRANSFER_TIMEOUT_MS) {
                sendJson(
                    JSONObject()
                        .put("type", "downloadfile")
                        .put("path", safeRemotePath)
                )

                val response = readJsonFrame() ?: return@withSocketTimeout DownloadResult.Failed
                if (response.optString("type") != "downloadfile") {
                    return@withSocketTimeout DownloadResult.Failed
                }

                val error = response.optString("error")
                if (error.isNotBlank()) {
                    return@withSocketTimeout if (error.contains("not", ignoreCase = true)) {
                        DownloadResult.NotFound
                    } else {
                        DownloadResult.Failed
                    }
                }

                val header = readFrameHeader() ?: return@withSocketTimeout DownloadResult.Failed
                if (header.type != TYPE_BINARY) {
                    reportTransferError(TransferErrorCode.PROTOCOL_MISMATCH, "download expected binary frame, got ${header.type}")
                    skipBytes(header.length)
                    return@withSocketTimeout DownloadResult.Failed
                }

                val total = header.length.toLong()
                onProgress?.invoke(0, total)
                targetFile.parentFile?.mkdirs()
                writeFrameToFile(targetFile, header.length) { done -> onProgress?.invoke(done, total) }
                onProgress?.invoke(total, total)
                DownloadResult.Success
            } ?: run {
                reportTransferError(TransferErrorCode.TIMEOUT, "download timeout")
                DownloadResult.Failed
            }
        }
    }

    suspend fun listFiles(path: String): ListFilesResult {
        val safePath = sanitizeRemotePath(path) ?: return ListFilesResult.Failed("invalid path")

        return ioMutex.withLock {
            withSocketTimeout(DEFAULT_MANAGED_TIMEOUT_MS) {
                sendJson(
                    JSONObject()
                        .put("type", "file")
                        .put("path", safePath)
                )

                val response = readJsonFrame() ?: return@withSocketTimeout ListFilesResult.Failed("invalid response")
                if (response.optString("type") != "file") {
                    return@withSocketTimeout ListFilesResult.Failed("invalid response")
                }

                val error = response.optString("error")
                if (error.isNotBlank()) {
                    return@withSocketTimeout ListFilesResult.Error(error)
                }

                val items = parseFileItems(response.optJSONArray("items"))
                ListFilesResult.Success(items)
            } ?: ListFilesResult.Failed("command timeout")
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        recvJob?.cancel()
        scope.cancel()
        onClosed(id)
    }

    private suspend fun executeExec(command: String, timeoutMs: Long): ExecResult {
        return ioMutex.withLock {
            withSocketTimeout(timeoutMs) {
                sendJson(
                    JSONObject()
                        .put("type", "exec")
                        .put("cmd", command)
                )

                val response = readJsonFrame()
                if (response == null || response.optString("type") != "exec") {
                    return@withSocketTimeout ExecResult(error = "invalid response")
                }

                val error = response.optString("error")
                if (error.isNotBlank()) {
                    return@withSocketTimeout ExecResult(error = error)
                }

                ExecResult(output = response.optString("output"))
            } ?: ExecResult(error = "command timeout")
        }
    }

    private suspend fun receiveRegistration() {
        val info = ioMutex.withLock {
            withSocketTimeout(REGISTRATION_TIMEOUT_MS) {
                val json = readJsonFrame() ?: return@withSocketTimeout null
                if (json.optString("type") != "register") return@withSocketTimeout null
                RegistrationInfo(
                    deviceName = json.optString("device_name", id),
                    deviceId = json.optString("device_id", id),
                    arch = json.optString("arch", "unknown")
                )
            }
        }

        if (info != null) {
            registrationInfo = info
            appendOutput("[register] ${info.deviceName} (${info.deviceId})")
        }
    }

    private fun sendJson(json: JSONObject) {
        val payload = json.toString().toByteArray(StandardCharsets.UTF_8)
        writeFrame(TYPE_JSON, payload)
    }

    private fun sendBinary(payload: ByteArray) {
        writeFrame(TYPE_BINARY, payload)
    }

    private fun sendBinary(input: InputStream, length: Long, onProgress: ((Long) -> Unit)? = null) {
        if (length < 0 || length > Int.MAX_VALUE) {
            reportTransferError(TransferErrorCode.INVALID_LENGTH, "binary frame length invalid: $length")
            throw IllegalArgumentException("invalid binary length: $length")
        }

        outputStream.writeInt(length.toInt())
        outputStream.writeByte(TYPE_BINARY)

        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var written = 0L
        while (written < length) {
            val toRead = minOf(buffer.size.toLong(), length - written).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) {
                reportTransferError(TransferErrorCode.IO_INTERRUPTED, "input stream ended before expected length")
                throw EOFException("unexpected EOF while sending binary frame")
            }
            outputStream.write(buffer, 0, read)
            written += read
            onProgress?.invoke(written)
        }
        outputStream.flush()
    }

    private fun writeFrame(type: Int, payload: ByteArray) {
        require(payload.size <= Int.MAX_VALUE)
        outputStream.writeInt(payload.size)
        outputStream.writeByte(type)
        outputStream.write(payload)
        outputStream.flush()
    }

    private fun readJsonFrame(): JSONObject? {
        val header = readFrameHeader() ?: return null
        if (header.type != TYPE_JSON) {
            reportTransferError(TransferErrorCode.PROTOCOL_MISMATCH, "expected json frame, got ${header.type}")
            skipBytes(header.length)
            return null
        }
        val payload = ByteArray(header.length)
        return runCatching {
            input.readFully(payload)
            JSONObject(String(payload, StandardCharsets.UTF_8))
        }.onFailure {
            reportTransferError(TransferErrorCode.IO_INTERRUPTED, "read json payload failed: ${it.message}")
        }.getOrNull()
    }

    private fun readFrameHeader(): FrameHeader? {
        return try {
            val length = input.readInt()
            if (length < 0) {
                reportTransferError(TransferErrorCode.INVALID_LENGTH, "negative frame length: $length")
                return null
            }
            val type = input.readUnsignedByte()
            val max = if (type == TYPE_JSON) MAX_JSON_FRAME_SIZE else maxBinaryFrameSize
            if (length > max) {
                reportTransferError(TransferErrorCode.INVALID_LENGTH, "frame length overflow, type=$type length=$length max=$max")
                return null
            }
            FrameHeader(type = type, length = length)
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: EOFException) {
            close()
            null
        } catch (_: Exception) {
            close()
            null
        }
    }

    private fun writeFrameToFile(targetFile: File, length: Int, onProgress: ((Long) -> Unit)? = null) {
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var remaining = length
        var done = 0L
        targetFile.outputStream().use { fileOut ->
            while (remaining > 0) {
                val chunkSize = minOf(buffer.size, remaining)
                val read = input.read(buffer, 0, chunkSize)
                if (read <= 0) {
                    reportTransferError(TransferErrorCode.IO_INTERRUPTED, "download frame interrupted")
                    throw EOFException("unexpected EOF while reading binary payload")
                }
                fileOut.write(buffer, 0, read)
                remaining -= read
                done += read
                onProgress?.invoke(done)
            }
        }
    }

    private fun skipBytes(length: Int) {
        var remaining = length
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size))
            if (read <= 0) return
            remaining -= read
        }
    }

    private fun reportTransferError(code: TransferErrorCode, message: String) {
        lastTransferError = TransferError(code, message)
        val text = "[$TRANSFER_LOG_TAG][${code.name}] $message"
        appendOutput(text)
        Log.w(LOG_TAG, text)
    }

    private fun <T> withSocketTimeout(timeoutMs: Long, block: () -> T): T? {
        val previous = socket.soTimeout
        return try {
            socket.soTimeout = timeoutMs.toInt()
            block()
        } catch (_: SocketTimeoutException) {
            null
        } finally {
            runCatching { socket.soTimeout = previous }
        }
    }

    private fun appendOutput(line: String) {
        _output.value += "$line\n"
    }

    private fun parseFileItems(items: JSONArray?): List<RemoteFileEntry> {
        if (items == null) return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val json = items.optJSONObject(index) ?: continue
                val path = json.optString("path")
                if (path.isBlank()) continue

                val type = json.optString("type").lowercase()
                val file = when {
                    type == "file" -> true
                    type == "path" -> false
                    json.has("file") -> json.optBoolean("file", true)
                    else -> continue
                }

                add(RemoteFileEntry(path = path, file = file))
            }
        }
    }

    private fun sanitizeRemotePath(path: String): String? {
        if (path.isBlank() || !path.startsWith('/')) return null
        if (path.length > MAX_REMOTE_PATH) return null
        if (path.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        return path
    }

    suspend fun requestDeviceInfo(timeoutMs: Long = 5000L): JSONObject? {
        return ioMutex.withLock {
            withSocketTimeout(timeoutMs) {
                sendJson(JSONObject().put("type", "info"))
                val response = readJsonFrame()
                if (response == null || response.optString("type") != "info") {
                    return@withSocketTimeout JSONObject().apply {
                        put("type", "info")
                        put("error", "Invalid response from client")
                    }
                }
                response
            }
        }
    }


    data class RegistrationInfo(
        val deviceName: String,
        val deviceId: String,
        val arch: String
    )

    @Volatile
    var lastTransferError: TransferError? = null
        private set

    data class TransferError(val code: TransferErrorCode, val message: String)

    enum class TransferErrorCode {
        TIMEOUT,
        INVALID_LENGTH,
        IO_INTERRUPTED,
        PROTOCOL_MISMATCH
    }

    private data class FrameHeader(val type: Int, val length: Int)

    private data class ExecResult(
        val output: String? = null,
        val error: String? = null
    )

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
        private const val TYPE_JSON = 0x01
        private const val TYPE_BINARY = 0x02

        private const val DEFAULT_MANAGED_TIMEOUT_MS = 10_000L
        private const val FILE_TRANSFER_TIMEOUT_MS = 180_000L
        private const val REGISTRATION_TIMEOUT_MS = 5_000L
        private const val MAX_REMOTE_PATH = 4096
        private const val MAX_JSON_FRAME_SIZE = 1024 * 1024
        private const val DEFAULT_MAX_BINARY_FRAME_SIZE = 256 * 1024 * 1024
        private const val STREAM_BUFFER_SIZE = 8 * 1024
        private const val TRANSFER_LOG_TAG = "ClientTransfer"
        private const val LOG_TAG = "ClientSession"
    }
}
