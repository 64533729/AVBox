package com.github.tvbox.osc.player.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Size
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.player.PlayerCodecStats
import com.github.tvbox.osc.player.TrackInfo
import com.github.tvbox.osc.player.TrackInfoBean
import com.github.tvbox.osc.player.effect.PictureEffects
import com.github.tvbox.osc.player.effect.RedrawPolicy
import com.github.tvbox.osc.player.effect.ReplayableCacheVideoRenderer
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.TrackMemory
import okhttp3.OkHttpClient
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 内核装配参数(新栈在创建 [PlayerEngine] 时一次性提供;设置由调用方解析,内核层不读 KV)。
 *
 * @param bufferTimes 缓冲时长倍数(1..10,LoadControl 按 media3 默认值缩放)
 * @param tunnelingRequested 隧道模式开关(设置项)
 * @param surfaceRender true = Surface 渲染(隧道仅在此模式生效;内核按 `tunnelingRequested && surfaceRender` 计算)
 * @param preferAac 音频优先 AAC(隧道兼容)
 * @param dynamicScheduling 视频动态调度(时长→进度,对应隐藏设置 exo_video_dynamic_scheduling)
 * @param enableLog 追加 media3 EventLogger(排障用,默认关)
 * @param playbackLooper 播放线程(预载对齐:须与 DefaultPreloadManager 的 preloadLooper 一致)
 * @param okHttpClient 数据源 OkHttpClient(DoH/hosts/代理/SSL 都在 client 上);null = 回落 OkGoHelper 共享 client(再兜底类持有单例)
 * @param preloadTargetChecker 该 url+headers 是否为本会话预载目标(命中换用默认 key 的 cache 源)
 * @param playCacheEnabled 边播边缓存开关(实时读设置)
 */
data class PlayerEngineConfig(
    val bufferTimes: Int = DEFAULT_BUFFER_TIMES,
    val tunnelingRequested: Boolean = false,
    val surfaceRender: Boolean = true,
    val preferAac: Boolean = false,
    val dynamicScheduling: Boolean = true,
    val enableLog: Boolean = false,
    val playbackLooper: Looper? = null,
    val okHttpClient: OkHttpClient? = null,
    val preloadTargetChecker: (url: String, headers: Map<String, String>?) -> Boolean = { _, _ -> false },
    val playCacheEnabled: () -> Boolean = { false },
) {
    companion object {
        /** 缓冲倍数默认值:与 HawkConfig.BUFFER_TIMES_DEFAULT 一致(旧内核缺键口径 = 3) */
        const val DEFAULT_BUFFER_TIMES = 3
    }
}

/**
 * 新播放栈的内核适配层(media3 ExoPlayer 装配 + 内核能力门面)。
 *
 * <p>承接面 = doikki `ExoMediaPlayer`/`ExoMediaSourceHelper`/`OkHttpDataSource`/`HlsErrorHandlingPolicy`
 * 的 media3 适配 + 旧 `osc.player.ExoPlayer` 的内核装配逻辑:
 * DataSource/Renderers/LoadControl/TrackSelector/缓存/HLS/效果装配;RTMP live=1;自动软解选择器;
 * 输出分辨率信令(裸 Surface 必须补发 MSG_SET_VIDEO_OUTPUT_RESOLUTION);效果链与暂停态重绘;
 * 丢帧/重缓冲统计与错误分类;(轨道读写见类尾 `TrackSelector` 段)。
 *
 * <p>线程约定:与 media3 一致 —— 在创建线程(主线程)使用;内核回调亦在主线程。
 * 所有权不变:播放器实例由调用方(PlaybackService/PlaybackController)持有,**Compose 直持** [player] 同一实例。
 * 本类不接 UI、不做调度(取流/重试/会话/预载编排在 M7c 的调度层)。
 */
