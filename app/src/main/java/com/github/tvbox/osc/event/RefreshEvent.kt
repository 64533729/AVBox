package com.github.tvbox.osc.event

/**
 * EventBus 事件类型注册表。
 * 2026-09-13 清理:删除 6 个**全工程零使用**的常量 —— TYPE_PUSH_URL(9)、TYPE_EPG_URL_CHANGE(10)、
 * TYPE_SETTING_SEARCH_TV(11)、TYPE_FILTER_CHANGE(13)、TYPE_LIVE_API_URL_CHANGE(14)、
 * TYPE_HOME_SOURCE_CHANGE(15)(对应功能早已移除,既无 post 也无订阅者)。
 * ⚠️ 保留常量的**数值不要改**:EventBus 按 int 分发,且 9/10/11/13/14/15 已成为空洞,新增事件请用新编号。
 */
class RefreshEvent {
    @JvmField
    var type: Int = 0

    @JvmField
    var obj: Any? = null

    constructor(type: Int) {
        this.type = type
    }

    constructor(type: Int, obj: Any?) {
        this.type = type
        this.obj = obj
    }

    companion object {
        const val TYPE_REFRESH = 0
        const val TYPE_HISTORY_REFRESH = 1
        const val TYPE_SEARCH_RESULT = 6
        const val TYPE_API_URL_CHANGE = 8
        const val TYPE_SUBTITLE_SIZE_CHANGE = 12
        const val TYPE_SET_DANMU_SETTINGS = 18
        const val TYPE_DANMU_REFRESH = 19
        const val TYPE_PLAY_QUALITY = 20
        const val TYPE_COLLECT_REFRESH = 21

        /** 播放头真的推进过(即"看过"),由 PlaybackProgress 每集发一次,观看历史据此落库 */
        const val TYPE_PLAYBACK_STARTED = 22

        /** 收藏页栅格列数变更(设置页"收藏页布局"落 KV 后广播,收藏页据此立即换列数) */
        const val TYPE_COLLECT_LAYOUT_CHANGE = 23
    }
}
