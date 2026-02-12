package com.kgapp.frpshell.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kgapp.frpshell.frp.FrpLogBus
import com.kgapp.frpshell.frp.FrpManager
import com.kgapp.frpshell.model.ShellTarget
import com.kgapp.frpshell.server.TcpServer
import com.kgapp.frpshell.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val frpRunning: Boolean = false
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
                    state.copy(clientIds = ids, selectedTarget = safeTarget)
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
            it.copy(
                firstLaunchFlow = false,
                screen = ScreenDestination.Main,
                localPort = localPort,
                selectedTarget = ShellTarget.FrpLog
            )
        }
        FrpLogBus.append(
            "[settings] saved (useSu=${state.useSu}, theme=${state.themeMode}, localPort=$localPort, font=${state.shellFontSizeSp})"
        )
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
            it.copy(
                firstLaunchFlow = false,
                screen = ScreenDestination.Main,
                localPort = localPort,
                selectedTarget = ShellTarget.FrpLog
            )
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
        viewModelScope.launch {
            frpManager.stop()
        }
    }

    fun sendCommand(command: String) {
        val target = _uiState.value.selectedTarget
        if (target is ShellTarget.Client) {
            TcpServer.getClient(target.id)?.send(command)
        }
    }

    private fun resolveLocalPort(config: String): Int {
        val parsed = FrpManager.parseLocalPort(config)
        if (parsed == null) {
            FrpLogBus.append("[config] localPort not found, fallback to $DEFAULT_LOCAL_PORT")
        } else {
            FrpLogBus.append("[config] localPort=$parsed")
        }
        return parsed ?: DEFAULT_LOCAL_PORT
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
