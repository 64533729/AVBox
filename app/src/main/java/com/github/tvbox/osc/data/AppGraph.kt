package com.github.tvbox.osc.data

/**
 * 数据层装配点(D7:不引入 DI 容器)。
 *
 * 消费方(ViewModel / UI / util / player)只依赖接口;DAO 也在这里取 —— 实现内部不直取静态入口。
 * 传 provider 而不是 DAO 实例:`AppDataManager.backup/restore` 会 close 并重建 DB 实例。
 * `@JvmStatic`:Java 侧(播放层/字幕层)按静态入口读,保持它们原有的调用形态。
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
