package com.github.tvbox.osc.util

object LocalAddress {
    @JvmStatic
    var provider: (() -> String?)? = null

    @JvmStatic
    fun get(): String = provider?.invoke() ?: ""
}
