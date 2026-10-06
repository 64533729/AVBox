package com.github.tvbox.osc.event

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

        const val TYPE_PLAYBACK_STARTED = 22

        const val TYPE_COLLECT_LAYOUT_CHANGE = 23
    }
}
