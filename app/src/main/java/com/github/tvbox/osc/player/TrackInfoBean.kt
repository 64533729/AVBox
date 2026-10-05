package com.github.tvbox.osc.player

class TrackInfoBean {
    @JvmField
    var trackId: Int = 0

    @JvmField
    var renderId: Int = 0

    @JvmField
    var trackGroupId: Int = 0

    @JvmField
    var name: String? = null

    @JvmField
    var language: String? = null

    @JvmField
    var groupIndex: Int = 0

    @JvmField
    var index: Int = 0

    @JvmField
    var selected: Boolean = false

    @JvmField
    var bitmapSubtitle: Boolean = false

    /** 轨道类型(media3 C.TRACK_TYPE_*) */
    @JvmField
    var type: Int = 0

    /** 轨道指纹(见 TrackMemory):跨集定位只认它 */
    @JvmField
    var formatKey: String? = null
}
