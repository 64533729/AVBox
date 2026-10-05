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

/**
 * 音轨/视频轨选择(M7e 起为 Kotlin):从内核读出轨道列表 → 弹选择框 → 切轨(暂停 + 200ms 后按代次恢复)。
 *
 * <p>移植口径 = 纯语言迁移,逐行等价。Kotlin 侧的形态差异:
 * ① `instanceof ExoPlayer` 保留为 `is ExoPlayer`(内核只剩 EXO,但判据不变);
 * ② Java 的"泛型平台类型"在 Kotlin 侧需要显式局部量:先把 `host.player()` 与内核取成局部 `val`,
 *   否则 lambda 内重新求值会丢失 smart cast(判空次数与旧实现一致);
 * ③ 选择框回调从 Java 的 `pos -> { …; return kotlin.Unit.INSTANCE; }` 换成 Kotlin lambda
 *   (`pos < 0` 早退点用 if/else 表达,不引入非局部 return);
 * ④ `getTrackInfo()/getAudio()/getAudioSelected()` 等仍是 Kotlin **函数**(非属性),按函数调用。
 */
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
        // ⚠️ 不要在这里 `?: return`:Java 在无内核时 instanceof 判假 ⇒ trackInfo==null ⇒ **弹提示**;
        // 提前返回会把提示整段跳过(无内核时点按钮从"有提示"退化成"静默无反应")
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
        // 与 Java 侧同口径:name 直接作为非空使用(平台类型直传)
        for (item in bean) names.add(item.name!!)
        val selected = trackInfo.getAudioSelected(false)
        // 诊断:把弹窗列出的轨道与当前选中项落盘(vivo ROM 吞 logcat,只能看文件日志)
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
                    // 诊断:记录点击了哪条轨 + 切换前的位置/状态
                    LOG.i(
                        "echo-setTrack request: name=" + value.name + " render=" + value.renderId +
                            " group=" + value.trackGroupId + " track=" + value.trackId +
                            " pos=" + progress + " state=" + (host.player()?.currentPlayState ?: -999),
                    )
                    (mediaPlayer as? ExoPlayer)?.setTrack(value)
                    val seq = trackSwitchSeq.incrementAndGet()
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (seq == trackSwitchSeq.get()) {
                            mediaPlayer.start()
                            // 诊断:切轨 +200ms 后的内核状态。⚠️ 只读播放状态、不读位置:本 runnable 在 try 块之外,
                            // 而这 200ms 内内核可能已被释放
                            LOG.i(
                                "echo-setTrack after start: state=" +
                                    (host.player()?.currentPlayState ?: -999),
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
        // ⚠️ 不要在这里 `?: return`:Java 在无内核时 instanceof 判假 ⇒ trackInfo==null ⇒ **弹提示**;
        // 提前返回会把提示整段跳过(无内核时点按钮从"有提示"退化成"静默无反应")
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
