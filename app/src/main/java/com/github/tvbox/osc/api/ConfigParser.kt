package com.github.tvbox.osc.api

import androidx.media3.common.util.UriUtil

import com.github.tvbox.osc.bean.Depot
import com.github.tvbox.osc.bean.LiveSettingItem
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HeaderGuard
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.RegexUtils
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

import java.util.ArrayList
import java.util.HashMap
import java.util.function.Supplier

/**
 * 配置解析里的纯函数:地址改写、文本/JSON 形状判断、站点与线路列表构造。
 * 需要写实例状态或 KV 的部分留在 ApiConfig。
 */
object ConfigParser {

    private val gson = Gson()

    /** 与 TextUtils.isEmpty 等价;不得改用 android.text.TextUtils —— 单测 returnDefaultValues 会让它静默返 false */
    private fun isEmpty(text: String?): Boolean {
        return text == null || text.length == 0
    }

    /** 截出第一个 { 到最后一个 } 之间的内容:接口正文前面常带版权说明、后面带尾巴 */
    @JvmStatic
    fun trimJsonObject(content: String?): String {
        if (content == null) {
            return ""
        }
        val trimContent = content.trim { it <= ' ' }
        val start = trimContent.indexOf("{")
        val end = trimContent.lastIndexOf("}")
        if (start >= 0 && end > start) {
            return trimContent.substring(start, end + 1)
        }
        return trimContent
    }

    /** 判断直播配置正文是 JSON 还是 m3u/txt 文本(带 BOM 的也算 JSON) */
    @JvmStatic
    fun isLiveJsonContent(content: String?): Boolean {
        if (content == null) return false
        var text = content.trim { it <= ' ' }
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim { it <= ' ' }
        return text.startsWith("{")
    }

    /** 从 m3u 头部 #EXTM3U 行取 EPG 地址,依次尝试 x-tvg-url / tvg-url / url-tvg */
    @JvmStatic
    fun extractLiveTextEpg(content: String?): String {
        if (content == null) return ""
        val text = content.replace("\r\n", "\n").replace('\r', '\n')
        val lines = RegexUtils.getPattern("\n").split(text)
        for (rawLine in lines) {
            var line = rawLine
            line = line.trim { it <= ' ' }
            if (line.startsWith("\uFEFF")) line = line.substring(1).trim { it <= ' ' }
            if (!line.startsWith("#EXTM3U")) continue
            var epg = extractQuotedAttr(line, "x-tvg-url")
            if (epg.isEmpty()) epg = extractQuotedAttr(line, "tvg-url")
            if (epg.isEmpty()) epg = extractQuotedAttr(line, "url-tvg")
            return epg
        }
        return ""
    }

    /** 取 key="value" 里的 value;没有该 key 或引号不闭合都返回空串 */
    @JvmStatic
    fun extractQuotedAttr(line: String, key: String): String {
        val token = "$key=\""
        var start = line.indexOf(token)
        if (start < 0) return ""
        start += token.length
        val end = line.indexOf("\"", start)
        if (end < 0) return ""
        return line.substring(start, end).trim { it <= ' ' }
    }

    /**
     * 解析点播站点列表。缺 key/type/api 的条目跳过(不完整的站点进内存只会在首页变成一个点不开的图标)。
     * 顺序即配置顺序,调用方按第一个站点兜底首页源。
     */
    @JvmStatic
    fun parseSites(infoJson: JsonObject): List<SourceBean> {
        val sites: MutableList<SourceBean> = ArrayList()
        for (opt in infoJson.get("sites").asJsonArray) {
            val obj = opt as JsonObject
            if (!obj.has("key") || !obj.has("type") || !obj.has("api")) {
                LOG.i("echo-skip incomplete site config: " + obj)
                continue
            }
            val sb = SourceBean()
            val siteKey = obj.get("key").asString.trim { it <= ' ' }
            sb.key = siteKey
            sb.name = if (obj.has("name")) obj.get("name").asString.trim { it <= ' ' } else siteKey
            sb.type = obj.get("type").asInt
            sb.api = obj.get("api").asString.trim { it <= ' ' }
            sb.setSearchable(DefaultConfig.safeJsonInt(obj, "searchable", 1))
            sb.setQuickSearch(DefaultConfig.safeJsonInt(obj, "quickSearch", 1))
            sb.setChangeable(DefaultConfig.safeJsonInt(obj, "changeable", 1))
            if (siteKey.startsWith("py_")) {
                sb.filterable = 1
            } else {
                sb.filterable = DefaultConfig.safeJsonInt(obj, "filterable", 1)
            }
            sb.playerUrl = DefaultConfig.safeJsonString(obj, "playUrl", "")
            sb.ext = DefaultConfig.safeJsonString(obj, "ext", "")
            sb.jar = DefaultConfig.safeJsonString(obj, "jar", "")
            sb.playerType = DefaultConfig.safeJsonInt(obj, "playerType", -1)
            sb.categories = DefaultConfig.safeJsonStringList(obj, "categories")
            sb.timeout = DefaultConfig.safeJsonInt(obj, "timeout", 0)
            sb.clickSelector = DefaultConfig.safeJsonString(obj, "click", "")
            sb.style = DefaultConfig.safeJsonString(obj, "style", "")
            sb.icon = DefaultConfig.safeJsonString(obj, "icon", "")
            sb.setHide(DefaultConfig.safeJsonInt(obj, "hide", 0))
            sb.setIndexs(DefaultConfig.safeJsonInt(obj, "indexs", 0))
            sb.setDanmaku(DefaultConfig.safeJsonInt(obj, "danmaku", 1))
            sb.header = parseHeaderObject(obj, "header")
            val extPreview = sb.ext
            LOG.i("echo-site:" + sb.name + " icon:" + sb.icon
                    + " ext:" + (if (extPreview != null && extPreview.length > 160) extPreview.substring(0, 160) else extPreview))
            sites.add(sb)
        }
        return sites
    }

