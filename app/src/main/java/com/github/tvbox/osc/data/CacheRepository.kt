package com.github.tvbox.osc.data

import com.github.tvbox.osc.util.LOG
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * 键值缓存(Room `cache` 表)的访问接口;载荷走 Java 序列化,与既有实现一致。
 *
 * `key` 来自 `MD5.string2MD5(...)`(空串入参返回 null),故 `get`/`delete` 可空(旧实现对 null 是"查不到/空操作"),
 * `save` 保持非空:主键空键写入旧实现本就抛异常。
 */
interface CacheRepository {

    fun get(key: String?): Any?

    fun save(key: String, body: Any?)

    fun delete(key: String?, body: Any?)

    /** 删除全部进度行(载荷是 Long 的行),返回被删缓存键供调用方作废后续回写 */
    fun clearAllProgress(): List<String>
}

/** Room 实现;DAO 由装配点传入的理由见 [RoomHistoryRepository] */
internal class RoomCacheRepository(
    private val caches: () -> CacheDao,
) : CacheRepository {

    override fun get(key: String?): Any? {
        val cache = caches().getCache(key) ?: return null
        val data = cache.data ?: return null
        return toObject(data)
    }

    override fun save(key: String, body: Any?) {
        val cache = Cache()
        cache.key = key
        cache.data = toByteArray(body)
        caches().save(cache)
    }

    override fun delete(key: String?, body: Any?) {
        if (key == null) return
        val cache = Cache()
        cache.key = key
        cache.data = toByteArray(body)
        caches().delete(cache)
    }

    /**
     * 删除全部进度行(反序列化是 Long 的行;字幕/歌词缓存是 String,不受影响),返回被删缓存键供调用方作废后续回写。
     * 供"清空历史"兜底:没有索引条目的存量进度键是 MD5,反推不出归属,只能这样清。
     */
    override fun clearAllProgress(): List<String> {
        val dao = caches()
        val rows = dao.getAll()
        val removed = ArrayList<String>()
        for (row in rows) {
            val data = row.data ?: continue
            if (toObject(data) is Long) {
                dao.delete(row)
                removed.add(row.key)
            }
        }
        return removed
    }

    //反序列,把二进制数据转换成java object对象
    private fun toObject(data: ByteArray): Any? {
        var bais: ByteArrayInputStream? = null
        var ois: ObjectInputStream? = null
        try {
            bais = ByteArrayInputStream(data)
            ois = ObjectInputStream(bais)
            return ois.readObject()
        } catch (e: Exception) {
            LOG.e("RoomCacheRepository", e)
        } finally {
            try {
                bais?.close()
                ois?.close()
            } catch (ignore: Exception) {
                LOG.e("RoomCacheRepository", ignore)
            }
        }
        return null
    }

    //序列化存储数据需要转换成二进制
    private fun toByteArray(body: Any?): ByteArray {
        var baos: ByteArrayOutputStream? = null
        var oos: ObjectOutputStream? = null
        try {
            baos = ByteArrayOutputStream()
            oos = ObjectOutputStream(baos)
            oos.writeObject(body)
            oos.flush()
            return baos.toByteArray()
        } catch (e: Exception) {
            LOG.e("RoomCacheRepository", e)
        } finally {
            try {
                baos?.close()
                oos?.close()
            } catch (e: Exception) {
                LOG.e("RoomCacheRepository", e)
            }
        }
        return ByteArray(0)
    }
}
