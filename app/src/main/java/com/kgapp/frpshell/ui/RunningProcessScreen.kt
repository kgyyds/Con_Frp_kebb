package com.kgapp.frpshell.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun RunningProcessScreen(
    contentPadding: PaddingValues,
    processItems: List<ClientProcessInfo>,
    loading: Boolean,
    errorMessage: String?,
    sortField: ProcessSortField,
    sortAscending: Boolean,
    onSortByPid: () -> Unit,
    onSortByRss: () -> Unit,
    onClickItem: (ClientProcessInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = onSortByPid,
                label = { Text("PID ${if (sortField == ProcessSortField.PID) if (sortAscending) "↑" else "↓" else ""}") }
            )
            AssistChip(
                onClick = onSortByRss,
                label = { Text("RSS ${if (sortField == ProcessSortField.RSS) if (sortAscending) "↑" else "↓" else ""}") }
            )
        }

        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
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
            modifier = Modifier.fillMaxSize().padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items = processItems, key = { it.pid }) { item ->
                Card(modifier = Modifier.fillMaxWidth().clickable { onClickItem(item) }) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("PID: ${item.pid}", style = MaterialTheme.typography.labelLarge)
                        Text("RSS: ${item.rss}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "CMD: ${item.cmd}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
