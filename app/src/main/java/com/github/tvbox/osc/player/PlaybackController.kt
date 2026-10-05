package com.github.tvbox.osc.player

import android.text.TextUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.ParseBean
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.ImgUtil
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.PlaybackProgress
import com.github.tvbox.osc.util.PlayerHelper
import com.github.tvbox.osc.util.WatchProgressStore
import com.github.tvbox.osc.util.thunder.Jianpian
import com.github.tvbox.osc.util.thunder.Thunder
import org.greenrobot.eventbus.EventBus
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import xyz.doikki.videoplayer.player.VideoView
import java.net.URLEncoder
import java.util.HashMap
import java.util.Locale

/**
 * 播放会话与派生数据层:播什么(vod/sourceKey/sourceBean/播放器配置)、进度与缓存键、清晰度、
 * 投屏地址改写;视图交互经 [PlaybackViewBridge],取流/解析调度见 [PlayUrlResolver]。
 */
class PlaybackController {

    // ==================== 会话数据 ====================

    private var vod: VodInfo? = null
    private var playerCfg: JSONObject? = null
    private var sourceKey: String = ""
    private var sourceBean: SourceBean? = null

    /** 当前集进度键(源+片+线路+集+集名) */
    private var progressKey: String? = null

    /** 进度键的归属(源|片id),与 progressKey 同处更新;进度键取 MD5 后无法反推归属 */
    private var progressOwner: String? = null

    /** 当前集字幕缓存键 */
    private var subtitleCacheKey: String? = null
    private var playSubtitle: String? = null
    private var playLyric: String? = null
    private var lyricCacheKey: String? = null

    /** 当前清晰度列表原始结果(多清晰度源的 url 数组;null=该源无多清晰度) */
    private var qualityResult: JSONObject? = null

    /** 净化后的 m3u8 代理地址与其原始地址(投屏时换回源地址) */
    private var m3u8ProxyUrl: String? = null
    private var m3u8SourceUrl: String? = null

    /** 换源/换线时"接着看"的进度(新键无历史时才写入) */
    private var inheritProgressKey: String? = null
    private var inheritProgress: Long = 0

    // ==================== 会话生命周期 ====================

    /**
     * 开启一次播放会话:接管页面组装的 [PlaybackSession] 并初始化播放器配置。
     * 调用方随后需自行把 [playerCfg] 刷到控制器。
     */
    fun startSession(session: PlaybackSession) {
        // **会话边界清场**:在途的解析/嗅探/取流/超时属于上一个会话,其结果不得作用到新会话。
        // 收尾放在会话边界而非"页面销毁":"快速返回再进入"时旧页面会把新会话刚发起的取流一起撤掉。
        cancelInFlight()
        // 作废上一条"播完待撤会话"的待判消息,避免落到新会话上
        timeouts.cancelPendingCompletionDrop()
        // 代际复位:上一会话的迟到回调不得作用到新会话
        resolver.resetGen()
        // 封面属于上一个会话:换内容必须清(playArtwork 只写一次、currentArtwork 只在取流结果里覆盖),
        // 否则影视源不给 cover 时会残留上一首的值("音乐 → 影视 → 再进音乐页");同片接管不能清。
        if (currentSession == null || !TextUtils.equals(currentSession!!.playbackKey(), session.playbackKey())) {
            music.clearArtworks()
            // 换内容 ⇒ 上一份内容的"纯音频"确认作废(同片接管不清:内容没变)
            st.audioOnlyConfirmed = false
        }
        currentSession = session
        // 本次会话的内容尚未真正交给播放器:先清掉"已起播内容"标记 ——
        // 否则"切到 B 但取流失败(播放器里其实还是 A)"后重进 B,会被 D6 误判成同片接管(播错内容)
        startedPlaybackKey = null
        // 会话级状态的统一复位:这些字段的复位点原本只在 play() 里,而 **D6 同片接管不走 play()** ⇒
        // playbackStarted 陈旧会吞掉续播失败(黑屏不重试)、switchStopPending 残留会丢在途取流结果、
        // m3u8 残留会让投屏拿到上一部的源地址。
        st.beginSession()
        clearM3u8ProxyUrl()
        vod = session.vod()
        sourceKey = session.sourceKey()
        sourceBean = ApiConfig.get().getSource(sourceKey)
        ApiConfig.get().setCurrentPlaySourceKey(sourceKey)
        initPlayerCfg()
    }

    fun initPlayerCfg() {
        config.initPlayerCfg()
    }

    // ==================== 进度与缓存键 ====================

    fun getSavedProgress(url: String?): Long {
        val skip = (playerCfg?.optInt("st", 0) ?: 0) * 1000L
        // 无痕:旧记录连读都不读 —— 只拦写的话,重进仍会从上次留下的位置接着播,隐身等于没开
        if (HistoryHelper.isIncognito()) return skip
        WatchProgressStore.awaitWrites()
        val theCache = AppGraph.cacheRepository.get(MD5.string2MD5(url))
        if (theCache == null) {
            return skip
        }
        var rec = 0L
        if (theCache is Long) {
            rec = theCache
        } else if (theCache is String) {
            try {
                rec = theCache.toLong()
            } catch (e: NumberFormatException) {
                LOG.i("echo-String value is not a valid long.")
            }
        } else {
            LOG.i("echo-Value cannot be converted to long.")
        }
        return Math.max(rec, skip)
    }

    /**
     * 记录"接着看"的进度(换源点击即停/自动换线时调用)。
     * 新键已有历史记录时不覆盖(见 [inheritProgressIfNeeded])。
     */
    fun inheritProgressFrom(key: String?, position: Long) {
        inheritProgressKey = key
        inheritProgress = position
    }

    /** 把"接着看"的进度写进新键(仅当新键无历史);无论结果如何都清掉待继承状态 */
    fun inheritProgressIfNeeded() {
        try {
            WatchProgressStore.inherit(progressOwner(), inheritProgressKey, progressKey, inheritProgress)
        } finally {
            inheritProgressKey = null
            inheritProgress = 0
        }
    }

