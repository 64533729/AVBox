package com.github.tvbox.osc.event

class ServerEvent {
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
        const val SERVER_SUCCESS = 0
        const val SERVER_CONNECTION = 1
        const val SERVER_SEARCH = 2
    }
}
