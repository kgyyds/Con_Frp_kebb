package com.kgapp.frpshellpro.ui

/**
 * Shell 终端展示模型：每条命令与其输出形成一个独立命令组。
 */
data class ShellCommandItem(
    val commandText: String,
    val outputText: String,
    val timestamp: Long,
    val status: ShellCommandStatus
)

enum class ShellCommandStatus {
    RUNNING,
    DONE
}
