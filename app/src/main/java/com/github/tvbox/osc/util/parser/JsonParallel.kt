package com.github.tvbox.osc.util.parser
import android.util.Base64
import com.github.catvod.crawler.SpiderDebug
import com.github.tvbox.osc.util.HeaderGuard
import com.github.tvbox.osc.util.LOG
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 并发解析，直到获得第一个结果
 */
object JsonParallel {

    /**
     * 当前在途解析任务；client/executor/futures 全部任务局部，并发调用互不覆盖。
     * 新一轮 parse 启动时自动取消上一个在途任务。
     */
    @Volatile
    private var currentTask: Task? = null

    private class Task {
        val client = OkHttpClient()
        val executorService: ExecutorService = Executors.newFixedThreadPool(5)
        val futures: MutableList<Future<JSONObject?>> = ArrayList()

        fun cancel() {
            try {
                client.dispatcher.cancelAll()
            } catch (ignored: Throwable) {
                LOG.d("JsonParallel", "cancel dispatcher calls failed")
            }
            for (future in futures) {
                try {
                    future.cancel(true)
                } catch (ignored: Throwable) {
                    LOG.d("JsonParallel", "cancel in-flight future failed")
                }
            }
            futures.clear()
            executorService.shutdownNow()
        }
    }

    @JvmStatic
    fun parse(jx: LinkedHashMap<String, String>?, url: String): JSONObject {
        val task = Task()
        cancelTasks() // 取消上一个在途任务，避免多轮解析并存
        currentTask = task
        try {
            if (jx != null && jx.size > 0) {
                // 使用线程池并发处理任务
                val completionService: CompletionService<JSONObject?> = ExecutorCompletionService(task.executorService)

                // 遍历所有的解析配置
                for (jxName in jx.keys) {
                    val parseUrl = jx[jxName]
                    task.futures.add(completionService.submit(Callable<JSONObject?> {
                        try {
                            // 获取请求头，并从中取出实际url
                            val reqHeaders = JsonParallel.getReqHeader(parseUrl!!)
                            val realUrl = reqHeaders["url"]
                            reqHeaders.remove("url")
                            val headers = Headers.Builder().apply { reqHeaders.forEach { (name, value) -> add(name, value) } }.build()
                            val request = Request.Builder()
                                .url(realUrl + url)
                                .headers(headers)
                                .tag("ParseTag")
                                .build()

                            val call = task.client.newCall(request)
                            val response = call.execute()
                            val json = response.body!!.string()

                            val taskResult = Utils.jsonParse(url, json)
                            taskResult!!.put("jxFrom", jxName)
                            taskResult
                        } catch (th: Throwable) {
                            // 输出日志
                            null
                        }
                    }))
                }

                var pTaskResult: JSONObject? = null
                for (i in 0 until task.futures.size) {
                    val completed = completionService.take()
                    try {
                        pTaskResult = completed.get()
                        if (pTaskResult != null) {
                            for (future in task.futures) {
                                try {
                                    future.cancel(true)
                                } catch (t: Throwable) {
                                    SpiderDebug.log(t)
                                }
                            }
                            task.futures.clear()
                            break
                        }
                    } catch (th: Throwable) {
                        SpiderDebug.log(th)
                    }
                }
                if (pTaskResult != null)
                    return pTaskResult
            }
        } catch (th: Throwable) {
            SpiderDebug.log(th)
        } finally {
            task.cancel()
            if (currentTask === task) currentTask = null
        }
        return JSONObject()
    }

    @JvmStatic
    fun cancelTasks() {
        val task = currentTask
        if (task != null) {
            task.cancel()
        }
    }
    @JvmStatic
    fun getReqHeader(url: String): HashMap<String, String> {
        val reqHeaders = HashMap<String, String>()
        reqHeaders["url"] = url
        if (url.contains("cat_ext")) {
            try {
                val start = url.indexOf("cat_ext=")
                // cat_ext 可能是末参数，此时 indexOf("&") 返回 -1，取串尾兜底
                var end = url.indexOf("&", start)
                if (end == -1) end = url.length
                var ext = url.substring(start + 8, end)
                ext = String(Base64.decode(ext, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charset.defaultCharset())
                var newUrl = url.substring(0, start)
                if (end < url.length) newUrl += url.substring(end + 1) // 跳过后续参数前的分隔符
                if (newUrl.endsWith("&") || newUrl.endsWith("?")) {
                    newUrl = newUrl.substring(0, newUrl.length - 1) // cat_ext 是唯一 query 参数时去掉残留分隔符
                }
                val jsonObject = JSONObject(ext)
                if (jsonObject.has("header")) {
                    val headerJson = jsonObject.optJSONObject("header")
                    if (headerJson != null) {
                        val keys = headerJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = headerJson.optString(key, "")
                            // 聚合解析器的 ext 头来自配置:非法字符会让 Headers.of 抛 IAE,该解析器静默失效
                            if (!HeaderGuard.isSendable(key, value)) {
                                LOG.d("JsonParallel", "drop illegal header: " + key)
                                continue
                            }
                            reqHeaders[key] = value
                        }
                    }
                }
                reqHeaders["url"] = newUrl
            } catch (th: Throwable) {
                LOG.d("JsonParallel", "cat_ext param decode failed, ignore extended headers")
            }
        }
        return reqHeaders
    }
}
