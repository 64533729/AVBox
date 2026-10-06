package com.github.catvod.crawler.js.rsa

import android.util.Base64

import java.nio.ByteBuffer
import java.nio.charset.Charset

class DataUtils {

    companion object {

        @JvmStatic
        fun base64Decode(data: String?): ByteArray {
            return Base64.decode(data!!.toByteArray(Charset.defaultCharset()), Base64.NO_WRAP)
        }

        @JvmStatic
        fun base64Encode(data: ByteArray): String {
            return Base64.encodeToString(data, Base64.NO_WRAP)
        }

        @JvmStatic
        fun byte2Int(bytes: ByteArray): Int {
            val buffer = ByteBuffer.wrap(bytes)
            return buffer.getInt()
        }

        @JvmStatic
        fun int2byte(data: Int): ByteArray {
            val buffer = ByteBuffer.allocate(4)
            buffer.putInt(data)
            return buffer.array()
        }
    }
}
