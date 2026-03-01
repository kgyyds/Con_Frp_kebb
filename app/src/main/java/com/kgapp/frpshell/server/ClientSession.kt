package com.kgapp.frpshellpro.server

import com.kgapp.frpshellpro.frp.FrpLogBus
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
    private val onClosed: (String, String) -> Unit,
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
    @Volatile
    private var closeReason: String = "unspecified"

    private var recvJob: Job? = null
    @Volatile
    private var lastProtocolReadFailure: ProtocolReadFailure? = null

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
                    logWarn("[ClientSession][executeExec] user-facing error=${result.error} clientId=$id")
                    val userReadable = userReadableExecError(result.error)
                    appendOutput("[ERROR] $userReadable")
                    _shellEvents.tryEmit(ShellEvent.OutputLine("[ERROR] $userReadable"))
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
        return result.output ?: result.error?.let { "[ERROR] ${userReadableExecError(it)}" }
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
            val context = OperationContext("uploadFile", safeRemotePath.take(80), FILE_TRANSFER_TIMEOUT_MS)
            withSocketTimeout(FILE_TRANSFER_TIMEOUT_MS, context) {
                sendJson(
                    JSONObject()
                        .put("type", "uploadfile")
                        .put("path", safeRemotePath),
                    context
                )

                localFile.inputStream().use { fileInput ->
                    sendBinary(fileInput, total) { done -> onProgress?.invoke(done, total) }
                }

                val response = readJsonFrame(context) ?: return@withSocketTimeout false
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
            val context = OperationContext("downloadFile", safeRemotePath.take(80), FILE_TRANSFER_TIMEOUT_MS)
            withSocketTimeout(FILE_TRANSFER_TIMEOUT_MS, context) {
                sendJson(
                    JSONObject()
                        .put("type", "downloadfile")
                        .put("path", safeRemotePath),
                    context
                )

                val response = readJsonFrame(context) ?: return@withSocketTimeout DownloadResult.Failed
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

                val header = readFrameHeader(contextFromJson("download.readFrameHeader", JSONObject().put("type", "downloadfile").put("path", safeRemotePath))) ?: return@withSocketTimeout DownloadResult.Failed
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
            val context = OperationContext("listFiles", safePath.take(80), DEFAULT_MANAGED_TIMEOUT_MS)
            withSocketTimeout(DEFAULT_MANAGED_TIMEOUT_MS, context) {
                sendJson(
                    JSONObject()
                        .put("type", "file")
                        .put("path", safePath),
                    context
                )

                val response = readJsonFrame(context) ?: return@withSocketTimeout ListFilesResult.Failed("invalid response")
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

    fun close(reason: String = "manual close", cause: Throwable? = null) {
        if (!closed.compareAndSet(false, true)) return
        val causeDetail = cause?.let { " (${it::class.java.simpleName}: ${it.message ?: "no message"})" } ?: ""
        closeReason = "$reason$causeDetail"
        logWarn("[ClientSession] closing id=$id reason=$closeReason")
        runCatching { socket.close() }
        recvJob?.cancel()
        scope.cancel()
        onClosed(id, closeReason)
    }

    private suspend fun executeExec(command: String, timeoutMs: Long): ExecResult {
        val commandSummary = command.take(80)
        logWarn("[ClientSession][executeExec] stage=start clientId=$id cmd=$commandSummary timeoutMs=$timeoutMs socketState=${socketState()}")
        val context = OperationContext(
            operation = "executeExec",
            commandSummary = commandSummary,
            timeoutMs = timeoutMs
        )
        return ioMutex.withLock {
            withSocketTimeout(timeoutMs, context) {
                lastProtocolReadFailure = null
                sendJson(
                    JSONObject()
                        .put("type", "exec")
                        .put("cmd", command),
                    context
                )
                logWarn("[ClientSession][executeExec] stage=request sent clientId=$id ${context.describe(socket)} socketState=${socketState()}")

                val response = readJsonFrame(context)
                if (response == null) {
                    val protocolFailure = lastProtocolReadFailure
                    val errorCode = when (protocolFailure?.throwable) {
                        is EOFException -> ExecErrorCode.EOF
                        else -> ExecErrorCode.PROTOCOL_MISMATCH
                    }
                    if (protocolFailure != null) {
                        logWarn(
                            "[ClientSession][executeExec][${errorCode.tag}] stage=${protocolFailure.stage} " +
                                "frameType=${protocolFailure.frameType?.toString() ?: "-"} frameLength=${protocolFailure.frameLength?.toString() ?: "-"} " +
                                "socketState=${socketState()} ${context.describe(socket)}"
                        )
                    } else {
                        logWarn("[ClientSession][executeExec][${errorCode.tag}] stage=read-json-null socketState=${socketState()} ${context.describe(socket)}")
                    }
                    return@withSocketTimeout ExecResult(error = formatExecError(errorCode, "invalid response"))
                }
                if (response.optString("type") != "exec") {
                    logWarn(
                        "[ClientSession][executeExec][ERR_PROTOCOL_MISMATCH] stage=type mismatch frameType=$TYPE_JSON frameLength=${response.toString().length} " +
                            "socketState=${socketState()} ${context.describe(socket)}"
                    )
                    return@withSocketTimeout ExecResult(error = formatExecError(ExecErrorCode.PROTOCOL_MISMATCH, "invalid response"))
                }

                val error = response.optString("error")
                if (error.isNotBlank()) {
                    logWarn("[ClientSession][executeExec][ERR_REMOTE] stage=remote error=$error socketState=${socketState()} ${context.describe(socket)}")
                    return@withSocketTimeout ExecResult(error = error)
                }

                ExecResult(output = response.optString("output"))
            } ?: run {
                logWarn("[ClientSession][executeExec][ERR_TIMEOUT] stage=socket timeout socketState=${socketState()} ${context.describe(socket)}")
                ExecResult(error = formatExecError(ExecErrorCode.TIMEOUT, "command timeout"))
            }
        }
    }

    private suspend fun receiveRegistration() {
        val info = ioMutex.withLock {
            val context = OperationContext("receiveRegistration", "register", REGISTRATION_TIMEOUT_MS)
            withSocketTimeout(REGISTRATION_TIMEOUT_MS, context) {
                val json = readJsonFrame(context) ?: return@withSocketTimeout null
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

    private fun sendJson(json: JSONObject, context: OperationContext? = null) {
        val resolvedContext = context ?: contextFromJson("sendJson", json)
        val payload = json.toString().toByteArray(StandardCharsets.UTF_8)
        runCatching { writeFrame(TYPE_JSON, payload, resolvedContext) }
            .onFailure {
                logError("[ClientSession] sendJson failed ${resolvedContext.describe(socket)}", it)
                throw it
            }
    }

    private fun sendBinary(payload: ByteArray) {
        writeFrame(TYPE_BINARY, payload, context = null)
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

    private fun writeFrame(type: Int, payload: ByteArray, context: OperationContext? = null) {
        require(payload.size <= Int.MAX_VALUE)
        runCatching {
            outputStream.writeInt(payload.size)
            outputStream.writeByte(type)
            outputStream.write(payload)
            outputStream.flush()
        }.onFailure {
            logError("[ClientSession] writeFrame failed type=$type ${context.describe(socket)}", it)
            throw it
        }
    }

    private fun readJsonFrame(context: OperationContext? = null): JSONObject? {
        val header = readFrameHeader(context) ?: return null
        if (header.type != TYPE_JSON) {
            reportTransferError(TransferErrorCode.PROTOCOL_MISMATCH, "expected json frame, got ${header.type}")
            lastProtocolReadFailure = ProtocolReadFailure(
                stage = "type mismatch",
                frameType = header.type,
                frameLength = header.length
            )
            skipBytes(header.length)
            return null
        }
        val payload = ByteArray(header.length)
        return runCatching {
            input.readFully(payload)
            JSONObject(String(payload, StandardCharsets.UTF_8))
        }.onFailure {
            lastProtocolReadFailure = ProtocolReadFailure(
                stage = "json parse",
                frameType = header.type,
                frameLength = header.length,
                throwable = it
            )
            logError("[ClientSession] readJsonFrame payload failed ${context.describe(socket)}", it)
            reportTransferError(TransferErrorCode.IO_INTERRUPTED, "read json payload failed: ${it.message}")
        }.getOrNull()
    }

    private fun readFrameHeader(context: OperationContext? = null): FrameHeader? {
        return try {
            val length = input.readInt()
            if (length < 0) {
                lastProtocolReadFailure = ProtocolReadFailure(stage = "header", frameLength = length)
                reportTransferError(TransferErrorCode.INVALID_LENGTH, "negative frame length: $length")
                return null
            }
            val type = input.readUnsignedByte()
            val max = if (type == TYPE_JSON) MAX_JSON_FRAME_SIZE else maxBinaryFrameSize
            if (length > max) {
                lastProtocolReadFailure = ProtocolReadFailure(stage = "header", frameType = type, frameLength = length)
                reportTransferError(TransferErrorCode.INVALID_LENGTH, "frame length overflow, type=$type length=$length max=$max")
                return null
            }
            FrameHeader(type = type, length = length)
        } catch (e: SocketTimeoutException) {
            lastProtocolReadFailure = ProtocolReadFailure(stage = "header", throwable = e)
            close("readFrameHeader timeout ${e::class.java.simpleName}: ${e.message ?: "no message"} ${context.describe(socket)}", e)
            null
        } catch (e: EOFException) {
            lastProtocolReadFailure = ProtocolReadFailure(stage = "header", throwable = e)
            close("readFrameHeader EOF ${e::class.java.simpleName}: ${e.message ?: "no message"} ${context.describe(socket)}", e)
            null
        } catch (e: Exception) {
            lastProtocolReadFailure = ProtocolReadFailure(stage = "header", throwable = e)
            close("readFrameHeader exception ${e::class.java.simpleName}: ${e.message ?: "no message"} ${context.describe(socket)}", e)
            null
        }
    }

    private fun socketState(): String {
        return "closed=${socket.isClosed}, connected=${socket.isConnected}"
    }

    private fun formatExecError(code: ExecErrorCode, message: String): String = "${code.tag}: $message"

    private fun userReadableExecError(error: String): String {
        return when {
            error.startsWith("${ExecErrorCode.TIMEOUT.tag}:") -> "命令执行超时"
            error.startsWith("${ExecErrorCode.PROTOCOL_MISMATCH.tag}:") -> "远端响应异常"
            error.startsWith("${ExecErrorCode.EOF.tag}:") -> "连接已中断"
            else -> error
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
        logWarn(text)
    }

    private fun <T> withSocketTimeout(timeoutMs: Long, context: OperationContext? = null, block: () -> T): T? {
        val previous = socket.soTimeout
        return try {
            socket.soTimeout = timeoutMs.toInt()
            block()
        } catch (e: SocketTimeoutException) {
            logWarn("[ClientSession] socket timeout ${context.describe(socket)}")
            null
        } finally {
            runCatching { socket.soTimeout = previous }
        }
    }

    private fun contextFromJson(operation: String, json: JSONObject): OperationContext {
        val summary = when {
            json.has("cmd") -> json.optString("cmd")
            json.has("path") -> "${json.optString("type")}:${json.optString("path")}"
            else -> json.optString("type", "unknown")
        }.take(80)
        return OperationContext(
            operation = operation,
            commandSummary = summary,
            timeoutMs = socket.soTimeout.toLong()
        )
    }

    private fun logWarn(message: String) {
        FrpLogBus.append(message)
        Log.w(LOG_TAG, message)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        val detail = throwable?.let { " (${it::class.java.simpleName}: ${it.message ?: "no message"})" } ?: ""
        val text = "$message$detail"
        FrpLogBus.append(text)
        Log.e(LOG_TAG, text, throwable)
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
            val context = OperationContext("requestDeviceInfo", "info", timeoutMs)
            withSocketTimeout(timeoutMs, context) {
                sendJson(JSONObject().put("type", "info"), context)
                val response = readJsonFrame(context)
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

    private data class ProtocolReadFailure(
        val stage: String,
        val frameType: Int? = null,
        val frameLength: Int? = null,
        val throwable: Throwable? = null
    )

    private enum class ExecErrorCode(val tag: String) {
        TIMEOUT("ERR_TIMEOUT"),
        PROTOCOL_MISMATCH("ERR_PROTOCOL_MISMATCH"),
        EOF("ERR_EOF")
    }

    private data class OperationContext(
        val operation: String,
        val commandSummary: String,
        val timeoutMs: Long
    )

    private fun OperationContext?.describe(socket: Socket): String {
        if (this == null) {
            return "operation=unknown cmd=- timeoutMs=${socket.soTimeout} remote=${socket.remoteSocketAddress}"
        }
        return "operation=$operation cmd=${commandSummary.ifBlank { "-" }} timeoutMs=$timeoutMs remote=${socket.remoteSocketAddress}"
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
