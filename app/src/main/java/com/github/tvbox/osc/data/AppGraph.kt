package com.github.tvbox.osc.data

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
