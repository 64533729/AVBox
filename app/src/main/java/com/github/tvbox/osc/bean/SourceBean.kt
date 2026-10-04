package com.github.tvbox.osc.bean

import java.util.ArrayList
import java.util.Collections

class SourceBean {

    var key: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    var name: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    var api: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    /** 0 xml 1 json 3 Spider */
    var type: Int = 0

    private var searchable: Int = 0

    private var quickSearch: Int = 0

    private var changeable: Int = 1

    var filterable: Int = 0

    /** 站点解析Url */
    var playerUrl: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    /** 扩展数据 */
    var ext: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    /** 自定义jar */
    var jar: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    /** 分类&排序 */
    var categories: ArrayList<String>? = null

    /** 2 exo 10 mxplayer -1 以参数设置页面的为准 */
    var playerType: Int = 0

    /** 站点播放信息获取超时，单位秒 */
    var timeout: Int = 0

    /** 需要点击播放的嗅探站点selector   ddrk.me;#id */
    var clickSelector: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    /** 展示风格 */
    var style: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    /** 站点头像/logo */
    var icon: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    /** 1=从站点切换列表隐藏 */
    private var hide: Int = 0

    /** 1=索引型源:卡片只是关键词入口,只走搜索不进详情 */
    private var indexs: Int = 0

    /** 0=本站不通过全局弹幕 API 自动搜弹幕 */
    private var danmaku: Int = 1

    /** 站点级请求头(type 0/1/4 的接口请求会带,并作为播放请求头的兜底) */
    var header: MutableMap<String, String>? = null
        get() = field ?: Collections.emptyMap<String, String>()

    private fun safeString(value: String?): String = value ?: ""

    fun isSearchable(): Boolean = searchable != 0

    fun setSearchable(value: Int) {
        searchable = value
    }

    fun isQuickSearch(): Boolean = quickSearch != 0

    fun setQuickSearch(value: Int) {
        quickSearch = value
    }

    fun isChangeable(): Boolean = changeable != 0

    fun setChangeable(value: Int) {
        changeable = value
    }

    val isIndexSource: Boolean
        get() = indexs == 1

    fun setIndexs(value: Int) {
        indexs = value
    }

    fun isHidden(): Boolean = hide == 1

    fun setHide(value: Int) {
        hide = value
    }

    fun isDanmakuEnabled(): Boolean = danmaku != 0

    fun setDanmaku(value: Int) {
        danmaku = value
    }

    fun getPlayTimeoutSeconds(): Int = if (timeout > 0) Math.max(5, Math.min(60, timeout)) else 15
}
