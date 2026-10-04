package com.github.tvbox.osc.api

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64

import com.github.catvod.crawler.JarLoader
import com.github.catvod.crawler.JsLoader
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.python.IPyLoader
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.BootGuard
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.FileUtils
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.RegexUtils
import com.google.gson.JsonObject

import org.json.JSONObject

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.HashSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 爬虫装载:jar / js / py 三个加载器 + jar 下载与重试链路 + 点播/直播的 spider 获取。
 * 源列表、代理分发时的"当前源"、KV 读写留在 ApiConfig。
 */
class SpiderLoader {

    /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
    private fun str(resId: Int, vararg args: Any?): String {
        val app = App.getInstance()
        return if (app == null) "" else LanguageManager.localized(app).getString(resId, *args)
    }

    private val jarLoader = JarLoader()
    private val jsLoader = JsLoader()
    private val pyLoader: IPyLoader = com.github.catvod.crawler.pyLoader()
    private val jarLoadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val danmuSearchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val warmedSearchSpiderKeys: MutableSet<String> = HashSet()
    private val userAgent = "okhttp/3.15"

    /** 配置级 jar(接口 JSON 顶层 "spider") */
    var spider: String? = null
    /** 直播配置级 jar(直播接口 JSON 顶层 "spider") */
    private var liveSpider: String = ""
    /** 当前生效的直播 spider 地址:py/js 为接口地址,否则为 jar 地址 */
    var currentLiveSpider: String? = null
        private set
    var currentPyKey: String? = ""
    var currentLivePyKey: String? = ""
        private set
    /** 接口 JSON 顶层 "jarCache":允许直接用一周内的 jar 缓存 */
    private var jarCache: String = "true"

    // ---------- 配置注入 ----------

    // setSpider/getSpider/setCurrentPyKey/getCurrentPyKey 由对应属性自身的访问器提供(Java 调用点不变)

    fun setLiveSpider(liveSpider: String) {
        this.liveSpider = liveSpider
    }

    fun setJarCache(jarCache: String) {
        this.jarCache = jarCache
    }

    // ---------- jar 下载与装载 ----------

    fun loadJar(useCache: Boolean, spider: String, callback: ApiConfig.LoadConfigCallback) {
        loadJar(useCache, spider, callback, 0)
    }

    private interface JarLoadCallback {
        fun complete(success: Boolean)
    }

    private interface JarDownloadCallback {
        fun complete(file: File?, error: String?)
    }

    private fun loadJarAsync(file: File?, callback: JarLoadCallback) {
        jarLoadExecutor.execute(Runnable {
            // 启动看门狗(2026-09-21):这里是所有 jar 装载的唯一收口(缓存命中与下载成功都走它),
            // 所以只需要在这里记一次"正在加载谁"—— 爬虫在自己的线程上闪退时,我们靠这个标记
            // 知道是哪个源把应用崩掉的(详见 BootGuard 注释里的自锁场景)。
            if (file != null) BootGuard.onJarLoadStart(file.absolutePath)
            var success = false
            try {
                success = file != null && file.exists() && jarLoader.load(file.absolutePath)
            } catch (th: Throwable) {
                LOG.e("echo---jar Loader threw exception: " + th.message)
            }
            // 装载成功**不**清计数(2026-09-21):爬虫的 <clinit> 跑在自己的线程上,
            // 这里报成功之后 28ms 它才崩 —— 早清等于擦掉唯一证据。改由 BootGuard
            // 在"连续存活满 STABLE_RUN_MS(10 分钟)"后清(那时才真的算稳定源)。
            if (success) BootGuard.scheduleStableRunReset()
            val result = success
            mainHandler.post(Runnable {
                callback.complete(result)
            })
        })
    }

