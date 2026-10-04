package com.github.tvbox.osc.sourcedata

import android.text.TextUtils
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.LOG
import com.google.gson.Gson
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 全站/单源搜索(searchContent)。
 *
 * <p>t4 源的 extend 是阻塞拉取,所以整段搜索请求先挪到准备线程池;
 * 空结果与解析失败都走 [SourceResultParser.postEmptySearchResult] 保持同一形状。
 */
class SearchLoader(
    private val gson: Gson,
    private val extendCache: ConcurrentHashMap<String, String>,
    private val searchResult: SourceChannel<AbsXml?>,
    private val resultParser: SourceResultParser,
) {

    // searchContent
    fun getSearch(sourceKey: String?, wd: String?) {
        getSearch(sourceKey, wd, "")
    }

    fun getSearch(sourceKey: String?, wd: String?, searchToken: String?) {
        getSearch(sourceKey, wd, searchToken, searchResult, "search")
    }

    private fun getSearch(sourceKey: String?, wd: String?, searchToken: String?, result: SourceChannel<AbsXml?>, requestTag: String?) {
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (sourceBean == null) {
            resultParser.postEmptySearchResult(result, sourceKey, searchToken)
            return
        }
        val type = sourceBean.type
        if (type == 3) {
            searchFromSpider(sourceBean, wd, result, searchToken)
        } else if (type == 0 || type == 1) {
            searchFromApi(sourceBean, wd, result, searchToken, requestTag)
        } else if (type == 4) {
            searchFromExtendedApi(sourceBean, wd, result, searchToken, requestTag)
        } else {
            resultParser.postEmptySearchResult(result, sourceBean.key, searchToken)
        }
    }

    /** type 3:爬虫 searchContent;空结果也回一条空 AbsXml,保持与其它分支同形状 */
    private fun searchFromSpider(sourceBean: SourceBean, wd: String?, result: SourceChannel<AbsXml?>, searchToken: String?) {

        try {
            val sp = ApiConfig.get().getCSP(sourceBean)
            val search = sp.searchContent(wd, false)
            if (!TextUtils.isEmpty(search)) {
                resultParser.json(result, search, sourceBean.key, searchToken)
            } else {
                resultParser.json(result, "", sourceBean.key, searchToken)
            }
        } catch (th: Throwable) {
            LOG.e("SourceViewModel", th)
            resultParser.json(result, "", sourceBean.key, searchToken)
        }
    }

    /** type 0/1:站点搜索接口(type 0 走 XML) */
    private fun searchFromApi(sourceBean: SourceBean, wd: String?, result: SourceChannel<AbsXml?>, searchToken: String?, requestTag: String?) {
        // 回调里要按 type 分流 xml/json,值语义与调用点一致(原为捕获上层局部量)
        val type = sourceBean.type

        SourceHelper.siteGet(sourceBean)
            .params("wd", wd)
            .params(if (type == 1) "ac" else null, if (type == 1) "detail" else null)
            .tag(requestTag)
            .execute(object : AbsCallback<String>() {
                override fun convertResponse(response: okhttp3.Response): String {
                    val body = response.body
                    return if (body != null) body.string() else throw IllegalStateException(SourceHelper.ERR_NETWORK)
                }

                override fun onSuccess(response: Response<String>) {
                    if (type == 0) {
                        val xml = response.body()
                        resultParser.xml(result, xml, sourceBean.key, searchToken)
                    } else {
                        val json = response.body()
                        resultParser.json(result, json, sourceBean.key, searchToken)
                    }
                }

                override fun onError(response: Response<String>) {
                    super.onError(response)
                    resultParser.postEmptySearchResult(result, sourceBean.key, searchToken)
                }
            })
    }

    /** type 4:带 extend 的搜索;extend 是阻塞拉取,故整段挪到准备线程池 */
    private fun searchFromExtendedApi(sourceBean: SourceBean, wd: String?, result: SourceChannel<AbsXml?>, searchToken: String?, requestTag: String?) {

        SourceHelper.PREPARE_POOL.execute {
            var extend = sourceBean.ext
            extend = SourceHelper.getFixUrlDirect(extendCache, gson, extend)
            var queryWd = wd
            try {
                queryWd = URLEncoder.encode(queryWd, "UTF-8")
            } catch (e: UnsupportedEncodingException) {
                LOG.e("SourceViewModel", e)
            }

            val request = SourceHelper.siteGet(sourceBean)
                .tag(requestTag)
                .params("wd", queryWd)
                .params("ac", "detail")
                .params("quick", "false")
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend)
            }
            request.execute(object : AbsCallback<String>() {
                override fun convertResponse(response: okhttp3.Response): String {
                    val body = response.body
                    return if (body != null) body.string()
                    else {
                        LOG.i("echo-t4 search-网络请求错误")
                        throw IllegalStateException(SourceHelper.ERR_NETWORK)
                    }
                }

                override fun onSuccess(response: Response<String>) {
                    val json = response.body()
//                            LOG.i("echo-t4 search onSuccess"+json);
                    resultParser.json(result, json, sourceBean.key, searchToken)
                }

                override fun onError(response: Response<String>) {
                    LOG.i("echo-t4 search-onError")
                    super.onError(response)
                    resultParser.postEmptySearchResult(result, sourceBean.key, searchToken)
                }
            })
        }
    }
}
