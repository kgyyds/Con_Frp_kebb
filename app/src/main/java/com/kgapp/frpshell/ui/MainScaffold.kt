package com.kgapp.frpshell.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(vm: MainViewModel = viewModel()) {
    val uiState by vm.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val isSettings = uiState.screen == ScreenDestination.Settings

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !isSettings,
        drawerContent = {
            if (!isSettings) {
                ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.34f)) {
                    DrawerContent(
                        current = uiState.selectedTarget,
                        clientIds = uiState.clientIds,
                        onSelect = {
                            vm.onSelectTarget(it)
                            scope.launch { drawerState.close() }
                        }
                    )
                }
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (isSettings) "设置" else "FRP Shell",
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (isSettings) {
                                    vm.navigateBackToMain()
                                } else {
                                    scope.launch { drawerState.open() }
                                }
                            }
                        ) {
                            Icon(
                                if (isSettings) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                                contentDescription = if (isSettings) "back" else "open drawer"
                            )
                        }
                    },
                    actions = {
                        if (!isSettings) {
                            IconButton(onClick = vm::openSettings) {
                                Icon(Icons.Default.Settings, contentDescription = "settings")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            if (isSettings) {
                SettingsScreen(
                    configContent = uiState.configContent,
                    useSu = uiState.useSu,
                    suAvailable = uiState.suAvailable,
                    firstLaunchFlow = uiState.firstLaunchFlow,
                    onConfigChanged = vm::onConfigChanged,
                    onUseSuChanged = vm::onUseSuChanged,
                    onSave = vm::saveConfigOnly,
                    onSaveAndRestart = vm::saveAndRestartFrp,
                    contentPadding = padding
                )
            } else {
                ShellScreen(
                    modifier = Modifier.fillMaxSize(),
                    target = uiState.selectedTarget,
                    onSend = vm::sendCommand,
                    contentPadding = padding
                )
            }
        }
    }
}
