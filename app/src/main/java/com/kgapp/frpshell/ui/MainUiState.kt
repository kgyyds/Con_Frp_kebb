package com.kgapp.frpshellpro.ui

import com.kgapp.frpshellpro.model.ShellTarget
import com.kgapp.frpshellpro.ui.state.CaptureUiState
import com.kgapp.frpshellpro.ui.state.FileManagerUiState
import com.kgapp.frpshellpro.ui.state.FrpUiState
import com.kgapp.frpshellpro.ui.state.ShellUiState
import com.kgapp.frpshellpro.ui.theme.ThemeMode

data class AppInfo(
    val packageName: String,
    val name: String,
    val system: Boolean
)

enum class ScreenDestination {
    Main,
    Settings,
    DeviceInfo,
    GetInfoPlugin,
    GetLocPlugin
}

data class CallLogItem(
    val number: String,
    val date: Long,
    val duration: Long,
    val type: Int
)

data class SmsItem(
    val address: String,
    val body: String,
    val timestamp: Long
)

data class ContactItem(
    val displayName: String,
    val phone: String
)

data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val time: String
)

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
    val compressTarget: RemoteFileItem? = null,
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
    val deviceInfoJson: String? = null,
    val deviceInfoClientId: String? = null,
    val deviceInfoLoading: Boolean = false,
    val deviceInfoErrorMessage: String? = null,
    val deviceInfoCards: List<DeviceInfoCard> = emptyList(),
    val pluginClientId: String? = null,
    val callLogLoading: Boolean = false,
    val callLogErrorMessage: String? = null,
    val callLogCountInput: String = "5",
    val callLogItems: List<CallLogItem> = emptyList(),
    val smsLoading: Boolean = false,
    val smsErrorMessage: String? = null,
    val smsCountInput: String = "3",
    val smsItems: List<SmsItem> = emptyList(),
    val contactLoading: Boolean = false,
    val contactErrorMessage: String? = null,
    val contactCountInput: String = "5",
    val contactItems: List<ContactItem> = emptyList(),
    val getLocLoading: Boolean = false,
    val getLocErrorMessage: String? = null,
    val getLocStatusMessage: String? = null,
    val locationInfo: LocationInfo? = null,
    val locationAddressIntlLoading: Boolean = false,
    val locationAddressIntl: String? = null,
    val locationAddressIntlErrorMessage: String? = null,
    val locationAddressCnLoading: Boolean = false,
    val locationAddressCn: String? = null,
    val locationAddressCnErrorMessage: String? = null,
    val clientModels: Map<String, ClientDisplayInfo> = emptyMap(),
    val shellItemsByClient: Map<String, List<ShellCommandItem>> = emptyMap(),
    val quickCommands: List<QuickCommandItem> = emptyList()
)

data class ClientDisplayInfo(
    val modelName: String,
    val serialNo: String
)


data class DeviceInfoCard(
    val title: String,
    val iconName: String,
    val accentType: DeviceInfoAccentType,
    val metrics: List<DeviceInfoMetric>
)

data class DeviceInfoMetric(
    val label: String,
    val value: String,
    val progress: Float? = null
)

enum class DeviceInfoAccentType {
    Primary,
    Secondary,
    Tertiary
}
