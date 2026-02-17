package com.kgapp.frpshell.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.sp
import com.kgapp.frpshell.frp.FrpLogBus
import com.kgapp.frpshell.model.ShellTarget

@Composable
fun ShellScreen(
    target: ShellTarget,
    fontSizeSp: Float,
    commandItems: List<ShellCommandItem>,
    frpRunning: Boolean,
    onStartFrp: () -> Unit,
    onStopFrp: () -> Unit,
    onSend: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    var input by remember(target.id) { mutableStateOf("") }
    val frpLog by FrpLogBus.logs.collectAsState()
    val parsedBuffer = remember(target.id) { AnsiAnnotatedBuffer() }
    val listState = rememberLazyListState()
    val frpScroll = rememberScrollState()

    fun submit() {
        val cmd = input
        if (cmd.isNotBlank() && target is ShellTarget.Client) {
            onSend(cmd)
        }
        input = ""
    }

    LaunchedEffect(target.id, commandItems.size, frpLog.length) {
        if (target is ShellTarget.Client && commandItems.isNotEmpty()) {
            listState.animateScrollToItem(commandItems.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (target is ShellTarget.FrpLog) {
            Text(
                text = parsedBuffer.update(frpLog),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(frpScroll),
                fontFamily = FontFamily.Monospace,
                fontSize = fontSizeSp.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onStartFrp, enabled = !frpRunning) { Text("启动 frp") }
                Button(onClick = onStopFrp, enabled = frpRunning) { Text("停止 frp") }
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(commandItems) { _, item ->
                Text(
                    text = "$ ${item.commandText}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSizeSp.sp
                )
                if (item.outputText.isNotBlank()) {
                    Text(
                        text = parsedBuffer.update(item.outputText),
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSizeSp.sp
                    )
                }
                val statusHint = if (item.status == ShellCommandStatus.RUNNING) "执行中..." else "命令完成"
                Text(
                    text = statusHint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (fontSizeSp - 2f).coerceAtLeast(10f).sp
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入命令...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSizeSp.sp
                )
            )
            Button(onClick = { submit() }) { Text("发送") }
        }
    }
}
