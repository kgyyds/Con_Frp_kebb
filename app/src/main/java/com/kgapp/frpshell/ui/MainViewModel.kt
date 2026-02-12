package com.kgapp.frpshell.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kgapp.frpshell.frp.FrpLogBus
import com.kgapp.frpshell.frp.FrpManager
import com.kgapp.frpshell.model.ShellTarget
import com.kgapp.frpshell.server.ClientSession
import com.kgapp.frpshell.server.TcpServer
import com.kgapp.frpshell.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

enum class ScreenDestination {
    Main,
    Settings
}


data class MainUiState(
    val selectedTarget: ShellTarget = ShellTarget.FrpLog,
    val clientIds: List<String> = emptyList(),
    val screen: ScreenDestination = ScreenDestination.Main,
    val configContent: String = "",
    val firstLaunchFlow: Boolean = false,
    val suAvailable: Boolean = false,
    val useSu: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val localPort: Int = 23231,
    val shellFontSizeSp: Float = SettingsStore.DEFAULT_FONT_SIZE_SP,
    val frpRunning: Boolean = false,
    val fileManagerVisible: Boolean = false,
    val fileManagerClientId: String? = null,
    val fileManagerPath: String = "/",
    val fileManagerFiles: List<RemoteFileItem> = emptyList(),
    val fileEditorVisible: Boolean = false,
    val fileEditorRemotePath: String = "",
    val fileEditorCachePath: String = "",
    val fileEditorOriginalContent: String = "",
    val fileEditorContent: String = "",
    val fileEditorConfirmDiscardVisible: Boolean = false,
    val fileTransferVisible: Boolean = false,
    val fileTransferTitle: String = "",
    val fileTransferDone: Long = 0L,
    val fileTransferTotal: Long = 0L,
    val screenViewerVisible: Boolean = false,
    val screenViewerImagePath: String = "",
    val screenViewerTimestamp: Long = 0L,
    val screenCaptureLoading: Boolean = false,
    val screenCaptureLoadingText: String = "正在截屏...",
    val screenCaptureLog: String = "",
    val screenCaptureCancelable: Boolean = false,
    val cameraSelectorVisible: Boolean = false,
    val clientModels: Map<String, String> = emptyMap()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val frpManager = FrpManager(application.applicationContext, viewModelScope)
    private val settingsStore = SettingsStore(application.applicationContext)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var captureJob: kotlinx.coroutines.Job? = null


    init {
        viewModelScope.launch(Dispatchers.IO) {
            val context = application.applicationContext
            val jarFile = File(context.filesDir, "scrcpy-server.jar")
            // Ensure file exists and is not empty
            if (!jarFile.exists() || jarFile.length() == 0L) {
                FrpLogBus.append("[init] extracting scrcpy-server.jar to files dir...")
                copyAssetToFile(context, "scrcpy-server.jar", jarFile)
            } else {
                FrpLogBus.append("[init] scrcpy-server.jar ready in files dir")
            }
        }

        viewModelScope.launch {
            val requestedIds = mutableSetOf<String>()
            TcpServer.clientIds.collect { ids ->
                requestedIds.retainAll(ids.toSet())

                ids.forEach { id ->
                    if (!requestedIds.contains(id) && !_uiState.value.clientModels.containsKey(id)) {
                        requestedIds.add(id)
                        launch(Dispatchers.IO) {
                            val session = TcpServer.getClient(id)
                            val result = session?.runManagedCommand("getprop ro.product.model")
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

                    state.copy(
                        clientIds = ids,
                        clientModels = validModels,
                        selectedTarget = safeTarget,
                        fileManagerVisible = managerAlive,
                        fileEditorVisible = editorAlive,
                        fileManagerClientId = if (managerAlive || editorAlive) state.fileManagerClientId else null
                    )
                }
            }
        }

        viewModelScope.launch {
            frpManager.running.collect { running ->
                _uiState.update { it.copy(frpRunning = running) }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
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

            TcpServer.start(localPort)

            if (configExists) {
                if (useSuDefault && !suAvailable) {
                    FrpLogBus.append("[settings] su enabled but unavailable, start may fail")
                }
                frpManager.start(useSuDefault)
            }
        }
    }

    private fun copyAssetToFile(context: android.content.Context, assetName: String, outFile: File) {
        try {
            context.assets.open(assetName).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            FrpLogBus.append("[system] asset $assetName extracted success")
        } catch (e: Exception) {
            FrpLogBus.append("[init] failed to copy asset $assetName: ${e.message}")
        }
    }

    fun onSelectTarget(target: ShellTarget) {
        _uiState.update { it.copy(selectedTarget = target) }
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
        TcpServer.start(localPort)

        _uiState.update {
            it.copy(firstLaunchFlow = false, screen = ScreenDestination.Main, localPort = localPort, selectedTarget = ShellTarget.FrpLog)
        }
        FrpLogBus.append("[settings] saved (useSu=${state.useSu}, theme=${state.themeMode}, localPort=$localPort, font=${state.shellFontSizeSp})")
    }

    fun saveAndRestartFrp() {
        val state = _uiState.value
        frpManager.saveConfig(state.configContent)
        settingsStore.setUseSu(state.useSu)
        settingsStore.setThemeMode(state.themeMode)
        settingsStore.setShellFontSizeSp(state.shellFontSizeSp)

        val localPort = resolveLocalPort(state.configContent)
        TcpServer.start(localPort)

        _uiState.update {
            it.copy(firstLaunchFlow = false, screen = ScreenDestination.Main, localPort = localPort, selectedTarget = ShellTarget.FrpLog)
        }

        viewModelScope.launch {
            if (state.useSu && !state.suAvailable) {
                FrpLogBus.append("[settings] su enabled but unavailable, start may fail")
            }
            frpManager.restart(state.useSu)
        }
    }

    fun startFrp() {
        val state = _uiState.value
        viewModelScope.launch {
            if (state.useSu && !state.suAvailable) {
                FrpLogBus.append("[settings] su enabled but unavailable, start may fail")
            }
            frpManager.start(state.useSu)
        }
    }

    fun stopFrp() {
        viewModelScope.launch { frpManager.stop() }
    }

    fun sendCommand(command: String) {
        val target = _uiState.value.selectedTarget
        if (target is ShellTarget.Client) {
            TcpServer.getClient(target.id)?.send(command)
        }
    }

    fun openFileManager() {
        val target = _uiState.value.selectedTarget as? ShellTarget.Client ?: return
        _uiState.update {
            it.copy(fileManagerVisible = true, fileEditorVisible = false, fileManagerClientId = target.id, fileManagerPath = "/", fileManagerFiles = emptyList())
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
            FrpLogBus.append("[editor] no changes, skip upload")
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
                FrpLogBus.append("[editor] upload success: ${state.fileEditorRemotePath}")
                refreshCurrentDirectory()
            } else {
                FrpLogBus.append("[editor] upload failed: ${state.fileEditorRemotePath}")
            }
        }
    }

    fun captureScreen() {
        val target = _uiState.value.selectedTarget as? ShellTarget.Client ?: return
        if (_uiState.value.screenCaptureLoading) return

        captureJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(screenCaptureLoading = true, screenCaptureCancelable = false, screenCaptureLoadingText = "正在截屏...") }

            // Delay 4 seconds before showing cancel button
            val cancelTimer = launch {
                kotlinx.coroutines.delay(4000)
                _uiState.update { it.copy(screenCaptureCancelable = true) }
            }

            try {
                val session = TcpServer.getClient(target.id) ?: return@launch
                
                FrpLogBus.append("[screen] capturing...")
                // 1. Send screencap command
                session.runManagedCommand("screencap -j /data/local/tmp/tmp.jpg")
                
                // 2. Download the file
                val remotePath = "/data/local/tmp/tmp.jpg"
                val cacheFile = File(getApplication<Application>().cacheDir, "screen_${System.currentTimeMillis()}.jpg")
                
                // Don't show regular file transfer dialog
                // beginTransfer("获取截图中...") 
                val result = session.downloadFile(remotePath, cacheFile)
                // endTransfer()
                
                if (result == ClientSession.DownloadResult.Success) {
                     _uiState.update { 
                         it.copy(
                             screenViewerVisible = true, 
                             screenViewerImagePath = cacheFile.absolutePath,
                             screenViewerTimestamp = System.currentTimeMillis()
                         )
                     }
                     FrpLogBus.append("[screen] capture success")
                     
                     // Clean up remote file
                     session.runManagedCommand("rm /data/local/tmp/tmp.jpg")
                } else {
                     FrpLogBus.append("[screen] capture failed: download error")
                }
            } finally {
                cancelTimer.cancel()
                _uiState.update { it.copy(screenCaptureLoading = false) }
            }
        }
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

        captureJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(screenCaptureLoading = true, screenCaptureCancelable = false, screenCaptureLoadingText = "准备中...", screenCaptureLog = "") }

            val cancelTimer = launch {
                kotlinx.coroutines.delay(4000)
                _uiState.update { it.copy(screenCaptureCancelable = true) }
            }
            
            fun updateLog(msg: String) {
                _uiState.update { it.copy(screenCaptureLog = msg) }
            }

            try {
                val session = TcpServer.getClient(target.id) ?: return@launch
                
                // 1. Check tool
                _uiState.update { it.copy(screenCaptureLoadingText = "正在检查远程组件...") }
                
                val context = getApplication<Application>()
                val localJar = File(context.filesDir, "scrcpy-server.jar")
                
                // Ensure local file exists
                if (!localJar.exists() || localJar.length() == 0L) {
                    copyAssetToFile(context, "scrcpy-server.jar", localJar)
                }
                
                if (!localJar.exists() || localJar.length() == 0L) {
                    _uiState.update { it.copy(screenCaptureLoadingText = "本地组件缺失，无法继续") }
                    updateLog("错误：本地组件文件丢失")
                    return@launch
                }

                // Check remote file using shell conditional for robustness
                val checkCmd = "if [ -f /data/local/tmp/scrcpy-server.jar ]; then echo 'exists'; else echo 'missing'; fi"
                val checkResult = session.runManagedCommand(checkCmd)?.trim()
                val toolExists = checkResult?.contains("exists") == true
                
                if (!toolExists) {
                     _uiState.update { it.copy(screenCaptureLoadingText = "远程组件缺失，准备上传...") }
                     updateLog("正在上传 scrcpy-server.jar...")

                     val ok = session.uploadFile("/data/local/tmp/scrcpy-server.jar", localJar) { done, total ->
                         val percent = if (total > 0) (done * 100 / total).toInt() else 0
                         _uiState.update { it.copy(screenCaptureLoadingText = "正在上传组件: $percent%") }
                     }
                     
                     if (!ok) {
                         updateLog("错误：文件上传失败")
                         _uiState.update { it.copy(screenCaptureLoadingText = "组件上传失败") }
                         return@launch
                     }
                     
                     session.runManagedCommand("chmod 777 /data/local/tmp/scrcpy-server.jar")
                } else {
                     updateLog("远程组件检查通过")
                     _uiState.update { it.copy(screenCaptureLoadingText = "组件检查通过") }
                }

                // Double check existence before execution
                val verifyCmd = "ls -l /data/local/tmp/scrcpy-server.jar"
                val verifyResult = session.runManagedCommand(verifyCmd)
                if (verifyResult == null || !verifyResult.contains("scrcpy-server.jar")) {
                     updateLog("错误：组件校验失败，远程文件不可见")
                     _uiState.update { it.copy(screenCaptureLoadingText = "组件校验失败") }
                     return@launch
                }

                // 2. Take photo
                _uiState.update { it.copy(screenCaptureLoadingText = "正在执行拍照指令...") }
                updateLog("发送拍照指令 (camera_id=$cameraId)...")
                
                val cmd = "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process /data/local/tmp com.genymobile.scrcpy.Server video=true audio=false video_source=camera camera_id=$cameraId "
                
                // Fire and forget (in background)
                session.runManagedCommand(cmd)
                
                updateLog("等待拍照完成 (7s)...")
                kotlinx.coroutines.delay(7000)

                // 3. Check result file
                _uiState.update { it.copy(screenCaptureLoadingText = "正在验证结果...") }
                val remotePath = "/data/local/tmp/scrcpy_test.jpg"
                val checkPhotoCmd = "if [ -f $remotePath ]; then echo 'exists'; else echo 'missing'; fi"
                val photoCheckResult = session.runManagedCommand(checkPhotoCmd, timeoutMs = 32000)?.trim()
                
                if (photoCheckResult?.contains("exists") == true) {
                    _uiState.update { it.copy(screenCaptureLoadingText = "拍照成功，准备获取照片...") }
                    updateLog("开始下载照片...")
                    
                    val cacheFile = File(getApplication<Application>().cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                    
                    val result = session.downloadFile(remotePath, cacheFile) { done, total ->
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        _uiState.update { it.copy(screenCaptureLoadingText = "正在下载照片: $percent%") }
                    }
                    
                    if (result == ClientSession.DownloadResult.Success) {
                         updateLog("下载完成")
                         _uiState.update { 
                             it.copy(
                                 screenViewerVisible = true, 
                                 screenViewerImagePath = cacheFile.absolutePath,
                                 screenViewerTimestamp = System.currentTimeMillis()
                             )
                         }
                         session.runManagedCommand("rm $remotePath")
                    } else {
                        updateLog("错误：照片下载失败")
                        _uiState.update { it.copy(screenCaptureLoadingText = "下载失败") }
                    }
                } else {
                    updateLog("错误：未检测到照片生成 (超时)")
                    _uiState.update { it.copy(screenCaptureLoadingText = "拍照失败") }
                }

            } catch (e: Exception) {
                updateLog("异常：${e.message}")
            } finally {
                cancelTimer.cancel()
                _uiState.update { it.copy(screenCaptureLoading = false) }
            }
        }
    }

    fun cancelScreenCapture() {
        captureJob?.cancel()
        _uiState.update { it.copy(screenCaptureLoading = false) }
        FrpLogBus.append("[screen] capture cancelled by user")
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
            FrpLogBus.append("[file-manager] local file missing: ${localFile.absolutePath}")
            return
        }

        val cleanName = fileName.trim()
        if (cleanName.isBlank()) {
            FrpLogBus.append("[file-manager] invalid upload file name")
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
                FrpLogBus.append("[file-manager] upload success: $remotePath")
                refreshCurrentDirectory()
            } else {
                FrpLogBus.append("[file-manager] upload failed: $remotePath")
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
            ClientSession.DownloadResult.Success -> FrpLogBus.append("[file-manager] download success: $remotePath -> ${cacheFile.absolutePath}")
            ClientSession.DownloadResult.NotFound -> FrpLogBus.append("[file-manager] remote file not found: $remotePath")
            ClientSession.DownloadResult.Failed -> FrpLogBus.append("[file-manager] download failed: $remotePath")
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
                ClientSession.DownloadResult.Success -> FrpLogBus.append("[file-manager] download success: $remotePath -> ${localFile.absolutePath}")
                ClientSession.DownloadResult.NotFound -> FrpLogBus.append("[file-manager] remote file not found: $remotePath")
                ClientSession.DownloadResult.Failed -> FrpLogBus.append("[file-manager] download failed: $remotePath")
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
                FrpLogBus.append("[editor] download success: $remotePath")
            }

            ClientSession.DownloadResult.NotFound -> {
                FrpLogBus.append("[editor] remote file not found: $remotePath")
            }

            ClientSession.DownloadResult.Failed -> {
                FrpLogBus.append("[editor] download failed: $remotePath")
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
            if (it == null) FrpLogBus.append("[file-manager] command failed: $command")
        }
    }

    private fun currentFileManagerSession(): ClientSession? {
        val clientId = _uiState.value.fileManagerClientId ?: return null
        return TcpServer.getClient(clientId)
    }

    private fun resolveLocalPort(config: String): Int {
        val parsed = FrpManager.parseLocalPort(config)
        if (parsed == null) FrpLogBus.append("[config] localPort not found, fallback to $DEFAULT_LOCAL_PORT")
        else FrpLogBus.append("[config] localPort=$parsed")
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
        TcpServer.stopAll()
    }

    companion object {
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
