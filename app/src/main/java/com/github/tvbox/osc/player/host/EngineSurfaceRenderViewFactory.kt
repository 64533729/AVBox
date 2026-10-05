package com.github.tvbox.osc.player.host

import android.content.Context

/**
 * 新栈表面宿主工厂(Surface 模式,M7b;M7e 起去 doikki)。
 *
 * <p>宿主侧的"本次起播用哪种渲染方式"判定改走 [PlayerRenderViewFactory] 的具体类型
 * (Texture 判定见 `AppPlayerView.renderType`),不再依赖 doikki `TextureRenderViewFactory`。
 */
class EngineSurfaceRenderViewFactory : PlayerRenderViewFactory() {

    companion object {
        @JvmStatic
        fun create(): EngineSurfaceRenderViewFactory = EngineSurfaceRenderViewFactory()
    }

    override fun createRenderView(context: Context): PlayerRenderView = EngineSurfaceRenderView(context)
}
