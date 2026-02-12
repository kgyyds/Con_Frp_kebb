package com.kgapp.frpshell.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(
    currentPath: String,
    files: List<RemoteFileItem>,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onBackDirectory: () -> Unit,
    onOpenFile: (RemoteFileItem) -> Unit,
    onEditFile: (RemoteFileItem) -> Unit,
    onUploadFile: () -> Unit,
    onRename: (RemoteFileItem, String) -> Unit,
    onChmod: (RemoteFileItem, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var actionItem by remember { mutableStateOf<RemoteFileItem?>(null) }
    var renameTarget by remember { mutableStateOf<RemoteFileItem?>(null) }
    var chmodTarget by remember { mutableStateOf<RemoteFileItem?>(null) }
    var editTarget by remember { mutableStateOf<RemoteFileItem?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = "当前路径：$currentPath", style = MaterialTheme.typography.titleSmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBackDirectory, enabled = currentPath != "/") {
                Text("上级目录")
            }
            Button(onClick = onRefresh) {
                Text("刷新")
            }
            Button(onClick = onUploadFile) {
                Text("上传文件")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(files, key = { "${it.type}:${it.name}" }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onOpenFile(item) },
                            onLongClick = { actionItem = item }
                        )
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = when (item.type) {
                            RemoteFileType.Directory -> Icons.Outlined.Folder
                            RemoteFileType.Executable -> Icons.Outlined.Memory
                            RemoteFileType.Symlink -> Icons.Outlined.Link
                            RemoteFileType.File -> Icons.AutoMirrored.Outlined.Article
                        },
                        contentDescription = null
                    )
                    Text(item.name)
                }
            }
        }
    }

    actionItem?.let { item ->
        AlertDialog(
            onDismissRequest = { actionItem = null },
            title = { Text(item.name) },
            text = { Text("选择操作") },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget = item
                    actionItem = null
                }) {
                    Text("重命名")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.type != RemoteFileType.Directory) {
                        TextButton(onClick = {
                            editTarget = item
                            actionItem = null
                        }) {
                            Text("编辑")
                        }
                    }
                    TextButton(onClick = {
                        chmodTarget = item
                        actionItem = null
                    }) {
                        Text("权限设置")
                    }
                }
            }
        )
    }

    renameTarget?.let { target ->
        var newName by remember(target.name) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("新名称") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!newName.isBlank()) {
                        onRename(target, newName)
                    }
                    renameTarget = null
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    chmodTarget?.let { target ->
        var mode by remember(target.name) { mutableStateOf("755") }
        AlertDialog(
            onDismissRequest = { chmodTarget = null },
            title = { Text("权限设置") },
            text = {
                OutlinedTextField(
                    value = mode,
                    onValueChange = { mode = it.filter { ch -> ch.isDigit() }.take(4) },
                    label = { Text("权限 (如 755)") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (mode.isNotBlank()) {
                        onChmod(target, mode)
                    }
                    chmodTarget = null
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { chmodTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    editTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("编辑文件") },
            text = { Text("打开并编辑 ${target.name} ?") },
            confirmButton = {
                TextButton(onClick = {
                    onEditFile(target)
                    editTarget = null
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

}
