package com.github.tvbox.osc.sourcedata

import android.text.TextUtils
import androidx.lifecycle.ViewModel
import com.github.catvod.crawler.Spider
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.util.LOG
import com.google.gson.Gson
import org.json.JSONObject

/**
 * 站点取数门面:对外只暴露通道 + 入口方法,取数实现按职责分在同包 Loader 里。
 *
 * <p>门面自己只保留跨 Loader 共享的东西:7 个结果通道;homeContent/extend 缓存归
 * [SourceRuntimeState],换源清理因此仍有唯一出口(线程池仍在 [SourceHelper])。
 *
 * <p>7 个通道已由裸 `MutableLiveData` 换成 [SourceChannel]:消费侧(Kotlin 页面 VM)收 Flow,
 * 播放层(Java)在 M7 迁移前走通道的 LiveData 兼容面。
 *
 * @author pj567
 */
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

    /** 下一集预解析专用通道（预载方案,与 playResult 独立 seq 防串扰,规格 §5.2） */
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

    /**
     * V4:详情回包带代次(原"换实例"隔离迟到回包的替代)。`requestToken=null` 表示不判代次(老调用点)。
     */
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

    /** 下一集预解析（预载方案）:结果走 preloadResult 通道,seq 独立于真实播放请求,互不作废 */
    fun getPlayForPreload(sourceKey: String?, playFlag: String?, progressKey: String?, url: String?, subtitleKey: String?) {
        playLoader.getPlayForPreload(sourceKey, playFlag, progressKey, url, subtitleKey)
    }

    fun cancelPlayRequest() {
        playLoader.cancelPlayRequest()
    }

    /** 磁力链接交给迅雷解析改写,结果回投 detailResult */
    fun checkThunder(data: AbsXml, index: Int) {
        pushDetailResolver.checkThunder(data, index)
    }

    override fun onCleared() {
        super.onCleared()
    }
}