    private fun downloadJarAsync(url: String, isJarInImg: Boolean, cache: File, callback: JarDownloadCallback) {
        jarLoadExecutor.execute(Runnable {
            var result: File? = null
            var error: String? = ""
            var response: okhttp3.Response? = null
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null
            val temp = File(cache.absolutePath + ".tmp")
            try {
                val cacheDir = cache.parentFile
                if (cacheDir != null && !cacheDir.exists()) cacheDir.mkdirs()
                if (temp.exists()) temp.delete()
                val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", userAgent)
                        .build()
                var client: okhttp3.OkHttpClient? = OkGoHelper.getDefaultClient()
                if (client == null) client = com.github.catvod.net.OkHttp.client()
                response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    error = "HTTP " + response.code
                } else if (response.body == null) {
                    error = "empty body"
                } else if (isJarInImg) {
                    val respData = response.body.string()
                    LOG.i("echo---jar Response: " + respData)
                    val imgJar = getImgJar(respData)
                    if (imgJar == null || imgJar.size == 0) {
                        error = "empty img jar"
                    } else {
                        val output = FileOutputStream(temp)
                        outputStream = output
                        output.write(imgJar)
                        output.flush()
                        closeQuietly(output)
                        outputStream = null
                        result = replaceCache(temp, cache)
                    }
                } else {
                    val input = response.body.byteStream()
                    inputStream = input
                    val output = FileOutputStream(temp)
                    outputStream = output
                    val buffer = ByteArray(16384)
                    var bytesRead = input.read(buffer)
                    while (bytesRead != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesRead = input.read(buffer)
                    }
                    output.flush()
                    closeQuietly(output)
                    outputStream = null
                    result = replaceCache(temp, cache)
                }
            } catch (th: Throwable) {
                error = th.message
            } finally {
                closeQuietly(inputStream)
                closeQuietly(outputStream)
                if (response != null) closeQuietly(response.body)
                if (result == null && temp.exists()) temp.delete()
            }
            val finalResult = result
            val finalError = error
            mainHandler.post(Runnable {
                callback.complete(finalResult, finalError)
            })
        })
    }

    private fun replaceCache(temp: File, cache: File): File {
        if (cache.exists() && !cache.delete()) {
            LOG.i("echo---delete old jar cache failed:" + cache.absolutePath)
        }
        if (!temp.renameTo(cache)) {
            FileUtils.copyFile(temp, cache)
            temp.delete()
        }
        return cache
    }

    private fun getImgJar(body: String): ByteArray? {
        val pattern = RegexUtils.getPattern("[A-Za-z0-9]{8}\\*\\*")
        val matcher = pattern.matcher(body)
        var body = body
        if (matcher.find()) {
            body = body.substring(body.indexOf(matcher.group()) + 10)
            return Base64.decode(body, Base64.DEFAULT)
        }
        return "".toByteArray(Charset.defaultCharset())
    }

    private fun loadJar(useCache: Boolean, spider: String, callback: ApiConfig.LoadConfigCallback, retryCount: Int) {
        val urls = RegexUtils.getPattern(";md5;").split(spider)
        var jarUrl = urls[0]
        val md5 = if (urls.size > 1) urls[1].trim { it <= ' ' } else ""
        val cache = File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp/" + MD5.string2MD5(jarUrl) + ".jar")

        if (!md5.isEmpty() || useCache) {
            if (cache.exists() && (useCache || MD5.getFileMd5(cache).equals(md5, ignoreCase = true))) {
                if (cache.exists()) {
                    loadJarAsync(cache, object : JarLoadCallback {
                        override fun complete(success: Boolean) {
                            if (success) {
                                callback.success()
                            } else {
                                callback.error(str(R.string.toast_jar_load_failed))
                            }
                        }
                    })
                    return
                }
                if (jarLoader.load(cache.absolutePath)) {
                    callback.success()
                } else {
                    callback.error(str(R.string.toast_jar_load_failed))
                }
                return
            }
        } else {
            if (jarCache.toBoolean() && cache.exists() && !FileUtils.isWeekAgo(cache)) {
                LOG.i("echo-load jar jarCache:" + jarUrl)
                if (cache.exists()) {
                    loadJarAsync(cache, object : JarLoadCallback {
                        override fun complete(success: Boolean) {
                            if (success) {
                                callback.success()
                            } else {
                                loadJar(false, spider, callback, retryCount)
                            }
                        }
                    })
                    return
                }
                if (jarLoader.load(cache.absolutePath)) {
                    callback.success()
                    return
                }
            }
        }

        val isJarInImg = jarUrl.startsWith("img+")
        jarUrl = jarUrl.replace("img+", "")
        LOG.i("echo-load jar start:" + jarUrl)
        val requestUrl = jarUrl
        downloadJarAsync(requestUrl, isJarInImg, cache, object : JarDownloadCallback {
            private fun retryLoad(reason: String): Boolean {
                if (retryCount >= LOAD_JAR_MAX_RETRY) return false
                if (cache.exists() && !cache.delete()) {
                    LOG.i("echo---delete bad jar cache failed:" + cache.absolutePath)
                }
                LOG.i("echo---retry load jar reason:" + reason + " url:" + requestUrl + " retry:" + (retryCount + 1))
                loadJar(false, spider, callback, retryCount + 1)
                return true
            }

            override fun complete(file: File?, error: String?) {
                if (file != null && file.exists()) {
                    loadJarAsync(file, object : JarLoadCallback {
                        override fun complete(success: Boolean) {
                            if (success) {
                                LOG.i("echo---load-jar-success")
                                callback.success()
                            } else {
                                LOG.e("echo---jar Loader returned false")
                                if (retryLoad("loader_false")) return
                                callback.error(str(R.string.toast_jar_load_failed))
                            }
                        }
                    })
                    return
                }
                if (!TextUtils.isEmpty(error)) {
                    LOG.i("echo---jar Request failed: " + error)
                }
                if (cache.exists()) {
                    loadJarAsync(cache, object : JarLoadCallback {
                        override fun complete(success: Boolean) {
                            if (success) {
                                callback.success()
                            } else {
                                if (retryLoad("request_error")) return
                                callback.error(str(R.string.toast_network_error))
                            }
                        }
                    })
                    return
                }
                if (retryLoad("request_error")) return
                callback.error(str(R.string.toast_network_error))
            }
        })
    }

    // ---------- spider 获取(点播) ----------

    fun getCSP(sourceBean: SourceBean): Spider {
        if (sourceBean.api!!.endsWith(".js") || sourceBean.api!!.contains(".js?")) {
            currentPyKey = ""
            return jsLoader.getSpider(sourceBean.key, sourceBean.api, sourceBean.ext, sourceBean.jar)
        } else if (sourceBean.api!!.contains(".py")) {
            currentPyKey = sourceBean.key
            pyLoader.setRecentPyKey(currentPyKey)
            return pyLoader.getSpider(sourceBean.key, sourceBean.api, sourceBean.ext)
        } else {
            currentPyKey = ""
            return jarLoader.getSpider(sourceBean.key, sourceBean.api, sourceBean.ext, sourceBean.jar)
        }
    }

    /** 按 key 装载 py spider(代理分发时按"当前源"重新装载) */
    fun pySpider(key: String?, api: String?, ext: String?): Spider {
        val result = pyLoader.getSpider(key, api, ext)
        pyLoader.setRecentPyKey(key)
        return result
    }

    // getCurrentPyKey/setCurrentPyKey 由 currentPyKey 属性自身的访问器提供

    // ---------- spider 获取(直播) ----------

    fun setLiveJar(liveJar: String) {
        if (liveJar.contains(".py")) {
            currentLivePyKey = MD5.string2MD5(liveJar)
            pyLoader.getSpider(currentLivePyKey, liveJar, "")
            pyLoader.setRecentPyKey(currentLivePyKey)
        } else if (liveJar.contains(".js")) {
            jsLoader.getSpider(MD5.string2MD5(liveJar), liveJar, "", "")
        } else {
            val jarUrl = if (!liveJar.isEmpty()) liveJar else liveSpider
            jarLoader.setRecentJarKey(MD5.string2MD5(jarUrl))
        }
        currentLiveSpider = liveJar
    }

    fun getPyCSP(url: String): Spider {
        currentLivePyKey = MD5.string2MD5(url)
        currentLiveSpider = url
        return pyLoader.getSpider(currentLivePyKey, url, "")
    }

    fun getJsCSP(url: String): Spider {
        currentLiveSpider = url
        return jsLoader.getSpider(MD5.string2MD5(url), url, "", "")
    }

    fun getLiveCSP(url: String): Spider {
        return if (url.contains(".js")) getJsCSP(url) else getPyCSP(url)
    }

    /** 直播接口(type=3)的 spider 装载:py/js 走接口地址,否则按 jar 地址装载 */
    fun loadLiveSpider(api: String, jarUrl: String, livesOBJ: JsonObject) {
        LOG.i("echo-liveApi1" + api)
        if (api.contains(".py")) {
            LOG.i("echo-pyLoader.getSpider")
            val ext = liveExt(livesOBJ)
            currentLivePyKey = MD5.string2MD5(api)
            currentLiveSpider = api
            pyLoader.getSpider(currentLivePyKey, api, ext)
        } else if (api.contains(".js")) {
            LOG.i("echo-jsLoader.getSpider")
            val ext = liveExt(livesOBJ)
            currentLiveSpider = api
            jsLoader.getSpider(MD5.string2MD5(api), api, ext, jarUrl)
        }
        if (!jarUrl.isEmpty() && !isLiveSpiderApi(api)) {
            jarLoader.loadLiveJar(jarUrl)
            if (TextUtils.isEmpty(currentLiveSpider)) {
                currentLiveSpider = jarUrl
            }
        } else if (!liveSpider.isEmpty() && !isLiveSpiderApi(api)) {
            jarLoader.loadLiveJar(liveSpider)
            if (TextUtils.isEmpty(currentLiveSpider)) {
                currentLiveSpider = liveSpider
            }
        }
    }

    private fun liveExt(livesOBJ: JsonObject): String {
        if (livesOBJ.has("ext") && (livesOBJ.get("ext").isJsonObject || livesOBJ.get("ext").isJsonArray)) {
            return livesOBJ.get("ext").toString()
        }
        return DefaultConfig.safeJsonString(livesOBJ, "ext", "")
    }

    /** 进入新一轮直播配置解析时清空"当前生效"标记 */
    fun resetCurrentLiveSpider() {
        currentLiveSpider = ""
        currentLivePyKey = ""
    }

    // getCurrentLiveSpider/getCurrentLivePyKey 由上面属性自身的访问器提供

    // ---------- 预热 ----------

    /** 登记"已预热";返回 false 表示这次之前已经登记过(调用方跳过) */
    fun markWarmed(warmKey: String): Boolean {
        synchronized(warmedSearchSpiderKeys) {
            return warmedSearchSpiderKeys.add(warmKey)
        }
    }

    // ---------- 代理与扩展 ----------

    fun proxyInvokeJar(param: Map<String, String>): Array<Any?>? {
        return jarLoader.proxyInvoke(param)
    }

    fun proxyInvokeJs(param: Map<String, String>): Array<Any?>? {
        return jsLoader.proxyInvoke(param)
    }

    fun proxyInvokePy(param: Map<String, String>, pyKey: String?): Array<Any?>? {
        return pyLoader.proxyInvoke(param, pyKey)
    }

    fun jsonExt(key: String, jxs: LinkedHashMap<String, String>, url: String): JSONObject? {
        return jarLoader.jsonExt(key, jxs, url)
    }

    fun jsonExtMix(flag: String, key: String, name: String, jxs: LinkedHashMap<String, HashMap<String, String>>, url: String): JSONObject? {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url)
    }

    fun searchDanmuUi(name: String, episode: String, longClick: Boolean) {
        danmuSearchExecutor.execute(Runnable {
            try {
                jarLoader.searchDanmuUi(name, episode, longClick)
            } catch (th: Throwable) {
                LOG.e("ApiConfig searchDanmuUi error: " + th.message)
                LOG.e("SpiderLoader", th)
            }
        })
    }

    fun hasDanmuSearchUi(): Boolean {
        return jarLoader.hasDanmuSearchUi()
    }

    // ---------- 清理 ----------

    fun clearJarLoader() {
        jarLoader.clear()
    }

    fun clearLoader() {
        jarLoader.clear()
        pyLoader.clear()
        jsLoader.clear()
        synchronized(warmedSearchSpiderKeys) {
            warmedSearchSpiderKeys.clear()
        }
    }

    fun clearSpiderCache() {
        currentPyKey = ""
        currentLivePyKey = ""
        currentLiveSpider = ""
        clearLoader()
    }

    companion object {
        private const val LOAD_JAR_MAX_RETRY = 1

        /** 关闭失败即忽略;ApiConfig 拉配置时复用同一份实现 */
        @JvmStatic
        fun closeQuietly(closeable: Closeable?) {
            try {
                if (closeable != null) closeable.close()
            } catch (ignored: Throwable) {
                LOG.d("ApiConfig", "close failed")
            }
        }

        @JvmStatic
        fun isLiveSpiderApi(api: String): Boolean {
            return api.contains(".py") || api.contains(".js")
        }
    }
}
