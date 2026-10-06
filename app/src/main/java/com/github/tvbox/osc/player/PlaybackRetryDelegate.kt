package com.github.tvbox.osc.player

import android.text.TextUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.EpisodeMatcher
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import org.json.JSONObject
import java.util.HashMap

class PlaybackRetryDelegate(private val host: Host) {

    interface Host {
        fun attemptState(): PlaybackAttemptState

        fun view(): PlaybackViewBridge?

        fun playerCfg(): JSONObject?

        fun vod(): VodInfo?

        fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries?

        fun progressKey(): String?

        fun getSavedProgress(url: String): Long

        fun inheritProgressFrom(key: String?, position: Long)

        fun webPlayUrl(): String?

        fun webHeaderMap(): HashMap<String, String>?

        fun resolverHasFoundUrls(): Boolean

        fun resolverConsumeFoundUrl()

        fun play(reset: Boolean)

        fun playUrl(url: String, headers: HashMap<String, String>?)

        fun stopParse()

        fun initParseLoadFound()

        fun cancelPlayRequest()

        fun cancelPlayTimeout()

        fun isPlaybackStarted(): Boolean

        fun isCrossContentReuseAllowed(): Boolean

        fun stopMusicSessionForFailedPlayback()
    }

    private fun restoreAutoSwitchedPlayer() {
        val st = host.attemptState()
        if (st.autoSwitchedPlayerType < 0) return
        st.setReleaseIntent(true)
        try {
            LOG.i("echo-autoRetry restore player: " + host.playerCfg()!!.optInt("pl", -1) + " -> " + st.autoSwitchedPlayerType)
            host.playerCfg()!!.put("pl", st.autoSwitchedPlayerType)
            host.view()?.applyPlayerConfig(host.playerCfg()!!)
        } catch (th: Throwable) {
            LOG.e("PlaybackController", th)
        } finally {
            st.autoSwitchedPlayerType = -1
        }
    }

    private fun restoreAutoSwitchedDecode() {
        val st = host.attemptState()
        if (st.autoSwitchedDecodeOld == null) return
        st.hasAutoSwitchedDecode = false
        try {
            val cfg = host.playerCfg()
            if (cfg != null) {
                LOG.i("echo-autoRetry restore decode: " + cfg.optString(st.autoSwitchedDecodeKey, "") + " -> " + st.autoSwitchedDecodeOld)
                cfg.put(st.autoSwitchedDecodeKey, st.autoSwitchedDecodeOld)
                host.view()?.applyPlayerConfig(cfg)
            }
        } catch (th: Throwable) {
            LOG.e("PlaybackController", th)
        } finally {
            st.autoSwitchedDecodeOld = null
        }
    }

    private fun exoLastErrorKind(): Int {
        try {
            val live = host.view()?.mediaPlayer()
            if (live is ExoPlayer) return live.lastErrorKind()
        } catch (ignored: Throwable) {
            LOG.d("PlaybackController", "exo error kind probe failed")
        }
        return ExoPlayer.ERROR_KIND_UNKNOWN
    }

    private fun trySoftDecodeFallback(): Boolean {
        val st = host.attemptState()
        val cfg = host.playerCfg() ?: return false
        if (st.hasAutoSwitchedDecode) return false
        if (cfg.optInt("pl", 2) >= 10) return false
        val decodeKey = "exo"
        if ("硬解码" != cfg.optString(decodeKey, "")) return false // i18n: keep —— 已经是软解,不再回退
        if (TextUtils.isEmpty(host.webPlayUrl())) return false
        val oldDecode = cfg.optString(decodeKey, "")
        try {
            cfg.put(decodeKey, "软解码") // i18n: keep
        } catch (th: Throwable) {
            return false
        }
        LOG.i("echo-autoRetry hard->soft decode: " + host.webPlayUrl())
        st.autoSwitchedDecodeOld = oldDecode
        st.autoSwitchedDecodeKey = decodeKey
        st.hasAutoSwitchedDecode = true
        val view = host.view()
        view?.applyPlayerConfig(cfg)
        host.stopParse()
        host.initParseLoadFound()
        if (view != null && view.isPageAlive()) {
            view.runOnUi { view.toast(PlaybackController.str(R.string.player_decode_fallback_tip)) }
        }
        view?.releasePlayer()
        if (view != null) host.playUrl(host.webPlayUrl()!!, host.webHeaderMap())
        return true
    }

