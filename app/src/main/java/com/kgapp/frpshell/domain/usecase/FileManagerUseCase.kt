package com.kgapp.frpshellpro.domain.usecase

import com.kgapp.frpshellpro.data.repository.DeviceCommandRepository
import com.kgapp.frpshellpro.server.ClientSession
import java.io.File

class FileManagerUseCase(
    private val repository: DeviceCommandRepository
) {
    suspend fun listFiles(clientId: String, path: String): ClientSession.ListFilesResult = repository.listFiles(clientId, path)

    suspend fun uploadFile(clientId: String, remotePath: String, localFile: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        return repository.uploadFile(clientId, remotePath, localFile, onProgress)
    }

    suspend fun downloadFile(clientId: String, remotePath: String, targetFile: File, onProgress: ((Long, Long) -> Unit)? = null): ClientSession.DownloadResult {
        return repository.downloadFile(clientId, remotePath, targetFile, onProgress)
    }

    suspend fun executeInDirectory(clientId: String, directory: String, command: String): String? {
        val fullCommand = "cd ${shellEscape(directory)} && $command"
        return repository.runManagedCommand(clientId, fullCommand)
    }

    private fun shellEscape(value: String): String = "'${value.replace("'", "'\\''")}'"
}
