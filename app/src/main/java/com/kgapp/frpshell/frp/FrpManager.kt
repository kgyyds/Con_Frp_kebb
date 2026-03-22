package com.kgapp.frpshellpro.frp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var monitorJob: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

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
        runCatching {
            if (_running.value) {
                FrpLogBus.append("[FRP] 已在运行")
                return
            }
            if (!configExists()) {
                FrpLogBus.append("[FRP] 未找到 frpc.toml 配置文件")
                return
            }
            if (!ensureFrpcBinaryReady()) {
                FrpLogBus.append("[FRP] frpc 不可执行")
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

            val started = ProcessBuilder(command).start()
            process = started
            _running.value = true
            FrpLogBus.append("[FRP] 启动成功 (useSu=$useSu)")

            stdoutJob?.cancel()
            stderrJob?.cancel()
            monitorJob?.cancel()

            stdoutJob = scope.launch(Dispatchers.IO) {
                runCatching {
                    started.inputStream.bufferedReader().forEachLine { FrpLogBus.append(it) }
                }.onFailure {
                    FrpLogBus.append("[FrpManager] 标准输出读取异常：${it.message ?: "未知错误"}")
                }
            }
            stderrJob = scope.launch(Dispatchers.IO) {
                runCatching {
                    started.errorStream.bufferedReader().forEachLine { FrpLogBus.append("[FRP错误] $it") }
                }.onFailure {
                    FrpLogBus.append("[FrpManager] 错误输出读取异常：${it.message ?: "未知错误"}")
                }
            }
            monitorJob = scope.launch(Dispatchers.IO) {
                val code = runCatching { started.waitFor() }.getOrDefault(-1)
                _running.value = false
                if (process == started) {
                    process = null
                }
                FrpLogBus.append("[FRP] 进程退出，退出码 $code")
            }
        }.onFailure {
            _running.value = false
            FrpLogBus.append("[FrpManager] 启动流程异常：${it.message ?: "未知错误"}")
            FrpLogBus.append("[FRP] 启动失败 (useSu=$useSu)：${it.message ?: "未知错误"}")
        }
    }

    suspend fun stop() {
        if (!_running.value && process == null) {
            FrpLogBus.append("[FRP] 已停止")
            return
        }
        process?.destroy()
        process = null
        stdoutJob?.cancelAndJoin()
        stderrJob?.cancelAndJoin()
        monitorJob?.cancelAndJoin()
        stdoutJob = null
        stderrJob = null
        monitorJob = null
        _running.value = false
        FrpLogBus.append("[FRP] 已停止运行")
    }

    private fun ensureFrpcBinaryReady(): Boolean {
        return runCatching {
            if (!frpcBinary.exists()) {
                context.assets.open("frpc").use { input ->
                    frpcBinary.outputStream().use { out ->
                        input.copyTo(out)
                    }
                }
            }

            val chmodResult = runCatching {
                ProcessBuilder("chmod", "777", frpcBinary.absolutePath)
                    .start()
                    .waitFor()
            }.getOrElse { -1 }

            if ((chmodResult != 0 || !frpcBinary.canExecute()) && !frpcBinary.setExecutable(true, false)) {
                return false
            }

            frpcBinary.canExecute()
        }.onFailure {
            FrpLogBus.append("[FrpManager] frpc 初始化异常：${it.message ?: "未知错误"}")
        }.getOrDefault(false)
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
            FrpLogBus.append("[FRP] 未发现残留 frpc 进程")
            return
        }

        FrpLogBus.append("[FRP] 发现残留 frpc 进程：${pids.joinToString(",")}")

        val killResult = runCatching {
            ProcessBuilder("su", "-c", "kill ${pids.joinToString(" ")}").start().waitFor()
        }.getOrElse { -1 }

        if (killResult != 0) {
            ProcessBuilder("su", "-c", "kill -9 ${pids.joinToString(" ")}").start().waitFor()
        }

        FrpLogBus.append("[FRP] 残留 frpc 进程清理完成")
    }

    private fun runCommandWithOutput(command: List<String>): String {
        return runCatching {
            val proc = ProcessBuilder(command).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
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
