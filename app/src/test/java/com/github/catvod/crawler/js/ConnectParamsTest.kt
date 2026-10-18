package com.github.catvod.crawler.js

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectParamsTest {

    @Test
    fun noParamsKeepsUrl() {
        assertEquals("http://a/b", Connect.buildUrl("http://a/b", null))
        assertEquals("http://a/b", Connect.buildUrl("http://a/b", JsonParser.parseString("{}")))
    }

    @Test
    fun appendsAndEncodes() {
        val params = JsonParser.parseString("{\"name\":\"斗罗 大陆\",\"page\":2}")
        assertEquals(
            "http://a/b?name=%E6%96%97%E7%BD%97+%E5%A4%A7%E9%99%86&page=2",
            Connect.buildUrl("http://a/b", params)
        )
    }

    @Test
    fun keepsExistingQuery() {
        val params = JsonParser.parseString("{\"a\":\"1\"}")
        assertEquals("http://a/b?x=1&a=1", Connect.buildUrl("http://a/b?x=1", params))
    }

    @Test
    fun arrayValueRepeatsKey() {
        val params = JsonParser.parseString("{\"id\":[1,2]}")
        assertEquals("http://a/b?id=1&id=2", Connect.buildUrl("http://a/b", params))
    }

    @Test
    fun nullValueIsSkipped() {
        val params = JsonParser.parseString("{\"a\":null,\"b\":\"c\"}")
        assertEquals("http://a/b?b=c", Connect.buildUrl("http://a/b", params))
    }
}
