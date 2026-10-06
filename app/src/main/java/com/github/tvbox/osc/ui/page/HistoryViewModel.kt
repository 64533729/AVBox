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
import com.github.tvbox.osc.util.HistoryMerge
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.TrackMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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

    init {
        EventBus.getDefault().register(this)
        refresh()
        viewModelScope.launch {
            AppBootstrap.state.collect { boot ->
                if (boot is AppBootstrap.Boot.Ready) refresh()
            }
        }
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
    }

    val scrollSignal = MutableStateFlow(0)

    val placementAnim = MutableStateFlow(false)

    fun refresh(scrollToTop: Boolean = false) {
        if (HistoryHelper.isIncognito()) {
            incognito.value = true
            loading.value = false
            items.value = emptyList()
            episodeTotals.value = emptyMap()
            playedPercents.value = emptyMap()
            return
        }
        incognito.value = false
        if (items.value.isEmpty()) loading.value = true
        if (scrollToTop) placementAnim.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val limit = HistoryHelper.getHisNum(KV.get(HawkConfig.HISTORY_NUM, 0))
            val all = history.getAllVodRecord(limit)
            if (HistoryMerge.isEnabled()) {
                val (kept, dropped) = HistoryMerge.dedupe(all) { it.name }
                dropped.forEach { history.deleteVodRecord(it.sourceKey, it) }
                items.value = kept
            } else {
                items.value = all
            }
            episodeTotals.value = EpisodeTotals.snapshot()
            playedPercents.value = PlaybackProgress.snapshot()
            resolveSourceNames()
            loading.value = false
            if (scrollToTop) scrollSignal.value++
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_HISTORY_REFRESH) refresh(scrollToTop = event.obj !is Boolean)
        else if (event.type == RefreshEvent.TYPE_API_URL_CHANGE) refresh()
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
            refresh()
        }
    }

    fun deleteAll() {
        placementAnim.value = false
        viewModelScope.launch(Dispatchers.IO) {
            history.deleteVodRecordAll()
            WatchProgressStore.clearAll()
            TrackMemory.deleteAll()
            refresh()
        }
    }

    companion object {
        fun key(item: VodInfo): String = item.sourceKey + "|" + item.id
    }
}
