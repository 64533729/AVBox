package com.github.tvbox.osc.player.controller

import android.content.Context
import android.media.AudioManager
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.AppPlayerView
import com.github.tvbox.osc.player.ui.VideoGestureActions
import com.github.tvbox.osc.player.ui.VideoGestureSession
import com.github.tvbox.osc.util.GestureHelper
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.PlayerUtils

/**
 * 把 [VideoGestureActions] 落到 [ComposeVideoController]。
 *
 * <p>**为什么单独一个类**:手势的"判定"在 [com.github.tvbox.osc.player.ui.VideoGestureHandler],
 * "接线"在 [com.github.tvbox.osc.player.ui.videoGestureLayer],这里只做**副作用落实**
 * (控制条显隐、播放暂停、倍速、seek、亮度音量)。把它从控制器里分出来,控制器不必再持有
 * 一大堆手势用的临时基准量,也便于单独审阅。
 *
 * <p>移植口径对齐旧 `GestureController` 的同名回调(逐条):
 * 单击=等双击窗口后显隐(锁屏时改唤锁屏钮);双击=播放暂停;长按=倍速(暂停态/预览态/锁屏不触发);
 * 横滑=按满屏宽 `slideFullWidthMs` 缩放并 `seekTo`;竖滑左半屏亮度、右半屏音量。
 *
 * <p>**与旧实现的两处刻意差异**:
 * ① 亮度/音量基准改为**每个手势会话现取**(旧实现只在 `onDown` 取,同一会话内取一次;
 *   这里 [beginSession] 每次 DOWN 都会重取,语义与旧实现一致而不再跨会话串味);
 * ② 长按倍速的恢复**不依赖 ACTION_UP**:接线层在长按计时结束时就会调 [onLongPressEnd],
 *    避免 CANCEL 时倍速停在 3.0x。
 */
internal class VideoGestureActionsImpl(private val host: ComposeVideoController) : VideoGestureActions {

    /**
     * 横滑满屏宽对应的时长。
     *
     * <p>旧实现是 240000(4 分钟/屏);真机反馈"太灵敏"⇒ 收敛为 **120000(2 分钟/屏)**,
     * 同样的手指位移只走一半时长,更容易停在想要的点上。常量集中在此便于再调。
     */
    private val slideFullWidthMs = 120000f

    /**
     * 竖滑灵敏度:满屏高对应多少倍范围。
     *
     * <p>旧实现是 `deltaY * 2 / height`(半屏就走完 0..100%);真机反馈"太灵敏"⇒ 改为 **0.9**:
     * 需要接近整屏高度才走完整个范围,微调更好停。
     */
    private val verticalSensitivity = 0.9f

    /** 本次手势的横滑目标(-1 = 无) */
    private var seekTargetMs = -1

    private val audioManager: AudioManager? by lazy {
        host.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    override fun inPlayback(): Boolean = host.isInPlaybackState()

    /**
     * 组装本次会话快照。**宽高与边缘带每次现算**,不用组合期常量
     * (旧草稿把屏幕几何冻结在组合期,旋转后不重算 —— 复核中危项 ⑥)。
     */
    fun beginSession(width: Int, height: Int, screenWidth: Int): VideoGestureSession {
        seekTargetMs = -1
        val view = host.playerView
        val paused = host.curPlayState == AppPlayerView.STATE_PAUSED
        return VideoGestureSession(
            inPlayback = host.isInPlaybackState(),
            canChangePosition = host.gestureCanChangePosition(),
            enableInNormal = host.gestureEnableInNormal(),
            fullScreen = host.playerState() == AppPlayerView.PLAYER_FULL_SCREEN,
            locked = host.isLocked,
            previewMode = host.previewMode,
            paused = paused,
            gestureEnabled = host.gestureEnabled(),
            verticalSlidingDisabled = GestureHelper.isControlDisabled(),
            width = width,
            height = height,
            screenWidth = screenWidth,
            edge = false,
        )
    }

    override fun onSingleTap() {
        // 锁屏:只唤出锁屏钮(旧 onTouch 在锁屏时吞掉全部事件并只在 UP 时 showLockView)
        if (host.isLocked) {
            host.showLockView()
            return
        }
        host.toggleControls()
    }

    override fun onDoubleTapTogglePlay() {
        if (!host.isLocked) host.togglePlayFromGesture()
    }

    override fun onLongPressStart() {
        if (host.isLocked) return
        host.speedOld = host.currentSpeed()
        // 实时读设置:改完立即生效(旧实现同样每次长按现读)
        val boost = KV.get(HawkConfig.LONG_PRESS_SPEED, HawkConfig.LONG_PRESS_SPEED_DEFAULT).toFloat()
        host.setSpeedFromGesture(boost)
        // ⚠️ 必须同时点亮提示:只改速度不设这两个状态,真机上会"能提速但看不到倍速提示"(实测)
        host.state.speedBoostValue = boost
        host.state.speedBoostVisible = true
    }

    override fun onLongPressEnd() {
        host.setSpeedFromGesture(host.speedOld)
        host.state.speedBoostVisible = false
    }

    override fun onSeekPreview(totalDeltaX: Float) {
        if (host.isLocked) return
        val width = host.width
        if (width <= 0) return
        val view = host.playerView ?: return
        // 与拖动 seek 同口径进入"拖拽态":进度定时器据此丢弃这一拍
        host.enterGestureSeek()
        val duration = PlayerUtils.safeTimeMs(view.duration)
        val current = PlayerUtils.safeTimeMs(view.currentPosition)
        // 右滑(deltaX > 0)= 前进;满屏宽对应 slideFullWidthMs
        var target = (totalDeltaX / width * slideFullWidthMs + current).toInt()
        if (target > duration) target = duration
        if (target < 0) target = 0
        host.updateSeekUiHint(current, target)
        seekTargetMs = target
    }

    override fun onSeekCommit() {
        val target = seekTargetMs
        seekTargetMs = -1
        host.exitGestureSeek()
        if (target < 0) return
        host.seekToFromGesture(target.toLong())
        // 显式落盘:暂停态等不到下一跳,播放态也不该等到下一跳才更新历史页
        host.saveGestureProgress(target)
    }

    override fun onSeekCancel() {
        seekTargetMs = -1
        host.exitGestureSeek()
    }

    override fun onBrightnessSlide(totalDeltaY: Float) {
        if (host.isLocked) return
        val activity = host.playerActivity() ?: return
        val window = activity.window
        val attrs = window.attributes
        val height = host.height
        if (height <= 0) return
        val base = if (attrs.screenBrightness < 0f) 0.5f else attrs.screenBrightness
        var target = base - totalDeltaY * verticalSensitivity / height
        if (target < 0f) target = 0f
        if (target > 1f) target = 1f
        attrs.screenBrightness = target
        window.attributes = attrs
        host.showSlideHint(host.context.getString(R.string.player_gesture_percent, (target * 100).toInt()), brightness = true)
    }

    override fun onVolumeSlide(totalDeltaY: Float) {
        if (host.isLocked) return
        val am = audioManager ?: return
        val height = host.height
        if (height <= 0) return
        val streamMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (streamMax <= 0) return
        val base = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        var index = base - totalDeltaY * verticalSensitivity / height * streamMax
        if (index > streamMax) index = streamMax.toFloat()
        if (index < 0f) index = 0f
        am.setStreamVolume(AudioManager.STREAM_MUSIC, index.toInt(), 0)
        host.showSlideHint(host.context.getString(R.string.player_gesture_percent, (index / streamMax * 100).toInt()), brightness = false)
    }
}
