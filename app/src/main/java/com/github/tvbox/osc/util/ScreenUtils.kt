package com.github.tvbox.osc.util

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager

object ScreenUtils {

    @JvmStatic
    fun getSqrt(activity: Activity): Double {
        val wm: WindowManager = activity.windowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getMetrics(dm)
        val x = Math.pow((dm.widthPixels / dm.xdpi).toDouble(), 2.0)
        val y = Math.pow((dm.heightPixels / dm.ydpi).toDouble(), 2.0)
        val screenInches = Math.sqrt(x + y)// 屏幕尺寸
        return screenInches
    }

    /**
     * 是否处于 TV 模式(锁屏钮 / 若干 gesture 据此隐藏)。
     *
     * <p>2026-09-13 收窄判据:原实现是 `UI_MODE_TYPE_TELEVISION || (屏幕很大 && !是手机)`,
     * 其中"是手机"靠 `TelephonyManager.getPhoneType()` —— 该调用**需要 READ_PHONE_STATE**
     * (API 23+ 未授权会 SecurityException),于是清单里长期挂着一个没人用的敏感权限。
     *
     * <p>但本项目是**纯手机定位**(`abiFilters` 只有 arm64-v8a、TV/遥控器适配代码已全删,
     * 见 avbox-mobile-ui-spec §1):真正需要防的反而是"大屏手机被 SCREENLAYOUT_SIZE_LARGE
     * 误判成 TV"(会莫名隐藏锁屏钮)。故只保留"系统声明为 TV"这一个权威判据,
     * 电话权限随之从清单移除。
     */
    @JvmStatic
    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager?
        return uiModeManager != null
                && uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
