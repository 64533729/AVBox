package com.github.catvod.crawler.js

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

import okhttp3.Response

class ProxyStream(private val response: Response) : InputStream() {

    private val delegate: InputStream = response.body.byteStream()

    private val closed = AtomicBoolean(false)

    override fun read(): Int = delegate.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

    override fun available(): Int = delegate.available()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        response.close()
    }
}
