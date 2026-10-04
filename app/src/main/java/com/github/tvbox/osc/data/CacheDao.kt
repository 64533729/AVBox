package com.github.tvbox.osc.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update

/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(cache: Cache): Long

    /**
     * 注意,冒号后面必须紧跟参数名,中间不能有空格。大于小于号和冒号中间是有空格的。
     * select *from cache where【表中列名】 =:【参数名】------>等于
     * where 【表中列名】 < :【参数名】 小于
     * where 【表中列名】 between :【参数名1】 and :【参数名2】------->这个区间
     * where 【表中列名】like :参数名----->模糊查询
     * where 【表中列名】 in (:【参数名集合】)---->查询符合集合内指定字段值的记录
     *
     * 入参可空:`MD5.string2MD5` 对空串/空值返回 null,此时匹配不到行(既有行为)。
     */
    @Query("select *from cache where `key`=:key")
    fun getCache(key: String?): Cache?

    //全表读:只服务"清空历史"时的存量进度清理,勿在热路径用
    @Query("select *from cache")
    fun getAll(): List<Cache>

    //只能传递对象昂,删除时根据Cache中的主键 来比对的
    @Delete
    fun delete(cache: Cache): Int

    //只能传递对象昂,删除时根据Cache中的主键 来比对的
    @Update(onConflict = OnConflictStrategy.REPLACE)
    fun update(cache: Cache): Int
}
