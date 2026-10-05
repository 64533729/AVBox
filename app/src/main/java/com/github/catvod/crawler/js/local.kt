package com.github.catvod.crawler.js

import androidx.annotation.Keep

import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.whl.quickjs.wrapper.Function

class local {

    @Keep
    @Function
    fun delete(str: String, str2: String) {
        try {
            KV.delete("jsRuntime_" + str + "_" + str2)
        } catch (e: Exception) {
            LOG.e("local", e)
        }
    }

    @Keep
    @Function
    fun get(str: String, str2: String): String {
        try {
            return KV.get("jsRuntime_" + str + "_" + str2, "")
        } catch (e: Exception) {
            // BugReview P3:原实现删的是 str,坏数据残留;应删完整 key
            KV.delete("jsRuntime_" + str + "_" + str2)
            return str2
        }
    }

    @Keep
    @Function
    fun set(str: String, str2: String, str3: String) {
        try {
            KV.put("jsRuntime_" + str + "_" + str2, str3)
        } catch (e: Exception) {
            LOG.e("local", e)
        }
    }
}
