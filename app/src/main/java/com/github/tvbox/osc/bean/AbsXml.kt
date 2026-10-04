package com.github.tvbox.osc.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import java.io.Serializable

/**
 * @author pj567
 * @date :2020/12/18
 */
@XStreamAlias("rss")
class AbsXml : Serializable {
    @JvmField
    var sourceKey: String? = null
    @JvmField
    var searchToken: String? = null

    /** 详情代次(V4):发起详情请求时代入,回包原样带回;非详情通道为 null */
    @JvmField
    var detailToken: Int? = null

    @JvmField
    @XStreamAlias("list")
    var movie: Movie? = null

    @JvmField
    @XStreamAlias("msg")
    var msg: String? = null
}
