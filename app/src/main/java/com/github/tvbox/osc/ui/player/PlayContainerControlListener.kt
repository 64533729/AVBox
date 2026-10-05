package com.github.tvbox.osc.ui.player

import android.text.TextUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.ParseBean
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.controller.VodControlListener
import com.github.tvbox.osc.player.state.DanmuSettingSheetState
import com.github.tvbox.osc.util.DanmuHelper
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.WatchProgressStore
import org.greenrobot.eventbus.EventBus
import java.util.HashMap

/**
 * 播放器控制层的业务回调(M7e 起为 Kotlin):把控制器的按钮/生命周期意图转发给 [PlayContainer]。
 *
 * <p>移植口径 = 纯语言迁移,逐行等价。Kotlin 侧的几点形态差异:
 * ① `kotlin.Unit.INSTANCE` 的 Java 匿名 lambda 换成 Kotlin lambda(同一 `Function0<Unit>`);
 * ② 容器字段在 Kotlin 侧是平台类型,故按 Java 侧的实参口径补 `?.`/`?: ""`
 *   (`scheduler.vod()` 的判空在 Java 里写了三遍,这里用局部量收敛但**判定次数与早退点逐条保留**);
 * ③ 可见性由"Java 包私有"改为 Kotlin 默认 public —— 跨语言调用(Java 持有者)需要它可见。
 */
class PlayContainerControlListener(private val container: PlayContainer) : VodControlListener {

    override fun showDanmuSetting() {
        if (!container.isAttached()) return
        container.mController.getUiState().danmuSettingSheet =
            DanmuSettingSheetState(
                onOpenSearch = { container.openDanmuSearchSheet() },
                onReset = {
                    DanmuHelper.reset()
                    container.applyDanmuSettings(true)
                },
            )
    }

    override fun toggleDanmu(): Boolean {
        val danmu = container.danmuLoadController ?: return false
        return danmu.toggle()
    }

    override fun showEpisodes() {
        container.mPageHost?.showEpisodeSheet()
    }

    override fun searchDanmuUi(longClick: Boolean) {
        val vod = container.scheduler.vod()
        val series = if (vod == null) {
            null
        } else {
            container.scheduler.currentSeries(vod.playFlag, vod.playIndex)
        }
        ApiConfig.get().searchDanmuUi(
            // Java 侧是平台类型直传(未判空);Kotlin 声明为非空 ⇒ 用 !! 复刻"直接解引用"的既有约定
            if (vod == null) "" else vod.name!!,
            series?.name ?: "",
            longClick,
        )
    }

    override fun playNext(rmProgress: Boolean) {
        val preProgressKey = container.scheduler.progressKey()
        val preOwner = container.scheduler.progressOwner()
        container.playNext(rmProgress)
        // 与 Java 侧同口径:仅当拿到父进度键时才清理(owner 与 key 同源、同时有值)
        if (rmProgress && preProgressKey != null) {
            WatchProgressStore.clear(preOwner ?: "", preProgressKey)
        }
    }

    override fun playPre() {
        container.playPrevious()
    }

    override fun changeParse(pb: ParseBean) {
        container.scheduler.resetAutoRetryState()
        container.scheduler.clearTriedLines()
        container.scheduler.doParse(pb)
    }

    override fun updatePlayerCfg() {
        val persistCfg = container.scheduler.playerCfgForPersist() ?: return
        container.scheduler.vod()?.playerCfg = persistCfg.toString()
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_REFRESH, persistCfg))
    }

    override fun replay(replay: Boolean) {
        container.reviveEngineIfReleased()
        container.scheduler.resetAutoRetryState()
        container.scheduler.clearTriedLines()
        container.scheduler.setPlaybackStarted(false)
        if (replay) {
            container.playViaScheduler(true)
        } else {
            container.replayCurrentAddress()
        }
    }

    override fun errReplay() {
        container.errorWithRetry(container.context.getString(R.string.player_error_play), false)
    }

    override fun closeSubtitles() {
        container.closeSubtitles()
    }

    override fun selectSubtitle() {
        try {
            container.selectMySubtitle()
        } catch (e: Exception) {
            LOG.e("PlayContainer", e)
        }
    }

    override fun selectAudioTrack() {
        container.selectMyAudioTrack()
    }

    override fun selectVideoTrack() {
        container.selectMyVideoTrack()
    }

    override fun prepared() {
        container.initSubtitleView()
        container.mVideoView?.prepared()
        container.startDanmuIfReady()
    }

    override fun startPlayUrl(url: String, headers: HashMap<String, String>?) {
        if (!TextUtils.isEmpty(container.scheduler.m3u8SourceUrl()) &&
            !container.scheduler.isM3u8ProxyUrl(url)
        ) {
            container.scheduler.clearM3u8ProxyUrl()
        }
        container.scheduler.goPlayUrl(url, headers)
    }

    override fun onM3u8ProxyUrl(proxyUrl: String, sourceUrl: String) {
        container.scheduler.setM3u8Urls(proxyUrl, sourceUrl)
    }

    override fun clickCast() {
        container.showCastDialog()
    }

    override fun setAllowSwitchPlayer(isAllow: Boolean) {
        container.scheduler.setAllowSwitchPlayer(isAllow)
    }

    override fun setAllowDecodeFallback(isAllow: Boolean) {
        container.scheduler.setAllowDecodeFallback(isAllow)
    }
}
