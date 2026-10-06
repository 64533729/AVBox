package com.github.tvbox.osc.player

import android.content.Context
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.github.tvbox.osc.player.engine.MediaSources
import com.github.tvbox.osc.player.engine.PlayerCache
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import java.util.TreeMap

/**
 * 下一集预载的持有者:预载管理器/线程/注册表/就绪回调都在这里(进程级单例,全静态面)。
 *
 * <p>移植口径 = 纯语言迁移(Java → Kotlin),逐行等价。几处 Kotlin 形态差异:
 * ① Java 的"全静态类"→ Kotlin `object` + 公开成员加 `@JvmStatic`(Java 侧调用形式不变);
 * ② Java 的 `synchronized static` → `@Synchronized` + `@JvmStatic`。**互斥对象仍是 Class**:
 *    `@JvmStatic` 会在静态桥上生成带 `ACC_SYNCHRONIZED` 的方法(独立复核已用 `javap` 核实,
 *    整个类里 `monitorenter` 计数为 0),与 Java 的语义逐位一致 —— 并非"改为锁 INSTANCE"。
 * ③ `LinkedHashMap` 匿名子类重写 `removeEldestEntry` → Kotlin `object : LinkedHashMap(...)`;
 * ④ `PreloadManagerListener` 匿名类 → Kotlin 匿名对象;`PreloadException.getCause()` → `.cause`。
 */
object PreloadManagerHolder {

    private const val TAG = "PreloadManager"

    /** 预载时长(秒)兜底值/边界:设置项「预载时长」20~120 步长 10(第二期参数化) */
    private const val PRELOAD_SECONDS_DEFAULT = 60
    private const val PRELOAD_SECONDS_MIN = 20
    private const val PRELOAD_SECONDS_MAX = 120

    /**
     * 当前预载请求的 headers 快照(预缓存下载源建源时读取):media3 只支持 builder 级工厂,
     * 而 headers 是 per-item 的;本项目恒只预载 1 项,且下载源在任务创建时才构建,快照即当前项。
     */
    @Volatile
    private var sPreloadHeaders: Map<String, String> = emptyMap()

    /** 当前预载请求的起始位置（ms,片头跳过/历史进度对齐,预载线程经 TargetPreloadStatusControl 读取） */
    @Volatile
    private var sStartPosMs = 0L

    /** 当前预载请求的数据时长（ms,设置项「预载时长」,预载线程经 TargetPreloadStatusControl 读取） */
    @Volatile
    private var sRangeMs = PRELOAD_SECONDS_DEFAULT * 1000L

    private var sManager: DefaultPreloadManager? = null

    /** 预载/播放共享线程(进程级单例,见 preloadLooper) */
    private var sPreloadThread: HandlerThread? = null

    /** key(url+headers) → 预载中的 MediaItem(去重与失效清理用;预缓存完成不移除——磁盘数据要留给播放读盘) */
    private val sRegistry: MutableMap<String, MediaItem> = HashMap()

