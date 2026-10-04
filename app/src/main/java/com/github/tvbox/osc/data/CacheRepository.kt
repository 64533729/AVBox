package com.github.tvbox.osc.data

/**
 * 键值缓存(Room `cache` 表)的访问接口。
 *
 * 载荷走 Java 序列化,与既有实现一致;`clearAllProgress` 是"清空历史"的兜底扫尾入口。
 */
interface CacheRepository {

    fun get(key: String): Any?

    fun save(key: String, body: Any?)

    fun delete(key: String, body: Any?)

    /** 删除全部进度行(载荷是 Long 的行),返回被删缓存键供调用方作废后续回写 */
    fun clearAllProgress(): List<String>
}

internal class RoomCacheRepository : CacheRepository {

    override fun get(key: String): Any? = CacheManager.getCache(key)

    override fun save(key: String, body: Any?) {
        CacheManager.save(key, body)
    }

    override fun delete(key: String, body: Any?) {
        CacheManager.delete(key, body)
    }

    override fun clearAllProgress(): List<String> = CacheManager.clearAllProgress()
}
