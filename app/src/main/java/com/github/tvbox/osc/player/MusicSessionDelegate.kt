package com.github.tvbox.osc.player

import android.text.TextUtils
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.util.LOG
import org.json.JSONArray
import org.json.JSONObject
import java.util.HashMap

class MusicSessionDelegate(private val host: Host) {

    interface Host {
        fun view(): PlaybackViewBridge?

        fun attemptState(): PlaybackAttemptState

        fun timeouts(): PlaybackTimeouts

        fun vod(): VodInfo?

        fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries?

        fun quality(): JSONObject?

        fun isStartedPlayState(state: PlayState): Boolean

        fun retryAfterStartedError(): Boolean

        fun initParse(flag: String?, useParse: Boolean, playUrl: String, url: String)

        fun playUrl(url: String, headers: HashMap<String, String>?)
    }

    private var playArtwork: String? = null

    private var playDanmu: String? = null

    private var currentArtwork: String? = null

    fun clearArtworks() {
        playArtwork = null
        currentArtwork = null
    }

    fun beginSwitchPlayback() {
        host.attemptState().switchingPlayback = true
        host.timeouts().cancelPendingCompletionDrop()
    }

    fun playArtwork(): String? = playArtwork

    fun currentArtwork(): String? = currentArtwork

    fun setCurrentArtwork(artwork: String?) {
        currentArtwork = artwork
    }

    fun playDanmu(): String? = playDanmu

    fun setPlayDanmu(danmu: String?) {
        playDanmu = danmu ?: ""
    }

    fun stopMusicSessionForFailedPlayback() {
        host.attemptState().clearSessionFlags()
        host.timeouts().cancelPendingCompletionDrop()
        stopMusicSession()
    }

    fun stopMusicSession() {
        val view = host.view() ?: return
        PlaybackService.stopSession(view.context(), view.playbackHost())
    }

    fun isConfirmedAudioOnly(): Boolean {
        return java.lang.Boolean.TRUE == isAudioOnlyPlayback() || host.attemptState().audioOnlyConfirmed
    }

    fun handlePlayStateForMusicSession(playState: PlayState): Boolean {
        val st = host.attemptState()
        if (st.switchingPlayback) {
            if (playState == PlayState.COMPLETED) {
                LOG.i("echo-music keep session while resolving next episode")
                return true
            } else if (playState == PlayState.ERROR) {
                st.switchingPlayback = false
            } else if (host.isStartedPlayState(playState)) {
                if (hasPlayableAudio() || st.audioPlayback) {
                    st.switchingPlayback = false
                    st.audioPlayback = true
                }
            }
        }
        if (!st.switchingPlayback) {
            if (playState == PlayState.COMPLETED) {
                host.timeouts().cancelPendingCompletionDrop()
                host.timeouts().armPendingCompletionDrop()
                return false
            }
            updateMusicSession()
        }
        return false
    }

    fun handlePendingCompletionDrop() {
        val st = host.attemptState()
        if (st.switchingPlayback) {
            LOG.i("echo-music completion drop skipped: page registered switching")
            return
        }
        val view = host.view() ?: return
        if (!PlaybackService.isSupported(view.context())) return
        val state = view.playState()
        if (host.isStartedPlayState(state)) {
            LOG.i("echo-music completion drop skipped: kernel already started, state=$state")
            return
        }
        if (!view.isPageAlive()) {
            LOG.i("echo-music completion drop skipped: page not alive, state=$state")
            return
        }
        if (state != PlayState.COMPLETED) {
            LOG.i("echo-music completion drop skipped: state moved on, state=$state")
            return
        }
        LOG.i("echo-music session drop after completed (deferred): no next episode registered")
        PlaybackService.stopSession(view.context(), view.playbackHost())
        st.audioPlayback = false
    }

    private fun hasPlayableAudio(): Boolean {
        val trackInfo = currentTrackInfo()
        return trackInfo != null && trackInfo.getAudio().isNotEmpty()
    }

    private fun isAudioOnlyPlayback(): Boolean? {
        val trackInfo = currentTrackInfo()
        if (trackInfo == null || trackInfo.getAudio().isEmpty()) return null
        return trackInfo.getVideo().isEmpty()
    }

