package com.github.catvod.crawler.js

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ConnectTimeoutTest {

    @Test
    fun withTimeout_appliesCrawlerTimeout() {
        val derived = Connect.withTimeout(Req.objectFrom("{\"timeout\":30000}"), OkHttpClient.Builder().build())
        assertEquals(30_000, derived.readTimeoutMillis)
        assertEquals(30_000, derived.connectTimeoutMillis)
        assertEquals(30_000, derived.writeTimeoutMillis)
    }

    @Test
    fun withTimeout_reusesBaseWhenNothingToApply() {
        val base = OkHttpClient.Builder().build()
        assertSame(base, Connect.withTimeout(Req.objectFrom("{}"), base))
        assertSame(base, Connect.withTimeout(Req.objectFrom("{\"timeout\":10000}"), base))
        assertSame(base, Connect.withTimeout(Req.objectFrom("{\"timeout\":0}"), base))
        assertSame(base, Connect.withTimeout(Req.objectFrom("{\"timeout\":-1}"), base))
    }

    @Test
    fun derivedClientKeepsSharedDispatcherSoTagCancelStillWorks() {
        val base = OkHttpClient.Builder().build()
        val derived = Connect.withTimeout(Req.objectFrom("{\"timeout\":3000}"), base)
        assertSame(base.dispatcher, derived.dispatcher)
        assertSame(base.connectionPool, derived.connectionPool)
    }

    @Test
    fun twoBuildsFromOneBuilderShareDispatcher() {
        val builder = OkHttpClient.Builder()
        val defaultClient = builder.build()
        builder.followRedirects(false).followSslRedirects(false)
        val noRedirectClient = builder.build()
        assertSame(defaultClient.dispatcher, noRedirectClient.dispatcher)
    }

    @Test
    fun withTimeout_keepsBaseRedirectSetting() {
        val noRedirect = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val derived = Connect.withTimeout(Req.objectFrom("{\"timeout\":3000}"), noRedirect)
        assertNotSame(noRedirect, derived)
        assertFalse(derived.followRedirects)
    }
}
