package com.github.tvbox.osc.sourcedata

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.BoundedCall
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.PlayerHelper
import com.google.gson.Gson
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.HashMap
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PlayLoader(
    private val gson: Gson,
    private val extendCache: ConcurrentHashMap<String, String>,
    private val playResult: SourceChannel<JSONObject?>,
    private val preloadResult: SourceChannel<JSONObject?>,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playRequestSeq = AtomicInteger()
    private val preloadRequestSeq = AtomicInteger()

    fun getPlay(sourceKey: String?, playFlag: String?, progressKey: String?, url: String?, subtitleKey: String?) {
        getPlayInternal(playRequestSeq, playResult, "play", sourceKey, playFlag, progressKey, url, subtitleKey)
    }

    fun getPlayForPreload(sourceKey: String?, playFlag: String?, progressKey: String?, url: String?, subtitleKey: String?) {
        getPlayInternal(preloadRequestSeq, preloadResult, "playPreload", sourceKey, playFlag, progressKey, url, subtitleKey)
    }

    private fun getPlayInternal(
        seqHolder: AtomicInteger,
        resultChannel: SourceChannel<JSONObject?>,
        requestTag: String,
        sourceKey: String?,
        playFlag: String?,
        progressKey: String?,
        url: String?,
        subtitleKey: String?,
    ) {
        val requestSeq = seqHolder.incrementAndGet()
        if (Looper.myLooper() === Looper.getMainLooper()) {
            SourceHelper.PREPARE_POOL.execute {
                getPlayPrepared(seqHolder, resultChannel, requestSeq, requestTag, sourceKey, playFlag, progressKey, url, subtitleKey)
            }
            return
        }
        getPlayPrepared(seqHolder, resultChannel, requestSeq, requestTag, sourceKey, playFlag, progressKey, url, subtitleKey)
    }

    private fun getPlayPrepared(
        seqHolder: AtomicInteger,
        resultChannel: SourceChannel<JSONObject?>,
        requestSeq: Int,
        requestTag: String,
        sourceKey: String?,
        playFlag: String?,
        progressKey: String?,
        url: String?,
        subtitleKey: String?,
    ) {
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        val pushFallback = PushUrlParser.isPushFallback(sourceKey, sourceBean)
        val pushUrl = if (pushFallback) PushUrlParser.parsePushUrl(url) else PushUrlParser.createPushUrl(url)
        val requestUrl = pushUrl.url
        if (pushFallback) {
            postPlayResult(seqHolder, resultChannel, requestSeq, PushUrlParser.createPushPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag))
            return
        }
        if (sourceBean == null) {
            LOG.i("echo--getPlay--source-null--$sourceKey")
            postPlayResult(seqHolder, resultChannel, requestSeq, null)
            return
        }
        val type = sourceBean.type
        if (type == 3) {
            playFromSpider(seqHolder, resultChannel, requestSeq, sourceBean, requestUrl, url, progressKey, subtitleKey, playFlag, pushUrl)
        } else if (type == 0 || type == 1) {
            playFromApi(seqHolder, resultChannel, requestSeq, sourceBean, requestUrl, url, progressKey, subtitleKey, playFlag, pushUrl)
        } else if (type == 4) {
            playFromExtendedApi(seqHolder, resultChannel, requestSeq, requestTag, sourceBean, requestUrl, url, progressKey, subtitleKey, playFlag, pushUrl)
        } else {
            postPlayResult(seqHolder, resultChannel, requestSeq, null)
        }
    }

    private fun playFromSpider(
        seqHolder: AtomicInteger,
        resultChannel: SourceChannel<JSONObject?>,
        requestSeq: Int,
        sourceBean: SourceBean,
        requestUrl: String,
        url: String?,
        progressKey: String?,
        subtitleKey: String?,
        playFlag: String?,
        pushUrl: PushUrlParser.PushUrl,
    ) {
        SourceHelper.SPIDER_POOL.execute {
            val json = BoundedCall.call(Callable<String> {
                val sp = ApiConfig.get().getCSP(sourceBean)
                if (TextUtils.isEmpty(requestUrl)) return@Callable ""
                try {
                    LOG.i("echo--getPlay--id: $requestUrl")
                    sp.playerContent(playFlag, requestUrl, ApiConfig.get().getVipParseFlags())
                } catch (e: Exception) {
                    LOG.i("echo--getPlay--error: " + e.message)
                    ""
                }
            }, sourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getPlay--" + sourceBean.key)
            LOG.i("echo--getPlay--result:$json")
            if (TextUtils.isEmpty(json)) {
                postPlayResult(seqHolder, resultChannel, requestSeq, null)
                return@execute
            }
            try {
                val result = normalizePlayerResult(JSONObject(json))!!
                result.put("key", url)
                PushUrlParser.mergePushHeaders(result, pushUrl)
                mergeSiteHeaders(result, sourceBean)
                result.put("proKey", progressKey)
                result.put("subtKey", subtitleKey)
                if (!result.has("flag")) result.put("flag", playFlag)
                if (TextUtils.isEmpty(result.optString("url", "")) && shouldDirectPlay(sourceBean, requestUrl)) {
                    postPlayResult(seqHolder, resultChannel, requestSeq, createDirectPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag, sourceBean))
                } else {
                    postPlayResult(seqHolder, resultChannel, requestSeq, result)
                }
            } catch (e: Exception) {
                LOG.i("echo--getPlay--error: " + e.message)
                postPlayResult(seqHolder, resultChannel, requestSeq, null)
            }
        }
    }

    private fun playFromApi(
        seqHolder: AtomicInteger,
        resultChannel: SourceChannel<JSONObject?>,
        requestSeq: Int,
        sourceBean: SourceBean,
        requestUrl: String,
        url: String?,
        progressKey: String?,
        subtitleKey: String?,
        playFlag: String?,
        pushUrl: PushUrlParser.PushUrl,
    ) {
        val result = JSONObject()
        try {
            result.put("key", url)
            val playUrl = sourceBean.playerUrl!!.trim { it <= ' ' }
            if (DefaultConfig.isVideoFormat(requestUrl) && playUrl.isEmpty()) {
                result.put("parse", 0)
                result.put("url", requestUrl)
            } else {
                result.put("parse", 1)
                result.put("url", requestUrl)
            }
            PushUrlParser.mergePushHeaders(result, pushUrl)
            mergeSiteHeaders(result, sourceBean)
            result.put("proKey", progressKey)
            result.put("subtKey", subtitleKey)
            result.put("playUrl", playUrl)
            result.put("flag", playFlag)
            postPlayResult(seqHolder, resultChannel, requestSeq, result)
        } catch (th: Throwable) {
            LOG.e("SourceViewModel", th)
            postPlayResult(seqHolder, resultChannel, requestSeq, null)
        }
    }

    private fun playFromExtendedApi(
        seqHolder: AtomicInteger,
        resultChannel: SourceChannel<JSONObject?>,
        requestSeq: Int,
        requestTag: String,
        sourceBean: SourceBean,
        requestUrl: String,
        url: String?,
        progressKey: String?,
        subtitleKey: String?,
        playFlag: String?,
        pushUrl: PushUrlParser.PushUrl,
    ) {
        val extend = SourceHelper.getFixUrl(extendCache, gson, sourceBean.ext, sourceBean.getPlayTimeoutSeconds().toLong())

        val request = SourceHelper.siteGet(sourceBean)
            .tag(requestTag)
            .params("play", requestUrl)
            .params("flag", playFlag)
        if (extend != null && !extend.isEmpty()) {
            request.params("extend", extend)
        }
        request.execute(object : AbsCallback<String>() {
            override fun convertResponse(response: okhttp3.Response): String {
                val body = response.body
                return if (body != null) body.string() else throw IllegalStateException(SourceHelper.ERR_NETWORK)
            }

            override fun onSuccess(response: Response<String>) {
                val json = response.body()
                LOG.i(json)
                try {
                    val result = normalizePlayerResult(JSONObject(json))!!
                    result.put("key", url)
                    PushUrlParser.mergePushHeaders(result, pushUrl)
                    mergeSiteHeaders(result, sourceBean)
                    result.put("proKey", progressKey)
                    result.put("subtKey", subtitleKey)
                    if (!result.has("flag")) result.put("flag", playFlag)
                    postPlayResult(seqHolder, resultChannel, requestSeq, result)
                } catch (th: Throwable) {
                    LOG.e("SourceViewModel", th)
                    postPlayResult(seqHolder, resultChannel, requestSeq, null)
                }
            }

            override fun onError(response: Response<String>) {
                super.onError(response)
                postPlayResult(seqHolder, resultChannel, requestSeq, null)
            }
        })
    }

    fun cancelPlayRequest() {
        playRequestSeq.incrementAndGet()
    }

    private fun shouldDirectPlay(sourceBean: SourceBean?, requestUrl: String?): Boolean {
        if (sourceBean == null || TextUtils.isEmpty(requestUrl)) return false
        val url = requestUrl!!
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun createDirectPlayResult(
        rawUrl: String?,
        pushUrl: PushUrlParser.PushUrl,
        progressKey: String?,
        subtitleKey: String?,
        playFlag: String?,
        sourceBean: SourceBean?,
    ): JSONObject? {
        try {
            val result = JSONObject()
            result.put("key", rawUrl)
            result.put("proKey", progressKey)
            result.put("subtKey", subtitleKey)
            result.put("flag", playFlag)
            result.put("parse", 0)
            result.put("jx", 0)
            result.put("url", pushUrl.url)
            PushUrlParser.mergePushHeaders(result, pushUrl)
            mergeSiteHeaders(result, sourceBean)
            LOG.i("echo--getPlay--direct:" + pushUrl.url)
            return result
        } catch (th: Throwable) {
            LOG.e("SourceViewModel", th)
            return null
        }
    }

    private fun normalizePlayerResult(result: JSONObject?): JSONObject? {
        if (result == null) return null
        try {
            val playUrl = result.optString("playUrl", "")
            var url = result.optString("url", "")
            if (TextUtils.isEmpty(url)) return result
            if (url.startsWith("[") && url.endsWith("]")) {
                val array = JSONArray(url)
                for (i in 0 until array.length()) {
                    val item = array.get(i)
                    if (item is String) {
                        var str = item
                        if (str.startsWith("proxy://")) {
                            str = DefaultConfig.checkReplaceProxy(str)
                            array.put(i, str)
                        } else if (str.startsWith("video://")) {
                            str = str.substring(8)
                            array.put(i, str)
                        }
                    }
                }
                result.put("url", array.toString())
                result.put("parse", 0)
                return result
            }
            if (url.startsWith("video://")) {
                url = url.substring(8)
                result.put("url", url)
                result.put("parse", 1)
            } else if (url.startsWith("proxy://")) {
                url = DefaultConfig.checkReplaceProxy(url)
                result.put("url", url)
                result.put("parse", 0)
            } else if (playUrl.length == 0
                && DefaultConfig.isVideoFormat(url)
                && !result.has("parse")
                && !result.has("jx")
            ) {
                result.put("parse", 0)
            }
        } catch (th: Throwable) {
            LOG.e("SourceViewModel", th)
        }
        return result
    }

    private fun mergeSiteHeaders(result: JSONObject?, sourceBean: SourceBean?) {
        if (result == null || sourceBean == null) return
        val siteHeader = sourceBean.header!!
        if (siteHeader.isEmpty()) return
        try {
            val extracted: HashMap<String, String>? = PlayerHelper.extractPlayHeaders(result)
            val merged = extracted ?: HashMap()
            for ((key, value) in siteHeader) {
                if (!merged.containsKey(key)) merged[key] = value
            }
            val header = JSONObject()
            for ((key, value) in merged) header.put(key, value)
            result.put("header", header)
            result.remove("headers")
        } catch (th: Throwable) {
            LOG.e("SourceViewModel", "merge site headers failed", th)
        }
    }

    companion object {

        @JvmStatic
        fun isStaleResult(requestSeq: Int, seqHolder: AtomicInteger): Boolean {
            return requestSeq != seqHolder.get()
        }
    }

    private fun postPlayResult(seqHolder: AtomicInteger, resultChannel: SourceChannel<JSONObject?>, requestSeq: Int, result: JSONObject?) {
        mainHandler.post {
            if (isStaleResult(requestSeq, seqHolder)) {
                LOG.i("echo--getPlay--ignore stale result")
                return@post
            }
            resultChannel.setValue(result)
        }
    }
}