    private fun currentTrackInfo(): TrackInfo? {
        val view = host.view() ?: return null
        try {
            val mediaPlayer = view.mediaPlayer()
            if (mediaPlayer is ExoPlayer) {
                return mediaPlayer.getTrackInfo()
            }
        } catch (ignored: Throwable) {
            LOG.d("PlaybackController", "track info unavailable")
        }
        return null
    }

    fun ensureAudioOnlyRender() {
        val view = host.view() ?: return
        val audioOnly = isAudioOnlyPlayback()
        if (java.lang.Boolean.TRUE == audioOnly) {
            view.switchRenderToTexture()
        } else if (java.lang.Boolean.FALSE == audioOnly) {
            view.ensureRenderViewMatchesConfig()
        }
    }

    fun updateMusicSession() {
        val view = host.view() ?: return
        if (!view.isPageAlive()) return
        val context = view.context()
        if (!PlaybackService.isSupported(context)) return
        val st = host.attemptState()
        if (st.switchingPlayback) return
        val trackInfo = currentTrackInfo()
        val hasAudio: Boolean? = trackInfo != null && trackInfo.getAudio().isNotEmpty()
        val audioOnly: Boolean? = if (trackInfo == null || trackInfo.getAudio().isEmpty()) {
            null
        } else {
            trackInfo.getVideo().isEmpty()
        }
        if (java.lang.Boolean.TRUE == hasAudio) {
            st.audioPlayback = true
            if (java.lang.Boolean.TRUE == audioOnly) st.audioOnlyConfirmed = true
        }
        val state = view.playState()
        LOG.i(
            "echo-music session gate: state=" + state + " playing=" + view.isPlaying()
                + " hasAudio=" + hasAudio + " audioOnly=" + audioOnly + " audioPlayback=" + st.audioPlayback
                + " audioOnlyConfirmed=" + st.audioOnlyConfirmed + " switching=" + st.switchingPlayback
                + " pos=" + view.currentPosition()
        )
        if (st.audioPlayback && java.lang.Boolean.TRUE == audioOnly
            && !host.isStartedPlayState(state)
            && TextUtils.isEmpty(playArtwork) && host.vod() != null && !TextUtils.isEmpty(host.vod()!!.pic)
        ) {
            playArtwork = host.vod()!!.pic
            view.setArtwork(playArtwork!!)
        }
        if ((state == PlayState.ERROR && st.audioOnlyConfirmed) && host.retryAfterStartedError()) {
            LOG.i("echo-music session keep: auto retry after started error (audio-only)")
            return
        }
        if (host.vod() == null || !st.audioPlayback
            || state == PlayState.ERROR
            || state == PlayState.COMPLETED
        ) {
            LOG.i(
                "echo-music session drop: vod=" + (host.vod() != null) + " audioPlayback=" + st.audioPlayback
                    + " state=" + state + " (ERROR=" + PlayState.ERROR
                    + " COMPLETED=" + PlayState.COMPLETED + ")"
            )
            PlaybackService.stopSession(context, view.playbackHost())
            st.audioPlayback = false
            return
        }
        view.requestNotificationPermission()
        val currentSeries = host.currentSeries(host.vod()!!.playFlag, host.vod()!!.playIndex)
        val episode = if (currentSeries == null || TextUtils.isEmpty(currentSeries.name)) "" else currentSeries.name
        PlaybackService.updateSession(
            context, view.playbackHost(),
            if (TextUtils.isEmpty(host.vod()!!.name)) "TVBox" else host.vod()!!.name,
            episode, host.vod()!!.pic, view.currentPosition(), view.duration(), view.isPlaying()
        )
    }

    fun selectQuality(position: Int): Boolean {
        val quality = host.quality() ?: return false
        return try {
            val urls = JSONArray(quality.optString("url"))
            val url = urls.optString(position * 2 + 1)
            if (TextUtils.isEmpty(url)) return false
            val playUrl = quality.optString("playUrl", "")
            val flag = quality.optString("flag")
            val parse = quality.optString("parse", "1") == "1"
            val jx = quality.optString("jx", "0") == "1"
            val headers = PlaybackController.extractHeaders(quality)
            if (parse || jx) {
                val flags = ApiConfig.get().getVipParseFlags() ?: mutableListOf()
                val userJxList = (playUrl.isEmpty() && flags.contains(flag)) || jx
                host.initParse(flag, userJxList, playUrl, url)
            } else {
                host.view()?.showParse(false)
                host.playUrl(playUrl + url, headers)
            }
            true
        } catch (th: Throwable) {
            false
        }
    }
}
