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
    val fileEditorConfirmDiscardVisible: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val frpManager = FrpManager(application.applicationContext, viewModelScope)
    private val settingsStore = SettingsStore(application.applicationContext)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            TcpServer.clientIds.collect { ids ->
                _uiState.update { state ->
                    val safeTarget = if (state.selectedTarget is ShellTarget.Client && state.selectedTarget.id !in ids) {
                        ShellTarget.FrpLog
                    } else {
                        state.selectedTarget
                    }
                    val managerAlive = state.fileManagerVisible && state.fileManagerClientId in ids
                    val editorAlive = state.fileEditorVisible && state.fileManagerClientId in ids
                    state.copy(
                        clientIds = ids,
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
                fileEditorConfirmDiscardVisible = false
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
            val ok = session.uploadFile(state.fileEditorRemotePath, cacheFile)
            if (ok) {
                _uiState.update { it.copy(fileEditorOriginalContent = it.fileEditorContent, fileEditorConfirmDiscardVisible = false) }
                FrpLogBus.append("[editor] upload success: ${state.fileEditorRemotePath}")
                refreshCurrentDirectory()
            } else {
                FrpLogBus.append("[editor] upload failed: ${state.fileEditorRemotePath}")
            }
        }
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
            val ok = session.uploadFile(remotePath, localFile)
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

        when (session.downloadFile(remotePath, cacheFile)) {
            ClientSession.DownloadResult.Success -> FrpLogBus.append("[file-manager] download success: $remotePath -> ${cacheFile.absolutePath}")
            ClientSession.DownloadResult.NotFound -> FrpLogBus.append("[file-manager] remote file not found: $remotePath")
            ClientSession.DownloadResult.Failed -> FrpLogBus.append("[file-manager] download failed: $remotePath")
        }
    }

    private suspend fun openEditorForRemoteFile(item: RemoteFileItem) {
        val state = _uiState.value
        val remotePath = appendPath(state.fileManagerPath, item.name)
        val cacheFile = File(getApplication<Application>().cacheDir, "editor_${state.fileManagerClientId}_${item.name}.tmp")
        val session = currentFileManagerSession() ?: return

        when (session.downloadFile(remotePath, cacheFile)) {
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
