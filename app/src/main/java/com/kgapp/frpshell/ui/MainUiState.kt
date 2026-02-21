package com.kgapp.frpshellpro.ui

import com.kgapp.frpshellpro.model.ShellTarget
import com.kgapp.frpshellpro.ui.state.CaptureUiState
import com.kgapp.frpshellpro.ui.state.FileManagerUiState
import com.kgapp.frpshellpro.ui.state.FrpUiState
import com.kgapp.frpshellpro.ui.state.ShellUiState

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
      val localPort: Int = 23231,
    val shellFontSizeSp: Float = SettingsStore.DEFAULT_FONT_SIZE_SP,
    val uploadScriptContent: String = "",
    val recordStreamHost: String = SettingsStore.DEFAULT_RECORD_STREAM_HOST,
    val recordStreamPort: String = SettingsStore.DEFAULT_RECORD_STREAM_PORT.toString(),
    val recordStartTemplate: String = SettingsStore.DEFAULT_RECORD_START_TEMPLATE,
    val recordStopTemplate: String = SettingsStore.DEFAULT_RECORD_STOP_TEMPLATE,
    val recordConfigErrorMessage: String? = null,
    val shellUiState: ShellUiState = ShellUiState(),
    val fileManagerUiState: FileManagerUiState = FileManagerUiState(),
    val captureUiState: CaptureUiState = CaptureUiState(),
    val frpUiState: FrpUiState = FrpUiState(),
    val frpRunning: Boolean = false,
    val fileManagerVisible: Boolean = false,
    val processListVisible: Boolean = false,
    val processLoading: Boolean = false,
    val processErrorMessage: String? = null,
    val processItems: List<ClientProcessInfo> = emptyList(),
    val processSortField: ProcessSortField = ProcessSortField.RSS,
    val processSortAscending: Boolean = false,
    val processPendingKill: ClientProcessInfo? = null,
    val fileManagerClientId: String? = null,
    val fileManagerPath: String = "/",
    val fileManagerFiles: List<RemoteFileItem> = emptyList(),
    val fileManagerErrorMessage: String? = null,
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
    val clientModels: Map<String, ClientDisplayInfo> = emptyMap(),
    val shellItemsByClient: Map<String, List<ShellCommandItem>> = emptyMap()
)

data class ClientDisplayInfo(
    val modelName: String,
    val serialNo: String
)
