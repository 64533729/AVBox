package com.github.catvod.crawler.js

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyStreamTest {

    private class TrackingBody(payload: ByteArray) : ResponseBody() {

        private val source: BufferedSource = Buffer().write(payload)

        private val length: Long = payload.size.toLong()

        var closeCount = 0
            private set

        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength(): Long = length

        override fun source(): BufferedSource = source

        override fun close() {
            closeCount += 1
            super.close()
        }
    }

    private fun open(body: TrackingBody): ProxyStream {
        val request = Request.Builder().url("http://localhost/stream").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
        return ProxyStream(response)
    }

    @Test
    fun readReturnsUpstreamBytes() {
        val body = TrackingBody(ByteArray(32) { index -> index.toByte() })
        val stream = open(body)
        val bytes = ByteArray(32)
        var total = 0
        while (total < bytes.size) {
            val read = stream.read(bytes, total, bytes.size - total)
            if (read < 0) break
            total += read
        }
        assertEquals(32, total)
        assertEquals(31.toByte(), bytes[31])
        assertEquals(-1, stream.read())
        stream.close()
        assertEquals(1, body.closeCount)
    }

    @Test
    fun closeIsIdempotent() {
        val body = TrackingBody(ByteArray(8))
        val stream = open(body)
        stream.close()
        stream.close()
        assertEquals(1, body.closeCount)
    }
}
