
package com.kgapp.frpshellpro.server

import android.util.Log
import com.kgapp.frpshellpro.frp.FrpLogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

object TcpServer {

    private const val CMD_PORT  = 9001
    private const val FILE_PORT = 9002

    // 握手超时：连上后必须在此时间内发来 token，否则关闭
    private const val HANDSHAKE_TIMEOUT_MS  = 10_000L
    // 配对超时：CMD 通道收到 token 后，等待 FILE 通道同 token 的最长时间
    private const val PAIR_TIMEOUT_MS       = 15_000L
    // 孤儿清理间隔
    private const val ORPHAN_CLEANUP_MS     = 20_000L

    private val sessions = ConcurrentHashMap<String, ClientSession>()

    private val _clientIds = MutableStateFlow<List<String>>(emptyList())
    val clientIds: StateFlow<List<String>> = _clientIds.asStateFlow()

    // 等待配对的 CMD 通道：token -> (socket, Connection, arrivalTime)
    private val pendingCmd  = ConcurrentHashMap<String, PendingConn>()
    // 等待配对的 FILE 通道：token -> (socket, Connection, arrivalTime)
    private val pendingFile = ConcurrentHashMap<String, PendingConn>()

    private data class PendingConn(
        val socket: Socket,
        val conn: Connection,
        val token: String,
        val arrivedAt: Long = System.currentTimeMillis()
    )

    @Volatile private var serverScope: CoroutineScope? = null
    @Volatile private var cmdServerSocket: ServerSocket? = null
    @Volatile private var fileServerSocket: ServerSocket? = null

    // --------------------------------------------------------
    //  启动
    // --------------------------------------------------------
    fun start(port: Int = CMD_PORT) {
        if (cmdServerSocket?.isClosed == false) {
            logWarn("[TCP] 监听器已在运行")
            return
        }
        stopAll()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope

        runCatching {
            val cmdSock  = ServerSocket(CMD_PORT).apply  { reuseAddress = true }
            val fileSock = ServerSocket(FILE_PORT).apply { reuseAddress = true }
            cmdServerSocket  = cmdSock
            fileServerSocket = fileSock

            // CMD 通道 accept
            scope.launch {
                logWarn("[TCP] 命令通道监听 0.0.0.0:$CMD_PORT")
                while (isActive) {
                    try {
                        val socket = cmdSock.accept()
                        scope.launch { handleIncoming(socket, isCmd = true) }
                    } catch (e: SocketException) {
                        if (!cmdSock.isClosed) logError("[TCP] cmd accept 异常", e)
                        break
                    } catch (e: Exception) {
                        logError("[TCP] cmd accept 失败", e)
                    }
                }
            }

            // FILE 通道 accept
            scope.launch {
                logWarn("[TCP] 文件通道监听 0.0.0.0:$FILE_PORT")
                while (isActive) {
                    try {
                        val socket = fileSock.accept()
                        scope.launch { handleIncoming(socket, isCmd = false) }
                    } catch (e: SocketException) {
                        if (!fileSock.isClosed) logError("[TCP] file accept 异常", e)
                        break
                    } catch (e: Exception) {
                        logError("[TCP] file accept 失败", e)
                    }
                }
            }

            // 孤儿连接清理（token 配对超时）
            scope.launch {
                while (isActive) {
                    delay(ORPHAN_CLEANUP_MS)
                    val now = System.currentTimeMillis()
                    pendingCmd.entries.removeIf { (token, pc) ->
                        if (now - pc.arrivedAt > PAIR_TIMEOUT_MS) {
                            logWarn("[TCP] CMD 通道配对超时，关闭 token=$token")
                            runCatching { pc.socket.close() }
                            true
                        } else false
                    }
                    pendingFile.entries.removeIf { (token, pc) ->
                        if (now - pc.arrivedAt > PAIR_TIMEOUT_MS) {
                            logWarn("[TCP] FILE 通道配对超时，关闭 token=$token")
                            runCatching { pc.socket.close() }
                            true
                        } else false
                    }
                }
            }

        }.onFailure { error ->
            logError("[TCP] 启动失败：${error.message}", error)
            stopAll()
        }
    }

