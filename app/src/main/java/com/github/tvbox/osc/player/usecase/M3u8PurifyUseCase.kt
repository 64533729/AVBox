package com.github.tvbox.osc.player.usecase

import android.content.Context
import android.widget.Toast
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.server.RemoteServer
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.M3u8
import com.github.tvbox.osc.util.RegexUtils
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.HttpHeaders
import com.lzy.okgo.model.Response
import java.net.MalformedURLException
import java.net.URL
import java.util.HashMap

class M3u8PurifyUseCase(context: Context, private val callback: Callback) {

    interface Callback {
        fun startPlayUrl(url: String?, headers: HashMap<String, String>?)

        fun onM3u8ProxyUrl(proxyUrl: String?, sourceUrl: String?)
    }

    private fun str(resId: Int, vararg args: Any?): String {
        val app = App.getInstance() ?: return ""
        return LanguageManager.localized(app).getString(resId, *args)
    }

    private val context: Context = context.applicationContext

    fun playM3u8(url: String, headers: HashMap<String, String>?) {
        if (url.contains("url=")) {
            callback.startPlayUrl(url, headers)
            return
        }
        OkGo.getInstance().cancelTag("m3u8-1")
        OkGo.getInstance().cancelTag("m3u8-2")
        val okGoHeaders = HttpHeaders()
        if (headers != null) {
            for ((key, value) in headers) {
                okGoHeaders.put(key, value)
            }
        }
        OkGo.get<String>(url)
            .tag("m3u8-1")
            .headers(okGoHeaders)
            .execute(object : AbsCallback<String>() {
                override fun onSuccess(response: Response<String>) {
                    val content = response.body()
                    if (!content.startsWith("#EXTM3U")) {
                        callback.startPlayUrl(url, headers)
                        return
                    }
                    val forwardUrl = extractForwardUrl(url, content)
                    if (forwardUrl.isEmpty()) {
                        LOG.i("echo-m3u81-to-play")
                        processM3u8Content(url, content, headers)
                    } else {
                        fetchAndProcessForwardUrl(forwardUrl, headers, okGoHeaders, url)
                    }
                }

                override fun convertResponse(response: okhttp3.Response): String = response.body.string()

                override fun onError(response: Response<String>) {
                    super.onError(response)
                    LOG.e("echo-m3u8请求错误1: " + response.exception)
                    callback.startPlayUrl(url, headers)
                }
            })
    }

    private fun extractForwardUrl(baseUrl: String, content: String): String {
        val lines = RegexUtils.getPattern("\\r?\\n").split(content, -1)
        for (i in lines.indices) {
            val line = lines[i].trim { it <= ' ' }
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                for (j in i + 1 until lines.size) {
                    val targetLine = lines[j].trim { it <= ' ' }
                    if (targetLine.isEmpty()) continue
                    if (isValidM3u8Line(targetLine)) {
                        return resolveForwardUrl(baseUrl, targetLine)
                    }
                }
            }
        }
        return ""
    }

    private fun isValidM3u8Line(line: String): Boolean {
        return !line.startsWith("#") && (line.endsWith(".m3u8") || line.contains(".m3u8?"))
    }

    private fun processM3u8Content(url: String, content: String, headers: HashMap<String, String>?) {
        val basePath = getBasePath(url)
        val purified = M3u8.purify(basePath, content)
        if (purified == null || M3u8.currentAdCount == 0) {
            LOG.i("echo-m3u8内容解析：未检测到广告")
            callback.startPlayUrl(url, headers)
        } else {
            val key = RemoteServer.putM3u8Content(purified)
            val proxyUrl = ControlManager.get().getAddress(true) + "proxyM3u8?k=" + key
            callback.onM3u8ProxyUrl(proxyUrl, url)
            callback.startPlayUrl(proxyUrl, headers)
            Toast.makeText(context, str(R.string.toast_ads_removed, M3u8.currentAdCount), Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchAndProcessForwardUrl(
        forwardUrl: String,
        headers: HashMap<String, String>?,
        okGoHeaders: HttpHeaders,
        fallbackUrl: String,
    ) {
        OkGo.get<String>(forwardUrl)
            .tag("m3u8-2")
            .headers(okGoHeaders)
            .execute(object : AbsCallback<String>() {
                override fun onSuccess(response: Response<String>) {
                    val content = response.body()
                    LOG.i("echo-m3u82-to-play")
                    processM3u8Content(forwardUrl, content, headers)
                }

                override fun convertResponse(response: okhttp3.Response): String = response.body.string()

                override fun onError(response: Response<String>) {
                    super.onError(response)
                    LOG.e("echo-重定向 m3u8 请求错误: " + response.exception)
                    callback.startPlayUrl(fallbackUrl, headers)
                }
            })
    }

    private fun getBasePath(url: String): String {
        val parts = RegexUtils.getPattern("/").split(url)
        return parts[0] + "/"
    }

    private fun resolveForwardUrl(baseUrl: String, line: String): String {
        return try {
            val base = URL(baseUrl)
            val resolved = URL(base, line)
            resolved.toString()
        } catch (e: MalformedURLException) {
            LOG.e("echo-resolveForwardUrl异常: " + e.message)
            line
        }
    }
}
