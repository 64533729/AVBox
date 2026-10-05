package com.github.tvbox.osc.base

import android.app.Activity
import android.app.Application
import android.content.Context

import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppDataManager
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.util.AppContextHolder
import com.github.tvbox.osc.util.AppManager
import com.github.tvbox.osc.util.EpgUtil
import com.github.tvbox.osc.util.FileUtils
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.OkGoHelper
import com.github.catvod.crawler.JsLoader
import com.p2p.P2PClass
import com.whl.quickjs.android.QuickJSLoader

import me.jessyan.autosize.AutoSizeConfig
import me.jessyan.autosize.unit.Subunits
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
class App : Application() {
    private var vodInfo: VodInfo? = null

    /**
     * 语言资源包裹必须最早做(attachBaseContext 早于 onCreate),而语言选择存在 KV 里
     * ⇒ 在这里提前 KV.init(幂等,onCreate 里那处保留不动)。
     */
    override fun attachBaseContext(base: Context) {
        KV.init(base)
        super.attachBaseContext(LanguageManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 下层(util/data/server/catvod)读 Context 一律走这里,不再反向依赖 App 类
        AppContextHolder.install(this)
        // 启动看门狗(2026-09-21):装崩溃记录器。必须最早装 —— 第三方爬虫可能在
        // Application.onCreate 之后的任意时刻于自己的线程上闪退,晚了就记不到。
        com.github.tvbox.osc.util.BootGuard.install()
        initParams()
        // OKGo
        OkGoHelper.init() //台标获取
        EpgUtil.init()
        // 初始化Web服务器
        ControlManager.init(this)
        //初始化数据库
        AppDataManager.init()
        AutoSizeConfig.getInstance().setCustomFragment(true).unitsManager
            .setSupportDP(false)
            .setSupportSP(false)
            .setSupportSubunits(Subunits.MM)
        // 共享缓存容量(第二期扩展):设置项 → player 模块(须在首次 getSharedCache 前注入,改动重启 App 生效)
        ExoMediaSourceHelper.setSharedCacheSizeBytes(
            Math.max(128, KV.get(HawkConfig.EXO_CACHE_SIZE_MB, HawkConfig.EXO_CACHE_SIZE_MB_DEFAULT)) * 1024L * 1024L
        )
        QuickJSLoader.init()
        // Coil 单例:海报地址约定的请求头注入(Compose UI 图片管线)
        com.github.tvbox.osc.ui.components.VodImages.init(this)
        FileUtils.cleanPlayerCache()
        // 「清除缓存」遗留的 Exo 视频缓存清理(2026-09-13):clearCache 不直接删该目录(进程级 SimpleCache
        // 常驻,直删会"内存索引/磁盘失配"),改为启动早期删除 —— 必须早于首次 getSharedCache,且目录删除
        // 属耗时 IO,放后台线程(无待清理标记时仅一次 exists() 检查,零开销)
        Thread(FileUtils::purgeExoCacheIfPending, "exo-cache-purge").start()
    }

    private fun initParams() {
        // KV 存储(2026-09-13 起为唯一实现,取代 Hawk):初始化必须在任何 KV 读写之前
        KV.init(this)
        KV.put(HawkConfig.PLAYER_IS_LIVE, false)
        if (!KV.contains(HawkConfig.PLAY_TYPE)) {
            KV.put(HawkConfig.PLAY_TYPE, 2)
        } else {
            val playType = KV.get(HawkConfig.PLAY_TYPE, 2)
            // 0 为非法值、1 为已移除的 IJK 内核 —— 一并归一到 EXO,避免设置页选不出内核
            if (playType == 0 || playType == 1) {
                KV.put(HawkConfig.PLAY_TYPE, 2)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        JsLoader.destroy()
    }

    fun setVodInfo(vodinfo: VodInfo?) {
        this.vodInfo = vodinfo
    }

    fun getVodInfo(): VodInfo? {
        return this.vodInfo
    }

    fun getCurrentActivity(): Activity {
        return AppManager.getInstance().currentActivity()
    }

    fun setDashData(data: String?) {
        dashData = data
    }

    fun getDashData(): String? {
        return dashData
    }

    companion object {
        private var instance: App? = null

        private var p: P2PClass? = null

        @JvmField
        var burl: String? = null

        private var dashData: String? = null

        @JvmStatic
        fun getInstance(): App? {
            return instance
        }

        @JvmStatic
        fun getp2p(): P2PClass? {
            try {
                if (p == null) {
                    p = P2PClass(FileUtils.getExternalCachePath())
                }
                return p
            } catch (e: Exception) {
                LOG.e(e.toString())
                return null
            }
        }
    }
}
