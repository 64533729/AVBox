package com.github.tvbox.osc.player.engine

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object PlayerCache {

    private const val DEFAULT_SIZE_BYTES = 512L * 1024 * 1024

    private const val CACHE_DIR_NAME = "exo-video-cache"

    @Volatile
    private var sharedCache: Cache? = null

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

    @JvmStatic
    fun setSharedCacheSizeBytes(bytes: Long) {
        if (bytes > 0) {
            sharedCacheSizeBytes = bytes
        }
    }

    private fun externalCacheDir(context: Context): File =
        context.externalCacheDir ?: context.cacheDir
}
