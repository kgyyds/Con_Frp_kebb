package com.kgapp.frpshellpro.ui.state

import com.kgapp.frpshellpro.ui.ShellCommandItem

data class ShellUiState(
    val shellItemsByClient: Map<String, List<ShellCommandItem>> = emptyMap()
)
