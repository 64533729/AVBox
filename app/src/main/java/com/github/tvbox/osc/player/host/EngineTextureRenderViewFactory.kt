package com.github.tvbox.osc.player.host

import android.content.Context
import xyz.doikki.videoplayer.render.IRenderView
import xyz.doikki.videoplayer.render.TextureRenderViewFactory

/**
 * 新栈表面宿主工厂(Texture 模式,M7b)。
 *
 * <p>刻意继承旧 [TextureRenderViewFactory]:`MyVideoView.factoryRenderType()`/`ensureRenderViewMatchesConfig()`
 * 以 `instanceof TextureRenderViewFactory` 判定"本次起播实际用哪种渲染视图",继承可让这层判定零改动
 * (M7c 起收口到宿主的 renderType 查询口,见 M7b 登记)。
 */
class EngineTextureRenderViewFactory : TextureRenderViewFactory() {

    companion object {
        @JvmStatic
        fun create(): EngineTextureRenderViewFactory = EngineTextureRenderViewFactory()
    }

    override fun createRenderView(context: Context): IRenderView = EngineTextureRenderView(context)
}
