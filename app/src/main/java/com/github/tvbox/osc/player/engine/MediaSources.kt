package com.github.tvbox.osc.player.engine

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.github.tvbox.osc.util.OkGoHelper
import okhttp3.OkHttpClient
import java.util.HashMap
import java.util.Locale
import java.util.TreeMap

/**
 * MediaSource 构建(移植自 doikki `ExoMediaSourceHelper`):
 * content type 推断(TVBox-Format 头 > 文件名)、headers 规范化与 MediaItem 携带、
 * 磁盘缓存数据源(headers 后缀 key 防跨线路串缓存)、预载目标专用源(media3 默认 key)。
 *
 * @param appContext application 上下文
 * @param client 注入的 OkHttpClient(DoH/hosts/代理/SSL 都在 client 上);null = 用类持有式兜底单例
 * @param cache 显式覆盖磁盘缓存;null = 用进程级共享缓存 [PlayerCache.getSharedCache]
 */
class MediaSources(
    context: Context,
    private var client: OkHttpClient? = null,
    private var cache: Cache? = null,
) {

    /** 构造即归一 application 上下文(旧 helper 单例同款;避免 Activity 被 DataSource/SimpleCache 长期持有) */
    private val appContext: Context = context.applicationContext

    fun setOkClient(client: OkHttpClient?) {
        this.client = client
    }

    /** 显式覆盖缓存实例(测试/多实例场景);默认走 [PlayerCache.getSharedCache] */
    fun setCache(cache: Cache?) {
        this.cache = cache
    }

    fun getMediaSource(uri: String): MediaSource = getMediaSource(uri, null, false)

    fun getMediaSource(uri: String, headers: Map<String, String>?): MediaSource =
        getMediaSource(uri, headers, false)

    fun getMediaSource(uri: String, isCache: Boolean): MediaSource =
        getMediaSource(uri, null, isCache)

    fun getMediaSource(uri: String, headers: Map<String, String>?, isCache: Boolean): MediaSource =
        getMediaSource(uri, headers, isCache, /*useDefaultCacheKey=*/false, inferContentType(uri, headers))

    /**
     * 预载目标专用的 cache 版 MediaSource:读盘 key 用 media3 默认(=uri,不带 headers 后缀)。
     * 预缓存写盘 key 由 media3 内部 CacheWriter 决定、注入不了本类的后缀工厂,不回落默认 key 会永远 miss。
     */
    fun getPreloadTargetMediaSource(uri: String, headers: Map<String, String>?): MediaSource =
        getMediaSource(uri, headers, true, /*useDefaultCacheKey=*/true, inferContentType(uri, headers))

    fun getHlsMediaSource(uri: String, headers: Map<String, String>?): MediaSource =
        getMediaSource(uri, headers, false, /*useDefaultCacheKey=*/false, C.TYPE_HLS)

    private fun getMediaSource(
        uri: String,
        headers: Map<String, String>?,
        isCache: Boolean,
        useDefaultCacheKey: Boolean,
        contentType: Int,
    ): MediaSource {
        val contentUri = Uri.parse(uri)
        if ("rtsp" == contentUri.scheme) {
            return RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(contentUri))
        }
        val requestHeaders = toRequestHeaders(headers)
        val mediaItem = buildMediaItem(uri, headers)
        var factory: DataSource.Factory = createDataSourceFactory(requestHeaders)
        if (isCache) {
            factory = getCacheDataSourceFactory(factory, requestHeaders, useDefaultCacheKey)
        }
        return when (contentType) {
            C.TYPE_DASH -> DashMediaSource.Factory(factory).createMediaSource(mediaItem)
            C.TYPE_HLS -> HlsMediaSource.Factory(factory)
                // 自定义错误处理策略:跳过坏的切片继续播放
                .setLoadErrorHandlingPolicy(HlsErrorHandlingPolicy())
                .createMediaSource(mediaItem)
            else -> ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
        }
    }

    /**
     * 由 headers 构建 per-item DataSource factory(headers 未落在 MediaItem 上时使用,如预缓存下载)。
     * 不复用全局共享 factory,避免多次构建 MediaSource 时 headers 相互覆盖。
     */
    fun createDataSourceFactory(headers: Map<String, String>?): DataSource.Factory {
        val normalized = toRequestHeaders(headers)
        var userAgent: String? = null
        val requestHeaders = HashMap<String, String>()
        for (entry in normalized.entries) {
            if ("User-Agent".equals(entry.key, ignoreCase = true)) {
                userAgent = entry.value
            } else {
                requestHeaders[entry.key] = entry.value
            }
        }
        // client 未注入时回落 OkGoHelper 的共享 client(DoH/hosts/代理/SSL 都挂在它上面;
        // 旧栈由 OkGoHelper.initExoOkHttpClient 全局注入,这里保持"必然有正确 client"的口径),
        // 再回落类持有式兜底单例
        val httpFactory = OkHttpDataSource.Factory(client ?: OkGoHelper.getItvClient() ?: FallbackClient.INSTANCE)
        httpFactory.setUserAgent(userAgent)
        httpFactory.setDefaultRequestProperties(requestHeaders)
        return DefaultDataSource.Factory(appContext, httpFactory)
    }

    /** 从 buildMediaItem 构建的 MediaItem 取 headers 后建 factory(播放/预载源常规入口) */
    fun createDataSourceFactory(mediaItem: MediaItem): DataSource.Factory =
        createDataSourceFactory(getHeadersFrom(mediaItem))

    /**
     * 边播缓存数据源(修复「跨线路串缓存」):
     * media3 默认的 CacheKeyFactory 只认 dataSpec.key/uri —— 同一 URL 配不同 Referer/UA/token
     * 的源会互相读到对方写到盘上的数据;此处改为「分片 uri + 规范化 headers」作为 key。
     *
     * <p>无 headers 时保持 media3 默认行为(key=uri),不改变原有命中语义;
     * [useDefaultCacheKey]=true(预载目标)时读盘回落到 media3 默认 key(=uri)。
     */
    private fun getCacheDataSourceFactory(
        upstream: DataSource.Factory,
        headers: Map<String, String>?,
        useDefaultCacheKey: Boolean,
    ): DataSource.Factory {
        val cache = this.cache ?: PlayerCache.getSharedCache(appContext)
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        if (!useDefaultCacheKey) {
            val keySuffix = headerKeySuffix(headers)
            if (keySuffix.isNotEmpty()) {
                factory.setCacheKeyFactory { dataSpec -> dataSpec.uri.toString() + keySuffix }
            }
        }
        return factory
    }

    companion object {

        const val HEADER_FORMAT = "TVBox-Format"

        /** MediaItem.requestMetadata.extras 中承载 http headers 的 key(HashMap&lt;String,String&gt;) */
        const val EXTRA_HEADERS = "avbox.extras.httpHeaders"

        /**
         * 兜底 OkHttpClient:仅在调用方未注入共享 client 时使用。
         * 类持有式懒加载单例:天然线程安全,无需 volatile(每个 MediaSource 各带一套 Dispatcher/ConnectionPool
         * 会丢失连接与 TLS 复用,HLS 多分片时明显更慢、更耗电)。
         */
        private object FallbackClient {
            val INSTANCE: OkHttpClient = OkHttpClient.Builder().build()
        }

        /**
         * 统一的 MediaItem 构建入口:headers 写入 requestMetadata.extras,播放与预载共用同一 header 语义。
         */
        @JvmStatic
        fun buildMediaItem(uri: String, headers: Map<String, String>?): MediaItem {
            val extras = Bundle()
            extras.putSerializable(EXTRA_HEADERS, toRequestHeaders(headers))
            val requestMetadata = MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse(uri))
                .setExtras(extras)
                .build()
            return MediaItem.Builder()
                .setUri(uri)
                .setRequestMetadata(requestMetadata)
                .build()
        }

        /**
         * 预缓存(PreCacheHelper/DownloadHelper)专用 MediaItem:显式带上推断出的 mimeType ——
         * DownloadHelper 只按 uri/mimeType 判类型、读不到 TVBox-Format 约定,HLS/DASH 会被当进度流下载。
         */
        @JvmStatic
        fun buildPreloadMediaItem(uri: String, headers: Map<String, String>?): MediaItem {
            val item = buildMediaItem(uri, headers)
            val mimeType = mimeTypeOf(inferContentType(uri, headers)) ?: return item
            return item.buildUpon().setMimeType(mimeType).build()
        }

        /** 从 buildMediaItem 构建的 MediaItem 中取回 headers(未携带时返回 null) */
        @JvmStatic
        fun getHeadersFrom(mediaItem: MediaItem): Map<String, String>? {
            val extras = mediaItem.requestMetadata.extras ?: return null
            val stored = extras.getSerializable(EXTRA_HEADERS)
            @Suppress("UNCHECKED_CAST")
            return stored as? Map<String, String>
        }

        /** headers → 磁盘缓存 key 后缀(排序 + trim + 大小写不敏感,与预载侧 key 口径一致) */
        @JvmStatic
        fun headerKeySuffix(headers: Map<String, String>?): String {
            if (headers == null || headers.isEmpty()) {
                return ""
            }
            val sorted = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
            for (entry in headers.entries) {
                val key = entry.key
                val value = entry.value
                if (key != null && value != null) {
                    sorted[key.trim { it <= ' ' }] = value.trim { it <= ' ' }
                }
            }
            if (sorted.isEmpty()) {
                return ""
            }
            val sb = StringBuilder()
            for ((key, value) in sorted) {
                sb.append('\n').append(key).append(':').append(value).append(';')
            }
            return sb.toString()
        }

        /**
         * 过滤内部标记与空键值,保留 UA 在 map 内(由 [createDataSourceFactory] 拆分处理)。
         */
        @JvmStatic
        fun toRequestHeaders(headers: Map<String, String>?): HashMap<String, String> {
            val requestHeaders = HashMap<String, String>()
            if (headers == null) {
                return requestHeaders
            }
            for (entry in headers.entries) {
                val key = entry.key
                val value = entry.value
                if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                    continue
                }
                if (HEADER_FORMAT.equals(key, ignoreCase = true)) {
                    continue
                }
                requestHeaders[key] = value.trim { it <= ' ' }
            }
            return requestHeaders
        }

        @JvmStatic
        fun inferContentType(fileName: String, headers: Map<String, String>?): Int {
            val formatType = inferFormatContentType(headers)
            if (formatType != C.TYPE_OTHER) {
                return formatType
            }
            val name = fileName.lowercase(Locale.getDefault())
            return when {
                name.contains(".mpd") || name.contains("type=mpd") || name.contains("type=dash")
                    || name.contains("format=mpd") || name.contains("format=dash") -> C.TYPE_DASH
                isHlsUri(name) -> C.TYPE_HLS
                else -> C.TYPE_OTHER
            }
        }

        /** TVBox-Format 头 → content type(hls/mpegurl/m3u8 → HLS;dash/mpd/dash+xml → DASH) */
        @JvmStatic
        fun inferFormatContentType(headers: Map<String, String>?): Int {
            if (headers == null || !headers.containsKey(HEADER_FORMAT)) {
                return C.TYPE_OTHER
            }
            val format = (headers[HEADER_FORMAT] ?: return C.TYPE_OTHER).trim { it <= ' ' }.lowercase(Locale.getDefault())
            if (format == "hls" || format.contains("mpegurl") || format.contains("m3u8")) {
                return C.TYPE_HLS
            }
            if (format == "dash" || format == "mpd" || format.contains("dash+xml")) {
                return C.TYPE_DASH
            }
            return C.TYPE_OTHER
        }

        /** 内容类型 → MediaItem mimeType(media3 类型推断依据;进度流返回 null 保持原样) */
        @JvmStatic
        fun mimeTypeOf(contentType: Int): String? = when (contentType) {
            C.TYPE_HLS -> MimeTypes.APPLICATION_M3U8
            C.TYPE_DASH -> MimeTypes.APPLICATION_MPD
            else -> null
        }

        @JvmStatic
        fun isHlsUri(uri: String): Boolean {
            if (isAudioUri(uri)) {
                return false
            }
            if (uri.contains("m3u8") || uri.contains("type=hls") || uri.contains("format=hls")) {
                return true
            }
            val parsedUri: Uri? = Uri.parse(uri)
            val path = parsedUri?.path ?: return false
            val lower = path.lowercase(Locale.getDefault())
            return lower.endsWith("/live.php") || lower.contains("/live/")
        }

        @JvmStatic
        fun isAudioUri(uri: String): Boolean {
            val parsedUri: Uri? = Uri.parse(uri)
            var path = parsedUri?.path ?: uri
            path = path.lowercase(Locale.getDefault())
            return path.endsWith(".mp3")
                || path.endsWith(".m4a")
                || path.endsWith(".aac")
                || path.endsWith(".flac")
                || path.endsWith(".wav")
                || path.endsWith(".ogg")
                || path.endsWith(".opus")
                || path.endsWith(".amr")
        }
    }
}
