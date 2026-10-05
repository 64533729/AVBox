package com.github.tvbox.osc.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.PlayerHelper
import com.github.tvbox.osc.util.Preconnect
import com.github.tvbox.osc.util.WatchProgressStore
import com.github.tvbox.osc.util.thunder.Jianpian
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 下一集预载的编排:目标评估时机(正片稳定/缓冲让路/缓冲结束补枪)、结果取用与冷却期都在这里。
 *
 * <p>归 `player` 而不是 `ui.player`:它由引擎创建、随引擎存活(页面只是喂快照),
 * 且不依赖任何页面类型 —— 放在 ui 包会让 `player` 反向依赖 UI。
 *
 * <p>移植口径 = 纯语言迁移(Java → Kotlin),逐行等价。几处 Kotlin 形态差异:
 * ① 嵌套类 `Snapshot`/`CachedEntry` 用 Kotlin 嵌套类(默认即 Java 的 static nested);
 * ② Java 的 `Runnable`/lambda 改成 SAM lambda,`postDelayed(r, d)` 保持同一入口;
 * ③ Java 的 `.equals(...)` 判等改成 Kotlin `==`(接收者非空时语义一致,且对可空右值更安全);
 * ④ `instanceof` 判类型改成 `is`(局部量,故有 smart cast);
 * ⑤ `AppGraph.cacheRepository` 是 Kotlin **属性**(不是 `getCacheRepository()`);
 * ⑥ `PlayerHelper.extractPlayHeaders` 返回**可空** `HashMap?`,故 `headers` 全程按可空传递。
 */
