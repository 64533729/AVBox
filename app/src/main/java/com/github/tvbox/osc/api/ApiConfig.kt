package com.github.tvbox.osc.api

import android.app.Activity
import android.net.Uri
import android.text.TextUtils
import android.util.Base64

import com.github.catvod.crawler.Spider
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.LiveChannelGroup
import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.bean.LiveSettingGroup
import com.github.tvbox.osc.bean.LiveSettingItem
import com.github.tvbox.osc.bean.ParseBean
import com.github.tvbox.osc.bean.ProxyRule
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.util.AES
import com.github.tvbox.osc.util.AdBlocker
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.FileUtils
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HeaderGuard
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.RegexUtils
import com.github.tvbox.osc.util.VideoParseRuler
import com.github.tvbox.osc.util.live.TxtSubscribe
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

import org.json.JSONObject

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Locale

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
class ApiConfig private constructor() {
    private val sourceBeanList: LinkedHashMap<String?, SourceBean>
    // 排序/缓存判定会在后台线程读,配置加载线程写
    @Volatile
    private var mHomeSource: SourceBean? = null
    private var mDefaultParse: ParseBean? = null
    private val liveChannelGroupList: MutableList<LiveChannelGroup>
    val parseBeanList: MutableList<ParseBean>
    private var vipParseFlags: MutableList<String>? = null
    // 点播/直播两套 hosts 分开存:合并视图见 getMyHost,避免直播配置把点播的覆盖掉。
    // volatile:DNS 解析在 OkHttp 线程读,配置解析在主线程写
    @Volatile
    private var vodHosts: MutableMap<String, String>? = null
    @Volatile
    private var liveHosts: MutableMap<String, String>? = null
    @JvmField
    var loadedLiveConfigUrl: String = ""
    private var danmaku: String? = ""
    @Volatile
    private var configLogo: String? = "" // 配置级头像(接口 JSON 顶层 "logo")

    fun getConfigLogo(): String {
        return configLogo ?: ""
    }

    private val emptyHome = SourceBean()

    /** 爬虫装载:jar/js/py 加载器与 jar 下载链路 */
    private val spiderLoader = SpiderLoader()

    /** /proxy 请求路由(jar/js/py 爬虫与直连回退) */
    private val proxyEntry = ProxyEntry(this, spiderLoader)

    /** spider 预热队列(独占线程 + 单项限时) */
    private val warmQueue = WarmQueue(this, spiderLoader)

    /** 配置拉取编排(快照回落/仓分流/本地源判断) */
    private val configLoader = ConfigLoader(this)
    private val gson: Gson
    private var searchSourceBeanList: MutableList<SourceBean> = ArrayList()

    /** 直播设置组:Java 里是带初始值的字段(初始化早于构造器体),Kotlin 侧同样在 init 之前初始化 */
    val liveSettingGroupList: MutableList<LiveSettingGroup> = ArrayList()

    init {
        clearLoader()
        sourceBeanList = LinkedHashMap()
        liveChannelGroupList = ArrayList()
        parseBeanList = ArrayList()
        searchSourceBeanList = ArrayList()
        gson = Gson()
        KV.put(HawkConfig.LIVE_GROUP_LIST, JsonArray())
        loadDefaultConfig()
    }

    fun loadConfig(useCache: Boolean, callback: LoadConfigCallback, activity: Activity?) {
        configLoader.loadConfig(useCache, callback, activity)
    }

    fun loadLiveConfig(useCache: Boolean, callback: LoadConfigCallback) {
        configLoader.loadLiveConfig(useCache, callback)
    }

    fun hasLiveConfigResult(): Boolean {
        return !liveChannelGroupList.isEmpty()
    }

    fun shouldReloadLiveConfig(): Boolean {
        val apiUrl = getEffectiveLiveUrl()
        return liveChannelGroupList.isEmpty() || apiUrl != loadedLiveConfigUrl
    }

    /**
     * 作废已加载的直播内存态(2026-09-12 点播/直播拆分):点播源或直播源变更后调用,
     * 让直播页下次进入必然重载。只清内存与"已加载来源"标记,不动 KV 与磁盘缓存(离线仍可用缓存兜底)。
     */
    fun invalidateLiveConfig() {
        liveChannelGroupList.clear()
        loadedLiveConfigUrl = ""
    }

    fun loadJar(useCache: Boolean, spider: String?, callback: LoadConfigCallback) {
        spiderLoader.loadJar(useCache, spider!!, callback)
    }

    /** 直播配置数据清场,不动 KV 与仓列表 —— 供"换仓后重新拉取"先丢弃旧结果用 */
    fun clearLiveConfigResult() {
        liveChannelGroupList.clear()
        spiderLoader.setLiveSpider("")
        spiderLoader.resetCurrentLiveSpider()
        initLiveSettings()
        KV.put(HawkConfig.LIVE_GROUP_LIST, JsonArray())
    }

    private fun resetConfigData() {
        warmQueue.bumpGeneration()
        clearSpiderCache()
        proxyEntry.setCurrentPlaySourceKey("")
        configLogo = ""
        sourceBeanList.clear()
        liveChannelGroupList.clear()
        parseBeanList.clear()
        searchSourceBeanList = ArrayList()
        KV.put(HawkConfig.LIVE_GROUP_LIST, JsonArray())
        // 只清点播那份 hosts:独立直播源的映射由直播配置自己维护
        vodHosts = null
        // 跟随态下 liveHosts 就是点播 hosts 的副本,一并清掉才不会让被删源的映射继续生效
        if (isLiveFollowVod()) liveHosts = null
        OkGoHelper.refreshHosts()
    }

