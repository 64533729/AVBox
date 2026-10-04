package com.github.tvbox.osc.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

import java.io.Serializable

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
@Entity(tableName = "vodRecord")
class VodRecord : Serializable {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0

    @JvmField
    @ColumnInfo(name = "vodId")
    var vodId: String? = null

    @JvmField
    @ColumnInfo(name = "updateTime")
    var updateTime: Long = 0

    @JvmField
    @ColumnInfo(name = "sourceKey")
    var sourceKey: String? = null

    /** 订阅标识(当前生效的配置地址):历史按它隔离,换订阅后旧订阅的历史不再列出 */
    @JvmField
    @ColumnInfo(name = "cid")
    var cid: String? = null

    @JvmField
    var dataJson: String? = null
}
