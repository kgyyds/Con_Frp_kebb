package com.kgapp.frpshell.server

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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
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

    private val _shellEvents = MutableSharedFlow<ShellEvent>(extraBufferCapacity = 256)
    val shellEvents: SharedFlow<ShellEvent> = _shellEvents.asSharedFlow()

    private var recvJob: Job? = null
    private val closed = AtomicBoolean(false)

    private val sendMutex = Mutex()
    private val managedCommandMutex = Mutex()
    private val transferMutex = Mutex()
    private val transferStateMutex = Mutex()

    @Volatile
    private var activeCapture: CaptureState? = null

    @Volatile
    private var receiveState: ReceiveState = ReceiveState.Idle

    @Volatile
    private var transferContext: TransferContext? = null

    @Volatile
    private var uploadResponse: CompletableDeferred<Boolean>? = null

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

            val deferred = CompletableDeferred<Boolean>()
            uploadResponse = deferred
            receiveState = ReceiveState.UploadResponse

            val sentOk = sendMutex.withLock {
                runCatching {
                    val output = socket.getOutputStream()
                    val cmd = "upload ${shellEscape(safeRemotePath)}\n"
                    output.write(cmd.toByteArray(StandardCharsets.UTF_8))
                    output.write(longToBigEndian(total))
                    output.write(byteArrayOf(LINE_FEED))

                    var sent = 0L
                    val buffer = ByteArray(UPLOAD_RAW_CHUNK_SIZE)
                    localFile.inputStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            sent += read
                            onProgress?.invoke(sent, total)
                        }
                    }
                    output.flush()
                }.isSuccess
            }

            if (!sentOk) {
                uploadResponse = null
                receiveState = ReceiveState.Idle
                return@withLock false
            }

            val ack = withTimeoutOrNull(UPLOAD_ACK_TIMEOUT_MS) { deferred.await() }
            uploadResponse = null
            receiveState = ReceiveState.Idle

            if (ack == null) {
                _output.value += "[upload] no explicit confirmation, assuming success\n"
                true
            } else {
                ack
            }
        }
    }

    private fun consumeUploadResponseLine(line: String): Boolean {
        val deferred = uploadResponse ?: return false
        return when (line.trim()) {
            "UPLOAD_COMPLETE" -> {
                deferred.complete(true)
                true
            }
            "UPLOAD_FAILED" -> {
                deferred.complete(false)
                true
            }
            else -> {
                if (line == CLIENT_COMMAND_END_MARKER) {
                    return true
                }
                _output.value += "$line\n"
                false
            }
        }
    }

    suspend fun downloadFile(remotePath: String, targetFile: File, onProgress: ((Long, Long) -> Unit)? = null): DownloadResult {
        val safeRemotePath = sanitizeRemotePath(remotePath) ?: return DownloadResult.Failed
        
        return transferMutex.withLock {
            val deferred = CompletableDeferred<DownloadResult>()
            val tmpFile = File(targetFile.parentFile ?: File("."), "${targetFile.name}.part")
            tmpFile.parentFile?.mkdirs()

            // Setup state before sending command
            val ctx = TransferContext(
                targetFile = targetFile,
                tmpFile = tmpFile,
                result = deferred,
                onProgress = onProgress
            )
            transferContext = ctx
            receiveState = ReceiveState.DownloadHeader

            // Send command: download <path>
            // Note: We don't use runManagedCommand because we need to hijack the receiver stream immediately
            val cmd = "download ${shellEscape(safeRemotePath)}"
            val sent = writeLineCommand(cmd, appendErrorToOutput = true)
            if (!sent) {
                resetTransferState("send command failed", cleanupFile = true)
                return@withLock DownloadResult.Failed
            }

            // Wait for completion with timeout
            withTimeoutOrNull(TRANSFER_TIMEOUT_MS) {
                deferred.await()
            } ?: run {
                resetTransferState("timeout", cleanupFile = true)
                DownloadResult.Failed
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
                        when (receiveState) {
                            ReceiveState.Idle -> {
                                val consumed = lineAccumulator.append(temp, offset, read - offset)
                                offset += consumed
                                while (true) {
                                    val line = lineAccumulator.readLine() ?: break
                                    if (!isExecutableLine(line)) {
                                        continue
                                    }
                                    if (!consumeManagedLine(line)) {
                                        if (line == CLIENT_COMMAND_END_MARKER) {
                                            _shellEvents.tryEmit(ShellEvent.CommandEnd)
                                        } else {
                                            _output.value += "$line\n"
                                            _shellEvents.tryEmit(ShellEvent.OutputLine(line))
                                        }
                                    }
                                }
                            }

                            ReceiveState.DownloadHeader -> {
                                offset += consumeDownloadHeader(temp, offset, read - offset)
                            }

                            ReceiveState.DownloadBody -> {
                                offset += consumeDownloadBody(temp, offset, read - offset)
                            }

                            ReceiveState.UploadResponse -> {
                                val consumed = lineAccumulator.append(temp, offset, read - offset)
                                offset += consumed
                                while (true) {
                                    val line = lineAccumulator.readLine() ?: break
                                    consumeUploadResponseLine(line)
                                }
                            }
                        }
                    }
                }
            }.onFailure {
                resetTransferState(it.message ?: "receiver error", cleanupFile = true)
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
        private const val DEFAULT_MANAGED_TIMEOUT_MS = 10_000L
        private const val FILE_CHUNK_SIZE = 4096
        private const val UPLOAD_RAW_CHUNK_SIZE = 16384
        private const val DOWNLOAD_RAW_CHUNK_SIZE = 3072
        // Increased max line length to handle command echoes and long paths
        private const val MAX_LINE = 8192
        private const val MAX_REMOTE_PATH = 4096
        private const val LINE_FEED: Byte = 0x0A
        private const val CLIENT_COMMAND_END_MARKER = "<<END>>"
        private const val TRANSFER_TIMEOUT_MS = 120_000L
        private const val UPLOAD_ACK_TIMEOUT_MS = 2_000L
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

    private enum class ReceiveState {
        Idle,
        DownloadHeader,
        DownloadBody,
        UploadResponse
    }

    private data class TransferContext(
        val targetFile: File,
        val tmpFile: File,
        val headerBuffer: ByteArray = ByteArray(8),
        var headerOffset: Int = 0,
        var waitSeparator: Boolean = true,
        var remaining: Long = 0,
        var outputStream: FileOutputStream? = null,
        val result: CompletableDeferred<DownloadResult>,
        val onProgress: ((Long, Long) -> Unit)? = null,
        var totalSize: Long = 0
    )

    private fun consumeDownloadHeader(buffer: ByteArray, start: Int, length: Int): Int {
        val context = transferContext ?: return length
        var offset = start
        var remain = length

        // 1. Read 8 bytes size (Big Endian)
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

        // 2. Read newline separator
        if (context.waitSeparator && remain > 0) {
            val separator = buffer[offset]
            offset += 1
            remain -= 1
            if (separator != LINE_FEED) {
                resetTransferState("invalid download separator: $separator", cleanupFile = true)
                return length // Consume all to stop processing
            }
            context.waitSeparator = false
        }

        if (context.waitSeparator) {
            return length
        }

        // 3. Parse size and prepare for body
        val size = bigEndianToLong(context.headerBuffer)
        // Check for error condition (e.g. file not found might return size 0 or specific error code if protocol defines)
        // Protocol says: 8 bytes size. If file not found, maybe server sends 0? Or maybe it doesn't send anything and we timeout?
        // Assuming 0 means empty file.
        
        context.tmpFile.parentFile?.mkdirs()
        context.outputStream = runCatching { FileOutputStream(context.tmpFile, false) }.getOrElse {
            resetTransferState("open temp file failed: ${it.message}", cleanupFile = true)
            return length // Consume all
        }
        context.remaining = size
        context.totalSize = size
        context.onProgress?.invoke(0, size)
        
        if (size == 0L) {
             completeTransferState(DownloadResult.Success, cleanupFile = false)
        } else {
             receiveState = ReceiveState.DownloadBody
        }
        
        return length - remain
    }

    private fun consumeDownloadBody(buffer: ByteArray, start: Int, length: Int): Int {
        val context = transferContext ?: return length
        if (context.remaining <= 0) {
            completeTransferState(DownloadResult.Success, cleanupFile = false)
            return 0
        }

        val writeSize = minOf(length.toLong(), context.remaining).toInt()
        runCatching {
            context.outputStream?.write(buffer, start, writeSize)
            context.remaining -= writeSize
            context.onProgress?.invoke(context.totalSize - context.remaining, context.totalSize)
        }.onFailure {
            resetTransferState("write download body failed: ${it.message}", cleanupFile = true)
            return length
        }

        if (context.remaining == 0L) {
            runCatching { context.outputStream?.flush() }
            runCatching { context.outputStream?.close() }
            context.outputStream = null
            
            if (context.tmpFile.exists() && !context.tmpFile.renameTo(context.targetFile)) {
                // Try copy and delete if rename fails (e.g. across mount points)
                val copyOk = runCatching { 
                    context.tmpFile.copyTo(context.targetFile, overwrite = true)
                    context.tmpFile.delete()
                    true
                }.getOrDefault(false)
                
                if (!copyOk) {
                    resetTransferState("rename temp file failed", cleanupFile = true)
                    return writeSize
                }
            }
            completeTransferState(DownloadResult.Success, cleanupFile = false)
        }
        return writeSize
    }

    private fun completeTransferState(result: DownloadResult, cleanupFile: Boolean) {
        val context = transferContext
        if (context != null && !context.result.isCompleted) {
            context.result.complete(result)
        }
        resetTransferState(null, cleanupFile)
    }

    private fun resetTransferState(reason: String?, cleanupFile: Boolean) {
        val context = transferContext
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
        transferContext = null
        receiveState = ReceiveState.Idle
        if (reason != null) {
            _output.value += "[transfer reset] $reason\n"
        }
    }
}
