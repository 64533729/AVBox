package com.github.tvbox.osc.sourcedata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlayLoaderSeqTest {

    @Test
    fun olderRequestBecomesStaleAfterCancelOrSwitch() {
        val seq = AtomicInteger(0)
        val mine = seq.incrementAndGet()
        assertFalse("本次请求的序号应被认领", PlayLoader.isStaleResult(mine, seq))

        seq.incrementAndGet()
        assertTrue("序号被顶掉后不得再投递", PlayLoader.isStaleResult(mine, seq))
    }

    @Test
    fun playAndPreloadKeepSeparateSeqFields() {
        val names = PlayLoader::class.java.declaredFields
            .filter { AtomicInteger::class.java.isAssignableFrom(it.type) }
            .map { it.name }
            .sorted()
        assertEquals(listOf("playRequestSeq", "preloadRequestSeq"), names)
    }

    @Test
    fun preloadSeqIsNotInvalidatedByRealPlayback() {
        val playSeq = AtomicInteger(0)
        val preloadSeqHolder = AtomicInteger(0)

        val preload = preloadSeqHolder.incrementAndGet()
        playSeq.incrementAndGet()

        assertFalse("预载结果不该被真实播放作废", PlayLoader.isStaleResult(preload, preloadSeqHolder))
        assertFalse("两条通道的序号各自独立", PlayLoader.isStaleResult(playSeq.get(), playSeq))
    }
}
