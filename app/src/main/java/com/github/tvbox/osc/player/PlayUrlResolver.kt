package com.github.tvbox.osc.player

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.ParseBean
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.AdBlocker
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HeaderGuard
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.VideoParseRuler
import com.github.tvbox.osc.util.parser.SuperParse
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.HttpHeaders
import com.lzy.okgo.model.Response
import org.json.JSONException
import org.json.JSONObject
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 解析/嗅探调度:WebView 嗅探 + json/聚合/超级解析 + 代际闸门;宿主能力见 [Host]。
 *
 * 坑:代际必须"发起时捕获值 vs 当前值"比较;写成当前值自比较即恒真闸门(等于没装)。
 * 解析超时由本类 Handler 管,与宿主 timeoutHandler 各自独立。
 *
 * 坑2:解析线程池里碰视图一律走 `view.runOnUi` —— 非主线程写视图会让 ViewGroup.mChildren
 * 出 null 洞,下次 traversal 必崩。
 */
class PlayUrlResolver(private val host: Host) {

    interface Host {

        fun view(): PlaybackViewBridge?

        fun sourceBean(): SourceBean?

        fun webHeaderMap(): HashMap<String, String>?

        fun setWebHeaderMap(headers: HashMap<String, String>?)

        fun webUserAgent(): String?

        fun setWebUserAgent(userAgent: String?)

        /** 嗅探命中:与回调同帧,不校验代际 */
        fun playUrl(url: String, headers: HashMap<String, String>?)

        /** 解析产物:入口校验代际 */
        fun playUrl(gen: Int, url: String, headers: HashMap<String, String>?)
    }

    private val parseHandler: Handler = Handler(
        Looper.getMainLooper(),
        Handler.Callback { msg: Message ->
            if (msg.what == MSG_PARSE_TIMEOUT) {
                stopParse()
                host.view()?.showErrorWithRetry(str(R.string.player_error_sniff), false)
                return@Callback true
            }
            false
        }
    )

    // ==================== 代际(宿主入口也用它) ====================

    fun nextGen(): Int = parseGeneration.incrementAndGet()

    fun resetGen() {
        parseGeneration.set(0)
    }

    fun currentGen(): Int = parseGeneration.get()

    fun cancelParseTimeout() {
        parseHandler.removeMessages(MSG_PARSE_TIMEOUT)
    }

    fun hasFoundUrls(): Boolean = !loadFoundVideoUrls.isEmpty()

    fun consumeFoundUrl() {
        autoRetryFromLoadFoundVideoUrls()
    }

    // ==================== 字段与常量 ====================

    private var webUrl: String? = null
    private var parseFlag: String? = null

    /** 解析/嗅探代际:发起时捕获、起播前比对,不一致即丢弃(旧集地址不得拉起新播放)。
     *  stopParse 只能"不再新增",拦不住已在跑的爬虫/迟到 WebView 请求,故不可省;线程:主线程写、网络线程读 ⇒ AtomicInteger。 */
    private val parseGeneration = AtomicInteger(0)

    /** 嗅探 WebView(1×1 挂在页面内容视图上,视图来自 host.view()) */
    private var mSysWebView: WebView? = null

    /** 当前嗅探页所属代际(投递导航前赋值,网络线程读故 volatile;-1 = 无嗅探页):用于丢弃旧页在途请求 */
    @Volatile
    private var webSniffGeneration: Int = -1

    // ⚠️ 非 UI 线程且不保证串行:以下集合必须并发安全
    private val loadedUrls: MutableMap<String, Boolean> = ConcurrentHashMap()

    @Volatile
    private var loadFoundVideoUrls: Queue<String> = ConcurrentLinkedQueue()

    @Volatile
    private var loadFoundVideoUrlsHeader: MutableMap<String, HashMap<String, String>> = ConcurrentHashMap()
    private val loadFoundCount = AtomicInteger(0)
    private var parseThreadPool: ExecutorService? = null

    // ==================== 成员 ====================