    fun retryAfterStartedError(): Boolean {
        val st = host.attemptState()
        if (st.hasRetriedAfterStart) return false
        if (TextUtils.isEmpty(host.webPlayUrl())) return false
        st.hasRetriedAfterStart = true
        st.hasRetriedSameUrlOnBoot = true
        LOG.i("echo-autoRetry retry after started error: " + host.webPlayUrl())
        val view = host.view()
        if (view != null && view.isPageAlive()) {
            view.runOnUi { view.toast(PlaybackController.str(R.string.player_play_error_retry)) }
        }
        host.stopParse()
        host.initParseLoadFound()
        st.playbackStarted = false
        if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer()
        if (view != null) host.playUrl(host.webPlayUrl()!!, host.webHeaderMap())
        return true
    }

    private fun retryWithFreshResolve(reason: String): Boolean {
        val st = host.attemptState()
        if (!st.usedPreloadedResult) return false
        st.usedPreloadedResult = false
        LOG.i("echo-preload-stale: re-resolve current episode ($reason)")
        st.playbackStarted = false
        host.stopParse()
        host.initParseLoadFound()
        if (!host.isCrossContentReuseAllowed()) host.view()?.releasePlayer()
        host.play(false)
        return true
    }

    fun autoRetry(): Boolean {
        val st = host.attemptState()
        if (retryWithFreshResolve("autoRetry")) return true
        val currentTime = System.currentTimeMillis()
        if (currentTime - st.lastRetryTime > 60_000) {
            LOG.i("echo-reset-autoRetryCount")
            st.resetAutoRetryLadder()
        }
        st.lastRetryTime = currentTime
        if (host.resolverHasFoundUrls()) {
            host.resolverConsumeFoundUrl()
            return true
        }
        val exoErrorKind = exoLastErrorKind()
        if (exoErrorKind != ExoPlayer.ERROR_KIND_DECODE
            && !st.hasRetriedSameUrlOnBoot && !TextUtils.isEmpty(host.webPlayUrl())
        ) {
            st.hasRetriedSameUrlOnBoot = true
            LOG.i("echo-autoRetry replay same url before decode fallback: " + host.webPlayUrl())
            host.stopParse()
            host.initParseLoadFound()
            val view = host.view()
            if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer()
            if (view != null) host.playUrl(host.webPlayUrl()!!, host.webHeaderMap())
            return true
        }
        if (exoErrorKind != ExoPlayer.ERROR_KIND_NETWORK && trySoftDecodeFallback()) return true
        if (host.webPlayUrl() != null) {
            if (st.allowSwitchPlayer && !st.hasAutoSwitchedPlayer) {
                LOG.i("echo-autoRetry switch player and replay current url")
                val playerType = host.playerCfg()!!.optInt("pl", -1)
                val view = host.view()
                val switchSkipped = view != null && view.switchPlayerKernel()
                st.hasAutoSwitchedPlayer = true
                st.allowSwitchPlayer = false
                if (!switchSkipped) {
                    st.autoSwitchedPlayerType = playerType
                    host.stopParse()
                    host.initParseLoadFound()
                    if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer()
                    if (view != null) host.playUrl(host.webPlayUrl()!!, host.webHeaderMap())
                    return true
                }
            }
            LOG.i("echo-autoRetry current url failed after player switch, try next line")
            return tryNextLineIfEnabled()
        }
        return tryNextLineIfEnabled()
    }

    fun tryNextLineIfEnabled(): Boolean {
        restoreAutoSwitchedPlayer()
        restoreAutoSwitchedDecode()
        if (host.attemptState().allowAutoSwitchLine && (KV.get(HawkConfig.AUTO_SWITCH_LINE, false) as Boolean)) {
            return tryNextLine()
        }
        LOG.i("echo-autoRetry line switching disabled")
        host.attemptState().resetAutoRetryLadder()
        return false
    }

