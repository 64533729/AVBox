package com.github.tvbox.osc.player

import android.view.ViewGroup

interface PlaybackPage {

    fun renderSlot(): ViewGroup

    fun viewBridge(): PlaybackViewBridge

    fun onServiceStopped()
}