    /**
     * 清空全部配置(点播 + 直播)。
     * 2026-09-12 点播/直播拆分后,业务侧通常应改用 [clearVodConfig] / [clearLiveConfig] ——
     * 删空点播源不应连坐清掉用户单独配置的直播源。
     */
    fun clearConfig() {
        clearVodConfig()
        clearLiveConfig()
    }

    /**
     * 清空点播配置(2026-09-12,配置管理页删光点播源时调用):
     * 内存源数据与 KV 点播地址一并清空,回到「尚未配置订阅接口」的初始状态;
     * 调用方随后执行 AppBootstrap.retry() 即可让各页按未配置刷新(getHomeSourceBean 有 emptyHome 兜底)。
     * **独立直播源不受影响**;直播若处于跟随态则 LIVE_API_URL 一并置空(否则会变成指向旧点播源的陈旧快照)。
     */
    fun clearVodConfig() {
        val followLive = isLiveFollowVod() // 必须在清空 API_URL 之前判定
        resetConfigData()
        mHomeSource = null
        KV.put(HawkConfig.API_URL, "")
        KV.put(HawkConfig.HOME_API, "")
        HistoryHelper.clearApiLineList()
        if (followLive) {
            KV.put(HawkConfig.LIVE_API_URL, "")
            // 跟随态下直播源就是点播源(2026-09-21):点播仓列表已清,直播仓列表同理作废
            HistoryHelper.clearLiveApiLineList()
        }
        invalidateLiveConfig()
    }

    /** 清空独立直播源并回到「跟随点播源」(2026-09-12):点播配置完全不受影响 */
    fun clearLiveConfig() {
        KV.put(HawkConfig.LIVE_API_URL, "")
        // 仓列表跟着被清掉的直播源一起作废(2026-09-21):留着会在「配置切换」里列出已失效的子源
        HistoryHelper.clearLiveApiLineList()
        clearLiveHosts()
        invalidateLiveConfig()
    }

    /** 直播源被换掉/切回跟随时清直播侧 hosts:否则旧源的 DNS 映射会一直生效到下次加载成功 */
    fun clearLiveHosts() {
        liveHosts = null
        OkGoHelper.refreshHosts()
    }

    /**
     * 作废内存里的点播配置(**不**动 KV 地址,2026-09-13)。
     *
     * 存在的理由:[loadConfig] 失败时走的是 `callback.error(...)`,**根本不会调用 [parseJson]**,
     * 而清场动作 `resetConfigData()` 只在 parseJson 开头执行 —— 于是单例里的
     * `sourceBeanList` / `mHomeSource` / `parseBeanList` 全部保留着**上一个源**的数据,
     * 首页套用旧源继续正常显示与播放,可 KV 里的 `API_URL` 已经指向新源:
     * 表现就是"运行中用旧源、重启后才发现新源不可用"的状态不一致。
     *
     * 因此在**切换点播源之前**调用本方法:新源拉取成功会由 parseJson 重新填充;
     * 拉取失败时首页自然落到空态/未配置引导态,与「配置加载失败」弹窗一致,
     * 不会再拿旧源冒充新源(同类修复先例:2026-09-11「删空订阅列表仍用着被删的源」)。
     */
    fun invalidateVodConfig() {
        resetConfigData()
        mHomeSource = null
        invalidateLiveConfig()
    }

    private fun clearApiLinesIfUnmatched(apiUrl: String) {
        val apiLines: ArrayList<String> = KV.get(HawkConfig.API_LINE_LIST, ArrayList<String>())
        if (apiLines.isEmpty()) {
            return
        }
        for (apiLine in apiLines) {
            if (apiUrl == HistoryHelper.getApiLineUrl(apiLine)) {
                return
            }
        }
        HistoryHelper.clearApiLineList()
    }

