package com.github.tvbox.osc.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

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

    @Query("DELETE FROM vodRecord where `cid`=:cid and id NOT IN (SELECT id FROM vodRecord WHERE `cid`=:cid ORDER BY updateTime desc, id desc LIMIT :size)")
    fun reserver(cid: String?, size: Int): Int
}