    /** 进度索引的归属键(源|片id):与 [progressKey] 成对,未起播过则为 null(此时只落进度、不维护索引) */
    fun progressOwner(): String? = progressOwner

    // 线路/剧集匹配见 EpisodeMatcher

    fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries? {
        val currentVod = vod ?: return null
        val seriesMap = currentVod.seriesMap ?: return null
        if (flag == null) return null
        val currentList = seriesMap[flag] ?: return null
        if (currentList.isEmpty()) return null
        val safeIndex = Math.max(0, Math.min(index, currentList.size - 1))
        return currentList[safeIndex]
    }

    /**
     * 取流结果是否已过期(切集/换线/换源后,旧源在途结果不得拉起播放)。
     */
    fun isStalePlayResult(info: JSONObject): Boolean {
        val currentVod = vod
        if (currentVod == null || currentVod.seriesMap == null || TextUtils.isEmpty(progressKey)) return false
        val resultKey = info.optString("proKey", "")
        if (!TextUtils.isEmpty(resultKey) && progressKey != resultKey) return true
        val resultFlag = info.optString("flag", "")
        if (!TextUtils.isEmpty(resultFlag) && resultFlag != currentVod.playFlag) return true
        val sourceUrl = info.optString("key", "")
        if (!TextUtils.isEmpty(sourceUrl)) {
            val vs = currentSeries(currentVod.playFlag, currentVod.playIndex)
            return vs != null && sourceUrl != vs.url
        }
        return false
    }

    // ==================== 清晰度 ====================

