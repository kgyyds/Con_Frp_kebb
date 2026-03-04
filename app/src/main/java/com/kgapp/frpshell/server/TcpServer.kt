package com.kgapp.frpshellpro.server

import android.util.Log
import com.kgapp.frpshellpro.frp.FrpLogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

object TcpServer {

    private val sessions = ConcurrentHashMap<String, ClientSession>()

    private val _clientIds = MutableStateFlow<List<String>>(emptyList())
    val clientIds: StateFlow<List<String>> = _clientIds.asStateFlow()

    @Volatile
    private var serverScope: CoroutineScope? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var listeningPort: Int? = null

    fun start(port: Int) {
        if (listeningPort == port && serverSocket?.isClosed == false) {
            logWarn("[TCP] 监听器已在端口 $port 运行")
            return
        }

        stopAll()

        listeningPort = port
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope

        runCatching {
            val socket = ServerSocket(port).apply { reuseAddress = true }
            serverSocket = socket
            acceptJob = scope.launch {
                logWarn("[TCP] 已开始监听 0.0.0.0:$port")
                while (isActive) {
                    try {
                        val client = socket.accept()
                        bindClient(client)
                    } catch (e: SocketException) {
                        if (!socket.isClosed) {
                            logError("[TCP] accept 异常：${e.message ?: "未知错误"}", e)
                        }
                        break
                    } catch (e: Exception) {
                        logError("[TCP] accept 失败：${e.message ?: "未知错误"}", e)
                    }
                }
            }
        }.onFailure { error ->
            logError("[TCP] 启动失败：${error.message ?: "未知错误"}", error)
            stopAll()
        }
    }

    fun getClient(id: String): ClientSession? = sessions[id]

    fun stopAll() {
        sessions.values.toList().forEach { it.close("server stopAll") }
        sessions.clear()
        _clientIds.value = emptyList()

        runCatching { serverSocket?.close() }
        serverSocket = null

        acceptJob?.cancel()
        acceptJob = null

        serverScope?.cancel()
        serverScope = null
        listeningPort = null
    }

    private fun onSessionClosed(id: String, reason: String) {
        val removed = sessions.remove(id)
        if (removed != null) {
            _clientIds.value = sessions.keys.sorted()
            logWarn("[TCP] 客户端已断开: $id, reason=$reason")
        }
    }

    private fun bindClient(socket: java.net.Socket): ClientSession {
        val id = socket.inetAddress?.hostAddress?.let { "$it:${socket.port}" } ?: "unknown-${socket.hashCode()}"
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = ClientSession(
            id = id,
            socket = socket,
            scope = sessionScope,
            onClosed = ::onSessionClosed
        )
        sessions[id] = session
        _clientIds.value = sessions.keys.sorted()
        logWarn("[TCP] 客户端已连接：$id")
        session.start()
        return session
    }

    private fun logWarn(message: String) {
        FrpLogBus.append(message)
        Log.w(LOG_TAG, message)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        FrpLogBus.append(message)
        Log.e(LOG_TAG, message, throwable)
    }

    private const val LOG_TAG = "TcpServer"
}
