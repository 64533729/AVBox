package com.github.catvod.crawler.js

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenStreamTest {

    private val payload = ByteArray(100) { index -> index.toByte() }

    private var cancelled = false

    private fun openStream(): OpenStream {
        val request = Request.Builder().url("http://localhost").build()
        val call = OkHttpClient().newCall(request)
        val tracked = object : Call by call {
            override fun cancel() {
                cancelled = true
                call.cancel()
            }
        }
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(payload.copyOf().toResponseBody("application/octet-stream".toMediaType()))
            .build()
        return OpenStream(tracked, response)
    }

    @Test
    fun read_returnsPartialChunksAndNullAtEnd() {
        val stream = openStream()
        var total = 0
        val chunks = ArrayList<Int>()
        while (true) {
            val chunk = stream.read(16) ?: break
            chunks.add(chunk.size)
            total += chunk.size
        }
        assertEquals(100, total)
        assertEquals(listOf(16, 16, 16, 16, 16, 16, 4), chunks)
        assertNull(stream.read(16))
        assertFalse(stream.close())
        assertFalse(cancelled)
    }

    @Test
    fun close_isIdempotentAndReadAfterCloseReturnsNull() {
        val stream = openStream()
        assertTrue(stream.close())
        assertFalse(stream.close())
        assertNull(stream.read(16))
        assertTrue(cancelled)
    }
}
