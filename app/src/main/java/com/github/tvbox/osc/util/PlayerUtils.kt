package com.github.tvbox.osc.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Point
import android.os.Build
import android.util.TypedValue
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager

/**
 * 播放器通用工具(去 doikki 承接的第一步):移植自 fork 的 `xyz.doikki.videoplayer.util.PlayerUtils`,
 * 只保留 app 侧真正有调用点的成员(见 [PlayerUtils] 各 KDoc 的调用点清单),语义逐条等价。
 *
 * <p>未移植的成员及理由:状态栏高度系列 / `dp2px` / `sp2px` / `getCurrentSystemTime` / `seconds2Time` /
 * `getSnapshot` / `getNetworkType`(含 6 个网络类型常量)/ `getApplication`(反射私有 API,已废弃)——
 * app 侧零调用点,仅被 fork 的 `VideoView`/`BaseVideoController` 内部使用。
 */
object PlayerUtils {

    /**
     * 格式化时间(时:分:秒 / 分:秒)。负数按 0 处理,小时位存在时用 `H:MM:SS`。
     *
     * <p>等价旧 `String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)` ——
     * 用 `padStart` 代替格式化串:两者对非负 int 输出逐字符相同,同时规避 `String.format` 的装箱与 locale 依赖。
     *
     * <p>调用点:`PlayerBottomBar`、`ComposeVideoController`(seek 提示与片头尾时间)。
     */
    @JvmStatic
    fun stringForTime(timeMs: Int): String {
        var ms = timeMs
        if (ms < 0) ms = 0
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            "$hours:${two(minutes)}:${two(seconds)}"
        } else {
            "${two(minutes)}:${two(seconds)}"
        }
    }

    private fun two(value: Int): String = value.toString().padStart(2, '0')

    /**
     * 把毫秒位置/时长安全收敛到 int(Compose 进度与 OSD 展示用):负数归 0、超界取 `Int.MAX_VALUE`。
     *
     * <p>与 [com.github.tvbox.osc.player.PlaybackTimes.safeTimeMs] 同语义 —— 后者的单测
     * (`PlaybackTimesTest`)已把边界钉住;此处是控制器侧的历史调用面,两份实现保持逐字一致。
     */
    @JvmStatic
    fun safeTimeMs(timeMs: Long): Int {
        if (timeMs <= 0) return 0
        if (timeMs > Int.MAX_VALUE) return Int.MAX_VALUE
        return timeMs.toInt()
    }

    /**
     * 边缘检测:四边各 40dp 内视为边缘,边缘手势不响应(避免与系统返回/下拉冲突)。
     *
     * <p>等价旧实现:用 `rawX/rawY` 与"含导航栏的屏幕宽高"比对 —— 全屏时 `widthPixels` 不含导航栏,
     * 必须补上导航栏高度才能覆盖到真实屏幕右/下边。
     *
     * <p>调用点:`GestureController`、`ComposeLiveController`。
     */
    @JvmStatic
    fun isEdge(context: Context, e: MotionEvent): Boolean {
        val edgeSize = dp2px(context, 40f)
        return e.rawX < edgeSize ||
            e.rawX > getScreenWidth(context, true) - edgeSize ||
            e.rawY < edgeSize ||
            e.rawY > getScreenHeight(context, true) - edgeSize
    }

    /** 从 Context(可能是 ContextWrapper 链)向上找到宿主 Activity;找不到返回 null(等价旧实现) */
    @JvmStatic
    fun scanForActivity(context: Context?): Activity? {
        if (context == null) return null
        if (context is Activity) return context
        if (context is ContextWrapper) return scanForActivity(context.baseContext)
        return null
    }

    /** 屏幕宽度(px);`isIncludeNav = true` 时补上导航栏高度,等价旧实现 */
    @JvmStatic
    fun getScreenWidth(context: Context, isIncludeNav: Boolean): Int {
        return if (isIncludeNav) {
            context.resources.displayMetrics.widthPixels + getNavigationBarHeight(context)
        } else {
            context.resources.displayMetrics.widthPixels
        }
    }

    /** 屏幕高度(px);口径同 [getScreenWidth] */
    private fun getScreenHeight(context: Context, isIncludeNav: Boolean): Int {
        return if (isIncludeNav) {
            context.resources.displayMetrics.heightPixels + getNavigationBarHeight(context)
        } else {
            context.resources.displayMetrics.heightPixels
        }
    }

    /** 导航栏高度(px);无导航栏时为 0 */
    private fun getNavigationBarHeight(context: Context): Int {
        if (!hasNavigationBar(context)) return 0
        val resources = context.resources
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return resources.getDimensionPixelSize(resourceId)
    }

    /** 是否存在导航栏:比较 `getSize` 与 `getRealSize` 的差值(等价旧实现) */
    private fun hasNavigationBar(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val display = getWindowManager(context).defaultDisplay
            val size = Point()
            val realSize = Point()
            display.getSize(size)
            display.getRealSize(realSize)
            return realSize.x != size.x || realSize.y != size.y
        } else {
            val menu = ViewConfiguration.get(context).hasPermanentMenuKey()
            val back = KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_BACK)
            return !(menu || back)
        }
    }

    /** 等价旧实现:未创建也直接取系统服务(返回的必定非空,类型不可空) */
    private fun getWindowManager(context: Context): WindowManager {
        return context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    /** dp → px(等价旧 `TypedValue.applyDimension` + 截断取整) */
    private fun dp2px(context: Context, dpValue: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dpValue,
            context.resources.displayMetrics,
        ).toInt()
    }
}
