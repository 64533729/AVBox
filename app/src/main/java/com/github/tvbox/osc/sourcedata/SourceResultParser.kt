package com.github.tvbox.osc.sourcedata

import android.text.TextUtils

import androidx.lifecycle.MutableLiveData

import com.github.tvbox.osc.bean.AbsJson
import com.github.tvbox.osc.bean.AbsSortJson
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.util.LOG
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.thoughtworks.xstream.XStream
import com.thoughtworks.xstream.io.xml.DomDriver

import org.greenrobot.eventbus.EventBus

import java.util.ArrayList
import java.util.LinkedHashMap

/**
 * 站点返回数据的解析与分投:XML/JSON → AbsXml,JSON → AbsSortXml,再按目标通道分投。
 *
 * 分投口径是三通道身份判定:搜索通道走 EventBus(面板自行收),
 * 详情通道要过 push/迅雷后处理,其余通道直接 postValue。
 */
class SourceResultParser(
    private val gson: Gson,
    private val searchResult: MutableLiveData<AbsXml>,
    private val detailResult: MutableLiveData<AbsXml>,
    private val pushDetailResolver: PushDetailResolver,
) {

    private fun getSortFilter(obj: JsonObject): MovieSort.SortFilter {
        val key = obj.get("key").asString
        val name = obj.get("name").asString
        val kv = obj.getAsJsonArray("value")
        val values = LinkedHashMap<String, String>()
        // 2026-09-10 BugFix:必须存 (v → n),与 FilterSheet 消费约定一致(显示 map value=显示名 n,
        // 选中发送 map key=筛选值 v);原 put(n, v) 写反,导致胶囊显示英文 v 且发错筛选值
        for (ele in kv) {
            val ele_obj = ele.asJsonObject
            val values_value = if (ele_obj.has("v")) ele_obj.get("v").asString else ""
            val values_name = if (ele_obj.has("n")) ele_obj.get("n").asString else ""
            values[values_value] = values_name
        }
        val filter = MovieSort.SortFilter()
        filter.key = key
        filter.name = name
        filter.values = values
        return filter
    }

    fun sortJson(result: MutableLiveData<AbsSortXml>?, json: String?): AbsSortXml? {
        try {
            if (TextUtils.isEmpty(json)) {
                return AbsSortJson().toAbsSortXml()
            }
            val obj = JsonParser.parseString(json!!).asJsonObject
            val sortJson = gson.fromJson<AbsSortJson>(obj, object : TypeToken<AbsSortJson>() {}.type)
            val data = sortJson.toAbsSortXml()
            try {
                if (obj.has("filters")) {
                    val sortFilters = LinkedHashMap<String?, ArrayList<MovieSort.SortFilter>>()
                    val filters = obj.getAsJsonObject("filters")
                    for (key in filters.keySet()) {
                        val sortFilter = ArrayList<MovieSort.SortFilter>()
                        val one = filters.get(key)
                        if (one.isJsonObject) {
                            sortFilter.add(getSortFilter(one.asJsonObject))
                        } else {
                            for (ele in one.asJsonArray) {
                                sortFilter.add(getSortFilter(ele.asJsonObject))
                            }
                        }
                        sortFilters[key] = sortFilter
                    }
                    val sortList = data.classes?.sortList
                    if (sortList != null) {
                        for (sort in sortList) {
                            if (sortFilters.containsKey(sort.id) && sortFilters[sort.id] != null) {
                                sort.filters = sortFilters[sort.id]!!
                            }
                        }
                    }
                }
            } catch (th: Throwable) {
                LOG.d("SourceViewModel", "sort filters parse failed, continue without filters")
            }
            return data
        } catch (e: Exception) {
            val head = if (json == null) "null" else json.substring(0, Math.min(200, json.length))
            LOG.i("echo--parse-fail-sortJson: ex=$e head=$head")
            return null
        }
    }

    fun sortXml(result: MutableLiveData<AbsSortXml>?, xml: String?): AbsSortXml? {
        try {
            val xstream = sortXStream.get()!!
            val data = xstream.fromXML(xml) as AbsSortXml
            for (sort in data.classes!!.sortList!!) {
                if (sort.filters == null) {
                    sort.filters = ArrayList()
                }
            }
            return data
        } catch (e: Exception) {
            val head = if (xml == null) "null" else xml.substring(0, Math.min(200, xml.length))
            LOG.i("echo--parse-fail-sortXml: ex=$e head=$head")
            return null
        }
    }

    fun xml(result: MutableLiveData<AbsXml>?, xml: String?, sourceKey: String?): AbsXml? {
        return xml(result, xml, sourceKey, "")
    }

    fun xml(result: MutableLiveData<AbsXml>?, xml: String?, sourceKey: String?, searchToken: String?): AbsXml? {
        return xml(result, xml, sourceKey, searchToken, null)
    }

    fun xml(result: MutableLiveData<AbsXml>?, xml: String?, sourceKey: String?, searchToken: String?, detailToken: Int?): AbsXml? {
        var text: String? = xml
        try {
            val xstream = listXStream.get()!!
            val original = text!!
            if (original.contains("<year></year>")) {
                text = original.replace("<year></year>", "<year>0</year>")
            }
            val withYear = text
            if (withYear.contains("<state></state>")) {
                text = withYear.replace("<state></state>", "<state>0</state>")
            }
            var data = xstream.fromXML(text) as AbsXml
            SourceHelper.absXml(data, sourceKey, searchToken)
            data.detailToken = detailToken
            if (searchResult === result) {
                EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data))
            } else if (result != null) {
                if (result === detailResult) {
                    data = pushDetailResolver.checkPush(data)
                    pushDetailResolver.checkThunder(data, 0)
                } else {
                    postSearchResult(result, data)
                }
            }
            return data
        } catch (e: Exception) {
            if (result != null) {
                val head = if (text == null) "null" else text.substring(0, Math.min(200, text.length))
                LOG.i("echo--parse-fail-xml:$sourceKey ex=$e head=$head")
            }
            if (searchResult === result) {
                postEmptySearchResult(result, sourceKey, searchToken)
            } else if (result != null) {
                if (result === detailResult) {
                    result.postValue(createEmptyDetail(sourceKey, detailToken))
                } else {
                    result.postValue(null)
                }
            }
            return null
        }
    }

    fun json(result: MutableLiveData<AbsXml>?, json: String?, sourceKey: String?): AbsXml? {
        return json(result, json, sourceKey, "")
    }

    fun json(result: MutableLiveData<AbsXml>?, json: String?, sourceKey: String?, searchToken: String?): AbsXml? {
        return json(result, json, sourceKey, searchToken, null)
    }

    fun json(result: MutableLiveData<AbsXml>?, json: String?, sourceKey: String?, searchToken: String?, detailToken: Int?): AbsXml? {
        try {
            if (json == null || json.trim().isEmpty()) {
                if (result != null) {
                    LOG.i("echo--parse-empty-body:$sourceKey (站点返回空响应;JSON 型源(ac=detail)拿不到内容时常见,或该源实为 XML 类型)")
                }
                if (searchResult === result) {
                    postEmptySearchResult(result, sourceKey, searchToken)
                } else if (result === detailResult) {
                    result.postValue(createEmptyDetail(sourceKey, detailToken))
                } else if (result != null) {
                    result.postValue(null)
                }
                return null
            }
            val absJson = gson.fromJson<AbsJson>(json, object : TypeToken<AbsJson>() {}.type)
            if (absJson == null) {
                throw IllegalStateException("json 非空但解析不出对象: $json")
            }
            var data = absJson.toAbsXml()
            SourceHelper.absXml(data, sourceKey, searchToken)
            data.detailToken = detailToken
            if (searchResult === result) {
                EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data))
            } else if (result != null) {
                if (result === detailResult) {
                    data = pushDetailResolver.checkPush(data)
                    pushDetailResolver.checkThunder(data, 0)
                } else {
                    postSearchResult(result, data)
                }
            }
            return data
        } catch (e: Exception) {
            if (result != null) {
                // json 可能是 null(接口 onError 分支、爬虫超时):裸取 substring 会再抛 NPE,线程死掉 UI 永远转圈
                val head = if (json == null) "null" else json.substring(0, Math.min(200, json.length))
                LOG.i("echo--parse-fail-json:$sourceKey ex=$e head=$head")
            }
            if (searchResult === result) {
                postEmptySearchResult(result, sourceKey, searchToken)
            } else if (result != null) {
                if (result === detailResult) {
                    result.postValue(createEmptyDetail(sourceKey, detailToken))
                } else {
                    result.postValue(null)
                }
            }
            return null
        }
    }

    fun postEmptySearchResult(result: MutableLiveData<AbsXml>?, sourceKey: String?, searchToken: String?) {
        val data = AbsXml()
        data.sourceKey = sourceKey
        data.searchToken = searchToken
        if (searchResult === result) {
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data))
        } else if (result != null) {
            postSearchResult(result, data)
        }
    }

    /** 解析失败的空详情:与 DetailLoader 的同名出口同形状,且带代次(V4) */
    private fun createEmptyDetail(sourceKey: String?, detailToken: Int?): AbsXml {
        val data = AbsXml()
        data.sourceKey = sourceKey
        data.detailToken = detailToken
        return data
    }

    private fun postSearchResult(result: MutableLiveData<AbsXml>, data: AbsXml) {
        result.postValue(data)
    }

    companion object {

        // XStream 非线程安全:按线程缓存实例复用(勿改共享单例)
        private val sortXStream: ThreadLocal<XStream> = object : ThreadLocal<XStream>() {
            override fun initialValue(): XStream {
                val xstream = XStream(DomDriver())
                xstream.autodetectAnnotations(true)
                xstream.processAnnotations(AbsSortXml::class.java)
                xstream.ignoreUnknownElements()
                return xstream
            }
        }

        private val listXStream: ThreadLocal<XStream> = object : ThreadLocal<XStream>() {
            override fun initialValue(): XStream {
                val xstream = XStream(DomDriver())
                xstream.autodetectAnnotations(true)
                xstream.processAnnotations(AbsXml::class.java)
                xstream.ignoreUnknownElements()
                return xstream
            }
        }
    }
}
