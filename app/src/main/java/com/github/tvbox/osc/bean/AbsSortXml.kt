package com.github.tvbox.osc.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import java.io.Serializable

/**
 * @author pj567
 * @date :2020/12/18
 */
@XStreamAlias("rss")
class AbsSortXml : Serializable {
    @JvmField
    var sourceKey: String? = null

    /** 分类取数失败兜底标记:SortLoader 真实失败时置位,与"站点确实无分类"区分 */
    @JvmField
    @Transient
    var loadFailed: Boolean = false

    @JvmField
    @XStreamAlias("class")
    var classes: MovieSort? = null

    @JvmField
    @XStreamAlias("list")
    var list: Movie? = null

    @JvmField
    var videoList: MutableList<Movie.Video>? = null
}
