package com.github.tvbox.osc.data

import com.github.tvbox.osc.bean.VodInfo

interface CollectRepository {

    fun currentCid(): String

    fun isVodCollect(sourceKey: String?, vodId: String?): Boolean

    fun getAllVodCollect(): List<VodCollect>

    fun insertVodCollect(sourceKey: String?, vodInfo: VodInfo)

    fun deleteVodCollect(sourceKey: String?, vodInfo: VodInfo)

    fun deleteVodCollect(id: Int)

    fun deleteVodCollectAll()
}

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
