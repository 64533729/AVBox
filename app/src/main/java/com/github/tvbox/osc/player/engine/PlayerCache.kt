package com.github.tvbox.osc.player.engine

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 进程级共享磁盘缓存(M7e 起实现体归本类,不再反向委派 doikki `ExoMediaSourceHelper`)。
 *
 * <p>三条约束不可动(与迁移前逐条一致):
 * ① **预缓存写盘与播放读盘共用同一实例**:SimpleCache 同一目录不允许多实例,第二个实例会抛;
 * ② 容量 LRU 默认 512MB、目录 `externalCacheDir/exo-video-cache`(沿用历史值/历史路径,
 *   目录名与 `FileUtils.EXO_CACHE_DIR_NAME` 必须同源,否则"清除缓存"删不到);
 * ③ 容量注入**必须早于首次 `getSharedCache`**(设置改动→重启 App 生效)。
 *
 * <p>初始化顺序:见 `App.onCreate` —— 先 `setSharedCacheSizeBytes`,再后台线程执行
 * `FileUtils.purgeExoCacheIfPending()`(须早于本类首次创建 SimpleCache,否则内存索引/磁盘失配)。
 */
object PlayerCache {

    /** 512MB 沿用历史值 */
    private const val DEFAULT_SIZE_BYTES = 512L * 1024 * 1024

    /** 与 `FileUtils.EXO_CACHE_DIR_NAME` 同源 */
    private const val CACHE_DIR_NAME = "exo-video-cache"

    @Volatile
    private var sharedCache: Cache? = null

    /** 共享缓存容量(字节):仅在首次创建前生效 */
    @Volatile
    private var sharedCacheSizeBytes: Long = DEFAULT_SIZE_BYTES

    @JvmStatic
    fun getSharedCache(context: Context): Cache {
        val existing = sharedCache
        if (existing != null) return existing
        synchronized(PlayerCache::class.java) {
            sharedCache?.let { return it }
            val appContext = context.applicationContext
            val created = SimpleCache(
                File(externalCacheDir(appContext), CACHE_DIR_NAME),
                LeastRecentlyUsedCacheEvictor(sharedCacheSizeBytes),
                StandaloneDatabaseProvider(appContext),
            )
            sharedCache = created
            return created
        }
    }

    /** 注入共享缓存容量(字节);仅影响尚未创建的缓存实例(设置改动需重启 App 生效) */
    @JvmStatic
    fun setSharedCacheSizeBytes(bytes: Long) {
        if (bytes > 0) {
            sharedCacheSizeBytes = bytes
        }
    }

    /** 外置缓存目录不可用时回落内置 cacheDir(与 `FileUtils.getExternalCachePath` 同一口径) */
    private fun externalCacheDir(context: Context): File =
        context.externalCacheDir ?: context.cacheDir
}
