package com.kgapp.frpshellpro.core

import com.kgapp.frpshellpro.frp.FrpLogBus
import com.kgapp.frpshellpro.server.TcpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 网络角色线程：唯一负责 TCP 监听、客户端命令与 shell 数据收发。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkThread {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val interactiveChannel = Channel<NetCommand>(Channel.UNLIMITED)
    private val backgroundChannel = Channel<QueuedBackgroundCommand>(Channel.UNLIMITED)

    private val _events = MutableSharedFlow<NetEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<NetEvent> = _events.asSharedFlow()

    private val outputJobs = mutableMapOf<String, Job>()
    private val clientMutexMap = ConcurrentHashMap<String, Mutex>()
    private val backgroundQueueSize = AtomicInteger(0)

    private data class QueuedBackgroundCommand(
        val command: NetCommand,
        val enqueueTimestampMs: Long
    )

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
                for (command in interactiveChannel) {
                    runCatching {
                        handleCommandWithClientOrder(command)
                    }.onFailure {
                        FrpLogBus.append("[Network] 交互命令处理异常：${it.message ?: "未知错误"}")
                    }
                }
            }.onFailure {
                FrpLogBus.append("[Network] 交互命令循环异常：${it.message ?: "未知错误"}")
            }
        }

        repeat(BACKGROUND_WORKER_COUNT) { workerIndex ->
            scope.launch(Dispatchers.IO.limitedParallelism(BACKGROUND_WORKER_COUNT)) {
                runCatching {
                    for (queued in backgroundChannel) {
                        val queueRemain = backgroundQueueSize.decrementAndGet().coerceAtLeast(0)
                        val waitCost = System.currentTimeMillis() - queued.enqueueTimestampMs
                        FrpLogBus.append(
                            "[Network][BG] worker=$workerIndex 队列长度=$queueRemain 等待=${waitCost}ms 命令=${queued.command::class.simpleName}"
                        )
                        runCatching {
                            handleCommandWithClientOrder(queued.command)
                        }.onFailure {
                            FrpLogBus.append("[Network] 后台命令处理异常：${it.message ?: "未知错误"}")
                        }
                    }
                }.onFailure {
                    FrpLogBus.append("[Network] 后台命令循环异常(worker=$workerIndex)：${it.message ?: "未知错误"}")
                }
            }
        }
    }

    fun post(command: NetCommand) {
        when (resolvePriority(command)) {
            NetCommandPriority.INTERACTIVE -> interactiveChannel.trySend(command)
            NetCommandPriority.BACKGROUND -> {
                val queued = QueuedBackgroundCommand(command, System.currentTimeMillis())
                val result = backgroundChannel.trySend(queued)
                if (result.isSuccess) {
                    val pending = backgroundQueueSize.incrementAndGet()
                    FrpLogBus.append("[Network][BG] 入队成功，队列长度=$pending，命令=${command::class.simpleName}")
                }
            }
        }
    }

    fun currentSession(clientId: String): com.kgapp.frpshellpro.server.ClientSession? = TcpServer.getClient(clientId)

    private suspend fun handleCommandWithClientOrder(command: NetCommand) {
        val clientId = commandClientId(command)
        if (clientId == null) {
            handleCommand(command)
            return
        }
        val mutex = clientMutexMap.getOrPut(clientId) { Mutex() }
        mutex.withLock {
            handleCommand(command)
        }
    }

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
                    ?: com.kgapp.frpshellpro.server.ClientSession.DownloadResult.Failed
                command.result.complete(result)
            }

            is NetCommand.ListFiles -> {
                val result = TcpServer.getClient(command.clientId)
                    ?.listFiles(command.path)
                    ?: com.kgapp.frpshellpro.server.ClientSession.ListFilesResult.Failed("client not found")
                command.result.complete(result)
            }
        }
    }

    private fun resolvePriority(command: NetCommand): NetCommandPriority {
        return when (command) {
            is NetCommand.SendShell -> NetCommandPriority.INTERACTIVE
            is NetCommand.RunManaged -> command.priority
            is NetCommand.UploadFile -> command.priority
            is NetCommand.DownloadFile -> command.priority
            is NetCommand.ListFiles -> command.priority
            is NetCommand.StartServer, NetCommand.StopServer -> NetCommandPriority.INTERACTIVE
        }
    }

    private fun commandClientId(command: NetCommand): String? {
        return when (command) {
            is NetCommand.SendShell -> command.clientId
            is NetCommand.RunManaged -> command.clientId
            is NetCommand.UploadFile -> command.clientId
            is NetCommand.DownloadFile -> command.clientId
            is NetCommand.ListFiles -> command.clientId
            is NetCommand.StartServer, NetCommand.StopServer -> null
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
                            is com.kgapp.frpshellpro.server.ClientSession.ShellEvent.OutputLine -> {
                                _events.emit(NetEvent.ShellOutputLine(id, shellEvent.line))
                            }

                            com.kgapp.frpshellpro.server.ClientSession.ShellEvent.CommandEnd -> {
                                _events.emit(NetEvent.ShellCommandEnded(id))
                            }
                        }
                    }
                }.onFailure {
                    when (it) {
                        is CancellationException -> {
                            FrpLogBus.append("[Network][DEBUG] Shell 事件收集协程已取消(客户端离线/切换)($id)")
                        }

                        else -> {
                            val exceptionType = it::class.java.simpleName
                            val stackSummary = it.stackTrace
                                .take(5)
                                .joinToString(" <- ") { element ->
                                    "${element.className}.${element.methodName}:${element.lineNumber}"
                                }
                                .ifBlank { "无堆栈" }
                            FrpLogBus.append(
                                "[Network] Shell 事件收集异常($id)：${it.message ?: "未知错误"} " +
                                    "[类型=$exceptionType, 堆栈=$stackSummary]"
                            )
                        }
                    }
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

    private companion object {
        private const val BACKGROUND_WORKER_COUNT = 2
    }
}
