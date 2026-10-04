package com.github.tvbox.osc.data

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV

/**
 * 当前订阅标识:普通源取生效地址;仓模式下 API_URL 已被改写成仓内子源,而订阅列表里记的是仓地址,
 * 必须取仓地址 —— 否则收藏/历史与订阅列表对不上(收藏会被判成"源不可用",路由也找不回原订阅)。
 */
internal object CurrentSubscription {

    fun cid(): String {
        val apiUrl = KV.get(HawkConfig.API_URL, "")
        val lineSource = KV.get(HawkConfig.API_LINE_SOURCE, "")
        if (lineSource != null && lineSource.isNotEmpty() && HistoryHelper.isApiLineUrl(apiUrl)) {
            return lineSource
        }
        return apiUrl
    }
}
