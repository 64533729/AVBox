package com.github.tvbox.osc.player.controller

import com.github.tvbox.osc.util.LOG
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.BatteryManager
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.webkit.WebView
import android.widget.Toast
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.SubtitleView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.ParseBean
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.ExoPlayer
import com.github.tvbox.osc.player.AppPlayerView
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.state.LockVisibility
import com.github.tvbox.osc.player.state.ParamsChoice
import com.github.tvbox.osc.player.state.ParamsSheetState
import com.github.tvbox.osc.player.effect.PictureEffects
import com.github.tvbox.osc.player.effect.anime4k.Anime4kSettings
import com.github.tvbox.osc.player.effect.anime4k.Anime4kTier
import com.github.tvbox.osc.player.state.PictureParamsState
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.player.state.VideoSizeGate
import com.github.tvbox.osc.player.state.SelectDialogState
import com.github.tvbox.osc.player.ui.PlayerOverlay
import com.github.tvbox.osc.player.ui.VideoGestureHandler
import com.github.tvbox.osc.player.usecase.M3u8PurifyUseCase
import com.github.tvbox.osc.player.usecase.PlayerSwitchUseCase
import com.github.tvbox.osc.player.usecase.WebParseUseCase
import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.util.DanmuHelper
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.PlayerHelper
import com.github.tvbox.osc.util.SubtitleHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.PlaybackProgress
import com.github.tvbox.osc.util.PlayerUtils
import org.greenrobot.eventbus.EventBus
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale

