package com.github.tvbox.osc.util

import coil3.network.NetworkFetcher
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Call

/**
 * Coil 3.6 起将 OkHttpNetworkFetcher 标记为 @Deprecated(HIDDEN),Kotlin 源码不可见;
 * 通过 Java 桥把自定义 Call.Factory(携带海报请求头拦截器)注册进 Coil 组件。
 */
object CoilBridge {

    @JvmStatic
    fun okhttpFetcher(callFactory: () -> Call.Factory): NetworkFetcher.Factory {
        return OkHttpNetworkFetcherFactory(callFactory)
    }
}
