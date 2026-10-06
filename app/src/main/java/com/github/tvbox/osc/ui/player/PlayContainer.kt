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
import com.github.tvbox.osc.player.state.PlayState
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

class PlayContainer(activity: Activity) : FrameLayout(activity), CustomAdapt, PlaybackHostApi, PlaybackPage {

    private val trackSelector: TrackSelectorDelegate = TrackSelectorDelegate(object : TrackSelectorDelegate.Host {
        override fun player(): MyVideoView? = mVideoView

        override fun context(): Context = mContext

        override fun uiState(): PlayerUiState = mController.getUiState()
    })

    lateinit var scheduler: PlaybackController
    private lateinit var surfaceSlot: FrameLayout
    private var engine: PlaybackEngine? = null
    var mPageHost: PageHost? = null
    var mActivity: Activity? = activity

    private val mContext: Context = activity

    private val tipStateListener: TipStateListener = TipStateListener { onTipStateChanged(it) }

    private val viewBridge: PlaybackViewBridge = PlayContainerViewBridge(this)

    private val controlListener: PlayContainerControlListener = PlayContainerControlListener(this)

    private var qualitySelectedListener: OnQualitySelectedListener? = null

    private var lifecyclePaused: Boolean = false
    private var ownedPlaybackKey: String? = null
    private var handedOver: Boolean = false

    var mVideoView: MyVideoView? = null
    lateinit var mController: PlayerControlApi
    private var preloadReadyToast: Toast? = null
    private var mHandler: Handler? = null
    var mExitingPreview: Boolean = false
    private var previewMode: Boolean = false
    private var mDanmuView: DanmakuView? = null
    var danmuLoadController: DanmuLoadController? = null
    private val exoCues: MutableList<Cue> = ArrayList()
    private var exoInternalSubtitle: Boolean = false

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
        PlayerTipBridge.setTipStateListener(tipStateListener)
        scheduler.setViewBridge(viewBridge)
        if (engine != null) engine!!.attach(this)
    }

    private fun onTipStateChanged(tip: PlayerTipState) {
        if (mHandler == null) return
        val showing = tip.loading || tip.err
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

    fun setEpisodeSheetOpen(open: Boolean) {
        if (mController != null) mController.getUiState().episodeSheetOpen = open
    }

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
        handedOver = false
        if (!ownsEngineContent() && mVideoView != null) {
            mVideoView!!.saveCurrentProgress()
        }
        if (mVideoView != null && mController != null) {
            mController.setKernelProvider(mVideoView)
            val state = mVideoView!!.playState
            if (mVideoView!!.mediaPlayer != null
                && state != PlayState.IDLE && state != PlayState.ERROR
                && ownsEngineContent()
            ) {
                rebindPlaybackOverlay()
            }
        }
        if (ownsEngineContent()) {
            syncSessionVod()
        }
        LOG.i("echo-p4 re-attach after live/other page")
    }

    override fun hostPause() {
        if (mVideoView != null && !mExitingPreview && !scheduler.isConfirmedAudioOnly()) {
            lifecyclePaused = mVideoView!!.isPlaying
            if (mController != null) mController.setLifecyclePaused(lifecyclePaused)
            mVideoView!!.pause()
        }
    }

    fun handOverToNextPage() {
        if (engine == null) return
        handedOver = true
        engine!!.detachForHandover(this)
    }

    override fun hostDestroy() {
        LOG.i("echo-music destroy: hostDestroy enter")
        PlayerTipBridge.clearTipStateListener(tipStateListener)
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
                name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
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

    private fun applySubtitleDecision(mediaPlayer: KernelPlayer?, trackInfo: TrackInfo?) {
        val memoryKey = trackMemoryKey()
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
            showInternalSubtitle(mediaPlayer)
            return
        }
        applyDefaultSubtitle(mediaPlayer, trackInfo)
    }

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

    private fun showInternalSubtitle(mediaPlayer: KernelPlayer?) {
        if (mediaPlayer is ExoPlayer) {
            mediaPlayer.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay())
            exoInternalSubtitle = true
            mController.getExoSubtitleView().setVisibility(View.VISIBLE)
            applyExoSubtitleSettings()
        }
    }

    private fun ensureInternalSubtitleTrackSelected(mediaPlayer: KernelPlayer?, trackInfo: TrackInfo?) {
        if (mediaPlayer is ExoPlayer) {
            mediaPlayer.ensureSubtitleTrackSelected()
        }
    }

    private fun resolveRememberedOnlineSubtitle(memoryKey: String, record: String?, fallback: Runnable) {
        val releaseUrl = TrackMemory.onlineRelease(record)
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
                    if (TextUtils.isEmpty(url)) {
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

    private fun isSubtitleResultCurrent(memoryKey: String, episodeKey: String?, decisionSeq: Int): Boolean {
        if (!isAttached() || subtitleDecisionSeq != decisionSeq) return false
        if (!TextUtils.equals(memoryKey, trackMemoryKey())) return false
        return TextUtils.equals(episodeKey, scheduler.progressKey())
    }

    private fun runOnUi(action: Runnable) {
        val activity = mActivity
        if (activity == null) return
        activity.runOnUiThread(action)
    }

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

    fun closeSubtitles() {
        if (mVideoView == null) return
        closeSubtitleViews()
        subtitleDecisionSeq++
        TrackMemory.saveSubtitle(trackMemoryKey(), TrackMemory.SUBTITLE_OFF)
    }

    fun trackMemoryKey(): String {
        val vod = scheduler?.vod()
        if (vod == null) return ""
        return TrackMemory.contentKey(vod.sourceKey, vod.id)
    }

    private fun rebindPlaybackOverlay() {
        initSubtitleView()
        checkDanmu(scheduler.playDanmu())
    }

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
            if (!scheduler.isCrossContentReuseAllowed()) releasePlayerKernel()
            scheduler.goPlayUrl(url, scheduler.webHeaderMap())
        } else {
            playViaScheduler(false)
        }
    }

    private fun alignInstanceConfigOnTakeover(): Boolean {
        if (mVideoView == null || scheduler == null) return false
        val cfg = scheduler.playerCfg() ?: return false
        mVideoView!!.setScreenScaleType(cfg.optInt("sc", 0))
        if (cfg.optInt("pl", 2) >= 10) return false
        val renderChanged = !scheduler.isConfirmedAudioOnly()
            && mVideoView!!.needsRenderRebuild(cfg.optInt("pr", 1))
        val decodeChanged = !PlayerHelper.isExoDecodeApplied(cfg)
        if (!renderChanged && !decodeChanged) return false
        LOG.i(
            if (renderChanged) "echo-render-changed: rebuild kernel on takeover"
            else "echo-exo-decode-changed: rebuild kernel on takeover",
        )
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
        if (HistoryHelper.isIncognito() && (mVideoView == null || !mVideoView!!.isPlaying)) return false
        if (!TextUtils.equals(scheduler.startedPlaybackKey(), session.playbackKey())) return false
        if (engine!!.isLiveMode()) return false
        if (mVideoView == null || mVideoView!!.mediaPlayer == null) return false
        val state = mVideoView!!.playState
        return state != PlayState.ERROR && state != PlayState.IDLE
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

    fun stopForContentSwitch() {
        if (mVideoView == null || !ownsEngineContent()) return
        scheduler.cancelInFlight()
        mVideoView!!.pause()
        mVideoView!!.saveCurrentProgress()
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
