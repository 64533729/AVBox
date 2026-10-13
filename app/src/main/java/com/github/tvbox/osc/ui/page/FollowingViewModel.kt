package com.github.tvbox.osc.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.data.EpisodeTotals
import com.github.tvbox.osc.data.FollowDays
import com.github.tvbox.osc.data.FollowRepository
import com.github.tvbox.osc.data.FollowWatched
import com.github.tvbox.osc.data.HistoryRepository
import com.github.tvbox.osc.data.PlaybackProgress
import com.github.tvbox.osc.data.VodFollow
import com.github.tvbox.osc.util.HistoryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

internal data class FollowEntry(
    val follow: VodFollow,
    val history: VodInfo?,
    val watchedDays: Set<Int>,
)

internal class FollowingViewModel(
    private val follows: FollowRepository = AppGraph.followRepository,
    private val history: HistoryRepository = AppGraph.historyRepository,
) : ViewModel() {

    val loading = MutableStateFlow(true)
    val items = MutableStateFlow<List<FollowEntry>>(emptyList())
    private val entries = MutableStateFlow<List<FollowEntry>>(emptyList())
    val episodeTotals = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playedPercents = MutableStateFlow<Map<String, Int>>(emptyMap())
    val today = MutableStateFlow(FollowDays.todayIndex())

    val selectedDay = MutableStateFlow<Int?>(null)
    val editMode = MutableStateFlow(false)
    val selected = MutableStateFlow<Set<Int>>(emptySet())

    init {
        refresh()
        viewModelScope.launch(Dispatchers.IO) {
            combine(entries, FollowWatched.state) { list, watched ->
                list.map { entry ->
                    entry.copy(
                        watchedDays = watched[FollowWatched.ownerOf(entry.follow.sourceKey, entry.follow.vodId)].orEmpty(),
                    )
                }
            }.collect { items.value = it; loading.value = false }
        }
        viewModelScope.launch(Dispatchers.IO) {
            PlaybackProgress.percents.collect { map ->
                playedPercents.value = if (HistoryHelper.isIncognito()) emptyMap() else map
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            AppBootstrap.subscriptionCid.drop(1).collect { refresh() }
        }
        viewModelScope.launch {
            AppBootstrap.state.collect { boot ->
                if (boot is AppBootstrap.Boot.Ready) refresh()
            }
        }
    }

    fun refresh() {
        today.value = FollowDays.todayIndex()
        viewModelScope.launch(Dispatchers.IO) {
            val list = follows.getAll()
            entries.value = list.map { follow ->
                FollowEntry(
                    follow = follow,
                    history = if (HistoryHelper.isIncognito()) {
                        null
                    } else {
                        history.getVodInfo(follow.sourceKey.orEmpty(), follow.vodId.orEmpty())
                    },
                    watchedDays = emptySet(),
                )
            }
            episodeTotals.value = EpisodeTotals.snapshot()
        }
    }

    fun setWatched(id: Int, watched: Boolean) {
        val entry = items.value.firstOrNull { it.follow.id == id } ?: return
        val days = targetDays(entry)
        if (days.isEmpty()) return
        val owner = FollowWatched.ownerOf(entry.follow.sourceKey, entry.follow.vodId)
        val updated = if (watched) entry.watchedDays + days else entry.watchedDays - days
        items.value = items.value.map { if (it.follow.id == id) it.copy(watchedDays = updated) else it }
        viewModelScope.launch(Dispatchers.IO) {
            val week = FollowWatched.weekStart()
            if (watched) {
                FollowWatched.mark(owner, days, week)
            } else {
                FollowWatched.clear(owner, days, week)
            }
        }
    }

    private fun targetDays(entry: FollowEntry): Set<Int> =
        selectedDay.value?.let { setOf(it) } ?: FollowDays.decode(entry.follow.updateDays)

    fun selectDay(day: Int?) {
        selectedDay.value = day
    }

    fun enterEdit(id: Int? = null) {
        editMode.value = true
        selected.value = if (id == null) emptySet() else setOf(id)
    }

    fun exitEdit() {
        editMode.value = false
        selected.value = emptySet()
    }

    fun toggleSelected(id: Int) {
        selected.value = if (id in selected.value) selected.value - id else selected.value + id
    }

    fun pruneSelection(ids: Set<Int>) {
        val pruned = selected.value.intersect(ids)
        if (pruned.size != selected.value.size) selected.value = pruned
        if (ids.isEmpty()) editMode.value = false
    }

    fun deleteSelected() {
        val ids = selected.value.toList()
        if (ids.isEmpty()) return
        exitEdit()
        viewModelScope.launch(Dispatchers.IO) {
            follows.deleteSelected(ids)
            refresh()
        }
    }
}
