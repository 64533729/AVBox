package com.github.tvbox.osc.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 *
 * 入参一律声明可空:Java 侧原为平台类型,传 null 时 SQL 匹配不到行(既有行为);写成非空会让生成实现
 * 插入判空,把"查不到"变成 NPE。
 */
@Dao
interface VodRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: VodRecord): Long

    @Query("select * from vodRecord where `cid`=:cid order by updateTime desc, id desc limit :size")
    fun getAll(cid: String?, size: Int): List<VodRecord>

    @Query("select * from vodRecord where `cid`=:cid and `sourceKey`=:sourceKey and `vodId`=:vodId")
    fun getVodRecord(cid: String?, sourceKey: String?, vodId: String?): VodRecord?

    @Delete
    fun delete(record: VodRecord): Int

    @Query("select count(*) from vodRecord where `cid`=:cid")
    fun getCount(cid: String?): Int

    @Query("DELETE FROM vodRecord where `cid`=:cid")
    fun deleteAll(cid: String?)

    /**
     * 保留最新指定条数, 其他删除.
     * @param size 保留条数
     * @return
     */
    @Query("DELETE FROM vodRecord where `cid`=:cid and id NOT IN (SELECT id FROM vodRecord WHERE `cid`=:cid ORDER BY updateTime desc, id desc LIMIT :size)")
    fun reserver(cid: String?, size: Int): Int
}
