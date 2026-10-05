package com.github.tvbox.osc.player.host

import android.content.Context

/**
 * 新栈表面宿主工厂(Texture 模式,M7b;M7e 起去 doikki)。
 *
 * <p>旧实现刻意继承 doikki `TextureRenderViewFactory` 以便 `instanceof` 判定渲染方式;去 doikki 后
 * 该判定由 `AppPlayerView.renderType` 按本工厂类型给出(见 `PlayerRenderViewFactory` 的类注释)。
 */
class EngineTextureRenderViewFactory : PlayerRenderViewFactory() {

    companion object {
        @JvmStatic
        fun create(): EngineTextureRenderViewFactory = EngineTextureRenderViewFactory()
    }

    override fun createRenderView(context: Context): PlayerRenderView = EngineTextureRenderView(context)
}
