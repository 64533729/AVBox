package com.github.tvbox.osc.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.dlna.CastVideo
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.AppPlayerView
import com.github.tvbox.osc.player.ExoPlayer
import com.github.tvbox.osc.player.KernelPlayer
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.PageHost
import com.github.tvbox.osc.player.PlaybackController
import com.github.tvbox.osc.player.PlaybackEngine
import com.github.tvbox.osc.player.PlaybackHostApi
import com.github.tvbox.osc.player.PlaybackPage
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.PlaybackSession
import com.github.tvbox.osc.player.PlaybackViewBridge
import com.github.tvbox.osc.player.PreloadCoordinator
import com.github.tvbox.osc.player.TrackInfo
import com.github.tvbox.osc.player.TrackInfoBean
import com.github.tvbox.osc.player.controller.ComposeVideoController
import com.github.tvbox.osc.player.controller.PlayerControlApi
import com.github.tvbox.osc.player.danmu.DanmuLoadController
import com.github.tvbox.osc.player.state.CastSheetState
import com.github.tvbox.osc.player.state.DanmuSearchSheetState
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.player.state.SelectDialogState
import com.github.tvbox.osc.player.state.SubtitleSearchSheetState
import com.github.tvbox.osc.player.state.SubtitleSheetState
import com.github.tvbox.osc.sourcedata.SubtitleViewModel
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.PlayerHelper
import com.github.tvbox.osc.util.SubtitleHelper
import com.github.tvbox.osc.util.TrackMemory
import master.flame.danmaku.ui.widget.DanmakuView
import me.jessyan.autosize.AutoSize
import me.jessyan.autosize.internal.CustomAdapt
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
import java.io.File
import java.util.HashMap

/**
 * 点播播放容器(M7e 起为 Kotlin):渲染宿主槽位、控制层挂载与字幕/弹幕/投屏接线。
 *
 * <p>移植口径 = 纯语言迁移,逐行等价。Kotlin 侧的形态差异:
 * ① Java 里返回 `kotlin.Unit` 的匿名 lambda(字幕/弹幕/选轨/投屏回调)改写成 Kotlin lambda,
 *   其中的早退点按"取反 + 嵌套"表达(lambda 内不能非局部 return),判定与早退点逐条保留;
 * ② Java 侧从不赋 null 的字段(`mController`/`scheduler`/`surfaceSlot`)落成 `lateinit`(保持非空类型):
 *   同包 Kotlin 调用方 [PlayContainerViewBridge]/[PlayContainerControlListener] 按非空直接解引用,
 *   放宽成可空会让它们编译不过;
 * ③ 可空字段(`mVideoView`/`mActivity`/`pageHost`/`engine`/`danmuLoadController`/`mHandler`/`mDanmuView`)
 *   按 Java 的判空形态逐点对齐:Java 有判空的走 `?.`,裸解引用保留 `!!`(复刻 Java 同路径的 NPE);
 * ④ `x == null ? a : x.y` 收敛成 `x?.y ?: a`(回落值与 Java 的 `a` 一致);
 * ⑤ 内部类 [MyWebView] 仍是 `inner class`(视图桥以 `container.MyWebView(...)` 构造);
 * ⑥ `@Subscribe(threadMode = ThreadMode.MAIN)` 的 [refresh] 保持 public(EventBus 反射调用)。
 */
class PlayContainer(activity: Activity) : FrameLayout(activity), CustomAdapt, PlaybackHostApi, PlaybackPage {

    private val trackSelector: TrackSelectorDelegate = TrackSelectorDelegate(object : TrackSelectorDelegate.Host {
        override fun player(): MyVideoView? = mVideoView

        override fun context(): Context = mContext

        override fun uiState(): PlayerUiState = mController.getUiState()
    })

    lateinit var scheduler: PlaybackController
    private lateinit var surfaceSlot: FrameLayout
    private var engine: PlaybackEngine? = null
    /**
     * 页面能力宿主(由 `DetailActivity` 经 [setPageHost] 注入)。
     *
     * ⚠️ 属性名带 `m` 前缀:显式 [setPageHost] 与属性 setter 会撞 JVM 签名(`setPageHost(PageHost)`)。
     */
    var mPageHost: PageHost? = null
    var mActivity: Activity? = activity

    private val mContext: Context = activity

    /** 存入字段而不是每次写 lambda:hostDestroy 要按"是不是自己"摘监听 */
    private val tipStateListener: TipStateListener = TipStateListener { onTipStateChanged(it) }

    /** 详情页选集面板显隐(面板状态在 DetailViewModel,这里只做投影,供底栏冻结自动收起用) */
    private val viewBridge: PlaybackViewBridge = PlayContainerViewBridge(this)

    /** 控制器回调:切解码重播等复用路径要直接触发,故存字段 */
    private val controlListener: PlayContainerControlListener = PlayContainerControlListener(this)

    private var qualitySelectedListener: OnQualitySelectedListener? = null

    private var lifecyclePaused: Boolean = false
    private var ownedPlaybackKey: String? = null
    private var handedOver: Boolean = false

    var mVideoView: MyVideoView? = null
    lateinit var mController: PlayerControlApi
    private var preloadReadyToast: Toast? = null
    private var mHandler: Handler? = null
    /**
     * "本次播放是否由预览态退出"侧写标记(由 [setExitingPreview] 写)。
     *
     * ⚠️ 属性名带 `m` 前缀:同上,显式 [setExitingPreview] 会与属性 setter 撞签名。
     */
    var mExitingPreview: Boolean = false
    private var previewMode: Boolean = false
    private var mDanmuView: DanmakuView? = null
    var danmuLoadController: DanmuLoadController? = null
    private val exoCues: MutableList<Cue> = ArrayList()
    private var exoInternalSubtitle: Boolean = false

    /** 字幕决定代际:用户每次选字幕/每轮起播决策自增;在途的在线字幕解析只在这期间没变时才允许落地 */
    private var subtitleDecisionSeq: Int = 0

    private val videoDuration: Long = -1

    private val refreshPreloadToastRunnable: Runnable = Runnable {
        if (preloadReadyToast != null) preloadReadyToast!!.show()
    }

    init {
        engine = PlaybackService.engine(activity)
        scheduler = engine!!.controller()
        AutoSize.autoConvertDensity(activity, getSizeInDp(), isBaseOnWidth())
        LayoutInflater.from(activity).inflate(R.layout.view_play_container, this, true)
        PlayerTipBridge.hide()
        init()
        // 提示层(加载/错误遮罩)画在控制器 Compose 层:状态要桥进控制层,并收起位置在控制器之上的弹幕视图。
        // 挂监听在 init() 之后(mController/danmuLoadController 已就位)与 hide() 之后(免旧容器残留回调)
        PlayerTipBridge.setTipStateListener(tipStateListener)
        scheduler.setViewBridge(viewBridge)
        if (engine != null) engine!!.attach(this)
    }

