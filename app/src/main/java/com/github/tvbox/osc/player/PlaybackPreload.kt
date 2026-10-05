package com.github.tvbox.osc.player

import com.github.tvbox.osc.sourcedata.SourceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import xyz.doikki.videoplayer.player.VideoView

/**
 * 预载调度:何时喂快照、何时取结果、何时作废(目标评估时机与冷却期都在 [PreloadCoordinator])。
 * 快照由页面侧组装(需要页面上下文与真实内核实例)。
 */
class PlaybackPreload(private val host: Host) {

    interface Host {
        fun view(): PlaybackViewBridge?

        /** 预载协调器需要取流实例;为空时宿主先建(见 PlaybackController.initFetch) */
        fun sourceViewModel(): SourceViewModel?

        fun ensureFetch()

        /** 预载结果命中:标记来源(失败时走重新取流)并把结果喂给取流链路 */
        fun onPreloadedResult(info: JSONObject?)
    }

    private var preloadCoordinator: PreloadCoordinator? = null
    private var preloadReadyListener: PreloadManagerHolder.ReadyListener? = null
    private var collectJob: Job? = null

    /** 建立预载协调器与"下一集已就绪"回调(页面 init 时调用一次,须在取流实例建立之后) */
    fun init() {
        if (host.sourceViewModel() == null) host.ensureFetch()
        val vm = host.sourceViewModel()
        preloadCoordinator = PreloadCoordinator(vm)
        // 预载结果的收集域随本对象:主线程派发,与旧 LiveData.observeForever 的投递线程一致
        if (vm != null) {
            collectJob = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                vm.preloadResult.flow.collect { info -> preloadCoordinator?.handlePreloadResult(info) }
            }
        }
        val listener = PreloadManagerHolder.ReadyListener { _ ->
            val view = host.view()
            if (view == null || !view.isPageAlive()) return@ReadyListener
            view.runOnUi {
                val current = host.view()
                current?.showPreloadReadyTip()
            }
        }
        preloadReadyListener = listener
        PreloadManagerHolder.setReadyListener(listener)
    }

    /**
     * 播放状态变化驱动预载评估(页面状态回调里调用):
     * STATE_PLAYING 正片稳定 → 延迟评估;STATE_BUFFERING 弱网 → 让路(清数据 + 冷却);
     * STATE_BUFFERED 缓冲结束 → 补一次评估(dkplayer 的 STATE_PLAYING 只在首帧发一次,不补枪则拖一次进度条就永久停摆)。
     */
    fun onPlayerState(playState: Int) {
        val coordinator = preloadCoordinator ?: return
        val view = host.view()
        // 无页面(仅引擎)时快照为空:跳过评估(预载需要页面上下文与集信息)
        if (view == null) return
        if (playState == VideoView.STATE_PLAYING || playState == VideoView.STATE_BUFFERED) {
            coordinator.scheduleEvaluate(view.buildPreloadSnapshot())
        } else if (playState == VideoView.STATE_BUFFERING) {
            coordinator.onMainPlayerBuffering()
        }
    }

    /**
     * 起播前消费预载结果。
     *
     * @return true = 命中并已把结果交给取流链路(调用方不要再发起取流)
     */
    fun consumeResult(progressKey: String?): Boolean {
        val coordinator = preloadCoordinator ?: return false
        val preResult = coordinator.consumeResult(progressKey)
        if (preResult != null) {
            // 直链可能已过期:标记来源,失败时走 retryWithFreshResolve 重取一次
            host.onPreloadedResult(preResult)
            return true
        }
        // 未复用 = 切到的不是预载目标集(或缓存过期):预载数据失效,清掉
        coordinator.dropPreloadData()
        return false
    }

    /** 切集/换线/换源/重播:作废在途预解析与预载数据(稳定播放后重新评估) */
    fun invalidate() {
        preloadCoordinator?.invalidate()
    }

    /** 页面销毁:停协调器 + 注销就绪回调(防页面销毁后回调/Toast 残留) */
    fun destroy() {
        PreloadManagerHolder.clearReadyListener(preloadReadyListener)
        preloadReadyListener = null
        collectJob?.cancel()
        collectJob = null
        preloadCoordinator?.destroy()
        preloadCoordinator = null
    }
}
