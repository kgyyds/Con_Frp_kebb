package com.kgapp.frpshellpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CallLogScreen(
    contentPadding: PaddingValues,
    loading: Boolean,
    countInput: String,
    items: List<CallLogItem>,
    errorMessage: String?,
    onCountChange: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = countInput,
                onValueChange = onCountChange,
                label = { Text("读取条目数") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRefresh) {
                Text("读取")
            }
        }

        if (loading) {
            CircularProgressIndicator()
        }

        errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items) { call ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("号码: ${call.number.ifBlank { "未知" }}", style = MaterialTheme.typography.titleMedium)
                        Text("时间: ${formatTime(call.date)}")
                        Text("时长: ${call.duration} 秒")
                        Text("类型: ${typeLabel(call.type)}")
                    }
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return "未知"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }.getOrDefault(timestamp.toString())
}

private fun typeLabel(type: Int): String {
    return when (type) {
        1 -> "呼入"
        2 -> "呼出"
        3 -> "漏接"
        4 -> "已屏蔽"
        else -> "未知($type)"
    }
}
