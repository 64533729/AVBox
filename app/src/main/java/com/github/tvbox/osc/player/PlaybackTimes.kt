package com.github.tvbox.osc.player

/**
 * 播放时间工具(M7d):承接 doikki `PlayerUtils.safeTimeMs` 的等价语义 ——
 * 把毫秒位置/时长安全收敛到 int(Compose 进度与 OSD 展示用):负数归 0、超界取 [Int.MAX_VALUE]。
 */
object PlaybackTimes {

    fun safeTimeMs(timeMs: Long): Int {
        if (timeMs <= 0) return 0
        if (timeMs > Int.MAX_VALUE) return Int.MAX_VALUE
        return timeMs.toInt()
    }
}
