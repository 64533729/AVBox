package com.github.catvod.crawler.js

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

import org.json.JSONObject

class Json {

    companion object {

        @JvmStatic
        fun valid(text: String?): Boolean {
            try {
                JSONObject(text)
                return true
            } catch (e: Exception) {
                return false
            }
        }

        @JvmStatic
        fun invalid(text: String?): Boolean {
            return !valid(text)
        }

        @JvmStatic
        fun safeString(obj: JsonObject, key: String): String {
            try {
                return obj.getAsJsonPrimitive(key).asString.trim()
            } catch (e: Exception) {
                return ""
            }
        }

        @JvmStatic
        fun safeListString(obj: JsonObject, key: String): List<String> {
            val result = ArrayList<String>()
            if (!obj.has(key)) return result
            if (obj.get(key).isJsonObject) result.add(safeString(obj, key))
            else for (opt in obj.getAsJsonArray(key)) result.add(opt.asString)
            return result
        }

        @JvmStatic
        fun safeListElement(obj: JsonObject, key: String): List<JsonElement> {
            val result = ArrayList<JsonElement>()
            if (!obj.has(key)) return result
            if (obj.get(key).isJsonObject) result.add(obj.get(key).asJsonObject)
            for (opt in obj.getAsJsonArray(key)) result.add(opt.asJsonObject)
            return result
        }

        // 入参可空:调用方(Req/Res 的 getHeader)会把 null 传进来,Java 版靠解引用抛 NPE 后落 catch 兜底
        @JvmStatic
        fun safeObject(element: JsonElement?): JsonObject {
            try {
                val source = element!!
                return if (source.isJsonPrimitive) JsonParser.parseString(source.asJsonPrimitive.asString).asJsonObject else source.asJsonObject
            } catch (e: Exception) {
                return JsonObject()
            }
        }

        @JvmStatic
        fun toMap(element: JsonElement?): Map<String, String> {
            val map = HashMap<String, String>()
            val obj = safeObject(element)
            for (key in obj.keySet()) map[key] = safeString(obj, key)
            return map
        }
    }
}
