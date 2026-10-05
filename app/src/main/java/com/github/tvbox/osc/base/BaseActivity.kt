package com.github.tvbox.osc.base

import com.github.tvbox.osc.util.LOG
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View

import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.PermissionChecker
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import com.github.tvbox.osc.ui.WindowSize
import com.github.tvbox.osc.util.AppManager
import com.github.tvbox.osc.util.LanguageManager

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

import me.jessyan.autosize.AutoSizeCompat
import me.jessyan.autosize.AutoSizeConfig
import me.jessyan.autosize.internal.CustomAdapt
import com.github.tvbox.osc.util.CutoutUtil

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
abstract class BaseActivity : AppCompatActivity(), CustomAdapt {

    @JvmField
    protected var mContext: Context? = null

    private var orientationPolicy = Int.MIN_VALUE

    private val refreshAutoSizeRunnable = Runnable {
        if (shouldRefreshAutoSize()) {
            refreshAutoSize()
        }
    }

    private val hideSysBarRunnable = Runnable {
        hideSysBar()
    }

    /** 语言资源包裹;必须早于 AppCompat 的 delegate 建基(它依赖包裹后的 base) */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            if (screenRatio < 0) {
                val dm = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(dm)
                updateScreenRatio(dm)
            }
        } catch (th: Throwable) {
            LOG.e("BaseActivity", th)
        }
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false)
            window.setStatusBarContrastEnforced(false)
        }
        setContentView(getLayoutResID())
        mContext = this
        initSystemUiListener()
        CutoutUtil.adaptCutoutAboveAndroidP(mContext!!, true) //设置刘海
        AppManager.getInstance().addActivity(this)
        applyOrientationPolicy()
        init()
    }

    override fun onResume() {
        super.onResume()
        applyOrientationPolicy()
        hideSysBar()
        if (shouldRefreshAutoSize()) {
            refreshAutoSize()
            scheduleRefreshAutoSize()
        }
    }

    open fun hideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            var uiOptions = window.decorView.systemUiVisibility
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_FULLSCREEN
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            window.decorView.systemUiVisibility = uiOptions
        }
        // 再走 InsetsController：把"短暂露出后自动收回"显式钉住(不依赖旧 IMMERSIVE_STICKY 的映射)，
        // 旧接口只保留 LAYOUT_* 的布局语义与下面可见性监听依赖的隐藏位
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun initSystemUiListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val decorView = window.decorView
            decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                val hiddenBars = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
                if ((visibility and hiddenBars) != hiddenBars) {
                    // 兜底：ROM 在沉浸进出/横竖屏/回前台会把系统栏放出来，延时要短于显示窗口
                    decorView.removeCallbacks(hideSysBarRunnable)
                    decorView.postDelayed(hideSysBarRunnable, SYSBAR_REHIDE_DELAY_MS)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        window.decorView.removeCallbacks(refreshAutoSizeRunnable)
        window.decorView.removeCallbacks(hideSysBarRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSysBar()
            if (shouldRefreshAutoSize()) {
                scheduleRefreshAutoSize()
            }
        }
    }

    protected open fun shouldRefreshAutoSize(): Boolean {
        return false
    }

    /**
     * 方向策略:sw<600dp 锁竖屏,>=600dp 放开 —— 与平台在 API 36+ 的忽略范围一致,
     * 故手机档行为不变,大屏交由用户旋转/折叠。
     */
    open fun applyOrientationPolicy() {
        try {
            val desired = orientationPolicyValue()
            // 只在策略值本身变化时下发,否则会覆盖播放器「旋转」按钮刚设过的方向
            if (orientationPolicy == desired) {
                return
            }
            orientationPolicy = desired
            requestedOrientation = desired
        } catch (th: Throwable) {
            LOG.e("BaseActivity", th)
        }
    }

    /** 当前窗口档下的策略值;播放器退出全屏时恢复到此值,而不是硬写竖屏 */
    open fun orientationPolicyValue(): Int {
        try {
            val configuration = super.getResources().configuration
            return if (WindowSize.shouldLockPortrait(configuration.smallestScreenWidthDp))
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } catch (th: Throwable) {
            LOG.e("BaseActivity", th)
            return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationPolicy()
    }

    private fun scheduleRefreshAutoSize() {
        val decorView = window.decorView
        decorView.removeCallbacks(refreshAutoSizeRunnable)
        decorView.postDelayed(refreshAutoSizeRunnable, 300)
    }

    private fun refreshAutoSize() {
        try {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(dm)
            if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
                return
            }
            updateScreenRatio(dm)
            AutoSizeConfig.getInstance()
                .setScreenWidth(dm.widthPixels)
                .setScreenHeight(dm.heightPixels)
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this)
            window.decorView.requestLayout()
        } catch (th: Throwable) {
            LOG.e("BaseActivity", th)
        }
    }

    private fun updateScreenRatio(dm: DisplayMetrics) {
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val min = Math.min(screenWidth, screenHeight)
        if (min > 0) {
            screenRatio = Math.max(screenWidth, screenHeight).toFloat() / min.toFloat()
        }
    }

    override fun getResources(): Resources {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this)
        }
        return super.getResources()
    }

    fun hasPermission(permission: String): Boolean {
        var has = true
        try {
            has = PermissionChecker.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED
        } catch (e: Exception) {
            LOG.e("BaseActivity", e)
        }
        return has
    }

    protected abstract fun getLayoutResID(): Int

    protected abstract fun init()

    override fun onDestroy() {
        super.onDestroy()
        AppManager.getInstance().finishActivity(this)
    }

    protected fun getAssetText(fileName: String): String {
        val stringBuilder = StringBuilder()
        try {
            val assets = assets
            val bf = BufferedReader(InputStreamReader(assets.open(fileName)))
            var line = bf.readLine()
            while (line != null) {
                stringBuilder.append(line)
                line = bf.readLine()
            }
            return stringBuilder.toString()
        } catch (e: IOException) {
            LOG.e("BaseActivity", e)
        }
        return ""
    }

    override fun getSizeInDp(): Float {
        return if (isBaseOnWidth()) 1280f else 720f
    }

    override fun isBaseOnWidth(): Boolean {
        return !(screenRatio >= 4.0f)
    }

    companion object {
        /** 系统栏被 ROM 放出后的兜底重藏延时：要短于"栏可见"的观感窗口，又不抢系统露出动画 */
        private const val SYSBAR_REHIDE_DELAY_MS = 100L

        private var screenRatio = -100.0f
    }
}
