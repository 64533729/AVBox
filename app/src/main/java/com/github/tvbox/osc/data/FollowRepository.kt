package com.github.tvbox.osc.data

import com.github.tvbox.osc.bean.VodInfo

interface FollowRepository {

    fun currentCid(): String

    fun find(sourceKey: String?, vodId: String?): VodFollow?

    fun getAll(): List<VodFollow>

    fun upsert(sourceKey: String?, vodInfo: VodInfo, days: Set<Int>, hour: Int)

    fun deleteSelected(ids: List<Int>)
}

internal class RoomFollowRepository(
    private val follows: () -> VodFollowDao,
) : FollowRepository {

    override fun currentCid(): String = CurrentSubscription.cid()

    override fun find(sourceKey: String?, vodId: String?): VodFollow? =
        follows().getVodFollow(CurrentSubscription.cid(), sourceKey, vodId)

    override fun getAll(): List<VodFollow> = follows().getAll()

    override fun upsert(sourceKey: String?, vodInfo: VodInfo, days: Set<Int>, hour: Int) {
        val cid = CurrentSubscription.cid()
        val dao = follows()
        val record = dao.getVodFollow(cid, sourceKey, vodInfo.id) ?: VodFollow()
        if (record.id == 0) {
            record.cid = cid
            record.sourceKey = sourceKey
            record.vodId = vodInfo.id
            record.addedTime = System.currentTimeMillis()
        }
        record.name = vodInfo.name
        record.pic = vodInfo.pic
        record.updateDays = FollowDays.encode(days)
        record.updateHour = hour.coerceIn(0, 23)
        dao.insert(record)
    }

    override fun deleteSelected(ids: List<Int>) {
        if (ids.isEmpty()) return
        follows().deleteSelected(ids)
    }
}
