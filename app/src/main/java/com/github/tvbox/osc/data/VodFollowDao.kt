package com.github.tvbox.osc.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface VodFollowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: VodFollow): Long

    @Query("select * from vodFollow order by addedTime desc")
    fun getAll(): List<VodFollow>

    @Query("select * from vodFollow where `cid`=:cid and `sourceKey`=:sourceKey and `vodId`=:vodId")
    fun getVodFollow(cid: String?, sourceKey: String?, vodId: String?): VodFollow?

    @Query("delete from vodFollow where `id` in (:ids)")
    fun deleteSelected(ids: List<Int>)
}
