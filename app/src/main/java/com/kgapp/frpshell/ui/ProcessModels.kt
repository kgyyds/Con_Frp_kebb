package com.kgapp.frpshellpro.ui

data class ClientProcessInfo(
    val pid: Int,
    val rss: Long,
    val cmd: String
)

enum class ProcessSortField {
    PID,
    RSS
}
