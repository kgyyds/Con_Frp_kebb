package com.kgapp.frpshell.server

import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

class ClientSession(
    val id: String,
    private val socket: Socket
) {
    val output = MutableStateFlow("")
    val sendQueue = LinkedBlockingQueue<String>()
    @Volatile var alive = true

    fun start() {
        // recv
        Thread {
            val reader = socket.getInputStream().bufferedReader()
            while (alive) {
                val line = reader.readLine() ?: break
                output.value += line + "\n"
            }
            alive = false
        }.start()

        // send
        Thread {
            val writer = socket.getOutputStream().bufferedWriter()
            while (alive) {
                val cmd = sendQueue.take()
                writer.write(cmd)
                writer.newLine()
                writer.flush()
            }
        }.start()
    }
}