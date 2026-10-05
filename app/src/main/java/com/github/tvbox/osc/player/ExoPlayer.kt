package com.github.tvbox.osc.player

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import com.github.tvbox.osc.player.engine.CodecPreferences
import com.github.tvbox.osc.player.engine.PlayerEngine
import com.github.tvbox.osc.player.engine.PlayerEngineConfig
import com.github.tvbox.osc.player.effect.PictureEffects
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.player.state.PlaybackStateMachine
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import xyz.doikki.videoplayer.player.AbstractPlayer

/**
 * 新栈内核适配器(M7b):把旧 doikki `AbstractPlayer` 契约桥到新内核 [PlayerEngine]。
 *
 * <p>为什么保留类名与对外方法面:宿主([MyVideoView]/doikki `VideoView`)、控制器与调度层
 * (选轨菜单/OSD/重试阶梯/媒体会话/画面效果)都按本类型读取内核能力;类名与方法面不变 ⇒
 * 内核换代对它们是透明的(M7b 最小切换面),M7c 重写 `player/` 时再按终态收敛。
 *
 * <p>行为对齐(逐条对照旧 `ExoPlayer extends ExoMediaPlayer`):
 * 首次 READY 发 `onPrepared` + `onInfo(RENDERING_START)`(旧 `mIsPreparing` 语义,含 HLS 原地重试后的重发)、
 * 其后 BUFFERING/READY/ENDED 对 `onInfo(BUFFERING_*)`/`onCompletion`;
 * 视频尺寸经 [PlayerEngine.videoSizeListener] 回发,旋转非 0 补发 `onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED)`;
 * `keepRenderViewOnReset` 固定 true(宿主 `replay` 的复用分支);
 * `setStartPosition` 用基类记录值(旧 `ExoMediaPlayer.prepareAsync` 的 getStartPosition/markStartPositionApplied 契约)。
 */
class ExoPlayer(context: Context) : AbstractPlayer() {

    private val appContext: Context = context.applicationContext

    /** 本会话内核(每次 [initPlayer] 重建;release 后置空) */
    private var engine: PlayerEngine? = null

    /** 新栈播放状态机(M7b):桥把内核事件与播放命令投给它,M7c 起由调度层直读 */
    val stateMachine = PlaybackStateMachine()

    private var onCuesListener: OnCuesListener? = null

    private var useDiskCacheFlag = false

    private var contentKeyValue = ""

    /** prepareAsync 之后等待首个 STATE_READY(旧 doikki `mIsPreparing` 语义) */
    private var awaitingPrepared = false

    /** 内核未建立时暂存的播放速度(旧 doikki `mSpeedPlaybackParameters` 语义;重建内核后回灌) */
    private var pendingSpeed: Float? = null

    // ==================== AbstractPlayer:内核生命周期 ====================

