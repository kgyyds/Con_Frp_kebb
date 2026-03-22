package com.kgapp.frpshellpro.data.repository

import com.kgapp.frpshellpro.core.NetCommand
import com.kgapp.frpshellpro.core.NetCommandPriority
import com.kgapp.frpshellpro.core.NetworkThread
import com.kgapp.frpshellpro.server.ClientSession
import kotlinx.coroutines.CompletableDeferred
import java.io.File

class DeviceCommandRepositoryImpl(
    private val networkThread: NetworkThread,
    private val currentSession: (String) -> ClientSession?
) : DeviceCommandRepository {
    override suspend fun runManagedCommand(
        clientId: String,
        command: String,
        timeoutMs: Long,
        priority: NetCommandPriority
    ): String? {
        val deferred = CompletableDeferred<String?>()
        networkThread.post(NetCommand.RunManaged(clientId, command, timeoutMs, deferred, priority))
        return deferred.await()
    }

    override suspend fun listFiles(clientId: String, path: String): ClientSession.ListFilesResult {
        val deferred = CompletableDeferred<ClientSession.ListFilesResult>()
        networkThread.post(NetCommand.ListFiles(clientId, path, deferred))
        return deferred.await()
    }

    override suspend fun uploadFile(clientId: String, remotePath: String, localFile: File, onProgress: ((Long, Long) -> Unit)?): Boolean {
        if (currentSession(clientId) == null) return false
        val deferred = CompletableDeferred<Boolean>()
        networkThread.post(
            NetCommand.UploadFile(
                clientId = clientId,
                remotePath = remotePath,
                localFile = localFile,
                progress = onProgress,
                result = deferred,
                priority = NetCommandPriority.INTERACTIVE
            )
        )
        return deferred.await()
    }

    override suspend fun downloadFile(clientId: String, remotePath: String, targetFile: File, onProgress: ((Long, Long) -> Unit)?): ClientSession.DownloadResult {
        if (currentSession(clientId) == null) return ClientSession.DownloadResult.Failed
        val deferred = CompletableDeferred<ClientSession.DownloadResult>()
        networkThread.post(
            NetCommand.DownloadFile(
                clientId = clientId,
                remotePath = remotePath,
                targetFile = targetFile,
                progress = onProgress,
                result = deferred,
                priority = NetCommandPriority.INTERACTIVE
            )
        )
        return deferred.await()
    }
}