class PreloadCoordinator(
    /**
     * 取流入口。**可空**:调用点(PlaybackPreload)先 `ensureFetch()` 再读,理论上仍可能为空,
     * 旧 Java 靠平台类型放行、到真正取流时才 NPE —— 这里用可空 + 使用点 `!!` 复刻同一语义,
     * 而不是在构造期就抛(那会把失败点提前,属行为变更)。
     */
    private val sourceViewModel: SourceViewModel?,
) {

    /** 下一个预载目标快照(由页面组装后喂进来) */
    class Snapshot(
        @JvmField val context: Context,
        @JvmField val sourceKey: String,
        /** 可空:调用点传 `VodInfo.playFlag`(Kotlin 侧可空);仅用于比较与 noAd 判据,无需非空 */
        @JvmField val playFlag: String?,
        /** 可空:调用点传 `scheduler.progressKey()`;仅用于比较与赋值给 gaveUpKey */
        @JvmField val currentKey: String?,
        @JvmField val nextKey: String,
        /** 可空:调用点传 `VodSeries.url`;`isJpUrl` 要求非空,故在那一处用 !! 复刻 Java 语义 */
        @JvmField val nextUrl: String?,
        @JvmField val nextSubtitleKey: String,
        @JvmField val startSkipMs: Long,
        @JvmField val exoKernel: Boolean,
    )

    private class CachedEntry(val info: JSONObject, val at: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val evaluatePending = AtomicBoolean(false)

    private var requestToken: String? = null
    private var gaveUpKey: String? = null
    private var preloadedKey: String? = null
    private var activeSnapshot: Snapshot? = null
    private var bufferingCooldownUntil = 0L
    private var replayReadyPending = false

    /** 预解析直链缓存池:键 = progressKey(含源/片/线路/集名),消费即删;TTL 与容量见 PreloadCachePolicy */
    private val cache = LinkedHashMap<String, CachedEntry>()

    /** 缓冲持续到阈值才执行的让路:取消预载并清数据(短暂缓冲不执行,见 onMainPlayerBuffering) */
    private val bufferingYield = Runnable {
        LOG.i("echo-preload-yield: sustained buffering")
        PreloadManagerHolder.clearAll()
        preloadedKey = null
        bufferingCooldownUntil = System.currentTimeMillis() + BUFFERING_COOLDOWN_MS
    }

    fun scheduleEvaluate(snapshot: Snapshot?) {
        if (snapshot == null || !PreloadManagerHolder.enabled()) {
            LOG.i("echo-preload-skip: " + if (snapshot == null) "no next episode" else "switch off")
            return
        }
        // 已恢复播放/缓冲结束:撤销待执行的让路(短暂缓冲不打断预载,已下数据与在途下载都保留)
        handler.removeCallbacks(bufferingYield)
        postEvaluate(snapshot, EVALUATE_DELAY_MS)
    }

    private fun postEvaluate(snapshot: Snapshot, delayMs: Long) {
        if (!evaluatePending.compareAndSet(false, true)) return
        handler.postDelayed({
            evaluatePending.set(false)
            evaluate(snapshot)
        }, delayMs)
    }

    fun invalidate() {
        handler.removeCallbacksAndMessages(null)
        evaluatePending.set(false)
        replayReadyPending = false
        requestToken = null
        activeSnapshot = null
    }

    fun dropPreloadData() {
        preloadedKey = null
        PreloadManagerHolder.clearAll()
    }

    fun onMainPlayerBuffering() {
        if (!PreloadManagerHolder.enabled()) return
        bufferingCooldownUntil = System.currentTimeMillis() + BUFFERING_COOLDOWN_MS
        replayReadyPending = true
        // 先不动预载:拖动/瞬断造成的短暂缓冲结束后数据仍在(evaluate 会重放就绪提示);
        // 持续缓冲才真正让路(取消 + 清数据),避免弱网下预载与正片抢带宽
        handler.removeCallbacks(bufferingYield)
        handler.postDelayed(bufferingYield, BUFFERING_YIELD_MS)
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        evaluatePending.set(false)
        replayReadyPending = false
        requestToken = null
        activeSnapshot = null
        clearCache()
        PreloadManagerHolder.release()
    }

    private fun evaluate(snapshot: Snapshot) {
        if (!PreloadManagerHolder.enabled()) return
        if (!snapshot.exoKernel) {
            LOG.i("echo-preload-skip: non-exo kernel")
            return
        }
        // 拖动/缓冲结束后:目标没变且已完成 → 立即重放「下一集已就绪」(数据未失效,不重下)
        if (replayReadyPending) {
            replayReadyPending = false
            if (snapshot.nextKey == preloadedKey) {
                PreloadManagerHolder.replayReadyIfCompleted()
            }
        }
        val cooldownRemain = bufferingCooldownUntil - System.currentTimeMillis()
        if (cooldownRemain > 0) {
            LOG.i("echo-preload-skip: buffering cooldown, retry in " + cooldownRemain + "ms")
            postEvaluate(snapshot, cooldownRemain)
            return
        }
        if (snapshot.nextKey == snapshot.currentKey) return
        if (snapshot.currentKey == gaveUpKey) return
        if (snapshot.nextKey == preloadedKey) {
            LOG.i("echo-preload-skip: already preloaded")
            return
        }
        if (preloadedKey != null) {
            dropPreloadData()
        }
        if (snapshot.nextKey == requestToken) {
            LOG.i("echo-preload-skip: resolving in-flight")
            return
        }
        if (Jianpian.isJpUrl(snapshot.nextUrl!!)) {
            gaveUp(snapshot)
            return
        }
        activeSnapshot = snapshot
        requestToken = snapshot.nextKey + PRELOAD_KEY_SUFFIX
        LOG.i("echo-preload-resolve: " + snapshot.nextUrl)
        sourceViewModel!!.getPlayForPreload(
            snapshot.sourceKey,
            snapshot.playFlag,
            snapshot.nextKey + PRELOAD_KEY_SUFFIX,
            snapshot.nextUrl,
            snapshot.nextSubtitleKey + PRELOAD_KEY_SUFFIX,
        )
    }

    /** 预载取流结果入口:由 [PlaybackPreload] 收集 preloadResult 通道后投递(主线程) */
    fun handlePreloadResult(info: JSONObject?) {
        val snapshot = activeSnapshot
        val token = requestToken
        requestToken = null
        if (snapshot == null || token == null) {
            LOG.i("echo-preload-result-drop: no active snapshot (late result)")
            return
        }
        if (info == null || token != info.optString("proKey", "")) {
            LOG.i("echo-preload-giveup: stale result, target=" + token)
            gaveUp(snapshot)
            return
        }
        val msg = info.optString("msg", "")
        val parse = info.optString("parse", "1") == "1"
        val jx = info.optString("jx", "0") == "1"
        val playUrl = info.optString("playUrl", "")
        val rawUrl = info.opt("url")
        val url = if (rawUrl is JSONArray) {
            rawUrl.toString()
        } else if (rawUrl == null) {
            ""
        } else {
            rawUrl.toString()
        }
        if (parse || jx || playUrl.isNotEmpty() || msg.isNotEmpty() ||
            url.isEmpty() ||
            url.startsWith("[") ||
            url.startsWith("data:application") ||
            url.startsWith("tvbox-xg:")
        ) {
            val reason = if (parse) {
                "parse=1"
            } else if (jx) {
                "jx=1"
            } else if (playUrl.isNotEmpty()) {
                "playUrl=" + playUrl
            } else if (msg.isNotEmpty()) {
                "msg=" + msg
            } else if (url.isEmpty()) {
                "empty url"
            } else if (url.startsWith("[")) {
                "array url"
            } else if (url.startsWith("data:application")) {
                "data: url"
            } else {
                "tvbox-xg"
            }
            LOG.i("echo-preload-giveup: " + reason)
            gaveUp(snapshot)
            return
        }
        if (isLocalProxyUrl(url)) {
            LOG.i("echo-preload-giveup: local proxy url")
            gaveUp(snapshot)
            return
        }
        if (url.contains(".m3u8") &&
            KV.get(HawkConfig.M3U8_PURIFY, false) &&
            !DefaultConfig.noAd(snapshot.playFlag)
        ) {
            LOG.i("echo-preload-giveup: m3u8 purify on, url=" + url)
            gaveUp(snapshot)
            return
        }
        val headers = extractHeaders(info)
        var startPos = snapshot.startSkipMs
        // 无痕:预载起点同样不认旧进度,否则自动连播的下一集会带着上次的位置起播
        if (!HistoryHelper.isIncognito()) {
            try {
                WatchProgressStore.awaitWrites()
                val history = AppGraph.cacheRepository.get(MD5.string2MD5(snapshot.nextKey))
                var rec = 0L
                if (history is Long) {
                    rec = history
                } else if (history is String) {
                    rec = history.toLong()
                }
                startPos = maxOf(startPos, rec)
            } catch (ignored: Throwable) {
                LOG.d("PreloadCoordinator", "read saved progress failed, use snapshot start")
            }
        }
        preloadedKey = snapshot.nextKey
        LOG.i("echo-preload-resolve-ok: " + url)
        Preconnect.warm(url, headers)
        try {
            info.put("proKey", snapshot.nextKey)
            info.put("subtKey", snapshot.nextSubtitleKey)
        } catch (ignored: Throwable) {
            LOG.d("PreloadCoordinator", "mark preload result keys failed")
        }
        putCache(snapshot.nextKey, info)
        PreloadManagerHolder.preload(snapshot.context, url, headers, startPos)
    }

    /** 取用并移除某集的预解析结果;开关关闭/未命中/过期都返回 null,由调用方走正常取流 */
    fun consumeResult(realKey: String?): JSONObject? {
        if (realKey == null) return null
        if (!PreloadManagerHolder.enabled()) {
            // 开关已关:池里旧结果不再复用,否则"关了还在省解析"与开关语义不符
            if (cache.isNotEmpty()) cache.clear()
            return null
        }
        val entry = cache.remove(realKey) ?: return null
        if (PreloadCachePolicy.isExpired(System.currentTimeMillis(), entry.at)) {
            LOG.i("echo-preload-cache-expired: " + realKey)
            return null
        }
        LOG.i("echo-preload-cache-hit: " + realKey + " size=" + cache.size)
        return entry.info
    }

    private fun clearCache() {
        cache.clear()
    }

    /** 写入一条预解析结果:顺带清过期项、超容量按插入序淘汰最旧(池很小,直接遍历比定时器简单) */
    private fun putCache(key: String, info: JSONObject) {
        val now = System.currentTimeMillis()
        val entries = cache.entries.iterator()
        while (entries.hasNext()) {
            if (PreloadCachePolicy.isExpired(now, entries.next().value.at)) entries.remove()
        }
        cache[key] = CachedEntry(info, now)
        val keys = cache.keys.iterator()
        while (PreloadCachePolicy.sizeExceeded(cache.size) && keys.hasNext()) {
            keys.next()
            keys.remove()
        }
        LOG.i("echo-preload-cache-put: " + key + " size=" + cache.size)
    }

    private fun gaveUp(snapshot: Snapshot) {
        gaveUpKey = snapshot.currentKey
    }

    companion object {
        private const val EVALUATE_DELAY_MS = 2000L

        /** 持续缓冲多久才让路(取消预载+清数据):短暂缓冲(拖动/瞬断)不停预载,否则每次拖动都从头重下 */
        private const val BUFFERING_YIELD_MS = 4000L
        private const val BUFFERING_COOLDOWN_MS = 5_000L
        private const val PRELOAD_KEY_SUFFIX = "-preload"

        private fun isLocalProxyUrl(url: String): Boolean =
            url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1") ||
                url.startsWith("http://localhost") || url.startsWith("https://localhost")

        private fun extractHeaders(info: JSONObject): HashMap<String, String>? =
            PlayerHelper.extractPlayHeaders(info)
    }
}