    fun parseJson(apiUrl: String, jsonStr: String) {
        resetConfigData()
        // 规则表等新配置到手再清:换源失败时旧规则要留给仍在播的旧源,清早了会让广告回归/click 失效
        VideoParseRuler.clearRule()
        LOG.i("echo-apiurl:" + apiUrl)
        val infoJson = gson.fromJson(jsonStr, JsonObject::class.java)
        // 配置级头像(2026-09-10):接口 JSON 顶层 "logo",胶囊头像的兜底来源(站点级 icon 优先)
        configLogo = DefaultConfig.safeJsonString(infoJson, "logo", "")
        // spider
        spiderLoader.spider = DefaultConfig.safeJsonString(infoJson, "spider", "")
        spiderLoader.setJarCache(DefaultConfig.safeJsonString(infoJson, "jarCache", "true"))
        danmaku = DefaultConfig.safeJsonString(infoJson, "danmaku", "")
        // 远端站点源
        val sites = ConfigParser.parseSites(infoJson)
        for (sb in sites) {
            sourceBeanList[sb.key] = sb
        }
        val firstSite = firstVisibleSite(sites)
        if (sourceBeanList.size > 0) {
            val home = KV.get(HawkConfig.HOME_API, "")
            val sh = getSource(home)
            if (sh == null) {
                assert(firstSite != null)
                setSourceBean(firstSite!!)
            } else {
                setSourceBean(sh)
            }
        }
        // 需要使用vip解析的flag
        vipParseFlags = DefaultConfig.safeJsonStringList(infoJson, "flags")
        // 解析地址
        parseBeanList.clear()
        val parsedParses = ConfigApplier.parseParseBeans(infoJson)
        if (!parsedParses.isEmpty()) {
            parseBeanList.addAll(parsedParses)
            addSuperParse()
        }
        // 获取默认解析
        if (parseBeanList.size > 0) {
            val defaultParse = KV.get(HawkConfig.DEFAULT_PARSE, "")
            if (!TextUtils.isEmpty(defaultParse)) {
                for (pb in parseBeanList) {
                    if (pb.name == defaultParse) {
                        setDefaultParse(pb)
                    }
                }
            }
            if (mDefaultParse == null) {
                setDefaultParse(parseBeanList[0])
            }
        }

        // 直播源
        val live_api_url = KV.get(HawkConfig.LIVE_API_URL, "")
        if (live_api_url.isEmpty() || apiUrl == live_api_url) {
            LOG.i("echo-load-config_live")
            initLiveSettings()
            if (infoJson.has("lives")) {
                val lives_groups = infoJson.get("lives").asJsonArray
                var live_group_index = getLiveGroupIndex()
                if (live_group_index > lives_groups.size() - 1) live_group_index = 0
                KV.put(HawkConfig.LIVE_GROUP_LIST, lives_groups)
                //加载多源配置
                try {
                    liveSettingGroupList[5].liveSettingItems = ConfigParser.parseLiveSettingItems(lives_groups)
                } catch (e: Exception) {
                    // 捕获任何可能发生的异常
                    LOG.e("ApiConfig", e)
                }

                val livesOBJ = lives_groups.get(live_group_index).asJsonObject
                loadLiveApi(livesOBJ)
            }
        }

        // 写完立即刷新:下方 rules/ads 段若抛异常,快照不会停在上一条配置的映射上
        vodHosts = if (infoJson.has("hosts")) ConfigParser.parseHosts(infoJson.getAsJsonArray("hosts")) else null
        OkGoHelper.refreshHosts()

        loadProxyRules(infoJson)

        ConfigApplier.applyHostRules(infoJson)

        ConfigApplier.applyDoh(infoJson)
        LOG.i("echo-api-config-----------load")
        ConfigApplier.applyAds(infoJson)
    }

    private fun loadDefaultConfig() {
        val defaultJson = gson.fromJson(FileUtils.getAsOpen("default_config.json"), JsonObject::class.java)
        if (defaultJson == null) {
            LOG.e("ApiConfig: default_config.json unavailable")
            return
        }
        // 广告地址
        if (AdBlocker.isEmpty()) {
            //默认广告拦截
            for (host in defaultJson.getAsJsonArray("ads")) {
                AdBlocker.addAdHost(host.asString)
            }
        }
        LOG.i("echo-default-config-----------load")
    }

    private fun parseLiveConfigContent(apiUrl: String, f: File) {
        // BugReview #27:close 放 finally/try-with-resources,读失败时防 FD 泄漏
        val content = BufferedReader(InputStreamReader(FileInputStream(f), "UTF-8")).use { bReader ->
            val sb = StringBuilder()
            var s: String? = bReader.readLine()
            while (s != null) {
                sb.append(s + "\n")
                s = bReader.readLine()
            }
            sb.toString()
        }
        parseLiveConfigContent(apiUrl, content)
    }

    fun parseLiveConfigContent(apiUrl: String, content: String) {
        val jsonContent = ConfigParser.trimJsonObject(content)
        if (!TextUtils.isEmpty(jsonContent)) {
            try {
                val infoJson = gson.fromJson(jsonContent, JsonObject::class.java)
                if (infoJson != null && infoJson.has("lives")) {
                    parseLiveJson(apiUrl, jsonContent)
                    return
                }
            } catch (ignored: Throwable) {
                LOG.d("ApiConfig", "live config json parse failed, fallback to text")
            }
        }
        if (ConfigParser.isLiveJsonContent(content)) {
            parseLiveJson(apiUrl, jsonContent)
        } else {
            parseLiveText(apiUrl, content)
        }
    }

    private fun parseLiveText(apiUrl: String, content: String) {
        liveChannelGroupList.clear()
        spiderLoader.setLiveSpider("")
        spiderLoader.resetCurrentLiveSpider()
        initLiveSettings()
        KV.put(HawkConfig.LIVE_GROUP_LIST, JsonArray())
        KV.put(HawkConfig.EPG_URL, ConfigParser.extractLiveTextEpg(content))
        KV.put(HawkConfig.LIVE_WEB_HEADER, null)
        // 文本直播配置没有 hosts 字段:清掉上一份直播源留下的映射,否则会继续生效
        liveHosts = null
        OkGoHelper.refreshHosts()
        val livesArray = TxtSubscribe.parseToJsonArray(content)
        loadLives(livesArray)
        LOG.i("echo-live-text-config-----------load:" + apiUrl)
    }

    private fun parseLiveJson(apiUrl: String, jsonStr: String) {
        liveChannelGroupList.clear()
        val infoJson = gson.fromJson(jsonStr, JsonObject::class.java)
        // spider
        spiderLoader.setLiveSpider(DefaultConfig.safeJsonString(infoJson, "spider", ""))
        // 直播源
        initLiveSettings()
        if (infoJson.has("lives")) {
            val lives_groups = infoJson.get("lives").asJsonArray

            var live_group_index = getLiveGroupIndex()
            if (live_group_index > lives_groups.size() - 1) live_group_index = 0
            KV.put(HawkConfig.LIVE_GROUP_LIST, lives_groups)
            //加载多源配置
            try {
                liveSettingGroupList[5].liveSettingItems = ConfigParser.parseLiveSettingItems(lives_groups)
            } catch (e: Exception) {
                // 捕获任何可能发生的异常
                LOG.e("ApiConfig", e)
            }

            val livesOBJ = lives_groups.get(live_group_index).asJsonObject
            loadLiveApi(livesOBJ)
        }

        liveHosts = if (infoJson.has("hosts")) ConfigParser.parseHosts(infoJson.getAsJsonArray("hosts")) else null
        // DNS 只认 OkGoHelper.myHosts 快照,写完必须刷新,否则直播 hosts 实际不生效
        OkGoHelper.refreshHosts()
        LOG.i("echo-api-live-config-----------load")
    }

