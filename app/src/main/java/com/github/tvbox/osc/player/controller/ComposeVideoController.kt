package com.github.tvbox.osc.player.controller

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.media3.ui.SubtitleView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.data.PlaybackProgress
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.AppPlayerView
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.state.LockVisibility
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.player.state.VideoSizeGate
import com.github.tvbox.osc.player.ui.PlayerOverlay
import com.github.tvbox.osc.player.ui.VideoGestureHandler
import com.github.tvbox.osc.player.usecase.M3u8PurifyUseCase
import com.github.tvbox.osc.player.usecase.PlayerSwitchUseCase
import com.github.tvbox.osc.player.usecase.WebParseUseCase
import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.util.DanmuHelper
import com.github.tvbox.osc.util.SubtitleHelper
import com.github.tvbox.osc.util.PlayerUtils
import org.greenrobot.eventbus.EventBus
import org.json.JSONObject
import java.util.HashMap

@Suppress("MemberVisibilityCanBePrivate")
class ComposeVideoController @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr),
    AppPlayerView.VideoControllerHost,
    PlayerControlApi {

    companion object {

        private const val LOCK_HIDE_DELAY_MS = 3000L
    }

    internal lateinit var state: PlayerUiState

    val playerView: MyVideoView?
        get() = videoView

    private var activityCache: Activity? = null

    val isLocked: Boolean
        get() = state.locked

    fun playerActivity(): Activity? =
        activityCache ?: PlayerUtils.scanForActivity(context)?.also { activityCache = it }
            ?: videoView?.hostActivity()

    internal fun gestureCanChangePosition(): Boolean = canChangePosition

    internal fun gestureEnableInNormal(): Boolean = enableInNormal

    internal fun gestureEnabled(): Boolean = gestureSwitch

    internal fun currentSpeed(): Float = playerView?.speed ?: 1f

    internal fun enterGestureSeek() {
        if (!state.dragging) {
            state.dragging = true
            if (!state.controlsVisible) actions.applyShowBottom()
            stopProgress()
        }
    }

    internal fun exitGestureSeek() {
        state.dragging = false
        startProgress()
        actions.keepControlsAlive()
    }

    internal fun saveGestureProgress(targetMs: Int) {
        savePlaybackProgress(notifyHistory = true, seekTargetMs = targetMs)
    }

    internal fun showSlideHint(text: String, brightness: Boolean) {
        state.slideHintText = text
        state.slideHintBrightness = brightness
        state.slideHintVisible = true
    }

    private val tapConfirmRunnable = Runnable { confirmGestureTap() }

    internal fun onGestureTapPending() {
        uiHandler.removeCallbacks(tapConfirmRunnable)
        uiHandler.postDelayed(tapConfirmRunnable, gestureHandler.doubleTapTimeoutMs)
    }

    private fun confirmGestureTap() {
        gestureHandler.markSingleTapConfirmed()
    }

    fun togglePlayFromGesture() {
        videoView?.togglePlay()
    }

    fun seekToFromGesture(positionMs: Long) {
        videoView?.seekTo(positionMs)
    }

    fun setSpeedFromGesture(speed: Float) {
        videoView?.setSpeed(speed)
    }

    internal var videoView: MyVideoView? = null

    override fun setKernelProvider(view: MyVideoView?) {
        videoView = view
        view?.setVideoController(this)
    }

    private lateinit var gestureActions: VideoGestureActionsImpl

    internal lateinit var gestureHandler: VideoGestureHandler

    internal lateinit var actions: PlayerActionsDelegate

    internal lateinit var config: PlayerConfigDelegate

    private var canChangePosition = true
    private var enableInNormal = false
    private var gestureSwitch = true

    private lateinit var mSubtitleView: SimpleSubtitleView
    private lateinit var mLyricView: SimpleSubtitleView
    private lateinit var mExoSubtitleView: SubtitleView

    internal val videoSizeGate = VideoSizeGate()

    internal var previewMode = false
    internal var speedOld = 1.0f
    private var skipEnd = true
    internal var isClickBackBtn = false
    private var showParseFlag = false
    internal var playerConfig: JSONObject? = null
    internal var listener: VodControlListener? = null

    internal val uiHandler by lazy { Handler(Looper.getMainLooper()) }

    private var progressTicking = false
    private val progressRunnable by lazy { Runnable { onProgressTick() } }
    internal val idleHideRunnable by lazy {
        Runnable {
            if (state.overlayPanelOpen) actions.keepControlsAlive() else actions.hideBottom()
        }
    }
    internal val lockHideRunnable by lazy { Runnable { state.lockState = LockVisibility.HIDDEN } }

    private val m3u8PurifyUseCase by lazy {
        M3u8PurifyUseCase(context, object : M3u8PurifyUseCase.Callback {
            override fun startPlayUrl(url: String?, headers: HashMap<String, String>?) {
                listener?.startPlayUrl(url ?: return, headers)
            }

            override fun onM3u8ProxyUrl(proxyUrl: String?, sourceUrl: String?) {
                listener?.onM3u8ProxyUrl(proxyUrl ?: return, sourceUrl ?: return)
            }
        })
    }
    private val webParseUseCase by lazy { WebParseUseCase() }

    init {
        state = PlayerUiState()

        gestureActions = VideoGestureActionsImpl(this)
        gestureHandler = VideoGestureHandler(gestureActions)
        actions = PlayerActionsDelegate(this)
        config = PlayerConfigDelegate(this)

        initNativeSubtitleViews()
        initComposeLayer()

        state.sysTimeVisible = false
        state.isPortrait =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        updateDanmuBtnState()
        updateDanmuSearchBtnState()
        initSubtitleInfo()
    }

    private fun initNativeSubtitleViews() {
        val vs5 = resources.getDimensionPixelSize(R.dimen.vs_5)
        val vs15 = resources.getDimensionPixelSize(R.dimen.vs_15)
        val vs20 = resources.getDimensionPixelSize(R.dimen.vs_20)

        mSubtitleView = SimpleSubtitleView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(vs20, vs15, vs20, vs15)
            visibility = View.VISIBLE
        }
        addView(
            mSubtitleView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )

        mExoSubtitleView = SubtitleView(context).apply { visibility = View.GONE }
        addView(mExoSubtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        mLyricView = SimpleSubtitleView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFF00FF00.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textScaleX = 1.1f
            setPadding(vs5, vs20, vs5, vs20)
            visibility = View.GONE
        }
        addView(mLyricView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    private fun initComposeLayer() {
        val composeView = ComposeView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setContent {
                AVBoxTheme(manageStatusBarIcons = false) {
                    PlayerOverlay(
                        state = state,
                        actions = actions,
                        gestureHandler = gestureHandler,
                        gestureSession = { w, h, sw, y -> gestureActions.beginSession(w, h, sw, y) },
                        onTapPending = { onGestureTapPending() },
                    )
                }
            }
        }
        addView(composeView)
    }

    private fun initSubtitleInfo() {
        mSubtitleView.setTextSize(SubtitleHelper.getTextSize(playerActivity()).toFloat())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiHandler.removeCallbacks(progressRunnable)
        progressTicking = false
        uiHandler.removeCallbacks(idleHideRunnable)
        uiHandler.removeCallbacks(lockHideRunnable)
        actions.cancelKeySeekCommit()
        config.cancelSpeedRetry()
        uiHandler.removeCallbacks(tapConfirmRunnable)
    }

    override fun setPlayState(playState: PlayState) {
        state.playState = playState
        if (playState != PlayState.IDLE && playState != PlayState.ERROR &&
            playState != PlayState.PREPARING
        ) {
            updateLiveButtonsState()
        }
        applyPlayState(playState)
    }

    private fun applyPlayState(playState: PlayState) = when (playState) {
        PlayState.IDLE -> {
            savePlaybackProgress(notifyHistory = true)
            state.locked = false
        }
        PlayState.PLAYING -> {
            initOrientationState()
            startProgress()
        }
        PlayState.PAUSED -> {
            if (!state.lifecyclePaused) {
                state.topLeftVisible = false
                state.netSpeedTopRightVisible = false
                if (state.controlsVisible) actions.hideBottom()
            }
            savePlaybackProgress(notifyHistory = true)
        }
        PlayState.ERROR -> listener?.errReplay()
        PlayState.PREPARED -> listener?.prepared()
        PlayState.COMPLETED -> {
            state.locked = false
            PlaybackProgress.markFinished()
            listener?.playNext(true)
        }
        PlayState.PREPARING, PlayState.BUFFERING, PlayState.BUFFERED, PlayState.START_ABORT -> Unit
    }

    override fun setPlayerState(playerState: Int) {
        state.playerState = playerState
    }

    override fun onVideoSizeChanged(width: Int, height: Int) {
        state.videoSize = videoSizeGate.textFor(width, height)
    }

    override fun onVideoSizeCleared() {
        videoSizeGate.onKernelContentReplaced()
    }

    private fun onProgressTick() {
        progressTicking = false
        val view = videoView
        if (view != null && !state.dragging) {
            onProgressTick(view.duration, view.currentPosition)
        }
        if (state.dragging) return
        if (view?.isPlaying != true) return
        progressTicking = true
        val speed = view.speed.takeIf { it > 0f } ?: 1f
        val delayMs = ((1000 - state.position % 1000) / speed).toLong().coerceAtLeast(1L)
        uiHandler.postDelayed(progressRunnable, delayMs)
    }

    private fun onProgressTick(duration: Long, position: Long) {
        val durationMs = PlayerUtils.safeTimeMs(duration)
        val positionMs = PlayerUtils.safeTimeMs(position)
        if (durationMs > 0) state.duration = durationMs
        if (positionMs > 0 || durationMs > 0) state.position = positionMs
        PlaybackProgress.onProgress(positionMs, durationMs)
        if (skipEnd && positionMs != 0 && durationMs != 0) {
            val et = playerConfig?.optInt("et", 0) ?: 0
            if (et > 0 && positionMs + et * 1000 >= durationMs) {
                skipEnd = false
                listener?.playNext(true)
            }
        }
        state.bufferedPercent = runCatching { videoView?.bufferedPercentage ?: 0 }.getOrDefault(0)
    }

    override fun startProgress() {
        if (progressTicking) return
        progressTicking = true
        uiHandler.post(progressRunnable)
    }

    internal fun stopProgress() {
        if (!progressTicking) return
        uiHandler.removeCallbacks(progressRunnable)
        progressTicking = false
    }

    internal fun savePlaybackProgress(notifyHistory: Boolean, seekTargetMs: Int = -1) {
        val viewDuration = runCatching { videoView?.duration ?: 0L }.getOrDefault(0L).toInt()
        val viewPosition = runCatching { videoView?.currentPosition ?: 0L }.getOrDefault(0L).toInt()
        val duration = if (viewDuration > 0) viewDuration else state.duration
        val position = when {
            seekTargetMs >= 0 -> seekTargetMs
            viewDuration > 0 -> viewPosition
            else -> state.position
        }
        if (duration <= 0) return
        PlaybackProgress.flush(position, duration)
        if (notifyHistory) EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
    }

    internal fun updateSeekUiHint(curr: Int, seekTo: Int) {
        state.seekHintForward = seekTo > curr
        state.seekHintText = PlayerUtils.stringForTime(seekTo)
        state.seekHintVisible = true
    }

    internal fun isInPlaybackState(): Boolean {
        if (videoView == null) return false
        return when (state.playState) {
            PlayState.PLAYING, PlayState.PAUSED, PlayState.BUFFERING, PlayState.BUFFERED -> true
            else -> false
        }
    }

    override fun toggleControlBar() {
        actions.toggleControls()
    }

    internal fun showLockView() {
        if (previewMode) {
            state.locked = false
            uiHandler.removeCallbacks(lockHideRunnable)
            state.lockState = LockVisibility.GONE
            return
        }
        state.lockState = LockVisibility.SHOWN
        uiHandler.removeCallbacks(lockHideRunnable)
        if (state.locked) {
            uiHandler.postDelayed(lockHideRunnable, LOCK_HIDE_DELAY_MS)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initOrientationState()
    }

    private fun initOrientationState() {
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        state.isPortrait = isPortrait
        if (isPortrait) {
            state.backVisible = false
        }
    }

    private fun updateDanmuBtnState() {
        state.danmuOpen = DanmuHelper.isOpen()
    }

    internal fun updateDanmuSearchBtnState() {
        state.danmuSearchAvailable = ApiConfig.get().hasDanmuSearchUi()
    }

    private fun updateLiveButtonsState() {
        state.liveButtonsVisible = runCatching { videoView?.duration ?: 0L != 0L }.getOrDefault(true)
    }

    override fun getUiState(): PlayerUiState = state

    override fun getSubtitleView(): SimpleSubtitleView = mSubtitleView

    override fun getLyricView(): SimpleSubtitleView = mLyricView

    override fun getExoSubtitleView(): SubtitleView = mExoSubtitleView

    override fun setListener(l: VodControlListener?) {
        listener = l
    }

    override fun setPlayerConfig(playerCfg: JSONObject) {
        playerConfig = playerCfg
        config.updatePlayerCfgState()
    }

    override fun showParse(userJxList: Boolean) {
        showParseFlag = userJxList
        state.showParseRow = userJxList
    }

    override fun setPreviewMode(previewMode: Boolean) {
        this.previewMode = previewMode
        state.previewMode = previewMode
        if (previewMode && state.controlsVisible) actions.hideBottom()
        uiHandler.removeCallbacks(lockHideRunnable)
        state.lockState = LockVisibility.GONE
    }

    override fun setTitle(playTitleInfo: String) {
        state.title = playTitleInfo
    }

    override fun setUrlTitle(playTitleInfo: String) = Unit

    override fun setHasDanmu(hasDanmu: Boolean) {
        updateDanmuBtnState()
    }

    override fun setCanChangePosition(canChangePosition: Boolean) {
        this.canChangePosition = canChangePosition
    }

    override fun setEnableInNormal(enableInNormal: Boolean) {
        this.enableInNormal = enableInNormal
    }

    override fun setGestureEnabled(gestureEnabled: Boolean) {
        this.gestureSwitch = gestureEnabled
    }

    override fun hidePauseRoot() = Unit

    override fun onNewPlayStarted() {
        val size = runCatching { videoView?.videoSize }.getOrNull() ?: intArrayOf(0, 0)
        state.videoSize = videoSizeGate.onNewSession(size[0], size[1])
        state.position = 0
        state.duration = 0
    }

    override fun setLifecyclePaused(paused: Boolean) {
        state.lifecyclePaused = paused
        if (paused) uiHandler.removeCallbacks(idleHideRunnable) else actions.keepControlsAlive()
    }

    override fun resetSpeed() {
        skipEnd = true
        config.applySpeedWhenReady()
    }

    override fun onBackPressed(): Boolean {
        if (isClickBackBtn) {
            isClickBackBtn = false
            if (state.controlsVisible) actions.hideBottom()
            return false
        }
        if (state.controlsVisible) {
            actions.hideBottom()
            return true
        }
        return false
    }

    override fun switchPlayer(): Boolean = PlayerSwitchUseCase.switchPlayer()

    override fun stopOther() {
        PlayerSwitchUseCase.stopOther()
    }

    override fun playM3u8(url: String?, headers: HashMap<String, String>?) {
        m3u8PurifyUseCase.playM3u8(url ?: return, headers)
    }

    override fun encodeUrl(url: String?): String = PlayerSwitchUseCase.encodeUrl(url)

    override fun firstUrlByArray(url: String?): String = PlayerSwitchUseCase.firstUrlByArray(url)

    override fun evaluateScript(sourceBean: SourceBean?, url: String?, view: WebView?) {
        webParseUseCase.evaluateScript(sourceBean, url, view)
    }

    override fun getWebPlayUrlIfNeeded(webPlayUrl: String?): String {
        return webParseUseCase.getWebPlayUrlIfNeeded(webPlayUrl) ?: ""
    }
}
