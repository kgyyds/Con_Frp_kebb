package com.kgapp.frpshellpro.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kgapp.frpshellpro.model.ShellTarget

@Composable
fun DrawerContent(
    current: ShellTarget,
    clientIds: List<String>,
    clientModels: Map<String, ClientDisplayInfo>,
    onSelect: (ShellTarget) -> Unit
) {
    Text(text = "会话", modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

    NavigationDrawerItem(
        label = { Text("frp 日志") },
        selected = current is ShellTarget.FrpLog,
        onClick = { onSelect(ShellTarget.FrpLog) }
    )

    clientIds.forEach { id ->
        val displayInfo = clientModels[id]
        NavigationDrawerItem(
            label = {
                Column {
                    Text(displayInfo?.modelName ?: id)
                    Text(displayInfo?.serialNo ?: id)
                }
            },
            selected = current is ShellTarget.Client && current.id == id,
            onClick = { onSelect(ShellTarget.Client(id)) }
        )
    }
}
