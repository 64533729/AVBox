package com.github.catvod.crawler.python

import com.github.catvod.crawler.Spider

interface IPyLoader {
    fun clear()
    fun setConfig(jsonStr: String?)
    fun setRecentPyKey(key: String?)
    fun getSpider(key: String, cls: String?, ext: String?): Spider
    fun proxyInvoke(params: Map<String, String>?): Array<Any?>?
    fun proxyInvoke(params: Map<String, String>?, key: String?): Array<Any?>?
}
