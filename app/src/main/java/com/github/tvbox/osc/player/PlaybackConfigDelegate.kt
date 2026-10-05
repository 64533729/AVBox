package com.github.tvbox.osc.player

import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import org.json.JSONObject

/** 播放器配置:会话开始时的补全、落库快照(剔自动容错态)、换集时跟随全局解码设置。 */
class PlaybackConfigDelegate(private val host: Host) {

    interface Host {
        fun vod(): VodInfo?

        fun sourceBean(): SourceBean?

        fun playerCfg(): JSONObject?

        fun setPlayerCfg(cfg: JSONObject)

        fun attemptState(): PlaybackAttemptState
    }

    /**
     * 初始化/补全播放器配置(内核 pl、渲染 pr/sc/sp/st/et)。
     * 与原实现一致:优先沿用 vod.playerCfg 里已存的值,缺失项回落全局设置。
     */
    fun initPlayerCfg() {
        val cfg = try {
            JSONObject(host.vod()!!.playerCfg)
        } catch (th: Throwable) {
            JSONObject()
        }
        try {
            if (!cfg.has("pl")) {
                // sourceBean 可能为空(切源窗口期 / 源被删):
                // 原写法在这里 NPE,而本块 catch(Throwable) 是空的 —— 会静默跳过下面
                // pr/sc/sp/st/et 全部设置,播放器配置只剩半截。改为退回全局播放器设置。
                val sourcePlayerType = if (host.sourceBean() == null) -1 else host.sourceBean()!!.playerType
                cfg.put("pl", if (sourcePlayerType == -1) (KV.get(HawkConfig.PLAY_TYPE, 2) as Int) else sourcePlayerType)
            }
            // 0 非法、1 为已移除的 IJK 内核 —— 一并归一到 EXO(老源配置/播放记录里可能还是 1)
            val configuredType = cfg.optInt("pl", 2)
            if (configuredType == 0 || configuredType == 1) {
                cfg.put("pl", 2)
            }
            cfg.put("pr", KV.get(HawkConfig.PLAY_RENDER, 1))
            // 解码方式以**全局设置**为准,只有用户在本剧播放器里显式选过(exoSet,见 ComposeVideoController)
            // 才按剧记忆 —— 否则播放记录里持久化的旧 "exo" 会一直压过设置页的新值,
            // "设置里改成软解、这部剧却永远硬解"。
            if (cfg.optInt("exoSet", 0) == 0) {
                cfg.put("exo", KV.get(HawkConfig.EXO_DECODE, "硬解码")) // i18n: keep
            }
            if (!cfg.has("sc")) {
                cfg.put("sc", KV.get(HawkConfig.PLAY_SCALE, 0))
            }
            if (!cfg.has("sp")) {
                cfg.put("sp", 1.0f)
            }
            if (!cfg.has("st")) {
                cfg.put("st", 0)
            }
            if (!cfg.has("et")) {
                cfg.put("et", 0)
            }
        } catch (th: Throwable) {
            // 与原实现一致:补全失败不阻断播放(配置保持已解析出的部分)
            LOG.d("PlaybackController", "initPlayerCfg fill-up failed, keep parsed part")
        }
        host.setPlayerCfg(cfg)
    }

    /**
     * 用户手动切内核(播放器选择按钮):自动切内核态作废 —— ①用户的选择要能落库(playerCfgForPersist 不再回填原值)
     * ②后续换线也不再自动回滚成"自动切换前的内核"(用户的选择优先)。
     * ⚠️ 调用方必须在 updatePlayerCfg()(落库)**之前**调用本方法,否则本次落库仍会带回填值。
     */
    fun setAllowSwitchPlayer(allow: Boolean) {
        val st = host.attemptState()
        st.allowSwitchPlayer = allow
        if (!allow) {
            st.autoSwitchedPlayerType = -1
        }
    }

    /**
     * 用户手动选过解码方式(播放器解码按钮):本次播放不再自动回退软解,且"自动软解"态作废 ——
     * 后者是为了让用户显式选的值能正常落进播放记录(见 [playerCfgForPersist])。
     */
    fun setAllowDecodeFallback(allow: Boolean) {
        if (allow) return
        val st = host.attemptState()
        st.hasAutoSwitchedDecode = true
        st.autoSwitchedDecodeOld = null
    }

    /**
     * 落库用的播放器配置快照:**自动容错态不得进入播放记录**。
     *
     * 自动软解(exo)只是本次会话的临时回退,但覆盖层任一设置改动都会经
     * `PlayContainer.updatePlayerCfg()` 把当时的 playerCfg 整体写进记录/发 EventBus ——
     * 于是临时回退变成"按剧记忆",把用户的设置永久顶掉。这里返回剔除自动态后的**副本**,
     * 内存中的 [Host.playerCfg] 不受影响(播放仍按自动态跑)。
     */
    fun playerCfgForPersist(): JSONObject? {
        val cfg = host.playerCfg() ?: return null
        return try {
            val copy = JSONObject(cfg.toString())
            val st = host.attemptState()
            if (st.autoSwitchedPlayerType >= 0) {
                copy.put("pl", st.autoSwitchedPlayerType)
            }
            // 只看"自动态是否仍在生效"(autoSwitchedDecodeOld),不看每次播放的阻断标记 hasAutoSwitchedDecode ——
            // 后者会被 beginNewPlay(换集)复位,若一并作为条件,换集后下一次落库就会把自动软解写进记录
            if (st.autoSwitchedDecodeOld != null) {
                copy.put(st.autoSwitchedDecodeKey, st.autoSwitchedDecodeOld)
            }
            copy
        } catch (th: Throwable) {
            cfg
        }
    }

    /**
     * 未按剧锁定时,让 cfg 的解码键跟随全局设置(换集入口调用)。
     *
     * 背景:cfg 里的 "exo" 是**会话开始时**由 [initPlayerCfg] 从全局写下的副本;
     * 用户在换集期间去设置页改了解码方式,不刷新的话要等下一部片才生效 ——
     * 这里在换集入口重写一次,让本次换集即按最新设置起播。
     *
     * 两种不能刷新:①按剧锁定(exoSet == 1,用户显式选过);②**自动软解态**
     * (autoSwitchedDecodeOld != null)是本次会话的回退结果,用全局值顶掉就等于把回退作废。
     */
    fun syncDecodeFromGlobal() {
        val cfg = host.playerCfg() ?: return
        val st = host.attemptState()
        val autoExo = st.autoSwitchedDecodeOld != null && "exo" == st.autoSwitchedDecodeKey
        try {
            if (cfg.optInt("exoSet", 0) == 0 && !autoExo) {
                cfg.put("exo", KV.get(HawkConfig.EXO_DECODE, "硬解码")) // i18n: keep
            }
        } catch (th: Throwable) {
            // 与 initPlayerCfg 一致:刷新失败不阻断播放
            LOG.d("PlaybackController", "syncDecodeFromGlobal failed, keep current cfg")
        }
    }
}
