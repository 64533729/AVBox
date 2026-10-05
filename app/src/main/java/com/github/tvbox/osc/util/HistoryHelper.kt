package com.github.tvbox.osc.util

import java.util.ArrayList

object HistoryHelper {
    private val hisNumArray = arrayOf(30, 50, 100)
    private const val API_LINE_SPLIT = "\t"

    @JvmStatic
    fun getHisNum(index: Int): Int {
        var value: Int
        if (index >= 0 && index < hisNumArray.size) {
            value = hisNumArray[index]
        } else {
            value = hisNumArray[0]
        }
        return value
    }

    /**
     * 无痕判定,所有判断统一走这里。写侧 = 搜索历史 / 观看历史 / 续播点 / 百分比 / 集数快照;
     * 读侧 = 续播点 / 详情页续播记录 / 预载起点 / 快照展示;接管侧 = 同片接管;UI = 历史页与搜索页空态。
     */
    @JvmStatic
    fun isIncognito(): Boolean {
        return KV.get(HawkConfig.INCOGNITO, false)
    }

    @JvmStatic
    fun setSearchHistory(title: String) {
        // 无痕模式:不记录新的搜索历史(已有历史照常展示,清空/删除仍可用)
        if (isIncognito()) return
        // 读取历史记录
        val history = KV.get(HawkConfig.SEARCH_HISTORY, ArrayList<String>())
        history.remove(title)
        history.add(0, title)
        // 保证最多只保留 20 条，超过的就删除最后一条
        if (history.size > 20) {
            history.removeAt(history.size - 1)
        }
        KV.put(HawkConfig.SEARCH_HISTORY, history)
    }

    @JvmStatic
    fun clearSearchHistory() {
        KV.put(HawkConfig.SEARCH_HISTORY, ArrayList<String>())
    }

    /** 删除单条搜索历史(2026-09-11:搜索页长按历史 chip) */
    @JvmStatic
    fun removeSearchHistory(title: String) {
        val history = KV.get(HawkConfig.SEARCH_HISTORY, ArrayList<String>())
        history.remove(title)
        KV.put(HawkConfig.SEARCH_HISTORY, history)
    }

