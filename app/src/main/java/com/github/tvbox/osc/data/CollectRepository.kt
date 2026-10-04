package com.github.tvbox.osc.data

import com.github.tvbox.osc.bean.VodInfo

/**
 * 收藏(Room `vodCollect` 表)的访问接口;阻塞式理由见 [HistoryRepository]。
 *
 * `currentCid` 留在数据层:收藏按 cid 归属,列表全局显示、点击时按它路由回原订阅。
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

/** Room 实现;DAO 由装配点传入的理由见 [RoomHistoryRepository] */
internal class RoomCollectRepository(
    private val collects: () -> VodCollectDao,
) : CollectRepository {

    override fun currentCid(): String = CurrentSubscription.cid()

    override fun isVodCollect(sourceKey: String?, vodId: String?): Boolean {
        val record = collects().getVodCollect(CurrentSubscription.cid(), sourceKey, vodId)
        return record != null
    }

    override fun getAllVodCollect(): List<VodCollect> = collects().getAll()

    override fun insertVodCollect(sourceKey: String?, vodInfo: VodInfo) {
        val cid = CurrentSubscription.cid()
        val dao = collects()
        if (dao.getVodCollect(cid, sourceKey, vodInfo.id) != null) {
            return
        }
        val record = VodCollect()
        record.cid = cid
        record.sourceKey = sourceKey
        record.vodId = vodInfo.id
        record.updateTime = System.currentTimeMillis()
        record.name = vodInfo.name
        record.pic = vodInfo.pic
        dao.insert(record)
    }

    override fun deleteVodCollect(id: Int) {
        collects().delete(id)
    }

    override fun deleteVodCollect(sourceKey: String?, vodInfo: VodInfo) {
        val dao = collects()
        val record = dao.getVodCollect(CurrentSubscription.cid(), sourceKey, vodInfo.id)
        if (record != null) {
            dao.delete(record)
        }
    }

    override fun deleteVodCollectAll() {
        collects().deleteAll()
    }
}
