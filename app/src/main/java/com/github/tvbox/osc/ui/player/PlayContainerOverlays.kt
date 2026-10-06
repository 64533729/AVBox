package com.github.tvbox.osc.ui.player

import android.os.Looper
import android.widget.Toast
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.danmu.DanmuLoadController
import com.github.tvbox.osc.player.state.DanmuSearchSheetState
import master.flame.danmaku.ui.widget.DanmakuView

private const val PRELOAD_TOAST_REFRESH_DELAY_MS = 1000L

internal class PlayContainerOverlays(private val host: PlayContainer) {

    private var preloadReadyToast: Toast? = null

    private var mDanmuView: DanmakuView? = null

    private val refreshPreloadToastRunnable: Runnable = Runnable {
        if (preloadReadyToast != null) preloadReadyToast!!.show()
    }

    fun onTipStateChanged(tip: PlayerTipState) {
        if (host.mHandler == null) return
        val showing = tip.loading || tip.err
        host.mHandler?.post {
            if (host.mController != null) {
                host.mController.getUiState().applyTip(tip.msg, tip.loading, tip.err)
            }
            if (host.danmuLoadController != null) host.danmuLoadController?.setOverlayHidden(showing)
        }
    }

    fun initDanmuView() {
        mDanmuView = host.findViewById(R.id.danmaku)
        host.danmuLoadController = DanmuLoadController(host.mVideoView, host.mController, mDanmuView)
    }

    fun setDanmuViewSettings(reload: Boolean) {
        if (host.danmuLoadController != null) host.danmuLoadController!!.applySettings(reload)
    }

    fun applyDanmuSettings(reload: Boolean) {
        setDanmuViewSettings(reload)
    }

    fun checkDanmu(danmu: String?) {
        checkDanmu(danmu, null)
    }

    fun checkDanmu(danmu: String?, callback: DanmuLoadController.LoadCallback?) {
        host.scheduler.setPlayDanmu(danmu)
        if (host.danmuLoadController != null) {
            val series = if (host.scheduler.vod() == null) null else host.scheduler.currentSeries(host.scheduler.vod()!!.playFlag, host.scheduler.vod()!!.playIndex)
            host.danmuLoadController!!.check(danmu, host.scheduler.vod()?.name ?: "", series?.name ?: "", callback)
        }
    }

    fun startDanmuIfReady() {
        if (host.danmuLoadController != null) host.danmuLoadController!!.startIfReady()
    }

    fun resetDanmuState() {
        if (host.danmuLoadController != null) host.danmuLoadController!!.reset()
    }

    fun reloadDanmuForPlayback() {
        if (host.danmuLoadController != null) host.danmuLoadController!!.reloadForPlayback()
    }

    fun openDanmuSearchSheet() {
        if (!host.isAttached()) return
        val series = if (host.scheduler.vod() == null) null else host.scheduler.currentSeries(host.scheduler.vod()!!.playFlag, host.scheduler.vod()!!.playIndex)
        val uiState = host.mController.getUiState()
        uiState.danmuSearchSheet = DanmuSearchSheetState(
            series?.name ?: "",
            host.scheduler.vod()?.name ?: "",
        ) { danmu ->
            if (host.isAttached()) {
                checkDanmu(danmu)
            }
        }
    }

    fun setTip(msg: String, loading: Boolean, err: Boolean) {
        if (!host.isAttached()) return
        PlayerTipBridge.setTip(msg, loading, err)
    }

    fun hideTip() {
        PlayerTipBridge.hide()
    }

    fun hideTipOnUiThread() {
        if (!host.isAttached()) return
        PlayerTipBridge.hide()
    }

    fun showPreloadReady() {
        val activity = host.mActivity
        if (activity == null || !host.isAttached() || host.mHandler == null) return
        if (preloadReadyToast != null) preloadReadyToast!!.cancel()
        preloadReadyToast = Toast.makeText(activity, activity.getString(R.string.player_next_episode_ready), Toast.LENGTH_SHORT)
        preloadReadyToast!!.show()
        host.mHandler!!.removeCallbacks(refreshPreloadToastRunnable)
        host.mHandler!!.postDelayed(refreshPreloadToastRunnable, PRELOAD_TOAST_REFRESH_DELAY_MS)
    }

    fun hidePreloadReady() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelPreloadToast()
        } else if (host.mActivity != null) {
            host.mActivity!!.runOnUiThread {
                cancelPreloadToast()
            }
        }
    }

    fun cancelPreloadToast() {
        if (host.mHandler != null) host.mHandler!!.removeCallbacks(refreshPreloadToastRunnable)
        if (preloadReadyToast != null) {
            preloadReadyToast!!.cancel()
            preloadReadyToast = null
        }
    }

    fun errorWithRetry(err: String, finish: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            host.mHandler!!.post { errorWithRetry(err, finish) }
            return
        }
        if (host.scheduler.isPlaybackStarted()) {
            host.scheduler.cancelPlayTimeout()
            hideTipOnUiThread()
            if (host.scheduler.retryAfterStartedError()) return
            host.scheduler.stopMusicSessionForFailedPlayback()
            if (!host.isAttached()) return
            setTip(err, false, true)
            if (finish) {
                Toast.makeText(host.context, err, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!host.scheduler.autoRetry()) {
            host.scheduler.stopMusicSessionForFailedPlayback()
            if (!host.isAttached()) return
            setTip(err, false, true)
            if (finish) {
                Toast.makeText(host.context, err, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
