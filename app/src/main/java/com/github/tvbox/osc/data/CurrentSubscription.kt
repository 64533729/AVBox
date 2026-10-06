package com.github.tvbox.osc.data

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV

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
