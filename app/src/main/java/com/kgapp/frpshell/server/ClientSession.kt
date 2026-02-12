package com.kgapp.frpshell.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Socket
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

    private var recvJob: Job? = null
    private val closed = AtomicBoolean(false)

    private val sendMutex = Mutex()
    private val managedCommandMutex = Mutex()
    @Volatile
    private var activeCapture: CaptureState? = null

    fun start() {
        recvJob = scope.launch(Dispatchers.IO) {
            runCatching {
                socket.getInputStream().bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (!consumeManagedLine(line)) {
                            _output.value += "$line\n"
                        }
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
            writeCommand(command, appendErrorToOutput = true)
        }
    }

    suspend fun runManagedCommand(command: String, timeoutMs: Long = DEFAULT_MANAGED_TIMEOUT_MS): String? {
        if (command.isBlank()) return ""

        return managedCommandMutex.withLock {
            val token = UUID.randomUUID().toString().replace("-", "")
            val beginMarker = "__FRPSHELL_BEGIN_${token}__"
            val endMarker = "__FRPSHELL_END_${token}__"
            val deferred = CompletableDeferred<String?>()

            activeCapture = CaptureState(
                beginMarker = beginMarker,
                endMarker = endMarker,
                result = deferred
            )

            val wrapped = "echo '$beginMarker'; $command; echo '$endMarker'"
            val sent = writeCommand(wrapped, appendErrorToOutput = false)
            if (!sent) {
                activeCapture = null
                deferred.complete(null)
                return@withLock null
            }

            withTimeoutOrNull(timeoutMs) { deferred.await() }.also {
                if (it == null) {
                    activeCapture = null
                }
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

    private suspend fun writeCommand(command: String, appendErrorToOutput: Boolean): Boolean {
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

    private data class CaptureState(
        val beginMarker: String,
        val endMarker: String,
        val result: CompletableDeferred<String?>,
        val buffer: StringBuilder = StringBuilder(),
        var started: Boolean = false
    )

    companion object {
        private const val DEFAULT_MANAGED_TIMEOUT_MS = 10_000L
    }
}
