package com.github.tvbox.osc.player

import java.util.concurrent.atomic.AtomicInteger

class PlayerCodecStats private constructor() {

    companion object {
        @JvmField
        @Volatile
        var videoDecoderName: String = ""

        /** 真实生效的视频解码类型；未初始化时为 UNKNOWN，显示侧需回落到档位。 */
        @JvmField
        @Volatile
        var videoDecodeKind: PlayerDecodeKind = PlayerDecodeKind.UNKNOWN

        /** 每次内核重建并初始化 codec 时自增，用于让“切档待生效”的显示意图失效。 */
        @JvmField
        val videoCodecGeneration = AtomicInteger(0)

        @JvmStatic
        fun resetVideo() {
            videoDecoderName = ""
            videoDecodeKind = PlayerDecodeKind.UNKNOWN
            videoCodecGeneration.incrementAndGet()
        }
    }
}
