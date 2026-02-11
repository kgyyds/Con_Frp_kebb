package com.kgapp.frpshell.server

import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

object TcpServer {

    val clients = ConcurrentHashMap<String, ClientSession>()

    fun start(port: Int, onNewClient: (ClientSession) -> Unit) {
        Thread {
            val server = ServerSocket(port)
            while (true) {
                val s = server.accept()
                val id = "${s.inetAddress.hostAddress}:${s.port}"
                val session = ClientSession(id, s)
                clients[id] = session
                session.start()
                onNewClient(session)
            }
        }.start()
    }
}