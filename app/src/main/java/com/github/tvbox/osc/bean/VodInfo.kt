package com.github.tvbox.osc.bean

import com.github.tvbox.osc.util.RegexUtils
import java.io.Serializable
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

/**
 * @author pj567
 * @date :2020/12/22
 */
class VodInfo : Serializable {
    /** 时间 */
    @JvmField
    var last: String? = null

    /** 内容id */
    @JvmField
    var id: String? = null

    /** 父级id */
    @JvmField
    var tid: Int = 0

    /** 影片名称 <![CDATA[老爸当家]]> */
    @JvmField
    var name: String? = null

    /** 类型名称 */
    @JvmField
    var type: String? = null

    /** 视频分类zuidam3u8,zuidall */
    @JvmField
    var dt: String? = null

    /** 图片 */
    @JvmField
    var pic: String? = null

    /** 语言 */
    @JvmField
    var lang: String? = null

    /** 地区 */
    @JvmField
    var area: String? = null

    /** 年份 */
    @JvmField
    var year: Int = 0

    @JvmField
    var state: String? = null

    /** 描述集数或者影片信息<![CDATA[共40集]]> */
    @JvmField
    var note: String? = null

    /** 演员<![CDATA[张国立,蒋欣,高鑫,曹艳艳,王维维,韩丹彤,孟秀,王新]]> */
    @JvmField
    var actor: String? = null

    /** 导演<![CDATA[陈国星]]> */
    @JvmField
    var director: String? = null

    @JvmField
    var seriesFlags: ArrayList<VodSeriesFlag>? = null
    @JvmField
    var seriesMap: LinkedHashMap<String?, MutableList<VodSeries>>? = null

    /** <![CDATA[权来] */
    @JvmField
    var des: String? = null
    @JvmField
    var playFlag: String? = null
    @JvmField
    var playIndex: Int = 0
    @JvmField
    var playNote: String = ""
    @JvmField
    var sourceKey: String? = null

    /** 源显示名快照(仅内存,2026-09-14 历史页解析用,不落 Room) */
    @JvmField
    var sourceName: String = ""

    /** 该条目的站点不在当前订阅(仅内存,历史页"当前源不可用"标记用,不落 Room) */
    @JvmField
    var sourceUnavailable: Boolean = false
    @JvmField
    var playerCfg: String = ""
    @JvmField
    var reverseSort: Boolean = false

    fun setVideo(video: Movie.Video) {
        last = video.last
        id = video.id
        tid = video.tid
        name = video.name
        type = video.type
        pic = video.pic
        lang = video.lang
        area = video.area
        year = video.year
        state = video.state
        note = video.note
        actor = video.actor
        director = video.director
        des = video.des
        val infoList = video.urlBean?.infoList
        if (infoList != null && infoList.isNotEmpty()) {
            val tempSeriesMap = LinkedHashMap<String?, MutableList<VodSeries>>()
            val flags = ArrayList<VodSeriesFlag>()
            seriesFlags = flags
            for (urlInfo in infoList) {
                val beanList = urlInfo.beanList
                if (beanList != null && beanList.isNotEmpty()) {
                    val seriesList = ArrayList<VodSeries>()
                    for (infoBean in beanList) {
                        seriesList.add(VodSeries(infoBean.name, infoBean.url))
                    }
                    tempSeriesMap[urlInfo.flag] = seriesList
                    flags.add(VodSeriesFlag(urlInfo.flag))
                }
            }

            val map = LinkedHashMap<String?, MutableList<VodSeries>>()
            seriesMap = map
            for (flag in flags) {
                val list = tempSeriesMap[flag.name]
                assert(list != null)
                val series = list!!
                if (flags.size <= 5) {
                    if (isReverse(series)) Collections.reverse(series)
                }
                map[flag.name] = series
            }
        }
    }

    private fun extractNumber(name: String): Int {
        val matcher = RegexUtils.getPattern("\\d+").matcher(name)
        if (matcher.find()) {
            return Integer.parseInt(matcher.group())
        }
        return 0
    }

    private fun isReverse(list: List<VodSeries>): Boolean {
        var ascCount = 0
        var descCount = 0
        // 比较最多前 6 个相邻元素对
        val limit = Math.min(list.size - 1, 6)
        for (i in 0 until limit) {
            val current = extractNumber(list[i].name!!)
            val next = extractNumber(list[i + 1].name!!)
            if (current < next) {
                ascCount++
                if (ascCount == 2) return false
            } else if (current > next) {
                descCount++
                if (descCount == 2) return true
            }
        }
        return false
    }

    fun reverse() {
        // 无线路时 setVideo 不会建 seriesMap:历史里存过"倒序"的片子再打开不能崩在这里
        val map = seriesMap ?: return
        val flags = map.keys
        for (flag in flags) {
            Collections.reverse(map[flag]!!)
        }
    }

    class VodSeriesFlag() : Serializable {

        @JvmField
        var name: String? = null
        @JvmField
        var selected: Boolean = false

        constructor(name: String?) : this() {
            this.name = name
        }
    }

    class VodSeries() : Serializable {

        @JvmField
        var name: String? = null
        @JvmField
        var url: String? = null
        @JvmField
        var selected: Boolean = false

        constructor(name: String?, url: String?) : this() {
            this.name = name
            this.url = url
        }
    }
}
