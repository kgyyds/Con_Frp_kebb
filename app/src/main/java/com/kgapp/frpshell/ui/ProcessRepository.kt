package com.kgapp.frpshellpro.ui

import android.util.Log
import com.kgapp.frpshellpro.server.ClientSession
import java.io.File

class ProcessRepository(
    private val getSession: (String) -> ClientSession?
) {
    suspend fun fetchProcessList(clientId: String, cacheDir: File): List<ClientProcessInfo>? {
        val session = getSession(clientId) ?: return null
        val remotePath = "/data/local/tmp/ps.log"
        val localFile = File(cacheDir, "ps_${clientId}.log")

        val executeResult = session.runManagedCommand("ps -A -w -o PID,RSS,CMD > $remotePath")
        if (executeResult == null) {
            Log.w("ProcessRepository", "执行进程采集命令失败")
            return null
        }

        val downloadResult = session.downloadFile(remotePath, localFile)
        if (downloadResult != ClientSession.DownloadResult.Success) {
            Log.w("ProcessRepository", "下载 ps.log 失败，结果=$downloadResult")
            return null
        }

        return runCatching { ProcessLogParser.parse(localFile.readText()) }
            .onFailure { Log.e("ProcessRepository", "解析 ps.log 失败", it) }
            .getOrNull()
    }

    suspend fun killProcess(clientId: String, pid: Int): Boolean {
        val session = getSession(clientId) ?: return false
        return session.runManagedCommand("kill -9 $pid") != null
    }
}
