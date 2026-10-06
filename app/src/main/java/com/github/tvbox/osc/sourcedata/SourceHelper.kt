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
import com.github.tvbox.osc.util.net.Http
import com.github.tvbox.osc.util.net.HttpRequest
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

object SourceHelper {

    @JvmField
    val SPIDER_POOL: ExecutorService = Executors.newFixedThreadPool(3)

    @JvmField
    val PREPARE_POOL: ExecutorService = Executors.newFixedThreadPool(3)

    /** i18n: keep —— 只进日志(convertResponse → onError → LOG.i),无 UI 出口 */
    const val ERR_NETWORK = "网络请求错误"

    suspend fun siteGet(sourceBean: SourceBean, init: HttpRequest.() -> Unit = {}): String {
        return Http.get(sourceBean.api!!) {
            for ((key, value) in sourceBean.header!!) {
                headers(key, value)
            }
            init()
        }
    }

    @JvmStatic
    fun siteGetRequest(sourceBean: SourceBean): GetRequest<String> {
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
            text = text.trim { it <= ' ' }
            val jsonElement = JsonParser.parseString(text)
            return gson.toJson(jsonElement)
        } catch (e: Exception) {
            return text
        }
    }
}
