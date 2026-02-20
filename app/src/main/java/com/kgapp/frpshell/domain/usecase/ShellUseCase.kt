package com.kgapp.frpshellpro.domain.usecase

import com.kgapp.frpshellpro.data.repository.DeviceCommandRepository

class ShellUseCase(
    private val repository: DeviceCommandRepository
) {
    suspend fun runManagedCommand(clientId: String, command: String, timeoutMs: Long = 10_000L): String? {
        return repository.runManagedCommand(clientId, command, timeoutMs)
    }
}
