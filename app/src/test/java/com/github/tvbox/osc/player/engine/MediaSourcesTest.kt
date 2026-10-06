package com.github.tvbox.osc.player.engine

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSourcesTest {

    @Test
    fun formatHeader_winsOverFileName() {
        assertEquals(C.TYPE_HLS, MediaSources.inferContentType("http://x/a.mp4", mapOf("TVBox-Format" to "hls")))
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/a.m3u8", mapOf("TVBox-Format" to "dash")))
    }

    @Test
    fun formatHeader_variants() {
        assertEquals(C.TYPE_HLS, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "application/vnd.apple.mpegurl")))
        assertEquals(C.TYPE_HLS, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "M3U8")))
        assertEquals(C.TYPE_DASH, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "application/dash+xml")))
        assertEquals(C.TYPE_DASH, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "mpd")))
        assertEquals(C.TYPE_OTHER, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "mp4")))
        assertEquals(C.TYPE_OTHER, MediaSources.inferFormatContentType(null))
        assertEquals(C.TYPE_OTHER, MediaSources.inferFormatContentType(mapOf("Other" to "hls")))
    }

    @Test
    fun inferContentType_byFileName() {
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/a.mpd", null))
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/play?type=mpd", null))
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/play?type=dash", null))
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/play?format=mpd", null))
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/play?format=dash", null))
        assertEquals(C.TYPE_HLS, MediaSources.inferContentType("http://x/a.m3u8", null))
        assertEquals(C.TYPE_HLS, MediaSources.inferContentType("http://x/play?type=hls", null))
        assertEquals(C.TYPE_HLS, MediaSources.inferContentType("http://x/play?format=hls", null))
        assertEquals(C.TYPE_OTHER, MediaSources.inferContentType("http://x/a.mp4", null))
    }

    @Test
    fun inferContentType_isCaseInsensitive() {
        assertEquals(C.TYPE_HLS, MediaSources.inferContentType("http://x/A.M3U8", null))
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/A.MPD", null))
        assertEquals(C.TYPE_HLS, MediaSources.inferContentType("http://x/P?TYPE=HLS", null))
    }

    @Test
    fun audioUri_detectedBySuffix() {
        assertTrue(MediaSources.isAudioUri("http://x/song.mp3"))
        assertTrue(MediaSources.isAudioUri("http://x/song.M4A"))
        assertTrue(MediaSources.isAudioUri("http://x/song.flac"))
        assertTrue(MediaSources.isAudioUri("http://x/song.opus"))
        assertFalse(MediaSources.isAudioUri("http://x/movie.mp4"))
        assertFalse(MediaSources.isAudioUri("http://x/a.m3u8"))
    }

    @Test
    fun hlsUri_audioSuffixExcludedEvenWithKeyword() {
        assertFalse(MediaSources.isHlsUri("http://x/live.m3u8.mp3"))
        assertTrue(MediaSources.isHlsUri("http://x/live.m3u8"))
    }

    @Test
    fun hlsUri_keywordVariants() {
        assertTrue(MediaSources.isHlsUri("http://x/play?type=hls"))
        assertTrue(MediaSources.isHlsUri("http://x/play?format=hls"))
        assertFalse(MediaSources.isHlsUri("http://x/a.mp4"))
    }

    @Test
    fun mimeTypeOf_onlyHlsAndDashGetMime() {
        assertEquals("application/x-mpegURL", MediaSources.mimeTypeOf(C.TYPE_HLS))
        assertEquals("application/dash+xml", MediaSources.mimeTypeOf(C.TYPE_DASH))
        assertEquals(null, MediaSources.mimeTypeOf(C.TYPE_OTHER))
    }

    @Test
    fun toRequestHeaders_filtersFormatHeaderAndTrims() {
        val out = MediaSources.toRequestHeaders(
            mapOf(
                "Referer" to " http://ref ",
                "TVBox-Format" to "hls",
                "tvbox-format" to "dash",
                "User-Agent" to "ua",
            ),
        )
        assertEquals("http://ref", out["Referer"])
        assertEquals("ua", out["User-Agent"])
        assertFalse(out.containsKey("TVBox-Format"))
        assertFalse(out.containsKey("tvbox-format"))
    }

    @Test
    fun toRequestHeaders_nullInput() {
        assertTrue(MediaSources.toRequestHeaders(null).isEmpty())
    }

    @Test
    fun toRequestHeaders_trimOnlyAsciiControlChars() {
        val keepNbsp = MediaSources.toRequestHeaders(mapOf("K" to "v\u00A0"))
        assertEquals("v\u00A0", keepNbsp["K"])
        val control = MediaSources.toRequestHeaders(mapOf("K" to "\tv\u0001"))
        assertEquals("v", control["K"])
    }

    @Test
    fun headerKeySuffix_sortedAndOrderIndependent() {
        val a = MediaSources.headerKeySuffix(linkedMapOf("User-Agent" to "u", "Referer" to "r"))
        val b = MediaSources.headerKeySuffix(linkedMapOf("Referer" to "r", "User-Agent" to "u"))
        assertEquals("\nReferer:r;\nUser-Agent:u;", a)
        assertEquals(a, b)
    }

    @Test
    fun headerKeySuffix_trimsValues() {
        assertEquals(
            MediaSources.headerKeySuffix(mapOf("K" to "v")),
            MediaSources.headerKeySuffix(mapOf("K" to " v ")),
        )
    }

    @Test
    fun headerKeySuffix_empty() {
        assertEquals("", MediaSources.headerKeySuffix(null))
        assertEquals("", MediaSources.headerKeySuffix(emptyMap()))
    }
}
