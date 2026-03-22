package com.kgapp.frpshellpro.ui

import java.io.File

enum class RemoteFileType {
    Directory,
    File
}

data class RemoteFileItem(
    val path: String,
    val file: Boolean
) {
    val name: String
        get() = File(path).name.ifBlank { path }

    val type: RemoteFileType
        get() = if (file) RemoteFileType.File else RemoteFileType.Directory
}
