package com.github.tvbox.osc.util

import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.ProxyRule
import com.github.tvbox.osc.player.danmu.Parser
import com.github.tvbox.osc.util.net.OkProxySelector
import com.github.tvbox.osc.util.net.ProxyAuthenticator
import com.github.tvbox.osc.util.SSL.SSLSocketFactoryCompat
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.lzy.okgo.OkGo
import com.lzy.okgo.https.HttpsUtils
import com.lzy.okgo.interceptor.HttpLoggingInterceptor
import com.lzy.okgo.model.HttpHeaders

import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

import java.util.concurrent.TimeUnit
import java.util.logging.Level

import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper


object OkGoHelper {
    const val DEFAULT_MILLISECONDS = 10000L      //默认的超时时间

    // 内置doh json
    /** App 内置 DoH(2026-09-12 起不再只是"兜底":始终置于 [getDohConfigArray] 列表**最前**,接口项去重后追加在后) */
    private const val dnsConfigJson = ("["
            + "{\"name\": \"腾讯\", \"url\": \"https://doh.pub/dns-query\"}," // i18n: keep(DNS 配置数据)
            + "{\"name\": \"阿里\", \"url\": \"https://dns.alidns.com/dns-query\"}," // i18n: keep(DNS 配置数据)
            + "{\"name\": \"360\", \"url\": \"https://doh.360.cn/dns-query\"}"
            + "]")

    @JvmField
    var ItvClient: OkHttpClient? = null

    private var proxySelector: OkProxySelector? = null
    private var proxyAuthenticator: ProxyAuthenticator? = null

    @JvmStatic
    @Synchronized
    fun proxySelector(): OkProxySelector {
        var selector = proxySelector
        if (selector == null) {
            selector = OkProxySelector()
            proxySelector = selector
        }
        return selector
    }

    @JvmStatic
    @Synchronized
    fun proxyAuthenticator(): ProxyAuthenticator {
        var authenticator = proxyAuthenticator
        if (authenticator == null) {
            authenticator = ProxyAuthenticator(proxySelector())
            proxyAuthenticator = authenticator
        }
        return authenticator
    }

    @JvmStatic
    @Synchronized
    fun setProxyList(proxyRules: List<ProxyRule>?) {
        proxySelector().clear()
        if (proxyRules != null && !proxyRules.isEmpty()) proxySelector().addAll(proxyRules)
        com.github.catvod.net.OkHttp.reset()
    }

    private fun initExoOkHttpClient() {
        val base = getDefaultClient()
        val builder = if (base != null) base.newBuilder() else OkHttpClient.Builder()
        val loggingInterceptor = HttpLoggingInterceptor("OkExoPlayer")

        loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE)
        loggingInterceptor.setColorLevel(Level.OFF)
        builder.addInterceptor(loggingInterceptor)

        builder.retryOnConnectionFailure(true)
        builder.followRedirects(true)
        builder.followSslRedirects(true)
        builder.proxySelector(proxySelector())
        builder.proxyAuthenticator(proxyAuthenticator())


        try {
            setOkHttpSsl(builder)
        } catch (th: Throwable) {
            LOG.e("OkGoHelper", th)
        }

//        builder.dns(dnsOverHttps);
        builder.dns(CustomDns())
        ItvClient = builder.build()

