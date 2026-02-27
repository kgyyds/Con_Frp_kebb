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
fun GetInfoPluginScreen(
    contentPadding: PaddingValues,
    callLogLoading: Boolean,
    callLogErrorMessage: String?,
    callLogCountInput: String,
    callLogItems: List<CallLogItem>,
    onCallLogCountChange: (String) -> Unit,
    onReadCallLog: () -> Unit,
    smsLoading: Boolean,
    smsErrorMessage: String?,
    smsCountInput: String,
    smsItems: List<SmsItem>,
    onSmsCountChange: (String) -> Unit,
    onReadSms: () -> Unit,
    contactLoading: Boolean,
    contactErrorMessage: String?,
    contactCountInput: String,
    contactItems: List<ContactItem>,
    onContactCountChange: (String) -> Unit,
    onReadContact: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("GetInfo 插件", style = MaterialTheme.typography.titleLarge)
        }

        item {
            FeatureHeader("读取通话记录", callLogCountInput, onCallLogCountChange, onReadCallLog)
        }
        item {
            if (callLogLoading) CircularProgressIndicator()
            callLogErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(callLogItems) { call ->
            InfoCard(
                title = "号码: ${call.number.ifBlank { "未知" }}",
                lines = listOf(
                    "时间: ${formatTime(call.date)}",
                    "时长: ${call.duration} 秒",
                    "类型: ${typeLabel(call.type)}"
                )
            )
        }

        item {
            FeatureHeader("读取短信", smsCountInput, onSmsCountChange, onReadSms)
        }
        item {
            if (smsLoading) CircularProgressIndicator()
            smsErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(smsItems) { sms ->
            InfoCard(
                title = "号码: ${sms.address.ifBlank { "未知" }}",
                lines = listOf(
                    "时间: ${formatTime(sms.timestamp)}",
                    "内容: ${sms.body}"
                )
            )
        }

        item {
            FeatureHeader("读取联系人", contactCountInput, onContactCountChange, onReadContact)
        }
        item {
            if (contactLoading) CircularProgressIndicator()
            contactErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(contactItems) { contact ->
            InfoCard(
                title = contact.displayName.ifBlank { "未知联系人" },
                lines = listOf("号码: ${contact.phone.ifBlank { "未知" }}")
            )
        }
    }
}

@Composable
private fun FeatureHeader(
    title: String,
    countInput: String,
    onCountChange: (String) -> Unit,
    onRead: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = countInput,
                onValueChange = onCountChange,
                label = { Text("读取条目数") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRead) { Text("读取") }
        }
    }
}

@Composable
private fun InfoCard(title: String, lines: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            lines.forEach { Text(it) }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return "未知"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }.getOrDefault(timestamp.toString())
}

private fun typeLabel(type: Int): String = when (type) {
    1 -> "呼入"
    2 -> "呼出"
    3 -> "漏接"
    4 -> "已屏蔽"
    else -> "未知($type)"
}
