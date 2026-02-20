package com.kgapp.frpshellpro.domain.usecase

import android.util.Log
import com.kgapp.frpshellpro.server.ClientSession
import com.kgapp.frpshellpro.ui.ClientProcessInfo
import com.kgapp.frpshellpro.ui.ProcessLogParser
import java.io.File

class ProcessUseCase(
    private val shellUseCase: ShellUseCase,
    private val fileManagerUseCase: FileManagerUseCase
) {
    suspend fun fetchProcessList(clientId: String, cacheDir: File): List<ClientProcessInfo>? {
        val remotePath = "/data/local/tmp/ps.log"
        val localFile = File(cacheDir, "ps_${clientId}.log")

        val executeResult = shellUseCase.runManagedCommand(clientId, "ps -A -w -o PID,RSS,CMD > $remotePath")
        if (executeResult == null) {
            Log.w("ProcessUseCase", "执行进程采集命令失败")
            return null
        }

        val downloadResult = fileManagerUseCase.downloadFile(clientId, remotePath, localFile)
        if (downloadResult != ClientSession.DownloadResult.Success) {
            Log.w("ProcessUseCase", "下载 ps.log 失败，结果=$downloadResult")
            return null
        }

        return runCatching { ProcessLogParser.parse(localFile.readText()) }
            .onFailure { Log.e("ProcessUseCase", "解析 ps.log 失败", it) }
            .getOrNull()
    }

    suspend fun killProcess(clientId: String, pid: Int): Boolean {
        return shellUseCase.runManagedCommand(clientId, "kill -9 $pid") != null
    }
}
