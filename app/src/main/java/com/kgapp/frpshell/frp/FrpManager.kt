package com.kgapp.frpshell.frp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

object FrpManager {

    fun start(context: Context) {
        val bin = File(context.filesDir, "frpc")
        val cfg = File(context.filesDir, "frpc.toml")

        val process = ProcessBuilder(
            bin.absolutePath,
            "-c",
            cfg.absolutePath
        )
            .redirectErrorStream(true)
            .start()

        GlobalScope.launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().forEachLine {
                FrpLogBus.append(it)
            }
        }
    }
}