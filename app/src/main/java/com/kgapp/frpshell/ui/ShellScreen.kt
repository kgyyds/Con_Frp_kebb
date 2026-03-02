package com.kgapp.frpshellpro.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kgapp.frpshellpro.frp.FrpLogBus
import com.kgapp.frpshellpro.model.ShellTarget

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShellScreen(
    target: ShellTarget,
    fontSizeSp: Float,
    commandItems: List<ShellCommandItem>,
    frpRunning: Boolean,
    onStartFrp: () -> Unit,
    onStopFrp: () -> Unit,
    onSend: (String) -> Unit,
    quickCommands: List<QuickCommandItem>,
    onAddQuickCommand: (alias: String, command: String) -> Unit,
    onUpdateQuickCommand: (oldAlias: String, newAlias: String, command: String) -> Unit,
    onDeleteQuickCommand: (alias: String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    var input by remember(target.id) { mutableStateOf("") }
    var showQuickMenu by remember(target.id) { mutableStateOf(false) }

    var showAddDialog by remember(target.id) { mutableStateOf(false) }
    var dialogAlias by remember(target.id) { mutableStateOf("") }
    var dialogCommand by remember(target.id) { mutableStateOf("") }

    var actionTarget by remember(target.id) { mutableStateOf<QuickCommandItem?>(null) }
    var editTarget by remember(target.id) { mutableStateOf<QuickCommandItem?>(null) }

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
            .consumeWindowInsets(contentPadding)
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
                androidx.compose.material3.Button(onClick = onStartFrp, enabled = !frpRunning) { Text("启动 frp") }
                androidx.compose.material3.Button(onClick = onStopFrp, enabled = frpRunning) { Text("停止 frp") }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(commandItems) { _, item ->
                    Text(text = "$ ${item.commandText}", fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp)
                    if (item.outputText.isNotBlank()) {
                        Text(text = parsedBuffer.update(item.outputText), fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp)
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
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box {
                    IconButton(onClick = { showQuickMenu = true }) {
                        Icon(imageVector = Icons.Filled.Bookmarks, contentDescription = "快捷命令")
                    }
                    DropdownMenu(expanded = showQuickMenu, onDismissRequest = { showQuickMenu = false }) {
                        if (quickCommands.isEmpty()) {
                            DropdownMenuItem(text = { Text("暂无快捷命令") }, onClick = {})
                        } else {
                            quickCommands.forEach { quick ->
                                DropdownMenuItem(
                                    text = {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .fillMaxHeight()
                                                .combinedClickable(
                                                    onClick = {
                                                        input = quick.command
                                                        showQuickMenu = false
                                                    },
                                                    onLongClick = {
                                                        showQuickMenu = false
                                                        actionTarget = quick
                                                    }
                                                ),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Text(quick.alias)
                                        }
                                    },
                                    onClick = {}
                                )
                            }
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ 添加快捷命令") },
                            onClick = {
                                dialogAlias = ""
                                dialogCommand = ""
                                showQuickMenu = false
                                showAddDialog = true
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入命令...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp)
                )
                androidx.compose.material3.Button(onClick = { submit() }) { Text("发送") }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加快捷命令") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = dialogAlias, onValueChange = { dialogAlias = it }, label = { Text("命令别名") }, singleLine = true)
                    OutlinedTextField(value = dialogCommand, onValueChange = { dialogCommand = it }, label = { Text("命令内容") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAddQuickCommand(dialogAlias, dialogCommand)
                    if (dialogAlias.isNotBlank() && dialogCommand.isNotBlank()) {
                        showAddDialog = false
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("取消") } }
        )
    }

    actionTarget?.let { targetQuick ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(targetQuick.alias) },
            text = { Text("请选择操作") },
            confirmButton = {
                TextButton(onClick = {
                    editTarget = targetQuick
                    dialogAlias = targetQuick.alias
                    dialogCommand = targetQuick.command
                    actionTarget = null
                }) { Text("修改") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        onDeleteQuickCommand(targetQuick.alias)
                        actionTarget = null
                    }) { Text("删除") }
                    TextButton(onClick = { actionTarget = null }) { Text("取消") }
                }
            }
        )
    }

    editTarget?.let { targetQuick ->
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("修改快捷命令") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = dialogAlias, onValueChange = { dialogAlias = it }, label = { Text("命令别名") }, singleLine = true)
                    OutlinedTextField(value = dialogCommand, onValueChange = { dialogCommand = it }, label = { Text("命令内容") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateQuickCommand(targetQuick.alias, dialogAlias, dialogCommand)
                    if (dialogAlias.isNotBlank() && dialogCommand.isNotBlank()) {
                        editTarget = null
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("取消") } }
        )
    }
}
