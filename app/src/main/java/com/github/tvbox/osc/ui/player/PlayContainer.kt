package com.github.tvbox.osc.ui.player

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.dlna.CastVideo
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.ExoPlayer
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.PageHost
import com.github.tvbox.osc.player.PlaybackController
import com.github.tvbox.osc.player.PlaybackEngine
import com.github.tvbox.osc.player.PlaybackHostApi
import com.github.tvbox.osc.player.PlaybackPage
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.PlaybackSession
import com.github.tvbox.osc.player.PlaybackViewBridge
import com.github.tvbox.osc.player.PlayerHelper
import com.github.tvbox.osc.player.PreloadCoordinator
import com.github.tvbox.osc.player.TrackInfoBean
import com.github.tvbox.osc.player.controller.ComposeVideoController
import com.github.tvbox.osc.player.controller.PlayerControlApi
import com.github.tvbox.osc.player.danmu.DanmuLoadController
import com.github.tvbox.osc.player.state.CastSheetState
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.LOG
import me.jessyan.autosize.AutoSize
import me.jessyan.autosize.internal.CustomAdapt
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
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

    private val tipStateListener: TipStateListener = TipStateListener { overlays.onTipStateChanged(it) }

    private val viewBridge: PlaybackViewBridge = PlayContainerViewBridge(this)

    private val controlListener: PlayContainerControlListener = PlayContainerControlListener(this)

    private val overlays: PlayContainerOverlays = PlayContainerOverlays(this)

    private val subtitles: PlayContainerSubtitles = PlayContainerSubtitles(this)

    private var qualitySelectedListener: OnQualitySelectedListener? = null

    private var lifecyclePaused: Boolean = false
    private var ownedPlaybackKey: String? = null
    private var handedOver: Boolean = false

    var mVideoView: MyVideoView? = null
    lateinit var mController: PlayerControlApi
    internal var mHandler: Handler? = null
    var mExitingPreview: Boolean = false
    internal var previewMode: Boolean = false
    var danmuLoadController: DanmuLoadController? = null
    private val videoDuration: Long = -1

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
        overlays.cancelPreloadToast()
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
            subtitles.applySubtitleTextSize()
        }
        if (event.type == RefreshEvent.TYPE_SET_DANMU_SETTINGS) {
            overlays.setDanmuViewSettings(event.obj is Boolean && event.obj as Boolean)
        } else if (event.type == RefreshEvent.TYPE_DANMU_REFRESH) {
            overlays.checkDanmu(if (event.obj is String) event.obj as String else "")
        }
    }

    private fun init() {
        initView()
        overlays.initDanmuView()
    }

    fun applyDanmuSettings(reload: Boolean) = overlays.applyDanmuSettings(reload)

    fun checkDanmu(danmu: String?, callback: DanmuLoadController.LoadCallback?) =
        overlays.checkDanmu(danmu, callback)

    fun startDanmuIfReady() = overlays.startDanmuIfReady()

    fun resetDanmuState() = overlays.resetDanmuState()

    private fun initView() {
        EventBus.getDefault().register(this)
        mHandler = Handler { msg ->
            when (msg.what) {
                MSG_PARSE_TIMEOUT -> {
                    scheduler.stopParse()
                    overlays.errorWithRetry(mContext.getString(R.string.player_error_sniff), false)
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

    fun openDanmuSearchSheet() = overlays.openDanmuSearchSheet()

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

    fun setSubtitle(path: String?) = subtitles.setSubtitle(path)

    fun selectMySubtitle() = subtitles.selectMySubtitle()

    override fun onLocalSubtitlePicked(uri: Uri) = subtitles.onLocalSubtitlePicked(uri)

    fun selectMyAudioTrack() {
        trackSelector.selectAudioTrack()
    }

    fun selectMyVideoTrack() {
        trackSelector.selectVideoTrack()
    }

    fun setTip(msg: String, loading: Boolean, err: Boolean) = overlays.setTip(msg, loading, err)

    fun hideTip() = overlays.hideTip()

    fun hideTipOnUiThread() = overlays.hideTipOnUiThread()

    fun showPreloadReady() = overlays.showPreloadReady()

    fun hidePreloadReady() = overlays.hidePreloadReady()

    fun errorWithRetry(err: String, finish: Boolean) = overlays.errorWithRetry(err, finish)

    fun initSubtitleView() = subtitles.initSubtitleView()

    fun closeSubtitles() = subtitles.closeSubtitles()

    fun trackMemoryKey(): String = subtitles.trackMemoryKey()

    private fun rebindPlaybackOverlay() {
        subtitles.initSubtitleView()
        overlays.checkDanmu(scheduler.playDanmu())
    }

    fun clearLyricView() = subtitles.clearLyricView()

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
        overlays.reloadDanmuForPlayback()
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
            subtitles.applySubtitleTextSize()
        }
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
        overlays.resetDanmuState()
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
    }
}
