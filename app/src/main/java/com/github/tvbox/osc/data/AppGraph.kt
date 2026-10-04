package com.github.tvbox.osc.data

/**
 * 数据层装配点(D7:不引入 DI 容器)。
 *
 * 消费方(ViewModel / UI / util)只依赖接口;实现类的构造依赖也在这里给,实现内部不直取静态入口。
 */
object AppGraph {

    val historyRepository: HistoryRepository by lazy { RoomHistoryRepository() }

    val collectRepository: CollectRepository by lazy { RoomCollectRepository() }

    val cacheRepository: CacheRepository by lazy { RoomCacheRepository() }
}
