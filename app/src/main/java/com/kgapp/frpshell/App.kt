package com.kgapp.frpshellpro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kgapp.frpshellpro.frp.FrpLogBus
import com.kgapp.frpshellpro.ui.MainScaffold

@Composable
fun App() {
    var firstRendered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        FrpLogBus.append("[UI] App 组合开始")
    }
    SideEffect {
        if (!firstRendered) {
            firstRendered = true
            FrpLogBus.append("[UI] App 首次渲染完成")
        }
    }

    MainScaffold()
}
