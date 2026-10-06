package com.github.tvbox.osc.sourcedata

import com.github.tvbox.osc.bean.AbsSortXml
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

object SourceRuntimeState {

    @JvmField
    val sortCache: MutableMap<String, AbsSortXml?> =
        object : LinkedHashMap<String, AbsSortXml?>(5, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AbsSortXml?>): Boolean {
                return size > 5
            }
        }

    @JvmField
    val extendCache: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    @JvmStatic
    fun clearRuntimeCache() {
        synchronized(sortCache) {
            sortCache.clear()
        }
        extendCache.clear()
    }
}
