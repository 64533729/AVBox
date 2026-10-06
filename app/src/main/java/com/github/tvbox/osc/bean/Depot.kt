package com.github.tvbox.osc.bean

import com.github.tvbox.osc.util.LOG
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 多仓(仓库)配置里 [urls] 数组的一项 —— 一条子源。
 *
 * 字段语义对齐 FongMi/TV 的 com.fongmi.android.tv.bean.Depot;但解析手写遍历,
 * 因为本项目的 [urls] 有三种写法(Gson 直接映射会把裸字符串那条整条丢掉)。
 */
class Depot {

    private var url: String? = null
    private var name: String? = null

    fun getUrl(): String {
        return url?.trim { it <= ' ' } ?: ""
    }

    fun getName(): String {
        val value = name?.trim { it <= ' ' } ?: ""
        return if (value.isEmpty()) getUrl() else value
    }

    companion object {

        /**
         * 与 TextUtils.isEmpty 等价 —— 不用 android.text.TextUtils:单测开了 returnDefaultValues,
         * 它会静默返回 false,判空在单测里失效(本仓已踩三次)。
         */
        private fun isEmpty(text: String?): Boolean = text == null || text.isEmpty()

        /**
         * 解析 [urls] 数组,兼容 `{"url":…}`、`{"api":…}` 与裸字符串三种写法;
         * 空地址条目丢弃,任何异常按"不是多仓"处理(保留已解析到的条目)。
         */
        @JvmStatic
        fun arrayFrom(urls: JsonArray?): List<Depot> {
            val items = ArrayList<Depot>()
            if (urls == null) return items
            try {
                for (element in urls) {
                    if (element == null || element.isJsonNull) continue
                    val depot = Depot()
                    if (element.isJsonObject) {
                        val item = element.asJsonObject
                        depot.url = string(item, "url")
                        if (isEmpty(depot.url)) depot.url = string(item, "api")
                        depot.name = string(item, "name")
                    } else if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                        // 只收字符串:getAsString() 会把数字 123 也变成 "123"
                        depot.url = element.asString
                    }
                    if (!isEmpty(depot.getUrl())) items.add(depot)
                }
            } catch (th: Throwable) {
                LOG.d("Depot", "depot urls parse failed, keep items so far")
            }
            return items
        }

        /** 取字符串字段:非字符串(数字等)按"没写"处理并回落 url,与 DefaultConfig.safeJsonString 同宽松度 */
        @JvmStatic
        fun string(json: JsonObject, key: String): String {
            val element = json.get(key)
            if (element == null || !element.isJsonPrimitive) return ""
            return try {
                if (element.asJsonPrimitive.isString) element.asString else ""
            } catch (th: Throwable) {
                ""
            }
        }
    }
}
