package com.github.tvbox.osc.util

import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.data.CacheRepository
import com.github.tvbox.osc.player.PlaybackService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object WatchProgressStore {

    private const val WRITER_THREAD_NAME = "vod-progress-writer"

    private const val DRAIN_TIMEOUT_MS = 500L

    private val writer: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, WRITER_THREAD_NAME).also { writerThread = it }
        }
    }

    private val cache: CacheRepository by lazy { AppGraph.cacheRepository }

    private val pendingWrites = AtomicInteger(0)

    @Volatile
    private var writerThread: Thread? = null

    private val indexLock = Any()

    private val lastDurations = ConcurrentHashMap<String, Long>()

    private const val TRIM_INTERVAL_MS = 60_000L

    private var lastTrimAt = 0L

    private val discardedKeys: MutableSet<String> = HashSet()

    private fun submit(task: () -> Unit) {
        pendingWrites.incrementAndGet()
        try {
            writer.execute {
                try {
                    task()
                } catch (th: Throwable) {
                    LOG.e("WatchProgressStore", "echo-progress write failed: " + th, th)
                } finally {
                    pendingWrites.decrementAndGet()
                }
            }
        } catch (th: Throwable) {
            pendingWrites.decrementAndGet()
            LOG.e("WatchProgressStore", "echo-progress submit failed: " + th, th)
        }
    }

    @JvmStatic
    fun awaitWrites() {
        if (pendingWrites.get() == 0) return
        if (Thread.currentThread() === writerThread) return
        try {
            val barrier = CountDownLatch(1)
            writer.execute { barrier.countDown() }
            if (!barrier.await(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                LOG.i("echo-progress drain-timeout pending=" + pendingWrites.get())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (th: Throwable) {
            LOG.e("WatchProgressStore", "echo-progress drain failed: " + th, th)
        }
    }

    @JvmStatic
    fun ownerOf(vod: VodInfo?): String? {
        if (vod == null) return null
        val source = vod.sourceKey
        val id = vod.id
        if (source.isNullOrEmpty() || id.isNullOrEmpty()) return null
        return source + "|" + id
    }

    @JvmStatic
    fun save(owner: String?, progressKey: String?, positionMs: Long, durationMs: Long) {
        if (progressKey.isNullOrEmpty()) return
        if (positionMs <= 0) {
            clear(owner, progressKey)
            return
        }
        if (isDiscarded(progressKey)) return
        if (HistoryHelper.isIncognito()) return
        if (WatchProgressRules.decide(positionMs, resolveDuration(owner, durationMs)) == WatchDecision.SKIP) return
        submit {
            if (isDiscarded(progressKey) || HistoryHelper.isIncognito()) return@submit
            cache.save(md5(progressKey), positionMs)
            remember(owner, progressKey)
        }
    }

    @JvmStatic
    fun noteDuration(owner: String?, durationMs: Long) {
        if (owner.isNullOrEmpty() || durationMs <= 0) return
        synchronized(indexLock) { lastDurations[owner] = durationMs }
    }

    private fun resolveDuration(owner: String?, durationMs: Long): Long {
        if (durationMs > 0) return durationMs
        if (owner.isNullOrEmpty()) return 0L
        synchronized(indexLock) { return lastDurations[owner] ?: 0L }
    }

    @JvmStatic
    fun clear(owner: String?, progressKey: String?) {
        if (progressKey.isNullOrEmpty()) return
        submit {
            cache.delete(md5(progressKey), 0L)
            forget(owner, progressKey)
        }
    }

    @JvmStatic
    fun inherit(owner: String?, fromKey: String?, toKey: String?, positionMs: Long) {
        if (fromKey.isNullOrEmpty() || toKey.isNullOrEmpty()) return
        if (fromKey == toKey) return
        if (positionMs <= 0) return
        if (HistoryHelper.isIncognito()) return
        if (isDiscarded(toKey)) return
        if (WatchProgressRules.decide(positionMs, 0L) == WatchDecision.SKIP) return
        submit {
            if (isDiscarded(toKey) || HistoryHelper.isIncognito()) return@submit
            if (cache.get(md5(toKey)) != null) return@submit
            cache.save(md5(toKey), positionMs)
            remember(owner, toKey)
        }
    }

    @JvmStatic
    fun clearOwner(owner: String?) {
        if (owner.isNullOrEmpty()) return
        awaitWrites()
        val cleared = synchronized(indexLock) { clearTitleLocked(owner) }
        discard(cleared)
        LOG.i("echo-progress clear-owner owner=" + owner + " eps=" + cleared.size)
        PlaybackService.peek()?.discardStartedContentOf(listOf(owner))
        PlaybackProgress.forget(owner)
        EpisodeTotals.remove(owner)
    }

    @JvmStatic
    fun onPlayStart(progressKey: String?) {
        if (progressKey.isNullOrEmpty()) return
        synchronized(indexLock) { discardedKeys.remove(md5(progressKey)) }
    }

    private fun discard(progressKeys: List<String>) {
        if (progressKeys.isEmpty()) return
        synchronized(indexLock) { progressKeys.forEach { discardedKeys.add(md5(it)) } }
    }

    private fun discardHashed(cacheKeys: List<String>) {
        if (cacheKeys.isEmpty()) return
        synchronized(indexLock) { discardedKeys.addAll(cacheKeys) }
    }

    private fun isDiscarded(progressKey: String): Boolean =
        synchronized(indexLock) { discardedKeys.contains(md5(progressKey)) }

    @JvmStatic
    fun clearAll() {
        awaitWrites()
        var titles = 0
        val owners = ArrayList<String>()
        val keys = ArrayList<String>()
        synchronized(indexLock) {
            for (indexKey in KV.keys(WatchProgressIndex.KEY_PREFIX)) {
                titles++
                val owner = WatchProgressIndex.ownerOf(indexKey)
                owners.add(owner)
                keys.addAll(clearTitleLocked(owner))
            }
        }
        discard(keys)
        PlaybackService.peek()?.discardStartedContentOf(owners)
        val stale = cache.clearAllProgress()
        discardHashed(stale)
        LOG.i("echo-progress clear-all titles=" + titles + " eps=" + keys.size + " stale=" + stale.size)
        PlaybackProgress.forgetAll()
        EpisodeTotals.removeAll()
    }

    private fun clearTitleLocked(owner: String): List<String> {
        val indexKey = WatchProgressIndex.keyOf(owner)
        val eps = WatchProgressIndex.decode(KV.get(indexKey, ""))?.eps.orEmpty()
        eps.forEach {
            discardedKeys.add(md5(it))
            cache.delete(md5(it), 0L)
        }
        KV.delete(indexKey)
        return eps
    }

    private fun remember(owner: String?, ep: String) {
        if (owner.isNullOrEmpty()) return
        synchronized(indexLock) {
            if (discardedKeys.contains(md5(ep))) return
            val key = WatchProgressIndex.keyOf(owner)
            KV.put(key, WatchProgressIndex.withEp(KV.get(key, ""), ep, System.currentTimeMillis()))
            trimCapacityLocked(owner)
        }
    }

    private fun forget(owner: String?, ep: String) {
        if (owner.isNullOrEmpty()) return
        synchronized(indexLock) {
            val key = WatchProgressIndex.keyOf(owner)
            val payload = WatchProgressIndex.withoutEp(KV.get(key, ""), ep)
            if (payload == null) KV.delete(key) else KV.put(key, payload)
        }
    }

    private fun trimCapacityLocked(justSavedOwner: String) {
        val now = System.currentTimeMillis()
        if (now - lastTrimAt < TRIM_INTERVAL_MS) return
        lastTrimAt = now
        val indexKeys = KV.keys(WatchProgressIndex.KEY_PREFIX)
        val entries = ArrayList<Pair<String, Long>>(indexKeys.size)
        for (key in indexKeys) {
            val owner = WatchProgressIndex.ownerOf(key)
            entries.add(owner to (WatchProgressIndex.decode(KV.get(key, ""))?.at ?: 0L))
        }
        val keep = entries.mapTo(HashSet(entries.size + 1)) { it.first }
        val currentOwner = App.getInstance()!!.getVodInfo()?.let { ownerOf(it) }
        if (!currentOwner.isNullOrEmpty()) keep.add(currentOwner)
        PlaybackProgress.retain(keep)
        EpisodeTotals.retain(keep)
        val evicted = WatchProgressIndex.pickEvictions(entries, WatchProgressIndex.MAX_TITLES)
            .filter { it != justSavedOwner && it != currentOwner }
        if (evicted.isEmpty()) return
        PlaybackService.peek()?.discardStartedContentOf(evicted)
        for (owner in evicted) {
            val keys = clearTitleLocked(owner)
            discard(keys)
            LOG.i("echo-progress evict owner=" + owner + " eps=" + keys.size)
            PlaybackProgress.forget(owner)
            EpisodeTotals.remove(owner)
        }
    }

    private fun md5(key: String): String = MD5.string2MD5(key)!!
}
