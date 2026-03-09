
package com.kgapp.frpshellpro.server

import android.util.Log
import com.kgapp.frpshellpro.frp.FrpLogBus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.*
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

// ============================================================
//  协议常量（与 C++ 端严格对齐）
// ============================================================
private const val MAGIC: Int           = 0x52535448   // "RSTH"
private const val MAX_PAYLOAD: Int     = 4 * 1024 * 1024
private const val HEARTBEAT_SEC: Long  = 5_000L
private const val HEARTBEAT_TIMEOUT: Long = 15_000L

private const val TYPE_HEARTBEAT:     Byte = 0x01
private const val TYPE_HEARTBEAT_ACK: Byte = 0x02
private const val TYPE_ACK:           Byte = 0x03
private const val TYPE_ERROR:         Byte = 0x04

private const val TYPE_CMD_REQ:       Byte = 0x10
private const val TYPE_CMD_RESP:      Byte = 0x11

private const val TYPE_FILE_OPEN:     Byte = 0x20
private const val TYPE_FILE_CHUNK:    Byte = 0x21
private const val TYPE_FILE_CLOSE:    Byte = 0x22
private const val TYPE_FILE_GET:      Byte = 0x23

private const val FLAG_NONE:     Byte = 0x00
private const val FLAG_NEED_ACK: Byte = 0x01

// ============================================================
//  帧结构
//  | 4B magic | 4B seq | 1B type | 1B flags | 2B reserved |
//  | 4B payload_len | payload | 4B crc32 |
//  header = 16 bytes
// ============================================================
private const val HEADER_SIZE = 16

data class Frame(
    val seq: Long,
    val type: Byte,
    val flags: Byte,
    val payload: ByteArray
)

// ============================================================
//  CRC32 工具（Java 内置，与 C++ 查表结果一致）
// ============================================================
private fun crc32Of(vararg blocks: ByteArray): Long {
    val crc = CRC32()
    blocks.forEach { crc.update(it) }
    return crc.value  // unsigned 32-bit as Long
}

// ============================================================
//  Connection：单条 TCP 连接上的帧收发（线程安全）
// ============================================================
class Connection(private val socket: Socket) {
    private val input  = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val writeMu = Mutex()
    private var txSeq: Long = 0

    val isOpen: Boolean get() = !socket.isClosed && socket.isConnected

    suspend fun sendFrame(type: Byte, flags: Byte, payload: ByteArray = ByteArray(0)): Boolean {
        if (!isOpen) return false
        if (payload.size > MAX_PAYLOAD) return false
        return writeMu.withLock {
            runCatching {
                val seq = txSeq++
                val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(MAGIC)
                    putInt(seq.toInt())
                    put(type)
                    put(flags)
                    putShort(0)                  // reserved
                    putInt(payload.size)
                }.array()

                // CRC 覆盖 header + payload
                val crc = crc32Of(header, payload)
                val crcBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(crc.toInt())
                    .array()

                output.write(header)
                if (payload.isNotEmpty()) output.write(payload)
                output.write(crcBytes)
                output.flush()
                true
            }.getOrElse { false }
        }
    }

    // 阻塞读取一帧（在 IO 线程调用）
    fun recvFrame(): Frame? {
        return runCatching {
            val headerBuf = readExact(HEADER_SIZE)
            val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.BIG_ENDIAN)

            val magic      = bb.int
            val seq        = bb.int.toLong() and 0xFFFFFFFFL
            val type       = bb.get()
            val flags      = bb.get()
            /*reserved*/     bb.short
            val payloadLen = bb.int

            if (magic != MAGIC) {
                Log.e("Connection", "魔数不匹配: 0x${magic.toString(16)}")
                return null
            }
            if (payloadLen < 0 || payloadLen > MAX_PAYLOAD) {
                Log.e("Connection", "payload 超限: $payloadLen")
                return null
            }

            val payload = if (payloadLen > 0) readExact(payloadLen) else ByteArray(0)
            val crcBuf  = readExact(4)
            val recvCrc = ByteBuffer.wrap(crcBuf).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL

            // 校验 CRC（用同样的 header 原始字节）
            val calcCrc = crc32Of(headerBuf, payload)
            if (calcCrc != recvCrc) {
                Log.e("Connection", "CRC 校验失败，丢弃帧 seq=$seq")
                return null
            }

            Frame(seq, type, flags, payload)
        }.getOrElse { null }
    }

    fun close() = runCatching { socket.close() }

    private fun readExact(len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n <= 0) throw EOFException("stream closed")
            off += n
        }
        return buf
    }
}

