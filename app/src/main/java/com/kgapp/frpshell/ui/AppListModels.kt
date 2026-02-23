package com.kgapp.frpshellpro.ui

data class AppListUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val loadingText: String = "正在获取应用列表...",
    val apps: List<AppInfo> = emptyList(),
    val errorMessage: String? = null
)
