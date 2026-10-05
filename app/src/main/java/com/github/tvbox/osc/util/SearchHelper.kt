package com.github.tvbox.osc.util

import com.github.tvbox.osc.api.ApiConfig

/**
 * 搜索源选择(哪些源参与全站搜索)。
 *
 * <p>存储:`SOURCES_FOR_SEARCH` 是一张「点播源地址 → {源 key → "1"}」的表 —— 每个源集合各自记住自己的勾选。
 * 表里没有当前地址的记录(或记录为空)= 没限制,搜索当前源集合的**全部可搜源**。
 */
object SearchHelper {

    /**
     * 读取当前地址对应的搜索源选择。
     *
     * @return null 或空表 ⇒ 调用方按"全部可搜源"处理
     */
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

    /**
     * 判断一份选择是否还"对得上当前源列表"。
     *
     * <p>存在的理由(2026-09-13 修):选择是按**源 key** 记的,而源 key 属于**具体的源集合**。
     * 换了点播源之后,旧选择里的 key 在新源列表里基本都不存在 —— 若还拿它去过滤,结果就是
     * "只搜到新旧共有的那一个源"(用户实测:切源后只剩「玩偶4K」能搜到),重启才恢复。
     *
     * <p>判据:选择里的每个 key 都必须在当前源列表里存在。只要有一个对不上,这份选择就已过期。
     */
    @JvmStatic
    fun isSelectionStale(checked: HashMap<String, String>?): Boolean {
        val liveKeys = HashSet<String>()
        for (bean in ApiConfig.get().getSourceBeanList()) {
            liveKeys.add(bean.key!!)
        }
        return isSelectionStale(checked, liveKeys)
    }

    /** 纯判定(与 Android 解耦,便于单测):选择是否已不匹配给定的源 key 集合 */
    @JvmStatic
    fun isSelectionStale(checked: HashMap<String, String>?, liveSourceKeys: Set<String>): Boolean {
        if (checked == null || checked.isEmpty()) return false // 没限制,谈不上过期
        for (checkedKey in checked.keys) {
            if (!liveSourceKeys.contains(checkedKey)) return true
        }
        return false
    }

    /** 当前源集合里所有可搜源(key → "1"),用于"未限制"时的选择 */
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
