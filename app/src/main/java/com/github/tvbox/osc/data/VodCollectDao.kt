package com.github.tvbox.osc.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface VodCollectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: VodCollect): Long

    @Query("select * from vodCollect  order by updateTime desc")
    fun getAll(): List<VodCollect>

    @Query("select * from vodCollect where `id`=:id")
    fun getVodCollect(id: Int): VodCollect?

    @Query("delete from vodCollect where `id`=:id")
    fun delete(id: Int)

    @Query("select * from vodCollect where `cid`=:cid and `sourceKey`=:sourceKey and `vodId`=:vodId")
    fun getVodCollect(cid: String?, sourceKey: String?, vodId: String?): VodCollect?

    @Delete
    fun delete(record: VodCollect): Int

    @Query("DELETE FROM vodCollect")
    fun deleteAll()
}
