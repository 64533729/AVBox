package com.github.tvbox.osc.player.danmu

import android.text.TextUtils
import android.view.View
import com.github.tvbox.osc.api.DanmakuApi
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.controller.PlayerControlApi
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.util.DanmuHelper
import com.github.tvbox.osc.util.LOG
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.ui.widget.DanmakuView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DanmuLoadController(
    private var videoView: MyVideoView?,
    private val controller: PlayerControlApi?,
    private val danmuView: DanmakuView?,
) {

    fun interface LoadCallback {
        fun onFailed()
    }

    private val danmakuContext: DanmakuContext = DanmakuContext.create()
    private val loadSeq = AtomicInteger()
    private var executor: ExecutorService? = null
    private var danmuText: String = ""
    private var danmuTitle: String = ""
    private var danmuEpisode: String = ""
    private var startedSeq: Int = -1
    private var pendingPrepare: Boolean = false
    private var temporarilyClosed: Boolean = false

    private var overlayHidden: Boolean = false
    private var loadCallback: LoadCallback? = null

    init {
        danmakuContext.updateMethod = 2
        videoView?.setDanmuView(danmuView)
        applySettings(false)
    }

    fun setVideoView(videoView: MyVideoView?) {
        this.videoView = videoView
        if (videoView != null && danmuView != null) {
            videoView.setDanmuView(danmuView)
        }
    }

    fun applySettings(reload: Boolean) {
        if (danmuView == null) return
        if (!DanmuHelper.isOpen()) {
            releaseView()
            controller?.setHasDanmu(!TextUtils.isEmpty(danmuText))
            return
        }
        val maxLines = HashMap<Int, Int>()
        val maxLine = DanmuHelper.getMaxLine()
        maxLines[BaseDanmaku.TYPE_FIX_TOP] = maxLine
        maxLines[BaseDanmaku.TYPE_SCROLL_RL] = maxLine
        maxLines[BaseDanmaku.TYPE_SCROLL_LR] = maxLine
        maxLines[BaseDanmaku.TYPE_FIX_BOTTOM] = maxLine
        danmakuContext.setMaximumLines(maxLines)
            .setScrollSpeedFactor(DanmuHelper.getSpeed())
            .setDanmakuTransparency(DanmuHelper.getAlpha())
            .setScaleTextSize(DanmuHelper.getSizeScale())
        danmakuContext.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3f)
            .setDanmakuMargin(8)
        if (reload && !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen()) {
            prepare(danmuText)
        }
    }

    fun check(danmu: String?) {
        check(danmu, "", "")
    }

    fun check(danmu: String?, title: String?, episode: String?) {
        check(danmu, title, episode, null)
    }

    fun check(danmu: String?, title: String?, episode: String?, callback: LoadCallback?) {
        loadCallback = callback
        temporarilyClosed = false
        danmuText = if (TextUtils.isEmpty(danmu)) "" else danmu!!.trim { it <= ' ' }
        danmuTitle = if (TextUtils.isEmpty(title)) "" else title!!
        danmuEpisode = if (TextUtils.isEmpty(episode)) "" else episode!!
        releaseView()
        val hasDanmu = !TextUtils.isEmpty(danmuText)
        controller?.setHasDanmu(hasDanmu)
        if (!hasDanmu || !DanmuHelper.isOpen()) {
            setViewVisible(false)
            return
        }
        setViewVisible(true)
        if (!isVideoReady()) {
            pendingPrepare = true
            return
        }
        prepare(danmuText)
    }

    fun startIfReady() {
        if (pendingPrepare && !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen() && isVideoReady()) {
            pendingPrepare = false
            prepare(danmuText)
            return
        }
        startIfReady(loadSeq.get())
    }

    fun reset() {
        DanmakuApi.cancel()
        temporarilyClosed = false
        danmuText = ""
        danmuTitle = ""
        danmuEpisode = ""
        pendingPrepare = false
        loadCallback = null
        loadSeq.incrementAndGet()
        startedSeq = -1
        controller?.setHasDanmu(false)
        releaseView()
    }

    fun close() {
        DanmakuApi.cancel()
        loadSeq.incrementAndGet()
        startedSeq = -1
        pendingPrepare = false
        releaseView()
    }

    fun toggle(): Boolean {
        if (temporarilyClosed) {
            temporarilyClosed = false
            reloadForPlayback()
            startIfReady()
            return true
        }
        temporarilyClosed = true
        close()
        return false
    }

    fun reloadForPlayback() {
        temporarilyClosed = false
        loadSeq.incrementAndGet()
        startedSeq = -1
        releaseView()
        pendingPrepare = !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen()
    }

    fun setOverlayHidden(hidden: Boolean) {
        if (overlayHidden == hidden) return
        overlayHidden = hidden
        if (hidden) {
            setViewVisible(false)
        } else {
            applyVisibility()
        }
    }

    private fun applyVisibility() {
        if (danmuView == null) return
        setViewVisible(
            DanmuHelper.isOpen()
                && !temporarilyClosed
                && (!TextUtils.isEmpty(danmuText) || pendingPrepare || danmuView.isPrepared)
        )
    }

    private fun setViewVisible(visible: Boolean) {
        val view = danmuView ?: return
        view.visibility = if (visible && !overlayHidden) View.VISIBLE else View.GONE
    }

    fun destroy() {
        reset()
        executor?.shutdownNow()
        executor = null
    }

    private fun prepare(danmu: String) {
        if (TextUtils.isEmpty(danmu)) return
        pendingPrepare = false
        val seq = loadSeq.incrementAndGet()
        startedSeq = -1
        LOG.i("echo-danmu load title: " + safeLog(danmuTitle) + ", episode: " + safeLog(danmuEpisode) + ", source: " + getSourceSummary(danmu))
        var worker = executor
        if (worker == null || worker.isShutdown) {
            worker = Executors.newSingleThreadExecutor()
            executor = worker
        }
        worker.execute {
            val parser = Parser(danmu) { seq != loadSeq.get() }
            if (seq != loadSeq.get()) return@execute
            val danmuCount = parser.getDanmuCount()
            LOG.i("echo-danmu parsed count: $danmuCount")
            val view = danmuView ?: return@execute
            view.post {
                if (seq != loadSeq.get()) return@post
                try {
                    view.release()
                    videoView?.setDanmuView(view)
                    if (danmuCount <= 0) {
                        LOG.e("echo-danmu empty after parse")
                        setViewVisible(false)
                        notifyLoadFailed(seq)
                        return@post
                    }
                    view.prepare(parser, danmakuContext)
                    clearLoadCallback(seq)
                    setViewVisible(DanmuHelper.isOpen())
                    startIfReady(seq)
                    view.postDelayed({ startIfReady(seq) }, 300)
                    view.postDelayed({ startIfReady(seq) }, 1000)
                } catch (th: Throwable) {
                    LOG.e("echo-danmu prepare error: " + th.message)
                    setViewVisible(false)
                    notifyLoadFailed(seq)
                }
            }
        }
    }

    private fun clearLoadCallback(seq: Int) {
        if (seq == loadSeq.get()) loadCallback = null
    }

    private fun notifyLoadFailed(seq: Int) {
        if (seq != loadSeq.get() || loadCallback == null) return
        val callback = loadCallback
        loadCallback = null
        callback?.onFailed()
    }

    private fun startIfReady(seq: Int) {
        val view = videoView
        val danmu = danmuView
        if (seq != loadSeq.get()
            || seq == startedSeq
            || view == null
            || !view.isPlaying
            || danmu == null
            || !danmu.isPrepared
            || !DanmuHelper.isOpen()
        ) {
            return
        }
        val position = view.currentPosition
        setViewVisible(true)
        danmu.seekTo(position)
        danmu.start(position)
        startedSeq = seq
        LOG.i("echo-danmu start at: $position")
    }

    private fun isVideoReady(): Boolean {
        val view = videoView ?: return false
        val state = view.playState
        return state == PlayState.PREPARED
            || state == PlayState.BUFFERED
            || state == PlayState.PLAYING
    }

    private fun releaseView() {
        val view = danmuView ?: return
        try {
            view.release()
        } catch (th: Throwable) {
            LOG.e("DanmuLoadController", "danmu view release failed", th)
        }
        setViewVisible(false)
    }

    private fun getSourceSummary(danmu: String?): String {
        if (TextUtils.isEmpty(danmu)) return ""
        if (danmu!!.startsWith("http") || danmu.startsWith("file")) return danmu
        return "inline xml length=" + danmu.length
    }

    private fun safeLog(text: String?): String = if (TextUtils.isEmpty(text)) "" else text!!
}