    /** 提示层状态变化:桥入控制层状态(遮罩在视频面之上、顶栏/底栏之下),并让弹幕视图让位 */
    private fun onTipStateChanged(tip: PlayerTipState) {
        if (mHandler == null) return
        val showing = tip.loading || tip.err
        // 提示可能由调度/取流线程写入(setTip 会从解析链路直接调用),控制层状态与弹幕视图可见性统一回主线程
        mHandler?.post {
            if (mController != null) {
                mController.getUiState().applyTip(tip.msg, tip.loading, tip.err)
            }
            if (danmuLoadController != null) danmuLoadController?.setOverlayHidden(showing)
        }
    }

    override fun viewBridge(): PlaybackViewBridge {
        return viewBridge
    }

    override fun renderSlot(): ViewGroup {
        return surfaceSlot
    }

    override fun onServiceStopped() {
        mVideoView = null
        engine = null
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    fun isAttached(): Boolean {
        if (mPageHost != null) return mPageHost!!.isPageAlive()
        return mActivity != null && !mActivity!!.isFinishing
    }

    fun setPageHost(host: PageHost) {
        mPageHost = host
    }

    /** 详情页选集面板显隐(面板状态在 DetailViewModel,这里只做投影,供底栏冻结自动收起用) */
    fun setEpisodeSheetOpen(open: Boolean) {
        if (mController != null) mController.getUiState().episodeSheetOpen = open
    }

    /** 清晰度切换结果回调:仅在受理后回调一次,与 `selectQuality` 同线程返回;页面必须从主线程调它 */
    fun interface OnQualitySelectedListener {
        fun onQualitySelected(position: Int)
    }

    fun setOnQualitySelectedListener(listener: OnQualitySelectedListener) {
        qualitySelectedListener = listener
    }

    override fun hostResume() {
        mExitingPreview = false
        if (mController != null) mController.setLifecyclePaused(false)
        reattachIfOwnedByOther()
        if (mVideoView != null && lifecyclePaused) {
            lifecyclePaused = false
            if (ownsEngineContent()) {
                mVideoView!!.resume()
            }
        }
    }

    private fun reattachIfOwnedByOther() {
        if (engine == null || surfaceSlot == null) return
        if (engine!!.attachedPage() === this) return
        if (engine!!.isReleased()) return
        if (engine!!.isLiveMode()) engine!!.exitLive()
        engine!!.attach(this)
        // 重新接管后本页恢复"退出即停播"的职责:交接标记是给"交出去后本页就销毁"准备的,
        // 音乐页返回(影视内容)这条路径本页仍存活,不清掉会让 hostDestroy 漏掉 detach —— 退出后声音不停
        handedOver = false
        if (!ownsEngineContent() && mVideoView != null) {
            // 内核内容已被别的页面换走(或对方尚未销毁):它的进度只有 detach 落盘这一个时点,
            // 而那次落盘可能晚于本页新起播改写 progressKey —— 接管时先按现键存一次,两边时序就都无害了
            mVideoView!!.saveCurrentProgress()
        }
        if (mVideoView != null && mController != null) {
            mController.setKernelProvider(mVideoView)
            val state = mVideoView!!.currentPlayState
            if (mVideoView!!.mediaPlayer != null
                && state != AppPlayerView.STATE_IDLE && state != AppPlayerView.STATE_ERROR
                && ownsEngineContent()
            ) {
                rebindPlaybackOverlay()
            }
        }
        if (ownsEngineContent()) {
            // 接管的是引擎里既有的会话(直播回切/音乐页交还),页面自己没走过 setData,数据要在这里补同步
            syncSessionVod()
        }
        LOG.i("echo-p4 re-attach after live/other page")
    }

    override fun hostPause() {
        if (mVideoView != null && !mExitingPreview && !scheduler.isConfirmedAudioOnly()) {
            // 传 isPlaying() 而非恒 true:标记语义 = 回前台会续播(与 hostResume 同一判据),手动暂停后离开须为 false
            lifecyclePaused = mVideoView!!.isPlaying
            if (mController != null) mController.setLifecyclePaused(lifecyclePaused)
            mVideoView!!.pause()
        }
    }

    /** 交给音乐播放页接管:引擎摘视图但不停播,随后的 hostDestroy 不得再 detach(会停掉刚交接的音频) */
    fun handOverToNextPage() {
        if (engine == null) return
        handedOver = true
        engine!!.detachForHandover(this)
    }

    override fun hostDestroy() {
        LOG.i("echo-music destroy: hostDestroy enter")
        PlayerTipBridge.clearTipStateListener(tipStateListener)
        // 页面回调随页面一起摘掉,不留方法引用
        qualitySelectedListener = null
        if (engine != null && !handedOver) engine!!.detach(this)
        cancelPreloadToast()
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        trackSelector.invalidatePendingSwitch()
        if (danmuLoadController != null) {
            danmuLoadController!!.destroy()
            danmuLoadController = null
        }
        mVideoView = null
        if (mController != null) mController.stopOther()
        mActivity = null
        LOG.i("echo-music destroy: hostDestroy done")
    }

    override fun getSizeInDp(): Float {
        return if (mActivity is CustomAdapt) (mActivity as CustomAdapt).getSizeInDp() else 0f
    }

    override fun isBaseOnWidth(): Boolean {
        return mActivity !is CustomAdapt || (mActivity as CustomAdapt).isBaseOnWidth()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE) {
            applySubtitleTextSize()
        }
        if (event.type == RefreshEvent.TYPE_SET_DANMU_SETTINGS) {
            setDanmuViewSettings(event.obj is Boolean && event.obj as Boolean)
        } else if (event.type == RefreshEvent.TYPE_DANMU_REFRESH) {
            checkDanmu(if (event.obj is String) event.obj as String else "")
        }
    }

    private fun init() {
        initView()
        initDanmuView()
    }

    private fun initDanmuView() {
        mDanmuView = findViewById(R.id.danmaku)
        danmuLoadController = DanmuLoadController(mVideoView, mController, mDanmuView)
    }

    private fun setDanmuViewSettings(reload: Boolean) {
        if (danmuLoadController != null) danmuLoadController!!.applySettings(reload)
    }

    fun applyDanmuSettings(reload: Boolean) {
        setDanmuViewSettings(reload)
    }

    private fun checkDanmu(danmu: String?) {
        checkDanmu(danmu, null)
    }

    fun checkDanmu(danmu: String?, callback: DanmuLoadController.LoadCallback?) {
        scheduler.setPlayDanmu(danmu)
        if (danmuLoadController != null) {
            val series = if (scheduler.vod() == null) null else scheduler.currentSeries(scheduler.vod()!!.playFlag, scheduler.vod()!!.playIndex)
            danmuLoadController!!.check(danmu, scheduler.vod()?.name ?: "", series?.name ?: "", callback)
        }
    }

    fun startDanmuIfReady() {
        if (danmuLoadController != null) danmuLoadController!!.startIfReady()
    }

    fun resetDanmuState() {
        if (danmuLoadController != null) danmuLoadController!!.reset()
    }

