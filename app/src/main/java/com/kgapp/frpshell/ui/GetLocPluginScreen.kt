package com.kgapp.frpshellpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
    statusMessage: String?,
    locationInfo: LocationInfo?,
    intlAddressLoading: Boolean,
    intlAddress: String?,
    intlAddressErrorMessage: String?,
    cnAddressLoading: Boolean,
    cnAddress: String?,
    cnAddressErrorMessage: String?,
    onInstallPlugin: () -> Unit,
    onUninstallPlugin: () -> Unit,
    onGrantPermission: () -> Unit,
    onFetchLocation: () -> Unit,
    onResolveAddressIntl: () -> Unit,
    onResolveAddressCn: () -> Unit,
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

        Button(onClick = onInstallPlugin, modifier = Modifier.fillMaxWidth()) {
            Text("安装GetLoc")
        }

        Button(onClick = onUninstallPlugin, modifier = Modifier.fillMaxWidth()) {
            Text("卸载GetLoc")
        }

        Button(onClick = onGrantPermission, modifier = Modifier.fillMaxWidth()) {
            Text("权限设置")
        }

        Button(onClick = onFetchLocation, modifier = Modifier.fillMaxWidth()) {
            Text("获取位置")
        }

        if (loading) {
            CircularProgressIndicator()
        }

        statusMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.primary)
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

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onResolveAddressIntl, modifier = Modifier.weight(1f)) {
                            Text("国际版查询")
                        }
                        Button(onClick = onResolveAddressCn, modifier = Modifier.weight(1f)) {
                            Text("中国版查询")
                        }
                    }

                    if (intlAddressLoading) CircularProgressIndicator()
                    intlAddress?.let { Text("国际地址: $it") }
                    intlAddressErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    if (cnAddressLoading) CircularProgressIndicator()
                    cnAddress?.let { Text("中国地址: $it") }
                    cnAddressErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
