package com.github.tvbox.osc.player

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import coil3.request.Disposable
import com.github.tvbox.osc.player.host.EngineTextureRenderViewFactory
import com.github.tvbox.osc.player.host.TextureRenderHost
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.util.ImgUtil
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.ui.widget.DanmakuView

class MyVideoView : AppPlayerView, DrawHandler.Callback {

    private var danmuView: DanmakuView? = null

    private var artworkView: ImageView? = null

    private var artworkDisposable: Disposable? = null

    private var frameCover: View? = null

    private var mExoDiskCacheEnabled: Boolean = false

    private var mKernelRebuildRequired: Boolean = false

    private var mTrackMemoryKey: String = ""

    @JvmOverloads
    constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        super(context, attrs, defStyleAttr)

    val exoPlayer: ExoPlayer?
        get() = mMediaPlayer as? ExoPlayer

    fun setExoDiskCacheEnabled(enabled: Boolean) {
        mExoDiskCacheEnabled = enabled
        applyExoDiskCacheFlag()
    }

    fun setTrackMemoryKey(key: String?) {
        mTrackMemoryKey = key ?: ""
        applyTrackMemoryKey()
    }

    override fun initPlayer() {
        super.initPlayer()
        applyExoDiskCacheFlag()
        applyTrackMemoryKey()
    }

    private fun applyTrackMemoryKey() {
        exoPlayer?.setContentKey(mTrackMemoryKey)
    }

    private fun applyExoDiskCacheFlag() {
        exoPlayer?.setUseDiskCache(mExoDiskCacheEnabled)
    }

    val playState: PlayState
        get() = mMediaPlayer?.playState ?: PlayState.IDLE

    fun isKernelErrored(): Boolean = mMediaPlayer != null && playState == PlayState.ERROR

    fun requireKernelRebuild() {
        mKernelRebuildRequired = true
    }

    fun consumeKernelRebuildRequired(): Boolean {
        val required = mKernelRebuildRequired
        mKernelRebuildRequired = false
        return required
    }

    override val renderIsSurface: Boolean
        get() = renderView()?.getView() is SurfaceView

    override fun factoryRenderType(): Int =
        if (renderViewFactory() is EngineTextureRenderViewFactory) 0 else 1

    fun switchRenderToTexture() {
        setRenderViewFactory(EngineTextureRenderViewFactory.create())
        addDisplay()
    }

    fun ensureRenderViewMatchesConfig() {
        if (renderView() == null || mMediaPlayer == null) return
        val expectedSurface = renderViewFactory() !is EngineTextureRenderViewFactory
        if (expectedSurface == renderIsSurface) return
        addDisplay()
    }

    override fun addDisplay() {
        super.addDisplay()
        val render = renderView()
        if (render is TextureRenderHost) {
            render.setOnSurfaceReadyListener { pushRenderOutputResolution() }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        pushRenderOutputResolution()
    }

    override fun onVideoSizeReported(videoWidth: Int, videoHeight: Int) {
        pushRenderOutputResolution()
    }

    private fun pushRenderOutputResolution() {
        val player = exoPlayer ?: return
        if (renderView() == null || renderIsSurface) return
        val width = mVideoSize[0]
        val height = mVideoSize[1]
        if (width <= 0 || height <= 0) return
        val render = renderView()
        if (render is TextureRenderHost) {
            render.setOutputSize(width, height)
        }
        player.notifyVideoOutputResolution(width, height)
    }

    fun setArtwork(url: String?) {
        if (TextUtils.isEmpty(url)) {
            clearArtwork()
            return
        }
        var view = artworkView
        if (view == null) {
            view = ImageView(context)
            view.setBackgroundColor(Color.BLACK)
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.isClickable = false
            view.isFocusable = false
            val index = if (renderView() == null) 0 else Math.min(1, mPlayerContainer.childCount)
            mPlayerContainer.addView(
                view,
                index,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
            artworkView = view
        }
        view.visibility = VISIBLE
        cancelArtworkRequest()
        artworkDisposable = ImgUtil.loadPlayerArtwork(url!!, view)
    }

    fun clearArtwork() {
        cancelArtworkRequest()
        artworkView?.visibility = GONE
        artworkView?.setImageDrawable(null)
    }

    private fun cancelArtworkRequest() {
        artworkDisposable?.dispose()
        artworkDisposable = null
    }

    fun isPortraitVideo(): Boolean = VideoOrientation.isPortrait(mVideoSize[0], mVideoSize[1])

    fun clearVideoFrame() {
        val player = mMediaPlayer
        if (player is ExoPlayer) player.stopForFrameClear() else player?.stop()
        showFrameCover()
    }

    fun coverVideoFrame() {
        showFrameCover()
    }

    private fun showFrameCover() {
        var cover = frameCover
        if (cover == null) {
            cover = View(context)
            cover.setBackgroundColor(Color.BLACK)
            mPlayerContainer.addView(
                cover,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
            frameCover = cover
        }
        cover.visibility = VISIBLE
        bringControllerToFront()
    }

    fun showVideoFrame() {
        hideVideoFrameCover()
        clearArtwork()
    }

    fun hideVideoFrameCover() {
        frameCover?.visibility = GONE
    }

    fun isVideoFrameCleared(): Boolean = frameCover?.visibility == VISIBLE

    override fun seekTo(pos: Long) {
        super.seekTo(pos)
        if (haveDanmu()) danmuView?.seekTo(pos)
    }

    override fun resume() {
        super.resume()
        if (haveDanmu()) danmuView?.resume()
    }

    override fun start() {
        super.start()
        if (haveDanmu()) danmuView?.resume()
    }

    override fun pause() {
        super.pause()
        if (haveDanmu()) danmuView?.pause()
    }

    override fun release() {
        super.release()
        mTrackMemoryKey = ""
        if (haveDanmu()) danmuView?.release()
    }

    private fun haveDanmu(): Boolean = danmuView?.isPrepared == true

    fun setDanmuView(view: DanmakuView?) {
        danmuView = view
        danmuView?.setCallback(this)
    }

    fun getDanmuView(): DanmakuView? = danmuView

    override fun prepared() {
        post {
            val view = danmuView ?: return@post
            if (isPlaying && view.isPrepared) {
                view.start(currentPosition)
            }
        }
    }

    override fun updateTimer(timer: DanmakuTimer?) {
    }

    override fun danmakuShown(danmaku: BaseDanmaku?) {
    }

    override fun drawingFinished() {
    }
}
