package com.github.tvbox.osc.sourcedata

import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.BoundedCall
import com.github.tvbox.osc.util.LOG
import com.google.gson.Gson
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response
import org.json.JSONObject
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

class ListLoader(
    private val gson: Gson,
    private val extendCache: ConcurrentHashMap<String, String>,
    private val listResult: SourceChannel<AbsXml?>,
    private val resultParser: SourceResultParser,
) {

    fun getList(sortData: MovieSort.SortData?, page: Int) {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            SourceHelper.PREPARE_POOL.execute {
                getList(sortData, page)
            }
            return
        }
        if (sortData == null) {
            LOG.i("echo-getList-sortData-null")
            listResult.postValue(null)
            return
        }
        val homeSourceBean = ApiConfig.get().getHomeSourceBean()
        val type = homeSourceBean.type
        if (type == 3) {
            getListFromSpider(homeSourceBean, sortData, page)
        } else if (type == 0 || type == 1) {
            getListFromApi(homeSourceBean, sortData, page)
        } else if (type == 4) {
            getListFromExtendedApi(homeSourceBean, sortData, page)
        } else {
            listResult.postValue(null)
        }
    }

    private fun getListFromSpider(homeSourceBean: SourceBean, sortData: MovieSort.SortData, page: Int) {

        SourceHelper.SPIDER_POOL.execute {
            val json = BoundedCall.call(Callable<String> {
                val sp = ApiConfig.get().getCSP(homeSourceBean)
                sp.categoryContent(sortData.id, page.toString(), true, sortData.filterSelect)
            }, homeSourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getList--" + homeSourceBean.key)
            if (json != null) {
                resultParser.json(listResult, json, homeSourceBean.key)
            } else {
                LOG.i("echo--list-spider-null:" + homeSourceBean.key + " sort=" + sortData.id + " pg=" + page)
                listResult.postValue(null)
            }
        }
    }

    private fun getListFromApi(homeSourceBean: SourceBean, sortData: MovieSort.SortData, page: Int) {
        val type = homeSourceBean.type

        SourceHelper.siteGet(homeSourceBean)
            .tag(homeSourceBean.api)
            .params("ac", if (type == 0) "videolist" else "detail")
            .params("t", sortData.id)
            .params("pg", page)
            .params(sortData.filterSelect)
            .params(
                "f",
                if (sortData.filterSelect == null || sortData.filterSelect.size <= 0) ""
                else JSONObject(sortData.filterSelect).toString()
            )
            .execute(object : AbsCallback<String>() {

                override fun convertResponse(response: okhttp3.Response): String {
                    val body = response.body
                    return if (body != null) body.string() else throw IllegalStateException(SourceHelper.ERR_NETWORK)
                }

                override fun onSuccess(response: Response<String>) {
                    if (type == 0) {
                        val xml = response.body()
                        resultParser.xml(listResult, xml, homeSourceBean.key)
                    } else {
                        val json = response.body()
                        resultParser.json(listResult, json, homeSourceBean.key)
                    }
                }

                override fun onError(response: Response<String>) {
                    super.onError(response)
                    LOG.i(
                        "echo--list-api-error:" + homeSourceBean.key + " t=" + sortData.id + " pg=" + page
                            + " code=" + response.code() + " ex=" + response.exception
                    )
                    listResult.postValue(null)
                }
            })
    }

    private fun getListFromExtendedApi(homeSourceBean: SourceBean, sortData: MovieSort.SortData, page: Int) {

        var ext = ""
        var extend = homeSourceBean.ext
        extend = SourceHelper.getFixUrl(extendCache, gson, extend, homeSourceBean.getPlayTimeoutSeconds().toLong())
        if (sortData.filterSelect != null && sortData.filterSelect.size > 0) {
            try {
                val selectExt = JSONObject(sortData.filterSelect).toString()
                ext = Base64.encodeToString(selectExt.toByteArray(Charsets.UTF_8), Base64.DEFAULT or Base64.NO_WRAP)
            } catch (e: UnsupportedEncodingException) {
                LOG.e("SourceViewModel", e)
            }
        } else {
            ext = Base64.encodeToString("{}".toByteArray(Charset.defaultCharset()), Base64.DEFAULT or Base64.NO_WRAP)
        }

        val request = SourceHelper.siteGet(homeSourceBean)
            .tag(homeSourceBean.api)
            .params("ac", "detail")
            .params("filter", "true")
            .params("t", sortData.id)
            .params("pg", page)
            .params("ext", ext)
        if (extend != null && !extend.isEmpty()) {
            request.params("extend", extend)
        }
        request.execute(object : AbsCallback<String>() {
            override fun convertResponse(response: okhttp3.Response): String {
                try {
                    val body = response.body
                    return if (body != null) body.string()
                    else throw IllegalStateException(SourceHelper.ERR_NETWORK + "，response body 为 null") // i18n: keep
                } catch (e: Exception) {
                    LOG.i("echo-list: convertResponse error" + e.message)
                    throw e
                }
            }

            override fun onSuccess(response: Response<String>) {
                val json = response.body()
                resultParser.json(listResult, json, homeSourceBean.key)
            }

            override fun onError(response: Response<String>) {
                super.onError(response)
                LOG.i(
                    "echo--list-ext-error:" + homeSourceBean.key + " t=" + sortData.id + " pg=" + page
                        + " code=" + response.code() + " ex=" + response.exception
                )
                listResult.postValue(null)
            }
        })
    }

    interface HomeRecCallback {
        fun done(videos: MutableList<Movie.Video>?)
    }

    fun getHomeRecList(sourceBean: SourceBean, ids: ArrayList<String?>?, callback: HomeRecCallback) {
        val type = sourceBean.type
        if (type == 3) {
            val waitResponse = Runnable {
                val sortJson = BoundedCall.call(Callable<String> {
                    val sp = ApiConfig.get().getCSP(sourceBean)
                    val json = sp.homeVideoContent()
                    json
                }, sourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getHomeRecList--" + sourceBean.key)
                if (sortJson != null) {
                    val absXml = resultParser.json(null, sortJson, sourceBean.key)
                    val videoList = absXml?.movie?.videoList
                    if (videoList != null) {
                        callback.done(videoList)
                    } else {
                        callback.done(null)
                    }
                } else {
                    callback.done(null)
                }
            }
            SourceHelper.SPIDER_POOL.execute(waitResponse)
        } else if (type == 0 || type == 1) {
            SourceHelper.siteGet(sourceBean)
                .tag("detail")
                .params("ac", if (sourceBean.type == 0) "videolist" else "detail")
                .params("ids", TextUtils.join(",", ids!!))
                .execute(object : AbsCallback<String>() {

                    override fun convertResponse(response: okhttp3.Response): String {
                        val body = response.body
                        return if (body != null) body.string() else throw IllegalStateException(SourceHelper.ERR_NETWORK)
                    }

                    override fun onSuccess(response: Response<String>) {
                        val absXml: AbsXml?
                        if (sourceBean.type == 0) {
                            val xml = response.body()
                            absXml = resultParser.xml(null, xml, sourceBean.key)
                        } else {
                            val json = response.body()
                            absXml = resultParser.json(null, json, sourceBean.key)
                        }
                        val videoList = absXml?.movie?.videoList
                        if (videoList != null) {
                            callback.done(videoList)
                        } else {
                            callback.done(null)
                        }
                    }

                    override fun onError(response: Response<String>) {
                        super.onError(response)
                        callback.done(null)
                    }
                })
        } else {
            callback.done(null)
        }
    }
}