    private fun initLiveSettings() {
        val groupNames = ArrayList(
            listOf(
                str(R.string.live_group_line), str(R.string.live_group_scale), str(R.string.live_group_decoder),
                str(R.string.live_group_timeout), str(R.string.settings_preference_title),
                str(R.string.live_group_multi_source), str(R.string.live_group_config_switch)
            )
        )
        val itemsArrayList = ArrayList<ArrayList<String>>()
        val sourceItems = ArrayList<String>()
        val scaleItems = ArrayList(
            listOf(
                str(R.string.common_default), "16:9", "4:3",
                str(R.string.player_scale_fill), str(R.string.player_scale_origin), str(R.string.player_scale_crop)
            )
        )
        val playerDecoderItems = ArrayList(
            listOf(str(R.string.player_decode_hard), str(R.string.player_decode_soft))
        )
        val timeoutItems = ArrayList(listOf("5s", "10s", "15s", "20s", "25s", "30s"))
        val personalSettingItems = ArrayList(
            listOf(
                str(R.string.live_setting_show_time), str(R.string.live_setting_show_speed),
                str(R.string.live_setting_reverse), str(R.string.live_setting_cross_group)
            )
        )
        val yumItems = ArrayList<String>()
        val liveApiHistoryItems = ArrayList<String>()

        itemsArrayList.add(sourceItems)
        itemsArrayList.add(scaleItems)
        itemsArrayList.add(playerDecoderItems)
        itemsArrayList.add(timeoutItems)
        itemsArrayList.add(personalSettingItems)
        itemsArrayList.add(yumItems)
        itemsArrayList.add(liveApiHistoryItems)

        liveSettingGroupList.clear()
        for (i in groupNames.indices) {
            val liveSettingGroup = LiveSettingGroup()
            val liveSettingItemList = ArrayList<LiveSettingItem>()
            liveSettingGroup.groupIndex = i
            liveSettingGroup.groupName = groupNames[i]
            for (j in itemsArrayList[i].indices) {
                val liveSettingItem = LiveSettingItem()
                liveSettingItem.itemIndex = j
                liveSettingItem.itemName = itemsArrayList[i][j]
                liveSettingItemList.add(liveSettingItem)
            }
            liveSettingGroup.liveSettingItems = liveSettingItemList
            liveSettingGroupList.add(liveSettingGroup)
        }
        refreshLiveApiHistoryItems()
    }

    /**
     * 刷新直播设置「配置切换」组(第 6 组):第 0 项固定为合成的「跟随点播源」(无条件占位,避免下标漂移),
     * 其后为候选项 —— 第 i 项的 itemIndex = i + 1。
     *
     * <p>2026-09-21 多仓:当前直播源来自仓列表时,第 1 项起改列**仓里的子源**而不是配置历史。
     */
    fun refreshLiveApiHistoryItems() {
        if (liveSettingGroupList.size < 7) return
        val liveSettingItemList = ArrayList<LiveSettingItem>()
        val followItem = LiveSettingItem()
        followItem.itemIndex = 0
        followItem.itemName = str(R.string.live_follow_vod_source)
        liveSettingItemList.add(followItem)
        val entries = getLiveConfigEntries()
        for (i in entries.indices) {
            val liveSettingItem = LiveSettingItem()
            liveSettingItem.itemIndex = i + 1
            liveSettingItem.itemName = HistoryHelper.getApiLineName(entries[i])
            liveSettingItemList.add(liveSettingItem)
        }
        liveSettingGroupList[6].liveSettingItems = liveSettingItemList
    }

    /** 「配置切换」当前列的是仓列表还是配置历史 —— UI 点击/删除时据此取值 */
    fun isLiveApiLineMode(): Boolean {
        return HistoryHelper.isLiveApiLineUrl(KV.get(HawkConfig.LIVE_API_URL, ""))
    }

    /** 「配置切换」第 1 项起的条目:仓模式给仓列表,否则给配置历史(与上面刷新用的是同一份) */
    fun getLiveConfigEntries(): ArrayList<String> {
        return if (HistoryHelper.isLiveApiLineUrl(KV.get(HawkConfig.LIVE_API_URL, ""))) {
            HistoryHelper.getLiveApiLines()
        } else {
            KV.get(HawkConfig.LIVE_API_HISTORY, ArrayList())
        }
    }

    /**
     * 同 [getLiveConfigEntries],但剥成纯地址列表。
     *
     * <p>条目是 `"名字\t链接"` 的行,而选中判定要比对地址 —— 直接拿整行去 indexOf 永远匹配不上
     * (表现为「配置切换」当前项不高亮)。
     */
    fun getLiveConfigUrls(): ArrayList<String> {
        val urls = ArrayList<String>()
        for (entry in getLiveConfigEntries()) {
            val url = HistoryHelper.getApiLineUrl(entry)
            if (!TextUtils.isEmpty(url)) urls.add(url)
        }
        return urls
    }

