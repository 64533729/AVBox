package com.github.tvbox.osc.sourcedata

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 取数通道的行为锁(M4a 语义转换的回归网):取数侧只认 postValue/setValue、消费侧只认 flow。
 *
 * 三条不变量:①flow 是热流且回放最近一次值(等价 LiveData 粘性,新收集者不会空等已发生的结果);
 * ②null 也是合法载荷(取数失败就是投 null,不能被当成"没有回包");③LiveData 兼容面的投递走向
 * 在 M7 之前必须与旧 `MutableLiveData` 一致(postValue 异步 / setValue 同步)——
 * 真实 `MutableLiveData` 不能在 JVM 单测里投递(没有 Looper),故用替身记录 [dispatchToLiveData]。
 */
class SourceChannelTest {

    /** 记录 LiveData 兼容面投递走向;Flow 面仍走真实实现 */
    private class RecordingLiveChannel : SourceChannel<Int?>() {
        val live = ArrayList<Pair<Int?, Boolean>>()
        override fun dispatchToLiveData(value: Int?, sync: Boolean) {
            live.add(value to sync)
        }
    }

    @Test
    fun flowReplaysLatestValueToLateCollector() = runBlocking {
        val channel = SourceChannel<Int?>()
        channel.postValue(1)
        channel.postValue(2)
        channel.postValue(3)
        assertEquals("新收集者应立刻拿到最近一次的值", 3, withTimeout(1000) { channel.flow.first() })
    }

    @Test
    fun valuePostedAfterCollectorStartsIsDelivered() = runBlocking {
        val channel = SourceChannel<String?>()
        val pending = async { withTimeout(1000) { channel.flow.first() } }
        yield()
        channel.postValue("late-post")
        assertEquals("late-post", pending.await())
    }

    @Test
    fun nullPayloadIsDeliveredNotSwallowed() = runBlocking {
        val channel = SourceChannel<Int?>()
        channel.postValue(7)
        channel.postValue(null)
        val got = withTimeout(1000) { channel.flow.take(1).toList() }
        assertEquals("null 必须作为一次投递送达", 1, got.size)
        assertNull(got[0])
    }

    @Test
    fun postValueFeedsFlowAndMirrorsAsync() = runBlocking {
        val channel = RecordingLiveChannel()
        channel.postValue(11)
        assertEquals(11, withTimeout(1000) { channel.flow.first() })
        assertEquals(listOf<Pair<Int?, Boolean>>(11 to false), channel.live)
    }

    @Test
    fun setValueFeedsFlowAndMirrorsSync() = runBlocking {
        val channel = RecordingLiveChannel()
        channel.setValue(9)
        assertEquals(9, withTimeout(1000) { channel.flow.first() })
        assertEquals(listOf<Pair<Int?, Boolean>>(9 to true), channel.live)
    }

    @Test
    fun bothEntryPointsFeedTheSameFlowOnceEach() = runBlocking {
        val channel = RecordingLiveChannel()
        val pending = async { withTimeout(1000) { channel.flow.take(2).toList() } }
        yield()
        channel.postValue(1)
        channel.setValue(2)
        assertEquals(listOf<Int?>(1, 2), pending.await())
    }
}
