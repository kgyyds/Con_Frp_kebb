package com.kgapp.frpshell.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
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
import com.kgapp.frpshell.model.ShellTarget
import com.kgapp.frpshell.ui.theme.FrpShellTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(vm: MainViewModel = viewModel()) {
    val uiState by vm.uiState.collectAsState()

    FrpShellTheme(themeMode = uiState.themeMode) {
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
                                if (isSettings) "设置" else if (uiState.fileEditorVisible) "文件编辑" else if (uiState.fileManagerVisible) "文件管理" else "FRP Shell",
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    when {
                                        isSettings -> vm.navigateBackToMain()
                                        uiState.fileEditorVisible -> vm.closeFileEditor()
                                        uiState.fileManagerVisible -> vm.closeFileManager()
                                        else -> scope.launch { drawerState.open() }
                                    }
                                }
                            ) {
                                Icon(
                                    if (isSettings || uiState.fileManagerVisible || uiState.fileEditorVisible) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                                    contentDescription = if (isSettings || uiState.fileManagerVisible || uiState.fileEditorVisible) "back" else "open drawer"
                                )
                            }
                        },
                        actions = {
                            if (!isSettings) {
                                if (!uiState.fileManagerVisible && !uiState.fileEditorVisible && uiState.selectedTarget is ShellTarget.Client) {
                                    IconButton(onClick = vm::openFileManager) {
                                        Icon(Icons.Default.Folder, contentDescription = "file manager")
                                    }
                                }
                                IconButton(onClick = vm::openSettings) {
                                    Icon(Icons.Default.Settings, contentDescription = "settings")
                                }
                            }
                        }
                    )
                }
            ) { padding ->
                when {
                    isSettings -> {
                        SettingsScreen(
                            configContent = uiState.configContent,
                            useSu = uiState.useSu,
                            suAvailable = uiState.suAvailable,
                            themeMode = uiState.themeMode,
                            shellFontSizeSp = uiState.shellFontSizeSp,
                            firstLaunchFlow = uiState.firstLaunchFlow,
                            onConfigChanged = vm::onConfigChanged,
                            onUseSuChanged = vm::onUseSuChanged,
                            onThemeModeChanged = vm::onThemeModeChanged,
                            onShellFontSizeChanged = vm::onShellFontSizeChanged,
                            onSave = vm::saveConfigOnly,
                            onSaveAndRestart = vm::saveAndRestartFrp,
                            contentPadding = padding
                        )
                    }

                    uiState.fileEditorVisible -> {
                        FileEditorScreen(
                            remotePath = uiState.fileEditorRemotePath,
                            content = uiState.fileEditorContent,
                            fontSizeSp = uiState.shellFontSizeSp,
                            contentPadding = padding,
                            onContentChange = vm::onEditorContentChanged,
                            onSave = vm::saveEditor,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    uiState.fileManagerVisible -> {
                        FileManagerScreen(
                            currentPath = uiState.fileManagerPath,
                            files = uiState.fileManagerFiles,
                            contentPadding = padding,
                            onRefresh = vm::fileManagerRefresh,
                            onBackDirectory = vm::fileManagerBackDirectory,
                            onOpenFile = vm::fileManagerOpen,
                            onRename = vm::fileManagerRename,
                            onChmod = vm::fileManagerChmod,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    else -> {
                        ShellScreen(
                            modifier = Modifier.fillMaxSize(),
                            target = uiState.selectedTarget,
                            fontSizeSp = uiState.shellFontSizeSp,
                            frpRunning = uiState.frpRunning,
                            onStartFrp = vm::startFrp,
                            onStopFrp = vm::stopFrp,
                            onSend = vm::sendCommand,
                            contentPadding = padding
                        )
                    }
                }
            }
        }
    }
}
