package com.kgapp.frpshellpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kgapp.frpshellpro.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    configContent: String,
    useSu: Boolean,
    suAvailable: Boolean,
    themeMode: ThemeMode,
    shellFontSizeSp: Float,
    uploadScriptContent: String,
    firstLaunchFlow: Boolean,
    onConfigChanged: (String) -> Unit,
    onUseSuChanged: (Boolean) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onShellFontSizeChanged: (Float) -> Unit,
    onUploadScriptContentChanged: (String) -> Unit,
    onSaveUploadScript: () -> Unit,
    onSave: () -> Unit,
    onSaveAndRestart: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (firstLaunchFlow) "初始化设置" else "设置",
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("使用 su 启动 frpc")
                Text(
                    if (suAvailable) "已检测到 su，可开启" else "未检测到 su，开启后可能启动失败",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = useSu,
                onCheckedChange = onUseSuChanged
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("主题", style = MaterialTheme.typography.titleSmall)
            ThemeMode.entries.forEach { mode ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    RadioButton(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChanged(mode) }
                    )
                    Text(
                        text = when (mode) {
                            ThemeMode.SYSTEM -> "跟随系统"
                            ThemeMode.LIGHT -> "浅色"
                            ThemeMode.DARK -> "深色"
                        },
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("终端字体大小：${shellFontSizeSp.toInt()}sp", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = shellFontSizeSp,
                onValueChange = onShellFontSizeChanged,
                valueRange = 12f..24f
            )
        }

        OutlinedTextField(
            value = configContent,
            onValueChange = onConfigChanged,
            modifier = Modifier.fillMaxWidth(),
            minLines = 14,
            label = { Text("frpc.toml") }
        )

        OutlinedTextField(
            value = uploadScriptContent,
            onValueChange = onUploadScriptContentChanged,
            modifier = Modifier.fillMaxWidth(),
            minLines = 10,
            label = { Text("upload.sh") }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSaveUploadScript) {
                Text("保存 upload.sh")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave) {
                Text("仅保存")
            }
            Button(onClick = onSaveAndRestart) {
                Text(if (firstLaunchFlow) "保存并启动" else "保存并重启")
            }
        }
    }
}
