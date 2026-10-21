package com.github.tvbox.osc.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.github.tvbox.osc.net.OkGoHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 在线更新引擎：
 * 1. check()    拉取远端 version.json，与本地 versionCode 比较
 * 2. download() 下载新版本 APK 到应用缓存目录（进度回调 + MD5 校验）
 * 3. install()  通过 FileProvider 调起系统安装器
 */
object AppUpdater {

    /** 远端版本信息接口 */
    private const val VERSION_URL = "https://www.cttv.vip/ys/tvbox/version.json"

    private const val APK_DIR_NAME = "update_apk"
    private const val BUFFER_SIZE = 64 * 1024

    /** 从 version.json 解析出的更新信息 */
    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val notes: String,
        val fileName: String,
        val size: Long,
        val md5: String,
    )

    sealed class CheckResult {
        data class UpdateAvailable(val info: UpdateInfo) : CheckResult()
        data object UpToDate : CheckResult()
    }

    /** 下载完成的 APK 与 version.json 中的 md5 不一致 */
    class MD5MismatchException : Exception("APK md5 mismatch")

    /**
     * 检查更新：请求 VERSION_URL，比较远端与本地 versionCode。
     * 网络异常或数据异常时抛出异常，由调用方提示。
     */
    suspend fun check(currentVersionCode: Int): CheckResult = withContext(Dispatchers.IO) {
        val client = OkGoHelper.getDefaultClient()
            ?: throw IllegalStateException("OkHttpClient not initialized")
        val body = client.newCall(Request.Builder().url(VERSION_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            resp.body?.string() ?: throw RuntimeException("empty response")
        }
        val json = JSONObject(body)
        val remoteCode = json.optInt("versionCode", 0)
        if (remoteCode <= 0) throw RuntimeException("invalid versionCode")
        if (remoteCode <= currentVersionCode) return@withContext CheckResult.UpToDate
        val url = json.optString("url")
        if (url.isEmpty()) throw RuntimeException("empty apk url")
        CheckResult.UpdateAvailable(
            UpdateInfo(
                versionCode = remoteCode,
                versionName = json.optString("versionName").ifEmpty { remoteCode.toString() },
                url = url,
                notes = json.optString("notes").ifEmpty { json.optString("changelog") },
                fileName = json.optString("fileName").ifEmpty { url.substringAfterLast('/') },
                size = json.optLong("size", 0L),
                md5 = json.optString("md5"),
            ),
        )
    }

    /**
     * 下载 APK 到应用缓存目录 update_apk/，onProgress 回调 0-100 的百分比。
     * 下载完成后若 version.json 提供了 md5 则进行校验，不一致时删除文件并抛出
     * [MD5MismatchException]。
     */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val client = OkGoHelper.getDefaultClient()
                ?: throw IllegalStateException("OkHttpClient not initialized")
            val dir = File(context.cacheDir, APK_DIR_NAME).apply { mkdirs() }
            // 清理旧安装包，避免缓存持续膨胀
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, info.fileName.ifEmpty { "update.apk" })

            val request = Request.Builder().url(info.url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                val body = resp.body ?: throw RuntimeException("empty response")
                val total = body.contentLength().let { if (it > 0) it else info.size }
                var downloaded = 0L
                var lastProgress = -1
                body.byteStream().use { input ->
                    apk.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val len = input.read(buffer)
                            if (len == -1) break
                            output.write(buffer, 0, len)
                            downloaded += len
                            if (total > 0) {
                                val progress = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (info.md5.isNotEmpty()) {
                val actual = MD5.getFileMd5(apk)
                if (actual.isEmpty() || !actual.equals(info.md5, ignoreCase = true)) {
                    apk.delete()
                    throw MD5MismatchException()
                }
            }
            apk
        }

    /** 当前应用是否已获得「安装未知应用」授权（Android 8.0+ 需要用户手动开启） */
    fun canInstall(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    /** 跳转系统设置，引导用户开启「允许安装未知应用」 */
    fun requestInstallPermission(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                LOG.e("AppUpdater", e)
            }
        }
    }

    /** 调起系统安装器安装 APK */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
    }
}
