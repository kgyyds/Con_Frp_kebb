package com.kgapp.frpshell.frp

import kotlinx.coroutines.flow.MutableStateFlow

object FrpLogBus {
    val logs = MutableStateFlow("")
    fun append(line: String) {
        logs.value += line + "\n"
    }
}