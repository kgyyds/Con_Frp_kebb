package com.kgapp.frpshellpro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DeviceInfoScreen(
    contentPadding: PaddingValues,
    clientId: String?,
    loading: Boolean,
    errorMessage: String?,
    cards: List<DeviceInfoCard>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 12.dp)
    ) {
        Text(
            text = "设备：${clientId ?: "未知"}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp)
        )

        if (loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(cards, key = { it.title }) { card ->
                DeviceInfoCardItem(card = card)
            }
        }
    }
}

@Composable
private fun DeviceInfoCardItem(card: DeviceInfoCard) {
    val tint = when (card.accentType) {
        DeviceInfoAccentType.Primary -> MaterialTheme.colorScheme.primary
        DeviceInfoAccentType.Secondary -> MaterialTheme.colorScheme.secondary
        DeviceInfoAccentType.Tertiary -> MaterialTheme.colorScheme.tertiary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Icon(resolveIcon(card.iconName), contentDescription = null, tint = tint)
                Text(card.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }

            card.metrics.forEach { metric ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = tint.copy(alpha = 0.85f),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(metric.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(metric.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }

                metric.progress?.let {
                    SimpleBarChart(progress = it, color = tint)
                }
            }
        }
    }
}

@Composable
private fun SimpleBarChart(progress: Float, color: Color) {
    Box(modifier = Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.CenterStart) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = size.height
            drawLine(
                color = color.copy(alpha = 0.2f),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width * progress.coerceIn(0f, 1f), size.height / 2f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun resolveIcon(name: String): ImageVector = when (name) {
    "DeviceHub" -> Icons.Default.DeviceHub
    "Memory" -> Icons.Default.Memory
    "Storage" -> Icons.Default.Storage
    else -> Icons.Default.Info
}
