package com.kgapp.frpshellpro.ui

object ProcessLogParser {
    fun parse(content: String): List<ClientProcessInfo> {
        return content
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .dropWhile { it.startsWith("PID", ignoreCase = true) }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
                val rss = parts[1].toLongOrNull() ?: return@mapNotNull null
                val cmd = parts[2].trim()
                if (cmd.isBlank()) return@mapNotNull null
                ClientProcessInfo(pid = pid, rss = rss, cmd = cmd)
            }
            .toList()
    }
}
