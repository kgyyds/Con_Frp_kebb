package com.kgapp.frpshell.ui

import com.kgapp.frpshell.model.ShellTarget
import com.kgapp.frpshell.ui.theme.ThemeMode

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
    val clientModels: Map<String, String> = emptyMap(),
    val shellItemsByClient: Map<String, List<ShellCommandItem>> = emptyMap()
)
