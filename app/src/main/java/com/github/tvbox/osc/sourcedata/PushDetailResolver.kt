package com.github.tvbox.osc.sourcedata

import android.text.TextUtils
import android.util.Base64

import androidx.lifecycle.MutableLiveData

import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.AbsJson
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.RegexUtils
import com.github.tvbox.osc.util.thunder.Thunder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response

import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.ArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 详情通道的后处理:push:// 线路当场解析成真实线路、磁力链接交给迅雷解析。
 *
 * 两者都会改写详情数据并把结果回投 detailResult,所以由详情/解析通道共用同一个实例。
 */
class PushDetailResolver(private val gson: Gson, private val detailResult: MutableLiveData<AbsXml>) {

    fun checkPush(data: AbsXml): AbsXml {
        val videoList = data.movie?.videoList
        if (videoList != null && videoList.size > 0) {
            val video = videoList[0]
            val infoList = video.urlBean?.infoList
            if (infoList != null && infoList.size > 0) {
                for (i in infoList.indices) {
                    val urlinfo = infoList[i]
                    val beanList = urlinfo.beanList
                    if (beanList != null && beanList.isNotEmpty()) {
                        for (infoBean in beanList) {
                            val beanUrl = infoBean.url!!
                            if (beanUrl.startsWith("push://")) {
                                var pushUrl = beanUrl.substring(7)
                                if (pushUrl.startsWith("b64:")) {
                                    try {
                                        pushUrl = String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                                    } catch (e: UnsupportedEncodingException) {
                                        LOG.e("SourceViewModel", e)
                                    }
                                } else {
                                    pushUrl = URLDecoder.decode(pushUrl)
                                }

                                val resData = arrayOfNulls<AbsXml>(1)

                                val countDownLatch = CountDownLatch(1)
                                val threadPool = Executors.newSingleThreadExecutor()
                                val finalPushUrl = pushUrl
                                threadPool.execute(Runnable {
                                    val sb = ApiConfig.get().getSource("push_agent")
                                    if (sb == null) {
                                        countDownLatch.countDown()
                                        return@Runnable
                                    }
                                    if (sb.type == 4) {
                                        SourceHelper.siteGet(sb)
                                            .tag("detail")
                                            .params("ac", "detail")
                                            .params("ids", finalPushUrl)
                                            .execute(object : AbsCallback<String>() {
                                                override fun convertResponse(response: okhttp3.Response): String {
                                                    val body = response.body
                                                    return if (body != null) body.string() else ""
                                                }

                                                override fun onSuccess(response: Response<String>) {
                                                    val res = response.body()
                                                    if (!TextUtils.isEmpty(res)) {
                                                        try {
                                                            val absJson = gson.fromJson<AbsJson>(res, object : TypeToken<AbsJson>() {}.type)
                                                            resData[0] = absJson.toAbsXml()
                                                            SourceHelper.absXml(resData[0]!!, sb.key)
                                                        } catch (e: Exception) {
                                                            LOG.e("SourceViewModel", e)
                                                        }
                                                    }
                                                    countDownLatch.countDown()
                                                }

                                                override fun onError(response: Response<String>) {
                                                    super.onError(response)
                                                    countDownLatch.countDown()
                                                }
                                            })
                                    } else {
                                        try {
                                            val sp = ApiConfig.get().getCSP(sb)
                                            val ids = ArrayList<String>()
                                            ids.add(finalPushUrl)
                                            val res = sp.detailContent(ids)
                                            if (!TextUtils.isEmpty(res)) {
                                                try {
                                                    val absJson = gson.fromJson<AbsJson>(res, object : TypeToken<AbsJson>() {}.type)
                                                    resData[0] = absJson.toAbsXml()
                                                    SourceHelper.absXml(resData[0]!!, sb.key)
                                                } catch (e: Exception) {
                                                    LOG.e("SourceViewModel", e)
                                                }
                                            }
                                        } catch (th: Throwable) {
                                            LOG.e("SourceViewModel", th)
                                        }
                                        countDownLatch.countDown()
                                    }
                                })
                                try {
                                    countDownLatch.await(15, TimeUnit.SECONDS)
                                } catch (e: InterruptedException) {
                                    LOG.e("SourceViewModel", e)
                                } finally {
                                    threadPool.shutdown()
                                }
                                val res = resData[0]
                                if (res != null) {
                                    val resVideoList = res.movie?.videoList
                                    if (resVideoList != null && resVideoList.size > 0) {
                                        val resVideo = resVideoList[0]
                                        val resInfoList = resVideo.urlBean?.infoList
                                        if (resInfoList != null && resInfoList.size > 0) {
                                            if (beanList.size == 1) {
                                                infoList.removeAt(i)
                                            } else {
                                                beanList.remove(infoBean)
                                            }
                                            for (resUrlinfo in resInfoList) {
                                                val resBeanList = resUrlinfo.beanList
                                                if (resBeanList != null && resBeanList.isNotEmpty()) {
                                                    infoList.add(resUrlinfo)
                                                }
                                            }
                                            video.sourceKey = "push_agent"
                                            return data
                                        }
                                    }
                                }
                                infoBean.name = str(R.string.player_parse_failed_prefix, infoBean.name)
                            }
                        }
                    }
                }
            }
        }
        return data
    }