    /** 「配置切换」组第 `position` 项对应的直播源地址(第 0 项是「跟随点播源」,返回空串) */
    fun getLiveApiHistoryUrl(position: Int): String {
        val urls = getLiveConfigUrls()
        val index = position - 1
        if (index < 0 || index >= urls.size) return ""
        return urls[index]
    }

    fun loadLives(livesArray: JsonArray) {
        liveChannelGroupList.clear()
        var groupIndex = 0
        var channelIndex: Int
        var channelNum = 0
        for (groupElement in livesArray) {
            val liveChannelGroup = LiveChannelGroup()
            liveChannelGroup.liveChannels = ArrayList<LiveChannelItem>()
            liveChannelGroup.groupIndex = groupIndex++
            val groupJson = groupElement as JsonObject
            val groupName = groupJson.get("group").asString.trim { it <= ' ' }
            val splitGroupName = RegexUtils.getPattern("_").split(groupName, 2)
            liveChannelGroup.groupName = splitGroupName[0]
            if (splitGroupName.size > 1) {
                liveChannelGroup.groupPassword = splitGroupName[1]
            } else {
                liveChannelGroup.groupPassword = ""
            }
            channelIndex = 0
            for (channelElement in groupJson.get("channels").asJsonArray) {
                val obj = channelElement as JsonObject
                val urls = DefaultConfig.safeJsonStringList(obj, "urls")
                // 没有地址的频道点了必崩(频道地址表为空),与点不开的站点一样整条跳过
                if (urls.isEmpty()) {
                    LOG.i("echo-skip live channel without url: " + obj)
                    continue
                }
                val liveChannelItem = LiveChannelItem()
                liveChannelItem.channelLogo = DefaultConfig.safeJsonString(obj, "logo", "")
                liveChannelItem.channelEpg = DefaultConfig.safeJsonString(obj, "epg", "")
                liveChannelItem.channelUa = DefaultConfig.safeJsonString(obj, "ua", "")
                liveChannelItem.channelClick = DefaultConfig.safeJsonString(obj, "click", "")
                liveChannelItem.channelFormat = DefaultConfig.safeJsonString(obj, "format", "")
                liveChannelItem.channelOrigin = DefaultConfig.safeJsonString(obj, "origin", "")
                liveChannelItem.channelReferer = DefaultConfig.safeJsonString(obj, "referer", "")
                liveChannelItem.channelTvgId = DefaultConfig.safeJsonString(obj, "tvg-id", "")
                liveChannelItem.channelTvgName = DefaultConfig.safeJsonString(obj, "tvg-name", "")
                if (obj.has("parse")) {
                    try {
                        liveChannelItem.setChannelParse(obj.get("parse").asInt)
                    } catch (ignored: Throwable) {
                        LOG.d("ApiConfig", "channel parse flag not an int, use default")
                    }
                }
                val catchupObj = ConfigParser.parseLiveCatchup(obj)
                if (catchupObj != null) liveChannelItem.channelCatchup = catchupObj
                if (obj.has("header") && obj.get("header").isJsonObject) {
                    val headerObj = obj.getAsJsonObject("header")
                    val channelHeader = HashMap<String, String>()
                    for (entry in headerObj.entrySet()) {
                        if (entry.value == null || !entry.value.isJsonPrimitive) continue
                        val value = entry.value.asString
                        if (!HeaderGuard.isSendable(entry.key, value)) {
                            LOG.i("echo-channel-header-skip:" + entry.key)
                            continue
                        }
                        channelHeader[entry.key] = value
                    }
                    liveChannelItem.channelHeader = channelHeader
                }
                val sourceNames = ArrayList<String>()
                val sourceUrls = ArrayList<String>()
                var sourceIndex = 1
                for (url in urls) {
                    val splitText = RegexUtils.getPattern("\\$").split(url, 2)
                    sourceUrls.add(splitText[0])
                    if (splitText.size > 1) {
                        sourceNames.add(splitText[1])
                    } else {
                        sourceNames.add(str(R.string.live_source_index_name, sourceIndex))
                    }
                    sourceIndex++
                }
                val channelName = ConfigParser.parseLiveChannelName(obj, sourceUrls)
                if (channelName.isEmpty()) {
                    LOG.i("echo-skip live channel without name/url: " + obj)
                    continue
                }
                liveChannelItem.channelName = channelName
                liveChannelItem.channelSourceNames = sourceNames
                liveChannelItem.channelUrls = sourceUrls
                if (mergeLiveChannel(liveChannelGroup.liveChannels!!, liveChannelItem)) {
                    liveChannelItem.channelIndex = channelIndex++
                    liveChannelItem.channelNum = ++channelNum
                }
            }
            liveChannelGroupList.add(liveChannelGroup)
        }
    }

    private fun mergeLiveChannel(channelItems: ArrayList<LiveChannelItem>, newItem: LiveChannelItem): Boolean {
        val oldItem = findLiveChannel(channelItems, newItem.channelName)
        if (oldItem == null) {
            channelItems.add(newItem)
            return true
        }
        mergeLiveChannelUrls(oldItem, newItem)
        return false
    }

    private fun findLiveChannel(channelItems: ArrayList<LiveChannelItem>, channelName: String?): LiveChannelItem? {
        for (item in channelItems) {
            if (channelName != null && channelName == item.channelName) return item
        }
        return null
    }

