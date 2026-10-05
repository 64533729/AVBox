package com.github.catvod.crawler.js.rsa

import android.util.Base64

import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * 数据工具类
 */
class DataUtils {

    companion object {

        /**
         * 将 Base64 字符串 解码成 字节数组
         */
        @JvmStatic
        fun base64Decode(data: String?): ByteArray {
            return Base64.decode(data!!.toByteArray(Charset.defaultCharset()), Base64.NO_WRAP)
        }

        /**
         * 将 字节数组 转换成 Base64 编码
         */
        @JvmStatic
        fun base64Encode(data: ByteArray): String {
            return Base64.encodeToString(data, Base64.NO_WRAP)
        }

        /**
         * 将字节数组转换成 int 类型
         */
        @JvmStatic
        fun byte2Int(bytes: ByteArray): Int {
            val buffer = ByteBuffer.wrap(bytes)
            return buffer.getInt()
        }

        /**
         * 将 int 转换成 byte 数组
         */
        @JvmStatic
        fun int2byte(data: Int): ByteArray {
            val buffer = ByteBuffer.allocate(4)
            buffer.putInt(data)
            return buffer.array()
        }
    }
}
