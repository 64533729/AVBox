package com.github.tvbox.osc.player.effect

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import com.github.tvbox.osc.player.PlayerCodecStats
import com.github.tvbox.osc.player.engine.CodecPreferences
import com.github.tvbox.osc.util.LOG

class ReplayableCacheVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val lateThresholdToDropDecoderInputUs: Long,
) : MediaCodecVideoRenderer(builder) {

    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl,
    ): PlaybackVideoGraphWrapper =
        PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
            .setEnablePlaylistMode(true)
            .experimentalSetLateThresholdToDropInputUs(
                if (lateThresholdToDropDecoderInputUs != C.TIME_UNSET) {
                    -lateThresholdToDropDecoderInputUs
                } else {
                    C.TIME_UNSET
                },
            )
            .setClock(clock)
            .setEnableReplayableCache(true)
            .build()

    override fun onCodecInitialized(
        name: String,
        configuration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs)
        PlayerCodecStats.videoDecoderName = name
        LOG.i("echo-exo-codec-init: name=$name preferSoft=${CodecPreferences.isPreferSoftwareDecode()}")
    }
}
