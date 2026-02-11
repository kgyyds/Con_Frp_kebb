package com.kgapp.frpshell.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.kgapp.frpshell.frp.FrpLogBus
import com.kgapp.frpshell.model.ShellTarget
import com.kgapp.frpshell.server.TcpServer

@Composable
fun ShellScreen(target: ShellTarget) {

    var input by remember { mutableStateOf("") }

    val output = when (target) {
        is ShellTarget.FrpLog -> FrpLogBus.logs.collectAsState()
        is ShellTarget.Client -> TcpServer.clients[target.id]!!
            .output.collectAsState()
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            output.value,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(">") },
            singleLine = true,
            onValueChangeFinished = {
                if (target is ShellTarget.Client) {
                    TcpServer.clients[target.id]?.sendQueue?.offer(input)
                }
                input = ""
            }
        )
    }
}