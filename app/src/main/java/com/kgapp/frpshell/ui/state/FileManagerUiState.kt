package com.kgapp.frpshellpro.ui.state

import com.kgapp.frpshellpro.ui.RemoteFileItem

data class FileManagerUiState(
    val visible: Boolean = false,
    val clientId: String? = null,
    val path: String = "/",
    val files: List<RemoteFileItem> = emptyList(),
    val errorMessage: String? = null,
    val editorVisible: Boolean = false,
    val editorRemotePath: String = "",
    val editorCachePath: String = "",
    val editorOriginalContent: String = "",
    val editorContent: String = "",
    val editorConfirmDiscardVisible: Boolean = false,
    val transferVisible: Boolean = false,
    val transferTitle: String = "",
    val transferDone: Long = 0L,
    val transferTotal: Long = 0L
)
