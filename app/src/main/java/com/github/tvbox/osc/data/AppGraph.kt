package com.github.tvbox.osc.data

/**
 * 数据层装配点:消费方只依赖接口,DAO 也在这里取 —— 实现内部不直取静态入口。
 *
 * DAO 以 provider 传入(AppDataManager.backup/restore 会重建 DB 实例);`@JvmStatic` 让播放/字幕层的
 * Java 侧按静态入口读,与其原有调用形态一致。
 */
object AppGraph {

    @JvmStatic
    val historyRepository: HistoryRepository by lazy {
        RoomHistoryRepository { AppDataManager.get().getVodRecordDao() }
    }

    @JvmStatic
    val collectRepository: CollectRepository by lazy {
        RoomCollectRepository { AppDataManager.get().getVodCollectDao() }
    }

    @JvmStatic
    val cacheRepository: CacheRepository by lazy {
        RoomCacheRepository { AppDataManager.get().getCacheDao() }
    }
}
