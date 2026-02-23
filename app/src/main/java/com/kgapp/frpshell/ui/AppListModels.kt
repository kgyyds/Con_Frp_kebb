package com.kgapp.frpshell.ui

import kotlinx.serialization.Serializable

@Serializable
data class AppInfo(
    val package_name: String,
    val name: String,
    val system: Boolean
)

data class AppListUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val loadingText: String = "正在获取应用列表...",
    val apps: List<AppInfo> = emptyList(),
    val errorMessage: String? = null
)