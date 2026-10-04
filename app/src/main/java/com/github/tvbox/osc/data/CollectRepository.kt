package com.github.tvbox.osc.data

import com.github.tvbox.osc.bean.VodInfo

/**
 * 收藏(Room `vodCollect` 表)的访问接口。
 *
 * 迁移期实现为对既有 Java 门面的薄委托,只做依赖倒置、不改行为;跨订阅路由语义(`currentCid`)
 * 随实现一起留在数据层。
 */
interface CollectRepository {

    /** 当前订阅标识:普通源取生效地址,仓模式取仓地址 */
    fun currentCid(): String

    fun isVodCollect(sourceKey: String?, vodId: String?): Boolean

    fun getAllVodCollect(): List<VodCollect>

    fun insertVodCollect(sourceKey: String?, vodInfo: VodInfo)

    fun deleteVodCollect(sourceKey: String?, vodInfo: VodInfo)

    fun deleteVodCollect(id: Int)

    fun deleteVodCollectAll()
}

internal class RoomCollectRepository : CollectRepository {

    override fun currentCid(): String = RoomDataManger.currentCid()

    override fun isVodCollect(sourceKey: String?, vodId: String?): Boolean =
        RoomDataManger.isVodCollect(sourceKey, vodId)

    override fun getAllVodCollect(): List<VodCollect> = RoomDataManger.getAllVodCollect()

    override fun insertVodCollect(sourceKey: String?, vodInfo: VodInfo) =
        RoomDataManger.insertVodCollect(sourceKey, vodInfo)

    override fun deleteVodCollect(sourceKey: String?, vodInfo: VodInfo) =
        RoomDataManger.deleteVodCollect(sourceKey, vodInfo)

    override fun deleteVodCollect(id: Int) = RoomDataManger.deleteVodCollect(id)

    override fun deleteVodCollectAll() = RoomDataManger.deleteVodCollectAll()
}
