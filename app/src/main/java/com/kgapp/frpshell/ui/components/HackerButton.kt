package com.kgapp.frpshellpro.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 黑客风格按钮组件
 * 方形设计，带微圆角
 */
@Composable
fun HackerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .fillMaxWidth(),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0x00ff41), // 亮绿色背景
            contentColor = Color(0x000000), // 黑色文字
            disabledContainerColor = Color(0x004400),
            disabledContentColor = Color(0x006600)
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp) // 4dp圆角，接近方形
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * 黑客风格边框按钮组件
 */
@Composable
fun HackerOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .fillMaxWidth(),
        enabled = enabled,
        border = BorderStroke(2.dp, Color(0x00ff41)), // 亮绿色边框
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Color(0x00ff41), // 亮绿色文字
            disabledContentColor = Color(0x004400)
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp) // 4dp圆角，接近方形
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * 黑客风格图标按钮组件
 */
@Composable
fun HackerIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .size(48.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0x00ff41), // 亮绿色背景
            contentColor = Color(0x000000), // 黑色文字
            disabledContainerColor = Color(0x004400),
            disabledContentColor = Color(0x006600)
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp) // 4dp圆角，接近方形
    ) {
        content()
    }
}

/**
 * 黑客风格按钮行，用于并排显示按钮
 */
@Composable
fun HackerButtonRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement
    ) {
        content()
    }
}