package com.kgapp.frpshellpro

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kgapp.frpshellpro.frp.FrpLogBus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("FrpShellCompose", "MainActivity setContent 开始")
        FrpLogBus.append("[UI] MainActivity setContent 开始")
        setContent {
            App()
        }
    }
}
