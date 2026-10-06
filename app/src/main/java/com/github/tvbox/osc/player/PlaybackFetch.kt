package com.github.tvbox.osc.player

import android.text.TextUtils
import android.util.Base64
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.api.DanmakuApi
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.util.FileUtils
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.PlayerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * 取流状态与结果观察者:持有取流通道并把结果落到会话数据(清晰度/进度键/字幕/歌词/封面),
 * 起播与解析分发仍由宿主完成。
 *
 * 观察面走通道的 [SourceChannel.flow]:收集域随本对象(init 建立、release 取消),
 * 主线程派发与旧 LiveData 观察面一致 —— 通道在发射线程上恢复收集者,由 Main.immediate 落回主线程。
 */
class PlaybackFetch(private val controller: PlaybackController) {

    private var sourceViewModel: SourceViewModel? = null
    private var collectJob: Job? = null

    /** 建立取流结果观察者(预载协调器仍归页面) */
    fun init() {
        val vm = SourceViewModel()
        sourceViewModel = vm
        collectJob = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            vm.playResult.flow.collect { info -> handlePlayResult(info) }
        }
    }

    /** 页面销毁时注销观察者(对应原 hostDestroy 的 removeObserver) */
    fun release() {
        collectJob?.cancel()
        collectJob = null
    }

    /** 把“已准备好的取流结果”直接喂给解析链(页面 play() 命中预载数据时调用) */
    fun deliver(info: JSONObject?) {
        if (collectJob?.isActive != true) return
        handlePlayResult(info)
    }

    /** 预载协调器需要它取流(PreloadCoordinator 构造参数) */
    fun sourceViewModel(): SourceViewModel? = sourceViewModel

    /** 取消在途取流请求 */
    fun cancelPlayRequest() {
        sourceViewModel?.cancelPlayRequest()
    }

    private fun handlePlayResult(info: JSONObject?) {
        if (info == null) controller.publishQuality(null)
        if (info != null) {
            try {
                if (controller.isStalePlayResult(info)) {
                    LOG.i("echo-ignore stale play result")
                    return
                }
                val view = controller.viewBridge()
                if (view != null && controller.isSwitchStopPending()) {
                    // 换源点击即停后,旧源在途的取流结果不得再拉起播放
                    LOG.i("echo-ignore play result while source switching")
                    return
                }
                controller.cancelResolvePlayUrlTimeout()
                controller.publishQuality(info)
                controller.setWebPlayUrl(null)
                controller.setProgressKey(info.optString("proKey", null))
                val parse = info.optString("parse", "1") == "1"
                val jx = info.optString("jx", "0") == "1"
                controller.setPlaySubtitle(info.optString("subt", ""))
                controller.setPlayLyric(info.optString("lyric", ""))
                controller.setLyricCacheKey(info.optString("lyricKey", null))
                if (TextUtils.isEmpty(controller.lyricCacheKey()) && !TextUtils.isEmpty(controller.progressKey())) {
                    controller.setLyricCacheKey(controller.progressKey() + "-lyric")
                }
                val lyrics = info.optJSONArray("lyrics")
                if (lyrics != null && lyrics.length() > 0) {
                    controller.setPlayLyric(getSubtitleUrl(lyrics.optJSONObject(0)))
                }
                val subtitles = info.optJSONArray("subs")
                if (subtitles != null) {
                    for (i in 0 until subtitles.length()) {
                        val obj = subtitles.optJSONObject(i) ?: continue
                        val url = getSubtitleUrl(obj)
                        val name = obj.optString("name", "")
                        if (isLyricSubtitle(name)) {
                            if (TextUtils.isEmpty(controller.playLyric())) controller.setPlayLyric(url)
                        } else if (TextUtils.isEmpty(controller.playSubtitle())) {
                            controller.setPlaySubtitle(url)
                        }
                    }
                }
                controller.setSubtitleCacheKey(info.optString("subtKey", null))
                val lyricPick = controller.playLyric()
                LOG.i(
                    "echo-lyric pick: " + if (TextUtils.isEmpty(lyricPick)) {
                        "none"
                    } else if (lyricPick!!.startsWith("data:")) {
                        "inline len=" + lyricPick.length
                    } else {
                        lyricPick
                    }
                )
                val playUrl = info.optString("playUrl", "")
                val flag = info.optString("flag")
                val rawUrl = info.opt("url")
                var url = if (rawUrl is JSONArray) rawUrl.toString() else rawUrl?.toString() ?: "null"
                if (url.startsWith("[") && view != null) {
                    url = view.firstUrlByArray(url)
                }
                // 音乐源取流结果的封面字段常是 cover 而不是 artwork;漏读会让换集后海报不刷新
                var artwork = info.optString("artwork", "")
                if (TextUtils.isEmpty(artwork)) artwork = info.optString("cover", "")
                if (TextUtils.isEmpty(artwork) && !TextUtils.isEmpty(controller.playLyric()) && controller.vod() != null) {
                    artwork = controller.vod()!!.pic ?: ""
                }
                controller.setCurrentArtwork(artwork)
                if (view != null) view.setArtwork(artwork)
                val msg = info.optString("msg", "")
                if (!TextUtils.isEmpty(msg)) {
                    controller.handleResolvePlayUrlFailed(msg)
                    return
                }
                // 取流成功,手动选线标记完成使命,后续失败恢复走正常自动策略
                controller.setUserPickedLine(false)
                val danmaku = info.optString("danmaku", "").trim { it <= ' ' }
                val danmuProgressKey = controller.progressKey()
                controller.setWebUserAgent(null)
                controller.setWebHeaderMap(null)
                val headers = PlaybackController.extractHeaders(info)
                if (headers != null) {
                    controller.setWebHeaderMap(headers)
                    val ua = PlaybackController.headerValue(headers, "user-agent")
                    controller.setWebUserAgent(if (ua == null) null else ua.trim { it <= ' ' })
                }
                if (parse || jx) {
                    val userJxList = (playUrl.isEmpty() && (ApiConfig.get().getVipParseFlags() ?: mutableListOf()).contains(flag)) || jx
                    controller.initParse(flag, userJxList, playUrl, url)
                } else {
                    if (view != null) view.showParse(false)
                    if (view != null) controller.playUrl(playUrl + url, headers)
                }
                if (TextUtils.isEmpty(danmaku)) {
                    checkDanmu("", null)
                    searchDanmu("")
                } else {
                    checkDanmu(danmaku) {
                        if (TextUtils.equals(danmuProgressKey, controller.progressKey())) {
                            searchDanmu("")
                        }
                    }
                }
            } catch (th: Throwable) {
                controller.handleResolvePlayUrlFailed(PlaybackController.str(R.string.player_get_info_error))
            }
        } else {
            // 获取播放信息错误后只需再重试一次
            controller.handleResolvePlayUrlFailed(PlaybackController.str(R.string.player_get_info_error))
        }
    }

    private fun getSubtitleUrl(obj: JSONObject?): String {
        if (obj == null) return ""
        val format = obj.optString("format", "")
        val name = obj.optString("name", PlaybackController.str(R.string.player_menu_subtitle))
        var ext = ".srt"
        if ("text/x-ssa" == format) {
            ext = ".ass"
        } else if ("text/vtt" == format) {
            ext = ".vtt"
        } else if ("text/lrc" == format) {
            ext = ".lrc"
        }
        val filename = name + if (name.lowercase(Locale.ROOT).endsWith(ext)) "" else ext
        var url = obj.optString("url", "")
        val data = obj.optString("data", "")
        // 本地代理 URL 要靠爬虫的内存态现取,拿不到就整段没有字幕/歌词;同一份内容已在 data 里时直接用
        if (!TextUtils.isEmpty(data) && (TextUtils.isEmpty(url) || PlayerHelper.isLocalProxyUrl(url))) {
            url = "data:text/plain;base64," + Base64.encodeToString(data.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            // data: URI 的文件名只能靠 fragment 带(内容里出现的点会让 hasExtension 误判)
            val view = controller.viewBridge()
            return if (view == null) url else "$url#" + view.encodeUrl(filename)
        }
        if (TextUtils.isEmpty(url) || FileUtils.hasExtension(url)) return url
        val view = controller.viewBridge()
        return if (view == null) url else "$url#" + view.encodeUrl(filename)
    }

    private fun isLyricSubtitle(name: String?): Boolean {
        if (TextUtils.isEmpty(name)) return false
        val text = name!!
        val value = text.lowercase(Locale.ROOT)
        return value.contains("lyric") || value.contains("lrc") || text.contains("歌词") // i18n: keep
    }

    /** 取流结果没带弹幕地址时联网搜一份(与进度键绑定:切集后旧结果作废) */
    private fun searchDanmu(danmaku: String) {
        if (!TextUtils.isEmpty(danmaku) || !DanmakuApi.canSearch(controller.sourceBean()) || controller.vod() == null) return
        val series = controller.currentSeries(controller.vod()!!.playFlag, controller.vod()!!.playIndex)
        val key = controller.progressKey()
        DanmakuApi.search(controller.vod()!!.name, if (series == null) "" else series.name, object : DanmakuApi.SearchCallback {
            override fun onFound(url: String) {
                if (!TextUtils.equals(key, controller.progressKey())) return
                checkDanmu(url, null)
            }

            override fun onNotFound() {
                if (!TextUtils.equals(key, controller.progressKey())) return
                checkDanmu("", null)
            }
        })
    }

    private fun checkDanmu(danmaku: String, onFailed: Runnable?) {
        controller.viewBridge()?.checkDanmu(danmaku, onFailed)
    }
}
