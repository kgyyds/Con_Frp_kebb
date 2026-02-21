package com.kgapp.frpshellpro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// 黑客风格主题颜色
private val HackerColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0x00ff41), // 亮绿色
    primaryContainer = androidx.compose.ui.graphics.Color(0x003300),
    onPrimary = androidx.compose.ui.graphics.Color(0x000000),
    secondary = androidx.compose.ui.graphics.Color(0x00ff41), // 亮绿色
    onSecondary = androidx.compose.ui.graphics.Color(0x000000),
    tertiary = androidx.compose.ui.graphics.Color(0xff00ff),
    onTertiary = androidx.compose.ui.graphics.Color(0x000000),
    background = androidx.compose.ui.graphics.Color(0x000000), // 黑色背景
    onBackground = androidx.compose.ui.graphics.Color(0x00ff41), // 亮绿色文本
    surface = androidx.compose.ui.graphics.Color(0x001122), // 深灰表面
    onSurface = androidx.compose.ui.graphics.Color(0x00ff41), // 亮绿色文本
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0x00aa00), // 淡绿色文本
    error = androidx.compose.ui.graphics.Color(0xff0000), // 红色错误
    onError = androidx.compose.ui.graphics.Color(0xffffff),
    outline = androidx.compose.ui.graphics.Color(0x00ff41), // 绿色边框
    outlineVariant = androidx.compose.ui.graphics.Color(0x004400) // 深绿色边框
)

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun FrpShellTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    // 强制使用黑客风格主题
    MaterialTheme(
        colorScheme = HackerColors,
        content = content
    )
}
