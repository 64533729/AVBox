package com.github.catvod.crawler

import android.util.Log

import com.github.catvod.crawler.js.JsSpider
import com.github.tvbox.osc.util.AppContextHolder
import com.github.tvbox.osc.util.FileUtils
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.RegexUtils
import com.lzy.okgo.OkGo

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

import dalvik.system.DexClassLoader
import com.whl.quickjs.wrapper.QuickJSContext

class JsLoader {

    //当前的Js爬虫key
    @Volatile
    private var recentKey: String = ""

    @Synchronized
    fun clear() {
        for (spider in spiders.values) {
            spider.cancelByTag()
            spider.destroy()
        }
        spiders.clear()
        classes.clear()
        recentKey = ""
    }

    private fun loadClassLoader(jar: String, key: String): Boolean {
        var success = false
        var classInit: Class<*>? = null
        try {
            val cacheDir = File(AppContextHolder.context()!!.cacheDir.absolutePath + "/catvod_jsapi")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val classLoader = DexClassLoader(jar, cacheDir.absolutePath, null, AppContextHolder.context()!!.classLoader)
            var count = 0
            do {
                try {
                    try {
                        val clz = classLoader.loadClass("com.github.catvod.js.Function")
                        classInit = clz
                        clz.getDeclaredConstructor(QuickJSContext::class.java)
                        Log.i("JSLoader", "echo-load_com.github.catvod.js.Function")
                    } catch (ignored: Throwable) {
                        val clz = classLoader.loadClass("com.github.catvod.js.Method")
                        classInit = clz
                        clz.getDeclaredConstructor(QuickJSContext::class.java)
                        Log.i("JSLoader", "echo-load_com.github.catvod.js.Method")
                    }
                    if (classInit != null) {
                        Log.i("JSLoader", "echo-自定义jsapi代码加载成功!")
                        success = true
                        break
                    }
                    Thread.sleep(200)
                } catch (th: Throwable) {
                    LOG.e("JsLoader", th)
                }
                count++
            } while (count < 5)

            if (success) {
                classes[key] = classInit!!
            }
        } catch (th: Throwable) {
            LOG.e("JsLoader", th)
        }
        return success
    }

    private fun loadJarInternal(jar: String, md5: String, key: String): Class<*>? {
        if (classes.containsKey(key)) {
            Log.i("JSLoader", "echo-loadJarInternal cached")
            return classes[key]
        }
        val cache = File(AppContextHolder.context()!!.filesDir.absolutePath + "/csp/" + key + ".jar")
        try {
            // BugReview #15:csp 父目录只有全局 jar 下载路径会创建;仅含站点级 jar 时目录
            // 不存在,new FileOutputStream(cache) 抛 FileNotFoundException,js 源全变 SpiderNull
            val parent = cache.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
        } catch (ignored: Throwable) {
            LOG.d("JsLoader", "create csp dir failed")
        }
        if (!md5.isEmpty()) {
            if (cache.exists() && MD5.getFileMd5(cache).equals(md5, ignoreCase = true)) {
                loadClassLoader(cache.absolutePath, key)
                return classes[key]
            }
        } else {
            if (cache.exists() && !FileUtils.isWeekAgo(cache)) {
                if (loadClassLoader(cache.absolutePath, key)) {
                    return classes[key]
                }
            }
        }
        try {
            val response = OkGo.get<File>(jar).execute()
            val inputStream = response.body.byteStream()
            val outputStream = FileOutputStream(cache)
            try {
                val buffer = ByteArray(2048)
                var length = 0
                while (inputStream.read(buffer).also { length = it } > 0) {
                    outputStream.write(buffer, 0, length)
                }
            } finally {
                try {
                    inputStream.close()
                    outputStream.close()
                } catch (e: Exception) {
                    LOG.e("JsLoader", e)
                }
            }
            loadClassLoader(cache.absolutePath, key)
            return classes[key]
        } catch (e: Throwable) {
            LOG.e("JsLoader", e)
        }
        return null
    }

    @Synchronized
    fun getSpider(key: String?, api: String?, ext: String?, jar: String?): Spider {
        recentKey = key!!
        if (spiders.containsKey(key)) {
            Log.i("JSLoader", "echo-getSpider cached " + key)
            return spiders.getValue(key)
        }
        var classLoader: Class<*>? = null
        if (!jar!!.isEmpty()) {
            val urls = RegexUtils.getPattern(";md5;").split(jar)
            val jarUrl = urls[0]
            val jarKey = MD5.string2MD5(jarUrl)!!
            val jarMd5 = if (urls.size > 1) urls[1].trim() else ""
            classLoader = loadJarInternal(jarUrl, jarMd5, jarKey)
        }
        // BugReview #19:sp 声明放 try 外,init 抛异常时 JsSpider 已创建 QuickJSContext +
        // 单线程 executor,不 destroy 会泄漏 native runtime 与线程(构造失败场景由
        // JsSpider 构造函数自行兜底,此时 sp 为 null)
        var sp: Spider? = null
        try {
            Log.i("JSLoader", "echo-getSpider load")
            val created = JsSpider(key, api!!, classLoader)
            sp = created
            created.siteKey = key
            created.init(AppContextHolder.context(), ext)
            spiders[key] = created
            return created
        } catch (th: Throwable) {
            LOG.i("echo-getSpider-error " + th.message)
            if (sp != null) {
                try {
                    sp!!.destroy()
                } catch (ignored: Throwable) {
                    LOG.d("JsLoader", "destroy spider failed")
                }
            }
        }
        return SpiderNull()
    }

    fun proxyInvoke(params: Map<String, String>?): Array<Any?>? {
        try {
            val proxyFun = spiders[recentKey]
            if (proxyFun != null) {
                return proxyFun.proxyLocal(params)
            }
        } catch (th: Throwable) {
            LOG.e("JsLoader", "proxy invoke failed", th)
        }
        return null
    }

    companion object {

        private val spiders = ConcurrentHashMap<String, Spider>()
        private val classes = ConcurrentHashMap<String, Class<*>>()

        @JvmStatic
        fun destroy() {
            for (spider in spiders.values) {
                spider.cancelByTag()
                spider.destroy()
            }
        }

        @JvmStatic
        fun stopAll() {
            for (spider in spiders.values) {
                spider.cancelByTag()
            }
        }
    }
}
