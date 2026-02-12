package com.kgapp.frpshell.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ClientSession(
    val id: String,
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val onClosed: (String) -> Unit
) {
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private var recvJob: Job? = null
    private val closed = AtomicBoolean(false)

    private val sendMutex = Mutex()
    private val managedCommandMutex = Mutex()
    private val transferMutex = Mutex()

    @Volatile
    private var activeCapture: CaptureState? = null

    fun start() {
        startReceiver()
    }

    fun send(command: String) {
        if (command.isBlank()) return
        scope.launch(Dispatchers.IO) {
            if (!isActive) return@launch
            writeLineCommand(command, appendErrorToOutput = true)
        }
    }

    suspend fun runManagedCommand(command: String, timeoutMs: Long = DEFAULT_MANAGED_TIMEOUT_MS): String? {
        if (command.isBlank()) return ""

        return managedCommandMutex.withLock {
            val token = UUID.randomUUID().toString().replace("-", "")
            val beginMarker = "__FRPSHELL_BEGIN_${token}__"
            val endMarker = "__FRPSHELL_END_${token}__"
            val deferred = CompletableDeferred<String?>()

            activeCapture = CaptureState(beginMarker = beginMarker, endMarker = endMarker, result = deferred)
            val wrapped = "echo '$beginMarker'; $command; echo '$endMarker'"
            val sent = writeLineCommand(wrapped, appendErrorToOutput = false)
            if (!sent) {
                activeCapture = null
                deferred.complete(null)
                return@withLock null
            }

            withTimeoutOrNull(timeoutMs) { deferred.await() }.also {
                if (it == null) activeCapture = null
            }
        }
    }

    suspend fun uploadFile(remotePath: String, localFile: File): Boolean {
        if (!remotePath.startsWith("/")) return false
        if (!localFile.exists() || !localFile.isFile) return false

        return transferMutex.withLock {
            pauseReceiverForTransfer {
                val size = localFile.length()
                val out = socket.getOutputStream()

                sendMutex.withLock {
                    out.write("upload $remotePath\n".toByteArray(StandardCharsets.UTF_8))
                    out.write(longToBigEndian(size))
                    out.write(LINE_FEED.toInt())
                    localFile.inputStream().use { input ->
                        val buffer = ByteArray(FILE_CHUNK_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                    out.flush()
                }
                true
            }
        }
    }

    suspend fun downloadFile(remotePath: String, targetFile: File): DownloadResult {
        if (!remotePath.startsWith("/")) return DownloadResult.Failed
        return transferMutex.withLock {
            pauseReceiverForTransfer {
                val input = socket.getInputStream()
                val out = socket.getOutputStream()

                sendMutex.withLock {
                    out.write("download $remotePath\n".toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }

                val sizeBytes = readExact(input, 8) ?: return@pauseReceiverForTransfer DownloadResult.Failed
                val separator = input.read()
                if (separator != LINE_FEED.toInt()) return@pauseReceiverForTransfer DownloadResult.Failed
                val size = bigEndianToLong(sizeBytes)
                if (size <= 0L) {
                    return@pauseReceiverForTransfer DownloadResult.NotFound
                }

                targetFile.parentFile?.mkdirs()
                targetFile.outputStream().use { fileOut ->
                    var remain = size
                    val buffer = ByteArray(FILE_CHUNK_SIZE)
                    while (remain > 0) {
                        val want = minOf(buffer.size.toLong(), remain).toInt()
                        val read = input.read(buffer, 0, want)
                        if (read <= 0) return@pauseReceiverForTransfer DownloadResult.Failed
                        fileOut.write(buffer, 0, read)
                        remain -= read
                    }
                    fileOut.flush()
                }
                DownloadResult.Success
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        recvJob?.cancel()
        scope.cancel()
        onClosed(id)
    }

    private fun startReceiver() {
        recvJob = scope.launch(Dispatchers.IO) {
            runCatching {
                socket.getInputStream().bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line == CLIENT_COMMAND_END_MARKER) return@forEach
                        if (!consumeManagedLine(line)) {
                            _output.value += "$line\n"
                        }
                    }
                }
            }.onFailure {
                if (!closed.get()) {
                    _output.value += "[session closed] ${it.message ?: "unknown"}\n"
                }
            }
            if (!closed.get()) close()
        }
    }

    private suspend fun writeLineCommand(command: String, appendErrorToOutput: Boolean): Boolean {
        return sendMutex.withLock {
            runCatching {
                socket.getOutputStream().bufferedWriter().apply {
                    write(command)
                    newLine()
                    flush()
                }
            }.onFailure {
                if (appendErrorToOutput) {
                    _output.value += "[send failed] ${it.message ?: "unknown"}\n"
                }
            }.isSuccess
        }
    }

    private suspend fun <T> pauseReceiverForTransfer(block: suspend () -> T): T {
        recvJob?.cancelAndJoin()
        recvJob = null
        return try {
            block()
        } finally {
            if (!closed.get()) {
                startReceiver()
            }
        }
    }

    private fun consumeManagedLine(line: String): Boolean {
        val capture = activeCapture ?: return false

        if (line == CLIENT_COMMAND_END_MARKER) {
            return true
        }

        if (!capture.started) {
            if (line == capture.beginMarker) {
                capture.started = true
                return true
            }
            return false
        }

        if (line == capture.endMarker) {
            activeCapture = null
            capture.result.complete(capture.buffer.toString())
            return true
        }

        capture.buffer.append(line).append('\n')
        return true
    }

    private fun readExact(input: InputStream, size: Int): ByteArray? {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(out, offset, size - offset)
            if (read <= 0) return null
            offset += read
        }
        return out
    }

    private fun longToBigEndian(value: Long): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(value)
        return buffer.array()
    }

    private fun bigEndianToLong(bytes: ByteArray): Long {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).long
    }

    private data class CaptureState(
        val beginMarker: String,
        val endMarker: String,
        val result: CompletableDeferred<String?>,
        val buffer: StringBuilder = StringBuilder(),
        var started: Boolean = false
    )

    enum class DownloadResult {
        Success,
        NotFound,
        Failed
    }

    companion object {
        private const val DEFAULT_MANAGED_TIMEOUT_MS = 10_000L
        private const val FILE_CHUNK_SIZE = 4096
        private const val LINE_FEED: Byte = 0x0A
        private const val CLIENT_COMMAND_END_MARKER = "<<END>>"
    }
}
