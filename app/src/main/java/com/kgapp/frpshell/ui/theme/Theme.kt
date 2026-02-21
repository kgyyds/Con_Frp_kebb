package com.kgapp.frpshellpro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// 黑客风格主题颜色 - 优化版
private val HackerColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0x7fff00), // 荧光绿色，更醒目
    primaryContainer = androidx.compose.ui.graphics.Color(0x003300),
    onPrimary = androidx.compose.ui.graphics.Color(0x000000),
    secondary = androidx.compose.ui.graphics.Color(0x00ff41), // 亮绿色
    onSecondary = androidx.compose.ui.graphics.Color(0x000000),
    tertiary = androidx.compose.ui.graphics.Color(0xff00ff), // 品红色作为点缀
    onTertiary = androidx.compose.ui.graphics.Color(0x000000),
    background = androidx.compose.ui.graphics.Color(0x0a0a0a), // 深黑色，减少刺眼感
    onBackground = androidx.compose.ui.graphics.Color(0x7fff00), // 荧光绿色文本
    surface = androidx.compose.ui.graphics.Color(0x111111), // 深灰色表面
    onSurface = androidx.compose.ui.graphics.Color(0x7fff00), // 荧光绿色文本
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0x00cc00), // 淡绿色文本
    error = androidx.compose.ui.graphics.Color(0xff4444), // 暗红色错误
    onError = androidx.compose.ui.graphics.Color(0xffffff),
    outline = androidx.compose.ui.graphics.Color(0x00ff00), // 亮绿色边框
    outlineVariant = androidx.compose.ui.graphics.Color(0x004400) // 深绿色边框
)

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun FrpShellTheme(
    content: @Composable () -> Unit
) {
    // 强制使用黑客风格主题
    MaterialTheme(
        colorScheme = HackerColors,
        typography = MaterialTheme.typography.copy(
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(color = HackerColors.onBackground),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(color = HackerColors.onBackground),
            bodySmall = MaterialTheme.typography.bodySmall.copy(color = HackerColors.onBackground),
            titleLarge = MaterialTheme.typography.titleLarge.copy(color = HackerColors.onBackground),
            titleMedium = MaterialTheme.typography.titleMedium.copy(color = HackerColors.onBackground),
            titleSmall = MaterialTheme.typography.titleSmall.copy(color = HackerColors.onBackground),
            labelLarge = MaterialTheme.typography.labelLarge.copy(color = HackerColors.onBackground),
            labelMedium = MaterialTheme.typography.labelMedium.copy(color = HackerColors.onBackground),
            labelSmall = MaterialTheme.typography.labelSmall.copy(color = HackerColors.onBackground)
        ),
        content = content
    )
}