    /** 按解析规则发起解析(直链/json/聚合/超级解析) */
    fun initParse(flag: String?, useParse: Boolean, playUrl: String, url: String) {
        parseFlag = flag
        webUrl = url
        var parseBean: ParseBean? = null
        host.view()?.showParse(useParse)
        if (useParse) {
            parseBean = ApiConfig.get().getDefaultParse()
        } else {
            if (playUrl.startsWith("json:")) {
                val bean = ParseBean()
                bean.type = 1
                bean.url = playUrl.substring(5)
                parseBean = bean
            } else if (playUrl.startsWith("parse:")) {
                val parseRedirect = playUrl.substring(6)
                for (pb in ApiConfig.get().parseBeanList) {
                    if (pb.name == parseRedirect) {
                        parseBean = pb
                        break
                    }
                }
            }
            if (parseBean == null) {
                val bean = ParseBean()
                bean.type = 0
                bean.url = playUrl
                parseBean = bean
            }
        }
        doParse(parseBean!!)
    }

    @Throws(JSONException::class)
    fun jsonParse(input: String?, json: String): JSONObject? {
        val jsonPlayData = JSONObject(json)
        val playData = jsonPlayData.optJSONObject("data") ?: jsonPlayData
        var url = playData.optString("url", jsonPlayData.optString("url", ""))
        if (url.startsWith("//")) {
            url = "http:$url"
        }
        var parse = false
        if (url.startsWith("video://")) {
            url = url.substring(8)
            parse = true
        }
        url = DefaultConfig.checkReplaceProxy(url)
        if (!url.startsWith("http") && !url.startsWith("data:application")) {
            return null
        }
        parse = parse || playData.optInt("parse", jsonPlayData.optInt("parse", 0)) == 1
        val headers = JSONObject()
        val headerMap = PlaybackController.extractHeaders(jsonPlayData)
        val dataHeaderMap = PlaybackController.extractHeaders(playData)
        if (headerMap != null) PlaybackController.putHeaders(headers, headerMap)
        if (dataHeaderMap != null) PlaybackController.putHeaders(headers, dataHeaderMap)
        val ua = playData.optString("user-agent", jsonPlayData.optString("user-agent", ""))
        if (ua.trim().isNotEmpty()) {
            headers.put("User-Agent", " $ua")
        }
        val referer = playData.optString("referer", jsonPlayData.optString("referer", ""))
        if (referer.trim().isNotEmpty()) {
            headers.put("Referer", " $referer")
        }
        val taskResult = JSONObject()
        taskResult.put("header", headers)
        taskResult.put("url", url)
        taskResult.put("parse", if (parse) 1 else 0)
        return taskResult
    }

    /** 停止解析/嗅探:取消解析超时、停 WebView、取消 OkGo 请求(含 M3U8 净化)、关线程池 */
    fun stopParse() {
        parseHandler.removeMessages(MSG_PARSE_TIMEOUT)
        stopLoadWebView(false)
        OkGo.getInstance().cancelTag("play")
        OkGo.getInstance().cancelTag("json_jx")
        // M3U8 净化在途请求也要撤:结果被代际丢弃后没人取消会一直持网到超时
        OkGo.getInstance().cancelTag("m3u8-1")
        OkGo.getInstance().cancelTag("m3u8-2")
        val pool = parseThreadPool
        if (pool != null) {
            try {
                pool.shutdown()
                parseThreadPool = null
            } catch (th: Throwable) {
                LOG.e("PlayUrlResolver", th)
            }
        }
    }

    /** 重置嗅探结果容器(整引用替换,不动旧对象) */
    fun initParseLoadFound() {
        loadFoundCount.set(0)
        loadFoundVideoUrls = ConcurrentLinkedQueue()
        loadFoundVideoUrlsHeader = ConcurrentHashMap()
    }

    /** 消费一个嗅探到的地址并起播(取到 null 即放弃:队列可能被并发消费/重置) */
    fun autoRetryFromLoadFoundVideoUrls() {
        val videoUrl = loadFoundVideoUrls.poll() ?: return
        val header = loadFoundVideoUrlsHeader[videoUrl]
        if (host.view() != null) host.playUrl(videoUrl, header)
    }

    /** 本轮解析是否仍有效(gen = 发起时捕获值;页面桥校验 M3U8 净化结果也用它)。
     *  逐请求调用故不打日志,需要日志的调用方自行打。 */
    fun isParseResultCurrent(gen: Int): Boolean = gen == parseGeneration.get()

