package com.github.catvod.crawler.js

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCookieJarTest {

    private val jar = MemoryCookieJar()

    @Test
    fun setCookieIsVisibleForSameHost() {
        assertTrue(jar.setCookie("https://a.test/watch", "sid=1; Path=/"))
        assertEquals("sid=1", jar.getCookie("https://a.test/other"))
        assertEquals("", jar.getCookie("https://b.test/watch"))
    }

    @Test
    fun laterCookieReplacesEarlierValue() {
        jar.setCookie("https://a.test/", "sid=1")
        jar.setCookie("https://a.test/", "sid=2")
        assertEquals("sid=2", jar.getCookie("https://a.test/"))
    }

    @Test
    fun expiredCookieIsRemoved() {
        jar.setCookie("https://a.test/", "sid=1")
        jar.setCookie("https://a.test/", "sid=; Max-Age=0")
        assertEquals("", jar.getCookie("https://a.test/"))
    }

    @Test
    fun invalidCookieIsRejected() {
        assertFalse(jar.setCookie("not a url", "sid=1"))
        assertFalse(jar.setCookie("https://a.test/", ""))
    }

    @Test
    fun separateJarsKeepSessionsIsolated() {
        val other = MemoryCookieJar()
        jar.setCookie("https://a.test/", "sid=1")
        other.setCookie("https://a.test/", "sid=2")
        assertEquals("sid=1", jar.getCookie("https://a.test/"))
        assertEquals("sid=2", other.getCookie("https://a.test/"))
    }

    @Test
    fun loadForRequestReturnsParsedCookies() {
        jar.setCookie("https://a.test/", "sid=1; Path=/")
        val cookies = jar.loadForRequest("https://a.test/x".toHttpUrl())
        assertEquals(1, cookies.size)
        assertEquals("sid", cookies[0].name)
        assertEquals("1", cookies[0].value)
    }
}
