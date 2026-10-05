package com.github.tvbox.osc.player.engine

import android.content.Context
import androidx.media3.datasource.cache.Cache
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper

/**
 * 进程级共享磁盘缓存(预缓存写盘与播放读盘共用同一实例;SimpleCache 同一目录不允许多实例)。
 *
 * <p>容量 LRU 默认 512MB,目录 externalCacheDir/exo-video-cache(沿用历史值/历史路径,
 * 设置改动->重启 App 生效:容量须在首次创建前注入)。
 *
 * <p>**双栈并存期(M7a–M7c)**:实现仍在 player 模块的 `ExoMediaSourceHelper`(模块依赖方向
 * app -> player,反向引用不成立),本类是其在新内核层的唯一委派入口 —— 新旧栈拿到同一实例。
 * M10 拆除 player 模块时把实现整体搬进本类(本文件是唯一改动点)。
 */
object PlayerCache {

    const val DEFAULT_CACHE_SIZE_BYTES = 512L * 1024 * 1024

    /** 共享缓存目录名(与 FileUtils 的清缓存逻辑同源) */
    const val CACHE_DIR_NAME = "exo-video-cache"

    @JvmStatic
    fun getSharedCache(context: Context): Cache = ExoMediaSourceHelper.getSharedCache(context)

    /** 注入共享缓存容量(字节);仅影响尚未创建的缓存实例(设置改动需重启 App 生效) */
    @JvmStatic
    fun setSharedCacheSizeBytes(bytes: Long) {
        ExoMediaSourceHelper.setSharedCacheSizeBytes(bytes)
    }
}