    // --------------------------------------------------------
    //  处理新连接：先读握手帧取 token，再尝试配对
    // --------------------------------------------------------

private suspend fun handleIncoming(socket: Socket, isCmd: Boolean) {
    // 修复：设置 socket 读超时，确保阻塞 recvFrame() 能被超时打断
    socket.soTimeout = HANDSHAKE_TIMEOUT_MS.toInt()
    val conn = Connection(socket)

    val frame = try {
        withContext(Dispatchers.IO) { conn.recvFrame() }
    } catch (e: Exception) {
        logWarn("[TCP] 握手读取异常，关闭连接 isCmd=$isCmd : ${e.message}")
        runCatching { socket.close() }
        return
    }

    // 握手完成后恢复正常超时
    socket.soTimeout = SOCK_IO_TIMEOUT_SEC * 1000

    if (frame == null || frame.type != TYPE_HANDSHAKE) {
        logWarn("[TCP] 握手帧无效，关闭连接 isCmd=$isCmd")
        runCatching { socket.close() }
        return
    }

    val token = frame.payload.toString(Charsets.UTF_8).trim()
    if (token.length != 16) {
        logWarn("[TCP] token 格式非法: '$token'")
        runCatching { socket.close() }
        return
    }

    conn.sendFrame(TYPE_HANDSHAKE_ACK, FLAG_NONE, ByteArray(0))
    logWarn("[TCP] 握手完成 token=$token isCmd=$isCmd")

    val pending = PendingConn(socket, conn, token)
    if (isCmd) {
        pendingCmd[token] = pending
        val filePending = pendingFile.remove(token)
        if (filePending != null) {
            pendingCmd.remove(token)
            bindClient(token, pending, filePending)
        }
    } else {
        pendingFile[token] = pending
        val cmdPending = pendingCmd.remove(token)
        if (cmdPending != null) {
            pendingFile.remove(token)
            bindClient(token, cmdPending, pending)
        }
    }
}


    // --------------------------------------------------------
    //  两条通道都就绪，建立 Session
    // --------------------------------------------------------
    private fun bindClient(token: String, cmdPc: PendingConn, filePc: PendingConn) {
        val id = "client-$token"
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = ClientSession(
            id         = id,
            cmdSocket  = cmdPc.socket,
            fileSocket = filePc.socket,
            scope      = sessionScope,
            onClosed   = ::onSessionClosed
        )
        sessions[id] = session
        _clientIds.value = sessions.keys.sorted()
        logWarn("[TCP] 客户端已连接（双通道）id=$id")
        session.start()
    }

    // --------------------------------------------------------
    //  公开 API
    // --------------------------------------------------------
    fun getClient(id: String): ClientSession? = sessions[id]

    fun stopAll() {
        sessions.values.toList().forEach { it.close("server stopAll") }
        sessions.clear()
        _clientIds.value = emptyList()

        pendingCmd.values.forEach  { runCatching { it.socket.close() } }
        pendingFile.values.forEach { runCatching { it.socket.close() } }
        pendingCmd.clear()
        pendingFile.clear()

        runCatching { cmdServerSocket?.close() }
        runCatching { fileServerSocket?.close() }
        cmdServerSocket  = null
        fileServerSocket = null

        serverScope?.cancel()
        serverScope = null
    }

    private fun onSessionClosed(id: String, reason: String) {
        val removed = sessions.remove(id)
        if (removed != null) {
            _clientIds.value = sessions.keys.sorted()
            logWarn("[TCP] 客户端已断开: $id, reason=$reason")
        }
    }

    private fun logWarn(message: String) {
        FrpLogBus.append(message)
        Log.w(LOG_TAG, message)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        FrpLogBus.append(message)
        Log.e(LOG_TAG, message, throwable)
    }

    // 协议常量（Kotlin 侧需要用到握手类型）
    private const val TYPE_HANDSHAKE:     Byte = 0x00
    private const val TYPE_HANDSHAKE_ACK: Byte = 0x05
    private const val FLAG_NONE:          Byte = 0x00

    private const val LOG_TAG = "TcpServer"
}