    private fun mergeLiveChannelUrls(oldItem: LiveChannelItem, newItem: LiveChannelItem) {
        var oldUrls = oldItem.channelUrls
        var oldSourceNames = oldItem.channelSourceNames
        if (oldUrls == null) {
            oldUrls = ArrayList()
            oldItem.channelUrls = oldUrls
        }
        if (oldSourceNames == null) {
            oldSourceNames = ArrayList()
            oldItem.channelSourceNames = oldSourceNames
        }
        while (oldSourceNames.size < oldUrls.size) {
            oldSourceNames.add(str(R.string.live_source_index_name, oldSourceNames.size + 1))
        }
        val newUrls = newItem.channelUrls
        val newSourceNames = newItem.channelSourceNames
        if (newUrls == null) return
        for (i in newUrls.indices) {
            val url = newUrls[i]
            if (oldUrls.contains(url)) continue
            oldUrls.add(url)
            if (newSourceNames != null && i < newSourceNames.size) {
                oldSourceNames.add(newSourceNames[i])
            } else {
                oldSourceNames.add(str(R.string.live_source_index_name, oldSourceNames.size + 1))
            }
        }
        oldItem.channelUrls = oldUrls
        oldItem.channelSourceNames = oldSourceNames
    }

    fun loadLiveApi(livesOBJ: JsonObject) {
        try {
            LOG.i("echo-loadLiveApi")
            liveChannelGroupList.clear()
            spiderLoader.resetCurrentLiveSpider()
            val lives = livesOBJ.toString()
            val index = lives.indexOf("proxy://")
            val url: String
            if (index != -1) {
                val endIndex = lives.lastIndexOf("\"")
                var fixUrl = lives.substring(index, endIndex)
                fixUrl = DefaultConfig.checkReplaceProxy(fixUrl)
                val extUrl = Uri.parse(fixUrl).getQueryParameter("ext")
                if (extUrl != null && !extUrl.isEmpty()) {
                    var extUrlFix: String
                    if (extUrl.startsWith("http") || extUrl.startsWith("clan://")) {
                        extUrlFix = extUrl
                    } else {
                        extUrlFix = String(Base64.decode(extUrl, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                    }
                    extUrlFix = Base64.encodeToString(extUrlFix.toByteArray(Charsets.UTF_8), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP)
                    fixUrl = fixUrl.replace(extUrl, extUrlFix)
                }
                url = fixUrl
            } else {
                val api = if (livesOBJ.has("api")) livesOBJ.get("api").asString.trim { it <= ' ' } else ""
                val type = if (livesOBJ.has("type")) livesOBJ.get("type").asString else (if (SpiderLoader.isLiveSpiderApi(api)) "3" else "0")
                if (type == "0" || type == "3") {
                    var fixUrl = if (livesOBJ.has("url")) livesOBJ.get("url").asString else ""
                    if (fixUrl.isEmpty()) fixUrl = api
                    LOG.i("echo-liveurl" + fixUrl)
                    if (!fixUrl.startsWith("http://127.0.0.1")) {
                        if (fixUrl.startsWith("http")) {
                            fixUrl = Base64.encodeToString(fixUrl.toByteArray(Charsets.UTF_8), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP)
                        }
                        fixUrl = "http://127.0.0.1:9978/proxy?do=live&type=txt&ext=" + fixUrl
                    }
                    if (type == "3") {
                        val jarUrl = if (livesOBJ.has("jar")) livesOBJ.get("jar").asString.trim { it <= ' ' } else ""
                        spiderLoader.loadLiveSpider(api, jarUrl, livesOBJ)
                    }
                    url = fixUrl
                } else {
                    // fongmi 的 lives 无 type 字段,TVBox 上游同样只认 0/3:未知取值保持拒载
                    LOG.i("echo-live-unsupported-type:" + type + " api:" + api)
                    resetLiveKvOnUnsupportedLine()
                    return
                }
            }
            //设置epg
            if (livesOBJ.has("epg")) {
                val epg = livesOBJ.get("epg").asString
                KV.put(HawkConfig.EPG_URL, epg)
            } else {
                KV.put(HawkConfig.EPG_URL, "")
            }
            //设置UA
            if (livesOBJ.has("timeout")) {
                val timeout = Math.max(5, Math.min(30, livesOBJ.get("timeout").asInt))
                KV.put(HawkConfig.LIVE_CONNECT_TIMEOUT, (timeout + 4) / 5 - 1)
            }
            if (livesOBJ.has("header") && livesOBJ.get("header").isJsonObject) {
                val headerObj = livesOBJ.getAsJsonObject("header")
                val liveHeader = HashMap<String, String>()
                for (entry in headerObj.entrySet()) {
                    if (entry.value == null || !entry.value.isJsonPrimitive) continue
                    val value = entry.value.asString
                    if (!HeaderGuard.isSendable(entry.key, value)) {
                        LOG.i("echo-live-header-skip:" + entry.key)
                        continue
                    }
                    liveHeader[entry.key] = value
                }
                KV.put(HawkConfig.LIVE_WEB_HEADER, liveHeader)
            } else if (livesOBJ.has("ua")) {
                val ua = DefaultConfig.safeJsonString(livesOBJ, "ua", "")
                val liveHeader = HashMap<String, String>()
                liveHeader["User-Agent"] = ua
                KV.put(HawkConfig.LIVE_WEB_HEADER, liveHeader)
            } else {
                KV.put(HawkConfig.LIVE_WEB_HEADER, null)
            }
            val liveChannelGroup = LiveChannelGroup()
            liveChannelGroup.groupName = url
            liveChannelGroupList.clear()
            liveChannelGroupList.add(liveChannelGroup)
        } catch (th: Throwable) {
            LOG.e("ApiConfig", th)
        }
    }

    /** 线路被拒载时的 KV 复位:与文本直播分支保持同一套"无直播配置"状态,避免沿用上一条线路的 EPG/UA */
    private fun resetLiveKvOnUnsupportedLine() {
        KV.put(HawkConfig.EPG_URL, "")
        KV.put(HawkConfig.LIVE_WEB_HEADER, null)
    }

    fun setLiveJar(liveJar: String) {
        spiderLoader.setLiveJar(liveJar)
    }

    fun getSpider(): String? {
        return spiderLoader.spider
    }

    fun getDanmaku(): String {
        return danmaku ?: ""
    }

    fun getCSP(sourceBean: SourceBean): Spider {
        return spiderLoader.getCSP(sourceBean)
    }

    fun warmSearchSpiders() {
        warmQueue.warmSearchSpiders(ArrayList(sourceBeanList.values), getHomeSourceBean())
    }

    fun getPyCSP(url: String): Spider {
        return spiderLoader.getPyCSP(url)
    }

    fun getJsCSP(url: String): Spider {
        return spiderLoader.getJsCSP(url)
    }

    fun getLiveCSP(url: String): Spider {
        return spiderLoader.getLiveCSP(url)
    }

    fun searchDanmuUi(name: String, episode: String, longClick: Boolean) {
        spiderLoader.searchDanmuUi(name, episode, longClick)
    }

    fun hasDanmuSearchUi(): Boolean {
        return spiderLoader.hasDanmuSearchUi()
    }

    val liveConnectTimeoutSeconds: Int
        get() = (KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5

    fun proxyLocal(param: MutableMap<String, String>): Array<Any?>? {
        return proxyEntry.proxyLocal(param)
    }

    fun setCurrentPlaySourceKey(sourceKey: String?) {
        proxyEntry.setCurrentPlaySourceKey(sourceKey)
    }

    fun jsonExt(key: String, jxs: LinkedHashMap<String, String>, url: String): JSONObject? {
        return spiderLoader.jsonExt(key, jxs, url)
    }

    fun jsonExtMix(flag: String, key: String, name: String, jxs: LinkedHashMap<String, HashMap<String, String>>, url: String): JSONObject? {
        return spiderLoader.jsonExtMix(flag, key, name, jxs, url)
    }

    interface LoadConfigCallback {
        fun success()

        fun error(msg: String?)
        fun notice(msg: String?)
    }

    interface FastParseCallback {
        fun success(parse: Boolean, url: String, header: MutableMap<String, String>?)

        fun fail(code: Int, msg: String?)
    }

    fun getSource(key: String?): SourceBean? {
        if (!sourceBeanList.containsKey(key)) {
            if ("push_agent" == key) {
                val sourceBean = SourceBean()
                sourceBean.key = "push_agent"
                sourceBean.name = str(R.string.source_push_agent)
                sourceBean.type = -1
                return sourceBean
            }
            return null
        }
        return sourceBeanList[key]
    }

    fun setSourceBean(sourceBean: SourceBean) {
        this.mHomeSource = sourceBean
        KV.put(HawkConfig.HOME_API, sourceBean.key)
    }

    fun setDefaultParse(parseBean: ParseBean) {
        if (this.mDefaultParse != null) {
            this.mDefaultParse!!.isDefault = false
        }
        this.mDefaultParse = parseBean
        KV.put(HawkConfig.DEFAULT_PARSE, parseBean.name)
        parseBean.isDefault = true
    }

    fun getDefaultParse(): ParseBean? {
        return mDefaultParse
    }

    fun getSourceBeanList(): List<SourceBean> {
        return ArrayList(sourceBeanList.values)
    }

    fun getSwitchSourceBeanList(): List<SourceBean> {
        // 标 hide 的站点不进切换列表;当前首页源例外,否则列表里没有高亮项
        val filteredList: MutableList<SourceBean> = ArrayList()
        val homeKey = getHomeSourceBean().key
        for (bean in sourceBeanList.values) {
            if (bean.isHidden() && bean.key != homeKey) continue
            filteredList.add(bean)
        }
        return filteredList
    }

    /** 首页兜底源:优先第一个未标 hide 的站点(否则首页会选中一个不在切换列表里的源);全是 hide 时退回第一条 */
    private fun firstVisibleSite(sites: List<SourceBean>): SourceBean? {
        for (bean in sites) {
            if (!bean.isHidden()) return bean
        }
        return if (sites.isEmpty()) null else sites[0]
    }

    fun getSearchSourceBeanList(): List<SourceBean> {
        if (searchSourceBeanList.isEmpty()) {
            LOG.i("echo-第一次getSearchSourceBeanList")
            searchSourceBeanList = ArrayList()
            for (bean in sourceBeanList.values) {
                if (bean.isSearchable()) {
                    searchSourceBeanList.add(bean)
                }
            }
        }
        return searchSourceBeanList
    }

    fun getVipParseFlags(): MutableList<String>? {
        return vipParseFlags
    }

    fun getHomeSourceBean(): SourceBean {
        return mHomeSource ?: emptyHome
    }

    val channelGroupList: MutableList<LiveChannelGroup>
        get() = liveChannelGroupList

    /** 点播/直播两套 hosts 的合并视图(点播优先):DNS 解析只认这一份 */
    fun getMyHost(): MutableMap<String, String> {
        val merged = HashMap<String, String>()
        if (liveHosts != null) merged.putAll(liveHosts!!)
        if (vodHosts != null) merged.putAll(vodHosts!!)
        return merged
    }

    private fun loadProxyRules(infoJson: JsonObject) {
        if (!infoJson.has("proxy")) {
            OkGoHelper.setProxyList(null)
            return
        }
        try {
            OkGoHelper.setProxyList(ProxyRule.arrayFrom(infoJson.get("proxy")))
        } catch (th: Throwable) {
            LOG.e("ApiConfig", th)
            OkGoHelper.setProxyList(null)
        }
    }

    fun clearJarLoader() {
        spiderLoader.clearJarLoader()
    }

    private fun addSuperParse() {
        val superPb = ParseBean()
        // i18n: keep —— 解析名参与 DEFAULT_PARSE 持久化与比较(见 setDefaultParse),不能翻
        superPb.name = "超级解析"
        superPb.url = "SuperParse"
        superPb.ext = ""
        superPb.type = 4
        parseBeanList.add(0, superPb)
    }

    fun clearLoader() {
        spiderLoader.clearLoader()
    }

    fun clearSpiderCache() {
        spiderLoader.clearSpiderCache()
    }

    companion object {
        // volatile:DCL 单例必须(2026-09-12 修复 Bug)。首次构造可能发生在 AppBootstrap 的 IO 协程上,
        // 而主线程 Compose 同时也在调 get() —— 构造函数很重(clearLoader + loadDefaultConfig 解析大 JSON),
        // 无 volatile 时其他线程可能读到未完全初始化的实例
        @Volatile
        private var instance: ApiConfig? = null

        @JvmStatic
        fun get(): ApiConfig {
            if (instance == null) {
                synchronized(ApiConfig::class.java) {
                    if (instance == null) {
                        instance = ApiConfig()
                    }
                }
            }
            return instance!!
        }

        @JvmStatic
        fun FindResult(json: String, configKey: String?): String? {
            var out: String? = json
            var content = json
            try {
                if (AES.isJson(content)) return content
                val pattern = RegexUtils.getPattern("[A-Za-z0-9]{8}\\*\\*")
                val matcher = pattern.matcher(content)
                if (matcher.find()) {
                    content = content.substring(content.indexOf(matcher.group()) + 10)
                    content = String(Base64.decode(content, Base64.DEFAULT), Charset.defaultCharset())
                }
                content = content.trim { it <= ' ' }
                if (content.startsWith("2423")) {
                    content = content.replace(Regex("\\s+"), "")
                    val data = content.substring(content.indexOf("2324") + 4, content.length - 26)
                    content = String(AES.toBytes(content), Charset.defaultCharset()).lowercase(Locale.getDefault())
                    val key = AES.rightPadding(content.substring(content.indexOf("\$#") + 2, content.indexOf("#\$")), "0", 16)
                    val iv = AES.rightPadding(content.substring(content.length - 13), "0", 16)
                    out = AES.CBC(data, key, iv)
                } else if (configKey != null && !AES.isJson(content)) {
                    out = AES.ECB(content, configKey)
                } else {
                    out = content
                }
            } catch (e: Exception) {
                LOG.e("ApiConfig", e)
            }
            return out
        }

        /** 本机服务基址:用 Supplier 传给解析器,保证只在 clan://localhost/ 地址上才求值 */
        @JvmStatic
        fun localFileBase(): String {
            return ControlManager.get().getAddress(true)
        }

        /**
         * 实际生效的直播配置地址(2026-09-12 点播/直播拆分):
         * 独立直播源(LIVE_API_URL)优先;未单独配置时回落到当前点播源(API_URL)。
         * 直播侧全部走这个方法取地址,避免各处重复写"空则回落"的判断。
         */
        @JvmStatic
        fun getEffectiveLiveUrl(): String {
            val liveApiUrl = KV.get(HawkConfig.LIVE_API_URL, "")
            return if (TextUtils.isEmpty(liveApiUrl)) KV.get(HawkConfig.API_URL, "") else liveApiUrl
        }

        /**
         * 直播是否跟随点播源(2026-09-12 点播/直播拆分):
         * LIVE_API_URL 为空,或与 API_URL 相同 —— 后者是旧版"切源双写"留下的存量状态,语义与"跟随"等价,
         * 因此无需数据迁移:老用户升级后行为与升级前完全一致,且点播换源时直播会继续跟随。
         */
        @JvmStatic
        fun isLiveFollowVod(): Boolean {
            val liveApiUrl = KV.get(HawkConfig.LIVE_API_URL, "")
            if (TextUtils.isEmpty(liveApiUrl)) {
                return true
            }
            // 两者都非空才比较;API_URL 为空(未配置点播)时直播源独立存在,不算跟随
            return liveApiUrl == KV.get(HawkConfig.API_URL, "")
        }

        /**
         * 资源文案;App 未就绪(极早调用/单测)返回空串,不抛异常。
         * 走 [LanguageManager.localized]:Application 的 base 只在进程启动时挂一次,切语言后
         * 直接用 app.getString 会停在旧语言。
         */
        @JvmStatic
        fun str(resId: Int, vararg args: Any?): String {
            val app = App.getInstance()
            return if (app == null) "" else LanguageManager.localized(app).getString(resId, *args)
        }

        @JvmStatic
        fun getLiveGroupIndexKey(): String {
            val liveApiUrl = KV.get(HawkConfig.LIVE_API_URL, "")
            if (liveApiUrl == null || liveApiUrl.length == 0) {
                return HawkConfig.LIVE_GROUP_INDEX
            }
            return HawkConfig.LIVE_GROUP_INDEX + "_" + liveApiUrl
        }

        @JvmStatic
        fun getLiveGroupIndex(): Int {
            return KV.get(getLiveGroupIndexKey(), 0)
        }

        @JvmStatic
        fun setLiveGroupIndex(index: Int) {
            KV.put(getLiveGroupIndexKey(), index)
        }
    }
}
