package com.github.catvod.crawler.js

import androidx.annotation.Keep

import com.github.catvod.Proxy
import com.github.catvod.crawler.js.rsa.RSAEncrypt
import com.github.tvbox.osc.util.LOG
import com.whl.quickjs.wrapper.ContextSetter
import com.whl.quickjs.wrapper.Function
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.JSUtils
import com.whl.quickjs.wrapper.QuickJSContext


import java.io.IOException
import java.net.URLEncoder
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

class Global {

    private var runtime: QuickJSContext? = null

    @JvmField
    var executor: ExecutorService

    private val timer: Timer

    /** BugReview #31:本爬虫专属取消 tag,按站点隔离,防 stopAll 误杀其他站点在途请求 */
    private val httpTag: String

    constructor(executor: ExecutorService) : this(executor, "default")

    constructor(executor: ExecutorService, key: String) {
        this.executor = executor
        this.httpTag = "js_okhttp_tag_" + key
        // 守护线程 + 命名：避免每个爬虫泄漏一条非守护线程
        this.timer = Timer("js-spider-timer", true)
    }

    fun getHttpTag(): String {
        return httpTag
    }

    @Keep
    @Function
    fun getProxy(local: Boolean): String {
        return Proxy.getUrl(local) + "?do=js"
    }

    @Keep
    @Function
    fun js2Proxy(dynamic: Boolean?, siteType: Int?, siteKey: String?, url: String?, headers: JSObject): String {
        val local = dynamic == null || !dynamic
        return getProxy(local) + "&from=catvod" + "&siteType=" + siteType + "&siteKey=" + siteKey + "&header=" + URLEncoder.encode(headers.stringify()) + "&url=" + URLEncoder.encode(url)
    }

    @Keep
    @Function
    fun joinUrl(parent: String?, child: String): String {
        return HtmlParser.joinUrl(parent, child)
    }

    @Keep
    @Function
    fun pd(html: String, rule: String, add_url: String): String {
        return HtmlParser.parseDomForUrl(html, rule, add_url)
    }

    @Keep
    @Function
    fun pdfh(html: String, rule: String): String {
        return HtmlParser.parseDomForUrl(html, rule, "")
    }

    @Keep
    @Function
    fun pdfa(html: String, rule: String): JSArray {

        return JSUtils<String>().toArray(runtime!!, HtmlParser.parseDomForArray(html, rule))
    }

    @Keep
    @Function
    fun pdfla(html: String, p1: String, list_text: String, list_url: String, add_url: String): JSArray {
        return JSUtils<String>().toArray(runtime!!, HtmlParser.parseDomForList(html, p1, list_text, list_url, add_url))
    }

    @Keep
    @Function
    fun s2t(text: String?): String? {
        try {
            return Trans.s2t(false, text)
        } catch (e: Exception) {
            return ""
        }
    }

    @Keep
    @Function
    fun t2s(text: String?): String? {
        try {
            return Trans.t2s(false, text)
        } catch (e: Exception) {
            return ""
        }
    }

    @Keep
    @Function
    fun aesX(mode: String?, encrypt: Boolean, input: String?, inBase64: Boolean, key: String?, iv: String?, outBase64: Boolean): String {
        val result = Crypto.aes(mode, encrypt, input, inBase64, key, iv, outBase64)
        //LOG.e("aesX",String.format("mode:%s\nencrypt:%s\ninBase64:%s\noutBase64:%s\nkey:%s\niv:%s\ninput:\n%s\nresult:\n%s", mode, encrypt, inBase64, outBase64, key, iv, input, result));
        return result
    }

    @Keep
    @Function
    fun rsaX(mode: String?, pub: Boolean, encrypt: Boolean, input: String?, inBase64: Boolean, key: String?, outBase64: Boolean): String {
        val result = Crypto.rsa(pub, encrypt, input, inBase64, key, outBase64)
        //LOG.e("aesX",String.format("mode:%s\npub:%s\nencrypt:%s\ninBase64:%s\noutBase64:%s\nkey:\n%s\ninput:\n%s\nresult:\n%s", mode, pub, encrypt, inBase64, outBase64, key, input, result));
        return result
    }

