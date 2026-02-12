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
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val frpManager = FrpManager(application.applicationContext, viewModelScope)
    private val settingsStore = SettingsStore(application.applicationContext)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        TcpServer.start()
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

        viewModelScope.launch(Dispatchers.IO) {
            val initialized = settingsStore.isInitialized()
            val suAvailable = FrpManager.detectSuAvailable()
            val useSuDefault = if (initialized) settingsStore.getUseSu() else suAvailable
            val themeMode = settingsStore.getThemeMode()
            if (!initialized) {
                settingsStore.setUseSu(useSuDefault)
                settingsStore.setThemeMode(ThemeMode.SYSTEM)
                settingsStore.setInitialized(true)
            }

            val configExists = frpManager.configExists()
            val content = if (configExists) frpManager.readConfig() else DEFAULT_CONFIG_TEMPLATE

            _uiState.update {
                it.copy(
                    screen = if (configExists) ScreenDestination.Main else ScreenDestination.Settings,
                    firstLaunchFlow = !configExists,
                    configContent = content,
                    suAvailable = suAvailable,
                    useSu = useSuDefault,
                    themeMode = themeMode
                )
            }

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
        _uiState.update { it.copy(firstLaunchFlow = false, screen = ScreenDestination.Main) }
        FrpLogBus.append("[settings] saved (useSu=${state.useSu}, theme=${state.themeMode})")
    }

    fun saveAndRestartFrp() {
        val state = _uiState.value
        frpManager.saveConfig(state.configContent)
        settingsStore.setUseSu(state.useSu)
        settingsStore.setThemeMode(state.themeMode)
        _uiState.update { it.copy(firstLaunchFlow = false, screen = ScreenDestination.Main) }

        viewModelScope.launch {
            if (state.useSu && !state.suAvailable) {
                FrpLogBus.append("[settings] su enabled but unavailable, start may fail")
            }
            frpManager.restart(state.useSu)
        }
    }

    fun sendCommand(command: String) {
        val target = _uiState.value.selectedTarget
        if (target is ShellTarget.Client) {
            TcpServer.getClient(target.id)?.send(command)
        }
    }

    companion object {
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