    override fun initPlayer() {
        val config = PlayerEngineConfig(
            bufferTimes = bufferTimes(),
            tunnelingRequested = KV.get(HawkConfig.PLAY_TUNNEL, false),
            surfaceRender = KV.get(HawkConfig.PLAY_RENDER, 1) == 1,
            preferAac = KV.get(HawkConfig.PLAY_PREFER_AAC, false),
            dynamicScheduling = KV.get(
                HawkConfig.EXO_VIDEO_DYNAMIC_SCHEDULING,
                HawkConfig.EXO_VIDEO_DYNAMIC_SCHEDULING_DEFAULT,
            ),
            playbackLooper = if (PreloadManagerHolder.enabled()) PreloadManagerHolder.preloadLooper() else null,
            preloadTargetChecker = { url, headers -> PreloadManagerHolder.isPreloadTargetUrl(url, headers) },
            playCacheEnabled = { KV.get(HawkConfig.PLAY_CACHE, false) },
        )
        val newEngine = PlayerEngine(appContext, config)
        engine = newEngine
        newEngine.setUseDiskCache(useDiskCacheFlag)
        newEngine.setContentKey(contentKeyValue)
        pendingSpeed?.let { newEngine.setSpeed(it) }
        newEngine.videoSizeListener = PlayerEngine.VideoSizeListener { width, height, rotation ->
            mPlayerEventListener?.onVideoSizeChanged(width, height)
            if (rotation > 0) {
                mPlayerEventListener?.onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, rotation)
            }
        }
        newEngine.playbackStateListener = { state -> dispatchPlaybackState(state) }
        newEngine.retryAsHlsListener = { awaitingPrepared = true }
        newEngine.addErrorListener { _, _ ->
            stateMachine.onError()
            mPlayerEventListener?.onError()
        }
        newEngine.setOnCuesListener { cues -> onCuesListener?.onCues(cues) }
        LOG.i("echo-m7b-engine-bridge-ready")
    }

    override fun setDataSource(path: String, headers: Map<String, String>?) {
        stateMachine.onContentReplaced()
        engine?.setDataSource(path, headers, KV.get(HawkConfig.PLAYER_IS_LIVE, false))
    }

    override fun setDataSource(fd: AssetFileDescriptor?) {
        // 不支持 AssetFileDescriptor 方式(旧实现为空,保持不变)
    }

    override fun prepareAsync() {
        val current = engine ?: return
        PictureEffects.onPrepare(this, current.isTunnelingEnabled)
        current.setStartPosition(startPosition)
        // 无源/无内核不下发也不进入"等待 onPrepared"(旧 doikki 在 setMediaSource 前的早退语义)
        if (!current.prepare()) return
        awaitingPrepared = true
        stateMachine.onPrepareRequested()
        markStartPositionApplied()
    }

    override fun start() {
        engine?.start()
        stateMachine.onPlayRequested()
    }

    override fun pause() {
        engine?.pause()
        stateMachine.onPauseRequested()
    }

    override fun stop() {
        engine?.stop()
        stateMachine.onStopRequested()
    }

    override fun reset() {
        awaitingPrepared = false
        engine?.reset()
        stateMachine.onReset()
    }

    override fun release() {
        PictureEffects.onPlayerReleased(this)
        engine?.release()
        engine = null
        awaitingPrepared = false
        stateMachine.onReset()
    }

    override fun keepRenderViewOnReset(): Boolean = true

    override fun resetTrackSelection() {
        engine?.resetTrackSelection()
    }

    override fun seekTo(time: Long) {
        if (stateMachine.currentState == PlayState.PAUSED) {
            stateMachine.onSeekWhilePaused()
        }
        engine?.seekTo(time)
    }

    override fun isPlaying(): Boolean = engine?.isPlaying ?: false

    override fun getCurrentPosition(): Long = engine?.currentPosition ?: 0L

    override fun getDuration(): Long = engine?.duration ?: 0L

    override fun getBufferedPercentage(): Int = engine?.bufferedPercentage ?: 0

    override fun getTcpSpeed(): Long = engine?.tcpSpeed ?: 0L

    override fun getSpeed(): Float = engine?.speed ?: pendingSpeed ?: 1f

    override fun setSpeed(speed: Float) {
        pendingSpeed = speed
        engine?.setSpeed(speed)
    }

    override fun setSurface(surface: Surface?) {
        engine?.setVideoSurface(surface)
    }

    override fun setDisplay(holder: SurfaceHolder?) {
        engine?.setDisplay(holder)
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        engine?.setVolume(leftVolume, rightVolume)
    }

    override fun setLooping(isLooping: Boolean) {
        engine?.setLooping(isLooping)
    }

    override fun setOptions() {
        engine?.setOptions()
    }

    // ==================== 内核事件 → 旧契约回调 ====================

    private fun dispatchPlaybackState(state: Int) {
        if (awaitingPrepared) {
            if (state == Player.STATE_READY) {
                awaitingPrepared = false
                stateMachine.onPrepared()
                stateMachine.onRenderingStart()
                val listener = mPlayerEventListener ?: return
                listener.onPrepared()
                listener.onInfo(MEDIA_INFO_RENDERING_START, 0)
            }
            return
        }
        when (state) {
            Player.STATE_BUFFERING -> {
                stateMachine.onBufferingStart()
                mPlayerEventListener?.onInfo(MEDIA_INFO_BUFFERING_START, engine?.bufferedPercentage ?: 0)
            }

            Player.STATE_READY -> {
                stateMachine.onBufferingEnd()
                mPlayerEventListener?.onInfo(MEDIA_INFO_BUFFERING_END, engine?.bufferedPercentage ?: 0)
            }

            Player.STATE_ENDED -> {
                stateMachine.onCompletion()
                mPlayerEventListener?.onCompletion()
            }
        }
    }

    // ==================== app 扩展面(调用点零改动的承接) ====================

    /** 本片记忆键(见 TrackMemory);内核重建后由 [initPlayer] 重新推给新实例 */
    fun setContentKey(key: String?) {
        contentKeyValue = key ?: ""
        engine?.setContentKey(contentKeyValue)
    }

    /** 点播磁盘缓存标记(第二期「边播边缓存」;由 MyVideoView 注入,直播页恒 false) */
    fun setUseDiskCache(enabled: Boolean) {
        useDiskCacheFlag = enabled
        engine?.setUseDiskCache(enabled)
    }

    /** 输出分辨率信令(纹理渲染路径由 MyVideoView 推;Surface 路径由内核 setDisplay 内部补发) */
    fun notifyVideoOutputResolution(width: Int, height: Int) {
        engine?.notifyVideoOutputResolution(width, height)
    }

    fun redrawVideoFrame() {
        engine?.redrawVideoFrame()
    }

    fun applyVideoEffects(effects: List<Effect>) {
        engine?.applyVideoEffects(effects)
    }

    fun isPictureEffectsActive(): Boolean = engine?.isPictureEffectsActive ?: false

    fun isPictureHdrSource(): Boolean = engine?.isPictureHdrSource ?: false

    val isTunnelingEnabled: Boolean
        get() = engine?.isTunnelingEnabled ?: false

    fun getTrackInfo(): TrackInfo = engine?.getTrackInfo() ?: TrackInfo()

    fun setTrack(track: TrackInfoBean?) {
        engine?.setTrack(track)
    }

    fun selectTrack(track: TrackInfoBean?) {
        engine?.selectTrack(track)
    }

    fun restoreTracks() {
        engine?.restoreTracks()
    }

    fun loadDefaultSubtitleTrack() {
        engine?.loadDefaultSubtitleTrack()
    }

    fun ensureSubtitleTrackSelected() {
        engine?.ensureSubtitleTrackSelected()
    }

    fun setOnCuesListener(listener: OnCuesListener?) {
        onCuesListener = listener
        engine?.setOnCuesListener(listener?.let { target -> { cues -> target.onCues(cues) } })
    }

    fun setInternalSubtitleDelay(milliseconds: Int) {
        engine?.setInternalSubtitleDelay(milliseconds)
    }

    fun droppedFrames(): Long = engine?.droppedFrames ?: 0L

    fun rebufferCount(): Int = engine?.rebufferCount ?: 0

    fun videoDecoderName(): String = engine?.videoDecoderName ?: ""

    fun measuredFrameRate(): Float = engine?.measuredFrameRate ?: 0f

    fun sampleFrameRate() {
        engine?.sampleFrameRate()
    }

    fun setFrameRateTracking(enabled: Boolean) {
        engine?.setFrameRateTracking(enabled)
    }

    val selectedVideoFormat: Format?
        get() = engine?.getSelectedVideoFormat()

    val selectedAudioFormat: Format?
        get() = engine?.getSelectedAudioFormat()

    fun lastErrorKind(): Int = engine?.lastErrorKind ?: ERROR_KIND_UNKNOWN

    private fun bufferTimes(): Int {
        val value = KV.get(HawkConfig.BUFFER_TIMES, HawkConfig.BUFFER_TIMES_DEFAULT)
        return value.coerceIn(1, 10)
    }

    /** 内核字幕回调(内置字幕渲染由内核完成,这里只透给播放器的外挂字幕面板) */
    @JvmSuppressWildcards
    fun interface OnCuesListener {
        fun onCues(cues: List<Cue>)
    }

    companion object {

        const val ERROR_KIND_UNKNOWN = 0
        const val ERROR_KIND_NETWORK = 1
        const val ERROR_KIND_DECODE = 2

        /** 下发 EXO 解码方式:true = 软解(系统软件解码器优先)。真值源收口在新内核层 CodecPreferences(双栈同一偏好)。 */
        @JvmStatic
        fun setPreferSoftwareDecode(prefer: Boolean) {
            CodecPreferences.setPreferSoftwareDecode(prefer)
        }

        /** 当前已下发的 EXO 解码方式(供 PlayerHelper 判断"这次起的解码方式变了没") */
        @JvmStatic
        fun isPreferSoftwareDecode(): Boolean = CodecPreferences.isPreferSoftwareDecode()
    }
}
