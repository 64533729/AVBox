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

/**
 * m3u8 去广告用例（从 VodController:1667-1834 剥离，Compose 化改造 阶段 0）。
 *
 * 职责：拉取 m3u8 → 识别重定向（#EXT-X-STREAM-INF）→ M3u8.purify 去广告 →
 * 命中广告时走本地代理播放，否则回退直链。纯网络/解析逻辑，与 UI 无关。
 */
class M3u8PurifyUseCase(context: Context, private val callback: Callback) {

    interface Callback {
        fun startPlayUrl(url: String?, headers: HashMap<String, String>?)

        fun onM3u8ProxyUrl(proxyUrl: String?, sourceUrl: String?)
    }

    /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
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
        // 不要给 limit(2026-09-12 修复 Bug):原为 split(..., 50),limit>0 时最后一个元素是"第 50 行到文末"的整块,
        // 于是第一个 #EXT-X-STREAM-INF 出现在第 50 行之后会被漏识别(master 被当媒体列表),第 49 行则会拼出跨行畸形 URL
        val lines = RegexUtils.getPattern("\\r?\\n").split(content, -1)
        for (i in lines.indices) {
            val line = lines[i].trim { it <= ' ' }
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // 只需要找接下来的几行
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
        // 2026-09-13:只在真正走代理时才写入内容槽 —— 无广告(走直链)的集不写,
        // 避免把在播集的槽位冲掉;proxyUrl 带本次生成的键(?k=),服务端按键取内容
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
            // 使用 URL 构造器自动解析相对路径
            val base = URL(baseUrl)
            val resolved = URL(base, line)
            resolved.toString()
        } catch (e: MalformedURLException) {
            // 出现异常时可以记录日志，并返回原始 line
            LOG.e("echo-resolveForwardUrl异常: " + e.message)
            line
        }
    }
}
