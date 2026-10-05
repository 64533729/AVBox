package com.github.tvbox.osc.sourcedata

import android.text.TextUtils
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

import com.github.tvbox.osc.bean.Subtitle
import com.github.tvbox.osc.bean.SubtitleData
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.SubtitleFilePicker
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback

import org.jsoup.Jsoup

import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class SubtitleViewModel : ViewModel() {

    /** 字幕直链回调(供 Compose 版 SubtitleSearchSheet 调用) */
    fun interface SubtitleLoader {
        fun loadSubtitle(subtitle: Subtitle)
    }

    /** 发布页文件列表回调(记忆还原路径用;error = 网络/解析失败) */
    private fun interface FilesCallback {
        fun onFiles(files: List<Subtitle>?, error: Boolean)
    }

    val searchResult: MutableLiveData<SubtitleData> = MutableLiveData()

    fun searchResult(title: String?, page: Int) {
        searchResultFromAssrt(title, page)
    }

    fun getSearchResultSubtitleUrls(subtitle: Subtitle) {
        getSearchResultSubtitleUrlsFromAssrt(subtitle)
    }

    fun getSubtitleUrl(subtitle: Subtitle, subtitleLoader: SubtitleLoader) {
        getSubtitleUrlFromAssrt(subtitle, subtitleLoader, null)
    }

    /** 记忆还原路径:挑出"本集"文件并解析直链;不走 searchResult,否则会覆盖用户正在浏览的面板列表。 */
    fun pickEpisodeSubtitle(releaseUrl: String?, episodeName: String?, fileNameHint: String?,
                            onPicked: SubtitleLoader?, onFailed: Runnable?) {
        if (TextUtils.isEmpty(releaseUrl) || onPicked == null) {
            if (onFailed != null) onFailed.run()
            return
        }
        val release = Subtitle()
        release.url = releaseUrl
        getSearchResultSubtitleUrlsFromAssrt(release, FilesCallback { files, error ->
            if (error || files == null || files.isEmpty()) {
                if (onFailed != null) onFailed.run()
                return@FilesCallback
            }
            val names = ArrayList<String>()
            for (item in files) names.add(item.name ?: "")
            val index = SubtitleFilePicker.pick(names, episodeName, fileNameHint)
            if (index < 0) {
                if (onFailed != null) onFailed.run()
                return@FilesCallback
            }
            getSubtitleUrlFromAssrt(files[index], onPicked, onFailed)
        })
    }

    private fun setSearchListData(data: List<Subtitle>?, isNew: Boolean, isZip: Boolean) {
        try {
            val subtitleData = SubtitleData()
            subtitleData.subtitleList = data
            subtitleData.isNew = isNew
            subtitleData.isZip = isZip
            searchResult.postValue(subtitleData)
        } catch (e: Throwable) {
            LOG.e("SubtitleViewModel", e)
            searchResult.postValue(null)
        }
    }

    private var pagesTotal = -1

    private fun searchResultFromAssrt(title: String?, page: Int) {
        try {
            if (pagesTotal > 0 && page > pagesTotal) {
                setSearchListData(ArrayList(), page <= 1, true)
                return
            }
            if (page == 1) pagesTotal = -1 //第一页时 重置页大小
            val searchApiUrl = "https://secure.assrt.net/sub/"
            OkGo.get<String>(searchApiUrl)
                .params("searchword", title)
                .params("sort", "rank")
                .params("page", page)
                .params("no_redir", "1")
                .execute(object : AbsCallback<String>() {
                    override fun onSuccess(response: com.lzy.okgo.model.Response<String>) {
                        try {
                            val content = response.body()
                            val doc = Jsoup.parse(content)
                            val items = doc.select(".resultcard .sublist_box_title a.introtitle")
                            val data = ArrayList<Subtitle>()
                            for (item in items) {
                                val subtitleTitle = item.attr("title")
                                val href = item.attr("href")
                                if (TextUtils.isEmpty(href) || !containsSearchWord(subtitleTitle, title)) continue
                                val one = Subtitle()
                                one.name = subtitleTitle
                                one.url = "https://assrt.net" + href
                                one.isZip = true
                                data.add(one)
                            }
                            setSearchListData(data, page <= 1, true)
                            val pages = doc.select(".pagelinkcard a")
                            if (pages.size > 0) {
                                val ps = pages.last()!!.text().split("/", limit = 2)
                                if (ps.size == 2 && !TextUtils.isEmpty(ps[1])) {
                                    pagesTotal = ps[1].trim { it <= ' ' }.toInt()
                                }
                            }
                        } catch (th: Throwable) {
                            LOG.e("SubtitleViewModel", th)
                        }
                    }

                    override fun convertResponse(response: Response): String {
                        return response.body.string()
                    }

                    override fun onError(response: com.lzy.okgo.model.Response<String>) {
                        super.onError(response)
                        setSearchListData(null, page <= 1, true)
                    }
                })
        } catch (e: Exception) {
            LOG.e("SubtitleViewModel", e)
        }
    }

    private val regexShooterFileOnclick = Pattern.compile("onthefly\\(\"(\\d+)\",\"(\\d+)\",\"([\\s\\S]*)\"\\)")

    private fun getSearchResultSubtitleUrlsFromAssrt(subtitle: Subtitle) {
        getSearchResultSubtitleUrlsFromAssrt(subtitle, FilesCallback { files, error ->
            setSearchListData(files, true, error)
        })
    }

    private fun getSearchResultSubtitleUrlsFromAssrt(subtitle: Subtitle, callback: FilesCallback) {
        try {
            val url = subtitle.url
            OkGo.get<String>(url!!).execute(object : AbsCallback<String>() {
                override fun onSuccess(response: com.lzy.okgo.model.Response<String>) {
                    try {
                        val content = response.body()
                        val data = ArrayList<Subtitle>()
                        val doc = Jsoup.parse(content)
                        val items = doc.select("#detail-filelist .waves-effect")
                        if (items.size > 0) { //压缩包里面的字幕
                            for (item in items) {
                                val onclick = item.attr("onclick")
                                if (TextUtils.isEmpty(onclick)) continue
                                val matcher = regexShooterFileOnclick.matcher(onclick)
                                if (matcher.find()) {
                                    val fileName = matcher.group(3)
                                    if (!isSupportedSubtitleFile(fileName)) continue
                                    val downloadUrl = String.format("https://secure.assrt.net/download/%s/-/%s/%s", matcher.group(1), matcher.group(2), matcher.group(3))
                                    val one = Subtitle()
                                    val name = item.selectFirst("#filelist-name")
                                    one.name = if (name == null) fileName else name.text()
                                    one.url = downloadUrl
                                    one.isZip = false
                                    data.add(one)
                                }
                            }
                            callback.onFiles(data, false)
                        } else { //有的字幕 不一定是压缩包
                            val item = doc.selectFirst(".download a#btn_download")
                            if (item == null) {
                                callback.onFiles(null, false)
                                return
                            }
                            val href = item.attr("href")
                            if (TextUtils.isEmpty(href)) {
                                callback.onFiles(null, false)
                                return
                            }
                            if (isSupportedSubtitleFile(href)) {
                                val downloadUrl = "https://assrt.net" + href
                                val one = Subtitle()
                                val title = href.substring(href.lastIndexOf("/") + 1)
                                one.name = URLDecoder.decode(title)
                                one.url = downloadUrl
                                one.isZip = false
                                data.add(one)
                                callback.onFiles(data, false)
                            } else {
                                callback.onFiles(null, false)
                            }
                        }
                    } catch (th: Throwable) {
                        LOG.e("SubtitleViewModel", th)
                        callback.onFiles(null, true)
                    }
                }

                override fun convertResponse(response: Response): String {
                    return response.body.string()
                }

                override fun onError(response: com.lzy.okgo.model.Response<String>) {
                    super.onError(response)
                    callback.onFiles(null, true)
                }
            })
        } catch (e: Exception) {
            LOG.e("SubtitleViewModel", e)
            callback.onFiles(null, true)
        }
    }

    private fun containsSearchWord(subtitleTitle: String?, searchWord: String?): Boolean {
        if (TextUtils.isEmpty(subtitleTitle) || TextUtils.isEmpty(searchWord)) return false
        return subtitleTitle!!.lowercase(Locale.ROOT).contains(searchWord!!.lowercase(Locale.ROOT))
    }

    private fun isSupportedSubtitleFile(fileName: String?): Boolean {
        if (TextUtils.isEmpty(fileName)) return false
        val lower = fileName!!.lowercase(Locale.ROOT)
        return lower.endsWith(".srt") ||
            lower.endsWith(".ass") ||
            lower.endsWith(".stl") ||
            lower.endsWith(".ttml")
    }

    /** 解析字幕直链(assrt 下载链是 302,直链在 Location 头);onFailed 只有记忆还原路径传,用于回落。 */
    private fun getSubtitleUrlFromAssrt(subtitle: Subtitle, subtitleLoader: SubtitleLoader, onFailed: Runnable?) {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36"
        val request = Request.Builder()
            .url(subtitle.url!!)
            .get()
            .addHeader("Referer", "https://secure.assrt.net")
            .addHeader("User-Agent", ua)
            .build()
        val base = OkGoHelper.getDefaultClient()
        val builder = if (base != null) base.newBuilder() else OkHttpClient.Builder().proxySelector(OkGoHelper.proxySelector()).proxyAuthenticator(OkGoHelper.proxyAuthenticator())
        builder.readTimeout(15, TimeUnit.SECONDS)
        builder.writeTimeout(15, TimeUnit.SECONDS)
        builder.connectTimeout(15, TimeUnit.SECONDS)
        builder.followRedirects(false)
        builder.followSslRedirects(false)
        builder.retryOnConnectionFailure(true)
        val client = builder.build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                LOG.e("SubtitleViewModel", e)
                if (onFailed != null) onFailed.run()
            }

            override fun onResponse(call: Call, response: Response) {
                val location = response.header("location")
                if (TextUtils.isEmpty(location)) {
                    if (onFailed != null) onFailed.run()
                    return
                }
                subtitle.url = location
                subtitleLoader.loadSubtitle(subtitle)
            }
        })
    }
}