    fun checkThunder(data: AbsXml, index: Int) {
        var thunderParse = false
        val videoList = data.movie?.videoList
        if (videoList != null && videoList.size == 1) {
            val video = videoList[0]
            val infoList = video.urlBean?.infoList
            if (infoList != null) {
                var hasThunder = false
                thunderLoop@ for (urlInfo in infoList) {
                    val beanList = urlInfo.beanList ?: continue
                    for (infoBean in beanList) {
                        if (infoBean.url != null && Thunder.isSupportUrl(infoBean.url)) {
                            hasThunder = true
                            break@thunderLoop
                        }
                    }
                }
                if (hasThunder) {
                    thunderParse = true
                    Thunder.parse(App.getInstance(), video.urlBean, object : Thunder.ThunderCallback {
                        override fun status(code: Int, info: String) {
                            if (code >= 0) {
                                LOG.i(info)
                            } else {
                                // 这个回调在线程池线程上跑,越界/空指针会直接崩进程:源结构不完整时只放弃改首集名
                                val first = if (infoList.isEmpty()) null else infoList[0]
                                val firstBeanList = first?.beanList
                                if (firstBeanList != null && firstBeanList.isNotEmpty()) {
                                    firstBeanList[0].name = info
                                }
                                detailResult.postValue(data)
                            }
                        }

                        override fun list(urlMap: MutableMap<Int, String>) {
                            for (key in urlMap.keys) {
                                if (key < 0 || key >= infoList.size) continue
                                val urlInfo = infoList[key]
                                val playList = urlMap[key]!!
                                urlInfo.urls = playList
                                // Java 的 split("#") 走 Pattern.split:尾部空串被丢掉;Kotlin 的 split(Regex) 会保留
                                val str = RegexUtils.getPattern("#").split(playList)
                                val infoBeanList = ArrayList<Movie.Video.UrlBean.UrlInfo.InfoBean>()
                                for (s in str) {
                                    if (s.contains("$")) {
                                        val ss = s.split(Regex("\\$"), 2)

                                        if (ss.isNotEmpty()) {
                                            if (ss.size >= 2) {
                                                infoBeanList.add(Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]))
                                            } else {
                                                infoBeanList.add(Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size + 1).toString(), ss[0]))
                                            }
                                        }
                                    }
                                }
                                urlInfo.beanList = infoBeanList
                            }
                            detailResult.postValue(data)
                        }

                        override fun play(url: String) {
                        }
                    })
                }
            }
        }
        if (!thunderParse && index == 0) {
            detailResult.postValue(data)
        }
    }

    companion object {

        /**
         * 资源文案;走 LanguageManager(Application 的 base 切语言不会重挂,直接 app.getString 会停旧语言);
         * App 未就绪(单测/极早调用)返回空串,不抛异常。
         */
        private fun str(resId: Int, vararg args: Any?): String {
            val app = App.getInstance()
            return if (app == null) "" else LanguageManager.localized(app).getString(resId, *args)
        }
    }
}
