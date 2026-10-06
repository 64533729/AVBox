package com.github.tvbox.osc.sourcedata

import android.os.Looper
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

    fun getSort(sourceKey: String?) {
        getSort(sourceKey, true)
    }

    fun getSort(sourceKey: String?, withRec: Boolean) {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            SourceHelper.PREPARE_POOL.execute {
                getSort(sourceKey, withRec)
            }
            return
        }
        if (sourceKey == null) {
            sortResult.postValue(AbsSortXml())
            return
        }

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

    private fun getSortFromSpider(sourceKey: String, sourceBean: SourceBean, withRec: Boolean) {
        val waitResponse = Runnable {
            val sortJson = BoundedCall.call(Callable<String> {
                val sp = ApiConfig.get().getCSP(sourceBean)
                val json = sp.homeContent(true)
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

    private fun getSortFromApi(sourceKey: String, sourceBean: SourceBean, withRec: Boolean) {
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

    private fun getSortFromExtendedApi(sourceKey: String, sourceBean: SourceBean) {
        var extend = sourceBean.ext
        extend = SourceHelper.getFixUrl(extendCache, gson, extend, sourceBean.getPlayTimeoutSeconds().toLong())
        if (URLEncoder.encode(extend).length < 1000) {
            val request = SourceHelper.siteGet(sourceBean)
                .tag(sourceBean.key + "_sort")
                .params("filter", "true")
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
