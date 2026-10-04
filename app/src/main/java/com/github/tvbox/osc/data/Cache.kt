package com.github.tvbox.osc.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

import java.io.Serializable

/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
@Entity(tableName = "cache")
class Cache : Serializable {
    @PrimaryKey(autoGenerate = false)
    @JvmField
    var key: String = ""

    @JvmField
    var data: ByteArray? = null
}
