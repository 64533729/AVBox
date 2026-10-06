package com.github.tvbox.osc.ui.page

import android.widget.Toast
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.io.FileUtils
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.SearchViewModel
import com.github.tvbox.osc.util.BootGuard
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.greenrobot.eventbus.EventBus

object AppBootstrap {

    sealed interface Boot {
        data object Loading : Boot
        data class Ready(val epoch: Long) : Boot
        data class Error(val msg: String) : Boot
    }

    private val _state = MutableStateFlow<Boot>(Boot.Loading)
    val state: StateFlow<Boot> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bootGeneration = BootGeneration()
    private var started = false

    fun start() {
        if (started) return
        started = true
        FileUtils.repairBogusNativeLibs()
        BootGuard.disableBootLoopingSource()
        ControlManager.get().startServer()
        startInit(forceFresh = false, offline = false)
    }

    fun retry() {
        _state.value = Boot.Loading
        startInit(forceFresh = true, offline = false)
    }

    fun continueOffline() {
        _state.value = Boot.Loading
        startInit(forceFresh = false, offline = true)
    }

    fun onApiUrlChanged() {
        val generation = bootGeneration.next()
        _state.value = Boot.Loading
        LOG.i("echo-switch: request")
        scope.launch {
            val startedAt = System.currentTimeMillis()
            ApiConfig.get().invalidateVodConfig()
            LOG.i("echo-switch: invalidate dt=" + (System.currentTimeMillis() - startedAt) + "ms")
            SearchViewModel.clearCheckedSources()
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_API_URL_CHANGE))
            LOG.i("echo-switch: broadcast dt=" + (System.currentTimeMillis() - startedAt) + "ms")
            startInit(forceFresh = true, offline = false, generation = generation)
        }
    }

    fun switchVodSubscription(url: String): Boolean {
        val followLive = ApiConfig.isLiveFollowVod()
        val oldApi = KV.get(HawkConfig.API_URL, "")
        val oldFollowTarget = KV.get(HawkConfig.LIVE_API_URL, "").ifEmpty { oldApi }
        HistoryHelper.setApiHistory(url)
        KV.put(HawkConfig.API_URL, url)
        if (followLive) {
            KV.put(HawkConfig.LIVE_API_URL, "")
            if (url != oldFollowTarget) HistoryHelper.clearLiveApiLineList()
        }
        if (!HistoryHelper.isApiLineHistory(url)) HistoryHelper.clearApiLineList()
        if (oldApi == url) {
            ApiConfig.get().invalidateLiveConfig()
            return followLive
        }
        onApiUrlChanged()
        return followLive
    }

    private fun startInit(forceFresh: Boolean, offline: Boolean, generation: Long = bootGeneration.next()) {
        scope.launch {
            var dataInitOk = offline
            var jarInitOk = offline
            if (!dataInitOk) {
                val err = awaitLoadConfig(forceFresh)
                if (err != null) {
                    if (err == "-1") {
                        dataInitOk = true
                        jarInitOk = true
                    } else {
                        if (bootGeneration.isLatest(generation)) {
                            _state.value = Boot.Error(err)
                        }
                        return@launch
                    }
                } else {
                    dataInitOk = true
                    if (ApiConfig.get().getSpider()!!.isEmpty()) jarInitOk = true
                }
            }
            if (dataInitOk && !jarInitOk) {
                val err = awaitLoadJar()
                jarInitOk = true
                if (err != null) toast(err + " jar load err")
            }
            if (dataInitOk && jarInitOk && bootGeneration.isLatest(generation)) {
                ApiConfig.get().warmSearchSpiders()
                _state.value = Boot.Ready(generation)
            }
        }
    }

    private const val CONFIG_CACHE_TTL_MS = 12 * 60 * 60 * 1000L

    private fun useCachedConfig(): Boolean {
        val apiUrl = KV.get(HawkConfig.API_URL, "")
        if (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://")) return false
        val app = App.getInstance() ?: return false
        val cache = File(app.filesDir, MD5.encode(apiUrl)!!)
        return cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < CONFIG_CACHE_TTL_MS
    }

    private suspend fun awaitLoadConfig(forceFresh: Boolean): String? = suspendCancellableCoroutine { cont ->
        ApiConfig.get().loadConfig(!forceFresh && useCachedConfig(), object : ApiConfig.LoadConfigCallback {
            override fun success() {
                if (cont.isActive) cont.resume(null)
            }

            override fun error(msg: String?) {
                if (cont.isActive) cont.resume(msg ?: "-1")
            }

            override fun notice(msg: String?) {
                toast(msg)
            }
        }, null)
    }

    private suspend fun awaitLoadJar(): String? = suspendCancellableCoroutine { cont ->
        ApiConfig.get().loadJar(false, ApiConfig.get().getSpider(), object : ApiConfig.LoadConfigCallback {
            override fun success() {
                if (cont.isActive) cont.resume(null)
            }

            override fun error(msg: String?) {
                if (cont.isActive) cont.resume(msg ?: "")
            }

            override fun notice(msg: String?) {
                toast(msg)
            }
        })
    }

    private fun toast(msg: String?) {
        if (msg.isNullOrEmpty()) return
        val context = com.github.tvbox.osc.base.App.getInstance()!!
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
