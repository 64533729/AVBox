package com.github.tvbox.osc.api

import android.os.Handler
import android.os.Looper
import android.text.TextUtils

import androidx.collection.ArrayMap

import com.github.catvod.crawler.js.Trans
import com.github.catvod.net.OkHttp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.DanmuSearchResult
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.DanmuHelper
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.RegexUtils

import org.json.JSONArray
import org.json.JSONObject

import java.io.IOException
import java.net.URLEncoder
import java.util.ArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

class DanmakuApi {

    interface SearchCallback {
        fun onFound(url: String)

        fun onNotFound() {
        }
    }

    interface SearchListCallback {
        fun onSuccess(results: List<DanmuSearchResult>?)

        fun onError(message: String?)
    }

    interface SearchResultCallback {
        fun onSuccess(danmu: String?)

        fun onError(message: String?)
    }

    private class EpisodeList(@JvmField val episodes: JSONArray?, @JvmField val isMovie: Boolean)

    private class EpisodeMatch(@JvmField val id: String, @JvmField val title: String, @JvmField val number: Int)

    companion object {

        /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
        private fun str(resId: Int, vararg args: Any?): String {
            val app = App.getInstance()
            return if (app == null) "" else LanguageManager.localized(app).getString(resId, *args)
        }

        private val TAG = DanmakuApi::class.java.simpleName
//    private static final String BUILTIN_API = "https://saas-oa.shyeguang.cn";
        private const val BUILTIN_API = "https://logvardanmu.konfan.cn/87654321"
        private const val USE_DEFAULT_KEY = "danmu_api_use_default"
        private val BUILTIN_TIMEOUT = TimeUnit.SECONDS.toMillis(20)
        private const val BUILTIN_MAX_RETRY = 2
        private const val EPISODE_QUERY_NUMBER = 0
        private const val EPISODE_QUERY_EMPTY = 1
        private val handler = Handler(Looper.getMainLooper())
        private val searchSeq = AtomicInteger()

        /** 站点可标 `danmaku:0` 关掉本站的自动搜弹幕;手动搜索不受此限 */
        @JvmStatic
        fun canSearch(sourceBean: SourceBean?): Boolean {
            if (sourceBean != null && !sourceBean.isDanmakuEnabled()) return false
            return DanmuHelper.isOpen() && !TextUtils.isEmpty(getApiUrl())
        }

        @JvmStatic
        fun isUseDefault(): Boolean {
            return KV.get(USE_DEFAULT_KEY, false)
        }

        @JvmStatic
        fun setUseDefault(useDefault: Boolean) {
            KV.put(USE_DEFAULT_KEY, useDefault)
            if (useDefault) KV.put(HawkConfig.DANMU_API, "")
        }

        @JvmStatic
        fun setCustomApi(api: String?) {
            KV.put(USE_DEFAULT_KEY, false)
            KV.put(HawkConfig.DANMU_API, api)
        }

        @JvmStatic
        fun search(name: String?, episode: String?, callback: SearchCallback?) {
            val apiUrl = getApiUrl()
//        LOG.i("echo-danmaku search apiUrl: " + apiUrl);
            if (TextUtils.isEmpty(apiUrl) || callback == null) return
            try {
                OkHttp.cancel(TAG)
                val seq = searchSeq.incrementAndGet()
//            LOG.i("echo-danmaku search title: " + safeLog(name) + ", episode: " + safeLog(episode));
                if (!hasPlaceholder(apiUrl) && !isDanmakuSearchApi(apiUrl)) {
                    searchBuiltin(apiUrl, name, episode, callback, 0, seq)
                    return
                }
                newCall(apiUrl, name, episode).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!isCurrentSearch(seq)) return
                        LOG.e("echo-danmaku search error: " + e.message)
                        notifyNotFound(callback, seq)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!isCurrentSearch(seq)) return
                        try {
                            val body = if (response.body == null) "" else response.body.string()
                            val url = parseUrl(body)
                            if (!TextUtils.isEmpty(url) && isCurrentSearch(seq)) {
                                handler.post(Runnable {
                                    if (isCurrentSearch(seq)) callback.onFound(url)
                                })
                            } else {
                                notifyNotFound(callback, seq)
                            }
                        } catch (th: Throwable) {
                            LOG.e("echo-danmaku search parse error: " + th.message)
                            notifyNotFound(callback, seq)
                        }
                    }
                })
            } catch (th: Throwable) {
                LOG.e("echo-danmaku search start error: " + th.message)
                notifyNotFound(callback, searchSeq.get())
            }
        }

        @JvmStatic
        fun searchList(name: String?, episode: String?, callback: SearchListCallback?) {
            if (callback == null) return
            OkHttp.cancel(TAG)
            val seq = searchSeq.incrementAndGet()
            val apiUrl = getApiUrl()
            if (TextUtils.isEmpty(apiUrl)) {
                notifySearchListError(callback, seq, str(R.string.danmu_search_api_empty))
                return
            }
            if (!hasPlaceholder(apiUrl) && !isDanmakuSearchApi(apiUrl)) {
                searchBuiltinList(apiUrl, name, callback, seq)
                return
            }
            try {
                newCall(apiUrl, name, episode).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        notifySearchListError(callback, seq, getErrorMessage(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            if (!response.isSuccessful) throw IOException("HTTP " + response.code)
                            val body = if (response.body == null) "" else response.body.string()
                            notifySearchListSuccess(callback, seq, parseSearchList(body))
                        } catch (th: Throwable) {
                            notifySearchListError(callback, seq, getErrorMessage(th))
                        } finally {
                            response.close()
                        }
                    }
                })
            } catch (th: Throwable) {
                notifySearchListError(callback, seq, getErrorMessage(th))
            }
        }

        @JvmStatic
        fun loadSearchResult(result: DanmuSearchResult?, callback: SearchResultCallback?) {
            if (callback == null) return
            OkHttp.cancel(TAG)
            val seq = searchSeq.incrementAndGet()
            if (result == null || TextUtils.isEmpty(result.url)) {
                notifySearchResultError(callback, seq, str(R.string.danmu_url_empty))
                return
            }
            if (!result.isBuiltIn) {
                notifySearchResultSuccess(callback, seq, result.url)
                return
            }
            OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), result.url, TAG).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    notifySearchResultError(callback, seq, getErrorMessage(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) throw IOException("HTTP " + response.code)
                        val body = if (response.body == null) "" else response.body.string()
                        val danmu = commentJsonToXml(body)
                        if (TextUtils.isEmpty(danmu)) throw IOException(str(R.string.danmu_content_empty))
                        notifySearchResultSuccess(callback, seq, danmu)
                    } catch (th: Throwable) {
                        notifySearchResultError(callback, seq, getErrorMessage(th))
                    } finally {
                        response.close()
                    }
                }
            })
        }

        @JvmStatic
        fun cancel() {
            searchSeq.incrementAndGet()
            OkHttp.cancel(TAG)
        }

        private fun searchBuiltinList(apiUrl: String, name: String?, callback: SearchListCallback?, seq: Int) {
            val baseUrl = normalizeBaseUrl(apiUrl)
            val searchUrl = baseUrl + "/api/v2/search/episodes?anime=" + encode(Trans.t2s(name ?: ""))
            OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), searchUrl, TAG).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    notifySearchListError(callback, seq, getErrorMessage(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) throw IOException("HTTP " + response.code)
                        val body = if (response.body == null) "" else response.body.string()
                        notifySearchListSuccess(callback, seq, parseBuiltinSearchList(body, baseUrl))
                    } catch (th: Throwable) {
                        notifySearchListError(callback, seq, getErrorMessage(th))
                    } finally {
                        response.close()
                    }
                }
            })
        }

        private fun searchBuiltin(apiUrl: String, name: String?, episode: String?, callback: SearchCallback?, retry: Int, seq: Int) {
            searchBuiltin(apiUrl, name, episode, callback, retry, seq, EPISODE_QUERY_NUMBER)
        }

        private fun searchBuiltin(apiUrl: String, name: String?, episode: String?, callback: SearchCallback?, retry: Int, seq: Int, queryMode: Int) {
            val baseUrl = normalizeBaseUrl(apiUrl)
            val simpleName = Trans.t2s(name ?: "")
            val simpleEpisode = Trans.t2s(episode ?: "")
            val episodeQuery = getEpisodeQuery(simpleEpisode, queryMode)
            val searchUrl = baseUrl + "/api/v2/search/episodes?anime=" + encode(simpleName) +
                    (if (TextUtils.isEmpty(episodeQuery)) "" else "&episode=" + encode(episodeQuery))
//        LOG.i("echo-danmaku builtin search episodes: " + searchUrl + ", retry=" + retry + ", mode=" + queryMode);
            OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), searchUrl, TAG).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!isCurrentSearch(seq)) return
                    if (retry < BUILTIN_MAX_RETRY) {
                        LOG.e("echo-danmaku builtin search error: " + e.message + ", retry later")
                        handler.postDelayed(Runnable {
                            if (isCurrentSearch(seq)) searchBuiltin(apiUrl, name, episode, callback, retry + 1, seq, queryMode)
                        }, 1500L * (retry + 1))
                    } else {
                        LOG.e("echo-danmaku builtin search error: " + e.message)
                        searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, callback, seq, true)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!isCurrentSearch(seq)) return
                    try {
                        val body = if (response.body == null) "" else response.body.string()
                        val episodeMatch = findEpisodeFromSearchEpisodes(body, simpleEpisode)
                        if (episodeMatch != null && !TextUtils.isEmpty(episodeMatch.id)) {
                            loadBuiltinComment(baseUrl, simpleName, simpleEpisode, episodeMatch, callback, seq)
                            return
                        }
                        if (isSearchEpisodesMovieResult(body)) {
                            LOG.i("echo-danmaku builtin movie result not matched, skip anime fallback")
                            notifyNotFound(callback, seq)
                            return
                        }
                        if (tryNextEpisodeQuery(apiUrl, name, episode, callback, seq, queryMode)) return
                        LOG.i("echo-danmaku builtin episode not matched, title: " + safeLog(simpleName) + ", episode: " + safeLog(simpleEpisode))
                        searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, callback, seq, true)
                    } catch (th: Throwable) {
                        LOG.e("echo-danmaku builtin episode parse error: " + th.message)
                        if (tryNextEpisodeQuery(apiUrl, name, episode, callback, seq, queryMode)) return
                        searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, callback, seq, true)
                    }
                }
            })
        }

        private fun tryNextEpisodeQuery(apiUrl: String, name: String?, episode: String?, callback: SearchCallback?, seq: Int, queryMode: Int): Boolean {
            val nextMode = getNextEpisodeQueryMode(Trans.t2s(episode ?: ""), queryMode)
            if (nextMode < 0) return false
//        LOG.i("echo-danmaku builtin retry episodes query mode: " + queryMode + " -> " + nextMode);
            searchBuiltin(apiUrl, name, episode, callback, 0, seq, nextMode)
            return true
        }

        private fun searchBuiltinAnime(baseUrl: String, name: String, episode: String, callback: SearchCallback?, seq: Int, notifyOnEmpty: Boolean) {
            val searchUrl = baseUrl + "/api/v2/search/anime?keyword=" + encode(name)
            LOG.i("echo-danmaku builtin search anime: " + searchUrl)
            OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), searchUrl, TAG).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!isCurrentSearch(seq)) return
                    LOG.e("echo-danmaku builtin anime error: " + e.message)
                    if (notifyOnEmpty) notifyNotFound(callback, seq)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!isCurrentSearch(seq)) return
                    try {
                        val body = if (response.body == null) "" else response.body.string()
                        val animeId = findAnimeId(body)
                        if (TextUtils.isEmpty(animeId)) {
                            if (notifyOnEmpty) notifyNotFound(callback, seq)
                            return
                        }
                        loadBuiltinBangumi(baseUrl, animeId, episode, callback, seq)
                    } catch (th: Throwable) {
                        LOG.e("echo-danmaku builtin anime parse error: " + th.message)
                        if (notifyOnEmpty) notifyNotFound(callback, seq)
                    }
                }
            })
        }

        private fun loadBuiltinBangumi(baseUrl: String, animeId: String, episode: String, callback: SearchCallback?, seq: Int) {
            val bangumiUrl = baseUrl + "/api/v2/bangumi/" + animeId
//        LOG.i("echo-danmaku builtin bangumi: " + bangumiUrl);
            OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), bangumiUrl, TAG).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!isCurrentSearch(seq)) return
                    LOG.e("echo-danmaku builtin bangumi error: " + e.message)
                    notifyNotFound(callback, seq)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!isCurrentSearch(seq)) return
                    try {
                        val body = if (response.body == null) "" else response.body.string()
                        val episodeMatch = findEpisode(body, episode)
                        if (episodeMatch != null && !TextUtils.isEmpty(episodeMatch.id)) {
                            loadBuiltinComment(baseUrl, "", episode, episodeMatch, callback, seq)
                        } else {
//                        LOG.i("echo-danmaku builtin bangumi episode not matched, episode: " + safeLog(episode));
                            notifyNotFound(callback, seq)
                        }
                    } catch (th: Throwable) {
                        LOG.e("echo-danmaku builtin bangumi parse error: " + th.message)
                        notifyNotFound(callback, seq)
                    }
                }
            })
        }

        private fun loadBuiltinComment(baseUrl: String, title: String, episode: String, episodeMatch: EpisodeMatch, callback: SearchCallback?, seq: Int) {
            val commentUrl = baseUrl + "/api/v2/comment/" + episodeMatch.id + "?format=json"
            LOG.i("echo-danmaku builtin load title: " + safeLog(title) +
                    ", request episode: " + safeLog(episode) +
                    ", matched episode: " + safeLog(episodeMatch.title) +
                    ", matched number: " + episodeMatch.number +
                    ", episodeId: " + episodeMatch.id)
            LOG.i("echo-danmaku builtin comment: " + commentUrl)
            OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), commentUrl, TAG).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!isCurrentSearch(seq)) return
                    LOG.e("echo-danmaku builtin comment error: " + e.message)
                    notifyNotFound(callback, seq)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!isCurrentSearch(seq)) return
                    try {
                        val body = if (response.body == null) "" else response.body.string()
                        val xml = commentJsonToXml(body)
                        if (TextUtils.isEmpty(xml) || !isCurrentSearch(seq)) {
                            notifyNotFound(callback, seq)
                            return
                        }
                        handler.post(Runnable {
                            if (isCurrentSearch(seq)) callback!!.onFound(xml)
                        })
                    } catch (th: Throwable) {
                        LOG.e("echo-danmaku builtin comment parse error: " + th.message)
                        notifyNotFound(callback, seq)
                    }
                }
            })
        }

        private fun notifyNotFound(callback: SearchCallback?, seq: Int) {
            if (callback == null || !isCurrentSearch(seq)) return
            handler.post(Runnable {
                if (isCurrentSearch(seq)) callback.onNotFound()
            })
        }

        private fun isCurrentSearch(seq: Int): Boolean {
            return seq == searchSeq.get()
        }

        private fun newCall(apiUrl: String, name: String?, episode: String?): Call {
            var name = Trans.t2s(name ?: "")
            var episode = Trans.t2s(episode ?: "")
            if (hasPlaceholder(apiUrl)) {
                return OkHttp.newCall(fillPlaceholders(apiUrl, name, episode), TAG)
            }
            val params = ArrayMap<String, String>()
            params["name"] = name
            params["episode"] = episode
            return OkHttp.newCall(apiUrl, OkHttp.toBody(params), TAG)
        }

        /** 占位符替换前必须编码:剧名里的 & 会被当成额外参数、# 之后整段变 fragment */
        @JvmStatic
        fun fillPlaceholders(apiUrl: String, name: String, episode: String): String {
            return apiUrl.replace("{name}", encodePlaceholder(name)).replace("{episode}", encodePlaceholder(episode))
        }

        /** 占位符可能落在 path 上:空格编成 %20(+ 在 path 里是字面量;在 query 里两种写法都表示空格) */
        private fun encodePlaceholder(text: String): String {
            return encode(text).replace("+", "%20")
        }

        private fun getApiUrl(): String {
            if (isUseDefault()) return BUILTIN_API
            val custom = KV.get(HawkConfig.DANMU_API, "")
            if (!TextUtils.isEmpty(custom)) return custom.trim { it <= ' ' }
            val config = ApiConfig.get().getDanmaku().trim { it <= ' ' }
            if (!TextUtils.isEmpty(config)) return config
            return BUILTIN_API
        }

        private fun hasPlaceholder(apiUrl: String?): Boolean {
            return !TextUtils.isEmpty(apiUrl) && (apiUrl!!.contains("{name}") || apiUrl.contains("{episode}"))
        }

        private fun isDanmakuSearchApi(apiUrl: String?): Boolean {
            var url = apiUrl?.trim { it <= ' ' } ?: ""
            while (url.endsWith("/")) url = url.substring(0, url.length - 1)
            return url.endsWith("/danmaku")
        }

        private fun normalizeBaseUrl(apiUrl: String?): String {
            var url = apiUrl?.trim { it <= ' ' } ?: ""
            if (url.endsWith("/")) url = url.substring(0, url.length - 1)
            if (url.endsWith("/87654321")) url = url.substring(0, url.length - "/87654321".length)
            return url
        }

        private fun encode(text: String?): String {
            try {
                return URLEncoder.encode(text ?: "", "UTF-8")
            } catch (th: Throwable) {
                return text ?: ""
            }
        }

        private fun getEpisodeQuery(episode: String, queryMode: Int): String {
            if (queryMode == EPISODE_QUERY_EMPTY) return ""
            val number = extractNumber(episode)
            return if (number > 0) number.toString() else ""
        }

        private fun getNextEpisodeQueryMode(episode: String, queryMode: Int): Int {
            if (queryMode == EPISODE_QUERY_NUMBER && !TextUtils.isEmpty(getEpisodeQuery(episode, EPISODE_QUERY_NUMBER))) {
                return EPISODE_QUERY_EMPTY
            }
            return -1
        }

        private fun findAnimeId(body: String): String {
            val obj = JSONObject(body)
            var array = obj.optJSONArray("animes")
            if (array == null) array = obj.optJSONArray("anime")
            if (array == null) array = obj.optJSONArray("data")
            if (array == null || array.length() <= 0) return ""
            val item = array.optJSONObject(0) ?: return ""
            var id = item.optString("animeId", "")
            if (TextUtils.isEmpty(id)) id = item.optString("id", "")
            return id
        }

        private fun findEpisode(body: String, episode: String?): EpisodeMatch? {
            return findEpisode(body, episode, true)
        }

        private fun findEpisode(body: String, episode: String?, allowMovieFallback: Boolean): EpisodeMatch? {
            val obj = JSONObject(body)
            val episodeList = findEpisodeList(obj) ?: return null
            val episodes = episodeList.episodes
            if (episodes == null || episodes.length() <= 0) return null
            val targetNumber = extractNumber(episode)
            var first: EpisodeMatch? = null
            var firstMandarin: EpisodeMatch? = null
            for (i in 0 until episodes.length()) {
                val item = episodes.optJSONObject(i) ?: continue
                val id = firstString(item, "episodeId", "id")
                if (TextUtils.isEmpty(id)) continue
                val title = firstString(item, "episodeTitle", "title", "name")
                val number = parseEpisodeNumber(firstString(item, "episodeNumber", "number", "sort"))
                val match = EpisodeMatch(id, title, number)
                if (first == null) first = match
                if (firstMandarin == null && isMandarinTitle(title)) firstMandarin = match
                if (!TextUtils.isEmpty(episode) && !TextUtils.isEmpty(title) && title.contains(episode!!)) {
                    return match
                }
                if (targetNumber > 0 && number == targetNumber) return match
                if (targetNumber > 0 && extractNumber(title) == targetNumber) return match
            }
            if (TextUtils.isEmpty(episode)) return first
            if (allowMovieFallback && episodeList.isMovie) return firstMandarin ?: first
            return null
        }

        private fun findEpisodeFromSearchEpisodes(body: String, episode: String?): EpisodeMatch? {
            val match = findEpisode(body, episode, false)
            if (match != null) return match
            val movieFallback = findMovieFallback(JSONObject(body), episode)
            if (movieFallback != null) {
                LOG.i("echo-danmaku episodes movie fallback episode: " + safeLog(movieFallback.title) + ", episodeId: " + movieFallback.id)
            }
            return movieFallback
        }

        private fun findEpisodeList(obj: JSONObject): EpisodeList? {
            var array = obj.optJSONArray("episodes")
            if (array != null) return EpisodeList(array, isMovieType(obj))
            val bangumi = obj.optJSONObject("bangumi")
            if (bangumi != null) {
                array = bangumi.optJSONArray("episodes")
                if (array != null) return EpisodeList(array, isMovieType(bangumi))
            }
            var animes = obj.optJSONArray("animes")
            if (animes == null) animes = obj.optJSONArray("anime")
            if (animes == null) animes = obj.optJSONArray("data")
            if (animes != null) {
                for (i in 0 until animes.length()) {
                    val item = animes.optJSONObject(i) ?: continue
                    array = item.optJSONArray("episodes")
                    if (array != null && array.length() > 0) return EpisodeList(array, isMovieType(item))
                }
            }
            return null
        }

        private fun isMovieType(obj: JSONObject?): Boolean {
            if (obj == null) return false
            if (isMovieTypeText(obj.optString("type", ""))) return true
            if (isMovieTypeText(obj.optString("typeDescription", ""))) return true
            val bangumi = obj.optJSONObject("bangumi")
            if (bangumi != null && isMovieTypeText(bangumi.optString("type", ""))) return true
            var animes = obj.optJSONArray("animes")
            if (animes == null) animes = obj.optJSONArray("anime")
            if (animes == null) animes = obj.optJSONArray("data")
            if (animes == null) return false
            for (i in 0 until animes.length()) {
                val item = animes.optJSONObject(i)
                if (item != null && isMovieTypeText(item.optString("type", ""))) return true
            }
            return false
        }

        private fun isMovieTypeText(type: String?): Boolean {
            return "\u7535\u5f71" == type
        }

        private fun isMandarinTitle(title: String?): Boolean {
            return !TextUtils.isEmpty(title) && (title!!.contains("\u56fd\u8bed") || title.contains("\u666e\u901a\u8bdd"))
        }

        private fun isCantoneseTitle(title: String?): Boolean {
            return !TextUtils.isEmpty(title) && title!!.contains("\u7ca4\u8bed")
        }

        private fun prefersCantonese(episode: String?): Boolean {
            return isCantoneseTitle(episode)
        }

        private fun isPreferredLanguageTitle(title: String?, preferCantonese: Boolean): Boolean {
            return if (preferCantonese) isCantoneseTitle(title) else isMandarinTitle(title)
        }

        private fun isSearchEpisodesMovieResult(body: String): Boolean {
            try {
                val obj = JSONObject(body)
                var animes = obj.optJSONArray("animes")
                if (animes == null) animes = obj.optJSONArray("anime")
                if (animes == null) animes = obj.optJSONArray("data")
                if (animes == null) return false
                for (i in 0 until animes.length()) {
                    val anime = animes.optJSONObject(i)
                    if (anime != null && isMovieType(anime) && anime.optJSONArray("episodes") != null) return true
                }
            } catch (ignored: Throwable) {
                LOG.d("DanmakuApi", "search body parse failed, treat as not movie")
            }
            return false
        }

        private fun findMovieFallback(obj: JSONObject, episode: String?): EpisodeMatch? {
            var animes = obj.optJSONArray("animes")
            if (animes == null) animes = obj.optJSONArray("anime")
            if (animes == null) animes = obj.optJSONArray("data")
            if (animes == null) return null
            val preferCantonese = prefersCantonese(episode)
            var firstMovie: EpisodeMatch? = null
            for (i in 0 until animes.length()) {
                val anime = animes.optJSONObject(i)
                if (anime == null || !isMovieType(anime)) continue
                val episodes = anime.optJSONArray("episodes") ?: continue
                for (j in 0 until episodes.length()) {
                    val item = episodes.optJSONObject(j) ?: continue
                    val id = firstString(item, "episodeId", "id")
                    if (TextUtils.isEmpty(id)) continue
                    val title = firstString(item, "episodeTitle", "title", "name")
                    val match = EpisodeMatch(id, title, parseEpisodeNumber(firstString(item, "episodeNumber", "number", "sort")))
                    if (firstMovie == null) firstMovie = match
                    if (isPreferredLanguageTitle(title, preferCantonese)) return match
                }
            }
            return firstMovie
        }

        private fun parseEpisodeNumber(value: String?): Int {
            try {
                if (TextUtils.isEmpty(value)) return -1
                return value!!.toFloat().toInt()
            } catch (th: Throwable) {
                return extractNumber(value)
            }
        }

        private fun extractNumber(text: String?): Int {
            if (TextUtils.isEmpty(text)) return -1
            val builder = StringBuilder()
            for (i in 0 until text!!.length) {
                val ch = text[i]
                if (ch.isDigit()) builder.append(ch)
            }
            if (builder.length == 0) return -1
            try {
                return builder.toString().toInt()
            } catch (th: Throwable) {
                return -1
            }
        }

        private fun commentJsonToXml(body: String): String {
            val obj = JSONObject(body)
            var comments = obj.optJSONArray("comments")
            if (comments == null) comments = obj.optJSONArray("data")
            if (comments == null || comments.length() <= 0) return ""
            val builder = StringBuilder()
            builder.append("<i>")
            for (i in 0 until comments.length()) {
                val item = comments.optJSONObject(i) ?: continue
                val param = item.optString("p", "")
                var text = item.optString("m", "")
                if (TextUtils.isEmpty(text)) text = item.optString("text", "")
                if (TextUtils.isEmpty(param) || TextUtils.isEmpty(text)) continue
                builder.append("<d p=\"").append(escapeXml(normalizeDanmakuParam(param))).append("\">")
                        .append(escapeXml(text)).append("</d>")
            }
            builder.append("</i>")
            val xml = builder.toString()
            LOG.i("echo-danmaku builtin xml length: " + xml.length)
            return xml
        }

        private fun normalizeDanmakuParam(param: String): String {
            val values = RegexUtils.getPattern(",").split(param)
            if (values.size < 4) return param
            val time = values[0]
            val type = values[1]
            var size = values[2]
            var color = values[3]
            if (isColorValue(size)) {
                color = size
                size = "25"
            } else if (!isColorValue(color)) {
                color = "16777215"
            }
            return time + "," + type + "," + size + "," + normalizeColor(color)
        }

        private fun isColorValue(value: String?): Boolean {
            try {
                if (TextUtils.isEmpty(value)) return false
                val text = value!!.trim { it <= ' ' }
                if (text.startsWith("#")) return true
                if (text.startsWith("0x") || text.startsWith("0X")) return true
                val color = text.toLong()
                return color >= 0 && color <= 0x00ffffffL
            } catch (th: Throwable) {
                return false
            }
        }

        private fun normalizeColor(color: String?): String {
            if (TextUtils.isEmpty(color)) return "16777215"
            val text = color!!.trim { it <= ' ' }
            try {
                if (text.startsWith("#")) return text.substring(1).toLong(16).toString()
                if (text.startsWith("0x") || text.startsWith("0X")) return text.substring(2).toLong(16).toString()
            } catch (ignored: Throwable) {
                LOG.d("DanmakuApi", "color '" + text + "' parse failed, keep raw")
            }
            return text
        }

        private fun firstString(obj: JSONObject, vararg keys: String): String {
            for (key in keys) {
                val value = obj.optString(key, "")
                if (!TextUtils.isEmpty(value)) return value
            }
            return ""
        }

        private fun safeLog(text: String?): String? {
            return if (TextUtils.isEmpty(text)) "" else text
        }

        private fun parseSearchList(body: String?): List<DanmuSearchResult> {
            val results: MutableList<DanmuSearchResult> = ArrayList()
            if (TextUtils.isEmpty(body)) return results
            val text = body!!.trim { it <= ' ' }
            if (text.startsWith("[")) {
                appendSearchResults(results, JSONArray(text))
                return results
            }
            if (!text.startsWith("{")) return results
            val obj = JSONObject(text)
            var items = obj.optJSONArray("data")
            if (items == null) items = obj.optJSONArray("list")
            if (items == null) items = obj.optJSONArray("results")
            if (items != null) {
                appendSearchResults(results, items)
                return results
            }
            val url = obj.optString("url", "").trim { it <= ' ' }
            if (!TextUtils.isEmpty(url)) {
                results.add(DanmuSearchResult(firstString(obj, "name", "title"), url, false))
            }
            return results
        }

        private fun appendSearchResults(results: MutableList<DanmuSearchResult>, items: JSONArray) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val url = item.optString("url", "").trim { it <= ' ' }
                if (TextUtils.isEmpty(url)) continue
                val name = firstString(item, "name", "title", "episode", "vod_name")
                results.add(DanmuSearchResult(name, url, false))
            }
        }

        private fun parseBuiltinSearchList(body: String, baseUrl: String): List<DanmuSearchResult> {
            val results: MutableList<DanmuSearchResult> = ArrayList()
            val obj = JSONObject(body)
            appendBuiltinResults(results, baseUrl, obj, "")
            val bangumi = obj.optJSONObject("bangumi")
            if (bangumi != null) appendBuiltinResults(results, baseUrl, bangumi, firstString(bangumi, "animeTitle", "title", "name"))
            appendBuiltinAnimeResults(results, baseUrl, obj.optJSONArray("animes"))
            appendBuiltinAnimeResults(results, baseUrl, obj.optJSONArray("anime"))
            appendBuiltinAnimeResults(results, baseUrl, obj.optJSONArray("data"))
            return results
        }

        private fun appendBuiltinAnimeResults(results: MutableList<DanmuSearchResult>, baseUrl: String, animes: JSONArray?) {
            if (animes == null) return
            for (i in 0 until animes.length()) {
                val anime = animes.optJSONObject(i) ?: continue
                appendBuiltinResults(results, baseUrl, anime, firstString(anime, "animeTitle", "title", "name"))
                val bangumi = anime.optJSONObject("bangumi")
                if (bangumi != null) {
                    appendBuiltinResults(results, baseUrl, bangumi, firstString(anime, "animeTitle", "title", "name"))
                }
            }
        }

        private fun appendBuiltinResults(results: MutableList<DanmuSearchResult>, baseUrl: String, obj: JSONObject, animeName: String) {
            val episodes = obj.optJSONArray("episodes") ?: return
            for (i in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(i) ?: continue
                val id = firstString(episode, "episodeId", "id")
                if (TextUtils.isEmpty(id)) continue
                val episodeName = firstString(episode, "episodeTitle", "title", "name")
                var name = if (TextUtils.isEmpty(animeName)) episodeName else animeName + " " + episodeName
                if (TextUtils.isEmpty(name)) name = id
                val url = baseUrl + "/api/v2/comment/" + id + "?format=json"
                results.add(DanmuSearchResult(name, url, true))
            }
        }

        private fun notifySearchListSuccess(callback: SearchListCallback?, seq: Int, results: List<DanmuSearchResult>?) {
            handler.post(Runnable {
                if (isCurrentSearch(seq)) callback!!.onSuccess(results)
            })
        }

        private fun notifySearchListError(callback: SearchListCallback?, seq: Int, message: String?) {
            handler.post(Runnable {
                if (isCurrentSearch(seq)) callback!!.onError(message)
            })
        }

        private fun notifySearchResultSuccess(callback: SearchResultCallback?, seq: Int, danmu: String?) {
            handler.post(Runnable {
                if (isCurrentSearch(seq)) callback!!.onSuccess(danmu)
            })
        }

        private fun notifySearchResultError(callback: SearchResultCallback?, seq: Int, message: String?) {
            handler.post(Runnable {
                if (isCurrentSearch(seq)) callback!!.onError(message)
            })
        }

        private fun getErrorMessage(th: Throwable?): String? {
            val message = if (th == null) "" else th.message
            return if (TextUtils.isEmpty(message)) str(R.string.danmu_search_failed) else message
        }

        private fun escapeXml(text: String?): String {
            if (TextUtils.isEmpty(text)) return ""
            return text!!.replace("&", "&amp;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;")
                    .replace(">", "&gt;")
                    .replace("<", "&lt;")
        }

        private fun parseUrl(body: String?): String {
            if (TextUtils.isEmpty(body)) return ""
            val text = body!!.trim { it <= ' ' }
            if (text.startsWith("[")) {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url", "").trim { it <= ' ' }
                    if (!TextUtils.isEmpty(url)) return url
                }
            } else if (text.startsWith("{")) {
                return JSONObject(text).optString("url", "").trim { it <= ' ' }
            } else if (text.startsWith("http") || text.startsWith("file")) {
                return text
            }
            return ""
        }
    }
}
