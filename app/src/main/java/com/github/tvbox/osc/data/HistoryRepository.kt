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

/**
 * 观看历史(Room `vodRecord` 表)的访问接口。
 *
 * 方法一律阻塞式:调用点都是同步调用,改 suspend/Flow 必须同时把消费方协程化,否则线程与时序都会变。
 */
interface HistoryRepository {

    fun getVodInfo(sourceKey: String, vodId: String): VodInfo?

    fun getAllVodRecord(limit: Int): List<VodInfo>

    fun insertVodRecord(sourceKey: String, vodInfo: VodInfo)

    fun deleteVodRecord(sourceKey: String?, vodInfo: VodInfo)

    /** 只清当前订阅的历史 */
    fun deleteVodRecordAll()
}

/**
 * Room 实现。DAO 由装配点以 provider 传入:`AppDataManager.backup/restore` 会 close 并重建 DB 实例,
 * 缓存 DAO 会让恢复之后的读写落到已关闭的库。
 */
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
        // 条数下推 SQL:历史条目再多也只读所需条数(全表读 + 逐条反序列化会随条目数恶化)。
        // 代价:dataJson 读不出的行会占掉一个名额(仍会被下面的 reserver 裁掉)
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
        // 无痕模式(2026-09-12):不写入观看历史(含播放进度)——
        // 本方法是观看历史的唯一落库点(片头/切集/进度同步都汇聚到这里),在此拦截即可全覆盖;
        // 收藏走 insertVodCollect,不受无痕模式影响
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
