package com.kgapp.frpshellpro.domain.usecase

import com.kgapp.frpshellpro.ui.AppInfo
import org.json.JSONArray
import org.json.JSONObject

class AppListUseCase(
    private val shellUseCase: ShellUseCase,
    private val fileManagerUseCase: FileManagerUseCase,
    private val captureUseCase: CaptureUseCase
) {
    suspend fun getAppList(clientId: String, localJar: java.io.File): List<AppInfo> {
        ensureScrcpyServerReady(clientId, localJar)

        val command = "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process /data/local/tmp com.genymobile.scrcpy.Server getapp=true"
        val rawOutput = shellUseCase.runManagedCommand(clientId, command, timeoutMs = 30_000)
            ?: throw IllegalStateException("客户端未返回应用列表")

        val appJson = extractAppsJson(rawOutput)
            ?: throw IllegalStateException("未从客户端输出中解析到应用列表 JSON")

        return parseApps(appJson)
    }

    private suspend fun ensureScrcpyServerReady(clientId: String, localJar: java.io.File) {
        if (!localJar.exists() || localJar.length() == 0L) {
            throw IllegalStateException("本地 scrcpy-server.jar 不存在")
        }

        val checkCmd = "if [ -f /data/local/tmp/scrcpy-server.jar ]; then echo exists; else echo missing; fi"
        val checkOutput = shellUseCase.runManagedCommand(clientId, checkCmd, timeoutMs = 8_000).orEmpty()
        val exists = checkOutput.contains("exists")

        if (!exists) {
            val uploaded = captureUseCase.uploadDependency(
                clientId = clientId,
                remotePath = "/data/local/tmp/scrcpy-server.jar",
                localFile = localJar
            )
            if (!uploaded) {
                throw IllegalStateException("上传 scrcpy-server.jar 失败")
            }
            shellUseCase.runManagedCommand(clientId, "chmod 777 /data/local/tmp/scrcpy-server.jar", timeoutMs = 5_000)
        }

        val verify = shellUseCase.runManagedCommand(clientId, "ls -l /data/local/tmp/scrcpy-server.jar", timeoutMs = 8_000).orEmpty()
        if (!verify.contains("scrcpy-server.jar")) {
            throw IllegalStateException("远端 scrcpy-server.jar 校验失败")
        }
    }

    private fun extractAppsJson(output: String): JSONObject? {
        val startIndex = output.indexOf("{\"apps\"")
            .takeIf { it >= 0 }
            ?: output.indexOf('{').takeIf { it >= 0 }
            ?: return null

        val jsonPart = output.substring(startIndex)
        val endIndex = findJsonEndIndex(jsonPart)
        if (endIndex < 0) return null

        val jsonText = jsonPart.substring(0, endIndex + 1)
        return runCatching { JSONObject(jsonText) }.getOrNull()
    }

    private fun findJsonEndIndex(text: String): Int {
        var depth = 0
        var inString = false
        var escaping = false

        text.forEachIndexed { index, ch ->
            if (inString) {
                when {
                    escaping -> escaping = false
                    ch == '\\' -> escaping = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
        }

        return -1
    }

    private fun parseApps(json: JSONObject): List<AppInfo> {
        val appsArray = json.optJSONArray("apps") ?: JSONArray()
        return buildList {
            for (i in 0 until appsArray.length()) {
                val item = appsArray.optJSONObject(i) ?: continue
                val packageName = item.optString("package_name")
                if (packageName.isBlank()) continue
                add(
                    AppInfo(
                        packageName = packageName,
                        name = item.optString("name").ifBlank { packageName },
                        system = item.optBoolean("system", false)
                    )
                )
            }
        }.sortedBy { it.name.lowercase() }
    }
}
