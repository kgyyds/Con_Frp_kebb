package com.kgapp.frpshellpro.model

sealed class ShellTarget(val id: String) {
    data object FrpLog : ShellTarget("FRP_LOG")
    data class Client(private val clientId: String) : ShellTarget(clientId)
}
