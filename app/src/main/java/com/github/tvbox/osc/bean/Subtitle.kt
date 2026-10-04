package com.github.tvbox.osc.bean

class Subtitle {

    var name: String? = null

    var url: String? = null

    @get:JvmName("getIsZip")
    @set:JvmName("setIsZip")
    var isZip: Boolean = false

    override fun toString(): String {
        return "Subtitle{name='$name', url='$url', isZip=$isZip}"
    }
}
