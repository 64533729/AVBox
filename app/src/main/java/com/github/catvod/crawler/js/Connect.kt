package com.github.catvod.crawler.js

import android.util.Base64

import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.OkGoHelper
import com.google.common.net.HttpHeaders
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.JSUtils
import com.whl.quickjs.wrapper.QuickJSContext

import java.nio.charset.Charset
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class Connect {

    companion object {

        @JvmField
        var client: OkHttpClient? = null

        @JvmStatic
        fun to(url: String, req: Req): Call {
            return to(url, req, "js_okhttp_tag")
        }

        @JvmStatic
        fun to(url: String, req: Req, tag: Any?): Call {
            client = withTimeout(req, if (req.isRedirect()) OkGoHelper.getDefaultClient()!! else OkGoHelper.getNoRedirectClient()!!)
            return client!!.newCall(getRequest(url, req, req.getHeader().toHeaders(), tag))
        }

        @JvmStatic
        fun withTimeout(req: Req, base: OkHttpClient): OkHttpClient {
            var timeout = req.getTimeout()!!.toLong()
            if (timeout <= 0) timeout = OkGoHelper.DEFAULT_MILLISECONDS
            if (timeout == OkGoHelper.DEFAULT_MILLISECONDS) return base
            return base.newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS).build()
        }

        @JvmStatic
        fun success(ctx: QuickJSContext, req: Req, res: Response): JSObject {
            try {
                val jsObject = ctx.createNewJSObject()
                val jsHeader = ctx.createNewJSObject()
                setHeader(ctx, res, jsHeader)
                ctx.setProperty(jsObject, "headers", jsHeader)
                if (req.getBuffer() == 0) ctx.setProperty(jsObject, "content", String(res.body.bytes(), Charset.forName(req.getCharset())))
                if (req.getBuffer() == 1) {
                    val array = ctx.createNewJSArray()
                    val bytes = res.body.bytes()
                    for (i in bytes.indices) array.set(bytes[i].toInt() and 0xFF, i)
                    ctx.setProperty(jsObject, "content", array)
                }
                if (req.getBuffer() == 2) ctx.setProperty(jsObject, "content", Base64.encodeToString(res.body.bytes(), Base64.DEFAULT or Base64.NO_WRAP))
                return jsObject
            } catch (e: Exception) {
                return error(ctx)
            }
        }

        @JvmStatic
        fun error(ctx: QuickJSContext): JSObject {
            val jsObject = ctx.createNewJSObject()
            val jsHeader = ctx.createNewJSObject()
            ctx.setProperty(jsObject, "headers", jsHeader)
            ctx.setProperty(jsObject, "content", "")
            return jsObject
        }

        private fun getRequest(url: String, req: Req, headers: Headers, tag: Any?): Request {
            return if (req.getMethod().equals("post", ignoreCase = true)) {
                Request.Builder().url(url).tag(tag).headers(headers).post(getPostBody(req, headers.get(HttpHeaders.CONTENT_TYPE))).build()
            } else if (req.getMethod().equals("header", ignoreCase = true)) {
                Request.Builder().url(url).tag(tag).headers(headers).head().build()
            } else {
                Request.Builder().url(url).tag(tag).headers(headers).get().build()
            }
        }

        private fun getPostBody(req: Req, contentType: String?): RequestBody {
            if (req.getData() != null && req.getPostType() == "json") return getJsonBody(req)
            if (req.getData() != null && req.getPostType() == "form") return getFormBody(req)
            if (req.getData() != null && req.getPostType() == "form-data") return getFormDataBody(req)
            if (req.getBody() != null && contentType != null) return req.getBody()!!.toRequestBody(contentType.toMediaTypeOrNull())
            return "".toRequestBody(null)
        }

        private fun getJsonBody(req: Req): RequestBody {
            return req.getData()!!.toString().toRequestBody("application/json".toMediaTypeOrNull())
        }

        private fun getFormBody(req: Req): RequestBody {
            val formBody = FormBody.Builder()
            val params = Json.toMap(req.getData())
            for ((key, value) in params) formBody.add(key, value)
            return formBody.build()
        }

        private fun getFormDataBody(req: Req): RequestBody {
            val boundary = "--dio-boundary-" + ThreadLocalRandom.current().nextInt(42949) + "" + ThreadLocalRandom.current().nextInt(67296)
            val builder = MultipartBody.Builder(boundary).setType(MultipartBody.FORM)
            val params = Json.toMap(req.getData())
            for ((key, value) in params) builder.addFormDataPart(key, value)
            return builder.build()
        }

        private fun setHeader(ctx: QuickJSContext, res: Response, obj: JSObject) {
            for (entry in res.headers.toMultimap().entries) {
                if (entry.value.size == 1) ctx.setProperty(obj, entry.key, entry.value[0])
                if (entry.value.size >= 2) ctx.setProperty(obj, entry.key, JSUtils<String>().toArray(ctx, entry.value))
            }
        }

        @JvmStatic
        fun cancelByTag(tag: Any?) {
            try {
                if (client != null) {
                    val target = tag!!
                    for (call in client!!.dispatcher.queuedCalls()) {
                        if (target == call.request().tag()) {
                            call.cancel()
                        }
                    }
                    for (call in client!!.dispatcher.runningCalls()) {
                        if (target == call.request().tag()) {
                            call.cancel()
                        }
                    }
                }
                cancelDefaultClient(tag)
            } catch (e: Exception) {
                LOG.d("Connect", "cancel tag failed")
            }
        }

        private fun cancelDefaultClient(tag: Any?) {
            val defaultClient = OkGoHelper.getDefaultClient()
            if (defaultClient == null || tag == null) return
            for (call in defaultClient.dispatcher.queuedCalls()) {
                if (tag == call.request().tag()) {
                    call.cancel()
                }
            }
            for (call in defaultClient.dispatcher.runningCalls()) {
                if (tag == call.request().tag()) {
                    call.cancel()
                }
            }
        }
    }
}
