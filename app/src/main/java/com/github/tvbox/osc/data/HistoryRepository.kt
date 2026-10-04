package com.github.tvbox.osc.data

import com.github.tvbox.osc.bean.VodInfo

/**
 * 观看历史(Room `vodRecord` 表)的访问接口。
 *
 * 迁移期实现为对既有 Java 门面的薄委托,只做依赖倒置、不改行为;门面迁 Kotlin 后本接口语义不变。
 * 方法一律阻塞式:现有调用点全是同步调用(主线程或调用方自己的 IO 线程),改 suspend/Flow 会一并
 * 改动线程与时序(D6)。
 */
interface HistoryRepository {

    fun getVodInfo(sourceKey: String, vodId: String): VodInfo?

    fun getAllVodRecord(limit: Int): List<VodInfo>

    fun insertVodRecord(sourceKey: String, vodInfo: VodInfo)

    fun deleteVodRecord(sourceKey: String?, vodInfo: VodInfo)

    /** 只清当前订阅的历史 */
    fun deleteVodRecordAll()
}

internal class RoomHistoryRepository : HistoryRepository {

    override fun getVodInfo(sourceKey: String, vodId: String): VodInfo? =
        RoomDataManger.getVodInfo(sourceKey, vodId)

    override fun getAllVodRecord(limit: Int): List<VodInfo> = RoomDataManger.getAllVodRecord(limit)

    override fun insertVodRecord(sourceKey: String, vodInfo: VodInfo) =
        RoomDataManger.insertVodRecord(sourceKey, vodInfo)

    override fun deleteVodRecord(sourceKey: String?, vodInfo: VodInfo) =
        RoomDataManger.deleteVodRecord(sourceKey, vodInfo)

    override fun deleteVodRecordAll() = RoomDataManger.deleteVodRecordAll()
}
