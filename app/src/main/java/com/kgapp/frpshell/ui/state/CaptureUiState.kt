package com.kgapp.frpshellpro.ui.state

data class CaptureUiState(
    val screenViewerVisible: Boolean = false,
    val screenViewerImagePath: String = "",
    val screenViewerTimestamp: Long = 0L,
    val loading: Boolean = false,
    val loadingText: String = "正在截屏...",
    val log: String = "",
    val cancelable: Boolean = false,
    val cameraSelectorVisible: Boolean = false
)
