package com.kgapp.frpshellpro.domain.usecase

import com.kgapp.frpshellpro.server.ClientSession
import java.io.File

class CaptureUseCase(
    private val shellUseCase: ShellUseCase,
    private val fileManagerUseCase: FileManagerUseCase
) {
    suspend fun captureScreen(clientId: String, remotePath: String, localFile: File): ClientSession.DownloadResult {
        shellUseCase.runManagedCommand(clientId, "screencap -p $remotePath")
        return fileManagerUseCase.downloadFile(clientId, remotePath, localFile)
    }

    suspend fun runCommand(clientId: String, command: String, timeoutMs: Long = 10_000L): String? {
        return shellUseCase.runManagedCommand(clientId, command, timeoutMs)
    }

    suspend fun uploadDependency(clientId: String, remotePath: String, localFile: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        return fileManagerUseCase.uploadFile(clientId, remotePath, localFile, onProgress)
    }

    suspend fun downloadCapture(clientId: String, remotePath: String, localFile: File, onProgress: ((Long, Long) -> Unit)? = null): ClientSession.DownloadResult {
        return fileManagerUseCase.downloadFile(clientId, remotePath, localFile, onProgress)
    }
}
