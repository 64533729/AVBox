package com.github.catvod.crawler.js

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors

class HttpGateTest {

    private val executor = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun dispatch_keepsInFlightLimitAfterTransfer() {
        val gate = HttpGate(executor)
        var started = 0
        val start = Runnable { started++ }
        repeat(16) { gate.dispatch(HttpGate.Request(start) {}) }
        gate.dispatch(HttpGate.Request(start) {})
        assertEquals(16, started)

        gate.finish()
        assertEquals(17, started)

        gate.dispatch(HttpGate.Request(start) {})
        assertEquals(17, started)
    }

    @Test
    fun dispatch_rejectsWhenPendingQueueIsFull() {
        val gate = HttpGate(executor)
        val start = Runnable {}
        repeat(16) { gate.dispatch(HttpGate.Request(start) {}) }
        var message = ""
        repeat(64) { gate.dispatch(HttpGate.Request(start) { message = it }) }
        gate.dispatch(HttpGate.Request(start) { message = it })
        assertEquals("too many pending requests", message)
    }

    @Test
    fun cancelPending_failsQueuedRequests() {
        val gate = HttpGate(executor)
        val start = Runnable {}
        repeat(16) { gate.dispatch(HttpGate.Request(start) {}) }
        val messages = ArrayList<String>()
        repeat(3) { gate.dispatch(HttpGate.Request(start) { messages.add(it) }) }
        gate.cancelPending()
        assertEquals(listOf("cancelled", "cancelled", "cancelled"), messages)
    }
}
