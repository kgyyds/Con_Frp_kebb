package com.kgapp.frpshellpro.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ConfigEditorDialog(
    config: String,
    firstLaunch: Boolean,
    onChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onSaveAndRestart: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!firstLaunch) onDismiss()
        },
        title = {
            Text(if (firstLaunch) "初始化 frpc.toml" else "编辑 frpc.toml")
        },
        text = {
            OutlinedTextField(
                value = config,
                onValueChange = onChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp),
                minLines = 10
            )
        },
        confirmButton = {
            TextButton(onClick = onSaveAndRestart) {
                Text(if (firstLaunch) "保存并启动" else "保存并重启")
            }
        },
        dismissButton = {
            TextButton(onClick = onSave) {
                Text("仅保存")
            }
        }
    )
}
