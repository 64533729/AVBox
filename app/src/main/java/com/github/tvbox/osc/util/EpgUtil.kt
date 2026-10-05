package com.github.tvbox.osc.util

import android.content.res.AssetManager
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.lang.reflect.Type
import java.util.HashMap

object EpgUtil {

    private var epgDoc: JsonObject? = null
    private val epgHashMap = HashMap<String, JsonObject>()

    @JvmStatic
    fun init() {
        if (epgDoc != null)
            return
        try {
            val assetManager: AssetManager = AppContextHolder.context()!!.assets //获得assets资源管理器（assets中的文件无法直接访问，可以使用AssetManager访问）
            val inputStreamReader = InputStreamReader(assetManager.open("epg_data.json"), "UTF-8") //使用IO流读取json文件内容
            val br = BufferedReader(inputStreamReader) //使用字符高效流
            var line: String? = br.readLine()
            val builder = StringBuilder()
            while (line != null) {
                builder.append(line)
                line = br.readLine()
            }
            br.close()
            inputStreamReader.close()
            if (!builder.toString().isEmpty()) {
                epgDoc = Gson().fromJson(builder.toString(), JsonObject::class.java as Type) // 从builder中读取了json中的数据。
                for (opt: JsonElement in epgDoc!!.get("epgs").asJsonArray) {
                    val obj = opt as JsonObject
                    val name = obj.get("name").asString.trim { it <= ' ' }
                    val names = RegexUtils.getPattern(",").split(name)
                    for (string in names) {
                        epgHashMap[string] = obj
                    }
                }
                return
            }

        } catch (e: IOException) {
            LOG.e("EpgUtil", e)
        }
    }

    @JvmStatic
    fun getEpgInfo(channelName: String): Array<String>? {
        try {
            if (epgHashMap.containsKey(channelName)) {
                val obj = epgHashMap[channelName]!!
                return arrayOf(
                    obj.get("logo").asString,
                    obj.get("epgid").asString
                )
            }
        } catch (ex: Exception) {
            LOG.e("EpgUtil", ex)
        }
        return null
    }
}
