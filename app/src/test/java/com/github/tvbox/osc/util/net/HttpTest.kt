package com.github.tvbox.osc.util.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HttpTest {

    @Test
    fun get_returnsBodyForNonFailCodes() = runBlocking {
        val body = Http.executeWithRetry(request(), clientReturning(403, "denied"))
        assertEquals("denied", body)
    }

    @Test
    fun get_throwsHttpExceptionFor404() = runBlocking {
        try {
            Http.executeWithRetry(request(), clientReturning(404, "missing"))
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun get_throwsHttpExceptionForServerError() = runBlocking {
        try {
            Http.executeWithRetry(request(), clientReturning(503, "unavailable"))
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(503, e.code)
        }
    }

    @Test
    fun get_retriesSocketTimeoutUpToFourAttempts() = runBlocking {
        val attempts = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            attempts.incrementAndGet()
            throw SocketTimeoutException("read timed out")
        }.build()
        try {
            Http.executeWithRetry(request(), client)
            fail("expected SocketTimeoutException")
        } catch (e: SocketTimeoutException) {
        }
        assertEquals(4, attempts.get())
    }

    @Test
    fun get_doesNotRetryNonTimeoutFailures() = runBlocking {
        val attempts = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            attempts.incrementAndGet()
            throw ConnectException("connection refused")
        }.build()
        try {
            Http.executeWithRetry(request(), client)
            fail("expected ConnectException")
        } catch (e: ConnectException) {
        }
        assertEquals(1, attempts.get())
    }

    @Test
    fun get_readsResponseBodyOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val readOn = AtomicReference<Thread>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("test")
                .body(threadRecordingBody("ok", readOn))
                .build()
        }.build()
        assertEquals("ok", Http.executeWithRetry(request(), client))
        val readThread = readOn.get()
        assertTrue("响应体读取不应落在调用线程上", readThread != null && readThread !== caller)
    }

    @Test
    fun get_cancelPropagatesCancellationWithoutOtherFailure() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("released")
        }.build()
        var cancelled = false
        var other: Throwable? = null
        val job = launch {
            try {
                Http.executeWithRetry(request(), client)
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: Throwable) {
                other = e
            }
        }
        yield()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        release.countDown()
        assertTrue(cancelled)
        assertNull(other)
    }

    private fun request(): Request {
        return HttpRequest("https://example.com/api").build()
    }

    private fun clientReturning(code: Int, body: String): OkHttpClient {
        return OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody(null))
                .build()
        }.build()
    }

    private fun threadRecordingBody(text: String, readOn: AtomicReference<Thread>): ResponseBody {
        return object : ResponseBody() {
            override fun contentType(): MediaType? = null

            override fun contentLength(): Long = text.toByteArray().size.toLong()

            override fun source(): BufferedSource {
                readOn.set(Thread.currentThread())
                return Buffer().apply { writeUtf8(text) }
            }
        }
    }
}