    @JvmStatic
    fun setLiveApiHistory(value: String) {
        val history = KV.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>())
        if (!history.contains(value)) {
            history.add(0, value)
        }
        if (history.size > 30) {
            history.removeAt(30)
        }
        KV.put(HawkConfig.LIVE_API_HISTORY, history)
    }

    @JvmStatic
    fun setApiHistory(value: String) {
        val history = KV.get(HawkConfig.API_HISTORY, ArrayList<String>())
        if (!history.contains(value)) {
            history.add(0, value)
        }
        if (history.size > 30) {
            history.removeAt(30)
        }
        KV.put(HawkConfig.API_HISTORY, history)
    }

    @JvmStatic
    fun buildApiLine(name: String?, url: String?): String {
        var lineName = if (name == null) "" else name.trim { it <= ' ' }
        val lineUrl = if (url == null) "" else url.trim { it <= ' ' }
        if (lineName.isEmpty()) {
            lineName = lineUrl
        }
        return lineName + API_LINE_SPLIT + lineUrl
    }

    @JvmStatic
    fun getApiLineName(value: String?): String {
        if (value == null) return ""
        val splitIndex = value.indexOf(API_LINE_SPLIT)
        val name = if (splitIndex >= 0) value.substring(0, splitIndex) else value
        return name.trim { it <= ' ' }
    }

    @JvmStatic
    fun getApiLineUrl(value: String?): String {
        if (value == null) return ""
        val splitIndex = value.indexOf(API_LINE_SPLIT)
        val url = if (splitIndex >= 0) value.substring(splitIndex + API_LINE_SPLIT.length) else value
        return url.trim { it <= ' ' }
    }

    @JvmStatic
    fun isApiLineUrl(url: String?): Boolean {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return false
        val trimUrl = url.trim { it <= ' ' }
        val apiLines = KV.get(HawkConfig.API_LINE_LIST, ArrayList<String>())
        for (apiLine in apiLines) {
            if (trimUrl == getApiLineUrl(apiLine)) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun isApiLineSource(url: String?): Boolean {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return false
        val source = KV.get(HawkConfig.API_LINE_SOURCE, "")
        return url.trim { it <= ' ' } == source
    }

    @JvmStatic
    fun isApiLineHistory(url: String?): Boolean {
        return isApiLineSource(url) || isApiLineUrl(url)
    }

    /** 清空点播仓列表;顺带发信号 —— "当前源是否来自仓"这个结论变了,界面要重读 */
    @JvmStatic
    fun clearApiLineList() {
        KV.put(HawkConfig.API_LINE_LIST, ArrayList<String>())
        KV.put(HawkConfig.API_LINE_SOURCE, "")
        ApiLineSignal.notifyChanged()
    }

    /** 点播仓列表(每项 "名字\t链接"),空列表表示当前不是多仓点播源 */
    @JvmStatic
    fun getApiLines(): ArrayList<String> {
        return KV.get(HawkConfig.API_LINE_LIST, ArrayList<String>())
    }

    // ---- 直播侧多仓(2026-09-21):与点播四个判定一一对应,只是换 LIVE_API_LINE_LIST 这一对键 ----

    /** 这个地址是不是某个仓里的子源(决定直播「配置切换」列仓列表还是配置历史) */
    @JvmStatic
    fun isLiveApiLineUrl(url: String?): Boolean {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return false
        val trimUrl = url.trim { it <= ' ' }
        val apiLines = KV.get(HawkConfig.LIVE_API_LINE_LIST, ArrayList<String>())
        for (apiLine in apiLines) {
            if (trimUrl == getApiLineUrl(apiLine)) {
                return true
            }
        }
        return false
    }

    /** 这个地址本身是不是"仓地址"(用户当初填的那个仓库链接,而不是仓里的子源) */
    @JvmStatic
    fun isLiveApiLineSource(url: String?): Boolean {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return false
        return url.trim { it <= ' ' } == KV.get(HawkConfig.LIVE_API_LINE_SOURCE, "")
    }

    /** 直播源来自仓(仓地址本身或仓里某条子源)—— 换源时据此决定要不要清空仓列表 */
    @JvmStatic
    fun isLiveApiLineHistory(url: String?): Boolean {
        return isLiveApiLineSource(url) || isLiveApiLineUrl(url)
    }

    /** 直播仓列表(每项 "名字\t链接"),空列表表示当前不是多仓直播源 */
    @JvmStatic
    fun getLiveApiLines(): ArrayList<String> {
        return KV.get(HawkConfig.LIVE_API_LINE_LIST, ArrayList<String>())
    }

    @JvmStatic
    fun clearLiveApiLineList() {
        KV.put(HawkConfig.LIVE_API_LINE_LIST, ArrayList<String>())
        KV.put(HawkConfig.LIVE_API_LINE_SOURCE, "")
        ApiLineSignal.notifyChanged()
    }

    /**
     * 这个地址是不是"当前点播仓的来源地址"。
     *
     * <p>多仓加载会把 {@code API_URL} 改写成仓里第一条子源的地址,于是订阅列表里那条仓地址与
     * 当前地址不再相等 —— 凡拿订阅地址跟当前地址比对的 UI(配置管理页"使用中")都要一并认这种关系,
     * 否则切到仓之后订阅卡全部显示未使用。要求仓处于生效态,避免清场后残留误判。
     */
    @JvmStatic
    fun isApiLineSourceOf(url: String?, activeUrl: String?): Boolean {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return false
        if (!isApiLineUrl(activeUrl)) return false
        return url.trim { it <= ' ' } == KV.get(HawkConfig.API_LINE_SOURCE, "")
    }

    /** 同 {@link #isApiLineSourceOf},直播侧 */
    @JvmStatic
    fun isLiveApiLineSourceOf(url: String?, activeUrl: String?): Boolean {
        if (url == null || url.trim { it <= ' ' }.isEmpty()) return false
        if (!isLiveApiLineUrl(activeUrl)) return false
        return url.trim { it <= ' ' } == KV.get(HawkConfig.LIVE_API_LINE_SOURCE, "")
    }
}
