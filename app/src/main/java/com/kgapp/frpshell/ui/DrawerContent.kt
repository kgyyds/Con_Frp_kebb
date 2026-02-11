package com.kgapp.frpshell.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kgapp.frpshell.model.ShellTarget

@Composable
fun DrawerContent(
    current: ShellTarget,
    clientIds: List<String>,
    onSelect: (ShellTarget) -> Unit
) {
    Text(text = "会话", modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

    NavigationDrawerItem(
        label = { Text("frp 日志") },
        selected = current is ShellTarget.FrpLog,
        onClick = { onSelect(ShellTarget.FrpLog) }
    )

    clientIds.forEach { id ->
        NavigationDrawerItem(
            label = { Text(id) },
            selected = current is ShellTarget.Client && current.id == id,
            onClick = { onSelect(ShellTarget.Client(id)) }
        )
    }
}
