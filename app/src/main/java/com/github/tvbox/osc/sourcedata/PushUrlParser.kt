package com.github.tvbox.osc.sourcedata

import android.text.TextUtils

import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.HeaderGuard
import com.github.tvbox.osc.util.LOG

import org.json.JSONObject

import java.net.URLDecoder
import java.util.HashMap

/**
 * 推送源(push://)URL 的解析与相关结果合成。
 *
 * 推送链接把标记头(urlencoded 的 JSON)用 `@Headers=...@` 夹在 URL 里:摘出来、URL 还原干净。
 * 标记头同样要过 [HeaderGuard](会进 OkGo 与本地 m3u8 净化)。
 */
object PushUrlParser {

    @JvmField
    val PUSH_AGENT = "push_agent"

    @JvmField
    val PUSH_FALLBACK = "push_fallback"

    @JvmField
    val PUSH_HEADERS_MARKER = "@Headers="

    @JvmStatic
    fun isPushFallback(sourceKey: String?, sourceBean: SourceBean?): Boolean {
        return PUSH_FALLBACK == sourceKey || (sourceBean != null && PUSH_AGENT == sourceBean.key && sourceBean.type == -1)
    }

    @JvmStatic
    fun isCastPushUrl(url: String?): Boolean {
        return !TextUtils.isEmpty(url) && url!!.contains(PUSH_HEADERS_MARKER)
    }

    @JvmStatic
    fun createPushPlayResult(rawUrl: String?, pushUrl: PushUrl, progressKey: String?, subtitleKey: String?, playFlag: String?): JSONObject? {
        try {
            val result = JSONObject()
            result.put("key", rawUrl)
            result.put("proKey", progressKey)
            result.put("subtKey", subtitleKey)
            result.put("flag", playFlag)
            result.put("parse", 0)
            result.put("url", pushUrl.url)
            mergePushHeaders(result, pushUrl)
            return result
        } catch (th: Throwable) {
            LOG.e("SourceViewModel", th)
            return null
        }
    }

    @JvmStatic
    fun mergePushHeaders(result: JSONObject?, pushUrl: PushUrl?) {
        if (result == null || pushUrl == null || pushUrl.headers.isEmpty()) return
        try {
            var header = result.optJSONObject("header")
            if (header == null) header = result.optJSONObject("headers")
            if (header == null) header = JSONObject()
            for (key in pushUrl.headers.keys) {
                header.put(key, pushUrl.headers[key])
            }
            result.put("header", header)
        } catch (th: Throwable) {
            LOG.e("SourceViewModel", "merge push headers failed", th)
        }
    }

    @JvmStatic
    fun createPushUrl(rawUrl: String?): PushUrl {
        val pushUrl = PushUrl()
        pushUrl.url = rawUrl ?: ""
        return pushUrl
    }

    @JvmStatic
    fun parsePushUrl(rawUrl: String?): PushUrl {
        val pushUrl = createPushUrl(rawUrl)
        parseMarkedHeaders(pushUrl)
        return pushUrl
    }

    private fun parseMarkedHeaders(pushUrl: PushUrl): Boolean {
        val marker = PUSH_HEADERS_MARKER
        val start = pushUrl.url.indexOf(marker)
        if (start < 0) return false
        val valueStart = start + marker.length
        val end = pushUrl.url.indexOf('@', valueStart)
        if (end < 0) return false
        try {
            val text = URLDecoder.decode(pushUrl.url.substring(valueStart, end), "UTF-8")
            val json = JSONObject(text)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (TextUtils.isEmpty(key)) continue
                // push 标记头同样会进 OkGo 与本地 m3u8 净化:非法字符挡在入口
                if (!HeaderGuard.isSendable(key, value)) {
                    LOG.i("echo-push-header-skip:$key")
                    continue
                }
                pushUrl.headers[key] = value
            }
            pushUrl.url = pushUrl.url.substring(0, start) + pushUrl.url.substring(end + 1)
            return true
        } catch (ignored: Throwable) {
            return false
        }
    }

    class PushUrl {
        @JvmField
        var url: String = ""

        @JvmField
        var headers: HashMap<String, String> = HashMap()
    }
}
