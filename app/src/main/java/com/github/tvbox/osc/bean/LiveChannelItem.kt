package com.github.tvbox.osc.bean

import com.google.gson.JsonObject
import java.util.ArrayList
import java.util.HashMap
import java.util.Objects

/**
 * @author pj567
 * @date :2021/1/12
 */
class LiveChannelItem {
    /**
     * channelIndex : 频道索引号
     * channelNum : 频道名称
     * channelSourceNames : 频道源名称
     * channelUrls : 频道源地址
     * sourceIndex : 频道源索引
     * sourceNum : 频道源总数
     */
    var channelIndex: Int = 0
    var channelNum: Int = 0
    var channelName: String? = null

    var channelLogo: String? = null
        get() = field ?: ""

    var channelEpg: String? = null
        get() = field ?: ""

    var channelUa: String? = null
        get() = field ?: ""

    var channelClick: String? = null
        get() = field ?: ""

    var channelFormat: String? = null
        get() = field ?: ""

    var channelOrigin: String? = null
        get() = field ?: ""

    var channelReferer: String? = null
        get() = field ?: ""

    var channelTvgId: String? = null
        get() = field ?: ""

    var channelTvgName: String? = null
        get() = field ?: ""

    var channelCatchup: JsonObject? = null
        get() = field ?: JsonObject()

    var channelHeader: MutableMap<String, String>? = null
        get() = field ?: HashMap<String, String>()

    private var channelParse: Int? = null

    var channelSourceNames: ArrayList<String>? = null

    var channelUrls: ArrayList<String>? = null
        set(value) {
            field = value
            sourceNum = value!!.size
        }

    @JvmField
    var sourceIndex: Int = 0

    @JvmField
    var sourceNum: Int = 0

    @JvmField
    var include_back: Boolean = false

    val url: String
        get() = channelUrls!![sourceIndex]

    val sourceName: String
        get() = channelSourceNames!![sourceIndex]

    val headers: MutableMap<String, String>
        get() {
            val result = HashMap<String, String>(channelHeader.orEmpty())
            val ua = channelUa.orEmpty()
            if (ua.isNotEmpty()) result["User-Agent"] = ua
            val origin = channelOrigin.orEmpty()
            if (origin.isNotEmpty()) result["Origin"] = origin
            val referer = channelReferer.orEmpty()
            if (referer.isNotEmpty()) result["Referer"] = referer
            return result
        }

    fun setinclude_back(include_back: Boolean) {
        this.include_back = include_back
    }

    fun getinclude_back(): Boolean = include_back

    fun setSourceIndex(sourceIndex: Int) {
        this.sourceIndex = sourceIndex
    }

    fun getSourceIndex(): Int = sourceIndex

    fun getSourceNum(): Int = sourceNum

    fun getChannelParse(): Int = channelParse ?: 0

    fun setChannelParse(channelParse: Int?) {
        this.channelParse = channelParse
    }

    fun preSource() {
        sourceIndex--
        if (sourceIndex < 0) sourceIndex = sourceNum - 1
    }

    fun nextSource() {
        sourceIndex++
        if (sourceIndex == sourceNum) sourceIndex = 0
    }

    fun hasCatchup(): Boolean {
        val catchup = channelCatchup ?: return false
        return catchup.entrySet().size > 0
    }

    fun isEmptyCatchup(): Boolean {
        val catchup = channelCatchup ?: return true
        return catchup.entrySet().isEmpty()
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false
        val that = o as LiveChannelItem
        return Objects.equals(channelName, that.channelName) &&
            Objects.equals(channelUrls!![sourceIndex], that.url)
    }

    override fun hashCode(): Int {
        return Objects.hash(channelName, channelUrls!![sourceIndex])
    }
}
