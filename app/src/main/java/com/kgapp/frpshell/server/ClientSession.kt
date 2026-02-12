package com.kgapp.frpshell.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64
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
    private val transferStateMutex = Mutex()

    @Volatile
    private var activeCapture: CaptureState? = null

    @Volatile
    private var uploadReceiveState: UploadReceiveState = UploadReceiveState.Idle

    @Volatile
    private var uploadContext: UploadContext? = null

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

    suspend fun uploadFile(remotePath: String, localFile: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        val safeRemotePath = sanitizeRemotePath(remotePath) ?: return false
        if (!localFile.exists() || !localFile.isFile) return false

        return transferMutex.withLock {
            val total = localFile.length()
            onProgress?.invoke(0L, total)

            val escapedPath = shellEscape(safeRemotePath)
            val truncateResult = runManagedCommand(": > '$escapedPath'", timeoutMs = DEFAULT_MANAGED_TIMEOUT_MS)
            if (truncateResult == null) return@withLock false

            var sent = 0L
            val buffer = ByteArray(UPLOAD_RAW_CHUNK_SIZE)
            localFile.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val encoded = Base64.getEncoder().encodeToString(buffer.copyOf(read))
                    val ok = runManagedCommand("printf '%s' '$encoded' | base64 -d >> '$escapedPath'", timeoutMs = TRANSFER_TIMEOUT_MS)
                    if (ok == null) return@withLock false
                    sent += read
                    onProgress?.invoke(sent, total)
                }
            }

            val verify = runManagedCommand("wc -c < '$escapedPath'", timeoutMs = DEFAULT_MANAGED_TIMEOUT_MS)
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotBlank() }
                ?.toLongOrNull()

            verify == total
        }
    }

    suspend fun downloadFile(remotePath: String, targetFile: File, onProgress: ((Long, Long) -> Unit)? = null): DownloadResult {
        val safeRemotePath = sanitizeRemotePath(remotePath) ?: return DownloadResult.Failed
        return transferMutex.withLock {
            val escapedPath = shellEscape(safeRemotePath)
            val sizeOutput = runManagedCommand("if [ -f '$escapedPath' ]; then wc -c < '$escapedPath'; else echo -1; fi")
                ?: return@withLock DownloadResult.Failed
            val total = sizeOutput.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }?.toLongOrNull()
                ?: return@withLock DownloadResult.Failed
            if (total < 0) return@withLock DownloadResult.NotFound
            if (total == 0L) {
                targetFile.parentFile?.mkdirs()
                runCatching { targetFile.writeBytes(byteArrayOf()) }
                onProgress?.invoke(0L, 0L)
                return@withLock DownloadResult.Success
            }

            onProgress?.invoke(0L, total)
            val tmpFile = File(targetFile.parentFile ?: File("."), "${targetFile.name}.part")
            tmpFile.parentFile?.mkdirs()

            runCatching {
                FileOutputStream(tmpFile, false).use { output ->
                    var offset = 0L
                    while (offset < total) {
                        val count = minOf(DOWNLOAD_RAW_CHUNK_SIZE.toLong(), total - offset)
                        val cmd = "dd if='$escapedPath' bs=1 skip=$offset count=$count 2>/dev/null | base64"
                        val rawBase64 = runManagedCommand(cmd, timeoutMs = TRANSFER_TIMEOUT_MS) ?: return@withLock DownloadResult.Failed
                        val compact = rawBase64.filterNot { it == '\r' || it == '\n' || it == ' ' || it == '\t' }
                        val chunk = runCatching { Base64.getDecoder().decode(compact) }.getOrNull()
                            ?: return@withLock DownloadResult.Failed
                        if (chunk.isEmpty()) return@withLock DownloadResult.Failed
                        output.write(chunk)
                        offset += chunk.size
                        onProgress?.invoke(offset.coerceAtMost(total), total)
                    }
                    output.flush()
                }
            }.getOrElse {
                runCatching { tmpFile.delete() }
                return@withLock DownloadResult.Failed
            }

            if (!tmpFile.renameTo(targetFile)) {
                runCatching { tmpFile.delete() }
                return@withLock DownloadResult.Failed
            }

            DownloadResult.Success
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
                val input = socket.getInputStream()
                val lineAccumulator = LineAccumulator(
                    maxLineLength = MAX_LINE,
                    onLineTooLong = { _output.value += "[line dropped] command too long\n" }
                )
                val temp = ByteArray(FILE_CHUNK_SIZE)

                while (isActive) {
                    val read = input.read(temp)
                    if (read <= 0) break
                    var offset = 0
                    while (offset < read) {
                        when (uploadReceiveState) {
                            UploadReceiveState.Idle -> {
                                val consumed = lineAccumulator.append(temp, offset, read - offset)
                                offset += consumed
                                while (true) {
                                    val line = lineAccumulator.readLine() ?: break
                                    if (!isExecutableLine(line)) {
                                        continue
                                    }
                                    if (!consumeManagedLine(line)) {
                                        _output.value += "$line\n"
                                    }
                                }
                            }

                            UploadReceiveState.WaitUploadHeader -> {
                                offset += consumeUploadHeader(temp, offset, read - offset)
                            }

                            UploadReceiveState.RecvUploadBody -> {
                                offset += consumeUploadBody(temp, offset, read - offset)
                            }
                        }
                    }
                }
            }.onFailure {
                resetUploadState(it.message ?: "receiver error", cleanupFile = true)
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

    private fun isExecutableLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (line.length > MAX_LINE) return false
        return line.none { it == '\u0000' }
    }

    private fun sanitizeRemotePath(path: String): String? {
        if (path.isBlank() || !path.startsWith('/')) return null
        if (path.length > MAX_REMOTE_PATH) return null
        if (path.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        return path
    }

    private fun shellEscape(value: String): String = value.replace("'", "'\\''")

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
        private const val UPLOAD_RAW_CHUNK_SIZE = 2048
        private const val DOWNLOAD_RAW_CHUNK_SIZE = 3072
        private const val MAX_LINE = 1024
        private const val MAX_REMOTE_PATH = 4096
        private const val LINE_FEED: Byte = 0x0A
        private const val CLIENT_COMMAND_END_MARKER = "<<END>>"
        private const val TRANSFER_TIMEOUT_MS = 60_000L
    }

    private class LineAccumulator(
        private val maxLineLength: Int,
        private val onLineTooLong: () -> Unit
    ) {
        private val buffer = StringBuilder()
        private var droppingLongLine = false

        fun append(bytes: ByteArray, start: Int, length: Int): Int {
            val chars = String(bytes, start, length, StandardCharsets.UTF_8)
            chars.forEach { ch ->
                if (droppingLongLine) {
                    if (ch == '\n') {
                        droppingLongLine = false
                    }
                    return@forEach
                }

                buffer.append(ch)
                if (buffer.length > maxLineLength && !buffer.contains("\n")) {
                    buffer.clear()
                    droppingLongLine = true
                    onLineTooLong()
                }
            }
            return length
        }

        fun readLine(): String? {
            val lineEnd = buffer.indexOf("\n")
            if (lineEnd < 0) {
                if (buffer.length > maxLineLength) {
                    buffer.clear()
                    droppingLongLine = true
                    onLineTooLong()
                }
                return null
            }

            if (lineEnd > maxLineLength) {
                buffer.delete(0, lineEnd + 1)
                onLineTooLong()
                return null
            }

            val line = buffer.substring(0, lineEnd).trimEnd('\r')
            buffer.delete(0, lineEnd + 1)
            return line
        }
    }

    private enum class UploadReceiveState {
        Idle,
        WaitUploadHeader,
        RecvUploadBody
    }

    private data class UploadContext(
        val targetFile: File,
        val tmpFile: File,
        val headerBuffer: ByteArray = ByteArray(8),
        var headerOffset: Int = 0,
        var waitSeparator: Boolean = true,
        var remaining: Long = 0,
        var outputStream: FileOutputStream? = null,
        val result: CompletableDeferred<DownloadResult>
    )

    private fun consumeUploadHeader(buffer: ByteArray, start: Int, length: Int): Int {
        val context = uploadContext ?: return length
        var offset = start
        var remain = length

        if (context.headerOffset < 8) {
            val copy = minOf(8 - context.headerOffset, remain)
            System.arraycopy(buffer, offset, context.headerBuffer, context.headerOffset, copy)
            context.headerOffset += copy
            offset += copy
            remain -= copy
        }

        if (context.headerOffset < 8) {
            return length
        }

        if (context.waitSeparator && remain > 0) {
            val separator = buffer[offset]
            offset += 1
            remain -= 1
            if (separator != LINE_FEED) {
                resetUploadState("invalid upload separator", cleanupFile = true)
                return length
            }
            context.waitSeparator = false
        }

        if (context.waitSeparator) {
            return length
        }

        val size = bigEndianToLong(context.headerBuffer)
        if (size <= 0L) {
            val result = if (size == 0L) DownloadResult.NotFound else DownloadResult.Failed
            completeUploadState(result, cleanupFile = true)
            return length - remain
        }

        context.tmpFile.parentFile?.mkdirs()
        context.outputStream = runCatching { context.tmpFile.outputStream() }.getOrElse {
            resetUploadState("open temp file failed: ${it.message}", cleanupFile = true)
            return length - remain
        }
        context.remaining = size
        uploadReceiveState = UploadReceiveState.RecvUploadBody
        return length - remain
    }

    private fun consumeUploadBody(buffer: ByteArray, start: Int, length: Int): Int {
        val context = uploadContext ?: return length
        if (context.remaining <= 0) {
            completeUploadState(DownloadResult.Success, cleanupFile = false)
            return 0
        }

        val writeSize = minOf(length.toLong(), context.remaining).toInt()
        runCatching {
            context.outputStream?.write(buffer, start, writeSize)
            context.remaining -= writeSize
        }.onFailure {
            resetUploadState("write upload body failed: ${it.message}", cleanupFile = true)
            return length
        }

        if (context.remaining == 0L) {
            runCatching { context.outputStream?.flush() }
            runCatching { context.outputStream?.close() }
            context.outputStream = null
            if (!context.tmpFile.renameTo(context.targetFile)) {
                resetUploadState("rename temp file failed", cleanupFile = true)
                return writeSize
            }
            completeUploadState(DownloadResult.Success, cleanupFile = false)
        }
        return writeSize
    }

    private fun completeUploadState(result: DownloadResult, cleanupFile: Boolean) {
        val context = uploadContext
        if (context != null && !context.result.isCompleted) {
            context.result.complete(result)
        }
        resetUploadState(null, cleanupFile)
    }

    private fun resetUploadState(reason: String?, cleanupFile: Boolean) {
        val context = uploadContext
        if (context != null) {
            runCatching { context.outputStream?.close() }
            context.outputStream = null
            if (cleanupFile) {
                runCatching { context.tmpFile.delete() }
            }
            if (!context.result.isCompleted) {
                context.result.complete(DownloadResult.Failed)
            }
        }
        uploadContext = null
        uploadReceiveState = UploadReceiveState.Idle
        if (reason != null) {
            _output.value += "[transfer reset] $reason\n"
        }
    }
}
