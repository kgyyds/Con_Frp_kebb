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
import androidx.compose.foundation.shape.RoundedCornerShape

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
            containerColor = MaterialTheme.colorScheme.primary, // 使用主题的主色
            contentColor = MaterialTheme.colorScheme.onPrimary, // 使用主题的onPrimary
            disabledContainerColor = Color(0x004400),
            disabledContentColor = Color(0x006600)
        ),
        shape = RoundedCornerShape(8.dp) // 8dp圆角，保持方形但有美感
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
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
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline), // 使用主题的outline色
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface, // 使用主题的onSurface
            disabledContentColor = Color(0x004400)
        ),
        shape = RoundedCornerShape(8.dp) // 8dp圆角，保持方形但有美感
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
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
            containerColor = MaterialTheme.colorScheme.primary, // 使用主题的主色
            contentColor = MaterialTheme.colorScheme.onPrimary, // 使用主题的onPrimary
            disabledContainerColor = Color(0x004400),
            disabledContentColor = Color(0x006600)
        ),
        shape = RoundedCornerShape(8.dp) // 8dp圆角，保持方形但有美感
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