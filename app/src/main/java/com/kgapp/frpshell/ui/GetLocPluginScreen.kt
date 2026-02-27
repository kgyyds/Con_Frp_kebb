package com.kgapp.frpshellpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GetLocPluginScreen(
    contentPadding: PaddingValues,
    loading: Boolean,
    errorMessage: String?,
    locationInfo: LocationInfo?,
    addressLoading: Boolean,
    address: String?,
    addressErrorMessage: String?,
    onFetchLocation: () -> Unit,
    onResolveAddress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("GetLoc 插件", style = MaterialTheme.typography.titleLarge)

        Button(onClick = onFetchLocation, modifier = Modifier.fillMaxWidth()) {
            Text("获取位置")
        }

        if (loading) {
            CircularProgressIndicator()
        }

        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        locationInfo?.let { location ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("纬度: ${location.latitude}")
                    Text("经度: ${location.longitude}")
                    Text("时间: ${location.time}")

                    Button(onClick = onResolveAddress, modifier = Modifier.fillMaxWidth()) {
                        Text("查询位置")
                    }

                    if (addressLoading) {
                        CircularProgressIndicator()
                    }

                    address?.let { Text("地址: $it") }
                    addressErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
