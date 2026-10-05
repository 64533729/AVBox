package com.github.tvbox.osc.util

import android.app.Activity

object SubtitleHelper {

    @JvmStatic
    fun getSubtitleTextAutoSize(activity: Activity?): Int {
        val screenSqrt = ScreenUtils.getSqrt(activity!!)
        var subtitleTextSize = 16
        if (screenSqrt > 7.0 && screenSqrt <= 13.0) {
            subtitleTextSize = 24
        } else if (screenSqrt > 13.0 && screenSqrt <= 50.0) {
            subtitleTextSize = 36
        } else if (screenSqrt > 50.0) {
            subtitleTextSize = 46
        }
        return subtitleTextSize
    }

    @JvmStatic
    fun getTextSize(activity: Activity?): Int {
        val autoSize = getSubtitleTextAutoSize(activity)
        val subtitleConfigSize = KV.get(HawkConfig.SUBTITLE_TEXT_SIZE, autoSize)
        return subtitleConfigSize
    }

    @JvmStatic
    fun setTextSize(size: Int) {
        KV.put(HawkConfig.SUBTITLE_TEXT_SIZE, size)
    }

    @JvmStatic
    fun getTimeDelay(): Int {
        val subtitleConfigTimeDelay = KV.get(HawkConfig.SUBTITLE_TIME_DELAY, 0)
        return subtitleConfigTimeDelay
    }

    @JvmStatic
    fun setTimeDelay(delay: Int) {
        KV.put(HawkConfig.SUBTITLE_TIME_DELAY, delay)
    }

    @JvmStatic
    fun getExoSubtitleScale(): Int {
        return KV.get(HawkConfig.SUBTITLE_EXO_SCALE, 100)
    }

    @JvmStatic
    fun setExoSubtitleScale(scale: Int) {
        KV.put(HawkConfig.SUBTITLE_EXO_SCALE, scale)
    }

    @JvmStatic
    fun getExoSubtitlePosition(): Float {
        return KV.get(HawkConfig.SUBTITLE_EXO_POSITION, 0.0f)
    }

    @JvmStatic
    fun setExoSubtitlePosition(position: Float) {
        KV.put(HawkConfig.SUBTITLE_EXO_POSITION, position)
    }

    @JvmStatic
    fun reset() {
        KV.delete(HawkConfig.SUBTITLE_TEXT_SIZE)
        KV.delete(HawkConfig.SUBTITLE_TIME_DELAY)
        KV.delete(HawkConfig.SUBTITLE_TEXT_STYLE)
        KV.delete(HawkConfig.SUBTITLE_EXO_SCALE)
        KV.delete(HawkConfig.SUBTITLE_EXO_POSITION)
    }

}
