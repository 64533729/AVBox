package com.github.catvod.crawler.js

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlQueryTest {

    private val html = """
        <html><body>
        <article class="video" data-id="v1">
            <h2>影片一</h2>
            <span class="actor">演員甲</span>
            <span class="actor">演員乙</span>
        </article>
        <article class="video" data-id="v2">
            <h2>影片二</h2>
            <span class="actor">演員丙</span>
        </article>
        </body></html>
    """.trimIndent()

    @Test
    fun query_readsTextAttributeAndArrayFields() {
        val plan = """
            {"queries":{"videos":{"selector":"article.video","fields":{
                "id":{"attribute":"data-id"},
                "title":{"selector":"h2"},
                "actors":{"selector":".actor","all":true}
            }}}}
        """.trimIndent()
        val result = JsonParser.parseString(HtmlQuery.query(html, plan)).asJsonObject
        val videos = result.getAsJsonArray("videos")
        assertEquals(2, videos.size())
        val first = videos[0].asJsonObject
        assertEquals("v1", first.get("id").asString)
        assertEquals("影片一", first.get("title").asString)
        assertEquals(2, first.getAsJsonArray("actors").size())
        assertEquals("演員甲", first.getAsJsonArray("actors")[0].asString)
    }

    @Test
    fun query_missingAttributeBecomesNull() {
        val plan = """{"queries":{"videos":{"selector":"article.video","fields":{"id":{"attribute":"missing"}}}}}"""
        val videos = JsonParser.parseString(HtmlQuery.query(html, plan)).asJsonObject.getAsJsonArray("videos")
        assertTrue(videos[0].asJsonObject.get("id").isJsonNull)
    }

    @Test
    fun query_withoutSelectorsUsesWholeDocument() {
        val plan = """{"queries":{"text":{"fields":{"body":{"selector":"h2"}}}}}"""
        val result = JsonParser.parseString(HtmlQuery.query(html, plan)).asJsonObject
        val rows = result.getAsJsonArray("text")
        assertEquals(1, rows.size())
        assertEquals("影片一", rows[0].asJsonObject.get("body").asString)
    }

    @Test
    fun query_exceedingMaxRowsThrows() {
        val plan = """{"queries":{"videos":{"selector":"article.video","maxRows":1,"fields":{"id":{"attribute":"data-id"}}}}}"""
        assertThrows(IllegalArgumentException::class.java) {
            HtmlQuery.query(html, plan)
        }
    }

    @Test
    fun query_rejectsUnknownOption() {
        val plan = """{"queries":{"videos":{"selector":"article.video","unknown":true,"fields":{"id":{"attribute":"data-id"}}}}}"""
        assertThrows(IllegalArgumentException::class.java) {
            HtmlQuery.query(html, plan)
        }
    }

    @Test
    fun query_rejectsOversizedHtml() {
        val plan = """{"queries":{"videos":{"fields":{"id":{}}}}}"""
        assertThrows(IllegalArgumentException::class.java) {
            HtmlQuery.query("x".repeat(2_097_153), plan)
        }
    }
}
