package com.github.tvbox.osc.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.data.EpisodeTotals
import com.github.tvbox.osc.data.HistoryRepository
import com.github.tvbox.osc.data.PlaybackProgress
import com.github.tvbox.osc.data.WatchProgressStore
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.TrackMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class HistoryViewModel(
    private val history: HistoryRepository = AppGraph.historyRepository,
) : ViewModel() {
    val loading = MutableStateFlow(true)
    val items = MutableStateFlow<List<VodInfo>>(emptyList())
    val episodeTotals = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playedPercents = MutableStateFlow<Map<String, Int>>(emptyMap())

    val incognito = MutableStateFlow(HistoryHelper.isIncognito())

    private val reloadTick = MutableStateFlow(0)

    init {
        EventBus.getDefault().register(this)
        viewModelScope.launch(Dispatchers.IO) {
            combine(reloadTick, AppBootstrap.subscriptionCid) { _, cid -> cid }
                .flatMapLatest { cid -> history.observeVodRecords(cid) }
                .collect { list ->
                    val hidden = HistoryHelper.isIncognito()
                    incognito.value = hidden
                    items.value = if (hidden) emptyList() else list
                    episodeTotals.value = if (hidden) emptyMap() else EpisodeTotals.snapshot()
                    resolveSourceNames()
                    loading.value = false
                }
        }
        viewModelScope.launch {
            PlaybackProgress.percents.collect { map ->
                playedPercents.value = if (HistoryHelper.isIncognito()) emptyMap() else map
            }
        }
        viewModelScope.launch {
            AppBootstrap.state.collect { boot ->
                if (boot is AppBootstrap.Boot.Ready) reloadTick.value++
            }
        }
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
    }

    val scrollSignal = MutableStateFlow(0)

    val placementAnim = MutableStateFlow(false)

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type != RefreshEvent.TYPE_HISTORY_REFRESH) return
        placementAnim.value = false
        scrollSignal.value++
        viewModelScope.launch(Dispatchers.IO) {
            history.sweepDuplicates()
            reloadTick.value++
        }
    }

    private var resolveJob: Job? = null

    fun resolveSourceNames() {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch(Dispatchers.IO) {
            val list = items.value
            if (list.isEmpty()) return@launch
            if (AppBootstrap.state.value !is AppBootstrap.Boot.Ready) return@launch
            val cache = KV.get(HawkConfig.SOURCE_NAME_CACHE, HashMap<String, String>())
            var cacheChanged = false
            var listChanged = false
            list.forEach { info ->
                val key = info.sourceKey
                val bean = if (key.isNullOrEmpty()) null else ApiConfig.get().getSource(key)
                val resolved = if (key.isNullOrEmpty()) {
                    ""
                } else {
                    val current = bean?.name
                    if (!current.isNullOrEmpty()) {
                        if (cache[key] != current) {
                            cache[key] = current
                            cacheChanged = true
                        }
                        current
                    } else {
                        cache[key] ?: key
                    }
                }
                if (info.sourceName != resolved) {
                    info.sourceName = resolved
                    listChanged = true
                }
                val unavailable = !key.isNullOrEmpty() && bean == null
                if (info.sourceUnavailable != unavailable) {
                    info.sourceUnavailable = unavailable
                    listChanged = true
                }
            }
            if (cacheChanged) KV.put(HawkConfig.SOURCE_NAME_CACHE, cache)
            if (listChanged) items.value = list.toList()
        }
    }

    fun deleteSelected(list: List<VodInfo>) {
        if (list.isEmpty()) return
        placementAnim.value = true
        viewModelScope.launch(Dispatchers.IO) {
            list.forEach { item ->
                history.deleteVodRecord(item.sourceKey, item)
                WatchProgressStore.clearOwner(WatchProgressStore.ownerOf(item))
                TrackMemory.delete(TrackMemory.contentKey(item.sourceKey, item.id))
            }
        }
    }

    fun deleteAll() {
        placementAnim.value = false
        viewModelScope.launch(Dispatchers.IO) {
            history.deleteVodRecordAll()
            WatchProgressStore.clearAll()
            TrackMemory.deleteAll()
        }
    }

    companion object {
        fun key(item: VodInfo): String = item.sourceKey + "|" + item.id
    }
}
