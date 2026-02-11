package com.kgapp.frpshell.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kgapp.frpshell.frp.FrpManager
import com.kgapp.frpshell.model.ShellTarget
import com.kgapp.frpshell.server.TcpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val selectedTarget: ShellTarget = ShellTarget.FrpLog,
    val clientIds: List<String> = emptyList(),
    val showConfigEditor: Boolean = false,
    val configContent: String = "",
    val firstLaunchFlow: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val frpManager = FrpManager(application.applicationContext, viewModelScope)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        TcpServer.start()
        viewModelScope.launch {
            TcpServer.clientIds.collect { ids ->
                _uiState.update { state ->
                    val current = state.selectedTarget
                    val safeTarget = if (current is ShellTarget.Client && current.id !in ids) {
                        ShellTarget.FrpLog
                    } else {
                        current
                    }
                    state.copy(clientIds = ids, selectedTarget = safeTarget)
                }
            }
        }

        val configExists = frpManager.configExists()
        val content = if (configExists) frpManager.readConfig() else DEFAULT_CONFIG_TEMPLATE
        _uiState.update {
            it.copy(
                showConfigEditor = !configExists,
                firstLaunchFlow = !configExists,
                configContent = content
            )
        }
        if (configExists) {
            frpManager.start()
        }
    }

    fun onSelectTarget(target: ShellTarget) {
        _uiState.update { it.copy(selectedTarget = target) }
    }

    fun onConfigChanged(content: String) {
        _uiState.update { it.copy(configContent = content) }
    }

    fun saveConfigOnly() {
        val content = _uiState.value.configContent
        frpManager.saveConfig(content)
        _uiState.update { it.copy(showConfigEditor = false, firstLaunchFlow = false) }
    }

    fun saveAndRestartFrp() {
        saveConfigOnly()
        viewModelScope.launch {
            frpManager.restart()
        }
    }

    fun openSettings() {
        _uiState.update { it.copy(showConfigEditor = true, firstLaunchFlow = false) }
    }

    fun closeConfigEditor() {
        _uiState.update { state ->
            if (state.firstLaunchFlow) state else state.copy(showConfigEditor = false)
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
