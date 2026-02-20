package com.kgapp.frpshellpro.ui

enum class RemoteFileType {
    Directory,
    Executable,
    Symlink,
    File
}

data class RemoteFileItem(
    val name: String,
    val type: RemoteFileType
)

object LsFParser {
    private val ansiRegex = Regex("""\u001B\[[0-9;]*[A-Za-z]""")

    fun parse(rawOutput: String): List<RemoteFileItem> {
        return rawOutput
            .lineSequence()
            .map { stripAnsi(it).trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull(::toFileItem)
            .sortedWith(compareBy<RemoteFileItem> { it.type != RemoteFileType.Directory }.thenBy { it.name.lowercase() })
            .toList()
    }

    private fun stripAnsi(input: String): String = input.replace(ansiRegex, "")

    private fun toFileItem(line: String): RemoteFileItem? {
        if (line == "." || line == "..") return null

        return when {
            line.endsWith("/") -> RemoteFileItem(line.dropLast(1), RemoteFileType.Directory)
            line.endsWith("*") -> RemoteFileItem(line.dropLast(1), RemoteFileType.Executable)
            line.endsWith("@") -> RemoteFileItem(line.dropLast(1), RemoteFileType.Symlink)
            else -> RemoteFileItem(line, RemoteFileType.File)
        }
    }
}
