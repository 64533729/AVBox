package com.github.tvbox.osc.util

import com.github.tvbox.osc.api.ApiConfig

object SearchHelper {

    @JvmStatic
    fun getSourcesForSearch(): HashMap<String, String>? {
        var mCheckSources: HashMap<String, String>? = null
        try {
            val api = KV.get(HawkConfig.API_URL, "")
            if (api.isEmpty()) return null
            val mCheckSourcesForApi: HashMap<String, HashMap<String, String>> =
                    KV.get(HawkConfig.SOURCES_FOR_SEARCH, HashMap())
            mCheckSources = mCheckSourcesForApi[api]
        } catch (e: Exception) {
            return null
        }
        if (mCheckSources == null || mCheckSources.isEmpty()) {
            mCheckSources = getSources()
        }
        return mCheckSources
    }

    @JvmStatic
    fun isSelectionStale(checked: HashMap<String, String>?): Boolean {
        val liveKeys = HashSet<String>()
        for (bean in ApiConfig.get().getSourceBeanList()) {
            liveKeys.add(bean.key!!)
        }
        return isSelectionStale(checked, liveKeys)
    }

    @JvmStatic
    fun isSelectionStale(checked: HashMap<String, String>?, liveSourceKeys: Set<String>): Boolean {
        if (checked == null || checked.isEmpty()) return false
        for (checkedKey in checked.keys) {
            if (!liveSourceKeys.contains(checkedKey)) return true
        }
        return false
    }

    @JvmStatic
    fun getSources(): HashMap<String, String> {
        val mCheckSources = HashMap<String, String>()
        for (bean in ApiConfig.get().getSourceBeanList()) {
            if (!bean.isSearchable()) {
                continue
            }
            mCheckSources[bean.key!!] = "1"
        }
        return mCheckSources
    }

}
