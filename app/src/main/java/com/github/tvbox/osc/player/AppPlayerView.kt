package com.github.tvbox.osc.player

import android.app.Activity
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Parcelable
import android.text.TextUtils
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.tvbox.osc.player.host.EngineSurfaceRenderViewFactory
import com.github.tvbox.osc.player.host.EngineTextureRenderViewFactory
import com.github.tvbox.osc.player.host.PlayerRenderView
import com.github.tvbox.osc.player.host.PlayerRenderViewFactory
import com.github.tvbox.osc.player.host.PlayerAudioFocus
import com.github.tvbox.osc.player.host.AudioFocusTarget
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.PlayerUtils

open class AppPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    interface OnStateChangeListener {
        fun onPlayerStateChanged(playerState: Int)
        fun onPlayStateChanged(playState: Int)
    }

    open class SimpleOnStateChangeListener : OnStateChangeListener {
        override fun onPlayerStateChanged(playerState: Int) = Unit
        override fun onPlayStateChanged(playState: Int) = Unit
    }

    interface ProgressSink {
        fun saveProgress(url: String?, progress: Long)

        fun getSavedProgress(url: String?): Long
    }

    protected var mMediaPlayer: KernelPlayer? = null

    protected var mProgressSink: ProgressSink? = null

    protected val mPlayerContainer: FrameLayout = FrameLayout(context)

    protected var mRenderView: PlayerRenderView? = null

    protected var mRenderViewFactory: PlayerRenderViewFactory =
        if (com.github.tvbox.osc.util.KV.get(com.github.tvbox.osc.util.HawkConfig.PLAY_RENDER, 1) == 1) {
            EngineSurfaceRenderViewFactory.create()
        } else {
            EngineTextureRenderViewFactory.create()
        }

    protected var mCurrentScreenScaleType = SCREEN_SCALE_DEFAULT

    protected var mVideoSize = intArrayOf(0, 0)

    protected var mUrl: String? = null

    protected var mProgressKey: String? = null

    protected var mHeaders: Map<String, String>? = null

    protected var mCurrentPosition = 0L

    protected val mCurrentPlayState: Int
        get() = mMediaPlayer?.playState?.toLegacy() ?: STATE_IDLE

    private var mPausedBeforeSeek = false

    protected var mCurrentPlayerState = PLAYER_NORMAL

    protected var mEnableAudioFocus = true

    private var mAudioFocusHelper: PlayerAudioFocus? = null

    protected var mOnStateChangeListeners: MutableList<OnStateChangeListener>? = null

    private val audioFocusTarget = object : AudioFocusTarget {
        override fun isPlaybackPlaying(): Boolean = isPlaying

        override fun isPlaybackMuted(): Boolean = false

        override fun startPlayback() {
            start()
        }

        override fun pausePlayback() {
            pause()
        }

        override fun setPlaybackVolume(volume: Float) {
            mMediaPlayer?.setVolume(volume, volume)
        }
    }

    init {
        mPlayerContainer.setBackgroundColor(Color.BLACK)
        addView(
            mPlayerContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        mEnableAudioFocus = true
    }

    open fun start() {
        if (isInIdleState() || isInStartAbortState()) {
            startPlay()
        } else if (isInPlaybackState()) {
            startInPlaybackState()
        }
    }

    protected open fun startPlay(): Boolean {
        if (showNetWarning()) {
            setPlayState(STATE_START_ABORT)
            return false
        }
        if (mEnableAudioFocus) {
            ensureAudioFocusHelper()
            mAudioFocusHelper?.onNewPlayback()
        }
        mProgressSink?.let { sink ->
            mCurrentPosition = sink.getSavedProgress(progressKey())
        }
        mMediaPlayer?.release()
        mMediaPlayer = null
        initPlayer()
        addDisplay()
        startPrepare(false)
        return true
    }

    protected open fun showNetWarning(): Boolean = false

    protected open fun initPlayer() {
        val player = createPlayer()
        player.setPlayerEventListener(kernelEventListener)
        mMediaPlayer = player
        setInitOptions()
        player.initPlayer()
        setOptions()
    }

    protected open fun createPlayer(): KernelPlayer = ExoPlayer(context)

    protected open fun setInitOptions() = Unit

    protected open fun setOptions() {
        mMediaPlayer?.setLooping(false)
        mMediaPlayer?.setVolume(1.0f, 1.0f)
    }

    open fun prewarmKernel() {
        if (mMediaPlayer != null) return
        ensureAudioFocusHelper()
        initPlayer()
        addDisplay()
    }

    private fun ensureAudioFocusHelper() {
        if (mEnableAudioFocus && mAudioFocusHelper == null) {
            mAudioFocusHelper = PlayerAudioFocus(context, audioFocusTarget)
        }
    }

    protected open fun addDisplay() {
        mRenderView?.let { render ->
            mPlayerContainer.removeView(render.getView())
            render.release()
        }
        val render = mRenderViewFactory.createRenderView(context)
        mMediaPlayer?.let { render.attachToPlayer(it) }
        mRenderView = render
        mPlayerContainer.addView(
            render.getView(),
            0,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
    }

    protected fun startPrepare(reset: Boolean) {
        startPrepare(reset, false)
    }

    protected fun startPrepare(reset: Boolean, rebindRenderView: Boolean) {
        mPausedBeforeSeek = false
        if (reset) {
            mMediaPlayer?.reset()
            setOptions()
            if (rebindRenderView) {
                mMediaPlayer?.let { player -> mRenderView?.attachToPlayer(player) }
            }
        }
        if (prepareDataSource()) {
            mMediaPlayer?.let { player ->
                player.setStartPosition(mCurrentPosition)
                player.prepareAsync()
            }
            setPlayState(STATE_PREPARING)
            setPlayerState(PLAYER_NORMAL)
        }
    }

    protected open fun prepareDataSource(): Boolean {
        val url = mUrl
        if (!TextUtils.isEmpty(url)) {
            mMediaPlayer?.setDataSource(url!!, mHeaders)
            return true
        }
        return false
    }

    protected fun startInPlaybackState() {
        mPausedBeforeSeek = false
        mMediaPlayer?.start()
        setPlayState(STATE_PLAYING)
        if (!isMute()) {
            mAudioFocusHelper?.requestFocus()
        }
        mPlayerContainer.keepScreenOn = true
    }

    open fun pause() {
        val player = mMediaPlayer
        if (isInPlaybackState() && player?.isPlaying == true) {
            mPausedBeforeSeek = true
            player.pause()
            setPlayState(STATE_PAUSED)
            if (!isMute()) {
                mAudioFocusHelper?.abandonFocus()
            }
            mPlayerContainer.keepScreenOn = false
        }
    }

    open fun resume() {
        if (isInPlaybackState() && mMediaPlayer?.isPlaying == false) {
            resumePlay()
        }
    }

    private fun resumePlay() {
        mPausedBeforeSeek = false
        mMediaPlayer?.start()
        setPlayState(STATE_PLAYING)
        if (!isMute()) {
            mAudioFocusHelper?.requestFocus()
        }
        mPlayerContainer.keepScreenOn = true
    }

    open fun stopPlaybackKeepPlayer() {
        val player = mMediaPlayer ?: return
        if (mCurrentPlayState == STATE_PAUSED) return
        player.stop()
        setPlayState(STATE_IDLE)
    }

    open fun saveCurrentProgress() {
        saveProgress()
    }

    open fun release() {
        val hadActiveState = !isInIdleState()
        mPausedBeforeSeek = false
        mAudioFocusHelper?.abandonFocus()
        mAudioFocusHelper = null
        mMediaPlayer?.release()
        mMediaPlayer = null
        if (hadActiveState) {
            mRenderView?.let { render ->
                mPlayerContainer.removeView(render.getView())
                render.release()
            }
            mRenderView = null
            mPlayerContainer.keepScreenOn = false
            saveProgress()
            mCurrentPosition = 0
            setPlayState(STATE_IDLE)
        }
        mVideoSize[0] = 0
        mVideoSize[1] = 0
    }

    protected fun saveProgress() {
        val sink = mProgressSink ?: return
        if (mCurrentPosition > 0) {
            LOG.d("AppPlayerView", "saveProgress: " + mCurrentPosition)
            sink.saveProgress(progressKey(), mCurrentPosition)
        }
    }

    protected fun progressKey(): String? = mProgressKey ?: mUrl

    protected fun isInPlaybackState(): Boolean {
        return mMediaPlayer != null &&
            mCurrentPlayState != STATE_ERROR &&
            mCurrentPlayState != STATE_IDLE &&
            mCurrentPlayState != STATE_PREPARING &&
            mCurrentPlayState != STATE_START_ABORT &&
            mCurrentPlayState != STATE_PLAYBACK_COMPLETED
    }

    protected fun isInIdleState(): Boolean = mCurrentPlayState == STATE_IDLE

    private fun isInStartAbortState(): Boolean = mCurrentPlayState == STATE_START_ABORT

    private val kernelEventListener = object : KernelPlayer.Listener {

        override fun onPrepared() {
            val player = mMediaPlayer ?: return
            if (mCurrentPosition > 0 && !player.isStartPositionApplied()) {
                player.seekTo(mCurrentPosition)
            }
            setPlayState(STATE_PREPARED)
            if (!isMute()) {
                mAudioFocusHelper?.requestFocus()
            }
        }

        override fun onInfo(what: Int, extra: Int) {
            when (what) {
                KernelPlayer.MEDIA_INFO_BUFFERING_START ->
                    if (!keepPausedStateAfterSeek()) setPlayState(STATE_BUFFERING)

                KernelPlayer.MEDIA_INFO_BUFFERING_END ->
                    if (!keepPausedStateAfterSeek()) setPlayState(STATE_BUFFERED)

                KernelPlayer.MEDIA_INFO_RENDERING_START ->
                    if (!keepPausedStateAfterSeek()) {
                        setPlayState(STATE_PLAYING)
                        mPlayerContainer.keepScreenOn = true
                    }

                KernelPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED ->
                    mRenderView?.setVideoRotation(extra)
            }
        }

        override fun onError() {
            mPlayerContainer.keepScreenOn = false
            setPlayState(STATE_ERROR)
        }

        override fun onCompletion() {
            mPlayerContainer.keepScreenOn = false
            mCurrentPosition = 0
            mProgressSink?.saveProgress(progressKey(), 0L)
            setPlayState(STATE_PLAYBACK_COMPLETED)
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            mVideoSize[0] = width
            mVideoSize[1] = height
            onVideoSizeReported(width, height)
            mVideoController?.onVideoSizeChanged(width, height)
            mRenderView?.let { render ->
                render.setScaleType(mCurrentScreenScaleType)
                render.setVideoSize(width, height)
            }
        }
    }

    protected open fun onVideoSizeReported(width: Int, height: Int) = Unit

    @get:JvmName("getMediaPlayer")
    val mediaPlayer: KernelPlayer?
        get() = mMediaPlayer

    open val duration: Long
        get() = if (isInPlaybackState()) mMediaPlayer?.duration ?: 0L else 0L

    open val currentPosition: Long
        get() {
            if (isInPlaybackState()) {
                mCurrentPosition = mMediaPlayer?.currentPosition ?: 0L
                return mCurrentPosition
            }
            return 0
        }

    open fun seekTo(pos: Long) {
        if (isInPlaybackState()) {
            if (mCurrentPlayState == STATE_PAUSED) {
                mPausedBeforeSeek = true
            }
            mMediaPlayer?.seekTo(pos)
        }
    }

    private fun keepPausedStateAfterSeek(): Boolean {
        if (!mPausedBeforeSeek) return false
        if (mCurrentPlayState != STATE_PAUSED) {
            setPlayState(STATE_PAUSED)
        }
        return true
    }

    open val isPlaying: Boolean
        get() = isInPlaybackState() && mMediaPlayer?.isPlaying == true

    val bufferedPercentage: Int
        get() = mMediaPlayer?.bufferedPercentage ?: 0

    open val tcpSpeed: Long
        get() = mMediaPlayer?.tcpSpeed ?: 0L

    open fun setSpeed(speed: Float) {
        if (isInPlaybackState()) mMediaPlayer?.setSpeed(speed)
    }

    open val speed: Float
        get() = if (isInPlaybackState()) mMediaPlayer?.speed ?: 1f else 1f

    open fun isMute(): Boolean = false

    open fun setUrl(url: String) {
        setUrl(url, null)
    }

    open fun setUrl(url: String, headers: Map<String, String>?) {
        mPausedBeforeSeek = false
        mUrl = url
        mHeaders = headers
        mVideoSize[0] = 0
        mVideoSize[1] = 0
        mVideoController?.onVideoSizeCleared()
    }

    open fun setProgressKey(key: String?) {
        mProgressKey = key
    }

    open fun setProgressSink(sink: ProgressSink?) {
        mProgressSink = sink
    }

    open fun skipPositionWhenPlay(position: Int) {
        mCurrentPosition = position.toLong()
    }

    open fun replay(resetPosition: Boolean) {
        if (resetPosition) {
            mCurrentPosition = 0
        }
        val player = mMediaPlayer
        if (player == null) {
            LOG.i("replay() called without kernel, fallback to start()")
            start()
            return
        }
        if (mEnableAudioFocus) {
            mAudioFocusHelper?.onNewPlayback()
        }
        player.resetTrackSelection()
        if (player.keepRenderViewOnReset()) {
            player.reset()
            setOptions()
            player.setOptions()
            startPrepare(false)
        } else {
            startPrepare(true, true)
        }
    }

    open fun factoryRenderType(): Int =
        if (mRenderViewFactory is EngineTextureRenderViewFactory) 0 else 1

    open fun setRenderViewFactory(factory: PlayerRenderViewFactory) {
        mRenderViewFactory = factory
    }

    open fun needsRenderRebuild(targetRenderType: Int): Boolean {
        val render = mRenderView ?: return false
        return (targetRenderType == 1) != (render.getView() is SurfaceView)
    }

    open fun setScreenScaleType(screenScaleType: Int) {
        mCurrentScreenScaleType = screenScaleType
        mRenderView?.setScaleType(screenScaleType)
    }

    open val videoSize: IntArray
        get() = mVideoSize

    val currentPlayState: Int
        get() = mCurrentPlayState

    fun getCurrentPlayerState(): Int = mCurrentPlayerState

    @Suppress("UNUSED_PARAMETER")
    open fun setMute(isMute: Boolean) = Unit

    protected fun setPlayState(playState: Int) {
        mVideoController?.setPlayState(playState)
        mOnStateChangeListeners?.let { listeners ->
            for (listener in it2snapshot(listeners)) {
                listener?.onPlayStateChanged(playState)
            }
        }
    }

    protected fun setPlayerState(playerState: Int) {
        mCurrentPlayerState = playerState
        mVideoController?.setPlayerState(playerState)
        mOnStateChangeListeners?.let { listeners ->
            for (listener in it2snapshot(listeners)) {
                listener?.onPlayerStateChanged(playerState)
            }
        }
    }

    private fun it2snapshot(source: List<OnStateChangeListener>): List<OnStateChangeListener> {
        val result = ArrayList<OnStateChangeListener>(source.size)
        for (item in source) {
            if (item != null) result.add(item)
        }
        return result
    }

    open fun addOnStateChangeListener(listener: OnStateChangeListener) {
        val listeners = mOnStateChangeListeners ?: ArrayList<OnStateChangeListener>().also {
            mOnStateChangeListeners = it
        }
        listeners.add(listener)
    }

    open fun removeOnStateChangeListener(listener: OnStateChangeListener) {
        mOnStateChangeListeners?.remove(listener)
    }

    interface VideoControllerHost {
        fun setPlayState(playState: Int)

        fun setPlayerState(playerState: Int)

        fun onVideoSizeChanged(width: Int, height: Int)

        fun onVideoSizeCleared()

        fun startProgress()
    }

    protected var mVideoController: VideoControllerHost? = null

    open val videoController: VideoControllerHost?
        get() = mVideoController

    open fun setVideoController(controller: VideoControllerHost?) {
        val old = mVideoController
        if (old is View) {
            mPlayerContainer.removeView(old)
        }
        mVideoController = controller
        if (controller != null) {
            if (controller is View) {
                mPlayerContainer.addView(
                    controller,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                )
            }
            controller.setPlayState(mCurrentPlayState)
            controller.setPlayerState(mCurrentPlayerState)
            if (mCurrentPlayState != STATE_IDLE && mCurrentPlayState != STATE_ERROR) {
                controller.startProgress()
            }
        }
    }

    open fun releaseController(): VideoControllerHost? {
        val old = mVideoController
        setVideoController(null)
        return old
    }

    open fun isFullScreen(): Boolean = false

    open fun togglePlay() {
        if (isPlaying) pause() else start()
    }

    open fun doScreenShot(): Bitmap? = mRenderView?.doScreenShot()

    open fun onBackPressed(): Boolean = false

    open fun attachContainerTo(host: ViewGroup?) {
        if (host == null) return
        val parent = mPlayerContainer.parent as? ViewGroup
        if (parent === host) return
        parent?.removeView(mPlayerContainer)
        val lp = mPlayerContainer.layoutParams ?: LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        host.addView(mPlayerContainer, 0, lp)
    }

    open fun detachContainerFromHost() {
        val parent = mPlayerContainer.parent as? ViewGroup
        if (parent == null || parent === this) return
        parent.removeView(mPlayerContainer)
        addView(
            mPlayerContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    open fun isContainerAttachedTo(host: ViewGroup?): Boolean =
        host != null && mPlayerContainer.parent === host

    open fun playerContainer(): FrameLayout = mPlayerContainer

    open fun bringControllerToFront() {
        (mVideoController as? View)?.bringToFront()
    }

    fun hostActivity(): Activity? = PlayerUtils.scanForActivity(context)

    open val renderIsSurface: Boolean
        get() = mRenderView?.getView() is SurfaceView

    protected fun renderView(): PlayerRenderView? = mRenderView

    protected fun renderViewFactory(): PlayerRenderViewFactory = mRenderViewFactory

    override fun onSaveInstanceState(): Parcelable? {
        saveProgress()
        return super.onSaveInstanceState()
    }

    companion object {

        const val SCREEN_SCALE_DEFAULT = 0
        const val SCREEN_SCALE_16_9 = 1
        const val SCREEN_SCALE_4_3 = 2
        const val SCREEN_SCALE_MATCH_PARENT = 3
        const val SCREEN_SCALE_ORIGINAL = 4
        const val SCREEN_SCALE_CENTER_CROP = 5

        const val STATE_ERROR = -1
        const val STATE_IDLE = 0
        const val STATE_PREPARING = 1
        const val STATE_PREPARED = 2
        const val STATE_PLAYING = 3
        const val STATE_PAUSED = 4
        const val STATE_PLAYBACK_COMPLETED = 5
        const val STATE_BUFFERING = 6
        const val STATE_BUFFERED = 7
        const val STATE_START_ABORT = 8

        const val PLAYER_NORMAL = 10
        const val PLAYER_FULL_SCREEN = 11
        const val PLAYER_TINY_SCREEN = 12
    }
}
