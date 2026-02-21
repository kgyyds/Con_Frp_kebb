package com.kgapp.frpshellpro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 黑客风格主题颜色 - 优化版
private val HackerColors = darkColorScheme(
    primary = Color(0xFF7FFF00), // 荧光绿色，更醒目
    primaryContainer = Color(0xFF003300),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF00FF41), // 亮绿色
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFFFF00FF), // 品红色作为点缀
    onTertiary = Color(0xFF000000),
    background = Color(0xFF0A0A0A), // 深黑色，减少刺眼感
    onBackground = Color(0xFF7FFF00), // 荧光绿色文本
    surface = Color(0xFF111111), // 深灰色表面
    onSurface = Color(0xFF7FFF00), // 荧光绿色文本
    onSurfaceVariant = Color(0xFF00CC00), // 淡绿色文本
    error = Color(0xFFFF4444), // 暗红色错误
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF00FF00), // 亮绿色边框
    outlineVariant = Color(0xFF004400) // 深绿色边框
)

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
