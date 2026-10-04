package com.github.tvbox.osc.sourcedata

import android.os.Looper
import com.github.catvod.crawler.Spider
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.BoundedCall
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.RemoteTVBox
import com.google.gson.Gson
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response
import okhttp3.Call
import java.io.IOException
import java.net.URLEncoder
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

/**
 * 首页取数:站点分类(sort/分类列表)与首页推荐位。
 *
 * <p>带 homeContent 缓存(最多 5 个源),命中的判定与写入条件都在这里;缓存本体归
 * [SourceRuntimeState],这里只拿引用,便于统一清理。
 */
class SortLoader(
    private val gson: Gson,
    private val extendCache: ConcurrentHashMap<String, String>,
    private val sortCache: MutableMap<String, AbsSortXml?>,
    private val sortResult: SourceChannel<AbsSortXml?>,
    private val listLoader: ListLoader,
    private val resultParser: SourceResultParser,
) {

    private fun cacheSort(sourceKey: String, sortXml: AbsSortXml?) {
        attachSortSource(sourceKey, sortXml)
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (!hasHomeRecVideos(sortXml)) {
            return
        }
        if (!shouldBypassSortCache(sourceKey, sourceBean) && !hasActionSort(sortXml)) {
            synchronized(sortCache) {
                sortCache[sourceKey] = sortXml
            }
        }
    }

    private fun attachSortSource(sourceKey: String?, sortXml: AbsSortXml?): AbsSortXml? {
        if (sortXml != null) {
            sortXml.sourceKey = sourceKey
        }
        return sortXml
    }

    private fun postSortResult(sourceKey: String?, sortXml: AbsSortXml?) {
        var data = sortXml
        if (data == null) {
            data = AbsSortXml()
        }
        sortResult.postValue(attachSortSource(sourceKey, data))
    }

    /** 分类取数失败出口:空包 + loadFailed 标记,由 HomeViewModel 决定重试/错误态 */
    private fun postSortFailure(sourceKey: String?) {
        val sortXml = AbsSortXml()
        sortXml.loadFailed = true
        sortResult.postValue(attachSortSource(sourceKey, sortXml))
    }

    private fun hasActionSort(sortXml: AbsSortXml?): Boolean {
        if (sortXml == null) return false
        if (hasActionVideo(sortXml.videoList)) return true
        val list = sortXml.list
        return list != null && hasActionVideo(list.videoList)
    }

    private fun hasHomeRecVideos(sortXml: AbsSortXml?): Boolean {
        val videoList = sortXml?.videoList
        return videoList != null && videoList.isNotEmpty()
    }

    private fun hasActionVideo(videos: List<Movie.Video?>?): Boolean {
        if (videos == null) return false
        for (video in videos) {
            if (video?.action != null) return true
        }
        return false
    }

    private fun shouldBypassSortCache(sourceKey: String?, sourceBean: SourceBean?): Boolean {
        return SourceHelper.isHomeSource(sourceKey) && SourceHelper.isDoubanSource(sourceBean)
    }

    // homeContent
    fun getSort(sourceKey: String?) {
        getSort(sourceKey, true)
    }

    /** withRec=false 跳过首页推荐那一次额外请求(豆瓣类 videolist / spider homeVideoContent),sorts 不必等它 */
    fun getSort(sourceKey: String?, withRec: Boolean) {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            // t4 源要联网拉 extend 才能发 sort 请求,不能占着主线程等它
            SourceHelper.PREPARE_POOL.execute {
                getSort(sourceKey, withRec)
            }
            return
        }
        if (sourceKey == null) {
            sortResult.postValue(AbsSortXml())
            return
        }

        // 优先检查缓存
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (sourceBean == null) {
            LOG.i("echo--getSort-source-null--$sourceKey")
            postSortResult(sourceKey, null)
            return
        }
        val name = sourceBean.name!!
        if (name.length <= 3 && name.endsWith("搜")) { // i18n: keep
            postSortResult(sourceKey, null)
            return
        }

        if (!shouldBypassSortCache(sourceKey, sourceBean)) {
            val cached: AbsSortXml? = synchronized(sortCache) {
                sortCache[sourceKey]
            }
            if (cached != null) {
                val cachedVideoList = cached.videoList
                if (cachedVideoList != null && cachedVideoList.isNotEmpty()) {
                    attachSortSource(sourceKey, cached)
                    postSortResult(sourceKey, cached)
                    return
                }
            }
        }

        val type = sourceBean.type
        if (type == 3) {
            getSortFromSpider(sourceKey, sourceBean, withRec)
        } else if (type == 0 || type == 1) {
            getSortFromApi(sourceKey, sourceBean, withRec)
        } else if (type == 4) {
            getSortFromExtendedApi(sourceKey, sourceBean)
        } else {
            postSortResult(sourceKey, null)
        }
    }

    /** type 3:爬虫 homeContent,拿到 sorts 后再补一次首页推荐(推荐走 [ListLoader]) */
    private fun getSortFromSpider(sourceKey: String, sourceBean: SourceBean, withRec: Boolean) {
        val waitResponse = Runnable {
            val sortJson = BoundedCall.call(Callable<String> {
                val sp = ApiConfig.get().getCSP(sourceBean)
                val json = sp.homeContent(true)
//                            LOG.i("echo--getSort :" + json);
                json
            }, sourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getSort--" + sourceBean.key)
            if (sortJson != null) {
                val sortXml = resultParser.sortJson(sortResult, sortJson)
                attachSortSource(sourceKey, sortXml)
                if (sortXml != null) {
                    val absXml = resultParser.json(null, sortJson, sourceBean.key)
                    val absVideoList = absXml?.movie?.videoList
                    if (!withRec) {
                        postSortResult(sourceKey, sortXml)
                        cacheSort(sourceKey, sortXml)
                    } else if (absVideoList != null && absVideoList.size > 0) {
                        sortXml.videoList = absVideoList
                        postSortResult(sourceKey, sortXml)
                        cacheSort(sourceKey, sortXml)
                    } else if (sortXml.classes != null) {
                        // homeContent 解析成功却没带推荐视频是常态(分类够用),不能当取数失败
                        postSortResult(sourceKey, sortXml)
                        cacheSort(sourceKey, sortXml)
                    } else {
                        listLoader.getHomeRecList(sourceBean, null, object : ListLoader.HomeRecCallback {
                            override fun done(videos: MutableList<Movie.Video>?) {
                                sortXml.videoList = videos
                                postSortResult(sourceKey, sortXml)
                                cacheSort(sourceKey, sortXml)
                            }
                        })
                    }
                } else {
                    postSortFailure(sourceKey)
                }
            } else {
                LOG.i("echo--getSort-spider-null:$sourceKey")
                postSortFailure(sourceKey)
            }
        }
        SourceHelper.PREPARE_POOL.execute(waitResponse)
    }

    /** type 0/1:站点 XML / JSON 接口,带站点级 header */
    private fun getSortFromApi(sourceKey: String, sourceBean: SourceBean, withRec: Boolean) {
        // 回调里要按 type 分流 xml/json,值语义与调用点一致(原为捕获 getSort 的局部量)
        val type = sourceBean.type
        SourceHelper.siteGet(sourceBean)
            .tag(sourceBean.key + "_sort")
            .execute(object : AbsCallback<String>() {
                override fun convertResponse(response: okhttp3.Response): String {
                    val body = response.body
                    return if (body != null) body.string() else throw IllegalStateException(SourceHelper.ERR_NETWORK)
                }

                override fun onSuccess(response: Response<String>) {
                    val sortXml: AbsSortXml? = if (type == 0) {
                        val xml = response.body()
                        resultParser.sortXml(sortResult, xml)
                    } else if (type == 1) {
                        val json = response.body()
                        resultParser.sortJson(sortResult, json)
                    } else {
                        null
                    }
                    attachSortSource(sourceKey, sortXml)
                    if (sortXml != null) {
                        val recVideoList = sortXml.list?.videoList
                        if (withRec && recVideoList != null && recVideoList.size > 0) {
                            val ids = ArrayList<String?>()
                            for (vod in recVideoList) {
                                ids.add(vod.id)
                            }
                            listLoader.getHomeRecList(sourceBean, ids, object : ListLoader.HomeRecCallback {
                                override fun done(videos: MutableList<Movie.Video>?) {
                                    sortXml.videoList = videos
                                    postSortResult(sourceKey, sortXml)
                                    cacheSort(sourceKey, sortXml)
                                }
                            })
                        } else if (sortXml.classes != null) {
                            // 分类已解析出来,推荐位缺失不影响首页可用性;postSortFailure 只留给真没解析出响应的情况
                            postSortResult(sourceKey, sortXml)
                            cacheSort(sourceKey, sortXml)
                        } else {
                            postSortFailure(sourceKey)
                        }
                    } else {
                        postSortFailure(sourceKey)
                    }
                }

                override fun onError(response: Response<String>) {
                    super.onError(response)
                    LOG.i(
                        "echo--getSort-api-error:" + sourceKey + " code=" + response.code()
                            + " ex=" + response.exception
                    )
                    postSortFailure(sourceKey)
                }
            })
    }

    /** type 4:带 extend 的接口;extend 过长时改走 RemoteTVBox 的 POST(URL 长度限制) */
    private fun getSortFromExtendedApi(sourceKey: String, sourceBean: SourceBean) {
        var extend = sourceBean.ext
        extend = SourceHelper.getFixUrl(extendCache, gson, extend, sourceBean.getPlayTimeoutSeconds().toLong())
        if (URLEncoder.encode(extend).length < 1000) {
            val request = SourceHelper.siteGet(sourceBean)
                .tag(sourceBean.key + "_sort")
                .params("filter", "true")
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
                    val sortJson = response.body()
                    if (sortJson != null) {
                        val sortXml = resultParser.sortJson(sortResult, sortJson)
                        attachSortSource(sourceKey, sortXml)
                        if (sortXml != null) {
                            val absXml = resultParser.json(null, sortJson, sourceBean.key)
                            val absVideoList = absXml?.movie?.videoList
                            if (absVideoList != null && absVideoList.size > 0) {
                                sortXml.videoList = absVideoList
                                postSortResult(sourceKey, sortXml)
                                cacheSort(sourceKey, sortXml)
                            } else {
                                listLoader.getHomeRecList(sourceBean, null, object : ListLoader.HomeRecCallback {
                                    override fun done(videos: MutableList<Movie.Video>?) {
                                        sortXml.videoList = videos
                                        postSortResult(sourceKey, sortXml)
                                        cacheSort(sourceKey, sortXml)
                                    }
                                })
                            }
                        } else {
                            postSortFailure(sourceKey)
                        }
                    } else {
                        postSortFailure(sourceKey)
                    }
                }

                override fun onError(response: Response<String>) {
                    super.onError(response)
                    LOG.i(
                        "echo--getSort-ext-error:" + sourceKey + " code=" + response.code()
                            + " ex=" + response.exception
                    )
                    postSortFailure(sourceKey)
                }
            })
        } else {
            try {
                val params = HashMap<String, String>()
                params["filter"] = "true"
                if (extend != null && !extend.isEmpty()) {
                    params["extend"] = extend
                }
                RemoteTVBox.post(sourceBean.api, params, sourceBean.header, object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        LOG.i("echo--getSort-post-fail:" + sourceKey + " ex=" + e)
                        postSortFailure(sourceKey)
                    }

                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        val body = response.body
                        assert(body != null)
                        val sortJson = body.string()
                        val sortXml = resultParser.sortJson(sortResult, sortJson)
                        attachSortSource(sourceKey, sortXml)
                        if (sortXml != null) {
                            val absXml = resultParser.json(null, sortJson, sourceBean.key)
                            val absVideoList = absXml?.movie?.videoList
                            if (absVideoList != null && absVideoList.size > 0) {
                                sortXml.videoList = absVideoList
                                postSortResult(sourceKey, sortXml)
                                cacheSort(sourceKey, sortXml)
                            } else if (sortXml.classes != null) {
                                // 同上:解析成功但无推荐要走成功出口,否则这条分支全程无回包、只能等超时
                                postSortResult(sourceKey, sortXml)
                                cacheSort(sourceKey, sortXml)
                            } else {
                                postSortFailure(sourceKey)
                            }
                        } else {
                            postSortFailure(sourceKey)
                        }
                    }
                })
            } catch (ignored: Exception) {
                postSortFailure(sourceKey)
            }
        }
    }
}