@Suppress("MemberVisibilityCanBePrivate")
class ComposeVideoController @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr),
    AppPlayerView.VideoControllerHost,
    PlayerControlApi,
    PlayerActions {

    companion object {

        private const val LOCK_HIDE_DELAY_MS = 3000L
        private const val SPEED_RETRY_MAX = 30
        private const val SEEK_MAX = 1000
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
            if (!state.controlsVisible) applyShowBottom()
            stopProgress()
        }
    }

    internal fun exitGestureSeek() {
        state.dragging = false
        startProgress()
        keepControlsAlive()
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

    private var videoView: MyVideoView? = null

    override fun setKernelProvider(view: MyVideoView?) {
        videoView = view
        view?.setVideoController(this)
    }

    private lateinit var gestureActions: VideoGestureActionsImpl

    internal lateinit var gestureHandler: VideoGestureHandler

    private var canChangePosition = true
    private var enableInNormal = false
    private var gestureSwitch = true

    private lateinit var mSubtitleView: SimpleSubtitleView
    private lateinit var mLyricView: SimpleSubtitleView
    private lateinit var mExoSubtitleView: SubtitleView

    private val videoSizeGate = VideoSizeGate()

    internal var previewMode = false
    internal var speedOld = 1.0f
    private var speedRetryCount = 0
    private var skipEnd = true
    private var isClickBackBtn = false
    private var showParseFlag = false
    internal var playerConfig: JSONObject? = null
    private var listener: VodControlListener? = null

    private var keySeekProgress = 0

    private val idleHideMillis = 10000L

    private val uiHandler by lazy { Handler(Looper.getMainLooper()) }

    private var progressTicking = false
    private val progressRunnable by lazy { Runnable { onProgressTick() } }
    private val idleHideRunnable by lazy {
        Runnable {
            if (state.overlayPanelOpen) keepControlsAlive() else hideBottom()
        }
    }
    private val lockHideRunnable by lazy { Runnable { state.lockState = LockVisibility.HIDDEN } }
    private val keySeekCommitRunnable by lazy { Runnable { commitKeySeek() } }
    private val speedRetryRunnable by lazy { Runnable { applySpeedWhenReady() } }

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

    private val fastClickMap = HashMap<String, Long>()

    private fun fastClickAllowed(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = fastClickMap[key] ?: 0L
        if (now - last < 500) return false
        fastClickMap[key] = now
        return true
    }

    init {
        state = PlayerUiState()

        gestureActions = VideoGestureActionsImpl(this)
        gestureHandler = VideoGestureHandler(gestureActions)

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
                        actions = this@ComposeVideoController,
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
        uiHandler.removeCallbacks(keySeekCommitRunnable)
        uiHandler.removeCallbacks(speedRetryRunnable)
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
            state.duration = 0
            state.position = 0
        }
        PlayState.PLAYING -> {
            initOrientationState()
            startProgress()
        }
        PlayState.PAUSED -> {
            if (!state.lifecyclePaused) {
                state.topLeftVisible = false
                state.netSpeedTopRightVisible = false
                if (state.controlsVisible) hideBottom()
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
        state.duration = durationMs
        state.position = positionMs
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

    private fun stopProgress() {
        if (!progressTicking) return
        uiHandler.removeCallbacks(progressRunnable)
        progressTicking = false
    }

    private fun savePlaybackProgress(notifyHistory: Boolean, seekTargetMs: Int = -1) {
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

    override fun toggleControls() {
        if (!state.controlsVisible) showBottom() else hideBottom()
    }

    override fun toggleControlBar() {
        toggleControls()
    }

    private fun showBottom() {
        applyShowBottom()
    }

    private fun applyShowBottom() {
        updateDanmuSearchBtnState()
        state.controlsVisible = true
        state.topLeftVisible = true
        state.topRightVisible = true
        state.netSpeedTopRightVisible = true
        state.sysTimeVisible = true
        state.backVisible = !state.isPortrait
        showLockView()
        keepControlsAlive()
    }

    fun hideBottom() {
        uiHandler.removeCallbacks(idleHideRunnable)
        if (state.dragging) onSeekCancelled()
        state.controlsVisible = false
        state.topLeftVisible = false
        state.netSpeedTopRightVisible = false
        state.sysTimeVisible = false
        state.backVisible = false
        uiHandler.removeCallbacks(lockHideRunnable)
        if (state.lockState != LockVisibility.GONE) {
            state.lockState = LockVisibility.HIDDEN
        }
    }

    override fun keepControlsAlive() {
        if (state.controlsVisible) {
            uiHandler.removeCallbacks(idleHideRunnable)
            uiHandler.postDelayed(idleHideRunnable, idleHideMillis)
        }
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

    private fun updatePlayerCfgState() {
        val cfg = playerConfig ?: return
        try {
            val playerType = cfg.getInt("pl")
            state.playerType = playerType
            val start = cfg.getInt("st")
            val end = cfg.getInt("et")
            state.timeStartText = if (start == 0) "" else PlayerUtils.stringForTime(start * 1000)
            state.timeEndText = if (end == 0) "" else PlayerUtils.stringForTime(end * 1000)
            refreshParamsSheet()
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    private val speedOptions = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)

    private fun speedIndex(value: Float): Int {
        val idx = speedOptions.indexOfFirst { it == value }
        return if (idx >= 0) idx else speedOptions.indexOfFirst { it == 1.0f }
    }

    private fun sheetPlayerOrder(types: List<Int>): List<Int> {
        val head = listOf(2)
        return head.filter { types.contains(it) } + types.filter { it !in head }
    }

    private fun buildParamsSheet(): ParamsSheetState? {
        val cfg = playerConfig ?: return null
        val speed = cfg.optDouble("sp", 1.0).toFloat()
        val playerType = cfg.optInt("pl", 2)
        val players = sheetPlayerOrder(PlayerHelper.getExistPlayerTypes())
        val scaleType = cfg.optInt("sc", 0)
        return ParamsSheetState(
            speed = ParamsChoice(
                options = speedOptions.map { "${it}x" },
                selected = speedIndex(speed),
                onSelect = { applySpeed(speedOptions[it]) },
            ),
            decode = decodeChoice(cfg),
            player = ParamsChoice(
                options = players.map { PlayerHelper.getPlayerName(it) },
                selected = players.indexOf(playerType).coerceAtLeast(0),
                onSelect = { applyPlayer(players[it]) },
            ),
            scale = ParamsChoice(
                options = (0..5).map { PlayerHelper.getScaleName(it) },
                selected = scaleType.coerceIn(0, 5),
                onSelect = { applyScale(it) },
            ),
            picture = PictureParamsState(
                preset = PictureEffects.preset(),
                tuning = PictureEffects.custom(),
                unavailableReason = PictureEffects.unavailableReason(),
                anime4kTierText = context.getString(Anime4kTier.current().labelRes),
                anime4kEnabled = Anime4kSettings.enabled(),
                anime4kUnavailable = PictureEffects.anime4kUnavailable(),
                anime4kSharpen = Anime4kSettings.sharpen(),
                anime4kDeblur = Anime4kSettings.deblur(),
                onAnime4kToggled = {
                    Anime4kSettings.setEnabled(it)
                    restartForPictureIfNeeded()
                    refreshParamsSheet()
                },
                onAnime4kSharpenChanged = { PictureEffects.setAnime4kSharpen(it) },
                onAnime4kDeblurToggled = {
                    Anime4kSettings.setDeblur(it)
                    restartForPictureIfNeeded()
                    refreshParamsSheet()
                },
                onPresetSelected = {
                    PictureEffects.selectPreset(it)
                    restartForPictureIfNeeded()
                    refreshParamsSheet()
                },
                onTuningChanged = {
                    PictureEffects.setCustom(it)
                    restartForPictureIfNeeded()
                },
                onReset = {
                    PictureEffects.reset()
                    restartForPictureIfNeeded()
                    refreshParamsSheet()
                },
                onCompareChanged = {
                    PictureEffects.compare(it)
                    restartForPictureIfNeeded()
                },
            ),
            timeStartText = state.timeStartText,
            timeEndText = state.timeEndText,
            onSetTimeStart = { markTimeStart() },
            onSetTimeEnd = { markTimeEnd() },
            onResetTime = { onTimeResetClicked() },
            onSearchDanmu = if (state.danmuSearchAvailable) {
                { onDanmuSearchClicked() }
            } else {
                null
            },
        )
    }

    private fun refreshParamsSheet() {
        if (state.paramsSheet == null) return
        state.paramsSheet = buildParamsSheet()
    }

    private fun restartForPictureIfNeeded() {
        if (PictureEffects.consumeRestartNeeded()) listener?.replay(false)
    }

    private fun decodeChoice(cfg: JSONObject): ParamsChoice {
        val isSoft = cfg.optString("exo", "硬解码") == "软解码" // i18n: keep
        return ParamsChoice(
            options = listOf(
                context.getString(R.string.player_decode_hard),
                context.getString(R.string.player_decode_soft),
            ),
            selected = if (isSoft) 1 else 0,
            onSelect = { applyDecode(if (it == 1) "软解码" else "硬解码") }, // i18n: keep
        )
    }

    private fun applySpeed(value: Float) {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            cfg.put("sp", value.toDouble())
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
            speedOld = value
            videoView?.setSpeed(value)
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    private fun applyScale(index: Int) {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            cfg.put("sc", index)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
            videoView?.setScreenScaleType(index)
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    private fun applyPlayer(playerType: Int) {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            if (playerType == cfg.optInt("pl", 2)) return
            cfg.put("pl", playerType)
            listener?.setAllowSwitchPlayer(false)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
            listener?.replay(false)
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    private fun applyDecode(value: String) {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            val unchanged = cfg.optString("exo") == value
            cfg.put("exo", value) // i18n: keep
            cfg.put("exoSet", 1)
            listener?.setAllowDecodeFallback(false)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
            if (!unchanged) listener?.replay(false)
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    private fun updateDanmuBtnState() {
        state.danmuOpen = DanmuHelper.isOpen()
    }

    private fun updateDanmuSearchBtnState() {
        state.danmuSearchAvailable = ApiConfig.get().hasDanmuSearchUi()
    }

    private fun updateLiveButtonsState() {
        state.liveButtonsVisible = runCatching { videoView?.duration ?: 0L != 0L }.getOrDefault(true)
    }

    private fun applySpeedWhenReady() {
        if (isInPlaybackState()) {
            speedRetryCount = 0
            try {
                playerConfig?.let { videoView?.setSpeed(it.getDouble("sp").toFloat()) }
            } catch (e: JSONException) {
                LOG.e("ComposeVideoController", e)
            }
        } else if (speedRetryCount < SPEED_RETRY_MAX) {
            speedRetryCount++
            uiHandler.removeCallbacks(speedRetryRunnable)
            uiHandler.postDelayed(speedRetryRunnable, 100)
        }
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
        updatePlayerCfgState()
    }

    override fun showParse(userJxList: Boolean) {
        showParseFlag = userJxList
        state.showParseRow = userJxList
    }

    override fun setPreviewMode(previewMode: Boolean) {
        this.previewMode = previewMode
        state.previewMode = previewMode
        if (previewMode && state.controlsVisible) hideBottom()
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
    }

    override fun setLifecyclePaused(paused: Boolean) {
        state.lifecyclePaused = paused
        if (paused) uiHandler.removeCallbacks(idleHideRunnable) else keepControlsAlive()
    }

    override fun resetSpeed() {
        skipEnd = true
        applySpeedWhenReady()
    }

    override fun onBackPressed(): Boolean {
        if (isClickBackBtn) {
            isClickBackBtn = false
            if (state.controlsVisible) hideBottom()
            return false
        }
        if (state.controlsVisible) {
            hideBottom()
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

    override fun onNextClicked() {
        listener?.playNext(false)
        hideBottom()
    }

    override fun onPreClicked() {
        listener?.playPre()
        hideBottom()
    }

    override fun onPlayPauseClicked() {
        if (!fastClickAllowed("play_pause")) return
        if (state.tipVisible && !isInPlaybackState()) return
        videoView?.togglePlay()
        keepControlsAlive()
    }

    override fun onRefreshClicked() {
        listener?.replay(false)
        hideBottom()
    }

    override fun onScaleClicked() {
        keepControlsAlive()
        showScaleDialog()
    }

    override fun onScaleLongClicked() {
        keepControlsAlive()
        if (!fastClickAllowed("scale_long")) return
        applyScale(0)
    }

    override fun onSpeedClicked() {
        keepControlsAlive()
        showSpeedDialog()
    }

    override fun onSpeedLongClicked() {
        keepControlsAlive()
        if (!fastClickAllowed("speed_long")) return
        applySpeed(1.0f)
    }

    override fun onPlayerClicked() {
        keepControlsAlive()
        val cfg = playerConfig ?: return
        val existPlayerTypes = PlayerHelper.getExistPlayerTypes()
        if (existPlayerTypes.isEmpty()) return
        val current = cfg.optInt("pl", 2)
        var nextIdx = 0
        for (i in existPlayerTypes.indices) {
            if (current == existPlayerTypes[i]) {
                nextIdx = if (i == existPlayerTypes.size - 1) 0 else i + 1
            }
        }
        applyPlayer(existPlayerTypes[nextIdx])
        hideBottom()
    }

    override fun onPlayerLongClicked() {
        keepControlsAlive()
        if (!fastClickAllowed("player_long")) return
        try {
            val cfg = playerConfig ?: return
            val playerType = cfg.getInt("pl")
            var defaultPos = 0
            val players = PlayerHelper.getExistPlayerTypes()
            val names = ArrayList<String>()
            for (p in players.indices) {
                names.add(PlayerHelper.getPlayerName(players[p]))
                if (players[p] == playerType) {
                    defaultPos = p
                }
            }
            state.selectDialog = SelectDialogState(
                tip = context.getString(R.string.player_select_player),
                items = names,
                defaultIndex = defaultPos,
                onSelected = { pos ->
                    if (players[pos] != playerType) {
                        applyPlayer(players[pos])
                        hideBottom()
                    }
                },
            )
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    override fun onTimeStartClicked() {
        keepControlsAlive()
        markTimeStart()
    }

    override fun onTimeStartLongClicked() {
        setTimeMark("st", 0)
    }

    override fun onTimeEndClicked() {
        keepControlsAlive()
        markTimeEnd()
    }

    override fun onTimeEndLongClicked() {
        setTimeMark("et", 0)
    }

    override fun onTimeResetClicked() {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            cfg.put("st", 0)
            cfg.put("et", 0)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    private fun markTimeStart() {
        val view = videoView ?: return
        val current = PlayerUtils.safeTimeMs(view.currentPosition)
        if (current > PlayerUtils.safeTimeMs(view.duration) / 2) return
        setTimeMark("st", current / 1000)
    }

    private fun markTimeEnd() {
        val view = videoView ?: return
        val current = PlayerUtils.safeTimeMs(view.currentPosition)
        val duration = PlayerUtils.safeTimeMs(view.duration)
        if (current < duration / 2) return
        setTimeMark("et", (duration - current) / 1000)
    }

    private fun setTimeMark(key: String, seconds: Int) {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            cfg.put(key, seconds)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    override fun onEpisodeClicked() {
        if (!fastClickAllowed("episode")) return
        listener?.showEpisodes()
        keepControlsAlive()
    }

    override fun onCastClicked() {
        listener?.clickCast()
    }

    override fun onSubtitleClicked() {
        if (!fastClickAllowed("zimu")) return
        listener?.selectSubtitle()
        keepControlsAlive()
    }

    override fun onSubtitleLongClicked() {
        if (!fastClickAllowed("zimu_long")) return
        listener?.closeSubtitles()
        hideBottom()
        Toast.makeText(context, context.getString(R.string.player_subtitle_closed), Toast.LENGTH_SHORT).show()
    }

    override fun onAudioTrackClicked() {
        if (!fastClickAllowed("audio")) return
        listener?.selectAudioTrack()
        keepControlsAlive()
    }

    override fun onVideoTrackClicked() {
        if (!fastClickAllowed("video")) return
        listener?.selectVideoTrack()
        keepControlsAlive()
    }

    override fun onDanmuSettingClicked() {
        if (!fastClickAllowed("danmu")) return
        listener?.showDanmuSetting()
    }

    override fun onDanmuSettingLongClicked() {
        if (!fastClickAllowed("danmu_long")) return
        val opened = listener?.toggleDanmu() ?: false
        hideBottom()
        Toast.makeText(context, context.getString(if (opened) R.string.player_danmu_opened else R.string.player_danmu_temp_closed), Toast.LENGTH_SHORT).show()
    }

    override fun onDanmuSearchClicked() {
        listener?.searchDanmuUi(false)
        hideBottom()
    }

    override fun onDanmuSearchLongClicked() {
        listener?.searchDanmuUi(true)
        hideBottom()
    }

    override fun onRotateClicked() {
        if (state.locked) return
        if (!fastClickAllowed("rotate")) return
        val toPortrait =
            resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT
        playerActivity()?.requestedOrientation =
            if (toPortrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideBottom()
    }

    override fun onParamsClicked() {
        keepControlsAlive()
        state.paramsSheet = buildParamsSheet()
    }

    override fun onInfoOsdClicked() {
        state.infoOsdVisible = !state.infoOsdVisible
        val exo = videoView?.mediaPlayer as? ExoPlayer
        if (state.infoOsdVisible) {
            exo?.setFrameRateTracking(true)
            refreshInfoOsd(runCatching { videoView?.tcpSpeed ?: 0L }.getOrDefault(0L))
        } else {
            exo?.setFrameRateTracking(false)
        }
        if (state.overlayPanelOpen) keepControlsAlive() else hideBottom()
    }

    override fun onBackClicked() {
        isClickBackBtn = state.controlsVisible && !previewMode
        (playerActivity() as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
    }

    override fun onLockClicked() {
        val newLocked = !state.locked
        state.locked = newLocked
        if (newLocked) hideBottom()
        showLockView()
    }

    override fun onParseSelected(position: Int) {
        val parseBeanList = ApiConfig.get().parseBeanList
        if (position < 0 || position >= parseBeanList.size) return
        val parseBean: ParseBean = parseBeanList[position]
        ApiConfig.get().setDefaultParse(parseBean)
        state.parseListVersion++
        listener?.changeParse(parseBean)
        hideBottom()
    }

    override fun onSeekStarted() {
        if (!state.controlsVisible) applyShowBottom()
        if (state.dragging) return
        state.dragging = true
        stopProgress()
        uiHandler.removeCallbacks(idleHideRunnable)
        keepControlsAlive()
    }

    override fun onSeekPreview(progress: Int) {
        val view = videoView ?: return
        val duration = PlayerUtils.safeTimeMs(view.duration)
        state.seekPreviewPositionMs = seekBarToPosition(progress, duration)
    }

    override fun onSeekFinished(progress: Int) {
        keepControlsAlive()
        val view = videoView
        var seekTarget = -1
        if (view != null) {
            val duration = PlayerUtils.safeTimeMs(view.duration)
            seekTarget = seekBarToPosition(progress, duration).toInt()
            view.seekTo(seekTarget.toLong())
        }
        state.dragging = false
        keySeekProgress = 0
        startProgress()
        keepControlsAlive()
        if (seekTarget >= 0) savePlaybackProgress(notifyHistory = true, seekTargetMs = seekTarget)
    }

    override fun onSeekCancelled() {
        state.dragging = false
        keySeekProgress = 0
        startProgress()
        keepControlsAlive()
    }

    override fun onSeekStep(dir: Int) {
        val view = videoView ?: return
        val duration = PlayerUtils.safeTimeMs(view.duration)
        if (duration <= 0) return
        if (!state.controlsVisible) applyShowBottom()
        if (!state.dragging) {
            state.dragging = true
            stopProgress()
            uiHandler.removeCallbacks(idleHideRunnable)
        }
        keySeekProgress = (keySeekProgress + keySeekIncrement(duration) * dir).coerceIn(0, SEEK_MAX)
        state.seekPreviewPositionMs = seekBarToPosition(keySeekProgress, duration)
        updateSeekUiHint(
            PlayerUtils.safeTimeMs(view.currentPosition),
            state.seekPreviewPositionMs.toInt(),
        )
        uiHandler.removeCallbacks(keySeekCommitRunnable)
        uiHandler.postDelayed(keySeekCommitRunnable, 400)
    }

    private fun commitKeySeek() {
        if (!state.dragging) return
        onSeekFinished(keySeekProgress)
    }

    private fun seekBarToPosition(progress: Int, duration: Int): Long {
        if (duration <= 0) return 0L
        return duration.toLong() * progress / SEEK_MAX
    }

    private fun keySeekIncrement(duration: Int): Int {
        val increment: Long = when {
            duration > 3 * 60 * 60 * 1000 -> 5 * 60 * 1000L
            duration > 30 * 60 * 1000 -> 60 * 1000L
            duration > 15 * 60 * 1000 -> 30 * 1000L
            duration > 10 * 60 * 1000 -> 15 * 1000L
            else -> 10 * 1000L
        }
        return maxOf(1, (increment * SEEK_MAX / duration).toInt())
    }

    override fun refreshSystemInfo() {
        val view = videoView ?: return
        state.sysTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        readBattery()
        val speed = runCatching { view.tcpSpeed }.getOrDefault(0L)
        state.netSpeedTopRight = PlayerHelper.getDisplaySpeed(speed, true)
        state.netSpeedCenter = PlayerHelper.getDisplaySpeed(speed, false)
        val size = runCatching { view.videoSize }.getOrDefault(intArrayOf(0, 0))
        state.videoSize = videoSizeGate.textFor(size[0], size[1])
        if (state.infoOsdVisible) refreshInfoOsd(speed)
    }

    private fun refreshInfoOsd(speed: Long) {
        val view = videoView
        val exo = view?.mediaPlayer as? ExoPlayer
        val video = exo?.selectedVideoFormat
        val left = ArrayList<String>()
        val right = ArrayList<String>()

        left.add(context.getString(R.string.osd_video) + " " + videoText(video, exo))
        left.add(context.getString(R.string.osd_decoder) + " " + (exo?.videoDecoderName()?.takeIf { it.isNotEmpty() } ?: "-"))
        left.add(context.getString(R.string.osd_audio) + " " + audioText(exo?.selectedAudioFormat))

        exo?.sampleFrameRate()
        val throughput = runCatching {
            DefaultBandwidthMeter.getSingletonInstance(context).bitrateEstimate
        }.getOrDefault(0L)
        left.add(
            context.getString(R.string.osd_network) + " " + PlayerHelper.getDisplaySpeed(speed, true)
                + " · " + bitrateText(throughput) + marginText(throughput, video)
        )
        left.add(context.getString(R.string.osd_playback) + " " + playbackText(exo))
        val footer = context.getString(R.string.osd_config) + " " + configText(view, exo)
        left.add(
            context.getString(R.string.osd_conclusion) + " "
                + context.getString(if (state.playState == PlayState.ERROR) R.string.osd_abnormal else R.string.osd_normal)
        )

        right.add(
            context.getString(R.string.osd_device) + " " + Build.MODEL + " / " + Build.DEVICE + " / "
                + (Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
        )
        right.add(context.getString(R.string.osd_system) + " Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT)
        right.add(context.getString(R.string.osd_chip) + " " + chipText())
        right.add(context.getString(R.string.osd_screen) + " " + screenText())
        right.add("WebView " + webViewText())
        right.add(context.getString(R.string.osd_network_env) + " " + networkEnvText())

        state.infoOsdLeft = left
        state.infoOsdRight = right
        state.infoOsdFooter = footer
    }

    private fun videoText(format: Format?, exo: ExoPlayer?): String {
        if (format == null) return "-"
        val parts = ArrayList<String>()
        parts.add(videoCodecName(format))
        if (format.width > 0 && format.height > 0) parts.add(format.width.toString() + "x" + format.height)
        val fps = frameRateText(format, exo)
        if (fps.isNotEmpty()) parts.add(fps)
        val bitrate = bitrateText(format.bitrate.toLong())
        if (bitrate.isNotEmpty()) parts.add(bitrate)
        val codecs = format.codecs
        if (!codecs.isNullOrEmpty()) parts.add(codecs)
        return parts.joinToString(" · ")
    }

    private fun frameRateText(format: Format, exo: ExoPlayer?): String {
        if (format.frameRate > 0f) return format.frameRate.toInt().toString() + "fps"
        val measured = exo?.measuredFrameRate() ?: 0f
        if (measured <= 0f) return ""
        return String.format(Locale.US, "%.1ffps", measured)
    }

    private fun videoCodecName(format: Format): String = when (format.sampleMimeType) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "H.265"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_MP4V -> "MPEG-4"
        else -> format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US) ?: "-"
    }

    private fun audioText(format: Format?): String {
        if (format == null) return "-"
        val parts = ArrayList<String>()
        val codecs = format.codecs
        parts.add(if (!codecs.isNullOrEmpty()) codecs else format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US) ?: "-")
        if (format.channelCount > 0) parts.add(format.channelCount.toString() + ".0")
        if (format.sampleRate > 0) {
            val khz = format.sampleRate / 1000f
            parts.add((if (khz % 1f == 0f) khz.toInt().toString() else String.format(Locale.US, "%.1f", khz)) + "kHz")
        }
        return parts.joinToString(" · ")
    }

    private fun bitrateText(bps: Long): String {
        if (bps <= 0) return ""
        return String.format(Locale.US, "%.1fMbps", bps / 1000000f)
    }

    private fun marginText(throughput: Long, format: Format?): String {
        val bitrate = format?.bitrate ?: 0
        if (throughput <= 0 || bitrate <= 0) return ""
        return " · x" + String.format(Locale.US, "%.2f", throughput.toFloat() / bitrate)
    }

    private fun playbackText(exo: ExoPlayer?): String {
        val parts = ArrayList<String>()
        parts.add(context.getString(playStateRes()))
        parts.add(timeText(state.position) + " / " + timeText(state.duration))
        parts.add(context.getString(R.string.osd_dropped_frames, exo?.droppedFrames() ?: 0L))
        parts.add(context.getString(R.string.osd_rebuffer, exo?.rebufferCount() ?: 0))
        return parts.joinToString(" · ")
    }

    private fun playStateRes(): Int = when (state.playState) {
        PlayState.BUFFERING -> R.string.osd_state_buffering
        PlayState.PLAYING -> R.string.osd_state_playing
        PlayState.PAUSED -> R.string.osd_state_paused
        PlayState.COMPLETED -> R.string.osd_state_ended
        PlayState.ERROR -> R.string.osd_abnormal
        else -> R.string.osd_state_ready
    }

    private fun timeText(millis: Int): String {
        if (millis <= 0) return "00:00"
        val seconds = millis / 1000
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun decodeText(exo: ExoPlayer?): String {
        val name = exo?.videoDecoderName()?.takeIf { it.isNotEmpty() } ?: return "-"
        val software = name.startsWith("c2.android.") || name.startsWith("OMX.google.") ||
            name.startsWith("OMX.ffmpeg.") || name.contains(".sw.")
        return context.getString(if (software) R.string.player_decode_soft else R.string.player_decode_hard)
    }

    private fun configText(videoView: AppPlayerView?, exo: ExoPlayer?): String {
        val parts = ArrayList<String>()
        parts.add(context.getString(R.string.player_exo))
        parts.add(decodeText(exo))
        parts.add(if (videoView?.renderIsSurface == true) "Surface" else "Texture")
        parts.add(context.getString(R.string.osd_tunnel) + " " + onOffText(exo?.isTunnelingEnabled == true))
        parts.add(context.getString(R.string.osd_frame_rate_match) + " " + onOffText(false))
        parts.add(context.getString(R.string.osd_preload) + " " + onOffText(KV.get(HawkConfig.PRELOAD_NEXT_EPISODE, false) == true))
        parts.add(context.getString(R.string.osd_cache) + " " + onOffText(KV.get(HawkConfig.PLAY_CACHE, false) == true))
        return parts.joinToString(" · ")
    }

    private fun onOffText(on: Boolean): String = context.getString(if (on) R.string.common_on else R.string.common_off)

    private fun chipText(): String {
        val parts = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            Build.SOC_MODEL?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        }
        Build.HARDWARE?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        Build.BOARD?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return if (parts.isEmpty()) "-" else parts.joinToString(" / ")
    }

    @Suppress("DEPRECATION")
    private fun screenText(): String {
        val display = playerActivity()?.windowManager?.defaultDisplay ?: return "-"
        val parts = ArrayList<String>()
        val mode = display?.mode
        if (mode != null) {
            parts.add(mode.physicalWidth.toString() + "x" + mode.physicalHeight)
        } else {
            parts.add(resources.displayMetrics.widthPixels.toString() + "x" + resources.displayMetrics.heightPixels)
        }
        parts.add(String.format(Locale.US, "%.0fHz", display.refreshRate))
        return parts.joinToString(" · ")
    }

    private fun webViewText(): String {
        if (Build.VERSION.SDK_INT < 26) return "-"
        return WebView.getCurrentWebViewPackage()?.versionName ?: "-"
    }

    private fun networkEnvText(): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "-"
        val network = manager.activeNetwork ?: return "offline"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "offline"
        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        val validated = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "unvalidated"
        return type + " / " + validated + (if (manager.isActiveNetworkMetered) " metered" else " unmetered")
    }

    private fun readBattery() {
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            state.batteryPercent = if (level in 0..100) level else -1
            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            state.batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        }
    }

    override fun hideSeekHint() {
        state.seekHintVisible = false
    }

    override fun hideSlideHint() {
        state.slideHintVisible = false
    }

    private fun showScaleDialog() {
        try {
            val cfg = playerConfig ?: return
            val scaleType = cfg.getInt("sc")
            val scales = ArrayList<String>()
            for (i in 0..5) {
                scales.add(PlayerHelper.getScaleName(i))
            }
            state.selectDialog = SelectDialogState(
                tip = context.getString(R.string.player_select_scale),
                items = scales,
                defaultIndex = scaleType.coerceIn(0, 5),
                onSelected = { index -> applyScale(index) },
            )
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    private fun showSpeedDialog() {
        try {
            val cfg = playerConfig ?: return
            val speed = cfg.getDouble("sp").toFloat()
            val speeds = speedOptions.map { "${it}x" }
            state.selectDialog = SelectDialogState(
                tip = context.getString(R.string.player_select_speed),
                items = speeds,
                defaultIndex = speedIndex(speed),
                onSelected = { index -> applySpeed(speedOptions[index]) },
            )
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }
}
