package com.github.tvbox.osc.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(cache: Cache): Long

    @Query("select *from cache where `key`=:key")
    suspend fun getCache(key: String?): Cache?

    @Query("select *from cache")
    suspend fun getAll(): List<Cache>

    @Delete
    suspend fun delete(cache: Cache): Int

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(cache: Cache): Int
}
