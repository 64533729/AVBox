package com.github.catvod.crawler.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReqOptionsTest {

    @Test
    fun defaultsKeepCrawlerBehaviour() {
        val req = Req.objectFrom("{}")
        assertEquals(10_000, req.getTimeout())
        assertEquals(0, req.getCallTimeout())
        assertFalse(req.isCookie())
        assertNull(req.getSessionId())
    }

    @Test
    fun parsesSessionOptions() {
        val req = Req.objectFrom("{\"timeout\":3000,\"callTimeout\":1500,\"cookie\":true,\"__session\":7}")
        assertEquals(3000, req.getTimeout())
        assertEquals(1500, req.getCallTimeout())
        assertTrue(req.isCookie())
        assertEquals(7, req.getSessionId())
    }

    @Test
    fun cookieFalseStaysOff() {
        assertFalse(Req.objectFrom("{\"cookie\":false}").isCookie())
    }
}
