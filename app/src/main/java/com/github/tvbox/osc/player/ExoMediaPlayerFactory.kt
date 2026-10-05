package com.github.tvbox.osc.player

import android.content.Context
import xyz.doikki.videoplayer.player.PlayerFactory

class ExoMediaPlayerFactory : PlayerFactory<ExoPlayer>() {

    override fun createPlayer(context: Context): ExoPlayer = ExoPlayer(context)

    companion object {
        @JvmStatic
        fun create(): ExoMediaPlayerFactory = ExoMediaPlayerFactory()
    }
}
