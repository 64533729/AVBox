package com.github.tvbox.osc.data

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

import com.github.tvbox.osc.util.AppContextHolder
import com.github.tvbox.osc.util.FileUtils

import java.io.File
import java.io.IOException

object AppDataManager {
    private const val DB_FILE_VERSION = 4
    private const val DB_NAME = "tvbox"
    private var manager: AppDataManager? = null
    private var dbInstance: AppDataBase? = null

    @JvmStatic
    fun init() {
        if (manager == null) {
            synchronized(AppDataManager::class.java) {
                if (manager == null) {
                    manager = this
                }
            }
        }
    }

    private fun dbPath(): String {
        return DB_NAME + ".v" + DB_FILE_VERSION + ".db"
    }

    /** 获取(或重建)数据库实例:backup/restore 会 close 并置 null,故需重建;加锁防 check-then-act 竞态重复 build。 */
    @JvmStatic
    fun get(): AppDataBase {
        synchronized(AppDataManager::class.java) {
            if (manager == null) {
                throw RuntimeException("AppDataManager is no init")
            }
            if (dbInstance == null) {
                dbInstance = Room.databaseBuilder(AppContextHolder.context()!!, AppDataBase::class.java, dbPath())
                    .setDriver(BundledSQLiteDriver())
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .allowMainThreadQueries()
                    .build()
            }
            return dbInstance!!
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun backup(path: File): Boolean {
        dbInstance?.close()
        // 置 null,否则 get() 永远返回已关闭实例(后续 Room 操作全抛 "connection pool has been closed")
        dbInstance = null
        val db = AppContextHolder.context()!!.getDatabasePath(dbPath())
        return if (db.exists()) {
            FileUtils.copyFile(db, path)
            true
        } else {
            false
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun restore(path: File): Boolean {
        dbInstance?.close()
        dbInstance = null
        val db = AppContextHolder.context()!!.getDatabasePath(dbPath())
        if (db.exists()) {
            db.delete()
        }
        if (!db.parentFile.exists()) {
            db.parentFile.mkdirs()
        }
        FileUtils.copyFile(path, db)
        return true
    }
}
