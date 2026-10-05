package com.github.tvbox.osc.util

import android.graphics.Color

object DanmuHelper {
    private val PALETTE = arrayOf(
            "#ffffff", "#70f3ff", "#44cef6", "#3eede7", "#00e079", "#2edfa3",
            "#bce672", "#fff143", "#ffa631", "#ff7500", "#ff4e20", "#ff2d51",
            "#ef7a82", "#ff0097", "#b0a4e3", "#4b5cc4"
    )

    @JvmStatic
    fun isOpen(): Boolean {
        return KV.get(HawkConfig.DANMU_OPEN, true)
    }

    @JvmStatic
    fun setOpen(open: Boolean) {
        KV.put(HawkConfig.DANMU_OPEN, open)
    }

    @JvmStatic
    fun getMaxLine(): Int {
        return KV.get(HawkConfig.DANMU_MAX_LINE, 3)
    }

    @JvmStatic
    fun setMaxLine(line: Int) {
        KV.put(HawkConfig.DANMU_MAX_LINE, clamp(line, 1, 15))
    }

    @JvmStatic
    fun getSpeed(): Float {
        return KV.get(HawkConfig.DANMU_SPEED, 1.5f)
    }

    @JvmStatic
    fun setSpeed(speed: Float) {
        KV.put(HawkConfig.DANMU_SPEED, speed)
    }

    @JvmStatic
    fun getAlpha(): Float {
        return KV.get(HawkConfig.DANMU_ALPHA, 0.9f)
    }

    @JvmStatic
    fun setAlpha(alpha: Float) {
        KV.put(HawkConfig.DANMU_ALPHA, Math.max(0.1f, Math.min(alpha, 1.0f)))
    }

    @JvmStatic
    fun getSizeScale(): Float {
        return KV.get(HawkConfig.DANMU_SIZE_SCALE, 0.8f)
    }

    @JvmStatic
    fun setSizeScale(scale: Float) {
        KV.put(HawkConfig.DANMU_SIZE_SCALE, Math.max(0.6f, Math.min(scale, 2.0f)))
    }

    @JvmStatic
    fun useRandomColor(): Boolean {
        return KV.get(HawkConfig.DANMU_RANDOM_COLOR, false)
    }

    @JvmStatic
    fun setRandomColor(randomColor: Boolean) {
        KV.put(HawkConfig.DANMU_RANDOM_COLOR, randomColor)
    }

    @JvmStatic
    fun randomColor(): Int {
        val index = (Math.random() * PALETTE.size).toInt()
        return Color.parseColor(PALETTE[index])
    }

    @JvmStatic
    fun reset() {
        KV.delete(HawkConfig.DANMU_RANDOM_COLOR)
        KV.delete(HawkConfig.DANMU_SPEED)
        KV.delete(HawkConfig.DANMU_SIZE_SCALE)
        KV.delete(HawkConfig.DANMU_MAX_LINE)
        KV.delete(HawkConfig.DANMU_ALPHA)
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return Math.max(min, Math.min(value, max))
    }
}
