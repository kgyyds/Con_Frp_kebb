package com.kgapp.frpshellpro.server

import android.util.Log
import com.kgapp.frpshellpro.frp.FrpLogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

object TcpServer {

    private val sessions = ConcurrentHashMap<String, ClientSession>()

    private val _clientIds = MutableStateFlow<List<String>>(emptyList())
    val clientIds: StateFlow<List<String>> = _clientIds.asStateFlow()

    @Volatile
    private var serverScope: CoroutineScope? = null

    @Volatile
    private var wsServer: WsBridgeServer? = null

    @Volatile
    private var listeningPort: Int? = null

    fun start(port: Int) {
        if (listeningPort == port && wsServer != null) {
            logWarn("[WS] 监听器已在端口 $port 运行")
            return
        }

        stopAll()

        listeningPort = port
        serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        runCatching {
            WsBridgeServer(port).also {
                wsServer = it
                it.start()
            }
            logWarn("[WS] 已开始监听 0.0.0.0:$port")
        }.onFailure { error ->
            logError("[WS] 启动失败：${error.message ?: "未知错误"}", error)
            stopAll()
        }
    }

    fun getClient(id: String): ClientSession? = sessions[id]

    fun stopAll() {
        sessions.values.toList().forEach { it.close("server stopAll") }
        sessions.clear()
        _clientIds.value = emptyList()

        runCatching { wsServer?.stop() }
        wsServer = null

        serverScope?.cancel()
        serverScope = null
        listeningPort = null
    }

    private fun onSessionClosed(id: String, reason: String) {
        val removed = sessions.remove(id)
        if (removed != null) {
            _clientIds.value = sessions.keys.sorted()
            logWarn("[WS] 客户端已断开: $id, reason=$reason")
        }
    }

    private fun bindClient(conn: WebSocket): ClientSession {
        val id = conn.remoteSocketAddress?.let { "${it.address.hostAddress}:${it.port}" } ?: "unknown-${conn.hashCode()}"
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = ClientSession(
            id = id,
            connection = conn,
            scope = sessionScope,
            onClosed = ::onSessionClosed
        )
        sessions[id] = session
        _clientIds.value = sessions.keys.sorted()
        logWarn("[WS] 客户端已连接：$id")
        session.start()
        return session
    }

    private fun resolveClient(conn: WebSocket): ClientSession? {
        val id = conn.remoteSocketAddress?.let { "${it.address.hostAddress}:${it.port}" } ?: return null
        return sessions[id]
    }

    private fun logWarn(message: String) {
        FrpLogBus.append(message)
        Log.w(LOG_TAG, message)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        FrpLogBus.append(message)
        Log.e(LOG_TAG, message, throwable)
    }

    private class WsBridgeServer(port: Int) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            bindClient(conn)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            resolveClient(conn)?.close("ws close code=$code reason=$reason")
        }

        override fun onMessage(conn: WebSocket, message: String) {
            resolveClient(conn)?.onTextMessage(message)
        }

        override fun onMessage(conn: WebSocket, message: ByteBuffer) {
            val bytes = ByteArray(message.remaining())
            message.get(bytes)
            resolveClient(conn)?.onBinaryMessage(bytes)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn == null) {
                logError("[WS] 服务错误：${ex.message ?: "未知错误"}", ex)
                return
            }
            resolveClient(conn)?.close("ws error", ex)
        }

        override fun onStart() {
            connectionLostTimeout = 30
        }
    }

    private const val LOG_TAG = "TcpServer"
}
