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

        if (useSu) {
            cleanupResidualFrpcWithSu()
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

        // 每次启动前都确保 chmod 777
        val chmodResult = runCatching {
            ProcessBuilder("chmod", "777", frpcBinary.absolutePath)
                .start()
                .waitFor()
        }.getOrElse { -1 }

        if ((chmodResult != 0 || !frpcBinary.canExecute()) && !frpcBinary.setExecutable(true, false)) {
            return false
        }
        permissionPrepared = true

        return permissionPrepared && frpcBinary.canExecute()
    }

    private fun cleanupResidualFrpcWithSu() {
        val pidOutput = runCommandWithOutput(
            listOf("su", "-c", "pidof frpc 2>/dev/null || pgrep -f '/frpc( |$)' 2>/dev/null")
        )
        val pids = pidOutput
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.matches(Regex("\\d+")) }

        if (pids.isEmpty()) {
            FrpLogBus.append("[frp] no residual frpc process found")
            return
        }

        FrpLogBus.append("[frp] residual frpc process found: ${pids.joinToString(",")}")

        val killResult = runCatching {
            ProcessBuilder("su", "-c", "kill ${pids.joinToString(" ")}").start().waitFor()
        }.getOrElse { -1 }
        if (killResult != 0) {
            ProcessBuilder("su", "-c", "kill -9 ${pids.joinToString(" ")}").start().waitFor()
        }
        FrpLogBus.append("[frp] residual frpc cleanup finished")
    }

    private fun runCommandWithOutput(command: List<String>): String {
        return runCatching {
            val process = ProcessBuilder(command).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output
        }.getOrDefault("")
    }

    private fun shellEscape(value: String): String = "'${value.replace("'", "'\\''")}'"

    companion object {
        private val localPortRegex = Regex("""(?m)^\s*localPort\s*=\s*(\d+)\s*(?:#.*)?$""")

        fun parseLocalPort(configContent: String): Int? {
            val match = localPortRegex.find(configContent) ?: return null
            val parsed = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
            return parsed.takeIf { it in 1..65535 }
        }

        fun detectSuAvailable(): Boolean {
            val whichOk = runCatching {
                ProcessBuilder("sh", "-c", "which su").start().waitFor() == 0
            }.getOrDefault(false)
            if (!whichOk) return false

            return runCatching {
                ProcessBuilder("su", "-c", "echo frpshell").start().waitFor() == 0
            }.getOrDefault(false)
        }
    }
}
