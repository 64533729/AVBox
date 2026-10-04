package com.github.tvbox.osc.api

import com.github.tvbox.osc.bean.ParseBean
import com.github.tvbox.osc.util.AdBlocker
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.M3u8
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.VideoParseRuler
import com.google.gson.JsonObject

import java.util.ArrayList

/** 把配置 JSON 的规则段物化到全局表:嗅探规则/广告拦截/DNS/解析器列表 */
object ConfigApplier {

    /** 嗅探规则(rules):host 规则/过滤、广告正则、click 脚本与 exclude 排除 */
    @JvmStatic
    fun applyHostRules(infoJson: JsonObject) {
        if (infoJson.has("rules")) {
            VideoParseRuler.clearRule()
            for (oneHostRule in infoJson.getAsJsonArray("rules")) {
                val obj = oneHostRule as JsonObject
                //嗅探过滤规则
                if (obj.has("host")) {
                    val host = obj.get("host").asString
                    if (obj.has("rule")) {
                        val ruleJsonArr = obj.getAsJsonArray("rule")
                        val rule = ArrayList<String>()
                        for (one in ruleJsonArr) {
                            val oneRule = one.asString
                            rule.add(oneRule)
                        }
                        if (rule.size > 0) {
                            VideoParseRuler.addHostRule(host, rule)
                        }
                    }
                    if (obj.has("filter")) {
                        val filterJsonArr = obj.getAsJsonArray("filter")
                        val filter = ArrayList<String>()
                        for (one in filterJsonArr) {
                            val oneFilter = one.asString
                            filter.add(oneFilter)
                        }
                        if (filter.size > 0) {
                            VideoParseRuler.addHostFilter(host, filter)
                        }
                    }
                }
                //广告过滤规则
                if (obj.has("hosts") && obj.has("regex")) {
                    val rule = ArrayList<String>()
                    val ads = ArrayList<String>()
                    val regexArray = obj.getAsJsonArray("regex")
                    for (one in regexArray) {
                        val regex = one.asString
                        if (M3u8.isAd(regex)) ads.add(regex) else rule.add(regex)
                    }
                    val array = obj.getAsJsonArray("hosts")
                    for (one in array) {
                        val host = one.asString
                        VideoParseRuler.addHostRule(host, rule)
                        VideoParseRuler.addHostRegex(host, ads)
                    }
                }
                //嗅探脚本规则 如 click
                if (obj.has("hosts") && obj.has("script")) {
                    val scripts = ArrayList<String>()
                    val scriptArray = obj.getAsJsonArray("script")
                    for (one in scriptArray) {
                        val script = one.asString
                        scripts.add(script)
                    }
                    val array = obj.getAsJsonArray("hosts")
                    for (one in array) {
                        val host = one.asString
                        VideoParseRuler.addHostScript(host, scripts)
                    }
                }
                //排除不嗅探的 URL 条件(fongmi 规则的 exclude):命中即否决,优先于内置嗅探正则
                //字段类型写错时忽略该条,不能让整份配置解析失败(同 doh 的兜底态度)
                if (obj.has("hosts") && obj.has("exclude")
                        && obj.get("hosts").isJsonArray && obj.get("exclude").isJsonArray) {
                    val excludes = ArrayList<String>()
                    for (one in obj.getAsJsonArray("exclude")) {
                        excludes.add(one.asString)
                    }
                    if (!excludes.isEmpty()) {
                        for (one in obj.getAsJsonArray("hosts")) {
                            VideoParseRuler.addHostExclude(one.asString, excludes)
                        }
                    }
                }
            }
        }
    }

    /** DNS over HTTPS(doh):接口把它写成非数组或格式异常时视为未提供,退回内置列表 */
    @JvmStatic
    fun applyDoh(infoJson: JsonObject) {
        var dohJson = ""
        if (infoJson.has("doh")) {
            // 接口可能把 doh 写成非数组(或格式异常):此时视为未提供,退回内置列表,不让整个配置加载挂掉
            try {
                dohJson = infoJson.getAsJsonArray("doh").toString()
            } catch (e: Exception) {
                LOG.e("ApiConfig", e)
            }
        }
        OkGoHelper.applyDohConfig(dohJson)
    }

    /** 追加的广告拦截(ads) */
    @JvmStatic
    fun applyAds(infoJson: JsonObject) {
        if (infoJson.has("ads")) {
            for (host in infoJson.getAsJsonArray("ads")) {
                if (!AdBlocker.hasHost(host.asString)) {
                    AdBlocker.addAdHost(host.asString)
                }
            }
        }
    }

    /** 解析地址(parses):只做构造,超级解析与默认解析的选择由调用方负责 */
    @JvmStatic
    fun parseParseBeans(infoJson: JsonObject): List<ParseBean> {
        val parseBeans: MutableList<ParseBean> = ArrayList()
        if (infoJson.has("parses")) {
            val parses = infoJson.get("parses").asJsonArray
            for (opt in parses) {
                val obj = opt as JsonObject
                val pb = ParseBean()
                pb.name = obj.get("name").asString.trim { it <= ' ' }
                pb.url = obj.get("url").asString.trim { it <= ' ' }
                val ext = if (obj.has("ext")) obj.get("ext").asJsonObject.toString() else ""
                pb.ext = ext
                pb.type = DefaultConfig.safeJsonInt(obj, "type", 0)
                parseBeans.add(pb)
            }
        }
        return parseBeans
    }

}
