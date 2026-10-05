package com.github.tvbox.osc.player.host

import android.content.Context
import xyz.doikki.videoplayer.render.IRenderView
import xyz.doikki.videoplayer.render.RenderViewFactory

/**
 * 新栈表面宿主工厂(Surface 模式,M7b)。切换保留旧 `SurfaceRenderViewFactory` 作回退面
 * (注入点见 `PlayerHelper.updateCfg`)。
 */
class EngineSurfaceRenderViewFactory : RenderViewFactory() {

    companion object {
        @JvmStatic
        fun create(): EngineSurfaceRenderViewFactory = EngineSurfaceRenderViewFactory()
    }

    override fun createRenderView(context: Context): IRenderView = EngineSurfaceRenderView(context)
}
