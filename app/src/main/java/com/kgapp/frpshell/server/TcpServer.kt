package com.kgapp.frpshell.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

object TcpServer {

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, ClientSession>()

    private val _clientIds = MutableStateFlow<List<String>>(emptyList())
    val clientIds: StateFlow<List<String>> = _clientIds.asStateFlow()

    @Volatile
    private var started = false

    fun start(port: Int = 23231) {
        if (started) return
        started = true
        serverScope.launch {
            runCatching {
                ServerSocket(port).use { server ->
                    while (true) {
                        val socket = server.accept()
                        val id = "${socket.inetAddress.hostAddress}:${socket.port}"
                        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                        val session = ClientSession(id, socket, sessionScope)
                        sessions[id] = session
                        session.start()
                        _clientIds.value = sessions.keys.sorted()
                    }
                }
            }.onFailure {
                started = false
            }
        }
    }

    fun getClient(id: String): ClientSession? = sessions[id]

    fun stopAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        _clientIds.value = emptyList()
        serverScope.cancel()
        started = false
    }
}
