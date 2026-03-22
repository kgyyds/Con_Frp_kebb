package com.kgapp.frpshellpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
    boardCodeByClientId: Map<String, String>,
    clientModels: Map<String, ClientDisplayInfo>,
    onSelect: (ShellTarget) -> Unit
) {
    Column {
        Text(text = "会话", modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

        NavigationDrawerItem(
            label = { Text("日志") },
            selected = current is ShellTarget.FrpLog,
            onClick = { onSelect(ShellTarget.FrpLog) }
        )

        LazyColumn(modifier = Modifier.weight(1f, fill = true)) {
            items(clientIds, key = { it }) { id ->
                val displayInfo = clientModels[id]
                val boardCodeTitle = boardCodeByClientId[id] ?: id
                val selected = current is ShellTarget.Client && current.id == id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable { onSelect(ShellTarget.Client(id)) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(boardCodeTitle)
                        Text("机型：${displayInfo?.modelName ?: "--"}")
                        Text("电量：${displayInfo?.batteryPercent ?: "--"}")
                        Text("开机时间：${displayInfo?.uptimeHm ?: "--"}")
                    }
                }
            }
        }
    }
}
