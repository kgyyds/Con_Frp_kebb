package com.kgapp.frpshell.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FileEditorScreen(
    remotePath: String,
    content: String,
    fontSizeSp: Float,
    contentPadding: PaddingValues,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    
    // 计算行号
    val lineCount = remember(content) { 
        if (content.isEmpty()) 1 else content.count { it == '\n' } + 1 
    }
    // 生成行号文本 (优化：如果文件极大，joinToString 可能耗时，但在主线程做 UI 渲染前可能还好，或者用 StringBuilder)
    val lineNumbers = remember(lineCount) { 
        (1..lineCount).joinToString("\n") 
    }

    // 统一样式，确保对齐
    val lineHeight = (fontSizeSp * 1.3f).sp
    val monoStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSizeSp.sp,
        lineHeight = lineHeight
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding())
            .imePadding() // 键盘避让
    ) {
        // 顶部信息栏
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = remotePath,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 编辑区域
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            // 左侧行号
            Text(
                text = lineNumbers,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .padding(vertical = 4.dp, horizontal = 8.dp)
                    .width(IntrinsicSize.Min), // 根据宽度自适应，或者固定宽度
                textAlign = TextAlign.End,
                style = monoStyle
            )

            // 分割线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight() // 在 verticalScroll 的 Row 中可能不起作用，需要高度确定
                    // 实际上行号和文本高度一致，不需要显式高度
                    .padding(vertical = 4.dp)
            ) {
                 VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            // 右侧文本编辑器
            BasicTextField(
                value = content,
                onValueChange = onContentChange,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                textStyle = monoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
