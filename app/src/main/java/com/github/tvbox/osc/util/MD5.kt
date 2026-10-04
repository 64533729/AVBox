package com.github.tvbox.osc.util

import android.text.TextUtils
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * <b>类名称：</b> MD5 <br/>
 * <b>类描述：</b> MD5值计算<br/>
 * <b>创建人：</b> 林肯 <br/>
 * <b>修改人：</b> 编辑人 <br/>
 * <b>修改时间：</b> 2015年08月11日 下午2:41 <br/>
 * <b>修改备注：</b> <br/>
 *
 * @version 1.0.0 <br/>
 */
class MD5 {

    companion object {

        private val hexDigits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            'a', 'b', 'c', 'd', 'e', 'f')

        /**
         * 消息摘要非线程安全，禁止静态共享；改为每次调用创建局部实例（无锁竞争）.
         */
        private fun newDigest(): MessageDigest? {
            try {
                return MessageDigest.getInstance("MD5")
            } catch (e: NoSuchAlgorithmException) {
                Log.e("获取MD5信息摘要失败", "${e.message}")
                return null
            }
        }

        /**
         * MD5值计算
         * MD5的算法在RFC1321 中定义:
         * 在RFC 1321中，给出了Test suite用来检验你的实现是否正确：
         * MD5 ("") = d41d8cd98f00b204e9800998ecf8427e
         * MD5 ("a") = 0cc175b9c0f1b6a831c399e269772661
         * MD5 ("abc") = 900150983cd24fb0d6963f7d28e17f72
         * MD5 ("message digest") = f96b697d7cb7938d525a2f31aaf161d0
         * MD5 ("abcdefghijklmnopqrstuvwxyz") = c3fcd3d76192e4007dfb496cca67e13b
         *
         * @param res 源字符串
         * @return md5值
         */
        @JvmStatic
        fun encode(res: String): String? {
            val strTemp = res.toByteArray(Charset.defaultCharset())
            return encode(strTemp)
        }

        private fun encode(bytes: ByteArray): String? {
            try {
                val digest = newDigest() ?: return null
                digest.update(bytes)
                val md = digest.digest()
                val j = md.size
                val str = CharArray(j * 2)
                var k = 0
                for (byte0 in md) {
                    str[k++] = hexDigits[(byte0.toInt() ushr 4) and 0xf]
                    str[k++] = hexDigits[byte0.toInt() and 0xf]
                }
                return String(str)
            } catch (e: Exception) {
                return null
            }
        }

        @JvmStatic
        fun getFileMd5(f: File): String {
            val sb = StringBuffer("")
            try {
                val md = MessageDigest.getInstance("MD5")
                val buffer = ByteArray(4096)
                val fis = FileInputStream(f)
                var len = 0
                while (fis.read(buffer).also { len = it } != -1) {
                    md.update(buffer, 0, len)
                }
                fis.close()
                val b = md.digest()
                for (i in b.indices) {
                    var d = b[i].toInt()
                    if (d < 0) {
                        d = b[i].toInt() and 0xff
                    }
                    if (d < 16) sb.append("0")
                    sb.append(Integer.toHexString(d))
                }
            } catch (e: NoSuchAlgorithmException) {
                LOG.e("MD5", e)
            } catch (e: IOException) {
                LOG.e("MD5", e)
            }
            return sb.toString()
        }

        /**
         * MD5加码 生成32位md5码
         */
        @JvmStatic
        fun string2MD5(inStr: String?): String? {
            val digest = newDigest()
            if (digest == null) {
                Log.e("MD5", "MD5信息摘要初始化失败")
                return null
            } else if (TextUtils.isEmpty(inStr)) {
                Log.e("MD5", "参数strSource不能为空")
                return null
            }
            val charArray = inStr!!.toCharArray()
            val byteArray = ByteArray(charArray.size)

            for (i in charArray.indices) {
                byteArray[i] = charArray[i].code.toByte()
            }
            val md5Bytes = digest.digest(byteArray)
            val hexValue = StringBuilder()
            for (md5Byte in md5Bytes) {
                val value = md5Byte.toInt() and 0xff
                if (value < 16) {
                    hexValue.append("0")
                }
                hexValue.append(Integer.toHexString(value))
            }
            return hexValue.toString()
        }

        /**
         * 先使用MD5进行加密，再使用Base64进行编码， 若不支持此类字符集合的加密，返回null.
         *
         * @param strSource 待加密的源字符串
         * @return 加密后的字符串，不支持此类字符集合返回null
         */
        @JvmStatic
        fun encrypt(strSource: String?): String? {
            val digest = newDigest()
            if (digest == null) {
                Log.e("MD5", "MD5信息摘要初始化失败")
                return null
            } else if (TextUtils.isEmpty(strSource)) {
                Log.e("MD5", "参数strSource不能为空")
                return null
            }
            try {
                val md5Bytes = digest.digest(strSource!!.toByteArray(Charsets.UTF_8))
                val encryptBytes = Base64.encode(md5Bytes, Base64.DEFAULT)
                val strEncrypt = String(encryptBytes, Charsets.UTF_8)
                return strEncrypt.substring(0, strEncrypt.length - 1) // 截断Base64产生的换行符
            } catch (e: UnsupportedEncodingException) {
                Log.e("MD5", "加密模块暂不支持此字符集合$e")
            }
            return null
        }

        @JvmStatic
        fun encrypt4login(strSource: String?, appSecert: String?): String? {
            val str = encrypt(strSource) + appSecert
            return string2MD5(str)
        }
    }
}
