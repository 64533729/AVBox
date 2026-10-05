package com.github.tvbox.osc.player.host

import android.content.Context
import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.View
import com.github.tvbox.osc.player.KernelPlayer

/**
 * 渲染宿主契约(去 doikki 承接):取代 fork 的 `xyz.doikki.videoplayer.render.IRenderView`。
 *
 * <p>宿主([com.github.tvbox.osc.player.AppPlayerView])只经此接口操作渲染视图;两种实现分别是
 * [EngineSurfaceRenderView](SurfaceView)与 [EngineTextureRenderView](TextureView)。
 *
 * <p>与旧接口的差异:`attachToPlayer` 的形参由 doikki `AbstractPlayer` 换成 app 侧的 [KernelPlayer]
 * (纯类型替换);`doScreenShot()` 由 `Bitmap` 改为 `Bitmap?`(Surface 路径本就返回 null)。
 */
interface PlayerRenderView {

    /** 把内核挂上来:实现自行决定用 Surface 还是 SurfaceHolder 交面 */
    fun attachToPlayer(player: KernelPlayer)

    /** 视频原生尺寸(0/0 = 未知) */
    fun setVideoSize(videoWidth: Int, videoHeight: Int)

    /** 旋转角度(0/90/180/270) */
    fun setVideoRotation(degree: Int)

    /** 画面比例(取 [RenderMeasure] 的 `SCALE_*`) */
    fun setScaleType(scaleType: Int)

    /** 本实现对应的 View(就是自己) */
    fun getView(): View

    /** 截图;Surface 路径不支持,返回 null */
    fun doScreenShot(): Bitmap?

    /** 释放:实现须自行摘掉 Surface/回调 */
    fun release()
}

/** 渲染宿主工厂(取代 fork 的 `RenderViewFactory`);[com.github.tvbox.osc.player.engine.PlayerEngine]
 *  直接持有实现类,不再经工厂接口创建,本类型保留给宿主侧"按配置建视图"的入口。 */
abstract class PlayerRenderViewFactory {

    abstract fun createRenderView(context: Context): PlayerRenderView
}