        ExoMediaSourceHelper.getInstance(AppContextHolder.context()!!).setOkClient(ItvClient)
    }

    // DNS 解析在 OkHttp 线程读,init/reloadDns 在主线程写
    @JvmField
    @Volatile
    var dnsOverHttps: DnsOverHttps? = null

    // ⚠️ 配置解析在 IO 协程写、设置页在主线程 mapIndexed 遍历:必须整体替换引用,不能原地 clear/add
    @JvmField
    @Volatile
    var dnsHttpsList: List<String> = Collections.emptyList()

    @JvmField
    var is_doh: Boolean = false

    // 配置解析可能在 IO 线程写、DNS 解析在 OkHttp 线程读,需 volatile 保证可见性
    @JvmField
    @Volatile
    var myHosts: Map<String, String>? = null

    /**
     * 合并后的 DoH 配置数组(**唯一数据源**,2026-09-12 用户定稿):
     * App 内置项(腾讯/阿里/360)在前,接口配置的 doh 项按 url 去重后追加在后;
     * 接口未提供 doh 时就只有内置那三条(与原兜底行为一致)。
     * ⚠️ 显示列表 [dnsHttpsList] 与取值 [getDohUrl] / [initDnsOverHttps] 都按下标映射本数组
     * (下标 = 弹窗位置 - 1),三者必须同源 —— 任何一处单独解析 JSON 都会导致选中的项取到别人的 URL。
     * ⚠️ 本次把内置项提到最前会使"已选中的下标"指向另一台 DoH(名称与 URL 仍一致,不会串号),
     * 把 DoH 关着(下标 0)的用户不受影响。
     */
    @JvmStatic
    fun getDohConfigArray(): JsonArray {
        val merged = JsonArray()
        val keys = HashSet<String>()
        try {
            appendDohItems(merged, keys, JsonParser.parseString(dnsConfigJson).asJsonArray)
        } catch (e: Exception) {
            LOG.e("OkGoHelper", e)
        }
        appendDohItems(merged, keys, parseDohArray(KV.get(HawkConfig.DOH_JSON, "")))
        return merged
    }

    /** 接口传来的 doh 字段可能为空或格式异常;异常时返回 null(退化为只用内置项,不影响启动) */
    private fun parseDohArray(json: String?): JsonArray? {
        if (json == null || json.isEmpty()) return null
        try {
            return JsonParser.parseString(json).asJsonArray
        } catch (e: Exception) {
            LOG.e("OkGoHelper", e)
            return null
        }
    }

    /** 按 url 去重追加(url 缺失时退回 name);同 url 以先加入者为准 = 内置项优先(接口同 url 项被跳过) */
    private fun appendDohItems(target: JsonArray, keys: MutableSet<String>, source: JsonArray?) {
        if (source == null) return
        for (i in 0 until source.size()) {
            val element = source.get(i)
            if (element == null || !element.isJsonObject) continue
            val item = element.asJsonObject
            val key = if (item.has("url")) item.get("url").asString
                    else (if (item.has("name")) item.get("name").asString else null)
            if (key == null || !keys.add(key)) continue
            target.add(item)
        }
    }

    @JvmStatic
    fun getDohUrl(type: Int): String {
        val jsonArray = getDohConfigArray()
        if (type >= 1 && type <= jsonArray.size()) {
            val dnsConfig = jsonArray.get(type - 1).asJsonObject
            return if (dnsConfig.has("url")) dnsConfig.get("url").asString else ""
        }
        return ""
    }

    @JvmStatic
    fun applyDohConfig(dohJson: String?) {
        val pinned = getDohUrl(KV.get(HawkConfig.DOH_URL, 0))
        KV.put(HawkConfig.DOH_JSON, dohJson)
        val merged = getDohConfigArray()

        val list = ArrayList<String>()
        list.add("关闭") // i18n: keep(DNS 选项索引锚点,显示由设置页映射资源)
        for (i in 0 until merged.size()) {
            val dnsConfig = merged.get(i).asJsonObject
            val name = if (dnsConfig.has("name")) dnsConfig.get("name").asString else "Unknown Name"
            list.add(name)
        }
        dnsHttpsList = list

        val index = indexOfDohUrl(merged, pinned)
        if (index >= 0) {
            KV.put(HawkConfig.DOH_URL, index + 1)
        } else if (KV.get(HawkConfig.DOH_URL, 0) > merged.size()) {
            KV.put(HawkConfig.DOH_URL, 0)
        }
        refreshHosts()
    }

    @JvmStatic
    fun indexOfDohUrl(merged: JsonArray?, url: String?): Int {
        if (merged == null || url == null || url.isEmpty()) return -1
        for (i in 0 until merged.size()) {
            val element = merged.get(i)
            if (element == null || !element.isJsonObject) continue
            val item = element.asJsonObject
            val key = if (item.has("url")) item.get("url").asString
                    else (if (item.has("name")) item.get("name").asString else null)
            if (url == key) return i
        }
        return -1
    }

    /** 刷新 hosts 快照:CustomDns.lookup 只在 myHosts 为 null(首次刷新前)时才回落读 ApiConfig,写完必须显式刷新 */
    @JvmStatic
    fun refreshHosts() {
        myHosts = ApiConfig.get().getMyHost()
    }

    private fun DohIps(ips: JsonArray?): List<InetAddress> {
        val inetAddresses = ArrayList<InetAddress>()
        if (ips != null) {
            for (j in 0 until ips.size()) {
                try {
                    val inetAddress = InetAddress.getByName(ips.get(j).asString)
                    inetAddresses.add(inetAddress)  // 添加到 List 中
                } catch (e: Exception) {
                    LOG.e("OkGoHelper", e)  // 处理无效的 IP 字符串
                }
            }
        }
        return inetAddresses
    }

    private fun initDnsOverHttps() {
        var dohSelector = KV.get(HawkConfig.DOH_URL, 0)
        var ips: JsonArray? = null
        try {
            val list = ArrayList<String>()
            list.add("关闭") // i18n: keep(DNS 选项索引锚点,显示由设置页映射资源)
            val jsonArray = getDohConfigArray()
            if (dohSelector > jsonArray.size()) {
                KV.put(HawkConfig.DOH_URL, 0)
                dohSelector = 0
            }
            for (i in 0 until jsonArray.size()) {
                val dnsConfig = jsonArray.get(i).asJsonObject
                val name = if (dnsConfig.has("name")) dnsConfig.get("name").asString else "Unknown Name"
                list.add(name)
                if (dohSelector == (i + 1)) ips = if (dnsConfig.has("ips")) dnsConfig.getAsJsonArray("ips") else null
            }
            dnsHttpsList = list
        } catch (e: Exception) {
            LOG.e("OkGoHelper", e)
        }

        val builder = OkHttpClient.Builder()
        builder.proxySelector(proxySelector())
        builder.proxyAuthenticator(proxyAuthenticator())
        val loggingInterceptor = HttpLoggingInterceptor("OkExoPlayer")
        loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE)
        loggingInterceptor.setColorLevel(Level.OFF)
        builder.addInterceptor(loggingInterceptor)
        try {
            setOkHttpSsl(builder)
        } catch (th: Throwable) {
            LOG.e("OkGoHelper", th)
        }
        builder.cache(Cache(File(AppContextHolder.context()!!.cacheDir.absolutePath, "dohcache"), 100L * 1024 * 1024))
        val dohClient = builder.build()
        val dohUrl = getDohUrl(KV.get(HawkConfig.DOH_URL, 0))