    fun doParse(pb: ParseBean) {
        // 入口自增:作废上一轮解析;下面各回调统一捕获 gen
        val gen = parseGeneration.incrementAndGet()
        stopParse()
        initParseLoadFound()
        if (pb.type == 4) {
            parseMix(pb, true, gen)
        } else if (pb.type == 0) {
            host.view()?.showTip(str(R.string.player_sniffing_url), true, false)
            parseHandler.removeMessages(MSG_PARSE_TIMEOUT)
            parseHandler.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, PARSE_TIMEOUT_MS)
            if (pb.ext != null) {
                try {
                    val reqHeaders = HashMap<String, String>()
                    val jsonObject = JSONObject(pb.ext)
                    val headerMap = PlaybackController.extractHeaders(jsonObject)
                    if (headerMap != null) {
                        for (key in headerMap.keys) {
                            val value = headerMap[key]
                            // 解析器的 ext 头来自配置,非法字符会让 okhttp 构造请求时抛异常
                            if (!HeaderGuard.isSendable(key, value)) {
                                LOG.i("echo-ext-header-skip:$key")
                                continue
                            }
                            if (key.equals("user-agent", ignoreCase = true)) {
                                host.setWebUserAgent(value!!.trim())
                            } else {
                                reqHeaders[key] = value!!
                            }
                        }
                        if (reqHeaders.isNotEmpty()) host.setWebHeaderMap(reqHeaders)
                    }
                } catch (e: Throwable) {
                    LOG.e("PlayUrlResolver", e)
                }
            }
            loadWebView(pb.url + webUrl)
        } else if (pb.type == 1) { // json 解析
            host.view()?.showTip(str(R.string.player_resolving_url), true, false)
            val reqHeaders = HttpHeaders()
            try {
                val jsonObject = JSONObject(pb.ext)
                val headerMap = PlaybackController.extractHeaders(jsonObject)
                if (headerMap != null) {
                    for (key in headerMap.keys) {
                        if (!HeaderGuard.isSendable(key, headerMap[key])) {
                            LOG.i("echo-ext-header-skip:$key")
                            continue
                        }
                        reqHeaders.put(key, headerMap[key])
                    }
                }
            } catch (e: Throwable) {
                LOG.e("PlayUrlResolver", e)
            }
            val view = host.view()
            OkGo.get<String>(pb.url + (if (view == null) webUrl else view.encodeUrl(webUrl!!)))
                .tag("json_jx")
                .headers(reqHeaders)
                .execute(object : AbsCallback<String>() {
                    override fun convertResponse(response: okhttp3.Response): String {
                        return response.body.string()
                    }

                    override fun onSuccess(response: Response<String>) {
                        // 旧请求(切集/换源/换解析后)的结果不得再驱动播放
                        if (!isParseResultCurrent(gen)) return
                        val json = response.body()
                        try {
                            val rs = jsonParse(webUrl, json)!!
                            val headers = PlaybackController.extractHeaders(rs)
                            if (rs.optInt("parse", 0) == 1) {
                                host.setWebHeaderMap(headers)
                                if (headers != null) {
                                    host.setWebUserAgent(PlaybackController.headerValue(headers, "user-agent"))
                                    host.webUserAgent()?.let { host.setWebUserAgent(it.trim()) }
                                }
                                loadWebView(DefaultConfig.checkReplaceProxy(rs.getString("url")))
                            } else {
                                if (host.view() != null) host.playUrl(gen, rs.getString("url"), headers)
                            }
                        } catch (e: Throwable) {
                            LOG.e("PlayUrlResolver", e)
                            errorWithRetry(str(R.string.player_parse_error), false)
                        }
                    }

                    override fun onError(response: Response<String>) {
                        super.onError(response)
                        errorWithRetry(str(R.string.player_parse_error), false)
                    }
                })
        } else if (pb.type == 2) { // json 扩展
            host.view()?.showTip(str(R.string.player_resolving_url), true, false)
            val pool = Executors.newSingleThreadExecutor()
            parseThreadPool = pool
            val jxs = LinkedHashMap<String, String>()
            for (p in ApiConfig.get().parseBeanList) {
                if (p.type == 1) {
                    val name = p.name
                    val mixUrl = p.mixUrl()
                    // 空名/null 混流 URL 在 Java 侧本就不可达:主键查表与请求构造都拿不到,等价跳过
                    if (name != null && mixUrl != null) jxs[name] = mixUrl
                }
            }
            pool.execute {
                // jsonExt 是阻塞爬虫(可跑数十秒):结果到达时先校验本轮
                if (!isParseResultCurrent(gen)) return@execute
                val rs = ApiConfig.get().jsonExt(pb.url!!, jxs, webUrl!!)
                if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
                    val bridge = host.view()
                    if (isParseResultCurrent(gen) && bridge != null) {
                        bridge.runOnUi { bridge.showTip(str(R.string.player_parse_error), false, true) }
                    }
                } else {
                    val headers = PlaybackController.extractHeaders(rs)
                    if (rs.has("jxFrom") && host.view() != null) {
                        val jxFrom = rs.optString("jxFrom")
                        host.view()!!.runOnUi { host.view()!!.toast(str(R.string.player_parse_from, jxFrom)) }
                    }
                    val parseWV = rs.optInt("parse", 0) == 1
                    if (parseWV) {
                        val wvUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""))
                        loadUrl(gen, wvUrl)
                    } else {
                        if (host.view() != null) host.playUrl(gen, rs.optString("url", ""), headers)
                    }
                }
            }
        } else if (pb.type == 3) { // json 聚合
            parseMix(pb, false, gen)
        }
    }

    private fun errorWithRetry(err: String, finish: Boolean) {
        host.view()?.showErrorWithRetry(err, finish)
    }

    /** 聚合解析(type 3/4):超级解析 = 嗅探与 json 并发;普通聚合 = jsonExtMix */
    private fun parseMix(pb: ParseBean, isSuper: Boolean, gen: Int) {
        host.view()?.showTip(str(R.string.player_resolving_url), true, false)
        val pool = Executors.newSingleThreadExecutor()
        parseThreadPool = pool
        val jxs = LinkedHashMap<String, HashMap<String, String>>()
        val jsonJxs = LinkedHashMap<String, String>()
        var extendName = ""
        for (p in ApiConfig.get().parseBeanList) {
            val data = HashMap<String, String>()
            // 缺键与 null 值在下游都是 get() 读到 null,故按"不可达等价"只放非空项(不可空 map 值类型)
            p.url?.let { data["url"] = it }
            if (p.url == pb.url) {
                extendName = p.name ?: ""
            }
            data["type"] = p.type.toString()
            p.ext?.let { data["ext"] = it }
            val name = p.name
            if (name != null) {
                jxs[name] = data
                if (p.type == 1) {
                    p.mixUrl()?.let { jsonJxs[name] = it }
                }
            }
        }
        val finalExtendName = extendName
        // 解析器按调用传递,不得改回静态字段跨线程读
        val parseTargets = SuperParse.buildTargets(jxs, parseFlag + "123")
        pool.execute {
            // 阻塞爬虫(可跑数十秒):结果到达时先校验本轮
            if (!isParseResultCurrent(gen)) return@execute
            if (isSuper) {
                val rs = SuperParse.parse(jxs, parseFlag + "123", webUrl!!, parseTargets)
                if (!rs.has("url") || rs.optString("url").isEmpty()) {
                    if (isParseResultCurrent(gen) && host.view() != null) {
                        val bridge = host.view()!!
                        bridge.runOnUi { bridge.showTip(str(R.string.player_parse_error), false, true) }
                    }
                } else {
                    if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                        if (rs.has("ua")) {
                            host.setWebUserAgent(rs.optString("ua").trim())
                        }
                        if (host.view() != null) {
                            val bridge = host.view()!!
                            bridge.runOnUi { bridge.showTip(str(R.string.player_super_parsing), true, false) }
                        }
                        val mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""))
                        if (host.view() != null) {
                            host.view()!!.runOnUi {
                                // 排队期可能已切集:旧页不得替换当前嗅探页、更不得带着旧超时
                                if (!isParseResultCurrent(gen)) return@runOnUi
                                stopParse()
                                parseHandler.removeMessages(MSG_PARSE_TIMEOUT)
                                parseHandler.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, PARSE_TIMEOUT_MS)
                                loadWebView(mixParseUrl)
                            }
                        }
                        pool.execute {
                            val res = SuperParse.doJsonJx(parseTargets.jsonJx, webUrl!!)
                            rsJsonJX(gen, res, true)
                        }
                    } else {
                        rsJsonJX(gen, rs, false)
                    }
                }
            } else {
                val rs = ApiConfig.get().jsonExtMix(parseFlag + "111", pb.url!!, finalExtendName, jxs, webUrl!!)
                if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
                    if (isParseResultCurrent(gen) && host.view() != null) {
                        val bridge = host.view()!!
                        bridge.runOnUi { bridge.showTip(str(R.string.player_parse_error), false, true) }
                    }
                } else {
                    if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                        if (rs.has("ua")) {
                            host.setWebUserAgent(rs.optString("ua").trim())
                        }
                        val mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""))
                        if (host.view() != null) {
                            host.view()!!.runOnUi {
                                // 同上:排队期可能已切集
                                if (!isParseResultCurrent(gen)) return@runOnUi
                                stopParse()
                                host.view()!!.showTip(str(R.string.player_sniffing_url), true, false)
                                parseHandler.removeMessages(MSG_PARSE_TIMEOUT)
                                parseHandler.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, PARSE_TIMEOUT_MS)
                                loadWebView(mixParseUrl)
                            }
                        }
                    } else {
                        rsJsonJX(gen, rs, false)
                    }
                }
            }
        }
    }

    private fun rsJsonJX(gen: Int, rs: JSONObject?, isSuper: Boolean) {
        // 先校验本轮:否则切集后,上一轮遗留的 jsonJx 回调会关掉新集的嗅探页 / 起播旧地址
        if (!isParseResultCurrent(gen)) return
        if (isSuper) {
            if (rs == null || !rs.has("url")) return
            stopLoadWebView(false)
        }
        val headers = PlaybackController.extractHeaders(rs)
        if (rs!!.has("jxFrom") && host.view() != null) {
            val jxFrom = rs.optString("jxFrom")
            host.view()!!.runOnUi { host.view()!!.toast(str(R.string.player_parse_from, jxFrom)) }
        }
        if (host.view() != null) host.playUrl(gen, rs.optString("url", ""), headers)
    }

    fun loadWebView(url: String?) {
        if (mSysWebView == null) {
            initWebView()
        }
        loadUrl(url)
    }

    fun initWebView() {
        if (host.view() == null) return
        mSysWebView = host.view()!!.newSniffWebView()
        // 无页面(仅引擎/服务)时没有内容视图:取流前的嗅探只有页面在时才有意义
        val web = mSysWebView ?: return
        configWebViewSys(web)
    }

    fun loadUrl(url: String?) {
        if (host.view() == null || !host.view()!!.isPageAlive()) return
        // 本次导航所属代际(在投递前赋值,不能放 runnable 内:否则旧页请求与新一轮导航之间有窗口期)
        webSniffGeneration = parseGeneration.get()
        host.view()!!.runOnUi {
            mSysWebView?.let { web ->
                web.stopLoading()
                host.webUserAgent()?.let { web.settings.userAgentString = it }
                val headerMap = host.webHeaderMap()
                if (headerMap != null) {
                    web.loadUrl(url!!, headerMap)
                } else {
                    web.loadUrl(url!!)
                }
            }
        }
    }

    /** 带代际的嗅探页导航:后台线程发起,排队期若已切集不得把新集嗅探页换掉 */
    private fun loadUrl(gen: Int, url: String) {
        if (host.view() == null || !host.view()!!.isPageAlive()) return
        webSniffGeneration = gen
        host.view()!!.runOnUi {
            if (!isParseResultCurrent(gen)) return@runOnUi
            loadUrl(url)
        }
    }

    /** 当前请求是否属于正在嗅探的页面(旧页迟到请求一律丢弃,否则会把上一集地址塞进新队列)。
     *  ⚠️ 必须比"页面所属代际";写成 `isParseResultCurrent(parseGeneration.get())` 是恒真自比较,闸门等于没装。 */
    private fun isSniffRequestOfCurrentRound(): Boolean {
        return webSniffGeneration >= 0 && webSniffGeneration == parseGeneration.get()
    }

    fun stopLoadWebView(destroy: Boolean) {
        if (host.view() == null) return
        host.view()!!.runOnUi {
            mSysWebView?.let { web ->
                web.stopLoading()
                web.loadUrl("about:blank")
                if (destroy) {
                    web.clearCache(true)
                    web.removeAllViews()
                    web.destroy()
                    mSysWebView = null
                    webSniffGeneration = -1
                }
            }
        }
    }

    fun checkVideoFormat(url: String): Boolean {
        return try {
            if (url.contains("url=http") || url.contains(".html")) {
                return false
            }
            val source = host.sourceBean()
            if (source != null && source.type == 3) {
                val sp = ApiConfig.get().getCSP(source)
                if (sp != null && sp.manualVideoCheck()) {
                    return sp.isVideoFormat(url)
                }
            }
            VideoParseRuler.checkIsVideoForParse(webUrl!!, url)
        } catch (e: Exception) {
            false
        }
    }

    private fun configWebViewSys(webView: WebView?) {
        if (webView == null) {
            return
        }
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.clearFocus()
        webView.overScrollMode = View.OVER_SCROLL_ALWAYS
        if (host.view() == null || !host.view()!!.isPageAlive()) return
        host.view()!!.attachSniffWebView(webView)
        val settings = webView.settings
        settings.setNeedInitialFocus(false)
        settings.allowContentAccess = true
        settings.allowFileAccess = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.allowFileAccessFromFileURLs = true
        settings.databaseEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptEnabled = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.mediaPlaybackRequiresUserGesture = false
        }
        settings.blockNetworkImage = true
        settings.useWideViewPort = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.setSupportZoom(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.defaultTextEncodingName = "utf-8"
        settings.userAgentString = webView.settings.userAgentString

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = false

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean = true

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean = true

            override fun onJsPrompt(
                view: WebView,
                url: String,
                message: String,
                defaultValue: String,
                result: JsPromptResult,
            ): Boolean = true
        }
        val mSysWebClient = SysWebClient()
        webView.webViewClient = mSysWebClient
        webView.setBackgroundColor(Color.BLACK)
    }

    private inner class SysWebClient : WebViewClient() {

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(webView: WebView, sslErrorHandler: SslErrorHandler, sslError: SslError) {
            sslErrorHandler.proceed()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            LOG.i("echo-onPageFinished url:$url")
            if (url != "about:blank" && host.view() != null) {
                host.view()!!.evaluateScript(url, view)
            }
        }

        fun checkIsVideo(url0: String, headers: HashMap<String, String>): WebResourceResponse? {
            var url = url0
            if (url.endsWith("/favicon.ico")) {
                if (url.startsWith("http://127.0.0.1")) {
                    return WebResourceResponse("image/x-icon", "UTF-8", null)
                }
                return null
            }

            // 旧页迟到请求不得进入新一轮结果队列
            if (!isSniffRequestOfCurrentRound()) {
                return null
            }
            val isFilter = VideoParseRuler.isFilter(webUrl!!, url)
            if (isFilter) {
                LOG.i("shouldInterceptLoadRequest filter:$url")
                return null
            }

            val ad: Boolean
            if (!loadedUrls.containsKey(url)) {
                ad = AdBlocker.isAd(url)
                loadedUrls[url] = ad
            } else {
                ad = loadedUrls[url] == true
            }

            if (!ad) {
                if (checkVideoFormat(url)) {
                    loadFoundVideoUrls.add(url)
                    loadFoundVideoUrlsHeader[url] = headers
                    LOG.i("echo-loadFoundVideoUrl:$url")
                    if (loadFoundCount.incrementAndGet() == 1) {
                        stopLoadWebView(false)
                        SuperParse.stopJsonJx()
                        val found = loadFoundVideoUrls.poll()
                        // ⚠️ 队列可能已被并发消费或被新一轮重置(字段 volatile):
                        // poll 为 null 时若继续走 getCookie/playUrl 会 NPE
                        if (found == null) return null
                        parseHandler.removeMessages(MSG_PARSE_TIMEOUT)
                        val cookie = CookieManager.getInstance().getCookie(found)
                        if (!TextUtils.isEmpty(cookie)) headers["Cookie"] = " $cookie"//携带cookie
                        if (host.view() != null) host.playUrl(found, headers)
                    }
                }
            }

            return if (ad || loadFoundCount.get() > 0) AdBlocker.createEmptyResource() else null
        }

        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? = null

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            LOG.i("echo-shouldInterceptRequest url:$url")
            val webHeaders = HashMap<String, String>()
            val hds = request.requestHeaders
            if (hds != null && hds.keys.size > 0) {
                for (k in hds.keys) {
                    if (k.equals("user-agent", ignoreCase = true)
                        || k.equals("referer", ignoreCase = true)
                        || k.equals("origin", ignoreCase = true)
                    ) {
                        webHeaders[k] = " " + hds[k]
                    }
                }
            }
            return checkIsVideo(url, webHeaders)
        }
    }

    companion object {
        private const val MSG_PARSE_TIMEOUT = 100
        private const val PARSE_TIMEOUT_MS = 20 * 1000L

        /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
        @JvmStatic
        private fun str(resId: Int, vararg args: Any?): String {
            val app = App.getInstance() ?: return ""
            return LanguageManager.localized(app).getString(resId, *args)
        }
    }
}