    /**
     * 解析"线路合集"(多仓)配置:只有带 urls 且不带 sites 的才算合集,返回 [HistoryHelper.buildApiLine] 拼好的行。
     * 任何异常都按"不是合集"处理(返回已解析到的行),不能让它把正常配置加载带崩。
     */
    @JvmStatic
    fun parseApiCollection(jsonStr: String?): ArrayList<String> {
        val apiLines = ArrayList<String>()
        try {
            val json = trimJsonObject(jsonStr)
            if (isEmpty(json)) {
                return apiLines
            }
            val infoJson = gson.fromJson(json, JsonObject::class.java)
            if (!isDepotJson(infoJson)) {
                return apiLines
            }
            for (item in Depot.arrayFrom(infoJson.get("urls").asJsonArray)) {
                apiLines.add(HistoryHelper.buildApiLine(item.getName(), item.getUrl()))
            }
        } catch (ignored: Throwable) {
            LOG.d("ApiConfig", "api lines parse failed, keep lines so far")
        }
        return apiLines
    }

    /**
     * 这段 JSON 是不是"多仓"(仓库)配置:有非空 `urls` 数组且**没有** `sites`。
     * 点播与直播共用这条判定(2026-09-21);sites 优先,否则会把正常配置整段换成仓里第一条。
     */
    @JvmStatic
    fun isDepotJson(infoJson: JsonObject?): Boolean {
        if (infoJson == null || infoJson.has("sites")) return false
        if (!infoJson.has("urls")) return false
        val urls = infoJson.get("urls")
        return urls != null && urls.isJsonArray && urls.asJsonArray.size() > 0
    }

    /** 直播设置「配置切换」组的候选项:没写 name 的用"线路N"占位 */
    @JvmStatic
    fun parseLiveSettingItems(livesGroups: JsonArray): ArrayList<LiveSettingItem> {
        val liveSettingItemList = ArrayList<LiveSettingItem>()
        for (i in 0 until livesGroups.size()) {
            val jsonObject = livesGroups.get(i).asJsonObject
            val name = if (jsonObject.has("name")) jsonObject.get("name").asString else "线路" + (i + 1) // i18n: keep(数据默认名,进 bean 且被 ConfigParserTest 锁定)
            val liveSettingItem = LiveSettingItem()
            liveSettingItem.itemIndex = i
            liveSettingItem.itemName = name
            liveSettingItemList.add(liveSettingItem)
        }
        return liveSettingItemList
    }

    /** 配置顶层 hosts 数组,形如 "a.com=1.2.3.4";value 里再出现 = 也保留(只按第一个 = 拆) */
    @JvmStatic
    fun parseHosts(hostsArray: JsonArray): MutableMap<String, String> {
        val hosts: MutableMap<String, String> = HashMap()
        for (i in 0 until hostsArray.size()) {
            val entry = hostsArray.get(i).asString
            val parts = RegexUtils.getPattern("=").split(entry, 2)
            if (parts.size == 2) {
                hosts[parts[0]] = parts[1]
            }
        }
        return hosts
    }

