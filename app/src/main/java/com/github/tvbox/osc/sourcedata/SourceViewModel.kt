package com.github.tvbox.osc.sourcedata

import android.text.TextUtils
import androidx.lifecycle.ViewModel
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.util.LOG
import com.google.gson.Gson
import org.json.JSONObject

class SourceViewModel : ViewModel() {

    @JvmField
    val sortResult = SourceChannel<AbsSortXml?>()

    @JvmField
    val listResult = SourceChannel<AbsXml?>()

    @JvmField
    val searchResult = SourceChannel<AbsXml?>()

    @JvmField
    val detailResult = SourceChannel<AbsXml?>()

    @JvmField
    val actionResult = SourceChannel<JSONObject?>()

    @JvmField
    val playResult = SourceChannel<JSONObject?>()

    @JvmField
    val preloadResult = SourceChannel<JSONObject?>()

    private val gson = Gson()
    private val pushDetailResolver = PushDetailResolver(gson, detailResult)
    private val resultParser = SourceResultParser(gson, searchResult, detailResult, pushDetailResolver)
    private val listLoader = ListLoader(gson, SourceRuntimeState.extendCache, listResult, resultParser)
    private val sortLoader = SortLoader(gson, SourceRuntimeState.extendCache, SourceRuntimeState.sortCache, sortResult, listLoader, resultParser)
    private val detailLoader = DetailLoader(gson, SourceRuntimeState.extendCache, detailResult, resultParser)
    private val searchLoader = SearchLoader(gson, SourceRuntimeState.extendCache, searchResult, resultParser)
    private val playLoader = PlayLoader(gson, SourceRuntimeState.extendCache, playResult, preloadResult)

    fun getSort(sourceKey: String?) {
        sortLoader.getSort(sourceKey)
    }

    fun getSort(sourceKey: String?, withRec: Boolean) {
        sortLoader.getSort(sourceKey, withRec)
    }

    fun getList(sortData: MovieSort.SortData?, page: Int) {
        listLoader.getList(sortData, page)
    }

    fun getDetail(sourceKey: String?, urlid: String) {
        detailLoader.getDetail(sourceKey, urlid)
    }

    fun getDetail(sourceKey: String?, urlid: String, fallback: Boolean) {
        detailLoader.getDetail(sourceKey, urlid, fallback)
    }

    fun getDetail(sourceKey: String?, urlid: String, fallback: Boolean, requestToken: Int?) {
        detailLoader.getDetail(sourceKey, urlid, fallback, requestToken)
    }

    fun action(sourceKey: String?, action: String?) {
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (sourceBean == null || action == null) {
            actionResult.postValue(null)
            return
        }
        if (sourceBean.type == 3) {
            SourceHelper.SPIDER_POOL.execute {
                try {
                    val sp = ApiConfig.get().getCSP(sourceBean)
                    val json = sp.action(action)
                    actionResult.postValue(if (TextUtils.isEmpty(json)) null else JSONObject(json))
                } catch (th: Throwable) {
                    LOG.e("SourceViewModel", th)
                    actionResult.postValue(null)
                }
            }
        } else {
            actionResult.postValue(null)
        }
    }

    fun getSearch(sourceKey: String?, wd: String?) {
        searchLoader.getSearch(sourceKey, wd)
    }

    fun getSearch(sourceKey: String?, wd: String?, searchToken: String?) {
        searchLoader.getSearch(sourceKey, wd, searchToken)
    }

    fun getPlay(sourceKey: String?, playFlag: String?, progressKey: String?, url: String?, subtitleKey: String?) {
        playLoader.getPlay(sourceKey, playFlag, progressKey, url, subtitleKey)
    }

    fun getPlayForPreload(sourceKey: String?, playFlag: String?, progressKey: String?, url: String?, subtitleKey: String?) {
        playLoader.getPlayForPreload(sourceKey, playFlag, progressKey, url, subtitleKey)
    }

    fun cancelPlayRequest() {
        playLoader.cancelPlayRequest()
    }

    fun checkThunder(data: AbsXml, index: Int) {
        pushDetailResolver.checkThunder(data, index)
    }

    override fun onCleared() {
        super.onCleared()
    }
}
