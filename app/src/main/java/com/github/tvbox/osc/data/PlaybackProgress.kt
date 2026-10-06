package com.github.tvbox.osc.data

import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.WatchDecision
import com.github.tvbox.osc.util.WatchProgressRules
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object PlaybackProgress {

    private const val KEY = "playback_progress"

    private const val LIMIT = 300

    private const val MIN_INTERVAL_MS = 5_000L

    private const val MIN_ADVANCE_MS = 1_000

    private const val MAX_STEP_MS = 10_000

    private var lastKey = ""

    private var lastSavedAt = 0L

    private var lastSavedPercent = -1

    private var sampleToken = ""

    private var lastPosition = -1

    private var advancedMs = 0

    private var watchedToken = ""

    private val writer: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "playback-progress") }
    }

    fun key(sourceKey: String?, vodId: String?): String =
        sourceKey.orEmpty() + "|" + vodId.orEmpty()

    fun snapshot(): Map<String, Int> {
        if (HistoryHelper.isIncognito()) return emptyMap()
        return read()
            .mapNotNull { (key, value) ->
                value.toIntOrNull()?.takeIf { it in 0..100 }?.let { key to it }
            }
            .toMap()
    }

    fun onEpisodeStart(scrollToTop: Boolean) {
        val id = currentKey() ?: return
        val cleared = forget(id)
        val event = if (scrollToTop) {
            RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH)
        } else {
            RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH, java.lang.Boolean.FALSE)
        }
        if (cleared || !scrollToTop) EventBus.getDefault().post(event)
    }

    @JvmStatic
    fun onEpisodeStartNoScroll() = onEpisodeStart(false)

    fun onProgress(positionMs: Int, durationMs: Int) {
        val id = currentKey() ?: return
        WatchProgressStore.noteDuration(id, durationMs.toLong())
        markWatched(positionMs, durationMs)
        if (WatchProgressRules.decide(positionMs.toLong(), durationMs.toLong()) == WatchDecision.SKIP) return
        val percent = calcPercent(positionMs, durationMs) ?: return
        val now = System.currentTimeMillis()
        if (id == lastKey && (percent == lastSavedPercent || now - lastSavedAt < MIN_INTERVAL_MS)) return
        lastKey = id
        lastSavedAt = now
        lastSavedPercent = percent
        writer.execute { write(id, percent) }
    }

    fun flush(positionMs: Int, durationMs: Int): Boolean {
        val id = currentKey() ?: return false
        markWatched(positionMs, durationMs)
        watchedToken = ""
        sampleToken = ""
        advancedMs = 0
        if (WatchProgressRules.decide(positionMs.toLong(), durationMs.toLong()) == WatchDecision.SKIP) return false
        val percent = calcPercent(positionMs, durationMs) ?: return false
        if (id == lastKey && percent == lastSavedPercent) return false
        if (!write(id, percent)) return false
        lastKey = id
        lastSavedAt = System.currentTimeMillis()
        lastSavedPercent = percent
        return true
    }

    fun markFinished() {
        val id = currentKey() ?: return
        if (id == lastKey && lastSavedPercent == 100) return
        if (!write(id, 100)) return
        lastKey = id
        lastSavedAt = System.currentTimeMillis()
        lastSavedPercent = 100
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
    }

    fun stepAdvanceMs(positionMs: Int, lastPositionMs: Int): Int =
        (positionMs - lastPositionMs).takeIf { it in 1..MAX_STEP_MS } ?: 0

    fun shouldMarkWatched(advancedMs: Int, token: String, lastToken: String): Boolean =
        token != lastToken && advancedMs >= MIN_ADVANCE_MS

    private fun markWatched(positionMs: Int, durationMs: Int) {
        val vod = PlaybackPorts.currentVod?.invoke() ?: return
        if (PlaybackPorts.isLiveMode?.invoke() == true) return
        val token = key(vod.sourceKey, vod.id) + "#" + vod.playFlag + "#" + vod.playIndex
        if (token != sampleToken) {
            sampleToken = token
            lastPosition = positionMs
            advancedMs = 0
            return
        }
        advancedMs += stepAdvanceMs(positionMs, lastPosition)
        lastPosition = positionMs
        if (!shouldMarkWatched(advancedMs, token, watchedToken)) return
        if (!WatchProgressRules.shouldRemember(positionMs.toLong(), durationMs.toLong())) return
        watchedToken = token
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_PLAYBACK_STARTED))
    }

    private fun calcPercent(positionMs: Int, durationMs: Int): Int? {
        if (durationMs <= 0) return null
        return (positionMs.toLong() * 100 / durationMs).toInt().coerceIn(0, 100)
    }

    private fun currentKey(): String? {
        val vod = PlaybackPorts.currentVod?.invoke() ?: return null
        if (vod.sourceKey.isNullOrEmpty() || vod.id.isNullOrEmpty()) return null
        return key(vod.sourceKey, vod.id)
    }

    @Synchronized
    private fun write(id: String, percent: Int): Boolean {
        if (HistoryHelper.isIncognito()) return false
        val map = read()
        map[id] = percent.toString()
        if (map.size > LIMIT) map.keys.take(map.size - LIMIT).forEach { map.remove(it) }
        return KV.put(KEY, map)
    }

    @Synchronized
    private fun remove(id: String): Boolean {
        val map = read()
        if (map.remove(id) == null) return false
        return KV.put(KEY, map)
    }

    fun retain(owners: Set<String>) {
        writer.execute { retainNow(owners) }
    }

    @Synchronized
    private fun retainNow(owners: Set<String>) {
        val map = read()
        val kept = HashMap<String, String>(map.size)
        for ((id, value) in map) {
            if (owners.contains(id)) kept[id] = value
        }
        if (kept.size == map.size) return
        KV.put(KEY, kept)
    }

    @Synchronized
    fun forget(id: String): Boolean = remove(id)

    @Synchronized
    fun forgetAll() {
        lastKey = ""
        lastSavedPercent = -1
        KV.delete(KEY)
    }

    private fun read(): HashMap<String, String> = KV.get(KEY, HashMap<String, String>())
}
