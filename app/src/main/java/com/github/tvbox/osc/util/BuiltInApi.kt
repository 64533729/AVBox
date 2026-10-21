package com.github.tvbox.osc.util

/**
 * 内置 TVBOX 接口。
 *
 * 首次启动（或本地未配置任何接口）时自动启用该接口；
 * 用户手动配置过其他接口后，以用户配置为准，不再覆盖。
 * 若该接口曾被 BootGuard 判定为崩溃循环源并禁用，则保持禁用，避免启动死循环。
 */
object BuiltInApi {

    /** 内置接口地址（点播配置，含直播/解析等） */
    const val API_URL = "https://www.cttv.vip/ys/json/ctys.json"

    @JvmStatic
    fun applyIfMissing() {
        try {
            val current = KV.get(HawkConfig.API_URL, "")
            if (current.isNotEmpty()) return
            if (BootGuard.isDisabledSource(API_URL)) return
            KV.put(HawkConfig.API_URL, API_URL)
            HistoryHelper.setApiHistory(API_URL)
        } catch (ignored: Throwable) {
        }
    }
}