    /**
     * 本播放页会话内「已预载过的 url → 预载时 headers 签名」(LRU 8):签名一致才允许播放侧用
     * 预缓存写盘时的默认 key 读盘(签名不同是另一份数据,退回后缀 key 链路,宁可 miss 也不误读)。
     */
    private val sPreloadTargets: MutableMap<String, String> =
        object : LinkedHashMap<String, String>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > 8
        }

    /** 预载完成回调(第二期 UI 提示「下一集已就绪」;由 PlayContainer 注入,任意线程调用) */
    @Volatile
    private var sReadyListener: ReadyListener? = null

    /** 已完成预缓存的 url(拖动/缓冲结束后 UI 重放提示用;清数据/发起新预载即失效) */
    @Volatile
    private var sCompletedUrl: String? = null

    /** 预载就绪监听(第二期 UI 提示用) */
    fun interface ReadyListener {
        fun onPreloadReady(url: String)
    }

    /** 预载总开关（设置页「下一集预载」,默认关） */
    @JvmStatic
    fun enabled(): Boolean = KV.get(HawkConfig.PRELOAD_NEXT_EPISODE, false)

    /**
     * 发起一次预载(磁盘预缓存:流式写共享 SimpleCache,不占 SampleQueue)。同 url+headers 已在预载中时幂等跳过。
     *
     * @param startPosMs 预载起点（对齐片头跳过 max(st, 历史进度),< 0 时按 0 处理）
     */
    @JvmStatic
    @Synchronized
    fun preload(context: Context, url: String?, headers: Map<String, String>?, startPosMs: Long) {
        if (!enabled() || url.isNullOrEmpty()) {
            return
        }
        try {
            val manager = get(context.applicationContext)
            val key = keyOf(url, headers)
            if (sRegistry.containsKey(key)) {
                LOG.i("echo-preload-already: " + url)
                return
            }
            sStartPosMs = maxOf(0L, startPosMs)
            sRangeMs = preloadRangeMs()
            sCompletedUrl = null
            sPreloadHeaders = if (headers == null) emptyMap() else HashMap(headers)
            sPreloadTargets[url] = headersSignature(headers)
            // 预缓存路径必须显式带内容类型(DownloadHelper 只按 uri/mimeType 推断),见 buildPreloadMediaItem
            val item = MediaSources.buildPreloadMediaItem(url, headers)
            sRegistry[key] = item
            manager.add(item, 0) // rankingData 仅用于多项排序,本项目恒只预载 1 项
            // 注意:BasePreloadManager.add() 不触发重新排序,必须手动 invalidate 才会真正开始预载
            manager.invalidate()
            LOG.i("echo-preload-start: " + url)
        } catch (th: Throwable) {
            LOG.e("echo-preload-error: " + url + " " + th)
        }
    }

    /** 是否存在预载中的条目（仅用于日志/诊断,不参与命中判断） */
    @JvmStatic
    @Synchronized
    fun hasActivePreload(): Boolean = sRegistry.isNotEmpty()

    /**
     * 该 url 是否为本会话预载过、且 headers 与预载时一致的目标(第二期磁盘兜底判定):
     * app ExoPlayer.setDataSource 命中时换用 cache 版 MediaSource 从共享 SimpleCache 读盘。
     */
    @JvmStatic
    @Synchronized
    fun isPreloadTargetUrl(url: String?, headers: Map<String, String>?): Boolean {
        if (url.isNullOrEmpty()) {
            return false
        }
        val preloadSignature = sPreloadTargets[url] ?: return false
        return preloadSignature == headersSignature(headers)
    }

    /** 注入预载完成回调（第二期 UI 提示） */
    @JvmStatic
    fun setReadyListener(listener: ReadyListener?) {
        sReadyListener = listener
    }

    /** 已完成目标仍有效时重放一次就绪回调(拖动/缓冲后 UI 需再提示,数据没失效不重下);@return 是否重放 */
    @JvmStatic
    fun replayReadyIfCompleted(): Boolean {
        val url = sCompletedUrl
        val listener = sReadyListener
        if (url == null || listener == null) {
            return false
        }
        LOG.i("echo-preload-ready-replay: " + url)
        try {
            listener.onPreloadReady(url)
        } catch (th: Throwable) {
            LOG.e("PreloadManagerHolder", "preload ready replay failed", th)
        }
        return true
    }

    /** 注销回调：仅当当前回调仍为传入实例时清空（防多播放容器交错销毁时误清后来者的回调） */
    @JvmStatic
    @Synchronized
    fun clearReadyListener(listener: ReadyListener?) {
        if (listener != null && sReadyListener === listener) {
            sReadyListener = null
        }
    }

    /** 预载时长(ms):读设置项「预载时长」,越界兜底;每次发起预载时快照,预载线程经 volatile 读取 */
    private fun preloadRangeMs(): Long {
        var seconds = PRELOAD_SECONDS_DEFAULT
        try {
            seconds = KV.get(HawkConfig.PRELOAD_DURATION, PRELOAD_SECONDS_DEFAULT)
        } catch (th: Throwable) {
            LOG.e("PreloadManagerHolder", "preload duration KV read failed, use default", th)
        }
        seconds = maxOf(PRELOAD_SECONDS_MIN, minOf(PRELOAD_SECONDS_MAX, seconds))
        return seconds * 1000L
    }

    /** 清空全部预载（切集/换线/换源/暂停策略变化等失效事件）,保留 manager 可复用 */
    @JvmStatic
    @Synchronized
    fun clearAll() {
        sCompletedUrl = null
        val manager = sManager ?: return
        if (sRegistry.isNotEmpty()) {
            LOG.i("echo-preload-clear: " + sRegistry.size)
            sRegistry.clear()
        }
        try {
            manager.reset()
        } catch (th: Throwable) {
            LOG.e("echo-preload-clear-error " + th)
        }
    }

    /** 释放 manager（退出播放页）,下次预载重新构建 */
    @JvmStatic
    @Synchronized
    fun release() {
        val manager = sManager
        if (manager != null) {
            try {
                manager.release()
            } catch (th: Throwable) {
                LOG.e("PreloadManagerHolder", "preload manager release failed", th)
            }
            sManager = null
        }
        sRegistry.clear()
        sPreloadTargets.clear()
        sPreloadHeaders = emptyMap()
        sCompletedUrl = null
    }

    private fun get(appContext: Context): DefaultPreloadManager {
        var manager = sManager
        if (manager == null) {
            val control = TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
                DefaultPreloadManager.PreloadStatus.specifiedRangeCached(sStartPosMs, sRangeMs)
            }
            manager = DefaultPreloadManager.Builder(appContext, control)
                .setMediaSourceFactory(PreloadMediaSourceFactory(appContext))
                // cached 状态走 PreCacheHelper 磁盘预缓存:必须注入 Cache,否则 build 时 preCacheHelperFactory
                // 为 null,开始预缓存即抛 —— 数据不再进 SampleQueue,故不再需要 32MB 内存水位(LoadControl)
                .setCache(PlayerCache.getSharedCache(appContext))
                // 预缓存下载只认 builder 级 DataSource.Factory(不读 MediaItem.extras),站点 headers 靠它桥接
                .setDataSourceFactory(PreloadDataSourceFactory(appContext))
                // 预载线程须与播放器 playback looper 同一(PreloadMediaSource 硬校验,见 preloadLooper)
                .setPreloadLooper(preloadLooper())
                .build()
            manager.addListener(object : PreloadManagerListener {
                override fun onCompleted(mediaItem: MediaItem) {
                    // ⚠️ Java 侧还判了 `mediaItem == null`,但 media3 的该回调参数带 @NonNull,
                    // 那个分支不可达;这里只保留真正可能为空的 localConfiguration
                    val configuration = mediaItem.localConfiguration
                    val url = if (configuration == null) null else configuration.uri.toString()
                    sCompletedUrl = url
                    val listener = sReadyListener
                    LOG.i("echo-preload-complete: " + url + ", listener=" + (listener != null))
                    if (listener != null && url != null) {
                        try {
                            listener.onPreloadReady(url)
                        } catch (th: Throwable) {
                            LOG.e("PreloadManagerHolder", "preload ready callback failed", th)
                        }
                    }
                }

                override fun onError(exception: PreloadException) {
                    // PreloadException.toString() 只有类名,不带底层 IO/manifest 原因,必须显式打 cause
                    LOG.e("echo-preload-error: " + exception + ", cause=" + exception.cause)
                }
            })
            sManager = manager
        }
        return manager
    }

    @JvmStatic
    @Synchronized
    fun preloadLooper(): Looper {
        var thread = sPreloadThread
        if (thread == null || !thread.isAlive) {
            thread = HandlerThread("avbox-preload", android.os.Process.THREAD_PRIORITY_AUDIO)
            thread.start()
            sPreloadThread = thread
        }
        return thread.looper
    }

    /** url + 规范化(headers) 作为命中 key,headers 逐项一致才命中 */
    private fun keyOf(url: String, headers: Map<String, String>?): String =
        StringBuilder(url).append('\n').append(headersSignature(headers)).toString()

    /** 规范化 headers 签名(排序 + trim + 大小写不敏感,逐项以 ';' 分隔);无 headers 为空串 */
    private fun headersSignature(headers: Map<String, String>?): String {
        if (headers == null || headers.isEmpty()) {
            return ""
        }
        val sorted = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        for ((key, value) in headers) {
            if (key != null && value != null) {
                sorted[key.trim { it <= ' ' }] = value.trim { it <= ' ' }
            }
        }
        val sb = StringBuilder()
        for ((key, value) in sorted) {
            sb.append(key).append(':').append(value).append(';')
        }
        return sb.toString()
    }

    /** 预缓存下载源:建源时取当前预载项的 headers 快照(恒只预载 1 项),不桥接则站点请求 403 */
    private class PreloadDataSourceFactory(private val appContext: Context) : DataSource.Factory {

        override fun createDataSource(): DataSource =
            MediaSources.getInstance(appContext)
                .createDataSourceFactory(sPreloadHeaders)
                .createDataSource()
    }

    /**
     * 预载侧 MediaSource 工厂:与播放侧同源——直接复用 MediaSources.getMediaSource,
     * headers 从 MediaItem 的 requestMetadata.extras 取回（buildMediaItem 写入）。
     */
    private class PreloadMediaSourceFactory(private val appContext: Context) : MediaSource.Factory {

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val configuration = mediaItem.localConfiguration
            val uri = if (configuration != null) configuration.uri.toString() else mediaItem.mediaId
            val headers = MediaSources.getHeadersFrom(mediaItem)
            // cached 状态不往 SampleQueue 灌数据,该源只是 holder 的壳(onMediaSourceUpdated 会替换成新源)
            return MediaSources.getInstance(appContext).getMediaSource(uri, headers, true)
        }

        override fun getSupportedTypes(): IntArray = intArrayOf(
            C.CONTENT_TYPE_OTHER,
            C.CONTENT_TYPE_HLS,
            C.CONTENT_TYPE_DASH,
            C.CONTENT_TYPE_RTSP,
        )

        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
            // 预载不做 DRM(可预载判定已排除解析源/DRM 场景)
            return this
        }

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
            return this
        }
    }
}
