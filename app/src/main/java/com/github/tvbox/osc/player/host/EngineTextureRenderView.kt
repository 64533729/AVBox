package com.github.tvbox.osc.player.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import xyz.doikki.videoplayer.player.AbstractPlayer
import xyz.doikki.videoplayer.render.IRenderView

/**
 * 新栈表面宿主(Texture 模式,M7b):逐行为等价移植 doikki `TextureRenderView`。
 *
 * <p>承重细节一个不丢:输出缓冲尺寸(`setDefaultBufferSize`,TextureView 会把缓冲尺寸改回视图尺寸、
 * 这里改回去)、换面时旧 Surface 延后释放(先放会让 EGL 卡在已释放的 BufferQueue 上)、
 * `onSurfaceTextureDestroyed` 返回 false(面不销毁,供复用)、交面回调([setOnSurfaceReadyListener])。
 */
@SuppressLint("ViewConstructor")
class EngineTextureRenderView(
    context: Context,
) : TextureView(context), IRenderView, TextureView.SurfaceTextureListener, TextureRenderHost {

    private var mediaPlayer: AbstractPlayer? = null

    private var texture: SurfaceTexture? = null

    private var surface: Surface? = null

    /** 上一代 Surface:必须等输出 EGL 面切走之后再 release */
    private var retiredSurface: Surface? = null

    private var surfaceReadyListener: Runnable? = null

    private var outputWidth = 0

    private var outputHeight = 0

    private var scaleType = RenderMeasure.SCALE_DEFAULT

    private var videoWidth = 0

    private var videoHeight = 0

    private var videoRotationDegree = 0

    init {
        surfaceTextureListener = this
    }

    override fun setOnSurfaceReadyListener(listener: Runnable?) {
        surfaceReadyListener = listener
    }

    override fun setOutputSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == outputWidth && height == outputHeight) return
        outputWidth = width
        outputHeight = height
        applyOutputBufferSize()
        refreshSurface()
    }

    /** TextureView 自己会把默认缓冲尺寸改成视图尺寸(onSizeChanged / 建层时),这里改回输出尺寸 */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyOutputBufferSize()
    }

    private fun applyOutputBufferSize() {
        val surfaceTexture = texture ?: return
        if (outputWidth <= 0 || outputHeight <= 0) return
        surfaceTexture.setDefaultBufferSize(outputWidth, outputHeight)
    }

    /** 换一个新 Surface(同一个 SurfaceTexture):输出 EGL 面只在交面/清面/尺寸变化时重建 */
    fun refreshSurface(): Boolean {
        val surfaceTexture = texture ?: return false
        val player = mediaPlayer ?: return false
        releaseRetiredSurface()
        retiredSurface = surface
        val newSurface = Surface(surfaceTexture)
        surface = newSurface
        player.setSurface(newSurface)
        notifySurfaceReady()
        return true
    }

    private fun releaseRetiredSurface() {
        retiredSurface?.release()
        retiredSurface = null
    }

    override fun attachToPlayer(player: AbstractPlayer) {
        mediaPlayer = player
        val current = surface
        if (current != null) {
            player.setSurface(current)
            notifySurfaceReady()
        }
    }

    override fun setVideoSize(videoWidth: Int, videoHeight: Int) {
        if (videoWidth > 0 && videoHeight > 0) {
            this.videoWidth = videoWidth
            this.videoHeight = videoHeight
            requestLayout()
        }
    }

    override fun setVideoRotation(degree: Int) {
        videoRotationDegree = degree
        rotation = degree.toFloat()
    }

    override fun setScaleType(scaleType: Int) {
        this.scaleType = scaleType
        requestLayout()
    }

    override fun getView(): View = this

    override fun doScreenShot(): Bitmap? = bitmap

    override fun release() {
        releaseRetiredSurface()
        surface?.release()
        texture?.release()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measured = RenderMeasure.measure(
            widthMeasureSpec,
            heightMeasureSpec,
            View.MeasureSpec.getSize(widthMeasureSpec),
            View.MeasureSpec.getSize(heightMeasureSpec),
            scaleType,
            videoWidth,
            videoHeight,
            videoRotationDegree,
        )
        setMeasuredDimension(measured[0], measured[1])
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        val existing = texture
        if (existing != null) {
            setSurfaceTexture(existing)
            return
        }
        texture = surfaceTexture
        surface = Surface(surfaceTexture)
        applyOutputBufferSize()
        val player = mediaPlayer
        if (player != null) {
            player.setSurface(surface)
            notifySurfaceReady()
        }
    }

    private fun notifySurfaceReady() {
        surfaceReadyListener?.run()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
