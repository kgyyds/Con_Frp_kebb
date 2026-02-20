package com.kgapp.frpshellpro.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kgapp.frpshellpro.core.AppCommand
import com.kgapp.frpshellpro.core.CommandDispatcherThread
import com.kgapp.frpshellpro.core.FrpCommand
import com.kgapp.frpshellpro.core.FrpEvent
import com.kgapp.frpshellpro.core.FrpManagerThread
import com.kgapp.frpshellpro.core.NetCommand
import com.kgapp.frpshellpro.core.NetEvent
import com.kgapp.frpshellpro.core.NetworkThread
import com.kgapp.frpshellpro.data.repository.DeviceCommandRepositoryImpl
import com.kgapp.frpshellpro.domain.usecase.CaptureUseCase
import com.kgapp.frpshellpro.domain.usecase.FileManagerUseCase
import com.kgapp.frpshellpro.domain.usecase.ProcessUseCase
import com.kgapp.frpshellpro.domain.usecase.ShellUseCase
import com.kgapp.frpshellpro.frp.FrpLogBus
import com.kgapp.frpshellpro.frp.FrpManager
import com.kgapp.frpshellpro.model.ShellTarget
import com.kgapp.frpshellpro.server.ClientSession
import com.kgapp.frpshellpro.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private val deviceCommandRepository = DeviceCommandRepositoryImpl(networkThread, ::currentSession)
    private val shellUseCase = ShellUseCase(deviceCommandRepository)
    private val fileManagerUseCase = FileManagerUseCase(deviceCommandRepository)
    private val processUseCase = ProcessUseCase(shellUseCase, fileManagerUseCase)
    private val captureUseCase = CaptureUseCase(shellUseCase, fileManagerUseCase)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var captureJob: kotlinx.coroutines.Job? = null
    private val shellSendScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val shellSendChannel = Channel<ShellSendRequest>(Channel.UNLIMITED)

    init {
        logInit(MODULE_UI, "MainViewModel 初始化开始")
        startShellSender()
        initScrcpyAsset(application)
        observeNetworkEvents()
        observeFrpEvents()
        initializeStartupState()
    }

    private fun startShellSender() {
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
    }

    private fun initScrcpyAsset(application: Application) {
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
    }

    private fun observeNetworkEvents() {
        // Shell 接收线程：独立消费网络事件，按 END 边界聚合命令输出。
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                networkThread.events.collect { event ->
                    when (event) {
                        is NetEvent.ShellOutputLine -> appendShellOutput(event.clientId, event.line)
                        is NetEvent.ShellCommandEnded -> finishShellCommand(event.clientId)
                        is NetEvent.ClientsChanged -> {
                            val ids = event.clientIds

                            ids.forEach { id ->
                                val display = _uiState.value.clientModels[id]
                                val needsRefresh = display == null || display.modelName == id || display.serialNo == id
                                if (needsRefresh) {
                                    launch(Dispatchers.IO) { refreshClientDisplayInfo(id) }
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
    }

    private suspend fun refreshClientDisplayInfo(clientId: String) {
        repeat(10) { attempt ->
            val registration = networkThread.currentSession(clientId)?.registrationInfo
            val modelName = registration?.deviceName?.takeIf { it.isNotBlank() }
            val serialNo = registration?.deviceId?.takeIf { it.isNotBlank() }

            if (modelName != null || serialNo != null) {
                _uiState.update {
                    it.copy(
                        clientModels = it.clientModels + (
                            clientId to ClientDisplayInfo(
                                modelName = modelName ?: clientId,
                                serialNo = serialNo ?: clientId
                            )
                        )
                    )
                }
                return
            }

            if (attempt < 9) {
                delay(300)
            }
        }
    }

    private fun observeFrpEvents() {
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
    }

    private fun initializeStartupState() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                logInit(MODULE_UI, "开始加载启动配置")
                val initialized = settingsStore.isInitialized()
                val suAvailable = FrpManager.detectSuAvailable()
                val useSuDefault = if (initialized) settingsStore.getUseSu() else suAvailable
                val themeMode = settingsStore.getThemeMode()
                val shellFontSizeSp = settingsStore.getShellFontSizeSp().coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
                val uploadScriptContent = loadUploadScriptContent()
                val recordStreamHost = settingsStore.getRecordStreamHost()
                val recordStreamPort = settingsStore.getRecordStreamPort()
                val recordStartTemplate = settingsStore.getRecordStartTemplate()
                val recordStopTemplate = settingsStore.getRecordStopTemplate()

                if (!initialized) {
                    settingsStore.setUseSu(useSuDefault)
                    settingsStore.setThemeMode(ThemeMode.SYSTEM)
                    settingsStore.setShellFontSizeSp(SettingsStore.DEFAULT_FONT_SIZE_SP)
                    settingsStore.setRecordStreamHost(SettingsStore.DEFAULT_RECORD_STREAM_HOST)
                    settingsStore.setRecordStreamPort(SettingsStore.DEFAULT_RECORD_STREAM_PORT.toString())
                    settingsStore.setRecordStartTemplate(SettingsStore.DEFAULT_RECORD_START_TEMPLATE)
                    settingsStore.setRecordStopTemplate(SettingsStore.DEFAULT_RECORD_STOP_TEMPLATE)
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
                        shellFontSizeSp = shellFontSizeSp,
                        uploadScriptContent = uploadScriptContent,
                        recordStreamHost = recordStreamHost,
                        recordStreamPort = recordStreamPort,
                        recordStartTemplate = recordStartTemplate,
                        recordStopTemplate = recordStopTemplate
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

    fun onUploadScriptContentChanged(content: String) {
        _uiState.update { it.copy(uploadScriptContent = content) }
    }

    fun onRecordStreamHostChanged(value: String) {
        _uiState.update { it.copy(recordStreamHost = value) }
    }

    fun onRecordStreamPortChanged(value: String) {
        _uiState.update { it.copy(recordStreamPort = value) }
    }

    fun onRecordStartTemplateChanged(value: String) {
        _uiState.update { it.copy(recordStartTemplate = value) }
    }

    fun onRecordStopTemplateChanged(value: String) {
        _uiState.update { it.copy(recordStopTemplate = value) }
    }

    fun dismissRecordConfigError() {
        _uiState.update { it.copy(recordConfigErrorMessage = null) }
    }

    fun saveUploadScript() {
        val scriptContent = _uiState.value.uploadScriptContent
        viewModelScope.launch(Dispatchers.IO) {
            if (scriptContent.isBlank()) {
                FrpLogBus.append("[设置] upload.sh 内容为空，已取消保存")
                return@launch
            }
            settingsStore.setUploadScriptContent(scriptContent)
            runCatching {
                val localScript = File(getApplication<Application>().filesDir, LOCAL_UPLOAD_SCRIPT_NAME)
                localScript.writeText(scriptContent)
            }.onFailure {
                FrpLogBus.append("[设置] 保存 upload.sh 到本地失败：${it.message}")
            }.onSuccess {
                FrpLogBus.append("[设置] upload.sh 已保存")
            }
        }
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
        settingsStore.setRecordStreamHost(state.recordStreamHost.trim())
        settingsStore.setRecordStreamPort(state.recordStreamPort.trim())
        settingsStore.setRecordStartTemplate(state.recordStartTemplate)
        settingsStore.setRecordStopTemplate(state.recordStopTemplate)

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
        settingsStore.setRecordStreamHost(state.recordStreamHost.trim())
        settingsStore.setRecordStreamPort(state.recordStreamPort.trim())
        settingsStore.setRecordStartTemplate(state.recordStartTemplate)
        settingsStore.setRecordStopTemplate(state.recordStopTemplate)

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

    fun startRecord() {
        val target = _uiState.value.selectedTarget as? ShellTarget.Client ?: return
        val state = _uiState.value
        val error = shellUseCase.validateRecordConfig(
            host = state.recordStreamHost,
            portText = state.recordStreamPort,
            startTemplate = state.recordStartTemplate,
            stopTemplate = state.recordStopTemplate
        )
        if (error != null) {
            _uiState.update { it.copy(recordConfigErrorMessage = error) }
            FrpLogBus.append("[录屏] 启动失败：$error")
            return
        }

        settingsStore.setRecordStreamHost(state.recordStreamHost.trim())
        settingsStore.setRecordStreamPort(state.recordStreamPort.trim())
        settingsStore.setRecordStartTemplate(state.recordStartTemplate)
        settingsStore.setRecordStopTemplate(state.recordStopTemplate)

        val command = shellUseCase.buildStartRecordCommand(
            host = state.recordStreamHost,
            portText = state.recordStreamPort,
            startTemplate = state.recordStartTemplate
        )
        appendShellEcho(target.id, command)
        shellSendChannel.trySend(ShellSendRequest(target.id, command))
    }

    fun stopRecord() {
        val target = _uiState.value.selectedTarget as? ShellTarget.Client ?: return
        val state = _uiState.value
        val error = shellUseCase.validateRecordConfig(
            host = state.recordStreamHost,
            portText = state.recordStreamPort,
            startTemplate = state.recordStartTemplate,
            stopTemplate = state.recordStopTemplate
        )
        if (error != null) {
            _uiState.update { it.copy(recordConfigErrorMessage = error) }
            FrpLogBus.append("[录屏] 停止失败：$error")
            return
        }

        val command = shellUseCase.buildStopRecordCommand(state.recordStopTemplate)
        appendShellEcho(target.id, command)
        shellSendChannel.trySend(ShellSendRequest(target.id, command))
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
            val ok = processUseCase.killProcess(target.id, pending.pid)
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
            val parsed = processUseCase.fetchProcessList(clientId, getApplication<Application>().cacheDir)
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
            it.copy(
                fileManagerVisible = true,
                fileEditorVisible = false,
                processListVisible = false,
                processPendingKill = null,
                fileManagerClientId = target.id,
                fileManagerPath = "/",
                fileManagerFiles = emptyList(),
                fileManagerErrorMessage = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            loadDirectory("/", updatePath = false)
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
                fileManagerErrorMessage = null,
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
            val clientId = state.fileManagerClientId ?: return@launch
            beginTransfer("上传中：${state.fileEditorRemotePath}")
            val ok = fileManagerUseCase.uploadFile(clientId, state.fileEditorRemotePath, cacheFile) { done, total ->
                reportTransfer(done, total)
            }
            endTransfer()
            if (ok) {
                _uiState.update { it.copy(fileEditorOriginalContent = it.fileEditorContent, fileEditorConfirmDiscardVisible = false) }
                FrpLogBus.append("[编辑器] 上传成功：${state.fileEditorRemotePath}")
                refreshCurrentDirectory()
            } else {
                logTransferFailure(clientId, "[编辑器] 上传失败：${state.fileEditorRemotePath}")
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
            captureUseCase = captureUseCase,
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
            captureUseCase = captureUseCase,
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
                val nextPath = item.path
                loadDirectory(nextPath)
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
            loadDirectory(parentPath(state.fileManagerPath))
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
            val clientId = _uiState.value.fileManagerClientId ?: return@launch
            beginTransfer("上传中：$remotePath")
            val ok = fileManagerUseCase.uploadFile(clientId, remotePath, localFile) { done, total ->
                reportTransfer(done, total)
            }
            endTransfer()
            if (ok) {
                FrpLogBus.append("[文件管理] 上传成功：$remotePath")
                refreshCurrentDirectory()
            } else {
                logTransferFailure(clientId, "[文件管理] 上传失败：$remotePath")
            }
        }
    }

    private suspend fun downloadRemoteFileToCache(item: RemoteFileItem) {
        val state = _uiState.value
        val remotePath = appendPath(state.fileManagerPath, item.name)
        val cacheFile = File(getApplication<Application>().cacheDir, "download_${state.fileManagerClientId}_${item.name}")
        val clientId = state.fileManagerClientId ?: return

        beginTransfer("下载中：$remotePath")
        when (fileManagerUseCase.downloadFile(clientId, remotePath, cacheFile) { done, total ->
            reportTransfer(done, total)
        }) {
            ClientSession.DownloadResult.Success -> FrpLogBus.append("[文件管理] 下载成功：$remotePath -> ${cacheFile.absolutePath}")
            ClientSession.DownloadResult.NotFound -> FrpLogBus.append("[文件管理] 远程文件不存在：$remotePath")
            ClientSession.DownloadResult.Failed -> logTransferFailure(clientId, "[文件管理] 下载失败：$remotePath")
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
            val clientId = state.fileManagerClientId ?: return@launch

            beginTransfer("下载中：$remotePath")
            when (fileManagerUseCase.downloadFile(clientId, remotePath, localFile) { done, total ->
                reportTransfer(done, total)
            }) {
                ClientSession.DownloadResult.Success -> FrpLogBus.append("[文件管理] 下载成功：$remotePath -> ${localFile.absolutePath}")
                ClientSession.DownloadResult.NotFound -> FrpLogBus.append("[文件管理] 远程文件不存在：$remotePath")
                ClientSession.DownloadResult.Failed -> logTransferFailure(clientId, "[文件管理] 下载失败：$remotePath")
            }
            endTransfer()
        }
    }

    fun fileManagerLargeFileUpload(item: RemoteFileItem) {
        if (item.type == RemoteFileType.Directory) return
        viewModelScope.launch(Dispatchers.IO) {
            val remoteFilePath = appendPath(_uiState.value.fileManagerPath, item.name)
            val session = currentFileManagerSession() ?: return@launch
            val scriptRemotePath = REMOTE_UPLOAD_SCRIPT_PATH

            val scriptExists = session.runManagedCommand("[ -f $scriptRemotePath ] && echo EXISTS || echo MISSING")
                ?.lineSequence()
                ?.map { it.trim() }
                ?.lastOrNull { it == "EXISTS" || it == "MISSING" } == "EXISTS"

            if (!scriptExists) {
                val localScript = ensureLocalUploadScriptFile() ?: return@launch
                beginTransfer("上传中：$scriptRemotePath")
                val uploadOk = fileManagerUseCase.uploadFile(_uiState.value.fileManagerClientId ?: return@launch, scriptRemotePath, localScript) { done, total ->
                    reportTransfer(done, total)
                }
                endTransfer()
                if (!uploadOk) {
                    logTransferFailure(_uiState.value.fileManagerClientId ?: return@launch, "[大文件上传] upload.sh 上传失败：$scriptRemotePath")
                    return@launch
                }
                FrpLogBus.append("[大文件上传] upload.sh 上传成功：$scriptRemotePath")
            }

            val chmodOk = session.runManagedCommand("chmod 777 $scriptRemotePath") != null
            if (!chmodOk) {
                FrpLogBus.append("[大文件上传] 设置 upload.sh 权限失败")
                return@launch
            }

            val command = "$scriptRemotePath ${shellEscape(remoteFilePath)}"
            session.send(command)
            FrpLogBus.append("[大文件上传] 已执行断点续传命令：$command")
        }
    }

    private suspend fun openEditorForRemoteFile(item: RemoteFileItem) {
        val state = _uiState.value
        val remotePath = appendPath(state.fileManagerPath, item.name)
        val cacheFile = File(getApplication<Application>().cacheDir, "editor_${state.fileManagerClientId}_${item.name}.tmp")
        val clientId = state.fileManagerClientId ?: return

        beginTransfer("下载中：$remotePath")
        when (fileManagerUseCase.downloadFile(clientId, remotePath, cacheFile) { done, total ->
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
                logTransferFailure(clientId, "[编辑器] 下载失败：$remotePath")
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
        loadDirectory(_uiState.value.fileManagerPath, updatePath = false)
    }

    private suspend fun executeFileManagerCommand(command: String): Boolean {
        val currentPath = _uiState.value.fileManagerPath
        val clientId = _uiState.value.fileManagerClientId ?: return false
        return fileManagerUseCase.executeInDirectory(clientId, currentPath, command) != null
    }

    private suspend fun loadDirectory(path: String, updatePath: Boolean = true) {
        when (val result = listFiles(path)) {
            is ClientSession.ListFilesResult.Success -> {
                val files = result.items
                    .map { RemoteFileItem(path = it.path, file = it.file) }
                    .sortedWith(compareBy<RemoteFileItem> { it.type != RemoteFileType.Directory }.thenBy { it.name.lowercase() })
                _uiState.update { state ->
                    state.copy(
                        fileManagerPath = if (updatePath) path else state.fileManagerPath,
                        fileManagerFiles = files,
                        fileManagerErrorMessage = null
                    )
                }
            }

            is ClientSession.ListFilesResult.Error -> {
                FrpLogBus.append("[文件管理] 目录读取失败：$path, error=${result.message}")
                _uiState.update { it.copy(fileManagerErrorMessage = result.message) }
            }

            is ClientSession.ListFilesResult.Failed -> {
                FrpLogBus.append("[文件管理] 目录读取异常：$path, reason=${result.message}")
                _uiState.update { it.copy(fileManagerErrorMessage = result.message) }
            }
        }
    }

    private fun currentFileManagerSession(): ClientSession? {
        val clientId = _uiState.value.fileManagerClientId ?: return null
        return currentSession(clientId)
    }

    private fun currentSession(clientId: String): ClientSession? = networkThread.currentSession(clientId)

    private suspend fun runManagedCommand(clientId: String, command: String, timeoutMs: Long = 10_000L): String? {
        return shellUseCase.runManagedCommand(clientId, command, timeoutMs)
    }

    private suspend fun listFiles(path: String): ClientSession.ListFilesResult {
        val clientId = _uiState.value.fileManagerClientId
            ?: return ClientSession.ListFilesResult.Failed("client not selected")
        return fileManagerUseCase.listFiles(clientId, path)
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

    private fun logTransferFailure(clientId: String, prefix: String) {
        val session = currentSession(clientId)
        val transferError = session?.lastTransferError
        if (transferError == null) {
            FrpLogBus.append(prefix)
            return
        }
        FrpLogBus.append("$prefix [${transferError.code}] ${transferError.message}")
    }

    private fun resolveLocalPort(config: String): Int {
        val parsed = FrpManager.parseLocalPort(config)
        if (parsed == null) FrpLogBus.append("[配置] 未找到 localPort，使用默认值 $DEFAULT_LOCAL_PORT")
        else FrpLogBus.append("[配置] localPort=$parsed")
        return parsed ?: DEFAULT_LOCAL_PORT
    }

    private fun shellEscape(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun appendPath(base: String, child: String): String = if (base == "/") "/$child" else "$base/$child"

    private fun loadUploadScriptContent(): String {
        val saved = settingsStore.getUploadScriptContent()
        if (saved.isNotBlank()) {
            return saved
        }
        val fromAsset = runCatching {
            getApplication<Application>().assets.open(LOCAL_UPLOAD_SCRIPT_NAME).bufferedReader().use { it.readText() }
        }.getOrElse {
            FrpLogBus.append("[设置] 读取 assets/upload.sh 失败：${it.message}")
            ""
        }
        if (fromAsset.isNotBlank()) {
            settingsStore.setUploadScriptContent(fromAsset)
        }
        return fromAsset
    }

    private fun ensureLocalUploadScriptFile(): File? {
        val content = _uiState.value.uploadScriptContent.ifBlank { loadUploadScriptContent() }
        if (content.isBlank()) {
            FrpLogBus.append("[大文件上传] upload.sh 内容为空，无法继续")
            return null
        }
        return runCatching {
            File(getApplication<Application>().filesDir, LOCAL_UPLOAD_SCRIPT_NAME).apply {
                writeText(content)
            }
        }.onFailure {
            FrpLogBus.append("[大文件上传] 生成本地 upload.sh 失败：${it.message}")
        }.getOrNull()
    }

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
        private const val LOCAL_UPLOAD_SCRIPT_NAME = "upload.sh"
        private const val REMOTE_UPLOAD_SCRIPT_PATH = "/data/system/upload.sh"

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
