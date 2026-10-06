package com.github.tvbox.osc.player.effect

object RedrawPolicy {

    @JvmStatic
    fun shouldRedrawOnGeometry(sizeChanged: Boolean, playing: Boolean, redrawReady: Boolean): Boolean =
        sizeChanged && !playing && redrawReady

    @JvmStatic
    fun shouldRedrawOnParams(playing: Boolean, redrawReady: Boolean): Boolean = !playing && redrawReady
}
