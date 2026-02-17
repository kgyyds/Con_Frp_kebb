package com.kgapp.frpshell.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 逻辑调度线程：接收 UI 意图并转发到网络/FRP 线程，不直接做 I/O。
 */
class CommandDispatcherThread(
    private val networkThread: NetworkThread,
    private val frpThread: FrpManagerThread
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val appCommands = Channel<AppCommand>(Channel.UNLIMITED)

    private val _mode = MutableStateFlow(ControllerMode.IDLE)
    val mode: StateFlow<ControllerMode> = _mode.asStateFlow()

    init {
        scope.launch {
            for (command in appCommands) {
                when (command) {
                    is AppCommand.SelectClient -> {
                        _mode.value = if (command.clientId == null) ControllerMode.IDLE else ControllerMode.SHELL_MODE
                    }

                    is AppCommand.SendShell -> {
                        _mode.value = ControllerMode.SHELL_MODE
                        networkThread.post(NetCommand.SendShell(command.clientId, command.command))
                    }

                    is AppCommand.StartFrp -> frpThread.post(FrpCommand.Start(command.useSu))
                    AppCommand.StopFrp -> frpThread.post(FrpCommand.Stop)
                }
            }
        }
    }

    fun post(command: AppCommand) {
        appCommands.trySend(command)
    }
}
