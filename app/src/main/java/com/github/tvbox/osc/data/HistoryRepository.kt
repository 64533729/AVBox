package com.github.tvbox.osc.data

import android.text.TextUtils
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

interface HistoryRepository {

    fun getVodInfo(sourceKey: String, vodId: String): VodInfo?

    fun getAllVodRecord(limit: Int): List<VodInfo>

    fun insertVodRecord(sourceKey: String, vodInfo: VodInfo)

    fun deleteVodRecord(sourceKey: String?, vodInfo: VodInfo)

    fun deleteVodRecordAll()
}

internal class RoomHistoryRepository(
    private val records: () -> VodRecordDao,
) : HistoryRepository {

    private val vodInfoStrategy: ExclusionStrategy = object : ExclusionStrategy {
        override fun shouldSkipField(field: FieldAttributes): Boolean {
            if (field.declaringClass == VodInfo::class.java && field.name == "seriesFlags") {
                return true
            }
            if (field.declaringClass == VodInfo::class.java && field.name == "seriesMap") {
                return true
            }
            return false
        }

        override fun shouldSkipClass(clazz: Class<*>): Boolean = false
    }

    private fun vodInfoGson(): Gson =
        GsonBuilder().addSerializationExclusionStrategy(vodInfoStrategy).create()

    private fun vodInfoType(): Type = object : TypeToken<VodInfo>() {}.type

    override fun getVodInfo(sourceKey: String, vodId: String): VodInfo? {
        val record = records().getVodRecord(CurrentSubscription.cid(), sourceKey, vodId)
        try {
            if (record != null && record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                val vodInfo = vodInfoGson().fromJson<VodInfo>(record.dataJson, vodInfoType())
                if (vodInfo.name == null) return null
                return vodInfo
            }
        } catch (e: Exception) {
            LOG.e("RoomHistoryRepository", e)
        }
        return null
    }

    override fun getAllVodRecord(limit: Int): List<VodInfo> {
        val dao = records()
        val cid = CurrentSubscription.cid()
        val index = KV.get(HawkConfig.HISTORY_NUM, 0)
        val hisNum = HistoryHelper.getHisNum(index)
        val size = minOf(limit, hisNum)
        val recordList = dao.getAll(cid, size)
        val vodInfoList = ArrayList<VodInfo>(recordList.size)
        for (record in recordList) {
            var info: VodInfo? = null
            try {
                if (record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                    val parsed = vodInfoGson().fromJson<VodInfo>(record.dataJson, vodInfoType())
                    parsed.sourceKey = record.sourceKey
                    if (parsed.name != null) info = parsed
                }
            } catch (e: Exception) {
                LOG.e("RoomHistoryRepository", e)
            }
            if (info != null) {
                vodInfoList.add(info)
            }
        }
        if (dao.getCount(cid) > hisNum) {
            dao.reserver(cid, hisNum)
        }
        return vodInfoList
    }

    override fun insertVodRecord(sourceKey: String, vodInfo: VodInfo) {
        if (HistoryHelper.isIncognito()) return
        val cid = CurrentSubscription.cid()
        val dao = records()
        val record = dao.getVodRecord(cid, sourceKey, vodInfo.id) ?: VodRecord()
        record.cid = cid
        record.sourceKey = sourceKey
        record.vodId = vodInfo.id
        record.updateTime = System.currentTimeMillis()
        record.dataJson = vodInfoGson().toJson(vodInfo)
        dao.insert(record)
    }

    override fun deleteVodRecord(sourceKey: String?, vodInfo: VodInfo) {
        val dao = records()
        val record = dao.getVodRecord(CurrentSubscription.cid(), sourceKey, vodInfo.id)
        if (record != null) {
            dao.delete(record)
        }
    }

    override fun deleteVodRecordAll() {
        records().deleteAll(CurrentSubscription.cid())
    }
}
