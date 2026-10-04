package com.github.tvbox.osc.util

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object RegexUtils {

    // BugReview #26:被 NanoHTTPD 线程、主线程、QuickJS loadModule 线程并发读写,改并发容器
    private val patternCache = ConcurrentHashMap<String, Pattern>()

    @JvmStatic
    fun getPattern(regex: String): Pattern {
        var pattern = patternCache[regex]
        if (pattern == null) {
            pattern = Pattern.compile(regex)
            patternCache[regex] = pattern
        }
        return pattern
    }

    @JvmStatic
    fun getPattern(regex: String, flag: Int): Pattern {
        var pattern = patternCache[regex]
        if (pattern == null) {
            pattern = Pattern.compile(regex, flag)
            patternCache[regex] = pattern
        }
        return pattern
    }
}
