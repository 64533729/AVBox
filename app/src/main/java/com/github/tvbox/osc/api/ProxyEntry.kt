package com.github.tvbox.osc.api

import android.text.TextUtils

import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.Proxy

import java.net.URLDecoder
import java.util.HashMap

/** /proxy 请求路由:按 do 类型分发到 jar/js/py 爬虫,直播源改走当前直播爬虫 */
class ProxyEntry(private val owner: ApiConfig, private val spiderLoader: SpiderLoader) {
    private var currentPlaySourceKey = ""

    fun proxyLocal(param: MutableMap<String, String>): Array<Any?>? {
        val source = getCurrentProxySource(param)
        val api = source.api!!

        val siteKey = param["siteKey"]
        val action = param["do"]

        val isJs = "js" == action
        val isPy = "py" == action
        val isLive = KV.get(HawkConfig.PLAYER_IS_LIVE, false)
        val isApiJs = api.contains(".js")
        val isApiPy = api.contains(".py")

        val canUseType3 = !TextUtils.isEmpty(siteKey)
                && source.type == 3
                && !isJs
                && !isPy
                && !isLive
                && !isApiJs
                && !isApiPy

        if (canUseType3) {
            try {
                val spider = owner.getCSP(source)

                // spider.proxy 单独兜底:它抛异常不应吞掉下面的 jar/direct 兜底路径
                var result: Array<Any?>? = null
                try {
                    result = spider.proxy(param)
                } catch (th: Throwable) {
                    LOG.e("echo-proxy-route: spider.proxy error, fallback | " + th)
                }
                if (result != null) return result
                LOG.e("echo-proxy-route: spider.proxy null, try proxyInvokeJar")

                result = spiderLoader.proxyInvokeJar(param)
                if (result != null) return result
                LOG.e("echo-proxy-route: proxyInvokeJar null, try proxyDirect")

                result = proxyDirect(param)
                if (result != null) return result
                LOG.e("echo-proxy-route: proxyDirect null, give up")

                return null
            } catch (th: Throwable) {
                LOG.e("echo-proxy-route: type3 route error | " + th + " | msg=" + th.message)
                return null
            }
        }

        if (isJs) {
            return spiderLoader.proxyInvokeJs(param)
        }

        if (isLive) {
            val liveApi = spiderLoader.currentLiveSpider ?: ""

            if (liveApi.contains(".py")) {
                return spiderLoader.proxyInvokePy(param, spiderLoader.currentLivePyKey)
            }
            if (liveApi.contains(".js")) {
                return spiderLoader.proxyInvokeJs(param)
            }
            return spiderLoader.proxyInvokeJar(param)
        }

        if (isPy) {
            return spiderLoader.proxyInvokePy(param, getCurrentPyKey())
        }

        if (isApiPy) {
            return spiderLoader.proxyInvokePy(param, getCurrentPyKey())
        }

        return spiderLoader.proxyInvokeJar(param)
    }

    private fun proxyDirect(param: MutableMap<String, String>): Array<Any?>? {
        try {
            var url = param["url"]
            if (TextUtils.isEmpty(url)) return null
            url = URLDecoder.decode(url!!, "UTF-8")
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            if (!DefaultConfig.isVideoFormat(url)) return null
            if (url.contains(".m3u8")) {
                param["url"] = url
                param["go"] = "live"
                param["type"] = "m3u8"
                return Proxy.itv(param)
            }
            return null
        } catch (th: Throwable) {
            LOG.e("echo-proxy direct fallback error: " + th.message)
            return null
        }
    }

    private fun getCurrentProxySource(param: MutableMap<String, String>): SourceBean {
        var siteKey = param["siteKey"]
        if (TextUtils.isEmpty(siteKey)) {
            siteKey = currentPlaySourceKey
            if (!TextUtils.isEmpty(siteKey)) param["siteKey"] = siteKey
        }
        val sourceBean = if (TextUtils.isEmpty(siteKey)) null else owner.getSource(siteKey)
        return sourceBean ?: owner.getHomeSourceBean()
    }

    fun setCurrentPlaySourceKey(sourceKey: String?) {
        currentPlaySourceKey = sourceKey ?: ""
    }

    private fun getCurrentPyKey(): String? {
        val sourceBean = getCurrentProxySource(HashMap<String, String>())
        if (sourceBean.api!!.contains(".py")) {
            if (sourceBean.key != spiderLoader.currentPyKey) {
                spiderLoader.currentPyKey = sourceBean.key
                spiderLoader.pySpider(sourceBean.key, sourceBean.api, sourceBean.ext)
            }
            return spiderLoader.currentPyKey
        }
        return spiderLoader.currentPyKey
    }
}
