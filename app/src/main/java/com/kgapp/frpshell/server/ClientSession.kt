package com.kgapp.frpshellpro.server

import kotlinx.coroutines.CompletableDeferred
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
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class ClientSession(
    val id: String,
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val onClosed: (String) -> Unit
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
            appendOutput("[upload failed] file too large")
            return false
        }

        return ioMutex.withLock {
            val total = localFile.length()
            withSocketTimeout(FILE_TRANSFER_TIMEOUT_MS) {
                sendJson(
                    JSONObject()
                        .put("type", "uploadfile")
                        .put("path", safeRemotePath)
                )

                val payload = localFile.readBytes()
                sendBinary(payload)
                onProgress?.invoke(total, total)

                val response = readJsonFrame() ?: return@withSocketTimeout false
                if (response.optString("type") != "uploadfile") return@withSocketTimeout false
                response.optString("error").isBlank()
            } ?: false
        }
    }

    suspend fun downloadFile(remotePath: String, targetFile: File, onProgress: ((Long, Long) -> Unit)? = null): DownloadResult {
        val safeRemotePath = sanitizeRemotePath(remotePath) ?: return DownloadResult.Failed

        return ioMutex.withLock {
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

                val frame = readFrame() ?: return@withSocketTimeout DownloadResult.Failed
                if (frame.type != TYPE_BINARY) return@withSocketTimeout DownloadResult.Failed

                val total = frame.payload.size.toLong()
                onProgress?.invoke(0, total)
                targetFile.parentFile?.mkdirs()
                targetFile.outputStream().use { it.write(frame.payload) }
                onProgress?.invoke(total, total)
                DownloadResult.Success
            } ?: DownloadResult.Failed
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

    private fun writeFrame(type: Int, payload: ByteArray) {
        require(payload.size <= Int.MAX_VALUE)
        outputStream.writeInt(payload.size)
        outputStream.writeByte(type)
        outputStream.write(payload)
        outputStream.flush()
    }

    private fun readJsonFrame(): JSONObject? {
        val frame = readFrame() ?: return null
        if (frame.type != TYPE_JSON) return null
        return runCatching { JSONObject(String(frame.payload, StandardCharsets.UTF_8)) }.getOrNull()
    }

    private fun readFrame(): Frame? {
        return try {
            val length = input.readInt()
            if (length < 0 || length > MAX_FRAME_SIZE) return null
            val type = input.readUnsignedByte()
            val payload = ByteArray(length)
            input.readFully(payload)
            Frame(type = type, payload = payload)
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

    private fun sanitizeRemotePath(path: String): String? {
        if (path.isBlank() || !path.startsWith('/')) return null
        if (path.length > MAX_REMOTE_PATH) return null
        if (path.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        return path
    }

    data class RegistrationInfo(
        val deviceName: String,
        val deviceId: String,
        val arch: String
    )

    private data class Frame(val type: Int, val payload: ByteArray)

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

    companion object {
        private const val TYPE_JSON = 0x01
        private const val TYPE_BINARY = 0x02

        private const val DEFAULT_MANAGED_TIMEOUT_MS = 10_000L
        private const val FILE_TRANSFER_TIMEOUT_MS = 180_000L
        private const val REGISTRATION_TIMEOUT_MS = 5_000L
        private const val MAX_REMOTE_PATH = 4096
        private const val MAX_FRAME_SIZE = 32 * 1024 * 1024
    }
}
