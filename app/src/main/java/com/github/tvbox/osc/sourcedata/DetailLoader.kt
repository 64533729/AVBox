package com.github.tvbox.osc.sourcedata

import android.os.Looper
import android.util.Base64
import com.github.catvod.crawler.Spider
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.BoundedCall
import com.github.tvbox.osc.util.LOG
import com.google.gson.Gson
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

/**
 * 详情取数(detailContent)。
 *
 * <p>三类特殊入口都在这里:推送链接(push://→ 本地合成详情)、源已失效(返回空详情走空态,
 * 不在调用方 NPE)、换源回退(fallback,超时收紧到 6s)。
 */
class DetailLoader(
    private val gson: Gson,
    private val extendCache: ConcurrentHashMap<String, String>,
    private val detailResult: SourceChannel<AbsXml?>,
    private val resultParser: SourceResultParser,
) {

    // detailContent
    fun getDetail(sourceKey: String?, urlid: String) {
        getDetail(sourceKey, urlid, false)
    }

    fun getDetail(sourceKey: String?, urlid: String, fallback: Boolean) {
        getDetail(sourceKey, urlid, fallback, null)
    }

    /**
     * @param requestToken 详情代次(V4):回包原样带回去,由页面判"是否属于当前这一代";
     *                     null = 不判代次(老调用点)。
     */
    fun getDetail(sourceKey: String?, urlid: String, fallback: Boolean, requestToken: Int?) {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            // 同 getSort:t0/1/4 的 extend 拉取会阻塞
            val key = sourceKey
            val id = urlid
            SourceHelper.PREPARE_POOL.execute {
                getDetail(key, id, fallback, requestToken)
            }
            return
        }
        var key = sourceKey
        var id = urlid
        if (id.startsWith("push://") && ApiConfig.get().getSource(PushUrlParser.PUSH_AGENT) != null) {
            var pushUrl = id.substring(7)
            if (pushUrl.startsWith("b64:")) {
                try {
                    pushUrl = String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                } catch (e: UnsupportedEncodingException) {
                    LOG.e("SourceViewModel", e)
                }
            } else {
                pushUrl = URLDecoder.decode(pushUrl)
            }
            key = if (PushUrlParser.isCastPushUrl(pushUrl)) PushUrlParser.PUSH_FALLBACK else PushUrlParser.PUSH_AGENT
            id = pushUrl
        } else if (PushUrlParser.PUSH_AGENT == key && PushUrlParser.isCastPushUrl(id)) {
            key = PushUrlParser.PUSH_FALLBACK
        }

        val sourceBean = ApiConfig.get().getSource(key)
        if (PushUrlParser.isPushFallback(key, sourceBean)) {
            detailResult.postValue(createPushDetail(id, key, requestToken))
            return
        }
        if (sourceBean == null) {
            // 源已不存在(2026-09-13):典型场景 = 切到新源后加载完成前,从历史记录点进
            // 一条属于旧源(已失效 key)的条目;或订阅源被删。此处返回空 AbsXml(与末尾
            // 未知 type 分支同形状),详情页走空态,而不是在下面 sourceBean.getType() 处 NPE
            LOG.i("echo--getDetail--source-null--$key")
            detailResult.postValue(createEmptyDetail(key, requestToken))
            return
        }
        val type = sourceBean.type
        if (type == 3) {
            getDetailFromSpider(sourceBean, id, fallback, requestToken)
        } else if (type == 0 || type == 1 || type == 4) {
            getDetailFromApi(sourceBean, id, fallback, requestToken)
        } else {
            detailResult.postValue(createEmptyDetail(key, requestToken))
        }
    }

    /** type 3:爬虫 detailContent;换源回退(fallback)时超时收紧 */
    private fun getDetailFromSpider(sourceBean: SourceBean, id: String, fallback: Boolean, requestToken: Int?) {

        SourceHelper.SPIDER_POOL.execute {
            val json = BoundedCall.call(Callable<String> {
                val sp = ApiConfig.get().getCSP(sourceBean)
                val ids = ArrayList<String>()
                ids.add(id)
                try {
//                                LOG.i("echo--getDetail--id: " + id);
                    sp.detailContent(ids)
                } catch (e: Exception) {
                    LOG.i("echo--getDetail--error: " + e.message)
                    ""
                }
            }, if (fallback) 6_000L else sourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getDetail--" + sourceBean.key)
//                    LOG.i("echo--getDetail--result:" + json);
            resultParser.json(detailResult, json, sourceBean.key, "", requestToken)
        }
    }

    /** type 0/1/4:站点接口(带 extend);type 0 走 XML */
    private fun getDetailFromApi(sourceBean: SourceBean, id: String, fallback: Boolean, requestToken: Int?) {
        // 回调里要按 type 分流 xml/json,值语义与调用点一致(原为捕获上层局部量)
        val type = sourceBean.type

        val extend = if (fallback) {
            SourceHelper.getFixUrl(extendCache, gson, sourceBean.ext, 6L)
        } else {
            SourceHelper.getFixUrl(extendCache, gson, sourceBean.ext, sourceBean.getPlayTimeoutSeconds().toLong())
        }

        val request = SourceHelper.siteGet(sourceBean)
            .tag("detail")
            .params("ac", if (type == 0) "videolist" else "detail")
            .params("ids", id)
        // 当 extend 不为空且非空字符串时添加参数
        if (extend != null && !extend.isEmpty()) {
            request.params("extend", extend)
        }
        request.execute(object : AbsCallback<String>() {

            override fun convertResponse(response: okhttp3.Response): String {
                val body = response.body
                return if (body != null) body.string() else throw IllegalStateException(SourceHelper.ERR_NETWORK)
            }

            override fun onSuccess(response: Response<String>) {
                if (type == 0) {
                    val xml = response.body()
                    resultParser.xml(detailResult, xml, sourceBean.key, "", requestToken)
                } else {
                    val json = response.body()
                    LOG.i(json)
                    resultParser.json(detailResult, json, sourceBean.key, "", requestToken)
                }
            }

            override fun onError(response: Response<String>) {
                super.onError(response)
                resultParser.json(detailResult, "", sourceBean.key, "", requestToken)
            }
        })
    }

    /** 空详情(源不存在 / 未知 type):详情页按空态渲染,不带任何影片数据 */
    private fun createEmptyDetail(sourceKey: String?, requestToken: Int?): AbsXml {
        val data = AbsXml()
        data.sourceKey = sourceKey
        data.detailToken = requestToken
        return data
    }

    private fun createPushDetail(url: String?, sourceKey: String?, requestToken: Int?): AbsXml {
        val data = AbsXml()
        data.sourceKey = sourceKey
        data.detailToken = requestToken
        val movie = Movie()
        val videoList = ArrayList<Movie.Video>()
        movie.videoList = videoList
        val video = Movie.Video()
        video.id = url
        video.name = url
        // i18n: keep —— 以下是合成 Movie 的结构化数据(type/flag/`线路名$地址` 格式),会被持久化与比较,不能翻
        video.type = "推送"
        video.sourceKey = sourceKey
        val urlBean = Movie.Video.UrlBean()
        video.urlBean = urlBean
        val infoList = ArrayList<Movie.Video.UrlBean.UrlInfo>()
        urlBean.infoList = infoList
        val urlInfo = Movie.Video.UrlBean.UrlInfo()
        urlInfo.flag = "推送" // i18n: keep
        urlInfo.urls = "播放$url" // i18n: keep
        val beanList = ArrayList<Movie.Video.UrlBean.UrlInfo.InfoBean>()
        urlInfo.beanList = beanList
        beanList.add(Movie.Video.UrlBean.UrlInfo.InfoBean("播放", url)) // i18n: keep
        infoList.add(urlInfo)
        videoList.add(video)
        data.movie = movie
        return data
    }

    /**
     * 站点级 header 作为播放请求的兜底头(fongmi 同语义):只补结果里没有的键,结果自带的头优先。
     * 没配 header 的源这里是空操作。
     */
}
