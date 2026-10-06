package com.github.tvbox.osc.data

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(entities = [Cache::class, VodRecord::class, VodCollect::class], version = 1)
abstract class AppDataBase : RoomDatabase() {
    abstract fun getCacheDao(): CacheDao

    abstract fun getVodRecordDao(): VodRecordDao

    abstract fun getVodCollectDao(): VodCollectDao
}
