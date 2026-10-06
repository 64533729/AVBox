package com.github.tvbox.osc.player.engine

object CodecPreferences {

    @Volatile
    private var preferSoftware = false

    @JvmStatic
    fun setPreferSoftwareDecode(prefer: Boolean) {
        preferSoftware = prefer
    }

    @JvmStatic
    fun isPreferSoftwareDecode(): Boolean = preferSoftware
}
