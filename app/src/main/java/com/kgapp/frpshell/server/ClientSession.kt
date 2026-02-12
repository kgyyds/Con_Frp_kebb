package com.kgapp.frpshell.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Socket
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

    fun start() {
        recvJob = scope.launch(Dispatchers.IO) {
            runCatching {
                socket.getInputStream().bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        _output.value += "$line\n"
                    }
                }
            }.onFailure {
                _output.value += "[session closed] ${it.message ?: "unknown"}\n"
            }
            close()
        }
    }

    fun send(command: String) {
        if (command.isBlank()) return
        scope.launch(Dispatchers.IO) {
            if (!isActive) return@launch
            runCatching {
                socket.getOutputStream().bufferedWriter().apply {
                    write(command)
                    newLine()
                    flush()
                }
            }.onFailure {
                _output.value += "[send failed] ${it.message ?: "unknown"}\n"
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
}
