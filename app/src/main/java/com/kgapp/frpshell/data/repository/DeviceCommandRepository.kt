package com.kgapp.frpshellpro.data.repository

import com.kgapp.frpshellpro.server.ClientSession
import java.io.File

interface DeviceCommandRepository {
    suspend fun runManagedCommand(clientId: String, command: String, timeoutMs: Long = 10_000L): String?
    suspend fun listFiles(clientId: String, path: String): ClientSession.ListFilesResult
    suspend fun uploadFile(clientId: String, remotePath: String, localFile: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean
    suspend fun downloadFile(clientId: String, remotePath: String, targetFile: File, onProgress: ((Long, Long) -> Unit)? = null): ClientSession.DownloadResult
}
