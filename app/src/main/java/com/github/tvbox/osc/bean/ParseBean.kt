package com.github.tvbox.osc.bean

import android.util.Base64

import com.github.tvbox.osc.util.DefaultConfig

/**
 * @author pj567
 * @date :2021/3/8
 */
class ParseBean {

    var name: String? = null

    private var urlValue: String? = null

    var url: String?
        get() = DefaultConfig.checkReplaceProxy(urlValue)
        set(value) {
            urlValue = value
        }

    var ext: String? = null

    /** 0 普通嗅探 1 json 2 Json扩展 3 聚合 */
    var type: Int = 0

    var isDefault: Boolean = false

    fun mixUrl(): String? {
        if (!ext!!.isEmpty()) {
            val idx = urlValue!!.indexOf("?")
            if (idx > 0) {
                return urlValue!!.substring(0, idx + 1) + "cat_ext=" +
                    Base64.encodeToString(ext!!.toByteArray(), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP) +
                    "&" + urlValue!!.substring(idx + 1)
            }
        }
        return urlValue
    }
}