//        if (!dohUrl.isEmpty()) is_doh = true;
//        LOG.i("echo-initDnsOverHttps dohUrl:"+dohUrl);
//        LOG.i("echo-initDnsOverHttps ips:"+ips);
        // 官方 okhttp-dnsoverhttps 的 Builder 要求 url 非空 ⇒ "关闭" 时直接置空实例(调用方已判空回落到 Dns.SYSTEM)
        dnsOverHttps = if (dohUrl.isEmpty()) null
                else DnsOverHttps.Builder().client(dohClient).url(dohUrl.toHttpUrl()).bootstrapDnsHosts(if (ips != null && dohUrl != "https://doh.pub/dns-query") DohIps(ips) else null).build()
    }

    // 自定义 DNS 解析器
    private class CustomDns : Dns {
        private var map: ConcurrentHashMap<String, List<InetAddress>>? = null
        private val excludeIps = "2409:8087:6c02:14:100::14,2409:8087:6c02:14:100::18,39.134.108.253,39.134.108.245"

        // 接收外部注入的 DoH 实例
        constructor()

        @Throws(UnknownHostException::class)
        override fun lookup(hostname: String): List<InetAddress> {
            val originalHost = hostname
            var hosts = myHosts
            if (hosts == null) hosts = ApiConfig.get().getMyHost()
            var hostname = hostname
            if (hosts != null && !hosts.isEmpty() && hosts.containsKey(hostname)) {
                hostname = hosts.get(hostname)!!
            }
            assert(hostname != null)
            if (isValidIpAddress(hostname)) {
                return Collections.singletonList(InetAddress.getByName(hostname))
            } else {
                val dns = if (dnsOverHttps != null) dnsOverHttps!! else Dns.SYSTEM
                return dns.lookup(hostname)
            }
        }

        @Synchronized
        @Throws(UnknownHostException::class)
        fun mapHosts(hosts: Map<String, String>) {
            val m = ConcurrentHashMap<String, List<InetAddress>>()
            map = m
            for (entry in hosts.entries) {
                val key = entry.key
                val value = entry.value
                if (isValidIpAddress(value)) {
                    m.put(key, Collections.singletonList(InetAddress.getByName(value)))
                } else {
                    m.put(key, getAllByName(value))
                }
            }
        }

        private fun getAllByName(host: String): List<InetAddress> {
            try {
                // 获取所有与主机名关联的 IP 地址
                val allAddresses = InetAddress.getAllByName(host)
                if (excludeIps.isEmpty()) return Arrays.asList(*allAddresses)
                // 创建一个列表用于存储有效的 IP 地址
                val validAddresses = ArrayList<InetAddress>()
                val excludeIpsSet = HashSet<String>()
                for (ip in RegexUtils.getPattern(",").split(excludeIps)) {
                    excludeIpsSet.add(ip.trim { it <= ' ' })  // 添加到集合，去除多余的空格
                }
                for (address in allAddresses) {
                    if (!excludeIpsSet.contains(address.hostAddress)) {
                        validAddresses.add(address)
                    }
                }
                return validAddresses
            } catch (e: Exception) {
                return ArrayList()
            }
        }

        //简单判断减少开销
        private fun isValidIpAddress(str: String): Boolean {
            if (str.indexOf('.') > 0) return isValidIPv4(str)
            return str.indexOf(':') > 0
        }

        private fun isValidIPv4(str: String): Boolean {
            val parts = RegexUtils.getPattern("\\.").split(str)
            if (parts.size != 4) return false
            for (part in parts) {
                try {
                    Integer.parseInt(part)
                } catch (e: NumberFormatException) {
                    return false
                }
            }
            return true
        }
    }

    // 爬虫/JS 请求在后台线程读,init/reloadDns 在主线程写
    @Volatile
    private var defaultClient: OkHttpClient? = null

    @Volatile
    private var noRedirectClient: OkHttpClient? = null

    @JvmStatic
    fun getDefaultClient(): OkHttpClient? {
        return defaultClient
    }

    @JvmStatic
    fun getNoRedirectClient(): OkHttpClient? {
        return noRedirectClient
    }

    @JvmStatic
    fun getItvClient(): OkHttpClient? {
        return ItvClient
    }

    @JvmStatic
    fun init() {
        initDnsOverHttps()

        val builder = OkHttpClient.Builder()
        val loggingInterceptor = HttpLoggingInterceptor("OkGo")

        loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE)
        loggingInterceptor.setColorLevel(Level.OFF)

        //builder.retryOnConnectionFailure(false);

        builder.addInterceptor(loggingInterceptor)

        builder.readTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
        builder.writeTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
        builder.connectTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)

        builder.dns(CustomDns())
        builder.proxySelector(proxySelector())
        builder.proxyAuthenticator(proxyAuthenticator())
        try {
            setOkHttpSsl(builder)
        } catch (th: Throwable) {
            LOG.e("OkGoHelper", th)
        }

        HttpHeaders.setUserAgent("okhttp/" + OkHttp.VERSION)

        val okHttpClient = builder.build()
        // 原在 initPicasso 内设置(非 Picasso 专属):提升每主机并发上限
        okHttpClient.dispatcher.maxRequestsPerHost = 10
        OkGo.getInstance().setOkHttpClient(okHttpClient)

        defaultClient = okHttpClient

        builder.followRedirects(false)
        builder.followSslRedirects(false)
        noRedirectClient = builder.build()

        initExoOkHttpClient()
    }

    @JvmStatic
    @Synchronized
    fun reloadDns() {
        initDnsOverHttps()

        val builder = OkHttpClient.Builder()
        val loggingInterceptor = HttpLoggingInterceptor("OkGo")

        loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE)
        loggingInterceptor.setColorLevel(Level.OFF)

        builder.addInterceptor(loggingInterceptor)

        builder.readTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
        builder.writeTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
        builder.connectTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)

        builder.dns(CustomDns())
        builder.proxySelector(proxySelector())
        builder.proxyAuthenticator(proxyAuthenticator())
        try {
            setOkHttpSsl(builder)
        } catch (th: Throwable) {
            LOG.e("OkGoHelper", th)
        }

        HttpHeaders.setUserAgent("okhttp/" + OkHttp.VERSION)

        val okHttpClient = builder.build()
        // 与 init 同步:漏掉这行会让每主机并发上限退回默认 5
        okHttpClient.dispatcher.maxRequestsPerHost = 10
        OkGo.getInstance().setOkHttpClient(okHttpClient)

        defaultClient = okHttpClient

        builder.followRedirects(false)
        builder.followSslRedirects(false)
        noRedirectClient = builder.build()

        initExoOkHttpClient()
        Parser.resetHttpClient()
        com.github.catvod.net.OkHttp.resetClient()
    }

    @Synchronized
    private fun setOkHttpSsl(builder: OkHttpClient.Builder) {
        try {
            // 自定义一个信任所有证书的TrustManager，添加SSLSocketFactory的时候要用到
            val trustAllCert: X509TrustManager =
                    object : X509TrustManager {
                        @Throws(CertificateException::class)
                        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                        }

                        @Throws(CertificateException::class)
                        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                        }

                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                            return arrayOf()
                        }
                    }
            val sslSocketFactory: SSLSocketFactory = SSLSocketFactoryCompat(trustAllCert)
            builder.sslSocketFactory(sslSocketFactory, trustAllCert)
            builder.hostnameVerifier(HttpsUtils.UnSafeHostnameVerifier)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
