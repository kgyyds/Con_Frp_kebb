package com.kgapp.frpshell.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kgapp.frpshell.core.AppCommand
import com.kgapp.frpshell.core.CommandDispatcherThread
import com.kgapp.frpshell.core.FrpCommand
import com.kgapp.frpshell.core.FrpEvent
import com.kgapp.frpshell.core.FrpManagerThread
import com.kgapp.frpshell.core.NetCommand
import com.kgapp.frpshell.core.NetEvent
import com.kgapp.frpshell.core.NetworkThread
import com.kgapp.frpshell.frp.FrpLogBus
import com.kgapp.frpshell.frp.FrpManager
import com.kgapp.frpshell.model.ShellTarget
import com.kgapp.frpshell.server.ClientSession
import com.kgapp.frpshell.ui.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val frpThread = FrpManagerThread(application.applicationContext)
    private val networkThread = NetworkThread()
    private val dispatcher = CommandDispatcherThread(networkThread, frpThread)
    private val frpManager = frpThread.manager()
    private val settingsStore = SettingsStore(application.applicationContext)
    private val processRepository = ProcessRepository(::currentSession)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var captureJob: kotlinx.coroutines.Job? = null
    private val shellSendScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val shellSendChannel = Channel<ShellSendRequest>(Channel.UNLIMITED)

    init {
        logInit(MODULE_UI, "MainViewModel 初始化开始")

        // Shell 发送线程：只负责发送与立即回显，不等待返回。
        shellSendScope.launch {
            runCatching {
                for (request in shellSendChannel) {
                    dispatcher.post(AppCommand.SendShell(request.clientId, request.command))
                }
            }.onFailure {
                logInit(MODULE_UI, "Shell 发送线程异常", it)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val context = application.applicationContext
                val jarFile = File(context.filesDir, "scrcpy-server.jar")
                // Ensure file exists and is not empty
                if (!jarFile.exists() || jarFile.length() == 0L) {
                    FrpLogBus.append("[初始化] 正在提取 scrcpy-server.jar 到应用目录...")
                    copyAssetToFile(context, "scrcpy-server.jar", jarFile)
                } else {
                    FrpLogBus.append("[初始化] scrcpy-server.jar 已就绪")
                }
            }.onFailure {
                logInit(MODULE_NETWORK, "scrcpy 资源初始化异常", it)
            }
        }

        // Shell 接收线程：独立消费网络事件，按 END 边界聚合命令输出。
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val requestedIds = mutableSetOf<String>()
                networkThread.events.collect { event ->
                    when (event) {
                        is NetEvent.ShellOutputLine -> appendShellOutput(event.clientId, event.line)
                        is NetEvent.ShellCommandEnded -> finishShellCommand(event.clientId)
                        is NetEvent.ClientsChanged -> {
                            val ids = event.clientIds
                            requestedIds.retainAll(ids.toSet())

                            ids.forEach { id ->
                                if (!requestedIds.contains(id) && !_uiState.value.clientModels.containsKey(id)) {
                                    requestedIds.add(id)
                                    launch(Dispatchers.IO) {
                                        val result = runManagedCommand(id, "getprop ro.product.model")
                                        val model = result?.lines()?.firstOrNull()?.trim()
                                        if (!model.isNullOrBlank()) {
                                            _uiState.update { it.copy(clientModels = it.clientModels + (id to model)) }
                                        }
                                    }
                                }
                            }

                            _uiState.update { state ->
                                val safeTarget = if (state.selectedTarget is ShellTarget.Client && state.selectedTarget.id !in ids) {
                                    ShellTarget.FrpLog
                                } else {
                                    state.selectedTarget
                                }
                                val managerAlive = state.fileManagerVisible && state.fileManagerClientId in ids
                                val editorAlive = state.fileEditorVisible && state.fileManagerClientId in ids

                                val validModels = state.clientModels.filterKeys { it in ids }
                                val validShell = state.shellItemsByClient.filterKeys { it in ids }

                                state.copy(
                                    clientIds = ids,
                                    clientModels = validModels,
                                    shellItemsByClient = validShell,
                                    selectedTarget = safeTarget,
                                    fileManagerVisible = managerAlive,
                                    processListVisible = state.processListVisible && state.selectedTarget is ShellTarget.Client && state.selectedTarget.id in ids,
                                    processPendingKill = null,
                                    fileEditorVisible = editorAlive,
                                    fileManagerClientId = if (managerAlive || editorAlive) state.fileManagerClientId else null
                                )
                            }
                        }
                    }
                }
            }.onFailure {
                logInit(MODULE_NETWORK, "网络事件订阅异常", it)
            }
        }

        viewModelScope.launch {
            runCatching {
                frpThread.events.collect { event ->
                    if (event is FrpEvent.RunningChanged) {
                        _uiState.update { it.copy(frpRunning = event.running) }
                    }
                }
            }.onFailure {
                logInit(MODULE_FRP, "FRP 状态订阅异常", it)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                logInit(MODULE_UI, "开始加载启动配置")
                val initialized = settingsStore.isInitialized()
                val suAvailable = FrpManager.detectSuAvailable()
                val useSuDefault = if (initialized) settingsStore.getUseSu() else suAvailable
                val themeMode = settingsStore.getThemeMode()
                val shellFontSizeSp = settingsStore.getShellFontSizeSp().coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)

                if (!initialized) {
                    settingsStore.setUseSu(useSuDefault)
                    settingsStore.setThemeMode(ThemeMode.SYSTEM)
                    settingsStore.setShellFontSizeSp(SettingsStore.DEFAULT_FONT_SIZE_SP)
                    settingsStore.setInitialized(true)
                }

                val configExists = frpManager.configExists()
                val content = if (configExists) frpManager.readConfig() else DEFAULT_CONFIG_TEMPLATE
                val localPort = resolveLocalPort(content)

                _uiState.update {
                    it.copy(
                        screen = if (configExists) ScreenDestination.Main else ScreenDestination.Settings,
                        firstLaunchFlow = !configExists,
                        configContent = content,
                        suAvailable = suAvailable,
                        useSu = useSuDefault,
                        themeMode = themeMode,
                        localPort = localPort,
                        shellFontSizeSp = shellFontSizeSp
                    )
                }

                networkThread.post(NetCommand.StartServer(localPort))

                if (configExists) {
                    if (useSuDefault && !suAvailable) {
                        FrpLogBus.append("[设置] 已开启 su 但当前不可用，启动可能失败")
                    }
                    frpThread.post(FrpCommand.Start(useSuDefault))
                }
                logInit(MODULE_UI, "启动配置加载完成")
            }.onFailure {
                logInit(MODULE_UI, "启动配置加载失败", it)
            }
        }
    }

    private fun logInit(module: String, message: String, throwable: Throwable? = null) {
        val full = "[$module] $message"
        FrpLogBus.append(full)
        if (throwable == null) {
            Log.i(LOG_TAG, full)
        } else {
            Log.e(LOG_TAG, "$full: ${throwable.message}", throwable)
            FrpLogBus.append("[$module] 异常详情: ${throwable.message ?: throwable::class.java.simpleName}")
        }
    }

    private fun copyAssetToFile(context: android.content.Context, assetName: String, outFile: File) {
        try {
            context.assets.open(assetName).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            FrpLogBus.append("[系统] 资源文件 $assetName 提取成功")
        } catch (e: Exception) {
            FrpLogBus.append("[初始化] 复制资源文件 $assetName 失败：${e.message}")
        }
    }

    fun onSelectTarget(target: ShellTarget) {
        _uiState.update {
            it.copy(
                selectedTarget = target,
                processListVisible = false,
                processPendingKill = null,
                processErrorMessage = null,
                processLoading = false
            )
        }
        dispatcher.post(AppCommand.SelectClient((target as? ShellTarget.Client)?.id))
    }

    fun onConfigChanged(content: String) {
        _uiState.update { it.copy(configContent = content) }
    }

    fun onUseSuChanged(enabled: Boolean) {
        _uiState.update { it.copy(useSu = enabled) }
    }

    fun onThemeModeChanged(mode: ThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun onShellFontSizeChanged(sizeSp: Float) {
        val adjusted = sizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        settingsStore.setShellFontSizeSp(adjusted)
        _uiState.update { it.copy(shellFontSizeSp = adjusted) }
    }

    fun openSettings() {
        _uiState.update { it.copy(screen = ScreenDestination.Settings, firstLaunchFlow = false) }
    }

    fun navigateBackToMain() {
        _uiState.update { state ->
            if (state.firstLaunchFlow) state else state.copy(screen = ScreenDestination.Main)
        }
    }

    fun saveConfigOnly() {
        val state = _uiState.value
        frpManager.saveConfig(state.configContent)
        settingsStore.setUseSu(state.useSu)
        settingsStore.setThemeMode(state.themeMode)
        settingsStore.setShellFontSizeSp(state.shellFontSizeSp)

        val localPort = resolveLocalPort(state.configContent)
        networkThread.post(NetCommand.StartServer(localPort))

        _uiState.update {
            it.copy(firstLaunchFlow = false, screen = ScreenDestination.Main, localPort = localPort, selectedTarget = ShellTarget.FrpLog)
        }
        FrpLogBus.append("[设置] 保存完成 (useSu=${state.useSu}, theme=${state.themeMode}, localPort=$localPort, font=${state.shellFontSizeSp})")
    }

    fun saveAndRestartFrp() {
        val state = _uiState.value
        frpManager.saveConfig(state.configContent)
        settingsStore.setUseSu(state.useSu)
        settingsStore.setThemeMode(state.themeMode)
        settingsStore.setShellFontSizeSp(state.shellFontSizeSp)

        val localPort = resolveLocalPort(state.configContent)
        networkThread.post(NetCommand.StartServer(localPort))

        _uiState.update {
            it.copy(firstLaunchFlow = false, screen = ScreenDestination.Main, localPort = localPort, selectedTarget = ShellTarget.FrpLog)
        }

        viewModelScope.launch {
            if (state.useSu && !state.suAvailable) {
                FrpLogBus.append("[设置] 已开启 su 但当前不可用，启动可能失败")
            }
            frpThread.post(FrpCommand.Restart(state.useSu))
        }
    }

    fun startFrp() {
        val state = _uiState.value
        viewModelScope.launch {
            if (state.useSu && !state.suAvailable) {
                FrpLogBus.append("[设置] 已开启 su 但当前不可用，启动可能失败")
            }
            dispatcher.post(AppCommand.StartFrp(state.useSu))
        }
    }

    fun stopFrp() {
        dispatcher.post(AppCommand.StopFrp)
    }

    fun sendCommand(command: String) {
        val text = command.trim()
        if (text.isBlank()) return
        val target = _uiState.value.selectedTarget as? ShellTarget.Client ?: return

        appendShellEcho(target.id, text)
        shellSendChannel.trySend(ShellSendRequest(target.id, text))
    }

    fun openRunningPrograms() {
        val clientId = (_uiState.value.selectedTarget as? ShellTarget.Client)?.id ?: return
        _uiState.update {
            it.copy(
                processListVisible = true,
                processErrorMessage = null,
                processPendingKill = null
            )
        }
        refreshRunningPrograms(clientId)
    }

    fun closePerformance() {
        _uiState.update {
            it.copy(
                processListVisible = false,
                processLoading = false,
                processErrorMessage = null,
                processPendingKill = null,
                processItems = emptyList()
            )
        }
    }

    fun refreshRunningPrograms() {
        val clientId = (_uiState.value.selectedTarget as? ShellTarget.Client)?.id ?: return
        refreshRunningPrograms(clientId)
    }

    fun updateProcessSort(field: ProcessSortField) {
        _uiState.update { state ->
            val ascending = if (state.processSortField == field) !state.processSortAscending else true
            val sorted = sortProcessItems(state.processItems, field, ascending)
            state.copy(
                processSortField = field,
                processSortAscending = ascending,
                processItems = sorted
            )
        }
    }

    fun requestKillProcess(item: ClientProcessInfo) {
        _uiState.update { it.copy(processPendingKill = item) }
    }

    fun cancelKillProcess() {
        _uiState.update { it.copy(processPendingKill = null) }
    }

    fun confirmKillProcess() {
        val target = _uiState.value.selectedTarget as? ShellTarget.Client ?: return
        val pending = _uiState.value.processPendingKill ?: return
        _uiState.update { it.copy(processPendingKill = null) }

        viewModelScope.launch(Dispatchers.IO) {
            val ok = processRepository.killProcess(target.id, pending.pid)
            if (ok) {
                FrpLogBus.append("[性能] 已发送 kill -9 ${pending.pid}（${pending.cmd}）")
                refreshRunningPrograms(target.id)
            } else {
                FrpLogBus.append("[性能] 结束进程失败：${pending.pid}（${pending.cmd}）")
            }
        }
    }

    private fun refreshRunningPrograms(clientId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(processLoading = true, processErrorMessage = null) }
            val parsed = processRepository.fetchProcessList(clientId, getApplication<Application>().cacheDir)
            if (parsed == null) {
                _uiState.update { it.copy(processLoading = false, processErrorMessage = "获取进程列表失败，请稍后重试") }
                FrpLogBus.append("[性能] 获取运行进程失败")
                return@launch
            }

            _uiState.update { state ->
                val sorted = sortProcessItems(parsed, state.processSortField, state.processSortAscending)
                state.copy(processLoading = false, processErrorMessage = null, processItems = sorted)
            }
            FrpLogBus.append("[性能] 已刷新进程列表，共 ${parsed.size} 项")
        }
    }

    private fun sortProcessItems(
        items: List<ClientProcessInfo>,
        field: ProcessSortField,
        ascending: Boolean
    ): List<ClientProcessInfo> {
        val sorted = when (field) {
            ProcessSortField.PID -> items.sortedBy { it.pid }
            ProcessSortField.RSS -> items.sortedBy { it.rss }
        }
        return if (ascending) sorted else sorted.asReversed()
    }

    fun openFileManager() {
        val target = _uiState.value.selectedTarget as? ShellTarget.Client ?: return
        _uiState.update {
            it.copy(fileManagerVisible = true, fileEditorVisible = false, processListVisible = false, processPendingKill = null, fileManagerClientId = target.id, fileManagerPath = "/", fileManagerFiles = emptyList())
        }

        viewModelScope.launch(Dispatchers.IO) {
            val output = executeFileManagerCommandAndGetOutput("cd / && ls -F") ?: return@launch
            _uiState.update { it.copy(fileManagerFiles = LsFParser.parse(output)) }
        }
    }

    fun closeFileManager() {
        _uiState.update {
            it.copy(
                fileManagerVisible = false,
                fileEditorVisible = false,
                fileManagerClientId = null,
                fileManagerPath = "/",
                fileManagerFiles = emptyList(),
                fileEditorRemotePath = "",
                fileEditorCachePath = "",
                fileEditorOriginalContent = "",
                fileEditorContent = "",
                fileEditorConfirmDiscardVisible = false,
                fileTransferVisible = false,
                fileTransferTitle = "",
                fileTransferDone = 0L,
                fileTransferTotal = 0L
            )
        }
    }

    fun closeFileEditor() {
        _uiState.update { state ->
            if (!state.fileEditorVisible) return@update state
            val hasUnsaved = sha256(state.fileEditorContent) != sha256(state.fileEditorOriginalContent)
            if (hasUnsaved) state.copy(fileEditorConfirmDiscardVisible = true)
            else state.copy(fileEditorVisible = false, fileEditorConfirmDiscardVisible = false)
        }
    }

    fun cancelCloseFileEditor() {
        _uiState.update { it.copy(fileEditorConfirmDiscardVisible = false) }
    }

    fun confirmCloseFileEditor() {
        _uiState.update { it.copy(fileEditorVisible = false, fileEditorConfirmDiscardVisible = false) }
    }

    fun onEditorContentChanged(content: String) {
        _uiState.update { it.copy(fileEditorContent = content) }
    }

    fun saveEditor() {
        val state = _uiState.value
        if (!state.fileEditorVisible) return
        if (sha256(state.fileEditorContent) == sha256(state.fileEditorOriginalContent)) {
            FrpLogBus.append("[编辑器] 内容未变化，跳过上传")
            return
        }

        val cacheFile = File(state.fileEditorCachePath)
        cacheFile.writeText(state.fileEditorContent)

        viewModelScope.launch(Dispatchers.IO) {
            val session = currentFileManagerSession() ?: return@launch
            beginTransfer("上传中：${state.fileEditorRemotePath}")
            val ok = session.uploadFile(state.fileEditorRemotePath, cacheFile) { done, total ->
                reportTransfer(done, total)
            }
            endTransfer()
            if (ok) {
                _uiState.update { it.copy(fileEditorOriginalContent = it.fileEditorContent, fileEditorConfirmDiscardVisible = false) }
                FrpLogBus.append("[编辑器] 上传成功：${state.fileEditorRemotePath}")
                refreshCurrentDirectory()
            } else {
                FrpLogBus.append("[编辑器] 上传失败：${state.fileEditorRemotePath}")
            }
        }
    }

    fun captureScreen() {
        val target = _uiState.value.selectedTarget as? ShellTarget.Client ?: return
        if (_uiState.value.screenCaptureLoading) return

        captureJob = launchScreenCaptureJob(
            scope = viewModelScope,
            uiState = _uiState,
            targetId = target.id,
            getSession = ::currentSession,
            cacheDir = getApplication<Application>().cacheDir
        )
    }

    fun openCameraSelector() {
        _uiState.update { it.copy(cameraSelectorVisible = true) }
    }

    fun closeCameraSelector() {
        _uiState.update { it.copy(cameraSelectorVisible = false) }
    }

    fun takePhoto(cameraId: Int) {
        val target = _uiState.value.selectedTarget as? ShellTarget.Client ?: return
        if (_uiState.value.screenCaptureLoading) return
        closeCameraSelector()

        captureJob = launchCameraPhotoCaptureJob(
            scope = viewModelScope,
            uiState = _uiState,
            targetId = target.id,
            cameraId = cameraId,
            getSession = ::currentSession,
            appContext = getApplication<Application>(),
            copyAssetToFile = ::copyAssetToFile
        )
    }

    fun cancelScreenCapture() {
        captureJob?.cancel()
        _uiState.update { it.copy(screenCaptureLoading = false) }
        FrpLogBus.append("[截图] 用户取消了截图任务")
    }

    fun closeScreenViewer() {
        _uiState.update { it.copy(screenViewerVisible = false, screenViewerImagePath = "") }
    }

    fun fileManagerRefresh() {
        viewModelScope.launch(Dispatchers.IO) { refreshCurrentDirectory() }
    }

    fun fileManagerOpen(item: RemoteFileItem) {
        if (item.type == RemoteFileType.Directory) {
            viewModelScope.launch(Dispatchers.IO) {
                val output = executeFileManagerCommandAndGetOutput("cd ${shellEscape(item.name)} && ls -F") ?: return@launch
                _uiState.update { state ->
                    state.copy(
                        fileManagerPath = appendPath(state.fileManagerPath, item.name),
                        fileManagerFiles = LsFParser.parse(output)
                    )
                }
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            downloadRemoteFileToCache(item)
        }
    }

    fun fileManagerBackDirectory() {
        val state = _uiState.value
        if (state.fileManagerPath == "/") return

        viewModelScope.launch(Dispatchers.IO) {
            val output = executeFileManagerCommandAndGetOutput("cd .. && ls -F") ?: return@launch
            _uiState.update {
                it.copy(
                    fileManagerPath = parentPath(it.fileManagerPath),
                    fileManagerFiles = LsFParser.parse(output)
                )
            }
        }
    }

    fun fileManagerRename(item: RemoteFileItem, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            executeFileManagerCommand("mv ${shellEscape(item.name)} ${shellEscape(newName)}")
            refreshCurrentDirectory()
        }
    }

    fun fileManagerChmod(item: RemoteFileItem, mode: String) {
        if (!mode.matches(Regex("\\d{3,4}"))) return
        viewModelScope.launch(Dispatchers.IO) {
            executeFileManagerCommand("chmod $mode ${shellEscape(item.name)}")
            refreshCurrentDirectory()
        }
    }

    fun fileManagerDelete(item: RemoteFileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val command = "rm -rf ${shellEscape(item.name)}"
            executeFileManagerCommand(command)
            refreshCurrentDirectory()
        }
    }


    fun fileManagerEdit(item: RemoteFileItem) {
        if (item.type == RemoteFileType.Directory) return
        viewModelScope.launch(Dispatchers.IO) {
            openEditorForRemoteFile(item)
        }
    }

    fun uploadLocalFileToCurrentDirectory(localFile: File, fileName: String) {
        if (!localFile.exists() || !localFile.isFile) {
            FrpLogBus.append("[文件管理] 本地文件不存在：${localFile.absolutePath}")
            return
        }

        val cleanName = fileName.trim()
        if (cleanName.isBlank()) {
            FrpLogBus.append("[文件管理] 上传文件名无效")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val remotePath = appendPath(_uiState.value.fileManagerPath, cleanName)
            val session = currentFileManagerSession() ?: return@launch
            beginTransfer("上传中：$remotePath")
            val ok = session.uploadFile(remotePath, localFile) { done, total ->
                reportTransfer(done, total)
            }
            endTransfer()
            if (ok) {
                FrpLogBus.append("[文件管理] 上传成功：$remotePath")
                refreshCurrentDirectory()
            } else {
                FrpLogBus.append("[文件管理] 上传失败：$remotePath")
            }
        }
    }

    private suspend fun downloadRemoteFileToCache(item: RemoteFileItem) {
        val state = _uiState.value
        val remotePath = appendPath(state.fileManagerPath, item.name)
        val cacheFile = File(getApplication<Application>().cacheDir, "download_${state.fileManagerClientId}_${item.name}")
        val session = currentFileManagerSession() ?: return

        beginTransfer("下载中：$remotePath")
        when (session.downloadFile(remotePath, cacheFile) { done, total ->
            reportTransfer(done, total)
        }) {
            ClientSession.DownloadResult.Success -> FrpLogBus.append("[文件管理] 下载成功：$remotePath -> ${cacheFile.absolutePath}")
            ClientSession.DownloadResult.NotFound -> FrpLogBus.append("[文件管理] 远程文件不存在：$remotePath")
            ClientSession.DownloadResult.Failed -> FrpLogBus.append("[文件管理] 下载失败：$remotePath")
        }
        endTransfer()
    }

    fun fileManagerDownload(item: RemoteFileItem) {
        if (item.type == RemoteFileType.Directory) return
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val remotePath = appendPath(state.fileManagerPath, item.name)
            val outputDir = getApplication<Application>().getExternalFilesDir("downloads")
                ?: getApplication<Application>().cacheDir
            val localFile = File(outputDir, item.name)
            val session = currentFileManagerSession() ?: return@launch

            beginTransfer("下载中：$remotePath")
            when (session.downloadFile(remotePath, localFile) { done, total ->
                reportTransfer(done, total)
            }) {
                ClientSession.DownloadResult.Success -> FrpLogBus.append("[文件管理] 下载成功：$remotePath -> ${localFile.absolutePath}")
                ClientSession.DownloadResult.NotFound -> FrpLogBus.append("[文件管理] 远程文件不存在：$remotePath")
                ClientSession.DownloadResult.Failed -> FrpLogBus.append("[文件管理] 下载失败：$remotePath")
            }
            endTransfer()
        }
    }

    private suspend fun openEditorForRemoteFile(item: RemoteFileItem) {
        val state = _uiState.value
        val remotePath = appendPath(state.fileManagerPath, item.name)
        val cacheFile = File(getApplication<Application>().cacheDir, "editor_${state.fileManagerClientId}_${item.name}.tmp")
        val session = currentFileManagerSession() ?: return

        beginTransfer("下载中：$remotePath")
        when (session.downloadFile(remotePath, cacheFile) { done, total ->
            reportTransfer(done, total)
        }) {
            ClientSession.DownloadResult.Success -> {
                val content = runCatching { cacheFile.readText() }.getOrElse { "" }
                _uiState.update {
                    it.copy(
                        fileEditorVisible = true,
                        fileEditorRemotePath = remotePath,
                        fileEditorCachePath = cacheFile.absolutePath,
                        fileEditorOriginalContent = content,
                        fileEditorContent = content,
                        fileEditorConfirmDiscardVisible = false
                    )
                }
                FrpLogBus.append("[编辑器] 下载成功：$remotePath")
            }

            ClientSession.DownloadResult.NotFound -> {
                FrpLogBus.append("[编辑器] 远程文件不存在：$remotePath")
            }

            ClientSession.DownloadResult.Failed -> {
                FrpLogBus.append("[编辑器] 下载失败：$remotePath")
            }
        }
        endTransfer()
    }

    private fun beginTransfer(title: String) {
        _uiState.update { it.copy(fileTransferVisible = true, fileTransferTitle = title, fileTransferDone = 0L, fileTransferTotal = 0L) }
    }

    private fun reportTransfer(done: Long, total: Long) {
        _uiState.update { it.copy(fileTransferDone = done, fileTransferTotal = total) }
    }

    private fun endTransfer() {
        _uiState.update { it.copy(fileTransferVisible = false) }
    }

    private suspend fun refreshCurrentDirectory() {
        val output = executeFileManagerCommandAndGetOutput("ls -F") ?: return
        _uiState.update { it.copy(fileManagerFiles = LsFParser.parse(output)) }
    }

    private suspend fun executeFileManagerCommand(command: String): Boolean = executeFileManagerCommandAndGetOutput(command) != null

    private suspend fun executeFileManagerCommandAndGetOutput(command: String): String? {
        val session = currentFileManagerSession() ?: return null
        return session.runManagedCommand(command).also {
            if (it == null) FrpLogBus.append("[文件管理] 命令执行失败：$command")
        }
    }

    private fun currentFileManagerSession(): ClientSession? {
        val clientId = _uiState.value.fileManagerClientId ?: return null
        return currentSession(clientId)
    }

    private fun currentSession(clientId: String): ClientSession? = networkThread.currentSession(clientId)

    private suspend fun runManagedCommand(clientId: String, command: String, timeoutMs: Long = 10_000L): String? {
        val deferred = CompletableDeferred<String?>()
        networkThread.post(NetCommand.RunManaged(clientId, command, timeoutMs, deferred))
        return deferred.await()
    }

    private fun appendShellEcho(clientId: String, command: String) {
        val item = ShellCommandItem(
            commandText = command,
            outputText = "",
            timestamp = System.currentTimeMillis(),
            status = ShellCommandStatus.RUNNING
        )
        _uiState.update { state ->
            val list = state.shellItemsByClient[clientId].orEmpty() + item
            state.copy(shellItemsByClient = state.shellItemsByClient + (clientId to list))
        }
    }

    private fun appendShellOutput(clientId: String, line: String) {
        _uiState.update { state ->
            val current = state.shellItemsByClient[clientId].orEmpty().toMutableList()
            val index = current.indexOfLast { it.status == ShellCommandStatus.RUNNING }
            if (index >= 0) {
                val target = current[index]
                val merged = if (target.outputText.isBlank()) line else target.outputText + "\n" + line
                current[index] = target.copy(outputText = merged)
            } else {
                current += ShellCommandItem(
                    commandText = "(远端输出)",
                    outputText = line,
                    timestamp = System.currentTimeMillis(),
                    status = ShellCommandStatus.DONE
                )
            }
            state.copy(shellItemsByClient = state.shellItemsByClient + (clientId to current))
        }
    }

    private fun finishShellCommand(clientId: String) {
        _uiState.update { state ->
            val current = state.shellItemsByClient[clientId].orEmpty().toMutableList()
            val index = current.indexOfFirst { it.status == ShellCommandStatus.RUNNING }
            if (index >= 0) {
                current[index] = current[index].copy(status = ShellCommandStatus.DONE)
            }
            state.copy(shellItemsByClient = state.shellItemsByClient + (clientId to current))
        }
    }

    private fun resolveLocalPort(config: String): Int {
        val parsed = FrpManager.parseLocalPort(config)
        if (parsed == null) FrpLogBus.append("[配置] 未找到 localPort，使用默认值 $DEFAULT_LOCAL_PORT")
        else FrpLogBus.append("[配置] localPort=$parsed")
        return parsed ?: DEFAULT_LOCAL_PORT
    }

    private fun shellEscape(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun appendPath(base: String, child: String): String = if (base == "/") "/$child" else "$base/$child"

    private fun parentPath(path: String): String {
        if (path == "/") return "/"
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx <= 0) "/" else trimmed.substring(0, idx)
    }


    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun onCleared() {
        super.onCleared()
        shellSendScope.cancel()
        networkThread.close()
    }

    private data class ShellSendRequest(val clientId: String, val command: String)

    companion object {
        private const val LOG_TAG = "FrpShellInit"
        private const val MODULE_UI = "UI"
        private const val MODULE_NETWORK = "Network"
        private const val MODULE_FRP = "FrpManager"
        private const val DEFAULT_LOCAL_PORT = 23231
        private const val MIN_FONT_SIZE_SP = 12f
        private const val MAX_FONT_SIZE_SP = 24f

        private const val DEFAULT_CONFIG_TEMPLATE = """
serverAddr = "your.server.com"
serverPort = 7000

[[proxies]]
name = "example"
type = "tcp"
localIP = "127.0.0.1"
localPort = 22
remotePort = 6000
"""
    }
}
