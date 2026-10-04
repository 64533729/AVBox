package com.github.tvbox.osc.sourcedata

import android.text.TextUtils

import com.github.catvod.net.OkHttp
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.FileUtils
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.RegexUtils
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lzy.okgo.OkGo
import com.lzy.okgo.request.GetRequest

import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 站点取数的公共支撑:线程池、站点级请求构造、豆瓣源识别、extend 解析、结果来源标注。
 *
 * 这些能力被多个 Loader 共用,放在这里而不是各自复制;extend 解析缓存归
 * [SourceRuntimeState],由调用方把自己的缓存传进来。
 */
object SourceHelper {

    // 2026-09-11:单线程改 3 线程——原单线程被卡死的 spider 任务(不响应 interrupt)永久占用后,后续全部任务排队,首页永久骨架屏
    @JvmField
    val SPIDER_POOL: ExecutorService = Executors.newFixedThreadPool(3)

    @JvmField
    val PREPARE_POOL: ExecutorService = Executors.newFixedThreadPool(3)

    /** i18n: keep —— 只进日志(convertResponse → onError → LOG.i),无 UI 出口 */
    const val ERR_NETWORK = "网络请求错误"

    /**
     * type 0/1/4 接口请求统一入口:带上站点级 header(fongmi 的 sites[].header)。
     * spider 的请求走 jar 内自有网络栈,注入不进去(fongmi 官方也标注 type 3 不套用)。
     */
    @JvmStatic
    fun siteGet(sourceBean: SourceBean): GetRequest<String> {
        val request = OkGo.get<String>(sourceBean.api!!)
        for ((key, value) in sourceBean.header!!) {
            request.headers(key, value)
        }
        return request
    }

    @JvmStatic
    fun isDoubanSource(sourceBean: SourceBean?): Boolean {
        if (sourceBean == null) return false
        return containsDouban(sourceBean.key) ||
            containsDouban(sourceBean.name) ||
            containsDouban(sourceBean.api) ||
            containsDouban(sourceBean.ext)
    }

    private fun containsDouban(value: String?): Boolean {
        if (TextUtils.isEmpty(value)) return false
        val lower = value!!.lowercase(Locale.getDefault())
        return lower.contains("douban") || value.contains("豆瓣")
    }

    /** 首页源判定:兜底源可能不是列表第 0 项(第 0 项被标 hide 时会往后挑),所以比首页源 key 而不是下标 0 */
    @JvmStatic
    fun isHomeSource(sourceKey: String?): Boolean {
        return !TextUtils.isEmpty(sourceKey) && sourceKey == ApiConfig.get().getHomeSourceBean().key
    }

    @JvmStatic
    fun absXml(data: AbsXml, sourceKey: String?) {
        absXml(data, sourceKey, "")
    }

    @JvmStatic
    fun absXml(data: AbsXml, sourceKey: String?, searchToken: String?) {
        data.sourceKey = sourceKey
        data.searchToken = searchToken
        val videoList = data.movie?.videoList
        if (videoList != null) {
            for (video in videoList) {
                val infoList = video.urlBean?.infoList
                if (infoList != null) {
                    for (urlInfo in infoList) {
                        val urls = urlInfo.urls!!
                        // Java 的 split("#") 走 Pattern.split:尾部空串被丢掉;Kotlin 的 split(Regex) 会保留
                        val str: Array<String> = if (urls.contains("#")) RegexUtils.getPattern("#").split(urls) else arrayOf(urls)
                        val infoBeanList = ArrayList<Movie.Video.UrlBean.UrlInfo.InfoBean>()
                        for (s in str) {
                            val ss = s.split(Regex("\\$"), 2)
                            if (ss.isNotEmpty()) {
                                if (ss.size >= 2) {
                                    infoBeanList.add(Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]))
                                } else {
                                    infoBeanList.add(Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size + 1).toString(), ss[0]))
                                }
                            }
                        }
                        urlInfo.beanList = infoBeanList
                    }
                }
                video.sourceKey = sourceKey
            }
        }
    }

    /**
     * extend 解析:本地 127.0.0.1 走文件,其余走网络,结果压成单行 JSON 后进缓存。
     * 超时返回原值(不是空串),否则站点会收到被清空的 extend。
     */
    @JvmStatic
    fun getFixUrl(extendCache: ConcurrentHashMap<String, String>, gson: Gson, extend: String?, timeoutSeconds: Long): String? {
        if (TextUtils.isEmpty(extend)) return ""
        val url = extend!!
        if (!url.startsWith("http")) return url
        val key = MD5.string2MD5(url)!!
        if (extendCache.containsKey(key)) {
            LOG.i("echo-getFixUrl Cache")
            return extendCache[key]
        }
        val future: Future<String> = SPIDER_POOL.submit(Callable<String> {
            var result: String? = url
            if (url.startsWith("http://127.0.0.1")) {
                var path = url.replace(Regex("^http.+/file/"), FileUtils.getRootPath() + "/")
                path = path.replace(Regex("localhost/"), "/")
                result = FileUtils.readFileToString(path, "UTF-8")
                result = tryMinifyJson(gson, result!!)
                extendCache.putIfAbsent(key, result)
            } else if (url.startsWith("http")) {
                result = OkHttp.string(url, null)
                if (!result!!.isEmpty()) {
                    result = tryMinifyJson(gson, result)
                    if (result.length > 2500) result = url
                    extendCache.putIfAbsent(key, result)
                }
            }
            result
        })

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (te: TimeoutException) {
            LOG.e("SourceViewModel", te)
            future.cancel(true)
            return extend
        } catch (e: Exception) {
            LOG.e("SourceViewModel", e)
            return extend
        }
    }

    /** 同 [getFixUrl],但直接在当前线程取(调用方已在后台线程时用) */
    @JvmStatic
    fun getFixUrlDirect(extendCache: ConcurrentHashMap<String, String>, gson: Gson, extend: String?): String? {
        if (TextUtils.isEmpty(extend)) return ""
        val url = extend!!
        if (!url.startsWith("http")) return url
        val key = MD5.string2MD5(url)!!
        if (extendCache.containsKey(key)) {
            return extendCache[key]
        }
        var result: String? = url
        try {
            if (url.startsWith("http://127.0.0.1")) {
                var path = url.replace(Regex("^http.+/file/"), FileUtils.getRootPath() + "/")
                path = path.replace(Regex("localhost/"), "/")
                result = FileUtils.readFileToString(path, "UTF-8")
                result = tryMinifyJson(gson, result!!)
                extendCache.putIfAbsent(key, result)
            } else {
                result = OkHttp.string(url, null)
                if (!TextUtils.isEmpty(result)) {
                    result = tryMinifyJson(gson, result!!)
                    if (result.length > 2500) result = url
                    extendCache.putIfAbsent(key, result)
                }
            }
        } catch (th: Throwable) {
            LOG.e("SourceViewModel", th)
            return extend
        }
        return result
    }

    private fun tryMinifyJson(gson: Gson, raw: String): String {
        var text = raw
        try {
            // 兼容:Java 的 trim() 只去 <=0x20,Kotlin 的 trim() 会连 Unicode 空白一起去
            text = text.trim { it <= ' ' }
            val jsonElement = JsonParser.parseString(text)
            return gson.toJson(jsonElement)
        } catch (e: Exception) {
            return text
        }
    }
}
