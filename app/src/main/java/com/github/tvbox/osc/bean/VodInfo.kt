package com.github.tvbox.osc.bean

import com.github.tvbox.osc.util.RegexUtils
import java.io.Serializable
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

class VodInfo : Serializable {
    @JvmField
    var last: String? = null

    @JvmField
    var id: String? = null

    @JvmField
    var tid: Int = 0

    @JvmField
    var name: String? = null

    @JvmField
    var type: String? = null

    @JvmField
    var dt: String? = null

    @JvmField
    var pic: String? = null

    @JvmField
    var lang: String? = null

    @JvmField
    var area: String? = null

    @JvmField
    var year: Int = 0

    @JvmField
    var state: String? = null

    @JvmField
    var note: String? = null

    @JvmField
    var actor: String? = null

    @JvmField
    var director: String? = null

    @JvmField
    var seriesFlags: ArrayList<VodSeriesFlag>? = null
    @JvmField
    var seriesMap: LinkedHashMap<String?, MutableList<VodSeries>>? = null

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

    @JvmField
    var sourceName: String = ""

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