    fun reloadDanmuForPlayback() {
        if (danmuLoadController != null) danmuLoadController!!.reloadForPlayback()
    }

    private fun initView() {
        EventBus.getDefault().register(this)
        mHandler = Handler { msg ->
            when (msg.what) {
                MSG_PARSE_TIMEOUT -> {
                    scheduler.stopParse()
                    errorWithRetry(mContext.getString(R.string.player_error_sniff), false)
                }
            }
            false
        }
        surfaceSlot = findViewById(R.id.surfaceSlot)
        mController = ComposeVideoController(mActivity!!)

        mController.getLyricView().setTextSize(if (previewMode) 16f else 24f)
        mController.setCanChangePosition(true)
        mController.setEnableInNormal(true)
        mController.setGestureEnabled(true)
        mVideoView = if (engine == null) null else engine!!.player()
        mController.setListener(controlListener)
        // 挂载控制器:注入播放器视图并把自己注册为状态宿主(去 doikki 后取代 setVideoController)
        if (mVideoView != null) mController.setKernelProvider(mVideoView)
    }

    override fun showCast() {
        showCastDialog()
    }

    fun showCastDialog() {
        if (TextUtils.isEmpty(scheduler.webPlayUrl())) {
            Toast.makeText(mContext, mContext.getString(R.string.toast_no_cast_url), Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAttached()) return
        val headers: HashMap<String, String>? = scheduler.webHeaderMap()?.let { HashMap(it) }
        val video = CastVideo(scheduler.getCastUrl(scheduler.webPlayUrl())!!, getCastTitle(), headers, getCastPosition())
        val uiState = mController.getUiState()
        uiState.castSheet = CastSheetState(video) {
            if (mVideoView != null) mVideoView!!.pause()
        }
    }

    fun openDanmuSearchSheet() {
        if (!isAttached()) return
        val series = if (scheduler.vod() == null) null else scheduler.currentSeries(scheduler.vod()!!.playFlag, scheduler.vod()!!.playIndex)
        val uiState = mController.getUiState()
        uiState.danmuSearchSheet = DanmuSearchSheetState(
            series?.name ?: "",
            scheduler.vod()?.name ?: "",
        ) { danmu ->
            if (isAttached()) {
                checkDanmu(danmu)
            }
        }
    }

    /**
     * 把引擎当前会话的影片数据同步给控制层:选集入口可见性由它派生 ——
     * 同片接管(退出页面后快速重进)与页面重新接管都不走 prepare,只在 prepare 时计算会漏掉这些会话。
     */
    private fun syncSessionVod() {
        if (mController == null || scheduler == null) return
        mController.getUiState().sessionVod = scheduler.vod()
    }

    private fun getCastTitle(): String {
        if (scheduler.vod() == null) return "TVBox"
        try {
            val series = scheduler.vod()!!.seriesMap!![scheduler.vod()!!.playFlag]!![scheduler.vod()!!.playIndex]
            return scheduler.vod()!!.name + " " + series.name
        } catch (e: Exception) {
            return if (TextUtils.isEmpty(scheduler.vod()!!.name)) "TVBox" else scheduler.vod()!!.name!!
        }
    }

    private fun getCastPosition(): Long {
        try {
            return mVideoView?.currentPosition ?: 0L
        } catch (e: Exception) {
            return 0L
        }
    }

    fun setSubtitle(path: String?) {
        if (path != null && path.length > 0) {
            subtitleDecisionSeq++
            hideExoInternalSubtitle()
            mController.getSubtitleView().setVisibility(View.GONE)
            mController.getSubtitleView().setSubtitlePath(path)
            setSubtitleViewTextStyle(KV.get(HawkConfig.SUBTITLE_TEXT_STYLE, 0))
            mController.getSubtitleView().setVisibility(View.VISIBLE)
        }
    }

    fun selectMySubtitle() {
        try {
            if (!isAttached() || mVideoView == null) return
            val uiState = mController.getUiState()
            val mediaPlayer = mVideoView!!.mediaPlayer
            val hasInternal = mController.getSubtitleView().hasInternal || hasExoInternalSubtitle(mediaPlayer)
            val exoInternal = mediaPlayer is ExoPlayer && exoInternalSubtitle
            uiState.subtitleSheet = SubtitleSheetState(
                exoInternal,
                hasInternal,
                {
                    selectMyInternalSubtitle()
                },
                {
                    openLocalSubtitleChooser()
                },
                {
                    openSubtitleSearchSheet()
                },
                { style ->
                    KV.put(HawkConfig.SUBTITLE_TEXT_STYLE, style)
                    setSubtitleViewTextStyle(style)
                },
                {
                    applySubtitleTextSize()
                },
                {
                    SubtitleHelper.reset()
                    setSubtitleViewTextStyle(0)
                    applySubtitleTextSize()
                },
            )
        } catch (e: Exception) {
            LOG.e("PlayContainer", e)
        }
    }

    private fun openLocalSubtitleChooser() {
        if (mPageHost != null) mPageHost!!.launchLocalSubtitlePicker()
    }

    override fun onLocalSubtitlePicked(uri: Uri) {
        val activity = mActivity
        if (activity == null || activity.isFinishing) return
        Thread {
            try {
                var name = queryDisplayName(activity, uri)
                if (name == null || !name.contains(".")) name = "local_subtitle.srt"
                name = name.replace("[\\\\/:*?\"<>|]", "_")
                val dst = File(activity.cacheDir, "subtitle_" + System.currentTimeMillis() + "_" + name)
                activity.contentResolver.openInputStream(uri).use { input ->
                    java.io.FileOutputStream(dst).use { out ->
                        val buf = ByteArray(8192)
                        var len = 0
                        while (input!!.read(buf).also { len = it } > 0) out.write(buf, 0, len)
                    }
                }
                val path = dst.absolutePath
                activity.runOnUiThread {
                    if (!isAttached()) return@runOnUiThread
                    LOG.i("echo-Local Subtitle Path: " + path)
                    // 本地文件在整部片里通用,记进片级记忆(文件被系统清掉时按失效回落)
                    TrackMemory.saveSubtitle(trackMemoryKey(), TrackMemory.subtitleLocal(path))
                    setSubtitle(path)
                }
            } catch (e: Exception) {
                LOG.e("echo-Local Subtitle copy err: " + e)
                activity.runOnUiThread {
                    if (isAttached()) {
                        Toast.makeText(activity, activity.getString(R.string.toast_subtitle_read_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun queryDisplayName(activity: Activity, uri: Uri): String? {
        try {
            activity.contentResolver.query(uri, null, null, null, null).use { c ->
                if (c != null && c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        } catch (ignored: Exception) {
            LOG.d("PlayContainer", "query display name failed, keep null")
        }
        return null
    }

    private fun openSubtitleSearchSheet() {
        if (!isAttached()) return
        val word = if (scheduler.vod()!!.playFlag!!.contains("Ali") || scheduler.vod()!!.playFlag!!.contains("parse")) {
            scheduler.vod()!!.playNote
        } else {
            scheduler.vod()!!.name
        }
        val uiState = mController.getUiState()
        uiState.subtitleSearchSheet = SubtitleSearchSheetState(word ?: "") { subtitle, releaseUrl ->
            if (isAttached()) {
                mActivity!!.runOnUiThread {
                    val zimuUrl = subtitle.url
                    LOG.i("echo-Remote Subtitle Url: " + zimuUrl)
                    // 只记发布页 + 文件名(直链只对当集有效):换集按集号回同一发布页取本集文件
                    TrackMemory.saveSubtitle(
                        trackMemoryKey(),
                        TrackMemory.subtitleOnline(releaseUrl, subtitle.name),
                    )
                    setSubtitle(zimuUrl)
                }
            }
        }
    }

    @SuppressLint("UseCompatLoadingForColorStateLists")
    fun setSubtitleViewTextStyle(style: Int) {
        if (style == 0) {
            mController.getSubtitleView().setTextColor(context.resources.getColorStateList(R.color.color_FFFFFF))
        } else if (style == 1) {
            mController.getSubtitleView().setTextColor(context.resources.getColorStateList(R.color.color_FFB6C1))
        }
        applyExoSubtitleStyle()
    }

    fun selectMyAudioTrack() {
        trackSelector.selectAudioTrack()
    }

    fun selectMyVideoTrack() {
        trackSelector.selectVideoTrack()
    }

    fun selectMyInternalSubtitle() {
        if (mVideoView == null) return
        val mediaPlayer = mVideoView!!.mediaPlayer
        var trackInfo: TrackInfo? = null
        if (mediaPlayer is ExoPlayer) {
            trackInfo = mediaPlayer.getTrackInfo()
        }
        if (trackInfo == null) {
            Toast.makeText(mContext, mContext.getString(R.string.player_no_internal_subtitle), Toast.LENGTH_SHORT).show()
            return
        }
        val bean = trackInfo.getSubtitle()
        if (bean.size < 1) return
        val names = ArrayList<String>()
        for (item in bean) names.add(item.name!!)
        mController.getUiState().selectDialog = SelectDialogState(
            mContext.getString(R.string.player_switch_internal_subtitle),
            names,
            trackInfo.getSubtitleSelected(false),
        ) { pos ->
            if (pos >= 0 && pos < bean.size) {
                val value = bean[pos]
                try {
                    // 在途的在线字幕解析作废:别让它回头盖掉用户这一手
                    subtitleDecisionSeq++
                    for (subtitle in bean) {
                        subtitle.selected = TrackSelectorDelegate.isSameTrack(subtitle, value)
                    }
                    if (mediaPlayer is ExoPlayer) {
                        mController.getSubtitleView().setVisibility(View.GONE)
                        mController.getSubtitleView().destroy()
                        mController.getSubtitleView().clearSubtitleCache()
                        mController.getSubtitleView().isInternal = false
                        exoInternalSubtitle = true
                        mediaPlayer.setTrack(value)
                        mediaPlayer.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay())
                        mController.getExoSubtitleView().setVisibility(View.VISIBLE)
                        applyExoSubtitleSettings()
                    }
                } catch (e: Exception) {
                    LOG.e("echo-switch-internal-subtitle-error:" + e.message)
                }
            }
        }
    }

    private fun hasExoInternalSubtitle(mediaPlayer: KernelPlayer?): Boolean {
        if (mediaPlayer !is ExoPlayer) return false
        val trackInfo = mediaPlayer.getTrackInfo()
        return !trackInfo.getSubtitle().isEmpty()
    }

    private fun hideExoInternalSubtitle() {
        exoInternalSubtitle = false
        exoCues.clear()
        if (mController != null && mController.getExoSubtitleView() != null) {
            mController.getExoSubtitleView().setCues(exoCues)
            mController.getExoSubtitleView().setVisibility(View.GONE)
        }
    }

    private fun onExoCues(cues: List<Cue>?) {
        if (!isAttached() || !exoInternalSubtitle) return
        exoCues.clear()
        if (cues != null) exoCues.addAll(cues)
        mActivity!!.runOnUiThread {
            applyExoSubtitleSettings()
        }
    }

    private fun applyExoSubtitleSettings() {
        if (!exoInternalSubtitle || mController == null || mController.getExoSubtitleView() == null) return
        applyExoSubtitleStyle()
        val scale = SubtitleHelper.getExoSubtitleScale() / 100f
        val position = SubtitleHelper.getExoSubtitlePosition()
        mController.getExoSubtitleView().setFractionalTextSize(0.0533f * scale)
        mController.getExoSubtitleView().setBottomPaddingFraction(limit(0.08f + position / 100f, 0f, 0.9f))

        val displayCues = ArrayList<Cue>()
        for (cue in exoCues) {
            if (cue.bitmap == null) {
                displayCues.add(cue)
                continue
            }
            val builder = cue.buildUpon()
            if (cue.size != Cue.DIMEN_UNSET) {
                builder.setSize(limit(cue.size * scale, 0f, 1f))
            }
            if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
                builder.setBitmapHeight(limit(cue.bitmapHeight * scale, 0f, 1f))
            }
            if (cue.line != Cue.DIMEN_UNSET) {
                builder.setLine(limit(cue.line - position / 100f, 0f, 1f), cue.lineType)
            }
            displayCues.add(builder.build())
        }
        mController.getExoSubtitleView().setCues(displayCues)
    }

    private fun applyExoSubtitleStyle() {
        if (mController == null || mController.getExoSubtitleView() == null) return
        val style = KV.get(HawkConfig.SUBTITLE_TEXT_STYLE, 0)
        val textColor = context.resources.getColorStateList(
            if (style == 1) R.color.color_FFB6C1 else R.color.color_FFFFFF,
        ).defaultColor
        mController.getExoSubtitleView().setStyle(
            CaptionStyleCompat(
                textColor,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                Typeface.DEFAULT_BOLD,
            ),
        )
    }

    private fun limit(value: Float, min: Float, max: Float): Float {
        return Math.max(min, Math.min(max, value))
    }

    fun setTip(msg: String, loading: Boolean, err: Boolean) {
        if (!isAttached()) return
        PlayerTipBridge.setTip(msg, loading, err)
    }

    fun hideTip() {
        PlayerTipBridge.hide()
    }

    fun hideTipOnUiThread() {
        if (!isAttached()) return
        PlayerTipBridge.hide()
    }

    fun showPreloadReady() {
        val activity = mActivity
        if (activity == null || !isAttached() || mHandler == null) return
        if (preloadReadyToast != null) preloadReadyToast!!.cancel()
        preloadReadyToast = Toast.makeText(activity, activity.getString(R.string.player_next_episode_ready), Toast.LENGTH_SHORT)
        preloadReadyToast!!.show()
        mHandler!!.removeCallbacks(refreshPreloadToastRunnable)
        mHandler!!.postDelayed(refreshPreloadToastRunnable, PRELOAD_TOAST_REFRESH_DELAY_MS)
    }

    fun hidePreloadReady() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelPreloadToast()
        } else if (mActivity != null) {
            mActivity!!.runOnUiThread {
                cancelPreloadToast()
            }
        }
    }

    private fun cancelPreloadToast() {
        if (mHandler != null) mHandler!!.removeCallbacks(refreshPreloadToastRunnable)
        if (preloadReadyToast != null) {
            preloadReadyToast!!.cancel()
            preloadReadyToast = null
        }
    }

    /**
     * 回调线程可能不是主线程:本方法会走"释放内核 + 重起播"这条**增删播放器子视图**的链路,必须整段在主线程,
     * 非主线程增删子视图会让 `ViewGroup.mChildren` 出 null 洞(下次 traversal 崩)——不能只把提示文案 post 出去。
     */
    fun errorWithRetry(err: String, finish: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mHandler!!.post { errorWithRetry(err, finish) }
            return
        }
        if (scheduler.isPlaybackStarted()) {
            scheduler.cancelPlayTimeout()
            hideTipOnUiThread()
            if (scheduler.retryAfterStartedError()) return
            scheduler.stopMusicSessionForFailedPlayback()
            if (!isAttached()) return
            setTip(err, false, true)
            if (finish) {
                Toast.makeText(mContext, err, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!scheduler.autoRetry()) {
            scheduler.stopMusicSessionForFailedPlayback()
            if (!isAttached()) return
            setTip(err, false, true)
            if (finish) {
                Toast.makeText(mContext, err, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun initSubtitleView() {
        if (mVideoView == null) return
        var trackInfo: TrackInfo? = null
        val mediaPlayer = mVideoView!!.mediaPlayer
        mController.getLyricView().setTextSize(if (previewMode) 16f else 24f)
        applySubtitleTextSize()
        mController.getLyricView().setVisibility(View.GONE)
        mController.getLyricView().reset()
        mController.getLyricView().bindToMediaPlayer(mediaPlayer)
        mController.getLyricView().setMergeSameTime(true)
        mController.getLyricView().setLyricMode(true)
        mController.getLyricView().setPlaySubtitleCacheKey(scheduler.lyricCacheKey())
        mController.getSubtitleView().hasInternal = false
        mController.getSubtitleView().isInternal = false
        hideExoInternalSubtitle()
        val memoryKey = trackMemoryKey()
        if (mediaPlayer is ExoPlayer) {
            mediaPlayer.setContentKey(memoryKey)
            trackInfo = mediaPlayer.getTrackInfo()
            if (trackInfo != null && !trackInfo.getSubtitle().isEmpty()) {
                mController.getSubtitleView().hasInternal = true
                exoInternalSubtitle = true
                mController.getExoSubtitleView().setVisibility(View.VISIBLE)
                mediaPlayer.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay())
                mediaPlayer.setOnCuesListener { cues -> onExoCues(cues) }
                applyExoSubtitleSettings()
            }
            mediaPlayer.restoreTracks()
        }
        // 歌词来源:内联 data: 在内存里、毫秒级;URL 歌词优先吃本集缓存,否则每次起播都要走网络(快慢全看源站,慢链还要等满 10s 超时)
        val lyric = scheduler.playLyric()
        var lyricPath = lyric
        if (TextUtils.isEmpty(lyric) || !lyric!!.startsWith("data:")) {
            val cachedLyric = cachedPlayPath(scheduler.lyricCacheKey())
            if (!TextUtils.isEmpty(cachedLyric)) lyricPath = cachedLyric
        }
        if (!TextUtils.isEmpty(lyricPath)) {
            mController.getLyricView().setSubtitlePath(lyricPath)
            mController.getLyricView().setVisibility(View.VISIBLE)
        }
        mController.getSubtitleView().bindToMediaPlayer(mVideoView!!.mediaPlayer)
        mController.getSubtitleView().setPlaySubtitleCacheKey(scheduler.subtitleCacheKey())
        applySubtitleDecision(mediaPlayer, trackInfo)
    }

    /**
     * 字幕决策:本片记忆(用户显式选择)优先,其次本集缓存 → 源站字幕 → 内置默认。
     *
     * <p>显式选择压过源站每集给的字幕(点过来源就是明确意图);任一步拿不到就落到默认链,不新增"没字幕"的空档。
     */
    private fun applySubtitleDecision(mediaPlayer: KernelPlayer?, trackInfo: TrackInfo?) {
        val memoryKey = trackMemoryKey()
        // 新一轮决策:上一轮在途的在线字幕解析作废
        subtitleDecisionSeq++
        val record = TrackMemory.loadSubtitle(memoryKey)
        if (TrackMemory.isSubtitleOff(record)) {
            closeSubtitleViews()
            return
        }
        if (TrackMemory.isSubtitleLocal(record)) {
            val path = TrackMemory.localPath(record)
            if (!TextUtils.isEmpty(path) && File(path).exists()) {
                setSubtitle(path)
                return
            }
            LOG.i("echo-track-memory local subtitle gone, fallback: " + path)
        } else if (TrackMemory.isSubtitleOnline(record)) {
            val player = mediaPlayer
            val info = trackInfo
            resolveRememberedOnlineSubtitle(memoryKey, record) {
                applyDefaultSubtitle(player, info)
            }
            return
        } else if (TrackMemory.isSubtitleTrack(record) && mController.getSubtitleView().hasInternal) {
            // 轨道已由播放器按指纹还原(定位失败也会退默认选轨),这里只补"内置字幕在显示"的视图状态
            showInternalSubtitle(mediaPlayer)
            return
        }
        applyDefaultSubtitle(mediaPlayer, trackInfo)
    }

    /** 无记忆(或记忆失效)时的既有链路:本集缓存 → 源站字幕 → 内置字幕 */
    private fun applyDefaultSubtitle(mediaPlayer: KernelPlayer?, trackInfo: TrackInfo?) {
        val subtitlePathCache = cachedPlayPath(scheduler.subtitleCacheKey())
        if (!subtitlePathCache.isEmpty()) {
            hideExoInternalSubtitle()
            mController.getSubtitleView().setSubtitlePath(subtitlePathCache)
            return
        }
        if (scheduler.playSubtitle() != null && scheduler.playSubtitle()!!.length > 0) {
            hideExoInternalSubtitle()
            mController.getSubtitleView().setSubtitlePath(scheduler.playSubtitle())
            return
        }
        if (!mController.getSubtitleView().hasInternal) return
        ensureInternalSubtitleTrackSelected(mediaPlayer, trackInfo)
        showInternalSubtitle(mediaPlayer)
    }

    /** 让内置字幕显示出来(选哪条轨由播放器负责,这里只管视图与延时) */
    private fun showInternalSubtitle(mediaPlayer: KernelPlayer?) {
        if (mediaPlayer is ExoPlayer) {
            mediaPlayer.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay())
            exoInternalSubtitle = true
            mController.getExoSubtitleView().setVisibility(View.VISIBLE)
            applyExoSubtitleSettings()
        }
    }

    /**
     * 补一次默认内置选轨。
     *
     * <p>只在"外挂字幕落地失败回落"这条路上需要:那时播放器一条内置轨都没选过(EXO 只自动选带 DEFAULT
     * 标记的轨),光把视图置为显示态会得到整集无字幕。
     * ⚠️ 不要在"按指纹还原"那条分支上加这个调用:EXO 的 getCurrentTracks 读不到刚下发到播放线程的
     * setParameters,会把刚还原好的用户选择当成"没选",再顶成默认轨。
     */
    private fun ensureInternalSubtitleTrackSelected(mediaPlayer: KernelPlayer?, trackInfo: TrackInfo?) {
        if (mediaPlayer is ExoPlayer) {
            mediaPlayer.ensureSubtitleTrackSelected()
        }
    }

    /**
     * 还原"在线字幕"选择:同一发布页里按集号找本集文件,取不到就回落默认链(直链只对当集有效,入库的是发布页)。
     *
     * <p>发布页 + 直链是两跳异步请求,回来时可能已换集/换源/用户自己选过字幕,故落地前必须过
     * [isSubtitleResultCurrent] 的三道守卫。
     */
    private fun resolveRememberedOnlineSubtitle(memoryKey: String, record: String?, fallback: Runnable) {
        val releaseUrl = TrackMemory.onlineRelease(record)
        // ViewModel 挂在宿主 Activity 上(与字幕面板同一实例);拿不到就回落,不猜
        if (TextUtils.isEmpty(releaseUrl) || mActivity !is ViewModelStoreOwner) {
            runOnUi(fallback)
            return
        }
        val series = if (scheduler.vod() == null) null
        else scheduler.currentSeries(scheduler.vod()!!.playFlag, scheduler.vod()!!.playIndex)
        val episodeName = series?.name ?: ""
        val fileNameHint = TrackMemory.onlineFileName(record)
        val episodeKey = scheduler.progressKey()
        val decisionSeq = subtitleDecisionSeq
        LOG.i("echo-track-memory online subtitle: release=" + releaseUrl + " episode=" + episodeName)
        ViewModelProvider(mActivity as ViewModelStoreOwner).get(SubtitleViewModel::class.java).pickEpisodeSubtitle(
            releaseUrl, episodeName, fileNameHint,
            { subtitle ->
                runOnUi {
                    if (!isSubtitleResultCurrent(memoryKey, episodeKey, decisionSeq)) return@runOnUi
                    val url = subtitle?.url
                    if (TextUtils.isEmpty(url)) { // 302 头缺失等同失败:必须回落,否则这个片永远没字幕
                        LOG.i("echo-track-memory online subtitle empty url, fallback")
                        fallback.run()
                        return@runOnUi
                    }
                    LOG.i("echo-track-memory online subtitle picked: " + subtitle.name)
                    setSubtitle(url)
                }
            },
            {
                runOnUi {
                    if (!isSubtitleResultCurrent(memoryKey, episodeKey, decisionSeq)) return@runOnUi
                    LOG.i("echo-track-memory online subtitle miss, fallback")
                    fallback.run()
                }
            },
        )
    }

    /** 在途字幕结果是否仍然有效(换源 / 换集 / 用户中途自己选过字幕 ⇒ 作废) */
    private fun isSubtitleResultCurrent(memoryKey: String, episodeKey: String?, decisionSeq: Int): Boolean {
        if (!isAttached() || subtitleDecisionSeq != decisionSeq) return false
        if (!TextUtils.equals(memoryKey, trackMemoryKey())) return false
        return TextUtils.equals(episodeKey, scheduler.progressKey())
    }

    /** 回调线程不确定,统一回 UI 线程再动视图 */
    private fun runOnUi(action: Runnable) {
        val activity = mActivity
        if (activity == null) return
        activity.runOnUiThread(action)
    }

    /** 关闭字幕视图(内置 + 外挂);歌词是独立功能(独立缓存键),不跟着关 */
    private fun closeSubtitleViews() {
        try {
            hideExoInternalSubtitle()
            mController.getSubtitleView().setVisibility(View.GONE)
            mController.getSubtitleView().destroy()
            mController.getSubtitleView().clearSubtitleCache()
            mController.getSubtitleView().isInternal = false
        } catch (e: Exception) {
            LOG.e("echo-close-subtitle-error:" + e.message)
        }
    }

    /** 长按字幕按钮:关闭全部字幕并记住"这个片不要字幕"(换集不再自动开) */
    fun closeSubtitles() {
        if (mVideoView == null) return
        closeSubtitleViews()
        subtitleDecisionSeq++
        TrackMemory.saveSubtitle(trackMemoryKey(), TrackMemory.SUBTITLE_OFF)
    }

    /** 本片记忆键;直播/无剧集信息时为空串 ⇒ 记忆读写全部跳过 */
    fun trackMemoryKey(): String {
        val vod = scheduler?.vod()
        if (vod == null) return ""
        return TrackMemory.contentKey(vod.sourceKey, vod.id)
    }

    private fun rebindPlaybackOverlay() {
        initSubtitleView()
        checkDanmu(scheduler.playDanmu())
    }

    /**
     * 某集已落盘的字幕/歌词来源:内联 data: 直接可用;本地文件要确认还在(系统可能清 /zimu/ 缓存目录,否则会静默无字幕);
     * 其余情况返回空,由调用方回退到本次起播的新地址。
     */
    private fun cachedPlayPath(cacheKey: String?): String {
        if (TextUtils.isEmpty(cacheKey)) return ""
        val cached = AppGraph.cacheRepository.get(MD5.string2MD5(cacheKey))
        if (cached !is String) return ""
        val path = cached
        if (TextUtils.isEmpty(path)) return ""
        if (path.startsWith("data:")) return path
        return if (File(path).exists()) path else ""
    }

    fun clearLyricView() {
        if (mController == null || mController.getLyricView() == null) return
        mController.getLyricView().setVisibility(View.GONE)
        mController.getLyricView().destroy()
        mController.getLyricView().setText("")
    }

    fun releasePlayerKernel() {
        if (engine != null) {
            engine!!.releasePlayer()
        } else if (mVideoView != null) {
            mVideoView!!.release()
        }
    }

    fun reviveEngineIfReleased(): Boolean {
        if (engine != null && !engine!!.isReleased()) return false
        if (mActivity == null || surfaceSlot == null) return false
        if (scheduler != null) scheduler.stopPlaybackForPageExit()
        engine = PlaybackService.engine(mActivity!!)
        scheduler = engine!!.controller()
        mVideoView = engine!!.player()
        engine!!.attach(this)
        handedOver = false
        if (mVideoView != null) {
            mController.setKernelProvider(mVideoView)
            if (danmuLoadController != null) danmuLoadController!!.setVideoView(mVideoView)
        }
        LOG.i("echo-p2 revive engine after release")
        return true
    }

    override fun play(reset: Boolean) {
        reviveEngineIfReleased()
        scheduler.play(reset)
    }

    override fun selectQuality(position: Int): Boolean {
        val accepted = scheduler != null && scheduler.selectQuality(position)
        if (accepted && qualitySelectedListener != null) qualitySelectedListener!!.onQualitySelected(position)
        return accepted
    }

    override fun setData(session: PlaybackSession) {
        if (engine == null || engine!!.isReleased()) {
            if (!reviveEngineIfReleased()) {
                LOG.i("echo-p5 setData skipped: engine released")
                return
            }
        }
        if (isSamePlaybackOwned(session)) {
            LOG.i("echo-p3 take over same playback: " + session.playbackKey())
            engine!!.setData(session)
            syncSessionVod()
            mController.setPlayerConfig(scheduler.playerCfg()!!)
            scheduler.markContentStarted()
            scheduler.publishTitle()
            scheduler.clearTriedLines()
            scheduler.setUserPickedLine(session.userPickedLine())
            rebindPlaybackOverlay()
            ownedPlaybackKey = session.playbackKey()
            if (alignInstanceConfigOnTakeover()) return
            if (mVideoView != null && !mVideoView!!.isPlaying) mVideoView!!.start()
            return
        }
        // 同片同线路换集(选集面板点集走的就是这条):内核可复用,省一次重建;换片/换线路仍走重建
        val sameVodSwitch = isSameVodEpisodeSwitch(session)
        engine!!.setData(session)
        syncSessionVod()
        mController.setPlayerConfig(scheduler.playerCfg()!!)
        scheduler.clearTriedLines()
        scheduler.setUserPickedLine(session.userPickedLine())
        ownedPlaybackKey = session.playbackKey()
        if (sameVodSwitch) scheduler.setReusePlayerOnSwitch(true)
        playViaScheduler(false)
    }

    /** 引擎里已起播的是不是同一部片的同一线路(只是换集) —— 归属键前两段(源|片id)相同、线路相同即可 */
    private fun isSameVodEpisodeSwitch(session: PlaybackSession): Boolean {
        if (scheduler == null || mVideoView == null || mVideoView!!.mediaPlayer == null) return false
        val started = scheduler.startedPlaybackKey()
        if (TextUtils.isEmpty(started)) return false
        val key = session.playbackKey()
        val cut = key.lastIndexOf('|')
        return cut > 0 && started!!.startsWith(key.substring(0, cut + 1))
    }

    fun playViaScheduler(reset: Boolean) {
        reviveEngineIfReleased()
        scheduler.play(reset)
    }

    fun replayCurrentAddress() {
        reloadDanmuForPlayback()
        val url = scheduler.webPlayUrl()
        if (url != null && !url.isEmpty()) {
            scheduler.stopParse()
            scheduler.initParseLoadFound()
            // 重播/切播放器/切解码共走本方法:总闸下重播不必重建内核;切外部播放器不在此处(内核交不出去,由 pl≥10 分支先释放)
            if (!scheduler.isCrossContentReuseAllowed()) releasePlayerKernel()
            scheduler.goPlayUrl(url, scheduler.webHeaderMap())
        } else {
            playViaScheduler(false)
        }
    }

    /**
     * D6 同片接管时对齐实例级配置:缩放直接下发;渲染方式与解码方式都必须重建内核才生效
     * (复用内核不重建渲染视图,media3 也不给复用内核重选解码器),此处改走既有"重播"链路
     * 并返回 true,调用方不要再 resume。
     */
    private fun alignInstanceConfigOnTakeover(): Boolean {
        if (mVideoView == null || scheduler == null) return false
        val cfg = scheduler.playerCfg() ?: return false
        mVideoView!!.setScreenScaleType(cfg.optInt("sc", 0))
        // 外部播放器由 goPlayUrl 交给第三方,内核重建/重播不由这里发起(与 trySoftDecodeFallback 同一判据)
        if (cfg.optInt("pl", 2) >= 10) return false
        // 纯音频会话最终总会热切 Texture(见 ensureAudioOnlyRender),按用户设置重建只会白断一次声音
        val renderChanged = !scheduler.isConfirmedAudioOnly()
            && mVideoView!!.needsRenderRebuild(cfg.optInt("pr", 1))
        val decodeChanged = !PlayerHelper.isExoDecodeApplied(cfg)
        if (!renderChanged && !decodeChanged) return false
        LOG.i(
            if (renderChanged) "echo-render-changed: rebuild kernel on takeover"
            else "echo-exo-decode-changed: rebuild kernel on takeover",
        )
        // 重建后按配置值重新起播一次:重试阶梯(含自动软解额度)随之复位,起播失败时仍能自动回退
        scheduler.beginNewPlay()
        controlListener.replay(false)
        return true
    }

    fun hasClaimedPlayback(): Boolean {
        return !TextUtils.isEmpty(ownedPlaybackKey)
    }

    fun ownsEngineContent(): Boolean {
        if (!hasClaimedPlayback()) return false
        return TextUtils.equals(ownedPlaybackKey, scheduler?.startedPlaybackKey())
    }

    private fun isSamePlaybackOwned(session: PlaybackSession): Boolean {
        // 无痕:停着的那份是旧痕迹,不接管(重进从片头起播);正在播的(音频在后台)是活状态,照常接管不打断
        if (HistoryHelper.isIncognito() && (mVideoView == null || !mVideoView!!.isPlaying)) return false
        if (!TextUtils.equals(scheduler.startedPlaybackKey(), session.playbackKey())) return false
        if (engine!!.isLiveMode()) return false
        if (mVideoView == null || mVideoView!!.mediaPlayer == null) return false
        val state = mVideoView!!.currentPlayState
        return state != AppPlayerView.STATE_ERROR && state != AppPlayerView.STATE_IDLE
    }

    override fun onBackPressed(): Boolean {
        return mController.onBackPressed()
    }

    fun isPortraitVideo(): Boolean {
        return mVideoView != null && mVideoView!!.isPortraitVideo()
    }

    override fun setExitingPreview(exitingPreview: Boolean) {
        mExitingPreview = exitingPreview
    }

    override fun resumeFromMediaSession() {
        if (mVideoView != null) {
            mVideoView!!.start()
            scheduler.updateMusicSession()
        }
    }

    override fun pauseFromMediaSession() {
        if (mVideoView != null) {
            mVideoView!!.pause()
            scheduler.updateMusicSession()
        }
    }

    override fun stopFromMediaSession() {
        if (mVideoView != null) mVideoView!!.pause()
        scheduler.stopMusicSession()
    }

    override fun seekFromMediaSession(position: Long) {
        if (mVideoView != null) {
            mVideoView!!.seekTo(position)
            scheduler.updateMusicSession()
        }
    }

    override fun playNext(isProgress: Boolean) {
        scheduler.clearTriedLines()
        val hasNext: Boolean
        if (scheduler.vod() == null || scheduler.vod()!!.seriesMap!![scheduler.vod()!!.playFlag] == null) {
            hasNext = false
        } else {
            hasNext = scheduler.vod()!!.playIndex + 1 < scheduler.vod()!!.seriesMap!![scheduler.vod()!!.playFlag]!!.size
        }
        if (!hasNext) {
            Toast.makeText(mActivity!!, mActivity!!.getString(R.string.player_last_episode), Toast.LENGTH_SHORT).show()
            return
        } else {
            scheduler.vod()!!.playIndex++
        }
        scheduler.setReusePlayerOnSwitch(true)
        playViaScheduler(false)
    }

    override fun playPrevious() {
        scheduler.clearTriedLines()
        var hasPre = true
        if (scheduler.vod() == null || scheduler.vod()!!.seriesMap!![scheduler.vod()!!.playFlag] == null) {
            hasPre = false
        } else {
            hasPre = scheduler.vod()!!.playIndex - 1 >= 0
        }
        if (!hasPre) {
            Toast.makeText(mActivity!!, mActivity!!.getString(R.string.player_first_episode), Toast.LENGTH_SHORT).show()
            return
        }
        scheduler.vod()!!.playIndex--
        scheduler.setReusePlayerOnSwitch(true)
        playViaScheduler(false)
    }

    override fun setPlayTitle(show: Boolean) {
        if (!show) {
            mController.setTitle("")
            return
        }
        val vod = scheduler.vod()
        val vs = if (vod == null) null else scheduler.currentSeries(vod.playFlag, vod.playIndex)
        mController.setTitle(if (vod == null) "" else if (vs == null) vod.name!! else vod.name + " " + vs.name)
    }

    fun buildPreloadSnapshot(): PreloadCoordinator.Snapshot? {
        try {
            if (scheduler.vod() == null || scheduler.vod()!!.seriesMap == null) return null
            val episodes = scheduler.vod()!!.seriesMap!![scheduler.vod()!!.playFlag]
            if (episodes == null || scheduler.vod()!!.playIndex < 0 || scheduler.vod()!!.playIndex + 1 >= episodes.size) return null
            val next = episodes[scheduler.vod()!!.playIndex + 1]
            if (next == null || TextUtils.isEmpty(next.url)) return null
            val nextIndex = scheduler.vod()!!.playIndex + 1
            val nextKey = scheduler.vod()!!.sourceKey + scheduler.vod()!!.id + scheduler.vod()!!.playFlag + nextIndex + next.name
            val nextSubtKey = scheduler.vod()!!.sourceKey + "-" + scheduler.vod()!!.id + "-" + scheduler.vod()!!.playFlag + "-" + nextIndex + "-" + next.name + "-subt"
            val startSkipMs = if (scheduler.playerCfg() == null) 0L else scheduler.playerCfg()!!.optInt("st", 0) * 1000L
            val mediaPlayer = mVideoView?.mediaPlayer
            val exoKernel = mediaPlayer is ExoPlayer
            return PreloadCoordinator.Snapshot(
                mContext,
                scheduler.sourceKey(),
                scheduler.vod()!!.playFlag,
                scheduler.progressKey(),
                nextKey,
                next.url,
                nextSubtKey,
                startSkipMs,
                exoKernel,
            )
        } catch (th: Throwable) {
            LOG.i("echo-preload-skip: snapshot error " + th)
            return null
        }
    }

    override fun setAutoSwitchLineEnabled(enabled: Boolean) {
        scheduler.setAutoSwitchLineEnabled(enabled)
    }

    override fun setPreviewMode(previewMode: Boolean) {
        this.previewMode = previewMode
        if (mController != null) {
            mController.setPreviewMode(previewMode)
            mController.getLyricView().setTextSize(if (previewMode) 16f else 24f)
            applySubtitleTextSize()
        }
    }

    /** 字幕字号 = 设置值 × 当前形态(预览 0.6×/全屏 1×);统一走 setTextSize(float)=sp —— SimpleSubtitleView 只重写了 float 重载(描边层 backGroundText 随之同步),int 实参会被加宽到 float,同样落到该重载 */
    private fun applySubtitleTextSize() {
        if (mController == null || mController.getSubtitleView() == null) return
        val size = SubtitleHelper.getTextSize(mActivity)
        mController.getSubtitleView().setTextSize(if (previewMode) size * 0.6f else size.toFloat())
    }

    override fun toggleControllerControls() {
        if (mController != null) {
            mController.toggleControlBar()
        }
    }

    override fun stopForSourceSwitch(tip: String) {
        if (mVideoView == null) return
        scheduler.cancelPlayTimeout()
        scheduler.stopParse()
        scheduler.markStoppedForSourceSwitch()
        scheduler.stopMusicSessionForFailedPlayback()

        val position = mVideoView!!.currentPosition
        scheduler.setPendingInherit(scheduler.progressKey(), position)
        mVideoView!!.pause()
        if (scheduler.isCrossContentReuseAllowed()) {
            // 总闸下换源也算换线:内核留给新源复用(释放与判定共用同一许可);进度改由此处显式落盘,原先靠 release 内部兜底
            mVideoView!!.saveCurrentProgress()
            LOG.i("echo-switchSource keep player kernel for reuse")
        } else {
            releasePlayerKernel()
        }
        if (mController != null) mController.stopOther()
        resetDanmuState()
        scheduler.setWebPlayUrl(null)
        scheduler.setWebHeaderMap(null)
        scheduler.initParseLoadFound()
        LOG.i("echo-switchSource stop at " + position + "ms, key=" + scheduler.progressKey())
        if (!TextUtils.isEmpty(tip)) setTip(tip, true, false)
    }

    override fun clearSourceSwitchTip() {
        if (!scheduler.isSwitchStopPending()) return
        hideTipOnUiThread()
    }

    /** 同页换片:停掉当前内容并立即落盘,免得新片加载期间旧片声画残留;不在播本页内容时不动(别误停音乐页/直播) */
    fun stopForContentSwitch() {
        if (mVideoView == null || !ownsEngineContent()) return
        // 在途的解析/取流/超时属上一部:新片会话边界虽也会清,但新片详情回来之前它们足以把旧片再拉起来
        scheduler.cancelInFlight()
        mVideoView!!.pause()
        mVideoView!!.saveCurrentProgress()
        // pause 对取流中的起播无效(PAUSED 时本调用自会 return):不打断的话这一集会在新片加载期间自己响起来
        mVideoView!!.stopPlaybackKeepPlayer()
    }

    fun getPlayer(): MyVideoView? {
        return mVideoView
    }

    inner class MyWebView(context: Context) : WebView(context) {

        override fun setOverScrollMode(mode: Int) {
            super.setOverScrollMode(mode)
            if (mContext is Activity) {
                AutoSize.autoConvertDensityOfCustomAdapt(mContext as Activity, this@PlayContainer)
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            return false
        }
    }

    companion object {

        private const val MSG_PARSE_TIMEOUT = 100
        private const val PRELOAD_TOAST_REFRESH_DELAY_MS = 1000L
    }
}
