package com.kgapp.frpshell.core

import com.kgapp.frpshell.frp.FrpLogBus
import com.kgapp.frpshell.server.TcpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 网络角色线程：唯一负责 TCP 监听、客户端命令与 shell 数据收发。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkThread {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val commandChannel = Channel<NetCommand>(Channel.UNLIMITED)

    private val _events = MutableSharedFlow<NetEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<NetEvent> = _events.asSharedFlow()

    private val outputJobs = mutableMapOf<String, Job>()

    init {
        scope.launch {
            runCatching {
                TcpServer.clientIds.collect { ids ->
                    _events.emit(NetEvent.ClientsChanged(ids))
                    syncOutputCollectors(ids)
                }
            }.onFailure {
                FrpLogBus.append("[Network] 客户端列表订阅异常：${it.message ?: "未知错误"}")
            }
        }

        scope.launch {
            runCatching {
                for (command in commandChannel) {
                    runCatching {
                        handleCommand(command)
                    }.onFailure {
                        FrpLogBus.append("[Network] 命令处理异常：${it.message ?: "未知错误"}")
                    }
                }
            }.onFailure {
                FrpLogBus.append("[Network] 命令循环异常：${it.message ?: "未知错误"}")
            }
        }
    }

    fun post(command: NetCommand) {
        commandChannel.trySend(command)
    }

    fun currentSession(clientId: String): com.kgapp.frpshell.server.ClientSession? = TcpServer.getClient(clientId)

    private suspend fun handleCommand(command: NetCommand) {
        when (command) {
            is NetCommand.StartServer -> TcpServer.start(command.port)
            NetCommand.StopServer -> TcpServer.stopAll()
            is NetCommand.SendShell -> TcpServer.getClient(command.clientId)?.send(command.command)
            is NetCommand.RunManaged -> {
                val result = TcpServer.getClient(command.clientId)
                    ?.runManagedCommand(command.command, command.timeoutMs)
                command.result.complete(result)
            }

            is NetCommand.UploadFile -> {
                val result = TcpServer.getClient(command.clientId)
                    ?.uploadFile(command.remotePath, command.localFile, command.progress)
                    ?: false
                command.result.complete(result)
            }

            is NetCommand.DownloadFile -> {
                val result = TcpServer.getClient(command.clientId)
                    ?.downloadFile(command.remotePath, command.targetFile, command.progress)
                    ?: com.kgapp.frpshell.server.ClientSession.DownloadResult.Failed
                command.result.complete(result)
            }
        }
    }

    private fun syncOutputCollectors(ids: List<String>) {
        val activeIds = ids.toSet()

        outputJobs.keys.filterNot { it in activeIds }.toList().forEach { staleId ->
            outputJobs.remove(staleId)?.cancel()
        }

        ids.forEach { id ->
            if (outputJobs.containsKey(id)) return@forEach
            val session = TcpServer.getClient(id) ?: return@forEach
            outputJobs[id] = scope.launch {
                runCatching {
                    session.shellEvents.collect { shellEvent ->
                        when (shellEvent) {
                            is com.kgapp.frpshell.server.ClientSession.ShellEvent.OutputLine -> {
                                _events.emit(NetEvent.ShellOutputLine(id, shellEvent.line))
                            }

                            com.kgapp.frpshell.server.ClientSession.ShellEvent.CommandEnd -> {
                                _events.emit(NetEvent.ShellCommandEnded(id))
                            }
                        }
                    }
                }.onFailure {
                    FrpLogBus.append("[Network] Shell 事件收集异常($id)：${it.message ?: "未知错误"}")
                }
            }
        }
    }

    fun close() {
        FrpLogBus.append("[网络线程] 正在停止")
        outputJobs.values.forEach { it.cancel() }
        outputJobs.clear()
        TcpServer.stopAll()
        scope.cancel()
    }
}
