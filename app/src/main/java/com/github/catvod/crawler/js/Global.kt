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

    private val httpTag: String

    constructor(executor: ExecutorService) : this(executor, "default")

    constructor(executor: ExecutorService, key: String) {
        this.executor = executor
        this.httpTag = "js_okhttp_tag_" + key
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
        return result
    }

    @Keep
    @Function
    fun rsaX(mode: String?, pub: Boolean, encrypt: Boolean, input: String?, inBase64: Boolean, key: String?, outBase64: Boolean): String {
        val result = Crypto.rsa(pub, encrypt, input, inBase64, key, outBase64)
        return result
    }

    @Keep
    @Function
    fun rsaEncrypt(data: String?, key: String?): String? {
        return rsaEncrypt(data, key, null)
    }

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
                                func.release()
                            }
                        })
                    } catch (e: RejectedExecutionException) {
                        func.release()
                    }
                } else {
                    func.release()
                }
            }
        }, delay!!.toLong())
    }

    fun destroy() {
        timer.cancel()
    }

    private fun getCallback(complete: JSFunction, req: Req): Callback {
        return object : Callback {
            override fun onResponse(call: Call, res: Response) {
                if (executor.isShutdown) {
                    res.close()
                    return
                }
                try {
                    executor.submit(Runnable {
                        try {
                            complete.call(Connect.success(runtime!!, req, res))
                        } finally {
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
    @ContextSetter
    fun setJSContext(runtime: QuickJSContext) {
        this.runtime = runtime
    }
}
