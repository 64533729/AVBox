package com.github.tvbox.osc.util

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager

/**
 * 刘海屏工具(去 doikki 承接):移植自 fork 的 `xyz.doikki.videoplayer.util.CutoutUtil`。
 *
 * <p>移植口径 = **只保留 app 侧真正有调用点的成员**:`adaptCutoutAboveAndroidP(Context, boolean)`
 * (唯一调用点 `BaseActivity.hideSysBar`)。`allowDisplayToCutout` 与 4 个厂商反射探测在 app 侧
 * **零调用点**(旧调用方只有 fork 的 `BaseVideoController`,随控制器去 View 化删除;
 * 播放页的刘海避让已由 Compose 侧 `WindowInsets.displayCutout` 承担,见 `PlayerTopBar`),
 * 故不再移植 —— 少一份"只在 P 以下生效、靠厂商私有 API"的活代码。
 *
 * <p>`adaptCutoutAboveAndroidP(Dialog, boolean)` 同样零调用点,一并去掉。
 */
object CutoutUtil {

    /**
     * 适配刘海屏(Android P 以上):`isAdapt = true` 用 `SHORT_EDGES` 允许内容进入刘海区,
     * false 还原 `DEFAULT`。非 Activity 的 Context 静默返回(等价旧实现)。
     */
    @JvmStatic
    fun adaptCutoutAboveAndroidP(context: Context, isAdapt: Boolean) {
        val activity: Activity = PlayerUtils.scanForActivity(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp: WindowManager.LayoutParams = activity.window.attributes
            lp.layoutInDisplayCutoutMode = if (isAdapt) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            activity.window.attributes = lp
        }
    }
}