    @Keep
    @Function
    fun rsaEncrypt(data: String?, key: String?): String? {
        return rsaEncrypt(data, key, null)
    }

    /**
     * RSA 加密
     *
     * @param data    要加密的数据
     * @param key     密钥，type 为 1 则公钥，type 为 2 则私钥
     * @param options 加密的选项，包含加密配置和类型：{ config: "RSA/ECB/PKCS1Padding", type: 1, long: 1 }
     *                config 加密的配置，默认 RSA/ECB/PKCS1Padding （可选）
     *                type 加密类型，1 公钥加密 私钥解密，2 私钥加密 公钥解密（可选，默认 1）
     *                long 加密方式，1 普通，2 分段（可选，默认 1）
     *                block 分段长度，false 固定117，true 自动（可选，默认 true ）
     * @return 返回加密结果
     */

    @Keep
    @Function
    fun rsaEncrypt(data: String?, key: String?, options: JSObject?): String? {
        var mLong = 1
        var mType = 1
        var mBlock = true
        var mConfig: String? = null
        if (options != null) {
            val op = JSUtils.toJsonObject(options)
            if (op.has("config")) {
                try {
                    mConfig = op.get("config") as String?
                } catch (e: Exception) {
                    LOG.e("Global", e)
                }
            }
            if (op.has("type")) {
                try {
                    mType = (op.get("type") as Double).toInt()
                } catch (e: Exception) {
                    LOG.e("Global", e)
                }
            }
            if (op.has("long")) {
                try {
                    mLong = (op.get("long") as Double).toInt()
                } catch (e: Exception) {
                    LOG.e("Global", e)
                }
            }
            if (op.has("block")) {
                try {
                    mBlock = op.get("block") as Boolean
                } catch (e: Exception) {
                    LOG.e("Global", e)
                }
            }
        }
        try {
            return when (mType) {
                1 -> {
                    if (mConfig != null) {
                        RSAEncrypt.encryptByPublicKey(data, key, mConfig, mLong, mBlock)
                    } else {
                        RSAEncrypt.encryptByPublicKey(data, key, mLong, mBlock)
                    }
                }

                2 -> {
                    if (mConfig != null) {
                        RSAEncrypt.encryptByPrivateKey(data, key, mConfig, mLong, mBlock)
                    } else {
                        RSAEncrypt.encryptByPrivateKey(data, key, mLong, mBlock)
                    }
                }

                else -> ""
            }
        } catch (e: Exception) {
            return ""
        }
    }

    @Keep
    @Function
    fun rsaDecrypt(encryptBase64Data: String?, key: String?): String? {
        return rsaDecrypt(encryptBase64Data, key, null)
    }

