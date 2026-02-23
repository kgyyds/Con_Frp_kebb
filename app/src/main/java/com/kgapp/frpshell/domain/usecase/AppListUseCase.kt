package com.kgapp.frpshell.domain.usecase

import com.kgapp.frpshell.ui.AppInfo
import org.json.JSONObject

class AppListUseCase(
    private val shellUseCase: ShellUseCase,
    private val fileManagerUseCase: FileManagerUseCase,
    private val captureUseCase: CaptureUseCase
) {
    suspend fun getAppList(clientId: String): List<AppInfo> {
        // Check if we can use pm command directly (more efficient)
        val pmResult = shellUseCase.runManagedCommand(
            clientId,
            "pm list packages -f --show-versioncode --uid",
            timeoutMs = 15000
        )

        if (pmResult != null && pmResult.isNotEmpty()) {
            // Parse pm command output
            return parsePmOutput(pmResult)
        }

        // Fallback: Use pm list packages (basic version)
        val basicPmResult = shellUseCase.runManagedCommand(
            clientId,
            "pm list packages",
            timeoutMs = 15000
        )

        if (basicPmResult != null && basicPmResult.isNotEmpty()) {
            return parseBasicPmOutput(basicPmResult)
        }

        throw Exception("无法获取应用列表")
    }

    private fun parsePmOutput(output: String): List<AppInfo> {
        val apps = mutableListOf<AppInfo>()

        // Parse output format: package:/data/app/base.apk=com.example.app
        val packageRegex = Regex("package:([^=]+)=([^\\s]+)")
        val lines = output.split('\n')

        for (line in lines) {
            val match = packageRegex.find(line)
            if (match != null) {
                val apkPath = match.groupValues[1]
                val packageName = match.groupValues[2]

                // Determine if it's a system app (in /system or /vendor)
                val isSystem = apkPath.contains("/system/") ||
                              apkPath.contains("/vendor/") ||
                              apkPath.contains("/product/")

                // Get app name using pm command (this would be done in the actual implementation)
                val appName = getAppName(packageName, isSystem)

                apps.add(AppInfo(
                    package_name = packageName,
                    name = appName,
                    system = isSystem
                ))
            }
        }

        return apps.sortedBy { it.name.lowercase() }
    }

    private fun parseBasicPmOutput(output: String): List<AppInfo> {
        val apps = mutableListOf<AppInfo>()

        // Parse output format: package:com.example.app
        val packageRegex = Regex("package:([^\\s]+)")
        val lines = output.split('\n')

        for (line in lines) {
            val match = packageRegex.find(line)
            if (match != null) {
                val packageName = match.groupValues[1]

                // Default name to package name if we can't get display name
                val appName = packageName

                // Android apps are generally not system apps if installed via pm list packages
                val isSystem = false

                apps.add(AppInfo(
                    package_name = packageName,
                    name = appName,
                    system = isSystem
                ))
            }
        }

        return apps.sortedBy { it.name.lowercase() }
    }

    private fun getAppName(packageName: String, isSystem: Boolean): String {
        // For now, return package name. In a real implementation, we could:
        // 1. Use pm list packages -f to extract name from APK
        // 2. Use dumpsys package to get display name
        // 3. Use a pre-built mapping of common package names

        val commonNames = mapOf(
            "android" to "Android System",
            "com.android.systemui" to "System UI",
            "com.android.settings" to "Settings",
            "com.android.chrome" to "Chrome",
            "com.google.android.gms" to "Google Play Services",
            "com.google.android.youtube" to "YouTube",
            "com.instagram.android" to "Instagram",
            "com.facebook.katana" to "Facebook",
            "com.twitter.android" to "Twitter",
            "com.whatsapp" to "WhatsApp"
        )

        return commonNames[packageName] ?: packageName
    }
}