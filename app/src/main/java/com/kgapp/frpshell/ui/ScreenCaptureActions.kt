package com.kgapp.frpshellpro.ui

import android.app.Application
import android.content.Context
import com.kgapp.frpshellpro.frp.FrpLogBus
import com.kgapp.frpshellpro.server.ClientSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

internal fun launchScreenCaptureJob(
    scope: CoroutineScope,
    uiState: MutableStateFlow<MainUiState>,
    targetId: String,
    getSession: (String) -> ClientSession?,
    cacheDir: File
): Job {
    return scope.launch(Dispatchers.IO) {
        uiState.update { it.copy(screenCaptureLoading = true, screenCaptureCancelable = false, screenCaptureLoadingText = "正在截屏...") }

        val cancelTimer = launch {
            delay(4000)
            uiState.update { it.copy(screenCaptureCancelable = true) }
        }

        try {
            val session = getSession(targetId) ?: return@launch

            FrpLogBus.append("[截图] 开始执行截图")
            session.runManagedCommand("screencap -p /data/local/tmp/tmp.png")

            val remotePath = "/data/local/tmp/tmp.png"
            val cacheFile = File(cacheDir, "screen_${System.currentTimeMillis()}.png")

            val result = session.downloadFile(remotePath, cacheFile)
            if (result == ClientSession.DownloadResult.Success) {
                uiState.update {
                    it.copy(
                        screenViewerVisible = true,
                        screenViewerImagePath = cacheFile.absolutePath,
                        screenViewerTimestamp = System.currentTimeMillis()
                    )
                }
                FrpLogBus.append("[截图] 截图成功")
                session.runManagedCommand("rm /data/local/tmp/tmp.png")
            } else {
                FrpLogBus.append("[截图] 截图失败：下载远程文件失败")
            }
        } finally {
            cancelTimer.cancel()
            uiState.update { it.copy(screenCaptureLoading = false) }
        }
    }
}

internal fun launchCameraPhotoCaptureJob(
    scope: CoroutineScope,
    uiState: MutableStateFlow<MainUiState>,
    targetId: String,
    cameraId: Int,
    getSession: (String) -> ClientSession?,
    appContext: Application,
    copyAssetToFile: (Context, String, File) -> Unit
): Job {
    return scope.launch(Dispatchers.IO) {
        uiState.update { it.copy(screenCaptureLoading = true, screenCaptureCancelable = false, screenCaptureLoadingText = "准备中...", screenCaptureLog = "") }

        val cancelTimer = launch {
            delay(4000)
            uiState.update { it.copy(screenCaptureCancelable = true) }
        }

        fun updateLog(msg: String) {
            uiState.update { it.copy(screenCaptureLog = msg) }
        }

        try {
            val session = getSession(targetId) ?: return@launch
            uiState.update { it.copy(screenCaptureLoadingText = "正在检查远程组件...") }

            val localJar = File(appContext.filesDir, "scrcpy-server.jar")
            if (!localJar.exists() || localJar.length() == 0L) {
                copyAssetToFile(appContext, "scrcpy-server.jar", localJar)
            }

            if (!localJar.exists() || localJar.length() == 0L) {
                uiState.update { it.copy(screenCaptureLoadingText = "本地组件缺失，无法继续") }
                updateLog("错误：本地组件文件丢失")
                return@launch
            }

            val checkCmd = "if [ -f /data/local/tmp/scrcpy-server.jar ]; then echo 'exists'; else echo 'missing'; fi"
            val checkResult = session.runManagedCommand(checkCmd)?.trim()
            val toolExists = checkResult?.contains("exists") == true

            if (!toolExists) {
                uiState.update { it.copy(screenCaptureLoadingText = "远程组件缺失，准备上传...") }
                updateLog("正在上传 scrcpy-server.jar...")

                val ok = session.uploadFile("/data/local/tmp/scrcpy-server.jar", localJar) { done, total ->
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    uiState.update { it.copy(screenCaptureLoadingText = "正在上传组件: $percent%") }
                }

                if (!ok) {
                    updateLog("错误：文件上传失败")
                    uiState.update { it.copy(screenCaptureLoadingText = "组件上传失败") }
                    return@launch
                }

                session.runManagedCommand("chmod 777 /data/local/tmp/scrcpy-server.jar")
            } else {
                updateLog("远程组件检查通过")
                uiState.update { it.copy(screenCaptureLoadingText = "组件检查通过") }
            }

            val verifyCmd = "ls -l /data/local/tmp/scrcpy-server.jar"
            val verifyResult = session.runManagedCommand(verifyCmd)
            if (verifyResult == null || !verifyResult.contains("scrcpy-server.jar")) {
                updateLog("错误：组件校验失败，远程文件不可见")
                uiState.update { it.copy(screenCaptureLoadingText = "组件校验失败") }
                return@launch
            }

            uiState.update { it.copy(screenCaptureLoadingText = "正在执行拍照指令...") }
            updateLog("发送拍照指令 (camera_id=$cameraId)...")

            val cmd = """(sh -c "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process /data/local/tmp com.genymobile.scrcpy.Server video=true audio=false video_source=camera camera_id=$cameraId > /dev/null 2>&1 < /dev/null" &)"""
            session.runManagedCommand(cmd)

            updateLog("等待照片生成 (轮询)...")

            val remotePath = "/data/local/tmp/scrcpy_test.jpg"
            val pollInterval = 1000L
            val timeoutMs = 30_000L
            val startTime = System.currentTimeMillis()
            var photoExists = false

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val checkPhotoCmd = "ls $remotePath 2>/dev/null || true"
                val result = session.runManagedCommand(checkPhotoCmd, timeoutMs = 2000)?.trim()
                if (result != null && result.contains(remotePath)) {
                    photoExists = true
                    break
                }
                delay(pollInterval)
            }

            if (photoExists) {
                uiState.update { it.copy(screenCaptureLoadingText = "拍照成功，准备获取照片...") }
                delay(3000)
                updateLog("开始下载照片...")

                val cacheFile = File(appContext.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                val result = session.downloadFile(remotePath, cacheFile) { done, total ->
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    uiState.update { it.copy(screenCaptureLoadingText = "正在下载照片: $percent%") }
                }

                if (result == ClientSession.DownloadResult.Success) {
                    updateLog("下载完成")
                    uiState.update {
                        it.copy(
                            screenViewerVisible = true,
                            screenViewerImagePath = cacheFile.absolutePath,
                            screenViewerTimestamp = System.currentTimeMillis()
                        )
                    }
                    session.runManagedCommand("rm $remotePath")
                } else {
                    updateLog("错误：照片下载失败")
                    uiState.update { it.copy(screenCaptureLoadingText = "下载失败") }
                }
            } else {
                updateLog("错误：未检测到照片生成 (超时)")
                uiState.update { it.copy(screenCaptureLoadingText = "拍照失败") }
            }
        } catch (e: Exception) {
            updateLog("异常：${e.message}")
        } finally {
            cancelTimer.cancel()
            uiState.update { it.copy(screenCaptureLoading = false) }
        }
    }
}
