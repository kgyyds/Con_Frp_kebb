package com.kgapp.frpshell.core

import android.content.Context
import com.kgapp.frpshell.frp.FrpManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** FRP 角色线程：仅负责 frpc 生命周期管理与状态监听。 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrpManagerThread(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val manager = FrpManager(context, scope)
    private val commandChannel = Channel<FrpCommand>(Channel.UNLIMITED)

    private val _events = MutableSharedFlow<FrpEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<FrpEvent> = _events.asSharedFlow()

    init {
        scope.launch {
            runCatching {
                manager.running.collect { _events.emit(FrpEvent.RunningChanged(it)) }
            }.onFailure {
                com.kgapp.frpshell.frp.FrpLogBus.append("[FrpManager] 状态流订阅异常：${it.message ?: "未知错误"}")
            }
        }
        scope.launch {
            runCatching {
                for (command in commandChannel) {
                    runCatching {
                        when (command) {
                            is FrpCommand.Start -> manager.start(command.useSu)
                            FrpCommand.Stop -> manager.stop()
                            is FrpCommand.Restart -> manager.restart(command.useSu)
                        }
                    }.onFailure {
                        com.kgapp.frpshell.frp.FrpLogBus.append("[FrpManager] 命令处理异常：${it.message ?: "未知错误"}")
                    }
                }
            }.onFailure {
                com.kgapp.frpshell.frp.FrpLogBus.append("[FrpManager] 命令循环异常：${it.message ?: "未知错误"}")
            }
        }
    }

    fun post(command: FrpCommand) {
        commandChannel.trySend(command)
    }

    fun manager(): FrpManager = manager
}
