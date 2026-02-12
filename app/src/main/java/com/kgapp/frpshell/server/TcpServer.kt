package com.kgapp.frpshell.server

import com.kgapp.frpshell.frp.FrpLogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var listeningPort: Int? = null

    fun start(port: Int) {
        if (listeningPort == port && serverSocket != null) {
            FrpLogBus.append("[tcp] listener already running on $port")
            return
        }

        stopAll()

        listeningPort = port
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = newScope

        newScope.launch {
            runCatching {
                ServerSocket(port).use { server ->
                    serverSocket = server
                    FrpLogBus.append("[tcp] listening on 127.0.0.1:$port")
                    while (true) {
                        val socket = server.accept()
                        val id = "${socket.inetAddress.hostAddress}:${socket.port}"
                        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                        val session = ClientSession(
                            id = id,
                            socket = socket,
                            scope = sessionScope,
                            onClosed = ::onSessionClosed
                        )
                        sessions[id] = session
                        _clientIds.value = sessions.keys.sorted()
                        FrpLogBus.append("[tcp] client connected: $id")
                        session.start()
                    }
                }
            }.onFailure { error ->
                if (error !is SocketException) {
                    FrpLogBus.append("[tcp] listener stopped: ${error.message ?: "unknown"}")
                }
            }
        }
    }

    fun getClient(id: String): ClientSession? = sessions[id]

    fun stopAll() {
        sessions.values.toList().forEach { it.close() }
        sessions.clear()
        _clientIds.value = emptyList()

        runCatching { serverSocket?.close() }
        serverSocket = null

        serverScope?.cancel()
        serverScope = null
        listeningPort = null
    }

    private fun onSessionClosed(id: String) {
        val removed = sessions.remove(id)
        if (removed != null) {
            _clientIds.value = sessions.keys.sorted()
            FrpLogBus.append("[tcp] client disconnected: $id")
        }
    }
}
