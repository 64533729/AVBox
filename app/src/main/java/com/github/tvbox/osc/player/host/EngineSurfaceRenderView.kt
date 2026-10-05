package com.github.tvbox.osc.player.host

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.github.tvbox.osc.player.KernelPlayer

/**
 * 新栈表面宿主(Surface 模式,M7b):逐行为等价移植旧 `osc.player.render.SurfaceRenderView`,
 * 测量算法用 app 侧自有的 [RenderMeasure]。
 *
 * <p>交面/离面语义照旧:Created/Changed → `setDisplay(holder)`、Destroyed → `setDisplay(null)`;
 * 输出分辨率补发由内核侧 `setDisplay` 完成(Surface 路径唯一补发点,见 PlayerEngine.setDisplay)。
 *
 * <p>去 doikki(M7e 起):渲染契约由 [PlayerRenderView] 承担、内核形参由 doikki `AbstractPlayer`
 * 换成 app 侧 [KernelPlayer]。
 */
class EngineSurfaceRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr), PlayerRenderView, SurfaceHolder.Callback {

    private var mediaPlayer: KernelPlayer? = null

    private var scaleType = RenderMeasure.SCALE_DEFAULT

    private var videoWidth = 0

    private var videoHeight = 0

    private var videoRotationDegree = 0

    init {
        val surfaceHolder = holder
        surfaceHolder.addCallback(this)
        surfaceHolder.setFormat(PixelFormat.RGBA_8888)
    }

    override fun attachToPlayer(player: KernelPlayer) {
        mediaPlayer = player
        val surfaceHolder = holder
        val surface = surfaceHolder.surface
        if (surface != null && surface.isValid) {
            player.setDisplay(surfaceHolder)
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

    override fun doScreenShot(): Bitmap? = null

    override fun release() = Unit

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

    override fun surfaceCreated(holder: SurfaceHolder) {
        mediaPlayer?.setDisplay(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mediaPlayer?.setDisplay(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mediaPlayer?.setDisplay(null)
    }
}
