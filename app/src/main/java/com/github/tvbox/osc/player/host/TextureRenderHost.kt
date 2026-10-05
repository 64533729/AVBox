package com.github.tvbox.osc.player.host

/**
 * 纹理渲染宿主的扩展面(M7b):旧 `TextureRenderView` 的两个 app 侧钩子
 * (交面回调、输出缓冲尺寸),供 `MyVideoView` 在不依赖 doikki 具体类型的前提下判断与调用。
 */
interface TextureRenderHost {

    /** 交面之后的回调(效果链要求"先交面、后补输出尺寸") */
    fun setOnSurfaceReadyListener(listener: Runnable?)

    /** 输出面尺寸变化:改默认缓冲尺寸并换面(否则输出 EGL 面仍按旧尺寸出画) */
    fun setOutputSize(width: Int, height: Int)
}
