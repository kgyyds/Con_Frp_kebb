package com.kgapp.frpshellpro.frp

import kotlinx.coroutines.flow.MutableStateFlow

object FrpLogBus {
    val logs = MutableStateFlow("")
    fun append(line: String) {
        logs.value += line + "\n"
    }
}