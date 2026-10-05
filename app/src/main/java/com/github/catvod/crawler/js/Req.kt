package com.github.catvod.crawler.js

import android.text.TextUtils

import com.github.tvbox.osc.util.RegexUtils
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

import java.util.Arrays

class Req {

    @field:SerializedName("buffer")
    private var buffer: Int? = null

    @field:SerializedName("redirect")
    private var redirect: Int? = null

    @field:SerializedName("timeout")
    private var timeout: Int? = null

    @field:SerializedName("postType")
    private var postType: String? = null

    @field:SerializedName("method")
    private var method: String? = null

    @field:SerializedName("body")
    private var body: String? = null

    @field:SerializedName("data")
    private var data: JsonElement? = null

    @field:SerializedName("headers")
    private var headers: JsonElement? = null

    fun getBuffer(): Int {
        return buffer ?: 0
    }

    fun getRedirect(): Int? {
        return redirect ?: 1
    }

    fun getTimeout(): Int? {
        return timeout ?: 10000
    }

    fun getPostType(): String {
        return if (TextUtils.isEmpty(postType)) "json" else postType!!
    }

    fun getMethod(): String {
        return if (TextUtils.isEmpty(method)) "get" else method!!
    }

    fun getBody(): String? {
        return body
    }

    fun getData(): JsonElement? {
        return data
    }

    private fun getHeaders(): JsonElement? {
        return headers
    }

    fun isRedirect(): Boolean {
        return getRedirect() == 1
    }

    fun getHeader(): Map<String, String> {
        return Json.toMap(getHeaders())
    }

    fun getCharset(): String {
        val header = getHeader()
        val keys = Arrays.asList("Content-Type", "content-type")
        for (key in keys) if (header.containsKey(key)) return getCharset(header[key]!!)
        return "UTF-8"
    }

    private fun getCharset(value: String): String {
        for (text in RegexUtils.getPattern(";").split(value)) if (text.contains("charset=")) return RegexUtils.getPattern("=").split(text)[1]
        return "UTF-8"
    }

    companion object {

        @JvmStatic
        fun objectFrom(json: String): Req {
            return Gson().fromJson(json, Req::class.java)
        }
    }
}
