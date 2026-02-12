package com.kgapp.frpshell.frp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.File

class FrpManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val frpcBinary = File(context.filesDir, "frpc")
    private val configFile = File(context.filesDir, "frpc.toml")

    private var process: Process? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null

    @Volatile
    private var permissionPrepared = false

    fun configExists(): Boolean = configFile.exists()

    fun readConfig(): String = if (configFile.exists()) configFile.readText() else ""

    fun saveConfig(content: String) {
        configFile.writeText(content)
    }

    suspend fun restart(useSu: Boolean) {
        stop()
        start(useSu)
    }

    fun start(useSu: Boolean) {
        if (!configExists()) {
            FrpLogBus.append("[frp] frpc.toml not found")
            return
        }
        if (!ensureFrpcBinaryReady()) {
            FrpLogBus.append("[frp] frpc not executable")
            return
        }

        val command = if (useSu) {
            val suCmd = "${shellEscape(frpcBinary.absolutePath)} -c ${shellEscape(configFile.absolutePath)}"
            listOf("su", "-c", suCmd)
        } else {
            listOf(frpcBinary.absolutePath, "-c", configFile.absolutePath)
        }

        runCatching {
            val started = ProcessBuilder(command).start()
            process = started
            FrpLogBus.append("[frp] started (useSu=$useSu)")

            stdoutJob?.cancel()
            stderrJob?.cancel()
            stdoutJob = scope.launch(Dispatchers.IO) {
                started.inputStream.bufferedReader().forEachLine { FrpLogBus.append(it) }
            }
            stderrJob = scope.launch(Dispatchers.IO) {
                started.errorStream.bufferedReader().forEachLine { FrpLogBus.append("[err] $it") }
            }
        }.onFailure {
            FrpLogBus.append("[frp] failed (useSu=$useSu): ${it.message ?: "unknown"}")
        }
    }

    suspend fun stop() {
        process?.destroy()
        process = null
        stdoutJob?.cancelAndJoin()
        stderrJob?.cancelAndJoin()
        stdoutJob = null
        stderrJob = null
        FrpLogBus.append("[frp] stopped")
    }

    private fun ensureFrpcBinaryReady(): Boolean {
        if (!frpcBinary.exists()) {
            context.assets.open("frpc").use { input ->
                frpcBinary.outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            permissionPrepared = false
        }

        if (!permissionPrepared || !frpcBinary.canExecute()) {
            val chmodResult = runCatching {
                ProcessBuilder("chmod", "777", frpcBinary.absolutePath)
                    .start()
                    .waitFor()
            }.getOrElse { -1 }

            if (chmodResult != 0 && !frpcBinary.setExecutable(true, false)) {
                return false
            }
            permissionPrepared = true
        }

        return frpcBinary.canExecute()
    }

    private fun shellEscape(value: String): String = "'${value.replace("'", "'\\''")}'"

    companion object {
        fun detectSuAvailable(): Boolean {
            val whichOk = runCatching {
                ProcessBuilder("sh", "-c", "which su").start().waitFor() == 0
            }.getOrDefault(false)
            if (!whichOk) return false

            return runCatching {
                ProcessBuilder("su", "-c", "echo frpshell").start().waitFor() == 0
            }.getOrDefault(false)
        }

        if (!permissionPrepared || !frpcBinary.canExecute()) {
            val chmodResult = runCatching {
                ProcessBuilder("/system/bin/chmod", "777", frpcBinary.absolutePath)
                    .start()
                    .waitFor()
            }.getOrElse { -1 }

            if (chmodResult != 0 && !frpcBinary.setExecutable(true, false)) {
                return false
            }
            permissionPrepared = true
        }

        return frpcBinary.canExecute()
    }
}
