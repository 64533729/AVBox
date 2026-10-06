package com.github.tvbox.osc.data

import com.github.tvbox.osc.bean.VodInfo

object PlaybackPorts {

    var currentVod: (() -> VodInfo?)? = null

    var isLiveMode: (() -> Boolean)? = null

    var discardStartedContentOf: ((List<String>) -> Unit)? = null
}
