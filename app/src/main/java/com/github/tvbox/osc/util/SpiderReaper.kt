package com.github.tvbox.osc.util

import android.os.SystemClock

import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderNull

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 退役爬虫的延迟回收。
 *
 * clear() 会丢弃全部缓存的 spider，但其它线程可能仍在这些实例里执行第三方 jar 的代码；
 * 此时立刻 destroy() 会让第三方（含原生加固库）出现 use-after-free。
 * 因此这里只做两件事：登记在飞调用；把退役实例交给 janitor，
 * 待在飞归零且静默超过宽限期后再销毁。
 */
object SpiderReaper {

    private const val RETIRE_GRACE_MS = 20_000L

    private const val REAP_PERIOD_MS = 3_000L

    private val inflight = ConcurrentHashMap<Spider, AtomicInteger>()

    private val retired = ConcurrentHashMap<Spider, Long>()

    private val started = AtomicBoolean(false)

    /** 包裹一次真实的爬虫调用，保证期间实例不会被销毁。 */
    fun <T> track(spider: Spider?, block: () -> T): T {
        if (spider == null || spider is SpiderNull) return block()
        begin(spider)
        return try {
            block()
        } finally {
            end(spider)
        }
    }

    fun begin(spider: Spider) {
        inflight.computeIfAbsent(spider) { AtomicInteger() }.incrementAndGet()
    }

    fun end(spider: Spider) {
        val counter = inflight[spider] ?: return
        if (counter.decrementAndGet() <= 0) inflight.remove(spider, counter)
    }

    /** 让 spider 退役，稍后安全销毁。 */
    fun retire(spider: Spider?) {
        if (spider == null || spider is SpiderNull) return
        ensureJanitor()
        retired[spider] = SystemClock.elapsedRealtime()
    }

    private fun ensureJanitor() {
        if (started.compareAndSet(false, true)) {
            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "spider-reaper").apply { isDaemon = true }
            }
            scheduler.scheduleWithFixedDelay({ reap() }, REAP_PERIOD_MS, REAP_PERIOD_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun reap() {
        val now = SystemClock.elapsedRealtime()
        for (entry in retired.entries) {
            val spider = entry.key
            val retireAt = entry.value
            if (now - retireAt < RETIRE_GRACE_MS) continue
            if ((inflight[spider]?.get() ?: 0) > 0) continue
            if (!retired.remove(spider, retireAt)) continue
            try {
                spider.destroy()
            } catch (ignored: Throwable) {
            }
            inflight.remove(spider)
        }
    }
}
