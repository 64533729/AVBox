package com.github.tvbox.osc.base

import android.app.Activity
import android.app.Application
import android.content.Context

import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppDataManager
import com.github.tvbox.osc.data.PlaybackPorts
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
import com.github.tvbox.osc.player.engine.PlayerCache

class App : Application() {
    private var vodInfo: VodInfo? = null

    override fun attachBaseContext(base: Context) {
        KV.init(base)
        super.attachBaseContext(LanguageManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppContextHolder.install(this)
        PlaybackPorts.currentVod = { getInstance()?.getVodInfo() }
        com.github.tvbox.osc.util.BootGuard.install()
        initParams()
        OkGoHelper.init()
        EpgUtil.init()
        ControlManager.init(this)
        AppDataManager.init()
        AutoSizeConfig.getInstance().setCustomFragment(true).unitsManager
            .setSupportDP(false)
            .setSupportSP(false)
            .setSupportSubunits(Subunits.MM)
        PlayerCache.setSharedCacheSizeBytes(
            Math.max(128, KV.get(HawkConfig.EXO_CACHE_SIZE_MB, HawkConfig.EXO_CACHE_SIZE_MB_DEFAULT)) * 1024L * 1024L
        )
        QuickJSLoader.init()
        com.github.tvbox.osc.ui.components.VodImages.init(this)
        FileUtils.cleanPlayerCache()
        Thread(FileUtils::purgeExoCacheIfPending, "exo-cache-purge").start()
    }

    private fun initParams() {
        KV.init(this)
        KV.put(HawkConfig.PLAYER_IS_LIVE, false)
        if (!KV.contains(HawkConfig.PLAY_TYPE)) {
            KV.put(HawkConfig.PLAY_TYPE, 2)
        } else {
            val playType = KV.get(HawkConfig.PLAY_TYPE, 2)
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
