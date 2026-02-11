package com.kgapp.frpshell.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.kgapp.frpshell.frp.FrpManager
import com.kgapp.frpshell.model.ShellTarget
import com.kgapp.frpshell.server.TcpServer

@Composable
fun MainScaffold() {

    var current by remember { mutableStateOf<ShellTarget>(ShellTarget.FrpLog) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(current) { current = it }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("FRP Shell") },
                    actions = {
                        IconButton(onClick = { /* settings */ }) {
                            Icon(Icons.Default.Settings, null)
                        }
                    }
                )
            }
        ) {
            ShellScreen(current)
        }
    }
}