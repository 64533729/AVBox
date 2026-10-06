package com.github.catvod.crawler

import android.content.Context

import com.github.catvod.net.OkHttp

import org.json.JSONObject

import okhttp3.Dns
import okhttp3.OkHttpClient

open class Spider {

    @JvmField
    var siteKey: String? = null

    open fun init(context: Context?) {
        mContext = context
    }

    open fun init(context: Context?, extend: String?) {
        init(context)
    }

    open fun initApi(api: SpiderApi) {
    }

    open fun homeContent(filter: Boolean): String? {
        return ""
    }

    open fun homeVideoContent(): String? {
        return ""
    }

    open fun categoryContent(tid: String?, pg: String, filter: Boolean, extend: HashMap<String, String>?): String? {
        return ""
    }

    open fun detailContent(ids: List<String>?): String? {
        return ""
    }

    open fun searchContent(key: String?, quick: Boolean): String? {
        return ""
    }

    open fun searchContent(key: String?, quick: Boolean, pg: String?): String? {
        return searchContent(key, quick)
    }

    open fun playerContent(flag: String?, id: String, vipFlags: List<String>?): String? {
        return ""
    }

    open fun isVideoFormat(url: String?): Boolean {
        return false
    }

    open fun manualVideoCheck(): Boolean {
        return false
    }

    open fun liveContent(url: String?): String? {
        return ""
    }

    open fun cancelByTag() {

    }

    open fun destroy() {}

    open fun proxyLocal(params: Map<String, String>?): Array<Any?>? {
        return null
    }

    open fun proxy(params: Map<String, String>?): Array<Any?>? {
        return proxyLocal(params)
    }

    open fun action(action: String): String? {
        return null
    }

    companion object {

        @JvmField
        var empty: JSONObject = JSONObject()

        @JvmField
        protected var mContext: Context? = null

        @JvmStatic
        fun safeDns(): Dns {
            return OkHttp.dns()
        }

        @JvmStatic
        fun client(): OkHttpClient {
            return OkHttp.client()
        }
    }
}
