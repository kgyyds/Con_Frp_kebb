package com.kgapp.frpshell.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.kgapp.frpshell.model.ShellTarget
import com.kgapp.frpshell.server.TcpServer

@Composable
fun DrawerContent(
    current: ShellTarget,
    onSelect: (ShellTarget) -> Unit
) {
    Column {
        NavigationDrawerItem(
            label = { Text("FRP 日志") },
            selected = current is ShellTarget.FrpLog,
            onClick = { onSelect(ShellTarget.FrpLog) }
        )

        TcpServer.clients.values.forEach {
            NavigationDrawerItem(
                label = { Text(it.id) },
                selected = current is ShellTarget.Client && current.id == it.id,
                onClick = { onSelect(ShellTarget.Client(it.id)) }
            )
        }
    }
}