    /** 发布/清空清晰度列表(仅改内存态 + EventBus 广播,不启动播放) */
    fun publishQuality(info: JSONObject?) {
        try {
            val src = info ?: throw JSONException("invalid quality urls")
            val urls = JSONArray(src.optString("url"))
            if (urls.length() < 4 || urls.length() % 2 != 0) throw JSONException("invalid quality urls")
            qualityResult = JSONObject(src.toString())
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_PLAY_QUALITY, qualityResult))
        } catch (th: Throwable) {
            qualityResult = null
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_PLAY_QUALITY, null))
        }
    }

    fun quality(): JSONObject? = qualityResult

    // ==================== 投屏地址改写 ====================

    /** m3u8 代理地址 → 源地址;本地代理地址 → 局域网地址(配合 ControlManager 的地址发现) */
    fun getCastUrl(url: String?): String? {
        if (url == null || url.isEmpty()) return url
        if (isM3u8ProxyUrl(url) && !TextUtils.isEmpty(m3u8SourceUrl)) return m3u8SourceUrl
        val local = ControlManager.get().getAddress(true)
        val server = ControlManager.get().getAddress(false)
        if (!TextUtils.isEmpty(local) && !TextUtils.isEmpty(server) && url.startsWith(local)) {
            return server + url.substring(local.length)
        }
        return url
    }

    fun isM3u8ProxyUrl(url: String?): Boolean {
        return !TextUtils.isEmpty(m3u8ProxyUrl) && url == m3u8ProxyUrl
    }

    fun setM3u8Urls(proxyUrl: String?, sourceUrl: String?) {
        m3u8ProxyUrl = proxyUrl
        m3u8SourceUrl = sourceUrl
    }

    fun m3u8SourceUrl(): String? = m3u8SourceUrl

    fun clearM3u8ProxyUrl() {
        m3u8ProxyUrl = null
        m3u8SourceUrl = null
    }

    // ==================== 访问器 ====================

    fun vod(): VodInfo? = vod

    fun playerCfg(): JSONObject? = playerCfg

    fun sourceBean(): SourceBean? = sourceBean

    fun sourceKey(): String = sourceKey

    fun progressKey(): String? = progressKey

    fun setProgressKey(progressKey: String?) {
        this.progressKey = progressKey
        // 归属与键同处更新:换片时先 release 旧内核(那一刻视图里还是旧键),此处若按当前 vod 归属会把旧片的键记到新片名下
        this.progressOwner = WatchProgressStore.ownerOf(vod)
    }

    fun subtitleCacheKey(): String? = subtitleCacheKey

    fun setSubtitleCacheKey(subtitleCacheKey: String?) {
        this.subtitleCacheKey = subtitleCacheKey
    }

    fun playSubtitle(): String? = playSubtitle

    fun setPlaySubtitle(playSubtitle: String?) {
        this.playSubtitle = playSubtitle
    }

    fun playLyric(): String? = playLyric

    fun setPlayLyric(playLyric: String?) {
        this.playLyric = playLyric
    }

    fun lyricCacheKey(): String? = lyricCacheKey

    fun setLyricCacheKey(lyricCacheKey: String?) {
        this.lyricCacheKey = lyricCacheKey
    }

    // ==================== 调度:重试与换线决策 ====================
    // 一切视图交互都经 PlaybackViewBridge。

    /** 视图侧契约(页面内由 PlayContainer 提供匿名实现) */
    private var view: PlaybackViewBridge? = null

    fun setViewBridge(bridge: PlaybackViewBridge) {
        this.view = bridge
    }

    /** 三处超时(取流/换线/播完待撤)的定时消息投递 */
    private val timeouts: PlaybackTimeouts = PlaybackTimeouts(object : PlaybackTimeouts.Callback {
        override fun onResolvePlayUrlTimeout() {
            handleResolvePlayUrlTimeout()
        }

        override fun onSwitchLinePlayTimeout() {
            handleSwitchLinePlayTimeout()
        }

        override fun onPendingCompletionDrop() {
            music.handlePendingCompletionDrop()
        }
    })

    /** 播放器配置(见 PlaybackConfigDelegate) */
    private val config: PlaybackConfigDelegate = PlaybackConfigDelegate(object : PlaybackConfigDelegate.Host {
        override fun vod(): VodInfo? = this@PlaybackController.vod

        override fun sourceBean(): SourceBean? = this@PlaybackController.sourceBean

        override fun playerCfg(): JSONObject? = this@PlaybackController.playerCfg

        override fun setPlayerCfg(cfg: JSONObject) {
            this@PlaybackController.playerCfg = cfg
        }

        override fun attemptState(): PlaybackAttemptState = st
    })

    /** 重试与换线策略(见 PlaybackRetryDelegate) */
    private val retry: PlaybackRetryDelegate = PlaybackRetryDelegate(object : PlaybackRetryDelegate.Host {
        override fun attemptState(): PlaybackAttemptState = st

        override fun view(): PlaybackViewBridge? = this@PlaybackController.view

        override fun playerCfg(): JSONObject? = this@PlaybackController.playerCfg

        override fun vod(): VodInfo? = this@PlaybackController.vod

        override fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries? =
            this@PlaybackController.currentSeries(flag, index)

        override fun progressKey(): String? = this@PlaybackController.progressKey

        override fun getSavedProgress(url: String): Long = this@PlaybackController.getSavedProgress(url)

        override fun inheritProgressFrom(key: String?, position: Long) {
            this@PlaybackController.inheritProgressFrom(key, position)
        }

        override fun webPlayUrl(): String? = this@PlaybackController.webPlayUrl

        override fun webHeaderMap(): HashMap<String, String>? = this@PlaybackController.webHeaderMap

        override fun resolverHasFoundUrls(): Boolean = resolver.hasFoundUrls()

        override fun resolverConsumeFoundUrl() {
            resolver.consumeFoundUrl()
        }

        override fun play(reset: Boolean) {
            this@PlaybackController.play(reset)
        }

        override fun playUrl(url: String, headers: HashMap<String, String>?) {
            this@PlaybackController.playUrl(url, headers)
        }

        override fun stopParse() {
            this@PlaybackController.stopParse()
        }

        override fun initParseLoadFound() {
            this@PlaybackController.initParseLoadFound()
        }

        override fun cancelPlayRequest() {
            fetch.cancelPlayRequest()
        }

        override fun cancelPlayTimeout() {
            this@PlaybackController.cancelPlayTimeout()
        }

        override fun isPlaybackStarted(): Boolean = this@PlaybackController.isPlaybackStarted()

        override fun stopMusicSessionForFailedPlayback() {
            music.stopMusicSessionForFailedPlayback()
        }

        override fun isCrossContentReuseAllowed(): Boolean = this@PlaybackController.isCrossContentReuseAllowed()
    })

    /** 解析/嗅探调度(见 PlayUrlResolver) */
    private val resolver: PlayUrlResolver = PlayUrlResolver(object : PlayUrlResolver.Host {
        override fun view(): PlaybackViewBridge? = this@PlaybackController.view

        override fun sourceBean(): SourceBean? = this@PlaybackController.sourceBean()

        override fun webHeaderMap(): HashMap<String, String>? = this@PlaybackController.webHeaderMap

        override fun setWebHeaderMap(headers: HashMap<String, String>?) {
            this@PlaybackController.webHeaderMap = headers
        }

        override fun webUserAgent(): String? = this@PlaybackController.webUserAgent

        override fun setWebUserAgent(userAgent: String?) {
            this@PlaybackController.webUserAgent = userAgent
        }

        override fun playUrl(url: String, headers: HashMap<String, String>?) {
            this@PlaybackController.playUrl(url, headers)
        }

        override fun playUrl(gen: Int, url: String, headers: HashMap<String, String>?) {
            this@PlaybackController.playUrl(gen, url, headers)
        }
    })

    /** 尝试/换线/解码/会话标记状态(见 PlaybackAttemptState) */
    private val st: PlaybackAttemptState = PlaybackAttemptState()

    // -------------------- 状态开关(供页面在既有流程点调用) --------------------

    /** 新一次播放的清场:重试阶梯 + 内核/解码自动态 + 起播标记(内容边界标记仍在调用方) */
    fun beginNewPlay() {
        st.beginNewPlay()
        // 新内容开始 ⇒ 上一条"播完待撤会话"的判定作废(否则那条迟到的消息会打到本次新会话上)
        timeouts.cancelPendingCompletionDrop()
        // 换内容(换集/换线/换源/重播)⇒ 上一次确认的"纯音频"作废,由新内容自己重新确认
        // (自动重试不走本方法,见 retryAfterStartedError:同一内容的确认必须留着)
        st.audioOnlyConfirmed = false
    }

    /** 换源点击即停:清"播放中"标记与复用开关,并置"在途结果作废"标记(下一次 play 清除) */
    fun markStoppedForSourceSwitch() {
        st.stoppedForSourceSwitch()
    }

    /** 取出并复位"复用播放器"意图 */
    fun consumeReusePlayerOnSwitch(): Boolean {
        return st.consumeReuseIntent()
    }

    fun setReusePlayerOnSwitch(reuse: Boolean) {
        st.setReuseIntent(reuse)
    }

    fun setReleasePlayerOnSwitch(release: Boolean) {
        st.setReleaseIntent(release)
    }

    /** 切集/换线:清空"已尝试线路" */
    fun clearTriedLines() {
        st.clearTriedLines()
    }

    fun setUserPickedLine(picked: Boolean) {
        st.userPickedLine = picked
    }

    fun setAllowSwitchPlayer(allow: Boolean) {
        config.setAllowSwitchPlayer(allow)
    }

    fun setAllowDecodeFallback(allow: Boolean) {
        config.setAllowDecodeFallback(allow)
    }

    fun playerCfgForPersist(): JSONObject? {
        return config.playerCfgForPersist()
    }

    /** 用户自救(重播/切解析/切内核/切解码)后:允许再兜一次底 */
    fun resetAutoRetryState() {
        st.userSelfRescue()
    }

    fun setPlaybackStarted(started: Boolean) {
        st.playbackStarted = started
    }

    fun setPlayTimeoutBasePosition(position: Long) {
        st.playTimeoutBasePosition = position
    }

    /** 取流起播的基准位置(跳播/转圈判定用) */
    fun playTimeoutBasePosition(): Long {
        return st.playTimeoutBasePosition
    }

    fun isStartedPlayState(state: Int): Boolean {
        return state == VideoView.STATE_PREPARED || state == VideoView.STATE_BUFFERED || state == VideoView.STATE_PLAYING
    }

    fun markPlaybackStarted() {
        st.playbackStarted = true
        cancelPlayTimeout()
    }

    fun isPlaybackStarted(): Boolean {
        if (st.playbackStarted) return true
        val bridge = view ?: return false
        return isStartedPlayState(bridge.currentPlayState()) || hasPlaybackProgress(bridge.currentPosition()) || bridge.isPlaying()
    }

    private fun hasPlaybackProgress(progress: Long): Boolean {
        return progress > Math.max(st.playTimeoutBasePosition, 0) + 1000
    }

    // -------------------- 三处超时 --------------------

    fun startResolvePlayUrlTimeout() {
        timeouts.startResolvePlayUrlTimeout(getResolvePlayUrlTimeoutMs())
    }

    private fun getResolvePlayUrlTimeoutMs(): Long {
        if (sourceBean() == null) return PlaybackTimeouts.RESOLVE_PLAY_URL_TIMEOUT_MS
        return Math.max(PlaybackTimeouts.RESOLVE_PLAY_URL_TIMEOUT_MS, (sourceBean()!!.getPlayTimeoutSeconds() + 1L) * 1000L)
    }

    fun startSwitchLinePlayTimeout() {
        if (!st.allowAutoSwitchLine) {
            cancelPlayTimeout()
            return
        }
        cancelPlayTimeout()
        LOG.i("echo-switchLinePlay start timeout")
        timeouts.startSwitchLinePlayTimeout()
    }

    fun cancelSwitchLinePlayTimeout() {
        cancelPlayTimeout()
    }

    fun cancelPlayTimeout() {
        timeouts.cancelPlayTimeout()
    }

    /** 只取消"取流超时"(取流结果已到达时;换线播放超时另计,不能一起取消) */
    fun cancelResolvePlayUrlTimeout() {
        timeouts.cancelResolvePlayUrlTimeout()
    }

    /** 预览态启用/全屏禁用自动换线(全屏时用户在看画面,不该被换线打断) */
    fun setAutoSwitchLineEnabled(enabled: Boolean) {
        // 值未变就直接返回:页面每次进入/重进都会下发一遍,重复的"禁用"不能再去动在途取流超时与换线记录
        if (st.allowAutoSwitchLine == enabled) return
        st.allowAutoSwitchLine = enabled
        if (!enabled) {
            cancelPlayTimeout()
            st.clearTriedLines()
        }
    }

    // -------------------- 重试与换线 --------------------
    // 实现见 PlaybackRetryDelegate

    fun retryAfterStartedError(): Boolean {
        return retry.retryAfterStartedError()
    }

    fun autoRetry(): Boolean {
        return retry.autoRetry()
    }

    fun tryNextLineIfEnabled(): Boolean {
        return retry.tryNextLineIfEnabled()
    }

    fun tryNextLine(): Boolean {
        return retry.tryNextLine()
    }

    fun handleResolvePlayUrlTimeout() {
        retry.handleResolvePlayUrlTimeout()
    }

    fun handleResolvePlayUrlFailed(err: String) {
        retry.handleResolvePlayUrlFailed(err)
    }

    fun handleSwitchLinePlayTimeout() {
        retry.handleSwitchLinePlayTimeout()
    }

    // ==================== 取流状态与结果观察者 ====================

    /** 已解析出的可播地址与请求头(重试/换内核重播用) */
    private var webPlayUrl: String? = null
    private var webHeaderMap: HashMap<String, String>? = null
    private var webUserAgent: String? = null

    /**
     * 最近一次**通过校验**的起播请求所属的代际(仅主线程读写):[goPlayUrl] 入口签发,
     * UI 落地闭包用它比对 —— 排队期(回调 → runOnUi)若换了集,排队中的旧地址会被丢弃。
     * 签发点必须在入口(不能用解析产物入口的字段):M3U8 净化是主线程直接进 goPlayUrl 的。
     */
    private var playUrlGeneration: Int = 0

    /** 取流状态与结果观察者(见 PlaybackFetch) */
    private val fetch: PlaybackFetch = PlaybackFetch(this)

    /** 当前会话(页面 setData 交进来的那一份;D6 接管与"已起播内容"判定都基于它) */
    private var currentSession: PlaybackSession? = null

    /**
     * 最近一次**真正把内容交给播放器**的会话归属键(D6 接管的唯一可信依据):
     * `startSession` 只是登记要播什么,取流失败或被外部播放器接走时播放器里的内容不属于该会话,
     * D6 必须拒绝接管(真机 bug:点播页播着直播)。
     */
    private var startedPlaybackKey: String? = null

    /** 上一次真正起播时下发的进度键。与归属键的唯一区别:**会话边界不清** —— 换片时 [startSession] 会先清归属键(D6 依据须即时作废),复用判定若读它则恒判不出"内核里是上一部片"。 */
    private var startedProgressKey: String? = null

    /** 内容真正起播(地址交给播放器)时调用:记录归属,供 D6 接管判定 */
    fun markContentStarted() {
        startedPlaybackKey = currentSession?.playbackKey()
        // 与归属同处记录:此刻 progressKey 已是本次内容的键(起播点先 setProgressKey 再调本方法)
        startedProgressKey = progressKey
    }

    /** 内容不再属于当前会话(直播接管等):清空归属标记 */
    fun clearStartedContent() {
        startedPlaybackKey = null
        // 内核交出去后播放器里不再有"本控制器的内容",进度也没有可落盘的归属了(与上面同处清,保持两者同步)
        startedProgressKey = null
    }

    fun startedPlaybackKey(): String? = startedPlaybackKey

    /** 上一次真正起播的进度键(= 内核里那份内容的位置归属);null = 内核里没播过内容(未创建/预热空闲/已释放) */
    private fun startedProgressKey(): String? = startedProgressKey

    /**
     * 内核里那份内容是否就是本次要播的这一集(= 同内容重播,不是换内容)。
     * 起播点据此决定要不要在 replay 前补落盘:换内容时进度键与起点都已属新内容,补落盘会污染新旧两个键。
     */
    fun isSameStartedContent(): Boolean {
        return startedProgressKey != null && TextUtils.equals(startedProgressKey, progressKey())
    }

    /** 建立取流结果观察者 */
    fun initFetch() {
        fetch.init()
    }

    /** 页面销毁时注销观察者 */
    fun releaseFetch() {
        fetch.release()
    }

    /** 当前视图桥(取流观察者/预载调度读取) */
    fun viewBridge(): PlaybackViewBridge? = view

    fun setCurrentArtwork(artwork: String?) {
        music.setCurrentArtwork(artwork)
    }

    fun webPlayUrl(): String? = webPlayUrl

    fun setWebPlayUrl(webPlayUrl: String?) {
        this.webPlayUrl = webPlayUrl
    }

    fun webHeaderMap(): HashMap<String, String>? = webHeaderMap

    fun setWebHeaderMap(webHeaderMap: HashMap<String, String>?) {
        this.webHeaderMap = webHeaderMap
    }

    fun webUserAgent(): String? = webUserAgent

    fun setWebUserAgent(webUserAgent: String?) {
        this.webUserAgent = webUserAgent
    }

    // -------------------- 解析/嗅探门面(见 PlayUrlResolver) --------------------

    /** 按解析规则发起解析(直链/json/聚合/超级解析) */
    fun initParse(flag: String?, useParse: Boolean, playUrl: String, url: String) {
        resolver.initParse(flag, useParse, playUrl, url)
    }

    /** 解析入口 */
    fun doParse(pb: ParseBean) {
        resolver.doParse(pb)
    }

    /** 停止解析/嗅探 */
    fun stopParse() {
        resolver.stopParse()
    }

    /** 重置嗅探结果容器 */
    fun initParseLoadFound() {
        resolver.initParseLoadFound()
    }

    /** 本轮解析/嗅探是否仍有效(代际闸门) */
    fun isParseResultCurrent(gen: Int): Boolean {
        return resolver.isParseResultCurrent(gen)
    }

    fun stopLoadWebView(destroy: Boolean) {
        resolver.stopLoadWebView(destroy)
    }

    // ==================== 取流入口 ====================
    // play/playUrl/goPlayUrl 是"调度 → 视图"的分界线:决策(外部播放器、dash 强制 EXO、纯音频渲染、
    // 进度继承、预载命中)在调度层,真正操作 MyVideoView 的连招交给 view.startVideoPlayback(...)。

    fun isSwitchStopPending(): Boolean {
        return st.switchStopPending
    }

    /** 换源点击即停时记下"接着看"的进度(play 时写进新键) */
    fun setPendingInherit(key: String?, progress: Long) {
        st.pendingInheritKey = key
        st.pendingInheritProgress = progress
    }

    /**
     * 把当前会话的标题下发到视图(播放器顶栏 / 暂停浮层)。
     * D6「同片接管」**不经过** [play],而标题原先只在 play() 里下发 ⇒ 接管路径必须自己补一次,
     * 否则"退出详情页 → 重新进入同一部"顶栏标题为空。
     */
    fun publishTitle() {
        if (view == null || vod() == null) return
        val vs = currentSeries(vod()!!.playFlag, vod()!!.playIndex) ?: return
        view?.setTitle(vod()!!.name + " " + vs.name)
    }

    /**
     * 播放当前集的唯一入口(切集/换线/换源/重播都走它)。
     *
     * @param reset true = 清除已有进度从头发起(重播)
     */
    fun play(reset: Boolean) {
        // 新播放是用户显式请求(换源落地/回滚重播):解除换源停播抑制
        st.switchStopPending = false
        // 入口即失效(见 parseGeneration):上一集的在途结果不得再拉起播放。必须在下面的 early return 之前
        resolver.nextGen()
        // 预载失效事件(切集/换线/换源/重播):作废在途预解析与预载数据,稳定播放后重新评估
        invalidatePreload()
        view?.hidePreloadReadyTip()
        if (vod() == null) return
        // 起播前的复用判定走唯一入口(与各起播点同一函数):意图来自同片换集/换线/切歌;
        // 预热总闸开启后换片/换源也免意图复用 —— 否则会被这里的释放收走,预热与总闸双双落空
        val kernelPresent = view?.mediaPlayer() != null
        val idleKernelReused = isIdleKernelReusable(kernelPresent)
        val crossContentReuseAllowed = isCrossContentReuseAllowed()
        val reuseAllowed = consumeReusePlayerOnSwitch() || idleKernelReused || crossContentReuseAllowed
        // 内核里躺着的还是本次这一集(= 同片换集,提示语可留着);假 → 换内容或空闲内核,须给"获取信息"反馈
        val startedKey = startedProgressKey()
        val sameContentReuse = startedKey != null
            && !KernelReusePolicy.isCrossContentSwitch(startedKey, progressKey())
        // "必须重建"标记与 dash 专用路径取流后才可知,交起播点判定;这里只决定"要不要先把内核释放掉"
        val reusePlayer = KernelReusePolicy.decide(kernelPresent, false, false, reuseAllowed) == KernelDecision.REUSE
        st.switchingPlayback = true
        st.audioPlayback = false
        view?.onNewPlayStarted()
        view?.clearArtwork()
        // 逐级判空 + 集号 clamp(与 goPlayUrl 同源防护):历史恢复的线路在
        // 当前源不存在、或源更新后集数变少时,裸链式取值会 NPE/IOOBE 直接崩在主线程;
        // 走失败链路(自动换线兜底)而不是崩溃
        val vs = currentSeries(vod()!!.playFlag, vod()!!.playIndex)
        if (vs == null) {
            handleResolvePlayUrlFailed(str(R.string.player_get_info_error))
            return
        }
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_REFRESH, vod()))
        if (sameContentReuse) {
            // 复用播放器时提示已由上一集留着,这里强制写一次空态(走 view.showTip 而非页面 setTip):
            // 提示层状态归视图桥,页面/音乐页初始化都会先 hide(),旧态不会留给下一页
            view?.showTip("", true, false)
        } else {
            // 空闲内核与换内容都没有"上一集的提示"可留,仍要给"获取播放信息"反馈
            view?.showTip(str(R.string.player_getting_info), true, false)
        }
        publishTitle()

        stopParse()
        beginNewPlay()
        config.syncDecodeFromGlobal()
        setWebPlayUrl(null)
        setWebHeaderMap(null)
        initParseLoadFound()

        view?.stopOtherPlayers()
        view?.resetDanmu()
        view?.clearLyric()
        if (reusePlayer) {
            // 复用起播必经此处补落盘:同片换集有停播链路兜底(幂等),换内容(含音乐页换歌)则是唯一时机
            savePreviousContentProgress()
            view?.clearVideoFrame()
        } else if (kernelPresent) {
            // 内核本来就不在时不空转 release(它会重复清"已起播内容"归属)
            view?.releasePlayer()
        }
        ImgUtil.clearMemoryCache()
        setSubtitleCacheKey(
            vod()!!.sourceKey + "-" + vod()!!.id + "-" + vod()!!.playFlag + "-"
                + vod()!!.playIndex + "-" + vs.name + "-subt"
        )
        setProgressKey(vod()!!.sourceKey + vod()!!.id + vod()!!.playFlag + vod()!!.playIndex + vs.name)
        // 这一集是真的重新起播:删除时下的"作废"到此为止(否则用户重看一遍也不再记进度)
        WatchProgressStore.onPlayStart(progressKey())
        PlaybackProgress.onEpisodeStartNoScroll()
        startResolvePlayUrlTimeout()
        // 换源点击即停前记下的进度:新源进度键不同,写进新键缓存接着看(新键已有历史记录则不覆盖);
        // 回滚原源时键相同,停播 release 已落盘,该方法会直接跳过
        if (st.pendingInheritProgress > 0 && !TextUtils.isEmpty(st.pendingInheritKey)) {
            inheritProgressFrom(st.pendingInheritKey, st.pendingInheritProgress)
            LOG.i("echo-switchSource inherit progress " + st.pendingInheritProgress + "ms from " + st.pendingInheritKey)
        }
        st.pendingInheritKey = null
        st.pendingInheritProgress = 0
        // 重新播放清除现有进度
        if (reset) {
            // 重播不消费待继承进度,留着会被下一次非重播播放写进别的集
            inheritProgressKey = null
            inheritProgress = 0
            WatchProgressStore.clear(progressOwner(), progressKey())
            AppGraph.cacheRepository.delete(MD5.string2MD5(subtitleCacheKey()), 0)
        } else {
            inheritProgressIfNeeded()
            // 外挂字幕视图先复位为隐藏,真有字幕再由字幕决策链路(applyDefaultSubtitle/setSubtitlePath)显示
            view?.setSubtitleViewVisible(false)
        }

        if (Jianpian.isJpUrl(vs.url!!)) { // 荐片地址特殊判断
            val jpUrl = vs.url
            view?.showParse(false)
            if (vs.url!!.startsWith("tvbox-xg:")) {
                playUrl(Jianpian.JPUrlDec(jpUrl!!.substring(9))!!, null)
            } else {
                playUrl(Jianpian.JPUrlDec(jpUrl!!)!!, null)
            }
            return
        }
        // p2p 取流是异步回调(可能数十秒):同样带发起时的代际,切集后旧地址不得起播
        val thunderGen = resolver.currentGen()
        if (Thunder.play(vs.url!!, object : Thunder.ThunderCallback {
                override fun status(code: Int, info: String) {
                    view?.showTip(info, code >= 0, code < 0)
                }

                override fun list(urlMap: MutableMap<Int, String>) {
                }

                override fun play(url: String) {
                    playUrl(thunderGen, url, null)
                }
            })
        ) {
            view?.showParse(false)
            return
        }

        if (preload.consumeResult(progressKey())) return
        val svm = fetch.sourceViewModel()
        if (svm != null) {
            svm.getPlay(sourceKey(), vod()!!.playFlag, progressKey(), vs.url, subtitleCacheKey())
        }
    }

    /**
     * 未绑定内容的空闲内核(预热建的、或上次内容已停)可免意图复用:它没有内容语义要保护,复用只是 reset+换源。
     * 有内容的内核(暂停/在播)仍按"新内容先释放"处理。
     */
    private fun isIdleKernelReusable(kernelPresent: Boolean): Boolean {
        if (!kernelPresent) return false
        return view!!.currentPlayState() == VideoView.STATE_IDLE
    }

    /**
     * 跨内容复用许可(换片/换源/换集/换线):复用在上界内(见引擎的空闲释放)才有收益,与预热开关无关。
     * ERROR 态返回 false:复用一个坏内核没有意义,强制重建兜底。
     */
    fun isCrossContentReuseAllowed(): Boolean {
        val bridge = view ?: return false
        if (bridge.mediaPlayer() == null) return false
        return !bridge.isKernelErrored()
    }

    /**
     * 换内容前把上一段的位置落盘,**必须在下一次 [setProgressKey] 之前**调 —— 之后进度键就易主了。
     * 复用起播走 replay、不经 release(该方法内部才有 saveProgress 兜底),漏了这一步就丢上一段的观看位置。
     */
    private fun savePreviousContentProgress() {
        val bridge = view ?: return
        if (TextUtils.isEmpty(progressKey())) return
        val position = bridge.currentPosition()
        if (position <= 0) return
        WatchProgressStore.save(progressOwner(), progressKey(), position, bridge.duration())
    }

    /**
     * 解析/嗅探产物入口:入口校验挡"回调已跑起来"的旧结果(已切集时连 RefreshEvent 播放地址与换线超时都不该被改写);
     * [goPlayUrl] 里那道校验挡"回调 → UI 线程排队"期间的切集。
     * 自动重试/重播兜底/自动换线走的都是 2 参 [playUrl](与 goPlayUrl 同帧同代际)⇒ 不会误杀。
     */
    private fun playUrl(gen: Int, url: String, headers: HashMap<String, String>?) {
        if (!resolver.isParseResultCurrent(gen)) {
            LOG.i("echo-ignore stale parse result")
            return
        }
        playUrlGeneration = gen
        playUrl(url, headers)
    }

    /** 取流结果入口:先按 M3U8 去广告规则分流,再交给 goPlayUrl 起播 */
    fun playUrl(url: String, headers: HashMap<String, String>?) {
        startSwitchLinePlayTimeout()
        val target = attachProxySiteKey(url)
        if (!target.startsWith("data:application")) {
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_REFRESH, target)) // 更新播放地址
        }
        if (!KV.get(HawkConfig.M3U8_PURIFY, false)) {
            goPlayUrl(target, headers)
            return
        }
        if (target.startsWith("http://127.0.0.1") || !target.contains(".m3u8")) {
            goPlayUrl(target, headers)
            return
        }
        if (vod() != null && DefaultConfig.noAd(vod()!!.playFlag)) {
            goPlayUrl(target, headers)
            return
        }
        LOG.i("echo-playM3u8:" + target)
        // 净化链是唯一不走 goPlayUrl 的起播路径(净化完成回调 startPlayUrl),起播前由页面桥校验代际
        view?.playM3u8(target, headers, playUrlGeneration)
        // 净化期间先记下起点地址,否则净化源上 autoRetry/retryAfterStartedError 找不到可重播地址
        setWebPlayUrl(target)
    }

    /** 真正起播一个可播地址(外部播放器 / dash 强制 EXO / 复用播放器换集都在这里分流) */
    fun goPlayUrl(url: String, headers: HashMap<String, String>?) {
        LOG.i("echo-goPlayUrl:" + url)
        if (TextUtils.isEmpty(url)) {
            handleResolvePlayUrlFailed(str(R.string.player_play_url_empty))
            return
        }
        val bridge = view
        if (bridge == null || !bridge.isPageAlive()) return
        // 调用方与解析回调同帧或同线程 ⇒ 本地址归属当前轮,在排队前签发代际(见 playUrlGeneration 注释)
        playUrlGeneration = resolver.currentGen()
        val finalUrl = url
        bridge.runOnUi(Runnable {
            if (st.switchStopPending) {
                // 换源点击即停后,已排队的取流结果(含嗅探/解析回调)不得再拉起播放
                LOG.i("echo-ignore goPlayUrl while source switching")
                return@Runnable
            }
            if (playUrlGeneration != resolver.currentGen()) {
                // 上一轮的产物(排队期已切集/换线/换源/重播)⇒ 丢弃,并撤掉旧链超时(否则到期会触发一次换线)
                LOG.i("echo-ignore goPlayUrl of stale parse result")
                resolver.cancelParseTimeout()
                return@Runnable
            }
            // 地址在归属确认之后才记录,否则被丢弃的旧地址会留在 webPlayUrl 上被 autoRetry 拿去重播
            setWebPlayUrl(finalUrl)
            stopParse()
            if (view == null) return@Runnable
            var targetUrl = finalUrl
            try {
                val playerType = playerCfg()!!.getInt("pl")
                if (playerType >= 10) {
                    view?.releasePlayer()
                    // 历史恢复的线路在当前源不存在、或换源与切集交错时,
                    // seriesMap 链式取值可能 NPE,逐级判空后回退仅用片名
                    val series = if (vod() == null || vod()!!.seriesMap == null) {
                        null
                    } else {
                        vod()!!.seriesMap!![vod()!!.playFlag]
                    }
                    val vs = if (series == null || vod()!!.playIndex < 0 || vod()!!.playIndex >= series.size) {
                        null
                    } else {
                        series[vod()!!.playIndex]
                    }
                    val playTitle = vod()!!.name + if (vs == null) "" else " " + vs.name
                    view?.showTip(str(R.string.player_call_external_play, PlayerHelper.getPlayerName(playerType)), true, false)
                    val progress = getSavedProgress(progressKey())
                    val callResult = view?.playExternalPlayer(
                        playerType, targetUrl, playTitle, playSubtitle(), headers, progress
                    ) ?: false
                    view?.showTip(
                        str(
                            R.string.player_call_external_result,
                            PlayerHelper.getPlayerName(playerType),
                            if (callResult) str(R.string.common_success) else str(R.string.common_failed)
                        ),
                        callResult,
                        !callResult
                    )
                    return@Runnable
                }
            } catch (e: JSONException) {
                LOG.e("PlaybackController", e)
            }
            setPlayTimeoutBasePosition(getSavedProgress(progressKey()))
            val forceExoPlayer = targetUrl.startsWith("data:application/dash+xml;base64,")
                || targetUrl.contains(".mpd") || targetUrl.contains("type=mpd")
            if (targetUrl.startsWith("data:application/dash+xml;base64,")) {
                view?.applyPlayerConfigToView(2)
                App.getInstance()!!.setDashData(targetUrl.split("base64,")[1])
                targetUrl = ControlManager.get().getAddress(true) + "dash/proxy.mpd"
            } else if (targetUrl.contains(".mpd") || targetUrl.contains("type=mpd")) {
                view?.applyPlayerConfigToView(2)
            } else {
                view?.applyPlayerConfigToView(0)
            }
            // 纯音频 URL 预判:音乐直链没有视频帧,SurfaceView 会"洞穿"应用窗口(任务快照变白/回前台透视桌面),
            // 改用 TextureView 起播补住「起播 → 轨道信息就绪」的空窗;误判无功能损失(详见 MyVideoView.switchRenderToTexture)。
            if (looksLikeAudioUrl(targetUrl)) {
                view?.useTextureRenderForAudio()
            }
            view?.startVideoPlayback(targetUrl, headers, forceExoPlayer)
        })
    }

    /** 本地代理地址补 siteKey(播放侧代理需要它定位源) */
    private fun attachProxySiteKey(url: String): String {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(sourceKey())) return url
        if (!url.startsWith(ControlManager.get().getAddress(true) + "proxy?")) return url
        if (url.contains("siteKey=")) return url
        return try {
            url + (if (url.contains("?")) "&" else "?") + "siteKey=" + URLEncoder.encode(sourceKey(), "UTF-8")
        } catch (th: Throwable) {
            url + (if (url.contains("?")) "&" else "?") + "siteKey=" + sourceKey()
        }
    }

    private val preload: PlaybackPreload = PlaybackPreload(object : PlaybackPreload.Host {
        override fun view(): PlaybackViewBridge? = this@PlaybackController.view

        override fun sourceViewModel(): SourceViewModel? = fetch.sourceViewModel()

        override fun ensureFetch() {
            initFetch()
        }

        override fun onPreloadedResult(info: JSONObject?) {
            st.usedPreloadedResult = true
            fetch.deliver(info)
        }
    })

    /** 建立预载协调器与"下一集已就绪"回调(页面 init 时调用一次,须在 initFetch 之后) */
    fun initPreload() {
        preload.init()
    }

    /**
     * 播放状态变化驱动预载评估(页面状态回调里调用):STATE_PLAYING 延迟评估、STATE_BUFFERING 让路、
     * STATE_BUFFERED 补一次评估(dkplayer 的 STATE_PLAYING 只在首帧发一次,不补枪则拖一次进度条就永久停摆)。
     */
    fun onPlayerStateForPreload(playState: Int) {
        preload.onPlayerState(playState)
    }

    /** 切集/换线/换源/重播:作废在途预解析与预载数据(稳定播放后重新评估) */
    fun invalidatePreload() {
        preload.invalidate()
    }

    /** 页面销毁:停协调器 + 注销就绪回调(防页面销毁后回调/Toast 残留) */
    fun destroyPreload() {
        preload.destroy()
    }

    private val music: MusicSessionDelegate = MusicSessionDelegate(object : MusicSessionDelegate.Host {
        override fun view(): PlaybackViewBridge? = this@PlaybackController.view

        override fun attemptState(): PlaybackAttemptState = st

        override fun timeouts(): PlaybackTimeouts = this@PlaybackController.timeouts

        override fun vod(): VodInfo? = this@PlaybackController.vod

        override fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries? =
            this@PlaybackController.currentSeries(flag, index)

        override fun quality(): JSONObject? = qualityResult

        override fun isStartedPlayState(state: Int): Boolean = this@PlaybackController.isStartedPlayState(state)

        override fun retryAfterStartedError(): Boolean = this@PlaybackController.retryAfterStartedError()

        override fun initParse(flag: String?, useParse: Boolean, playUrl: String, url: String) {
            this@PlaybackController.initParse(flag, useParse, playUrl, url)
        }

        override fun playUrl(url: String, headers: HashMap<String, String>?) {
            this@PlaybackController.playUrl(url, headers)
        }
    })

    fun beginSwitchPlayback() {
        music.beginSwitchPlayback()
    }

    fun playArtwork(): String? = music.playArtwork()

    fun currentArtwork(): String? = music.currentArtwork()

    fun playDanmu(): String? = music.playDanmu()

    fun setPlayDanmu(danmu: String?) {
        music.setPlayDanmu(danmu)
    }

    fun cancelInFlight() {
        cancelPlayTimeout()
        cancelSwitchLinePlayTimeout()
        cancelResolvePlayUrlTimeout()
        fetch.cancelPlayRequest()
        stopParse()
    }

    fun stopPlaybackForPageExit() {
        st.clearSessionFlags()
        // 与 onHostDestroy 同属会话边界:一并作废"播完待撤会话"的待判消息
        timeouts.cancelPendingCompletionDrop()
        cancelInFlight()
        // 页面退出即"没有正在播的源"
        ApiConfig.get().setCurrentPlaySourceKey("")
        music.stopMusicSession()
    }

    fun stopMusicSessionForFailedPlayback() {
        music.stopMusicSessionForFailedPlayback()
    }

    fun stopMusicSession() {
        music.stopMusicSession()
    }

    /** 页面销毁:清会话标记 + 停通知 + 收预载 */
    fun onHostDestroy() {
        st.clearSessionFlags()
        timeouts.cancelPendingCompletionDrop()
        // 引擎已释放:三处超时消息若留着,到期仍会走"换线/报错"链路并打到视图桥(见 detach 的桥切换)
        cancelPlayTimeout()
        cancelResolvePlayUrlTimeout()
        stopParse()
        music.stopMusicSession()
        destroyPreload()
    }

    fun isConfirmedAudioOnly(): Boolean {
        return music.isConfirmedAudioOnly()
    }

    fun handlePlayStateForMusicSession(playState: Int): Boolean {
        return music.handlePlayStateForMusicSession(playState)
    }

    fun ensureAudioOnlyRender() {
        music.ensureAudioOnlyRender()
    }

    fun updateMusicSession() {
        music.updateMusicSession()
    }

    fun selectQuality(position: Int): Boolean {
        return music.selectQuality(position)
    }

    companion object {

        /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
        @JvmStatic
        fun str(resId: Int, vararg args: Any?): String {
            val app = App.getInstance()
            return if (app == null) "" else LanguageManager.localized(app).getString(resId, *args)
        }

        /** 提取播放请求头:与预载侧共用 `PlayerHelper.extractPlayHeaders`,两侧逐字一致才满足预载读盘守卫,否则预缓存不命中 */
        @JvmStatic
        fun extractHeaders(playResult: JSONObject?): HashMap<String, String>? {
            return PlayerHelper.extractPlayHeaders(playResult)
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun putHeaders(target: JSONObject?, headers: HashMap<String, String>?) {
            if (target == null || headers == null) return
            for (key in headers.keys) {
                target.put(key, headers[key])
            }
        }

        @JvmStatic
        fun headerValue(headers: HashMap<String, String>?, name: String?): String? {
            if (headers == null || name == null) return null
            for (key in headers.keys) {
                if (name.equals(key, ignoreCase = true)) {
                    return headers[key]
                }
            }
            return null
        }

        @JvmStatic
        fun looksLikeAudioUrl(url: String?): Boolean {
            if (url == null || url.isEmpty()) return false
            var lower = url.lowercase(Locale.getDefault())
            val query = lower.indexOf('?')
            if (query >= 0) lower = lower.substring(0, query)
            val fragment = lower.indexOf('#')
            if (fragment >= 0) lower = lower.substring(0, fragment)
            return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac")
                || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ogg")
                || lower.endsWith(".oga") || lower.endsWith(".opus") || lower.endsWith(".wma")
        }
    }
}
