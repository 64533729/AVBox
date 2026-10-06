package com.github.tvbox.osc.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.ExoPlayer
import com.github.tvbox.osc.player.KernelPlayer
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.TrackInfoBean
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.player.state.SelectDialogState
import com.github.tvbox.osc.util.LOG
import java.util.concurrent.atomic.AtomicInteger

class TrackSelectorDelegate(private val host: Host) {

    interface Host {
        fun player(): MyVideoView?

        fun context(): Context

        fun uiState(): PlayerUiState
    }

    private val trackSwitchSeq = AtomicInteger(0)

    fun invalidatePendingSwitch() {
        trackSwitchSeq.incrementAndGet()
    }

    fun selectAudioTrack() {
        val view = host.player() ?: return
        val mediaPlayer: KernelPlayer? = view.mediaPlayer
        val context = host.context()
        val trackInfo = (mediaPlayer as? ExoPlayer)?.getTrackInfo()
        if (trackInfo == null) {
            Toast.makeText(context, context.getString(R.string.player_no_audio_track), Toast.LENGTH_SHORT).show()
            return
        }
        val bean = trackInfo.getAudio()
        if (bean.size < 1) return
        val names = ArrayList<String>()
        for (item in bean) names.add(item.name!!)
        val selected = trackInfo.getAudioSelected(false)
        LOG.i(
            "echo-setTrack list: kernel=" + mediaPlayer.javaClass.simpleName +
                " count=" + bean.size + " selected=" + selected +
                " names=" + names,
        )
        host.uiState().selectDialog = SelectDialogState(
            context.getString(R.string.player_switch_audio_track),
            names,
            selected,
        ) { pos ->
            if (pos >= 0 && pos < bean.size) {
                val value = bean[pos]
                try {
                    for (audio in bean) {
                        audio.selected = isSameTrack(audio, value)
                    }
                    mediaPlayer.pause()
                    val progress = mediaPlayer.currentPosition
                    LOG.i(
                        "echo-setTrack request: name=" + value.name + " render=" + value.renderId +
                            " group=" + value.trackGroupId + " track=" + value.trackId +
                            " pos=" + progress + " state=" + (host.player()?.playState ?: "null"),
                    )
                    (mediaPlayer as? ExoPlayer)?.setTrack(value)
                    val seq = trackSwitchSeq.incrementAndGet()
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (seq == trackSwitchSeq.get()) {
                            mediaPlayer.start()
                            LOG.i(
                                "echo-setTrack after start: state=" +
                                    (host.player()?.playState ?: "null"),
                            )
                        }
                    }, 200)
                } catch (e: Exception) {
                    LOG.e("切换音轨出错")
                }
            }
        }
    }

    fun selectVideoTrack() {
        val view = host.player() ?: return
        val mediaPlayer: KernelPlayer? = view.mediaPlayer
        val context = host.context()
        val trackInfo = (mediaPlayer as? ExoPlayer)?.getTrackInfo()
        if (trackInfo == null || trackInfo.getVideo().isEmpty()) {
            Toast.makeText(context, context.getString(R.string.player_no_video_track), Toast.LENGTH_SHORT).show()
            return
        }
        val tracks = trackInfo.getVideo()
        val names = ArrayList<String>()
        for (item in tracks) names.add(item.name!!)
        host.uiState().selectDialog = SelectDialogState(
            context.getString(R.string.player_switch_video_track),
            names,
            trackInfo.getVideoSelected(false),
        ) { pos ->
            if (pos >= 0 && pos < tracks.size) {
                val value = tracks[pos]
                try {
                    for (track in tracks) {
                        track.selected = isSameTrack(track, value)
                    }
                    mediaPlayer.pause()
                    val progress = mediaPlayer.currentPosition
                    (mediaPlayer as? ExoPlayer)?.setTrack(value)
                    val seq = trackSwitchSeq.incrementAndGet()
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (seq == trackSwitchSeq.get()) {
                            mediaPlayer.seekTo(progress)
                            mediaPlayer.start()
                        }
                    }, 200)
                } catch (e: Exception) {
                    LOG.e("echo-switch-video-track-error:" + e.message)
                }
            }
        }
    }

    companion object {

        @JvmStatic
        fun isSameTrack(left: TrackInfoBean, right: TrackInfoBean): Boolean =
            left.renderId == right.renderId &&
                left.trackGroupId == right.trackGroupId &&
                left.trackId == right.trackId
    }
}
