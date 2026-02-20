package com.kgapp.frpshellpro.core

import com.kgapp.frpshellpro.server.ClientSession
import java.io.File
import kotlinx.coroutines.CompletableDeferred

/** UI 线程向调度线程发送的业务意图。 */
sealed interface AppCommand {
    data class SelectClient(val clientId: String?) : AppCommand
    data class SendShell(val clientId: String, val command: String) : AppCommand
    data class StartFrp(val useSu: Boolean) : AppCommand
    data object StopFrp : AppCommand
}

sealed interface NetCommand {
    data class StartServer(val port: Int) : NetCommand
    data object StopServer : NetCommand
    data class SendShell(val clientId: String, val command: String) : NetCommand
    data class RunManaged(
        val clientId: String,
        val command: String,
        val timeoutMs: Long,
        val result: CompletableDeferred<String?>
    ) : NetCommand

    data class UploadFile(
        val clientId: String,
        val remotePath: String,
        val localFile: File,
        val progress: ((Long, Long) -> Unit)?,
        val result: CompletableDeferred<Boolean>
    ) : NetCommand

    data class DownloadFile(
        val clientId: String,
        val remotePath: String,
        val targetFile: File,
        val progress: ((Long, Long) -> Unit)?,
        val result: CompletableDeferred<ClientSession.DownloadResult>
    ) : NetCommand
}

sealed interface NetEvent {
    data class ClientsChanged(val clientIds: List<String>) : NetEvent
    data class ShellOutputLine(val clientId: String, val line: String) : NetEvent
    data class ShellCommandEnded(val clientId: String) : NetEvent
}

sealed interface FrpCommand {
    data class Start(val useSu: Boolean) : FrpCommand
    data object Stop : FrpCommand
    data class Restart(val useSu: Boolean) : FrpCommand
}

sealed interface FrpEvent {
    data class RunningChanged(val running: Boolean) : FrpEvent
}

enum class ControllerMode {
    IDLE,
    SHELL_MODE
}
