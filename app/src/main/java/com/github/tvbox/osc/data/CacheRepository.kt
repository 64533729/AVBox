package com.github.tvbox.osc.data

import com.github.tvbox.osc.util.LOG
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

interface CacheRepository {

    fun get(key: String?): Any?

    fun save(key: String, body: Any?)

    fun delete(key: String?, body: Any?)

    fun clearAllProgress(): List<String>
}

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
