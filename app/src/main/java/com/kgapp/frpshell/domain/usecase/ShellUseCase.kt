package com.kgapp.frpshellpro.domain.usecase

import com.kgapp.frpshellpro.data.repository.DeviceCommandRepository

class ShellUseCase(
    private val repository: DeviceCommandRepository
) {
    suspend fun runManagedCommand(clientId: String, command: String, timeoutMs: Long = 10_000L): String? {
        return repository.runManagedCommand(clientId, command, timeoutMs)
    }

    fun validateRecordConfig(host: String, portText: String, startTemplate: String, stopTemplate: String): String? {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) return "录屏推流地址不能为空"

        val port = portText.trim().toIntOrNull()
        if (port == null || port !in 1..65535) return "录屏推流端口必须在 1~65535"

        if (startTemplate.isBlank()) return "录屏启动命令模板不能为空"
        if (!startTemplate.contains("{host}") || !startTemplate.contains("{port}")) {
            return "启动模板需包含 {host} 与 {port} 占位符"
        }

        if (stopTemplate.isBlank()) return "录屏停止命令模板不能为空"
        return null
    }

    fun buildStartRecordCommand(host: String, portText: String, startTemplate: String): String {
        val port = portText.trim().toInt()
        return startTemplate
            .replace("{host}", host.trim())
            .replace("{port}", port.toString())
            .trim()
    }

    fun buildStopRecordCommand(stopTemplate: String): String = stopTemplate.trim()
}