    /**
     * RSA 解密
     *
     * @param encryptBase64Data 加密后的 Base64 字符串
     * @param key               密钥，type 为 1 则私钥，type 为 2 则公钥
     * @param options           解密的选项，包含解密配置和类型：{ config: "RSA/ECB/PKCS1Padding", type: 1, long: 1 }
     *                          config 解密的配置，默认 RSA/ECB/PKCS1Padding （可选）
     *                          type 解密类型，1 公钥加密 私钥解密，2 私钥加密 公钥解密（可选，默认 1）
     *                          long 解密方式，1 普通，2 分段（可选，默认 1）
     *                          block 分段长度，false 固定128，true 自动（可选，默认 true ）
     * @return 返回解密结果
     */
    @Keep
    @Function
    fun rsaDecrypt(encryptBase64Data: String?, key: String?, options: JSObject?): String? {
        var mLong = 1
        var mType = 1
        var mBlock = true
        var mConfig: String? = null
        if (options != null) {
            val op = JSUtils.toJsonObject(options)
            if (op.has("config")) {
                try {
                    mConfig = op.get("config") as String?
                } catch (e: Exception) {
                    LOG.e("Global", e)
                }
            }
            if (op.has("type")) {
                try {
                    mType = (op.get("type") as Double).toInt()
                } catch (e: Exception) {
                    LOG.e("Global", e)
                }
            }
            if (op.has("long")) {
                try {
                    mLong = (op.get("long") as Double).toInt()
                } catch (e: Exception) {
                    LOG.e("Global", e)
                }
            }
            if (op.has("block")) {
                try {
                    mBlock = op.get("block") as Boolean
                } catch (e: Exception) {
                    LOG.e("Global", e)
                }
            }
        }
        try {
            return when (mType) {
                1 -> {
                    if (mConfig != null) {
                        RSAEncrypt.decryptByPrivateKey(encryptBase64Data, key, mConfig, mLong, mBlock)
                    } else {
                        RSAEncrypt.decryptByPrivateKey(encryptBase64Data, key, mLong, mBlock)
                    }
                }

                2 -> {
                    if (mConfig != null) {
                        RSAEncrypt.decryptByPublicKey(encryptBase64Data, key, mConfig, mLong, mBlock)
                    } else {
                        RSAEncrypt.decryptByPublicKey(encryptBase64Data, key, mLong, mBlock)
                    }
                }

                else -> ""
            }
        } catch (e: Exception) {
            return ""
        }
    }

    private fun req(url: String, options: JSObject): JSObject {
        try {
            val req = Req.objectFrom(JSUtils.toJsonObject(options).toString())
            val res = Connect.to(url, req, httpTag).execute()
            return Connect.success(runtime!!, req, res)
        } catch (e: Exception) {
            return Connect.error(runtime!!)
        }
    }

    @Keep
    @Function
    fun _http(url: String, options: JSObject): JSObject? {
        val complete = options.getJSFunction("complete")
        if (complete == null) return req(url, options)
        val req = Req.objectFrom(JSUtils.toJsonObject(options).toString())
        Connect.to(url, req, httpTag).enqueue(getCallback(complete, req))
        return null
    }

    @Keep
    @Function
    fun setTimeout(func: JSFunction, delay: Int?) {
        func.hold()
        timer.schedule(object : TimerTask() {
            override fun run() {
                if (!executor.isShutdown) {
                    try {
                        executor.submit(Runnable {
                            try {
                                func.call()
                            } finally {
                                func.release() // hold 的配对释放，防 JNI 全局引用泄漏
                            }
                        })
                    } catch (e: RejectedExecutionException) {
                        // isShutdown 检查与 submit 之间的竞态窗口兑底
                        func.release()
                    }
                } else {
                    func.release()
                }
            }
        }, delay!!.toLong())
    }

    /** 爬虫销毁时调用：停掉 Timer，释放线程 */
    fun destroy() {
        timer.cancel()
    }

    private fun getCallback(complete: JSFunction, req: Req): Callback {
        return object : Callback {
            override fun onResponse(call: Call, res: Response) {
                // 爬虫已销毁时不再向已 shutdown 的 executor 提交任务（防 RejectedExecutionException 崩溃）
                if (executor.isShutdown) {
                    res.close()
                    return
                }
                try {
                    executor.submit(Runnable {
                        try {
                            complete.call(Connect.success(runtime!!, req, res))
                        } finally {
                            // Connect.success 内部不一定关闭响应体，这里兑底防连接泄漏
                            // 注：success 消费后 body 已读入内存，close 重复调用是安全的
                            try {
                                res.close()
                            } catch (ignored: Throwable) {
                                LOG.d("Global", "close response failed")
                            }
                        }
                    })
                } catch (e: RejectedExecutionException) {
                    res.close()
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (executor.isShutdown) return
                try {
                    executor.submit(Runnable { complete.call(Connect.error(runtime!!)) })
                } catch (ignored: RejectedExecutionException) {
                    LOG.d("Global", "executor shutdown, drop error callback")
                }
            }
        }
    }

    @Keep
    // 声明用于依赖注入的 QuickJSContext
    @ContextSetter
    fun setJSContext(runtime: QuickJSContext) {
        this.runtime = runtime
    }
}