// ============================================================
//  ClientSession（命令通道）
// ============================================================
class ClientSession(
    val id: String,
    cmdSocket: Socket,
    private val fileSocket: Socket,          // 独立文件通道
    private val scope: CoroutineScope,
    private val onClosed: (String, String) -> Unit
) {
    private val cmdConn  = Connection(cmdSocket)
    private val fileConn = Connection(fileSocket)

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _shellEvents = MutableSharedFlow<ShellEvent>(extraBufferCapacity = 256)
    val shellEvents: SharedFlow<ShellEvent> = _shellEvents.asSharedFlow()

    @Volatile
    var registrationInfo: RegistrationInfo? = RegistrationInfo(id, id, "unknown")
        private set

    private val closed       = AtomicBoolean(false)
    private val cmdMutex     = Mutex()           // 保证命令串行
    private val lastPongMs   = AtomicLong(System.currentTimeMillis())

    // 命令结果通道
    private val cmdRespChannel = Channel<String>(Channel.UNLIMITED)

    @Volatile
    var lastTransferError: TransferError? = null
        private set

    // --------------------------------------------------------
    //  启动
    // --------------------------------------------------------
    fun start() {
        appendOutput("[connected] $id")
        scope.launch(Dispatchers.IO) { runCmdReadLoop() }
        scope.launch(Dispatchers.IO) { runFileReadLoop() }
        scope.launch(Dispatchers.IO) { runHeartbeat() }
    }

    // --------------------------------------------------------
    //  命令执行
    // --------------------------------------------------------
    fun send(command: String) {
        if (command.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val result = runManagedCommand(command)
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

    suspend fun runManagedCommand(
        command: String,
        timeoutMs: Long = DEFAULT_CMD_TIMEOUT_MS
    ): String? = cmdMutex.withLock {
        if (command.isBlank()) return@withLock ""
        if (!cmdConn.isOpen) return@withLock "[ERROR] connection closed"
        runCatching {
            cmdConn.sendFrame(TYPE_CMD_REQ, FLAG_NONE, command.toByteArray())
            withTimeoutOrNull(timeoutMs) { cmdRespChannel.receive() }
                ?: "[ERROR] command timeout"
        }.getOrElse { "[ERROR] ${it.message}" }
    }

    // --------------------------------------------------------
    //  文件上传（服务端 → 客户端）：走文件通道，分片+CRC
    // --------------------------------------------------------
    suspend fun uploadFile(
        remotePath: String,
        localFile: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean {
        val safePath = sanitizeRemotePath(remotePath) ?: return false
        if (!localFile.exists() || !localFile.isFile) return false

        return runCatching {
            lastTransferError = null
            val total     = localFile.length()
            val chunkSize = 65536
            val chunks    = ((total + chunkSize - 1) / chunkSize).toInt()

            // FILE_OPEN: path\0 + totalSize(8B) + totalChunks(4B)
            val pathBytes = safePath.toByteArray()
            val openPayload = ByteBuffer.allocate(pathBytes.size + 1 + 8 + 4)
                .put(pathBytes).put(0)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(total)
                .putInt(chunks)
                .array()
            fileConn.sendFrame(TYPE_FILE_OPEN, FLAG_NONE, openPayload)

            onProgress?.invoke(0, total)
            val crc32 = CRC32()
            var sent  = 0L
            var idx   = 0

            localFile.inputStream().buffered().use { fis ->
                val buf = ByteArray(chunkSize)
                var n: Int
                while (fis.read(buf).also { n = it } > 0) {
                    crc32.update(buf, 0, n)
                    // chunk payload: idx(4B) + data
                    val chunkPayload = ByteBuffer.allocate(4 + n)
                        .order(ByteOrder.BIG_ENDIAN)
                        .putInt(idx++)
                        .put(buf, 0, n)
                        .array()
                    fileConn.sendFrame(TYPE_FILE_CHUNK, FLAG_NONE, chunkPayload)
                    sent += n
                    onProgress?.invoke(sent, total)
                }
            }

            // FILE_CLOSE: crc32(4B)
            val closePayload = ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(crc32.value.toInt())
                .array()
            fileConn.sendFrame(TYPE_FILE_CLOSE, FLAG_NONE, closePayload)

            Log.d(LOG_TAG, "[upload] 完成: $safePath ($sent bytes, $idx chunks)")
            true
        }.getOrElse {
            reportTransferError(TransferErrorCode.IO_INTERRUPTED, it.message ?: "upload failed")
            false
        }
    }

    // --------------------------------------------------------
    //  文件下载（客户端 → 服务端）：发 FILE_GET，接收分片
    // --------------------------------------------------------
    suspend fun downloadFile(
        remotePath: String,
        targetFile: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): DownloadResult {
        val safePath = sanitizeRemotePath(remotePath) ?: return DownloadResult.Failed
        lastTransferError = null

        return runCatching {
            fileConn.sendFrame(TYPE_FILE_GET, FLAG_NONE, safePath.toByteArray())

            // 等待 FILE_OPEN
            val openResult = withTimeoutOrNull(DOWNLOAD_IDLE_TIMEOUT_MS) {
                fileRespChannel.receive()
            } ?: run {
                reportTransferError(TransferErrorCode.TIMEOUT, "等待 FILE_OPEN 超时")
                return@runCatching DownloadResult.Failed
            }
            if (openResult !is FileMsg.Open) return@runCatching DownloadResult.NotFound

            val totalSize   = openResult.totalSize
            val totalChunks = openResult.totalChunks
            onProgress?.invoke(0, totalSize)

            targetFile.parentFile?.mkdirs()
            val crc32 = CRC32()
            var received = 0L

            targetFile.outputStream().buffered().use { fos ->
                repeat(totalChunks) {
                    val chunk = withTimeoutOrNull(DOWNLOAD_IDLE_TIMEOUT_MS) {
                        fileRespChannel.receive()
                    } ?: run {
                        reportTransferError(TransferErrorCode.TIMEOUT, "分片接收超时")
                        return@runCatching DownloadResult.Failed
                    }
                    if (chunk !is FileMsg.Chunk) return@runCatching DownloadResult.Failed
                    crc32.update(chunk.data)
                    fos.write(chunk.data)
                    received += chunk.data.size
                    onProgress?.invoke(received, totalSize)
                }
            }

            // 等待 FILE_CLOSE
            val closeMsg = withTimeoutOrNull(DOWNLOAD_IDLE_TIMEOUT_MS) {
                fileRespChannel.receive()
            }
            if (closeMsg is FileMsg.Close) {
                if (crc32.value != closeMsg.crc32) {
                    reportTransferError(TransferErrorCode.CRC_MISMATCH, "文件 CRC 不匹配")
                    targetFile.delete()
                    return@runCatching DownloadResult.Failed
                }
            }

            onProgress?.invoke(received, received)
            Log.d(LOG_TAG, "[download] 完成: $safePath ($received bytes)")
            DownloadResult.Success
        }.getOrElse {
            reportTransferError(TransferErrorCode.IO_INTERRUPTED, it.message ?: "download failed")
            DownloadResult.Failed
        }
    }

    // --------------------------------------------------------
    //  文件消息内部通道（文件读取循环 → downloadFile）
    // --------------------------------------------------------
    private val fileRespChannel = Channel<FileMsg>(Channel.UNLIMITED)

    sealed interface FileMsg {
        data class Open(val totalSize: Long, val totalChunks: Int) : FileMsg
        data class Chunk(val index: Int, val data: ByteArray) : FileMsg
        data class Close(val crc32: Long) : FileMsg
        data object Error : FileMsg
    }

    // --------------------------------------------------------
    //  其他工具方法
    // --------------------------------------------------------
    suspend fun listFiles(path: String): ListFilesResult {
        val safePath = sanitizeRemotePath(path) ?: return ListFilesResult.Failed("invalid path")
        val result = runManagedCommand("ls -la ${shellEscape(safePath)}")
            ?: return ListFilesResult.Failed("empty response")
        if (result.startsWith("[ERROR]")) return ListFilesResult.Error(result)

        val items = result.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("total") && !it.startsWith("ls:") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 9)
                if (parts.size < 8) return@mapNotNull null  // 至少8列才处理
                val perms = parts[0]
                val name  = parts.last().trim()  // 取最后一列，兼容8列/9列格式
                if (name == "." || name == "..") return@mapNotNull null
                val isDir = perms.firstOrNull() == 'd'
                val full  = if (safePath == "/") "/$name" else "$safePath/$name"
                RemoteFileEntry(path = full, file = !isDir)
            }.toList()
        return ListFilesResult.Success(items)
    }

    suspend fun requestDeviceInfo(timeoutMs: Long = 5_000L): JSONObject {
        val uname = runManagedCommand("uname -a", timeoutMs) ?: "unknown"
        return JSONObject().apply {
            put("type", "info")
            put("device", id)
            put("uname", uname)
        }
    }

    fun close(reason: String = "manual close", cause: Throwable? = null) {
        if (!closed.compareAndSet(false, true)) return
        cmdConn.close()
        fileConn.close()
        cmdRespChannel.close()
        fileRespChannel.close()
        scope.cancel()
        val detail = cause?.message?.let { ": $it" } ?: ""
        onClosed(id, "$reason$detail")
    }

    // --------------------------------------------------------
    //  内部：命令通道读取循环
    // --------------------------------------------------------
    private suspend fun runCmdReadLoop() {
        try {
            while (scope.isActive && cmdConn.isOpen) {
                val frame = withContext(Dispatchers.IO) { cmdConn.recvFrame() } ?: break
                lastPongMs.set(System.currentTimeMillis())

                // 需要 ACK 的帧，先回复
                if (frame.flags == FLAG_NEED_ACK) {
                    val ackPayload = ByteBuffer.allocate(4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .putInt(frame.seq.toInt())
                        .array()
                    cmdConn.sendFrame(TYPE_ACK, FLAG_NONE, ackPayload)
                }

                when (frame.type) {
                    TYPE_HEARTBEAT -> {
                        cmdConn.sendFrame(TYPE_HEARTBEAT_ACK, FLAG_NONE)
                    }
                    TYPE_HEARTBEAT_ACK -> {
                        // pong 时间已在上面更新
                    }
                    TYPE_CMD_RESP -> {
                        val text = frame.payload.toString(Charsets.UTF_8)
                        cmdRespChannel.trySend(text)
                    }
                    TYPE_ACK -> {
                        // 服务端目前不需要处理客户端的 ACK，预留扩展
                    }
                    TYPE_ERROR -> {
                        val msg = frame.payload.toString(Charsets.UTF_8)
                        logWarn("[ClientSession] 客户端报错: $msg")
                        cmdRespChannel.trySend("[ERROR] $msg")
                    }
                    else -> logWarn("[ClientSession] cmd 通道未知类型: 0x${frame.type.toUByte().toString(16)}")
                }
            }
        } catch (e: Exception) {
            logError("[ClientSession] cmd 读取循环异常", e)
        } finally {
            close("cmd read loop end")
        }
    }

    // --------------------------------------------------------
    //  内部：文件通道读取循环
    // --------------------------------------------------------
    private suspend fun runFileReadLoop() {
        try {
            while (scope.isActive && fileConn.isOpen) {
                val frame = withContext(Dispatchers.IO) { fileConn.recvFrame() } ?: break

                when (frame.type) {
                    TYPE_FILE_OPEN -> {
                        // path\0 + totalSize(8B) + totalChunks(4B)
                        val nullIdx = frame.payload.indexOf(0)
                        if (nullIdx < 0) break
                        val bb = ByteBuffer.wrap(frame.payload, nullIdx + 1, 12)
                            .order(ByteOrder.BIG_ENDIAN)
                        val totalSize   = bb.long
                        val totalChunks = bb.int
                        fileRespChannel.trySend(FileMsg.Open(totalSize, totalChunks))
                    }
                    TYPE_FILE_CHUNK -> {
                        if (frame.payload.size < 4) continue
                        val idx  = ByteBuffer.wrap(frame.payload, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                        val data = frame.payload.copyOfRange(4, frame.payload.size)
                        fileRespChannel.trySend(FileMsg.Chunk(idx, data))
                    }
                    TYPE_FILE_CLOSE -> {
                        if (frame.payload.size < 4) continue
                        val crc = ByteBuffer.wrap(frame.payload).order(ByteOrder.BIG_ENDIAN).int
                            .toLong() and 0xFFFFFFFFL
                        fileRespChannel.trySend(FileMsg.Close(crc))
                    }
                    TYPE_ACK -> {
                        // 客户端确认文件接收成功，可记录日志
                        val msg = frame.payload.toString(Charsets.UTF_8)
                        Log.d(LOG_TAG, "[file] 客户端 ACK: $msg")
                    }
                    TYPE_ERROR -> {
                        val msg = frame.payload.toString(Charsets.UTF_8)
                        logWarn("[ClientSession] 文件通道错误: $msg")
                        fileRespChannel.trySend(FileMsg.Error)
                    }
                    else -> logWarn("[ClientSession] file 通道未知类型: 0x${frame.type.toUByte().toString(16)}")
                }
            }
        } catch (e: Exception) {
            logError("[ClientSession] file 读取循环异常", e)
        } finally {
            close("file read loop end")
        }
    }

    // --------------------------------------------------------
    //  内部：心跳发送 + 超时检测
    // --------------------------------------------------------
    private suspend fun runHeartbeat() {
        while (scope.isActive && cmdConn.isOpen) {
            delay(HEARTBEAT_SEC)
            val elapsed = System.currentTimeMillis() - lastPongMs.get()
            if (elapsed > HEARTBEAT_TIMEOUT) {
                logWarn("[ClientSession] 心跳超时 ${elapsed}ms，主动断开")
                close("heartbeat timeout")
                return
            }
            cmdConn.sendFrame(TYPE_HEARTBEAT, FLAG_NONE)
        }
    }

    // --------------------------------------------------------
    //  工具
    // --------------------------------------------------------
    private fun appendOutput(line: String) { _output.value += "$line\n" }

    private fun sanitizeRemotePath(path: String): String? {
        if (path.isBlank() || !path.startsWith('/')) return null
        if (path.length > MAX_REMOTE_PATH) return null
        if (path.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        return path
    }

    private fun shellEscape(v: String) = "'${v.replace("'", "'\\''")}'"

    private fun reportTransferError(code: TransferErrorCode, message: String) {
        lastTransferError = TransferError(code, message)
        logWarn("[ClientSession][$code] $message")
    }

    private fun logWarn(msg: String)  { FrpLogBus.append(msg); Log.w(LOG_TAG, msg) }
    private fun logError(msg: String, t: Throwable? = null) { FrpLogBus.append(msg); Log.e(LOG_TAG, msg, t) }

    // --------------------------------------------------------
    //  数据类 / 枚举 / sealed interface
    // --------------------------------------------------------
    data class RegistrationInfo(val deviceName: String, val deviceId: String, val arch: String)
    data class TransferError(val code: TransferErrorCode, val message: String)

    enum class TransferErrorCode {
        TIMEOUT, INVALID_LENGTH, IO_INTERRUPTED, PROTOCOL_MISMATCH, CRC_MISMATCH
    }

    sealed interface ShellEvent {
        data class OutputLine(val line: String) : ShellEvent
        data object CommandEnd : ShellEvent
    }

    enum class DownloadResult { Success, NotFound, Failed }

    sealed interface ListFilesResult {
        data class Success(val items: List<RemoteFileEntry>) : ListFilesResult
        data class Error(val message: String) : ListFilesResult
        data class Failed(val message: String) : ListFilesResult
    }

    data class RemoteFileEntry(val path: String, val file: Boolean)

    companion object {
        private const val DEFAULT_CMD_TIMEOUT_MS    = 10_000L
        private const val DOWNLOAD_IDLE_TIMEOUT_MS  = 5_000L
        private const val MAX_REMOTE_PATH           = 4096
        private const val LOG_TAG                   = "ClientSession"
    }
}