    fun tryNextLine(): Boolean {
        val st = host.attemptState()
        val vod = host.vod()
        val seriesMap = vod?.seriesMap
        if (vod == null || seriesMap == null || seriesMap.isEmpty()) {
            st.linesExhausted()
            return false
        }
        val currentFlag = vod.playFlag
        val currentIndex = Math.max(vod.playIndex, 0)
        val currentSeries = host.currentSeries(currentFlag, currentIndex)
        if (!TextUtils.isEmpty(currentFlag)) {
            st.triedLineFlags.add(currentFlag!!)
        }
        val lineFlags = EpisodeMatcher.lineFlagsInDisplayOrder(vod)
        val currentLineIndex = EpisodeMatcher.lineFlagIndex(lineFlags, currentFlag)
        val startLineIndex = if (currentLineIndex >= 0) currentLineIndex + 1 else 0
        var nextFlag: String? = null
        var nextIndex = 0
        for (i in startLineIndex until lineFlags.size) {
            val flag = lineFlags[i]
            val seriesList = seriesMap[flag]
            if (!st.triedLineFlags.contains(flag) && seriesList != null && seriesList.isNotEmpty()) {
                nextFlag = flag
                nextIndex = EpisodeMatcher.sameEpisodeIndex(currentSeries, seriesList, currentIndex)
                break
            }
        }
        val view = host.view()
        if (nextFlag == null) {
            LOG.i("echo-autoRetry all lines exhausted")
            st.linesExhausted()
            return view != null && view.onLinesExhausted()
        }
        val flagToSwitch = nextFlag
        val preProgressKey = host.progressKey()
        val savedProgress = if (TextUtils.isEmpty(preProgressKey)) 0L else host.getSavedProgress(preProgressKey!!)
        val preProgress = Math.max(savedProgress, if (view == null) 0L else view.currentPosition())
        LOG.i("echo-autoRetry switch line: " + vod.playFlag + " -> " + flagToSwitch)
        if (view != null && view.isPageAlive()) {
            view.runOnUi { host.view()!!.toast(PlaybackController.str(R.string.player_switch_line, flagToSwitch)) }
        }
        vod.playFlag = flagToSwitch
        vod.playIndex = nextIndex
        st.onLineSwitched()
        host.inheritProgressFrom(preProgressKey, preProgress)
        host.play(false)
        return true
    }

    fun handleResolvePlayUrlTimeout() {
        val st = host.attemptState()
        if (retryWithFreshResolve("resolveTimeout")) return
        LOG.i("echo-resolvePlayUrl timeout, try next line")
        host.cancelPlayRequest()
        host.stopParse()
        if (st.userPickedLine) {
            st.userPickedLine = false
            host.stopMusicSessionForFailedPlayback()
            showErrorTip(PlaybackController.str(R.string.player_get_url_timeout))
            return
        }
        if (!tryNextLineIfEnabled()) {
            host.stopMusicSessionForFailedPlayback()
            showErrorTip(PlaybackController.str(R.string.player_get_url_timeout))
        }
    }

    fun handleResolvePlayUrlFailed(err: String) {
        val st = host.attemptState()
        LOG.i("echo-resolvePlayUrl failed, try next line: $err")
        host.cancelPlayRequest()
        host.stopParse()
        if (st.userPickedLine) {
            st.userPickedLine = false
            host.cancelPlayTimeout()
            host.stopMusicSessionForFailedPlayback()
            showErrorTip(err)
            return
        }
        if (tryNextLineIfEnabled()) return
        host.cancelPlayTimeout()
        host.stopMusicSessionForFailedPlayback()
        showErrorTip(err)
    }

    fun handleSwitchLinePlayTimeout() {
        val st = host.attemptState()
        val view = host.view()
        val state = view?.playState()
        LOG.i("echo-switchLinePlay timeout state: $state, started: " + st.playbackStarted)
        if (host.isPlaybackStarted()) {
            host.cancelPlayTimeout()
            view?.hideTipOnUiThread()
            return
        }
        LOG.i("echo-switchLinePlay timeout, try next line")
        host.stopParse()
        if (st.hasAutoSwitchedPlayer) {
            if (!tryNextLineIfEnabled()) {
                host.stopMusicSessionForFailedPlayback()
                showErrorTip(PlaybackController.str(R.string.player_play_timeout))
            }
            return
        }
        if (!autoRetry()) {
            host.stopMusicSessionForFailedPlayback()
            showErrorTip(PlaybackController.str(R.string.player_play_timeout))
        }
    }

    private fun showErrorTip(err: String) {
        host.view()?.showTip(err, false, true)
    }
}
