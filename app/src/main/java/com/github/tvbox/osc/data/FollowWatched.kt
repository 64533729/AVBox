package com.github.tvbox.osc.data

import com.github.tvbox.osc.util.KV
import java.time.LocalDate

object FollowWatched {

    private const val KEY = "follow_watched"

    fun ownerOf(sourceKey: String?, vodId: String?): String =
        sourceKey.orEmpty() + "|" + vodId.orEmpty()

    fun weekStart(): Long {
        val today = LocalDate.now()
        return today.minusDays((today.dayOfWeek.value - 1).toLong()).toEpochDay()
    }

    fun snapshot(week: Long): Map<String, Set<Int>> = watchedDays(read(), week)

    fun mark(owner: String, days: Set<Int>, week: Long) {
        if (days.isEmpty()) return
        val value = week.toString()
        val map = read()
        days.forEach { map[dayKey(owner, it)] = value }
        prune(map, week)
        KV.put(KEY, map)
    }

    fun clear(owner: String, days: Set<Int>, week: Long) {
        if (days.isEmpty()) return
        val map = read()
        var removed = false
        days.forEach { if (map.remove(dayKey(owner, it)) != null) removed = true }
        val pruned = prune(map, week)
        if (removed || pruned) KV.put(KEY, map)
    }

    internal fun watchedDays(map: Map<String, String>, week: Long): Map<String, Set<Int>> {
        val value = week.toString()
        val result = HashMap<String, MutableSet<Int>>()
        map.forEach { (key, storedWeek) ->
            if (storedWeek != value) return@forEach
            val index = key.substringAfterLast('|').toIntOrNull() ?: return@forEach
            result.getOrPut(key.substringBeforeLast('|')) { HashSet() }.add(index)
        }
        return result
    }

    internal fun prune(map: MutableMap<String, String>, week: Long): Boolean {
        val value = week.toString()
        return map.entries.removeAll { it.value != value }
    }

    private fun dayKey(owner: String, day: Int): String = owner + "|" + day

    private fun read(): HashMap<String, String> = KV.get(KEY, HashMap<String, String>())
}
