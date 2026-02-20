package com.kgapp.frpshellpro.data.repository

import com.kgapp.frpshellpro.core.NetCommand
import com.kgapp.frpshellpro.core.NetworkThread
import com.kgapp.frpshellpro.server.ClientSession
import kotlinx.coroutines.CompletableDeferred
import java.io.File

class DeviceCommandRepositoryImpl(
    private val networkThread: NetworkThread,
    private val currentSession: (String) -> ClientSession?
) : DeviceCommandRepository {
    override suspend fun runManagedCommand(clientId: String, command: String, timeoutMs: Long): String? {
        val deferred = CompletableDeferred<String?>()
        networkThread.post(NetCommand.RunManaged(clientId, command, timeoutMs, deferred))
        return deferred.await()
    }

    override suspend fun listFiles(clientId: String, path: String): ClientSession.ListFilesResult {
        val deferred = CompletableDeferred<ClientSession.ListFilesResult>()
        networkThread.post(NetCommand.ListFiles(clientId, path, deferred))
        return deferred.await()
    }

    override suspend fun uploadFile(clientId: String, remotePath: String, localFile: File, onProgress: ((Long, Long) -> Unit)?): Boolean {
        val session = currentSession(clientId) ?: return false
        return session.uploadFile(remotePath, localFile, onProgress)
    }

    override suspend fun downloadFile(clientId: String, remotePath: String, targetFile: File, onProgress: ((Long, Long) -> Unit)?): ClientSession.DownloadResult {
        val session = currentSession(clientId) ?: return ClientSession.DownloadResult.Failed
        return session.downloadFile(remotePath, targetFile, onProgress)
    }
}
