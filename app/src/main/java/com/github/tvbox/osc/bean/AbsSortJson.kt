package com.github.tvbox.osc.bean

import com.google.gson.annotations.SerializedName
import java.io.Serializable
import java.util.ArrayList

class AbsSortJson : Serializable {

    // 元素类型可空是刻意的:JSON 里出现 "class":[null] 时 Gson 会往表里塞 null 元素,Java 版逐个跳过;
    // 写成 ArrayList<AbsJsonClass> 的话 Kotlin 会在迭代处插 checkNotNull(Java 具体集合的元素是平台类型),
    // 变成 NPE。可空元素类型不影响字段描述符与泛型签名,Java 调用方完全无感。
    @JvmField
    @SerializedName(value = "class")
    var classes: ArrayList<AbsJsonClass?>? = null

    @JvmField
    @SerializedName(value = "list")
    var list: ArrayList<AbsJson.AbsJsonVod>? = null

    fun toAbsSortXml(): AbsSortXml {
        val absSortXml = AbsSortXml()
        val movieSort = MovieSort()
        movieSort.sortList = ArrayList()
        if (classes == null) {
            classes = ArrayList()
        }
        for (cls in classes!!) {
            val item: AbsJsonClass = cls ?: continue
            if (item.type_id == null || item.type_name == null) {
                continue
            }
            val sortData = MovieSort.SortData()
            sortData.id = item.type_id
            sortData.name = item.type_name
            sortData.flag = item.type_flag
            movieSort.sortList!!.add(sortData)
        }
        if (list != null && !list!!.isEmpty()) {
            val movie = Movie()
            val videos = ArrayList<Movie.Video>()
            for (vod in list!!) {
                videos.add(vod.toXmlVideo())
            }
            movie.videoList = videos
            absSortXml.list = movie
        } else {
            absSortXml.list = null
        }
        absSortXml.classes = movieSort
        return absSortXml
    }

    inner class AbsJsonClass : Serializable {
        @JvmField
        @SerializedName(value = "type_id", alternate = ["id"])
        var type_id: String? = null
        @JvmField
        @SerializedName(value = "type_name", alternate = ["name"])
        var type_name: String? = null
        @JvmField
        var type_flag: String? = null
    }
}
