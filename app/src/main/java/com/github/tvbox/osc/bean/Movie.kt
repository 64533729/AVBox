package com.github.tvbox.osc.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import com.thoughtworks.xstream.annotations.XStreamAsAttribute
import com.thoughtworks.xstream.annotations.XStreamConverter
import com.thoughtworks.xstream.annotations.XStreamImplicit
import com.thoughtworks.xstream.converters.extended.ToAttributedValueConverter
import java.io.Serializable

/**
 * @author pj567
 * @date :2020/12/18
 */
@XStreamAlias("list")
class Movie : Serializable {
    @JvmField
    @XStreamAsAttribute
    var page: Int = 0
    @JvmField
    @XStreamAsAttribute
    var pagecount: Int = 0
    @JvmField
    @XStreamAsAttribute
    var pagesize: Int = 0
    @JvmField
    @XStreamAsAttribute
    var recordcount: Int = 0
    @JvmField
    @XStreamImplicit(itemFieldName = "video")
    var videoList: MutableList<Video>? = null

    @XStreamAlias("video")
    class Video : Serializable {
        /** 时间 */
        @JvmField
        @XStreamAlias("last")
        var last: String? = null

        /** 内容id */
        @JvmField
        @XStreamAlias("id")
        var id: String? = null

        /** 父级id */
        @JvmField
        @XStreamAlias("tid")
        var tid: Int = 0

        /** 影片名称 <![CDATA[老爸当家]]> */
        @JvmField
        @XStreamAlias("name")
        var name: String? = null

        /** 类型名称 */
        @JvmField
        @XStreamAlias("type")
        var type: String? = null

        /** 图片 */
        @JvmField
        @XStreamAlias("pic")
        var pic: String? = null

        /** 语言 */
        @JvmField
        @XStreamAlias("lang")
        var lang: String? = null

        /** 地区 */
        @JvmField
        @XStreamAlias("area")
        var area: String? = null

        /** 年份 */
        @JvmField
        @XStreamAlias("year")
        var year: Int = 0

        @JvmField
        @XStreamAlias("state")
        var state: String? = null

        /** 描述集数或者影片信息<![CDATA[共40集]]> */
        @JvmField
        @XStreamAlias("note")
        var note: String? = null

        /** 演员<![CDATA[张国立,蒋欣,高鑫,曹艳艳,王维维,韩丹彤,孟秀,王新]]> */
        @JvmField
        @XStreamAlias("actor")
        var actor: String? = null

        /** 导演<![CDATA[陈国星]]> */
        @JvmField
        @XStreamAlias("director")
        var director: String? = null

        @JvmField
        @XStreamAlias("dl")
        var urlBean: UrlBean? = null

        /** <![CDATA[权来] */
        @JvmField
        @XStreamAlias("des")
        var des: String? = null

        @JvmField
        var sourceKey: String? = null

        @JvmField
        @XStreamAlias("tag")
        var tag: String? = null

        @JvmField
        @XStreamAlias("action")
        var action: String? = null

        @XStreamAlias("dl")
        class UrlBean : Serializable {
            @JvmField
            @XStreamImplicit(itemFieldName = "dd")
            var infoList: MutableList<UrlInfo>? = null

            @XStreamAlias("dd")
            @XStreamConverter(value = ToAttributedValueConverter::class, strings = ["urls"])
            class UrlInfo : Serializable {
                /** zuidam3u8,zuidall(MP4) */
                @JvmField
                @XStreamAsAttribute
                var flag: String? = null

                /** <![CDATA[第01集$http://...]]> */
                @JvmField
                var urls: String? = null

                @JvmField
                var beanList: MutableList<InfoBean>? = null

                class InfoBean(nameValue: String?, urlValue: String?) : Serializable {
                    @JvmField
                    var name: String? = nameValue

                    @JvmField
                    var url: String? = urlValue
                }
            }
        }
    }
}
