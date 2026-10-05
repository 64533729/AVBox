package com.github.tvbox.osc.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.DisplayCutout
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import java.lang.reflect.Method

/**
 * 刘海屏工具(去 doikki 承接):移植自 fork 的 `xyz.doikki.videoplayer.util.CutoutUtil`,语义逐条等价。
 *
 * <p>原来有两个消费方:`BaseActivity`(`adaptCutoutAboveAndroidP(Context, boolean)`)与 fork 的
 * `BaseVideoController`(`allowDisplayToCutout` + 刘海高度)。控制器去 View 化后 `allowDisplayToCutout`
 * 在 app 侧仍有调用点(曲面宿主/全屏判定按需复用),故一并保留;`hasCutout*` 反射探测是它的实现细节
 * (华为/OPPO/vivo/小米四家 ROM 的私有 API,Android P 以下无官方 DisplayCutout)。
 */
object CutoutUtil {

    /**
     * 是否允许全屏界面把内容画进刘海区:Android P 起用官方 `DisplayCutout`,P 以下按厂商私有 API 探测。
     *
     * <p>与 AndroidManifest 的 `layoutInDisplayCutoutMode` 配置对应;调用方需自行判空 Activity。
     */
    @JvmStatic
    fun allowDisplayToCutout(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 9.0 系统全屏界面默认会保留黑边,不允许显示内容到刘海区域
            val window: Window = activity.window
            val windowInsets: WindowInsets = window.decorView.rootWindowInsets ?: return false
            val displayCutout: DisplayCutout = windowInsets.displayCutout ?: return false
            val boundingRects: List<Rect> = displayCutout.boundingRects
            return boundingRects.isNotEmpty()
        } else {
            return hasCutoutHuawei(activity) ||
                hasCutoutOPPO(activity) ||
                hasCutoutVIVO(activity) ||
                hasCutoutXIAOMI(activity)
        }
    }

    /** 是否华为刘海屏机型(P 以下,反射 `com.huawei.android.util.HwNotchSizeUtil`) */
    private fun hasCutoutHuawei(activity: Activity): Boolean {
        if (!Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)) return false
        return try {
            val cl: ClassLoader = activity.classLoader
            val hwNotchSizeUtil = cl.loadClass("com.huawei.android.util.HwNotchSizeUtil")
            val get: Method = hwNotchSizeUtil.getMethod("hasNotchInScreen")
            get.invoke(hwNotchSizeUtil) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    /** 是否 OPPO 刘海屏机型(P 以下,查系统特性声明) */
    private fun hasCutoutOPPO(activity: Activity): Boolean {
        if (!Build.MANUFACTURER.equals("oppo", ignoreCase = true)) return false
        return activity.packageManager.hasSystemFeature("com.oppo.feature.screen.heteromorphism")
    }

    /** 是否 vivo 刘海屏机型(P 以下,反射 `android.util.FtFeature.isFeatureSupport(0x20)`) */
    @SuppressLint("PrivateApi")
    private fun hasCutoutVIVO(activity: Activity): Boolean {
        if (!Build.MANUFACTURER.equals("vivo", ignoreCase = true)) return false
        return try {
            val cl: ClassLoader = activity.classLoader
            val ftFeatureUtil = cl.loadClass("android.util.FtFeature")
            val get: Method = ftFeatureUtil.getMethod("isFeatureSupport", Int::class.javaPrimitiveType)
            get.invoke(ftFeatureUtil, 0x00000020) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    /** 是否小米刘海屏机型(P 以下,反射 `android.os.SystemProperties.getInt("ro.miui.notch", 0)`) */
    @SuppressLint("PrivateApi")
    private fun hasCutoutXIAOMI(activity: Activity): Boolean {
        if (!Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)) return false
        return try {
            val cl: ClassLoader = activity.classLoader
            val systemProperties = cl.loadClass("android.os.SystemProperties")
            val getInt: Method = systemProperties.getMethod(
                "getInt",
                String::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            val hasCutout = getInt.invoke(systemProperties, "ro.miui.notch", 0) as Int
            hasCutout == 1
        } catch (e: Exception) {
            false
        }
    }

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

    /** 适配刘海屏(Dialog 版本,口径同上) */
    @JvmStatic
    fun adaptCutoutAboveAndroidP(dialog: Dialog, isAdapt: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp: WindowManager.LayoutParams = dialog.window!!.attributes
            lp.layoutInDisplayCutoutMode = if (isAdapt) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            dialog.window!!.attributes = lp
        }
    }
}
