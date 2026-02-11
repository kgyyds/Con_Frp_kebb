package com.kgapp.frpshell.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kgapp.frpshell.frp.FrpLogBus
import com.kgapp.frpshell.model.ShellTarget
import com.kgapp.frpshell.server.TcpServer

@Composable
fun ShellScreen(
    target: ShellTarget,
    onSend: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    var input by remember(target.id) { mutableStateOf("") }
    val output by when (target) {
        is ShellTarget.FrpLog -> FrpLogBus.logs.collectAsState()
        is ShellTarget.Client -> (TcpServer.getClient(target.id)?.output ?: remember { kotlinx.coroutines.flow.MutableStateFlow("client disconnected\n") }).collectAsState()
    }
    val scrollState = rememberScrollState()

    fun submit() {
        val cmd = input
        if (cmd.isNotBlank() && target is ShellTarget.Client) {
            onSend(cmd)
        }
        input = ""
    }

    LaunchedEffect(output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = modifier
            .padding(contentPadding)
            .padding(12.dp)
    ) {
        Text(
            text = output,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            fontFamily = FontFamily.Monospace
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(">") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() })
            )
            Button(onClick = { submit() }) {
                Text("发送")
            }
        }
    }
}
