package com.github.tvbox.osc.player.engine

/**
 * 数据源选择策略(纯函数,从旧 `osc.player.ExoPlayer.setDataSource` 提取)。
 *
 * <p>回答两件事:① RTMP 直播要不要补 `live=1` 后缀;② 这次起播用哪种缓存数据源。
 */
object SourcePolicy {

    /** 本次起播的缓存模式(优先级:预载目标 > 边播缓存 > 不用缓存) */
    enum class CacheMode {
        NONE,

        /** 边播边缓存:cache 数据源 + headers 后缀 key(防跨线路串缓存) */
        PLAY_CACHE,

        /** 预载目标:cache 数据源 + media3 默认 key(=uri,与预缓存写盘 key 一致) */
        PRELOAD_TARGET,
    }

    /**
     * RTMP 直播补 `live=1`:librtmp 要求直播流地址末尾带该后缀(media3 的 RtmpDataSource 原样透传 URL,
     * 不会补),缺了会被当作点播流,读到流尾即结束(直播必现)。
     */
    @JvmStatic
    fun applyRtmpLiveFlag(path: String, isLive: Boolean): String {
        if (path.startsWith(RTMP_SCHEME) && isLive && !path.contains(LIVE_FLAG)) {
            return path + " " + LIVE_FLAG
        }
        return path
    }

    @JvmStatic
    fun isRtmp(path: String?): Boolean = path != null && path.startsWith(RTMP_SCHEME)

    /** 本地代理地址(127.0.0.1 / localhost):代理源是宿主自己在服务,不走磁盘缓存 */
    @JvmStatic
    fun isLocalProxyUrl(url: String?): Boolean {
        if (url == null) return false
        return url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1") ||
            url.startsWith("http://localhost") || url.startsWith("https://localhost")
    }

    /**
     * 本次起播的缓存模式:本地代理/RTMP 一律不走磁盘缓存(地址是本地回环/流式协议,缓存无意义且拖慢起播);
     * 其余按"预载目标 > 边播缓存"优先级选择。
     */
    @JvmStatic
    fun resolveCacheMode(
        isLocalProxyUrl: Boolean,
        isRtmp: Boolean,
        preloadTarget: Boolean,
        playCacheWanted: Boolean,
    ): CacheMode = when {
        isLocalProxyUrl || isRtmp -> CacheMode.NONE
        preloadTarget -> CacheMode.PRELOAD_TARGET
        playCacheWanted -> CacheMode.PLAY_CACHE
        else -> CacheMode.NONE
    }

    private const val RTMP_SCHEME = "rtmp://"
    private const val LIVE_FLAG = "live=1"
}
