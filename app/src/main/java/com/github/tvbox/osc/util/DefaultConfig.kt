package com.github.tvbox.osc.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.text.TextUtils

import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.server.ControlManager
import com.google.gson.JsonObject

import java.util.ArrayList
import java.util.Collections
import java.util.regex.Pattern

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
object DefaultConfig {

    @JvmStatic
    fun adjustSort(sourceKey: String?, list: List<MovieSort.SortData>?, withMy: Boolean): List<MovieSort.SortData> {
        var data: MutableList<MovieSort.SortData> = ArrayList()
        if (sourceKey != null && list != null) {
            val sb = ApiConfig.get().getSource(sourceKey)
            if (sb == null || sb.categories == null) {
                for (sortData in list) {
                    if (sortData.filters == null)
                        sortData.filters = ArrayList()
                    data.add(sortData)
                }
                if (withMy)
                    data.add(0, MovieSort.SortData("my0", "主页")) // i18n: keep(默认配置数据)
                Collections.sort(data)
                return data
            }
            val categories = sb.categories!!
            if (!categories.isEmpty()) {
                data = pickByCategories(list, categories)
            } else {
                for (sortData in list) {
                    if (sortData.filters == null)
                        sortData.filters = ArrayList()
                    data.add(sortData)
                }
            }
        }
        if (withMy)
            data.add(0, MovieSort.SortData("my0", "主页")) // i18n: keep(默认配置数据)
        Collections.sort(data)
        return data
    }

    /** 按白名单挑分类;一个都没匹配上退化为全量,否则源改分类名会把首页整页过滤成空 */
    @JvmStatic
    fun pickByCategories(list: List<MovieSort.SortData>, categories: List<String>): MutableList<MovieSort.SortData> {
        val data = ArrayList<MovieSort.SortData>()
        for (cate in categories) {
            for (sortData in list) {
                if (sortData.name == cate) {
                    if (sortData.filters == null)
                        sortData.filters = ArrayList()
                    data.add(sortData)
                }
            }
        }
        if (data.isEmpty()) {
            for (sortData in list) {
                if (sortData.filters == null)
                    sortData.filters = ArrayList()
                data.add(sortData)
            }
        }
        return data
    }

    @JvmStatic
    fun getAppVersionCode(mContext: Context): Int {
        //包管理操作管理类
        val pm = mContext.packageManager
        try {
            val packageInfo = pm.getPackageInfo(mContext.packageName, 0)
            return packageInfo.versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            LOG.e("DefaultConfig", e)
        }
        return -1
    }

    @JvmStatic
    fun getAppVersionName(mContext: Context): String? {
        //包管理操作管理类
        val pm = mContext.packageManager
        try {
            val packageInfo = pm.getPackageInfo(mContext.packageName, 0)
            return packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            LOG.e("DefaultConfig", e)
        }
        return ""
    }

    /**
     * 后缀
     *
     * @param name
     * @return
     */
    @JvmStatic
    fun getFileSuffix(name: String?): String {
        if (TextUtils.isEmpty(name)) {
            return ""
        }
        val endP = name!!.lastIndexOf(".")
        return if (endP > -1) name.substring(endP) else ""
    }

    /**
     * 获取文件的前缀
     *
     * @param fileName
     * @return
     */
    @JvmStatic
    fun getFilePrefixName(fileName: String?): String {
        if (TextUtils.isEmpty(fileName)) {
            return ""
        }
        val start = fileName!!.lastIndexOf(".")
        return if (start > -1) fileName.substring(0, start) else fileName
    }

    private val snifferMatch = Pattern.compile(
        "http((?!http).){12,}?\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3|aac|mpd)\\?.*|" +
                "http((?!http).){12,}\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3|aac|mpd)|" +
                "http((?!http).)*?video/tos*|" +
                "http((?!http).){20,}?/m3u8\\?pt=m3u8.*|" +
                "http((?!http).)*?default\\.ixigua\\.com/.*|" +
                "http((?!http).)*?dycdn-tos\\.pstatp[^\\?]*|" +
                "http.*?/player/m3u8play\\.php\\?url=.*|" +
                "http.*?/player/.*?[pP]lay\\.php\\?url=.*|" +
                "http.*?/playlist/m3u8/\\?vid=.*|" +
                "http.*?\\.php\\?type=m3u8&.*|" +
                "http.*?/download.aspx\\?.*|" +
                "http.*?/api/up_api.php\\?.*|" +
                "https.*?\\.66yk\\.cn.*|" +
                "http((?!http).)*?netease\\.com/file/.*"
    )
    @JvmStatic
    fun isVideoFormat(url: String): Boolean {
        val uri = Uri.parse(url)
        val path = uri.path
        if (TextUtils.isEmpty(path)) {
            return false
        }
        if (snifferMatch.matcher(url).find()) return true
        return false
    }


    @JvmStatic
    fun safeJsonString(obj: JsonObject, key: String, defaultVal: String): String {
        try {
            if (obj.has(key)) {
                return if (obj.get(key).isJsonObject || obj.get(key).isJsonArray)
                    obj.get(key).toString().trim { it <= ' ' }
                else
                    obj.getAsJsonPrimitive(key).asString.trim { it <= ' ' }
            } else
                return defaultVal
        } catch (th: Throwable) {
            LOG.d("DefaultConfig", "json key '" + key + "' not a plain string, use default")
        }
        return defaultVal
    }

    @JvmStatic
    fun safeJsonInt(obj: JsonObject, key: String, defaultVal: Int): Int {
        try {
            if (obj.has(key))
                return obj.getAsJsonPrimitive(key).asInt
            else
                return defaultVal
        } catch (th: Throwable) {
            LOG.d("DefaultConfig", "json key '" + key + "' not a number, use default")
        }
        return defaultVal
    }

    @JvmStatic
    fun safeJsonStringList(obj: JsonObject, key: String): ArrayList<String> {
        val result = ArrayList<String>()
        try {
            if (obj.has(key)) {
                if (obj.get(key).isJsonObject) {
                    result.add(obj.get(key).asString)
                } else {
                    for (opt in obj.getAsJsonArray(key)) {
                        result.add(opt.asString)
                    }
                }
            }
        } catch (th: Throwable) {
            LOG.d("DefaultConfig", "json key '" + key + "' not a string list, use empty")
        }
        return result
    }

    @JvmStatic
    fun checkReplaceProxy(urlOri: String): String {
        if (urlOri.startsWith("proxy://"))
            return urlOri.replace("proxy://", ControlManager.get().getAddress(true) + "proxy?")
        return urlOri
    }

    private val NO_AD_KEYWORDS = listOf(
        "tx", "youku", "qq", "qiyi", "letv", "leshi", "sohu", "mgtv", "bilibili", "imgo", "优酷", "芒果", "腾讯", "奇艺" // i18n: keep(默认配置数据)
    )

    @JvmStatic
    fun noAd(flag: String?): Boolean {
        if (flag == null || flag.isEmpty()) return false
        for (keyword in NO_AD_KEYWORDS) {
            if (flag == keyword || flag.contains(keyword)) {
                return true
            }
        }
        return false
    }
}