    /** 站点级 header 对象(fongmi 的 sites[].header):只取标量值,缺失/非对象/值全非法时返回 null(表示没配) */
    @JvmStatic
    fun parseHeaderObject(obj: JsonObject?, key: String): MutableMap<String, String>? {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject) {
            return null
        }
        val header: MutableMap<String, String> = HashMap()
        for (entry in obj.getAsJsonObject(key).entrySet()) {
            if (entry.value == null || !entry.value.isJsonPrimitive) continue
            val name = entry.key
            val value = entry.value.asString
            // OkHttp 对 header 名/值有字符集限制,越界会在构造请求时抛异常把整个源带崩:这里直接跳过
            if (!HeaderGuard.isSendable(name, value)) {
                LOG.i("echo-site-header-skip:" + name)
                continue
            }
            header[name] = value
        }
        return if (header.isEmpty()) null else header
    }

    /** 频道显示名:优先 name,没写时用首地址兜底(与 Depot/parseApiCollection 同口径);两者都空返回空串=该条不该进列表 */
    @JvmStatic
    fun parseLiveChannelName(obj: JsonObject?, sourceUrls: ArrayList<String?>): String {
        if (obj != null && obj.has("name")) {
            val name = obj.get("name")
            if (name != null && name.isJsonPrimitive) {
                val text = name.asString.trim { it <= ' ' }
                if (text.isNotEmpty()) return text
            }
        }
        for (url in sourceUrls) {
            if (url != null && url.isNotEmpty()) return url
        }
        return ""
    }

    /**
     * 直播 catchup:对象原样用;标量转 type 并按需带上 catchup-source/replace;null 与非标量非对象算"未配"。
     * 这些脏值以前各抛一种异常,而加载链路(ApiConfig.loadLives)在主线程且没有 try。
     */
    @JvmStatic
    fun parseLiveCatchup(obj: JsonObject?): JsonObject? {
        if (obj == null || !obj.has("catchup")) return null
        val catchup = obj.get("catchup")
        if (catchup == null || catchup.isJsonNull) return null
        if (catchup.isJsonObject) return catchup.asJsonObject
        if (!catchup.isJsonPrimitive) return null
        val catchupObj = JsonObject()
        catchupObj.addProperty("type", catchup.asString)
        val source = DefaultConfig.safeJsonString(obj, "catchup-source", "")
        if (source.isNotEmpty()) catchupObj.addProperty("source", source)
        val replace = DefaultConfig.safeJsonString(obj, "catchup-replace", "")
        if (replace.isNotEmpty()) catchupObj.addProperty("replace", replace)
        return catchupObj
    }

    /**
     * clan:// 地址转真实地址。
     *
     * @param localFileBase 本机服务基址;用 Supplier 保持"只在 clan://localhost/ 时才求值"
     */
    @JvmStatic
    fun clanToAddress(lanLink: String, localFileBase: Supplier<String>): String {
        if (lanLink.startsWith("clan://localhost/")) {
            return lanLink.replace("clan://localhost/", localFileBase.get() + "file/")
        } else {
            val link = lanLink.substring(7)
            val end = link.indexOf('/')
            return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1)
        }
    }

    /** 把配置正文里的 clan://localhost/ 与 file:// 前缀统一改写成真实地址前缀 */
    @JvmStatic
    fun clanContentFix(lanLink: String, content: String): String {
        val fix = lanLink.substring(0, lanLink.indexOf("/file/") + 6)
        return content.replace("clan://localhost/", fix).replace("file://", fix)
    }

    /** 配置里用了 "./" / "../" 相对路径时,按配置地址把它们展开成绝对地址 */
    @JvmStatic
    fun fixContentPath(url: String, content: String, localFileBase: Supplier<String>): String {
        var url = url
        var content = content
        if (content.contains("\"./") || content.contains("\"../")) {
            url = url.replace("file://", "clan://localhost/")
            if (!url.startsWith("http") && !url.startsWith("clan://")) {
                url = "http://" + url
            }
            if (url.startsWith("clan://")) url = clanToAddress(url, localFileBase)
            content = content.replace("../", UriUtil.resolve(url, "../"))
            content = content.replace("./", UriUtil.resolve(url, "./"))
        }
        return content
    }

    /** 拆配置地址:支持 `地址;pk;密钥` 形式,返回真实地址与密钥 */
    @JvmStatic
    fun configUrl(apiUrl: String, localFileBase: Supplier<String>): ConfigUrl {
        var key: String? = null
        var configUrl = ""
        val pk = ";pk;"
        var apiUrl = apiUrl
        apiUrl = apiUrl.replace("file://", "clan://localhost/")
        if (apiUrl.contains(pk)) {
            val a = RegexUtils.getPattern(pk).split(apiUrl)
            key = a[1]
            if (apiUrl.startsWith("clan")) {
                configUrl = clanToAddress(a[0], localFileBase)
            } else if (apiUrl.startsWith("http")) {
                configUrl = a[0]
            } else {
                configUrl = "http://" + a[0]
            }
        } else if (apiUrl.startsWith("clan")) {
            configUrl = clanToAddress(apiUrl, localFileBase)
        } else if (!apiUrl.startsWith("http")) {
            configUrl = "http://" + apiUrl
        } else {
            configUrl = apiUrl
        }
        return ConfigUrl(configUrl, key)
    }

    class ConfigUrl(@JvmField val url: String, @JvmField val key: String?)
}