class PlayerEngine(
    context: Context,
    private val config: PlayerEngineConfig = PlayerEngineConfig(),
) {

    private val appContext: Context = context.applicationContext

    /** MediaSource 构建入口(预载侧等可复用同一实例) */
    val mediaSources = MediaSources(appContext, config.okHttpClient)

    /** 本工厂创建的视频渲染器实例(帧率匹配关闭/输出分辨率信令都按渲染器下发消息) */
    private val videoRenderers = ArrayList<Renderer>()

    private val trackSelector = DefaultTrackSelector(appContext)

    /** 唯一内核实例(Compose 直持);已释放后为 null(此时不可再操作) */
    private var internalPlayer: ExoPlayer? = null

    val player: ExoPlayer?
        get() = internalPlayer

    private var speedPlaybackParameters: PlaybackParameters? = null
    private var mediaSource: MediaSource? = null
    private var currentPlayPath: String? = null
    private var currentHeaders: Map<String, String>? = null
    private var retriedAsHls = false

    /** 点播磁盘缓存标记(边播边缓存,由调用方按场景注入;直播页恒 false) */
    private var useDiskCache = false

    /** 本片记忆键(见 TrackMemory);内核重建即新实例,故由调用方在起播前推入 */
    private var contentKey = ""

    @Volatile
    private var startPositionMs = 0L

    @Volatile
    private var startPositionApplied = false

    // ==================== 效果/渲染状态 ====================

    /** 效果管线是否已开通:开通后视频帧走 VideoSink,内核不再上报视频尺寸,须自行补报(见 reportVideoSizeFromTracks) */
    @Volatile
    private var videoEffectsOpen = false

    /** 本实例是否下发隧道模式(与效果管线互斥) */
    private var tunnelingEnabled = false

    /** 本集选中的视频轨是 HDR:效果链退化为纯拷贝,面板据此给原因 */
    @Volatile
    private var pictureHdrSource = false

    /** media3 的 Player 有线程校验,效果与重绘信令一律落主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastOutputWidth = 0
    private var lastOutputHeight = 0
    private var redrawScheduled = false

    /** 字幕延迟(微秒):文本渲染器代理在 render() 时回拨(主线程写、播放线程读) */
    @Volatile
    private var subtitleDelayUs = 0L
    private var onCuesListener: ((List<Cue>) -> Unit)? = null

    /** 视频尺寸变化(含 effects 打开时的 tracks 补报路径);unappliedRotationDegrees 无值时 0 */
    var videoSizeListener: VideoSizeListener? = null

    /** 播放状态变化(media3 `Player.STATE_*`);桥与新状态机都从这里取事件(M7b) */
    var playbackStateListener: ((Int) -> Unit)? = null

    /** 解析类错误已改 HLS 源原地重试(旧 `ExoMediaPlayer.retryAsHls` 的回调点):桥据此重挂"等待 onPrepared"语义 */
    var retryAsHlsListener: (() -> Unit)? = null

    /** 内核错误(内部已尝试的处理 —— 如 HLS 重试 —— 失败后才回调) */
    fun interface ErrorListener {
        fun onPlayerError(error: PlaybackException, kind: Int)
    }

    private val errorListeners = ArrayList<ErrorListener>()

    // ==================== 统计 ====================

    @Volatile
    private var lastErrorKindValue = ERROR_KIND_UNKNOWN

    @Volatile
    private var droppedFramesTotal = 0L

    @Volatile
    private var rebufferCountTotal = 0

    @Volatile
    private var playbackStarted = false
    private val renderedFrameCount = AtomicLong()

    @Volatile
    private var frameRateWindowStartMs = 0L

    @Volatile
    private var measuredFrameRateValue = 0f

    @Volatile
    private var frameRateTracking = false

    private val videoFrameListener = VideoFrameMetadataListener { _, _, _, _ ->
        renderedFrameCount.incrementAndGet()
    }

    // ==================== 初始化 ====================

    private val engineListener = object : Player.Listener {

        override fun onTracksChanged(tracks: Tracks) {
            loadDefaultSubtitleTrackBeforeReady()
            reportVideoSizeFromTracks(tracks)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                defaultSubtitleTrackSelectionClosed = true
            }
            playbackStateListener?.invoke(playbackState)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoEffectsOpen && videoSize.width > 0 && videoSize.height > 0) {
                videoEffectsOpen = false
                LOG.i("echo-picture-effects inactive: kernel reported video size")
            }
            videoSizeListener?.onVideoSizeChanged(videoSize.width, videoSize.height, videoSize.unappliedRotationDegrees)
        }

        override fun onCues(cueGroup: CueGroup) {
            onCuesListener?.invoke(cueGroup.cues)
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            droppedFramesTotal += droppedFrames
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                playbackStarted = true
            } else if (playbackState == Player.STATE_BUFFERING && playbackStarted) {
                rebufferCountTotal++
            }
        }
    }

    init {
        // ⚠️ 必须先把实例赋给 internalPlayer 再下发配置:下面的 applyPlaybackParameters/disableFrameRateMatching
        // 都经 internalPlayer 取内核(旧实现是 super.initPlayer() 先建实例再下发,同一顺序)
        val exo = createPlayer()
        internalPlayer = exo
        applyPlaybackParameters()
        disableFrameRateMatching()
        exo.addListener(engineListener)
        exo.addAnalyticsListener(analyticsListener)
        applyFrameRateTracking()
        LOG.i("echo-exo-cues-listener-ready")
    }

    private fun createPlayer(): ExoPlayer {
        val renderersFactory = EngineRenderersFactory(
            appContext,
            { subtitleDelayUs },
            videoRenderers,
            config.dynamicScheduling,
        )
            .setEnableDecoderFallback(true)
            // 音频硬解优先:MediaCodec 不支持的格式(AC3/DTS 类)才落到 ffmpeg 软解兜底
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        // 动态调度开关由自建渲染器带回,见 EngineRenderersFactory
        renderersFactory.forceDisableMediaCodecAsynchronousQueueing()
        LOG.i("echo-exo-disable-async-codec-queue")
        LOG.i("echo-exo-video-dynamic-scheduling: ${config.dynamicScheduling}")

        val bufferTimes = config.bufferTimes.coerceIn(1, 10)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * bufferTimes,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * bufferTimes,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()
        LOG.i("echo-exo-low-memory-load-control")

        val builder = ExoPlayer.Builder(
            appContext,
            renderersFactory,
            DefaultMediaSourceFactory(appContext),
            trackSelector,
            loadControl,
            DefaultBandwidthMeter.getSingletonInstance(appContext),
            DefaultAnalyticsCollector(Clock.DEFAULT),
        )
        // 预载对齐:播放线程与 DefaultPreloadManager 的 preloadLooper 一致(PreloadMediaSource 硬校验),
        // 不注入则交接预载源时抛 IllegalStateException(播放立即失败->自动重试切内核)。null = 不注入,保持 media3 默认。
        config.playbackLooper?.let { builder.setPlaybackLooper(it) }
        val exo = builder.build()
        // 准备好就开始播放(旧 setOptions 语义)
        exo.playWhenReady = true
        if (config.enableLog) {
            exo.addAnalyticsListener(EventLogger(trackSelector, "ExoPlayer"))
        }
        return exo
    }

    // ==================== 数据源 / 起播 ====================

    fun setDataSource(path: String, headers: Map<String, String>?) {
        setDataSource(path, headers, false)
    }

    /**
     * 设置播放地址:建 MediaSource(不 prepare)。[isLive] 用于 RTMP 直播补 `live=1` 后缀
     * (引擎模式切换严格早于起播,调用方按当前模式传值)。
     */
    fun setDataSource(path: String, headers: Map<String, String>?, isLive: Boolean) {
        LOG.i("echo-setDataSource:$path")
        var playPath = path
        if (SourcePolicy.isRtmp(playPath) && isLive) {
            val flagged = SourcePolicy.applyRtmpLiveFlag(playPath, true)
            if (flagged != playPath) {
                playPath = flagged
                LOG.i("echo-rtmp-live-flag: $playPath")
            }
        }
        currentPlayPath = playPath
        currentHeaders = copyHeaders(headers)
        retriedAsHls = false
        defaultSubtitleTrackSelected = false
        defaultSubtitleTrackSelectionClosed = false
        resetPlaybackStats()
        mediaSource = mediaSources.getMediaSource(playPath, copyHeaders(currentHeaders))

        val preloadTarget = config.preloadTargetChecker(playPath, headers)
        val playCacheWanted = useDiskCache && config.playCacheEnabled()
        val mode = SourcePolicy.resolveCacheMode(
            isLocalProxyUrl = SourcePolicy.isLocalProxyUrl(playPath),
            isRtmp = SourcePolicy.isRtmp(playPath),
            preloadTarget = preloadTarget,
            playCacheWanted = playCacheWanted,
        )
        if (mode == SourcePolicy.CacheMode.NONE) {
            if (preloadTarget || playCacheWanted) {
                LOG.i(
                    (if (SourcePolicy.isRtmp(playPath)) "echo-play-cache-skip-rtmp: " else "echo-play-cache-skip-local-proxy: ") + playPath,
                )
            }
            return
        }
        // 预载目标必须用与预缓存写盘一致的 key(media3 默认 key=uri);常规链路仍用 headers 后缀 key 防串缓存
        val cached = if (mode == SourcePolicy.CacheMode.PRELOAD_TARGET) {
            mediaSources.getPreloadTargetMediaSource(playPath, headers)
        } else {
            mediaSources.getMediaSource(playPath, headers, true)
        }
        mediaSource = cached
        LOG.i((if (mode == SourcePolicy.CacheMode.PRELOAD_TARGET) "echo-preload-disk-source: " else "echo-play-cache-source: ") + playPath)
    }

    private fun resetPlaybackStats() {
        droppedFramesTotal = 0
        rebufferCountTotal = 0
        playbackStarted = false
        PlayerCodecStats.videoDecoderName = ""
        renderedFrameCount.set(0)
        frameRateWindowStartMs = 0
        measuredFrameRateValue = 0f
    }

    /** 设置当前数据源准备完成后的起始播放位置(续播) */
    fun setStartPosition(positionMs: Long) {
        startPositionMs = maxOf(0L, positionMs)
        startPositionApplied = false
    }

    val isStartPositionApplied: Boolean
        get() = startPositionApplied

    /** 准备开始播放(异步):媒体源就绪后在这里下发,并应用速度与起始位置。返回是否真的下发了(无内核/无源 = false) */
    fun prepare(): Boolean {
        val exo = internalPlayer ?: return false
        val source = mediaSource ?: return false
        speedPlaybackParameters?.let { exo.setPlaybackParameters(it) }
        exo.setMediaSource(source, startPositionMs)
        startPositionApplied = true
        exo.prepare()
        return true
    }

    fun start() {
        internalPlayer?.playWhenReady = true
    }

    /**
     * 准备好就开始播放(旧 doikki `setOptions` 语义):`reset()` 会把 playWhenReady 停到 false,
     * 复用内核播下一段时宿主须在下发媒体源前显式调用(旧调用链 = reset -> setOptions -> prepare)。
     */
    fun setOptions() {
        internalPlayer?.playWhenReady = true
    }

    fun pause() {
        internalPlayer?.playWhenReady = false
    }

    fun stop() {
        internalPlayer?.stop()
    }

    fun seekTo(positionMs: Long) {
        internalPlayer?.seekTo(positionMs)
    }

    /** 重置内核到未装源状态(清媒体项与 HLS 重试标记);实例保留 */
    fun reset() {
        internalPlayer?.let {
            it.stop()
            it.clearMediaItems()
        }
        retriedAsHls = false
    }

    /** 释放内核(实例作废,之后 [player] 为 null) */
    fun release() {
        internalPlayer?.let {
            it.removeListener(engineListener)
            it.removeAnalyticsListener(analyticsListener)
            it.release()
        }
        internalPlayer = null
        speedPlaybackParameters = null
    }

    // ==================== 播放状态读取 ====================

    val isPlaying: Boolean
        get() {
            val exo = internalPlayer ?: return false
            return when (exo.playbackState) {
                Player.STATE_BUFFERING, Player.STATE_READY -> exo.playWhenReady
                else -> false
            }
        }

    val currentPosition: Long
        get() = internalPlayer?.currentPosition ?: 0L

    val duration: Long
        get() = internalPlayer?.duration ?: 0L

    val bufferedPercentage: Int
        get() = internalPlayer?.bufferedPercentage ?: 0

    val playbackState: Int
        get() = internalPlayer?.playbackState ?: Player.STATE_IDLE

    /** 挂/换显示面(Surface 渲染路径;裸 Surface 必须自行补发输出分辨率,见 [notifyVideoOutputResolution]) */
    fun setVideoSurface(surface: Surface?) {
        internalPlayer?.setVideoSurface(surface)
    }

    /**
     * 挂/换显示面(SurfaceView 路径)。裸 Surface 不走 media3 自动路径,**交面后立即按 surfaceFrame 补发**
     * MSG_SET_VIDEO_OUTPUT_RESOLUTION(与旧内核一致;漏发 = 效果管线每帧被丢弃)。
     */
    fun setDisplay(holder: SurfaceHolder?) {
        if (holder == null) {
            setVideoSurface(null)
            return
        }
        val surface = holder.surface
        setVideoSurface(surface)
        if (surface == null || !surface.isValid) return
        val frame = holder.surfaceFrame
        if (frame != null) {
            notifyVideoOutputResolution(frame.width(), frame.height())
        }
    }

    fun setVolume(leftVolume: Float, rightVolume: Float) {
        internalPlayer?.setVolume((leftVolume + rightVolume) / 2f)
    }

    fun setLooping(isLooping: Boolean) {
        internalPlayer?.repeatMode = if (isLooping) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    fun setSpeed(speed: Float) {
        val params = PlaybackParameters(speed)
        speedPlaybackParameters = params
        internalPlayer?.setPlaybackParameters(params)
    }

    val speed: Float
        get() = speedPlaybackParameters?.speed ?: 1f

    /** 当前缓冲网速(字节/秒;OSD 读取,见 [NetworkSpeed]) */
    val tcpSpeed: Long
        get() = NetworkSpeed.getNetSpeed(appContext)

    fun addErrorListener(listener: ErrorListener) {
        if (!errorListeners.contains(listener)) {
            errorListeners.add(listener)
        }
    }

    fun removeErrorListener(listener: ErrorListener) {
        errorListeners.remove(listener)
    }

    // ==================== 内核装配参数(隧道/选轨) ====================

    /** 隧道与音频偏好下发(创建内核后一次) */
    private fun applyPlaybackParameters() {
        if (internalPlayer == null) return
        tunnelingEnabled = config.tunnelingRequested && config.surfaceRender
        val builder = trackSelector.buildUponParameters()
        builder.setTunnelingEnabled(tunnelingEnabled)
        if (config.preferAac) {
            builder.setPreferredAudioMimeTypes(MimeTypes.AUDIO_AAC)
        }
        trackSelector.setParameters(builder.build())
        LOG.i(
            "echo-exo-tunnel-prefs: tunnel=${config.tunnelingRequested}, surfaceRender=${config.surfaceRender}, " +
                "preferAac=${config.preferAac}",
        )
    }

    val isTunnelingEnabled: Boolean
        get() = tunnelingEnabled

    val videoDecoderName: String
        get() = PlayerCodecStats.videoDecoderName

    val lastErrorKind: Int
        get() = lastErrorKindValue

    val droppedFrames: Long
        get() = droppedFramesTotal

    val rebufferCount: Int
        get() = rebufferCountTotal

    // ==================== 效果链 / 输出分辨率 ====================

    /** 下发画质效果(调色/超分)列表;失败只留痕,不让调色把播放带崩 */
    fun applyVideoEffects(effects: List<Effect>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { applyVideoEffects(effects) }
            return
        }
        val exo = internalPlayer ?: return
        try {
            exo.setVideoEffects(effects)
            videoEffectsOpen = true
        } catch (th: Throwable) {
            // 缺 media3-effect、非默认渲染器、DRM 等都会在这里抛:画面照常播,只是没有调色
            videoEffectsOpen = false
            LOG.e("PlayerEngine", "echo-picture-effects apply failed", th)
        }
    }

    /** 效果链是否真的挂着(apply 失败或非默认渲染器吞掉信令时翻 false),供面板提示"当前不可调色" */
    val isPictureEffectsActive: Boolean
        get() = videoEffectsOpen

    /** 本集视频轨是否 HDR(效果退化为纯拷贝,面板据此提示) */
    val isPictureHdrSource: Boolean
        get() = pictureHdrSource

    /**
     * 输出分辨率信令:裸 Surface(本项目直接 setVideoSurface)不走自动路径,**必须自己补发**
     * MSG_SET_VIDEO_OUTPUT_RESOLUTION,否则效果管线每帧被丢弃(黑屏)。
     *
     * <p>本地不去重:media3 按"同一显示面 + 同一尺寸"自己短路,而换面必须重发(换面会清掉 VideoSink 输出面信息)。
     */
    fun notifyVideoOutputResolution(width: Int, height: Int) {
        val exo = internalPlayer ?: return
        if (width <= 0 || height <= 0) return
        // 送屏画布尺寸:Anime4K 链末要按它出画(否则链内 2x 会被管线缩放器再抹一遍,放大成果到不了屏幕)
        PictureEffects.setOutputCanvas(width, height)
        val sizeChanged = width != lastOutputWidth || height != lastOutputHeight
        lastOutputWidth = width
        lastOutputHeight = height
        for (renderer in videoRenderers) {
            try {
                exo.createMessage(renderer)
                    .setType(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION)
                    .setPayload(Size(width, height))
                    .send()
            } catch (th: Throwable) {
                LOG.e("PlayerEngine", "echo-picture-output-resolution failed", th)
            }
        }
        // 暂停态换几何(退出全屏回小窗/转屏):信令更新了输出尺寸,但合成仍停在旧几何那一帧上
        if (RedrawPolicy.shouldRedrawOnGeometry(sizeChanged, isPlaying, redrawReady())) {
            redrawVideoFrame()
        }
    }

    /** 能重绘的前提:效果链已挂 && 视频渲染器带可重放缓存 */
    private fun redrawReady(): Boolean {
        if (!videoEffectsOpen) return false
        for (renderer in videoRenderers) {
            if (renderer is ReplayableCacheVideoRenderer) return true
        }
        return false
    }

    /** 让内核按当前参数/几何重绘最后一帧(暂停态唯一能更新画面的手段);同一帧内多次请求合并成一次 */
    fun redrawVideoFrame() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { redrawVideoFrame() }
            return
        }
        if (redrawScheduled || !redrawReady()) return
        redrawScheduled = true
        mainHandler.post {
            redrawScheduled = false
            val exo = internalPlayer ?: return@post
            if (!videoEffectsOpen) return@post
            try {
                exo.setVideoEffects(VideoFrameProcessor.REDRAW)
            } catch (th: Throwable) {
                LOG.e("PlayerEngine", "echo-picture-redraw failed", th)
            }
        }
    }

    /** 关闭帧率匹配:渲染器实例在创建后下发一次消息 */
    private fun disableFrameRateMatching() {
        val exo = internalPlayer ?: return
        for (renderer in videoRenderers) {
            try {
                exo.createMessage(renderer)
                    .setType(Renderer.MSG_SET_CHANGE_FRAME_RATE_STRATEGY)
                    .setPayload(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                    .send()
                LOG.i("echo-frameRate matching OFF -> ${renderer.javaClass.simpleName}")
            } catch (th: Throwable) {
                LOG.i("echo-frameRate matching OFF failed: $th")
            }
        }
    }

    // ==================== 帧率采样(OSD) ====================

    val measuredFrameRate: Float
        get() = measuredFrameRateValue

    /** 采样一次帧率(两次调用间隔 >= 1s 才更新) */
    fun sampleFrameRate() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (frameRateWindowStartMs == 0L) {
            frameRateWindowStartMs = now
            renderedFrameCount.set(0)
            return
        }
        val elapsed = now - frameRateWindowStartMs
        if (elapsed < 1000) return
        measuredFrameRateValue = renderedFrameCount.getAndSet(0) * 1000f / elapsed
        frameRateWindowStartMs = now
    }

    fun setFrameRateTracking(enabled: Boolean) {
        frameRateTracking = enabled
        frameRateWindowStartMs = 0
        renderedFrameCount.set(0)
        if (enabled) measuredFrameRateValue = 0f
        applyFrameRateTracking()
    }

    private fun applyFrameRateTracking() {
        val exo = internalPlayer ?: return
        if (frameRateTracking) {
            exo.setVideoFrameMetadataListener(videoFrameListener)
        } else {
            exo.clearVideoFrameMetadataListener(videoFrameListener)
        }
    }

    // ==================== 字幕(cues / 延迟) ====================

    fun setOnCuesListener(listener: ((List<Cue>) -> Unit)?) {
        onCuesListener = listener
    }

    fun setInternalSubtitleDelay(milliseconds: Int) {
        subtitleDelayUs = milliseconds * 1000L
    }

    // ==================== 轨道/格式读取(内核面) ====================

    fun getSelectedVideoFormat(): Format? = selectedFormat(C.TRACK_TYPE_VIDEO)

    fun getSelectedAudioFormat(): Format? = selectedFormat(C.TRACK_TYPE_AUDIO)

    private fun selectedFormat(trackType: Int): Format? {
        val exo = internalPlayer ?: return null
        val tracks = exo.currentTracks
        for (group in tracks.groups) {
            if (group.type != trackType || !group.isSelected) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) return group.getTrackFormat(i)
            }
        }
        return null
    }

    /** 选中的视频轨尺寸(effects 打开时内核不再上报尺寸,从这里补报) */
    private fun reportVideoSizeFromTracks(tracks: Tracks) {
        if (!videoEffectsOpen) return
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO || !group.isSelected) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val format = group.getTrackFormat(i)
                if (format.width <= 0 || format.height <= 0) return
                var width = format.width
                var height = format.height
                if (format.rotationDegrees == 90 || format.rotationDegrees == 270) {
                    val rotated = width
                    width = height
                    height = rotated
                }
                pictureHdrSource = ColorInfo.isTransferHdr(format.colorInfo)
                LOG.i(
                    "echo-picture-size: ${width}x$height rotation=${format.rotationDegrees} hdr=$pictureHdrSource",
                )
                videoSizeListener?.onVideoSizeChanged(width, height, 0)
                return
            }
        }
    }

    // ==================== 错误处理 ====================

    private fun handlePlayerError(error: PlaybackException) {
        val codeName = error.errorCodeName
        lastErrorKindValue = classifyError(codeName)
        LOG.e("Tvbox-runtime", "echo-Exo player error: $currentPlayPath", error)
        // 播放错误详情(错误码 + cause 链,排查播放失败用)
        val sb = StringBuilder("echo-exo-player-error: code=").append(codeName).append(", msg=").append(error.message)
        var cause = error.cause
        var i = 0
        while (cause != null && i < 5) {
            sb.append(" | cause[").append(i).append("]=")
                .append(cause.javaClass.simpleName).append(": ").append(cause.message)
            cause = cause.cause
            i++
        }
        LOG.i(sb.toString())
        if (retryAsHls(error)) {
            return
        }
        for (listener in ArrayList(errorListeners)) {
            listener.onPlayerError(error, lastErrorKindValue)
        }
    }

    /** 解析类错误:改 HLS 源原地重试一次(旧 ExoMediaPlayer.retryAsHls 语义) */
    private fun retryAsHls(error: PlaybackException): Boolean {
        val exo = internalPlayer ?: return false
        val path = currentPlayPath ?: return false
        if (retriedAsHls || !isParsingError(error)) {
            return false
        }
        retriedAsHls = true
        LOG.i("echo-Exo retry as HLS: $path")
        val hlsSource = mediaSources.getHlsMediaSource(path, copyHeaders(currentHeaders)) ?: return false
        mediaSource = hlsSource
        retryAsHlsListener?.invoke()
        exo.setMediaSource(hlsSource, startPositionMs)
        startPositionApplied = true
        exo.prepare()
        exo.playWhenReady = true
        return true
    }

    private fun copyHeaders(headers: Map<String, String>?): Map<String, String>? =
        headers?.let { HashMap(it) }

    companion object {

        const val ERROR_KIND_UNKNOWN = 0
        const val ERROR_KIND_NETWORK = 1
        const val ERROR_KIND_DECODE = 2

        /** 错误码名 → 粗分类(网络/解析 vs 解码) */
        @JvmStatic
        fun classifyError(codeName: String?): Int {
            if (codeName == null) return ERROR_KIND_UNKNOWN
            if (codeName.startsWith("ERROR_CODE_IO") || codeName.startsWith("ERROR_CODE_PARSING")) return ERROR_KIND_NETWORK
            if (codeName.startsWith("ERROR_CODE_DECOD")) return ERROR_KIND_DECODE
            return ERROR_KIND_UNKNOWN
        }

        /** 解析类错误(容器/manifest 解析失败):HLS 重试的触发条件 */
        @JvmStatic
        fun isParsingError(error: PlaybackException?): Boolean {
            val errorCode = error?.errorCode ?: return false
            return errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        }

        private val LANG_MAP = hashMapOf(
            "zh" to "国语",
            "zh-cn" to "国语",
            "cmn" to "国语",
            "chi" to "国语",
            "zho" to "国语",
            "chs" to "国语",
            "yue" to "粤语",
            "zh-hk" to "粤语",
            "zh-yue" to "粤语",
            "en" to "英语",
            "en-us" to "英语",
            "eng" to "英语",
            "ja" to "日语",
            "jpn" to "日语",
            "ko" to "韩语",
            "kor" to "韩语",
            "th" to "泰语",
            "tha" to "泰语",
        )
    }

    fun interface VideoSizeListener {
        fun onVideoSizeChanged(width: Int, height: Int, unappliedRotationDegrees: Int)
    }

    // ==================== 轨道读写 / 记忆还原(TrackSelector 面) ====================

    private var defaultSubtitleTrackSelected = false
    private var defaultSubtitleTrackSelectionClosed = false

    /** 当前轨道的完整清单(音频/视频/字幕):菜单与记忆还原都用它 */
    fun getTrackInfo(): TrackInfo {
        val data = TrackInfo()
        val mappedInfo = trackSelector.currentMappedTrackInfo ?: return data
        logRendererListOnce(mappedInfo)
        for (rendererIndex in 0 until mappedInfo.rendererCount) {
            val type = mappedInfo.getRendererType(rendererIndex)
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_TEXT) continue
            val groups = mappedInfo.getTrackGroups(rendererIndex)
            for (groupIndex in 0 until groups.length) {
                val group = groups[groupIndex]
                for (trackIndex in 0 until group.length) {
                    val fmt = group.getFormat(trackIndex)
                    if (type == C.TRACK_TYPE_TEXT && isUndeclaredClosedCaptionTrack(fmt)) continue
                    val language = getLanguage(fmt)
                    val detail = if (type == C.TRACK_TYPE_VIDEO) getVideoName(fmt) else getName(fmt)
                    val bean = TrackInfoBean()
                    bean.language = language
                    bean.name = buildDisplayName(
                        if (type == C.TRACK_TYPE_AUDIO) str(R.string.player_menu_audio_track)
                        else if (type == C.TRACK_TYPE_VIDEO) str(R.string.player_menu_video_track)
                        else str(R.string.player_menu_subtitle),
                        if (type == C.TRACK_TYPE_AUDIO) data.audio.size + 1
                        else if (type == C.TRACK_TYPE_VIDEO) data.video.size + 1
                        else data.subtitle.size + 1,
                        language,
                        detail,
                    )
                    bean.renderId = rendererIndex
                    bean.trackGroupId = groupIndex
                    bean.trackId = trackIndex
                    bean.groupIndex = groupIndex
                    bean.index = trackIndex
                    bean.selected = isCurrentTrackSelected(fmt, type)
                    bean.bitmapSubtitle = type == C.TRACK_TYPE_TEXT && isBitmapSubtitle(fmt)
                    bean.type = type
                    bean.formatKey = formatKey(fmt, type)
                    if (type == C.TRACK_TYPE_AUDIO) {
                        data.addAudio(bean)
                    } else if (type == C.TRACK_TYPE_VIDEO) {
                        data.addVideo(bean)
                    } else {
                        data.addSubtitle(bean)
                    }
                }
            }
        }
        return data
    }

    private var rendererListLogged = false

    /** 渲染器清单只落一次盘:getTrackInfo 在播放状态回调里被高频调用 */
    private fun logRendererListOnce(mappedInfo: MappingTrackSelector.MappedTrackInfo) {
        if (rendererListLogged) return
        rendererListLogged = true
        val sb = StringBuilder("echo-setTrack renderers:")
        for (i in 0 until mappedInfo.rendererCount) {
            sb.append(" [").append(i).append("]type=").append(mappedInfo.getRendererType(i))
                .append('/').append(mappedInfo.getRendererName(i))
        }
        LOG.i(sb.toString())
    }

    /** 用户显式选轨:改当前选择并记住**指纹**(下标换集即失效,存了必然选错轨) */
    fun setTrack(track: TrackInfoBean?) {
        if (track == null) return
        if (!applyTrack(track.renderId, track.trackGroupId, track.trackId)) return
        if (track.type == C.TRACK_TYPE_TEXT) {
            // 选内置字幕即重新决定字幕来源,覆盖 #off / #local / #online
            TrackMemory.saveSubtitle(contentKey, track.formatKey)
        } else {
            TrackMemory.saveTrack(contentKey, track.type, track.formatKey)
        }
    }

    /** 程序性选轨(默认字幕等自动逻辑),不写记忆 */
    fun selectTrack(track: TrackInfoBean?) {
        if (track == null) return
        applyTrack(track.renderId, track.trackGroupId, track.trackId)
    }

    /** 下发选择(无记忆写入);返回是否真的下发 */
    private fun applyTrack(rendererIndex: Int, groupIndex: Int, trackIndex: Int): Boolean {
        try {
            val mappedInfo = trackSelector.currentMappedTrackInfo
            if (mappedInfo == null) {
                LOG.i("echo-setTrack: MappedTrackInfo is null")
                return false
            }
            if (rendererIndex == C.INDEX_UNSET || rendererIndex < 0 || rendererIndex >= mappedInfo.rendererCount) {
                LOG.i("echo-setTrack: No renderer found")
                return false
            }
            val groups = mappedInfo.getTrackGroups(rendererIndex)
            if (!isTrackIndexValid(groups, groupIndex, trackIndex)) {
                LOG.i("echo-setTrack: Invalid track index - group:$groupIndex, track:$trackIndex")
                return false
            }
            val override = DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex)
            val builder = trackSelector.buildUponParameters()
            builder.setRendererDisabled(rendererIndex, false)
            builder.clearSelectionOverrides(rendererIndex)
            // 同一 track type 只允许一路渲染器持有选择:media3 只取第一个同类 definition、不清其余,
            // 两路音频渲染器同时 enable 即抛 "Multiple renderer media clocks enabled."(清掉即自动 disable)。
            val targetType = mappedInfo.getRendererType(rendererIndex)
            for (i in 0 until mappedInfo.rendererCount) {
                if (i != rendererIndex && mappedInfo.getRendererType(i) == targetType) {
                    builder.clearSelectionOverrides(i)
                }
            }
            builder.setSelectionOverride(rendererIndex, groups, override)
            trackSelector.setParameters(builder.build())
            // 诊断:记录真正下发的选择(渲染器/组/轨/格式);本机 ROM 吞 logcat,只信 App 文件日志
            val applied = groups.get(groupIndex).getFormat(trackIndex)
            LOG.i(
                "echo-setTrack applied: renderer=$rendererIndex group=$groupIndex track=$trackIndex type=$targetType " +
                    "mime=${applied?.sampleMimeType} channels=${applied?.channelCount ?: -1} codecs=${applied?.codecs} trackKey=$contentKey",
            )
            return true
        } catch (e: Exception) {
            LOG.i("echo-setTrack error: ${e.message}")
            return false
        }
    }

    /** 按记忆还原音轨/视轨/内置字幕;无记忆/定位不到的类型保持播放器默认(内置字幕则退"国语->第一条") */
    fun restoreTracks() {
        restoreByMemory(C.TRACK_TYPE_AUDIO)
        restoreByMemory(C.TRACK_TYPE_VIDEO)
        restoreSubtitleByMemory()
    }

    private fun restoreByMemory(trackType: Int) {
        val remembered = TrackMemory.loadTrack(contentKey, trackType) ?: return
        val position = locate(trackType, remembered)
        if (position == null) {
            LOG.i("echo-track-memory miss type=$trackType key=$contentKey fp=$remembered")
            return
        }
        if (applyTrack(position[0], position[1], position[2])) {
            LOG.i("echo-track-memory restore type=$trackType fp=$remembered")
        }
    }

    /** 内置字幕按指纹还原;#off / #local / #online 三种来源决定由页面层落地,这里不动 */
    private fun restoreSubtitleByMemory() {
        val record = TrackMemory.loadSubtitle(contentKey) ?: return
        if (!TrackMemory.isSubtitleTrack(record)) return
        val position = locate(C.TRACK_TYPE_TEXT, record)
        if (position == null) {
            // 有决定但这一集定位不到(编码变了/有歧义):退回默认选轨,别变成"什么都没有"
            LOG.i("echo-track-memory text miss, use default: $record")
            selectDefaultSubtitlePick()
            return
        }
        if (applyTrack(position[0], position[1], position[2])) {
            LOG.i("echo-track-memory restore text fp=$record")
        }
    }

    /** 在指定类型的全部渲染器/组/轨里按指纹定位;返回 {渲染器,组,轨},定位不到返回 null */
    private fun locate(trackType: Int, fingerprint: String): IntArray? {
        val mappedInfo = trackSelector.currentMappedTrackInfo ?: return null
        val keys = ArrayList<String>()
        val positions = ArrayList<IntArray>()
        for (rendererIndex in 0 until mappedInfo.rendererCount) {
            if (mappedInfo.getRendererType(rendererIndex) != trackType) continue
            val groups = mappedInfo.getTrackGroups(rendererIndex)
            for (groupIndex in 0 until groups.length) {
                val group = groups[groupIndex]
                for (trackIndex in 0 until group.length) {
                    val format = group.getFormat(trackIndex)
                    // 与菜单口径一致:未声明语言的 CEA608/708 不进列表(菜单里看不到,就不会是"用户选过")
                    if (trackType == C.TRACK_TYPE_TEXT && isUndeclaredClosedCaptionTrack(format)) continue
                    keys.add(formatKey(format, trackType))
                    positions.add(intArrayOf(rendererIndex, groupIndex, trackIndex))
                }
            }
        }
        val index = TrackMemory.pick(keys, fingerprint)
        return if (index < 0) null else positions[index]
    }

    /** 轨道指纹:语言取菜单同款的归一化值(跨内核可比),编码优先 codecs、缺失退 mime 子类型 */
    private fun formatKey(fmt: Format?, trackType: Int): String {
        if (fmt == null) return ""
        val codec = firstNonEmpty(fmt.codecs, mimeSubtype(fmt))
        if (trackType == C.TRACK_TYPE_AUDIO) {
            return TrackMemory.audioFingerprint(getLanguage(fmt), codec, fmt.channelCount)
        }
        if (trackType == C.TRACK_TYPE_VIDEO) {
            return TrackMemory.videoFingerprint(codec, fmt.width, fmt.height)
        }
        return TrackMemory.textFingerprint(getLanguage(fmt), codec)
    }

    private fun mimeSubtype(fmt: Format?): String {
        val mime = fmt?.sampleMimeType ?: return ""
        if (!mime.contains("/")) return ""
        return mime.substring(mime.indexOf('/') + 1)
    }

    private fun firstNonEmpty(first: String?, second: String?): String =
        if (first != null && first.isNotEmpty()) first else (second ?: "")

    fun loadDefaultSubtitleTrack() {
        if (defaultSubtitleTrackSelected) return
        // 该片已有字幕决定(内置/外挂/关闭):默认选轨让位,由页面层按记忆落地;
        // 内置指纹定位不到时,restoreSubtitleByMemory 会自己退回默认选轨
        if (TrackMemory.loadSubtitle(contentKey) != null) {
            LOG.i("echo-track-memory subtitle decision exists, skip default")
            defaultSubtitleTrackSelected = true
            return
        }
        selectDefaultSubtitlePick()
    }

    /** 当前没有选中任何内置字幕轨时补一次默认选轨(外挂字幕落地失败回落、或媒体未标 DEFAULT 轨时全靠它) */
    fun ensureSubtitleTrackSelected() {
        val subtitles = getTrackInfo().subtitle
        if (subtitles.isEmpty()) return
        for (subtitle in subtitles) {
            if (subtitle.selected) return
        }
        selectDefaultSubtitlePick()
    }

    /** 默认内置字幕:国语优先,否则第一条 */
    private fun selectDefaultSubtitlePick() {
        val subtitles = getTrackInfo().subtitle
        // 轨道还没映射出来时不封口:onTracksChanged 会再来一次(封了就再也选不上)
        if (subtitles.isEmpty()) return
        defaultSubtitleTrackSelected = true
        var target = subtitles[0]
        for (subtitle in subtitles) {
            if ("国语" == subtitle.language) { // i18n: keep(字幕语言匹配值)
                target = subtitle
                break
            }
        }
        selectTrack(target)
    }

    private fun loadDefaultSubtitleTrackBeforeReady() {
        if (defaultSubtitleTrackSelectionClosed) return
        loadDefaultSubtitleTrack()
    }

    /**
     * 清掉上一段内容留下的选轨覆盖(内核复用换内容时调用)。
     *
     * <p>选轨器随播放器常驻、reset 不清参数,而选轨覆盖表以轨道组为键、该键按内容比相等:
     * 不清则"在 A 片选过的轨"会串到轨道结构相同的 B 片(最刺眼:B 片记忆是关字幕却仍有字幕)。
     * 只影响默认选哪条,记忆还原([restoreTracks])在 STATE_PREPARED 会按新片重放。
     */
    fun resetTrackSelection() {
        trackSelector.setParameters(trackSelector.buildUponParameters().clearSelectionOverrides().build())
        LOG.i("echo-setTrack: clear stale overrides on content switch")
    }

    fun setContentKey(key: String?) {
        contentKey = key ?: ""
    }

    /** 点播磁盘缓存标记(边播边缓存;由调用方按场景注入,直播页恒 false) */
    fun setUseDiskCache(enabled: Boolean) {
        useDiskCache = enabled
    }

    private fun isTrackIndexValid(groups: TrackGroupArray, groupIndex: Int, trackIndex: Int): Boolean {
        if (groupIndex < 0 || groupIndex >= groups.length) return false
        val group = groups.get(groupIndex)
        return trackIndex >= 0 && trackIndex < group.length
    }

    private fun isBitmapSubtitle(format: Format?): Boolean {
        val mimeType = format?.sampleMimeType ?: return false
        val lower = mimeType.lowercase(Locale.getDefault())
        return lower.contains("pgs") || lower.contains("dvb") || lower.contains("vobsub")
    }

    private fun isUndeclaredClosedCaptionTrack(format: Format?): Boolean {
        if (format == null || format.accessibilityChannel != Format.NO_VALUE) return false
        return MimeTypes.APPLICATION_CEA608 == format.sampleMimeType ||
            MimeTypes.APPLICATION_CEA708 == format.sampleMimeType
    }

    private fun isCurrentTrackSelected(format: Format?, trackType: Int): Boolean {
        val exo = internalPlayer ?: return false
        val tracks = exo.currentTracks
        for (group in tracks.groups) {
            if (group.type != trackType || !group.isSelected) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i) && isSameFormat(format, group.getTrackFormat(i))) {
                    return true
                }
            }
        }
        return false
    }

    private fun isSameFormat(a: Format?, b: Format?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a.id != null && b.id != null && a.id == b.id) return true
        return a == b
    }

    private fun getLanguage(fmt: Format): String {
        val language = matchLanguage(fmt.language)
        if (language.isNotEmpty()) {
            return language
        }
        return matchLanguage(
            (fmt.label ?: "") + " " + (fmt.id ?: "") + " " + (fmt.codecs ?: ""),
        )
    }

    private fun matchLanguage(text: String?): String {
        if (text == null) return ""
        val value = text.lowercase(Locale.getDefault())
        LANG_MAP[value]?.let { return it }
        if (value.contains("yue") || value.contains("cantonese") || value.contains("粤") || value.contains("广东")) {
            return "粤语"
        }
        if (value.contains("zh") || value.contains("chi") || value.contains("zho") || value.contains("chs") ||
            value.contains("cht") || value.contains("cmn") || value.contains("中") ||
            value.contains("国语") || value.contains("普通话")
        ) {
            return "国语"
        }
        if (value.contains("en") || value.contains("eng") || value.contains("english") || value.contains("英")) {
            return "英语"
        }
        if (value.contains("ja") || value.contains("jpn") || value.contains("japanese") || value.contains("日")) {
            return "日语"
        }
        if (value.contains("ko") || value.contains("kor") || value.contains("korean") || value.contains("韩")) {
            return "韩语"
        }
        if (value.contains("tha") || value.contains("thai") || value.contains("th")) {
            return "泰语"
        }
        return ""
    }

    private fun getName(fmt: Format): String {
        val channelLabel: String = if (fmt.channelCount <= 0) {
            ""
        } else if (fmt.channelCount == 1) {
            str(R.string.player_channel_mono)
        } else if (fmt.channelCount == 2) {
            str(R.string.player_channel_stereo)
        } else {
            str(R.string.player_channel_count, fmt.channelCount)
        }

        var codec = ""
        if (!fmt.codecs.isNullOrEmpty()) {
            codec = fmt.codecs!!.uppercase(Locale.getDefault())
        }
        val mime = fmt.sampleMimeType
        if (mime != null && mime.contains("/")) {
            if (codec.isEmpty()) {
                codec = mime.substring(mime.indexOf('/') + 1).uppercase(Locale.getDefault())
            }
        }
        val builder = StringBuilder()
        appendPart(builder, fmt.label)
        appendPart(builder, codec)
        appendPart(builder, channelLabel)
        return builder.toString()
    }

    private fun getVideoName(fmt: Format): String {
        val builder = StringBuilder()
        appendPart(builder, fmt.label)
        if (fmt.width > 0 && fmt.height > 0) {
            appendPart(builder, "${fmt.width}x${fmt.height}")
        }
        val codecs = fmt.codecs
        val mime = fmt.sampleMimeType
        if (!codecs.isNullOrEmpty()) {
            appendPart(builder, codecs.uppercase(Locale.getDefault()))
        } else if (mime != null && mime.contains("/")) {
            appendPart(builder, mime.substring(mime.indexOf('/') + 1).uppercase(Locale.getDefault()))
        }
        return builder.toString()
    }

    private fun buildDisplayName(prefix: String, number: Int, language: String?, detail: String?): String {
        val builder = StringBuilder(prefix).append(number)
        if (!language.isNullOrEmpty()) {
            builder.append(" - ").append(language)
        }
        if (!detail.isNullOrEmpty()) {
            builder.append(" ").append(detail)
        }
        return builder.toString()
    }

    private fun appendPart(builder: StringBuilder, value: String?) {
        if (value == null) return
        val part = value.trim { it <= ' ' }
        if (part.isEmpty() || "und".equals(part, ignoreCase = true) || "未知" == part) return
        if (builder.isNotEmpty()) {
            builder.append(" / ")
        }
        builder.append(part)
    }

    /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
    private fun str(resId: Int, vararg args: Any?): String {
        val app = App.getInstance() ?: return ""
        return LanguageManager.localized(app).getString(resId, *args)
    }
}
