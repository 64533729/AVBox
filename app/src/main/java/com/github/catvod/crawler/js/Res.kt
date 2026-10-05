package com.github.catvod.crawler.js

import android.text.TextUtils
import android.util.Base64

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Arrays

class Res {

    @field:SerializedName("code")
    private var code: Int? = null

    @field:SerializedName("buffer")
    private var buffer: Int? = null

    @field:SerializedName("content")
    private var content: String? = null

    @field:SerializedName("headers")
    private var headers: JsonElement? = null

    fun getCode(): Int {
        return code ?: 200
    }

    fun getBuffer(): Int {
        return buffer ?: 0
    }

    fun getContent(): String {
        return if (TextUtils.isEmpty(content)) "" else content!!
    }

    private fun getHeaders(): JsonElement? {
        return headers
    }

    fun getHeader(): Map<String, String> {
        return Json.toMap(getHeaders())
    }

    fun getContentType(): String {
        val header = getHeader()
        val keys = Arrays.asList("Content-Type", "content-type")
        for (key in keys) if (header.containsKey(key)) return header[key]!!
        return "application/octet-stream"
    }

    fun getStream(): ByteArrayInputStream {
        if (getBuffer() == 2) return ByteArrayInputStream(Base64.decode(getContent(), Base64.DEFAULT))
        return ByteArrayInputStream(getContent().toByteArray(Charset.defaultCharset()))
    }

    companion object {

        @JvmStatic
        fun objectFrom(json: String): Res {
            return Gson().fromJson(json, Res::class.java)
        }
    }
}
