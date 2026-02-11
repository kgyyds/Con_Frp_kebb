package com.kgapp.frpshell.model

sealed class ShellTarget(val id: String) {
    object FrpLog : ShellTarget("FRP_LOG")
    class Client(id: String) : ShellTarget(id)
}