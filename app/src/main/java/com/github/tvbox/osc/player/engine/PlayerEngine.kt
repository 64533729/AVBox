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
        const val DEFAULT_BUFFER_TIMES = 3
    }
}

class PlayerEngine(
    context: Context,
    private val config: PlayerEngineConfig = PlayerEngineConfig(),
) {

    private val appContext: Context = context.applicationContext

    val mediaSources = MediaSources(appContext, config.okHttpClient)

    private val videoRenderers = ArrayList<Renderer>()

    private val trackSelector = DefaultTrackSelector(appContext)

    private var internalPlayer: ExoPlayer? = null

    val player: ExoPlayer?
        get() = internalPlayer

    private var speedPlaybackParameters: PlaybackParameters? = null
    private var mediaSource: MediaSource? = null
    private var currentPlayPath: String? = null
    private var currentHeaders: Map<String, String>? = null
    private var retriedAsHls = false

    private var useDiskCache = false

    private var contentKey = ""

    @Volatile
    private var startPositionMs = 0L

    @Volatile
    private var startPositionApplied = false

    @Volatile
    private var videoEffectsOpen = false

    private var tunnelingEnabled = false

    @Volatile
    private var pictureHdrSource = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastOutputWidth = 0
    private var lastOutputHeight = 0
    private var redrawScheduled = false

    @Volatile
    private var subtitleDelayUs = 0L
    private var onCuesListener: ((List<Cue>) -> Unit)? = null

    var videoSizeListener: VideoSizeListener? = null

    var playbackStateListener: ((Int) -> Unit)? = null

    var retryAsHlsListener: (() -> Unit)? = null

    fun interface ErrorListener {
        fun onPlayerError(error: PlaybackException, kind: Int)
    }

    private val errorListeners = ArrayList<ErrorListener>()

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
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
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
        config.playbackLooper?.let { builder.setPlaybackLooper(it) }
        val exo = builder.build()
        exo.playWhenReady = true
        if (config.enableLog) {
            exo.addAnalyticsListener(EventLogger(trackSelector, "ExoPlayer"))
        }
        return exo
    }

    fun setDataSource(path: String, headers: Map<String, String>?) {
        setDataSource(path, headers, false)
    }

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

    fun setStartPosition(positionMs: Long) {
        startPositionMs = maxOf(0L, positionMs)
        startPositionApplied = false
    }

    val isStartPositionApplied: Boolean
        get() = startPositionApplied

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

    fun reset() {
        internalPlayer?.let {
            it.stop()
            it.clearMediaItems()
        }
        retriedAsHls = false
    }

    fun release() {
        internalPlayer?.let {
            it.removeListener(engineListener)
            it.removeAnalyticsListener(analyticsListener)
            it.release()
        }
        internalPlayer = null
        speedPlaybackParameters = null
    }

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

    fun setVideoSurface(surface: Surface?) {
        internalPlayer?.setVideoSurface(surface)
    }

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
            videoEffectsOpen = false
            LOG.e("PlayerEngine", "echo-picture-effects apply failed", th)
        }
    }

    val isPictureEffectsActive: Boolean
        get() = videoEffectsOpen

    val isPictureHdrSource: Boolean
        get() = pictureHdrSource

    fun notifyVideoOutputResolution(width: Int, height: Int) {
        val exo = internalPlayer ?: return
        if (width <= 0 || height <= 0) return
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
        if (RedrawPolicy.shouldRedrawOnGeometry(sizeChanged, isPlaying, redrawReady())) {
            redrawVideoFrame()
        }
    }

    private fun redrawReady(): Boolean {
        if (!videoEffectsOpen) return false
        for (renderer in videoRenderers) {
            if (renderer is ReplayableCacheVideoRenderer) return true
        }
        return false
    }

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

    val measuredFrameRate: Float
        get() = measuredFrameRateValue

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

    fun setOnCuesListener(listener: ((List<Cue>) -> Unit)?) {
        onCuesListener = listener
    }

    fun setInternalSubtitleDelay(milliseconds: Int) {
        subtitleDelayUs = milliseconds * 1000L
    }

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

    private fun handlePlayerError(error: PlaybackException) {
        val codeName = error.errorCodeName
        lastErrorKindValue = classifyError(codeName)
        LOG.e("Tvbox-runtime", "echo-Exo player error: $currentPlayPath", error)
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

        @JvmStatic
        fun classifyError(codeName: String?): Int {
            if (codeName == null) return ERROR_KIND_UNKNOWN
            if (codeName.startsWith("ERROR_CODE_IO") || codeName.startsWith("ERROR_CODE_PARSING")) return ERROR_KIND_NETWORK
            if (codeName.startsWith("ERROR_CODE_DECOD")) return ERROR_KIND_DECODE
            return ERROR_KIND_UNKNOWN
        }

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

    private var defaultSubtitleTrackSelected = false
    private var defaultSubtitleTrackSelectionClosed = false

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
                        if (type == C.TRACK_TYPE_AUDIO) data.getAudio().size + 1
                        else if (type == C.TRACK_TYPE_VIDEO) data.getVideo().size + 1
                        else data.getSubtitle().size + 1,
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

    fun setTrack(track: TrackInfoBean?) {
        if (track == null) return
        if (!applyTrack(track.renderId, track.trackGroupId, track.trackId)) return
        if (track.type == C.TRACK_TYPE_TEXT) {
            TrackMemory.saveSubtitle(contentKey, track.formatKey)
        } else {
            TrackMemory.saveTrack(contentKey, track.type, track.formatKey)
        }
    }

    fun selectTrack(track: TrackInfoBean?) {
        if (track == null) return
        applyTrack(track.renderId, track.trackGroupId, track.trackId)
    }

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
            val targetType = mappedInfo.getRendererType(rendererIndex)
            for (i in 0 until mappedInfo.rendererCount) {
                if (i != rendererIndex && mappedInfo.getRendererType(i) == targetType) {
                    builder.clearSelectionOverrides(i)
                }
            }
            builder.setSelectionOverride(rendererIndex, groups, override)
            trackSelector.setParameters(builder.build())
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

    private fun restoreSubtitleByMemory() {
        val record = TrackMemory.loadSubtitle(contentKey) ?: return
        if (!TrackMemory.isSubtitleTrack(record)) return
        val position = locate(C.TRACK_TYPE_TEXT, record)
        if (position == null) {
            LOG.i("echo-track-memory text miss, use default: $record")
            selectDefaultSubtitlePick()
            return
        }
        if (applyTrack(position[0], position[1], position[2])) {
            LOG.i("echo-track-memory restore text fp=$record")
        }
    }

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
                    if (trackType == C.TRACK_TYPE_TEXT && isUndeclaredClosedCaptionTrack(format)) continue
                    keys.add(formatKey(format, trackType))
                    positions.add(intArrayOf(rendererIndex, groupIndex, trackIndex))
                }
            }
        }
        val index = TrackMemory.pick(keys, fingerprint)
        return if (index < 0) null else positions[index]
    }

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
        if (TrackMemory.loadSubtitle(contentKey) != null) {
            LOG.i("echo-track-memory subtitle decision exists, skip default")
            defaultSubtitleTrackSelected = true
            return
        }
        selectDefaultSubtitlePick()
    }

    fun ensureSubtitleTrackSelected() {
        val subtitles = getTrackInfo().getSubtitle()
        if (subtitles.isEmpty()) return
        for (subtitle in subtitles) {
            if (subtitle.selected) return
        }
        selectDefaultSubtitlePick()
    }

    private fun selectDefaultSubtitlePick() {
        val subtitles = getTrackInfo().getSubtitle()
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

    fun resetTrackSelection() {
        trackSelector.setParameters(trackSelector.buildUponParameters().clearSelectionOverrides().build())
        LOG.i("echo-setTrack: clear stale overrides on content switch")
    }

    fun setContentKey(key: String?) {
        contentKey = key ?: ""
    }

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

    private fun str(resId: Int, vararg args: Any?): String {
        val app = App.getInstance() ?: return ""
        return LanguageManager.localized(app).getString(resId, *args)
    }
}
