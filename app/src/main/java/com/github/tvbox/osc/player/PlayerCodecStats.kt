package com.github.tvbox.osc.player

class PlayerCodecStats private constructor() {

    companion object {
        @JvmField
        @Volatile
        var videoDecoderName: String = ""
    }
}
