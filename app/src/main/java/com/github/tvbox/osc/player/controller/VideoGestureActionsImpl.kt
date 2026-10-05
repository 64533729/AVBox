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
import com.github.tvbox.osc.util.LOG
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
 * ① 横滑**只出提示文字,不驱动底部进度条**(照旧 `GestureController`;曾误设 `dragging` +
 *    `seekPreviewPositionMs`,真机上表现为进度条白球缩放并整条左移);
 * ② 亮度/音量基准改为**每个手势会话现取**(旧实现只在 `onDown` 取,同一会话内取一次;
 *   这里 [beginSession] 每次 DOWN 都会重取,语义与旧实现一致而不再跨会话串味);
 * ③ 长按倍速的恢复**不依赖 ACTION_UP**:接线层在长按计时结束时就会调 [onLongPressEnd],
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
     * 竖滑定标:**滑过多少个"手势区高度"才走完全量程**。
     *
     * <p>旧实现是 `deltaY * 2 / height` ⇒ 半屏就到底;真机反馈"轻轻一划就到 100%,跟闪光弹一样"。
     * 现取 2.5 ⇒ 一屏位移只走 **40%**,要两屏半才到顶/到底,微调余量充足。
     */
    private val verticalRangePerScreen = 2.5f

    /**
     * 屏幕顶端"系统手势保留带"占手势区高度的比例。
     *
     * <p>系统拉下通知栏的手势只从最顶端起手;15% 在 1080×2400 上约 360px,足够覆盖
     * 状态栏/挖孔区域,又不会吃掉正常的画面中部竖滑。
     */
    private val topBandFraction = 0.15f

    /** 本次手势的横滑目标(-1 = 无) */
    private var seekTargetMs = -1

    /**
     * 手势开始时的亮度基准。
     *
     * <p>⚠️ **必须固定**,不能每次 MOVE 都读当前值:接线层送来的 `totalDeltaY` 是**从按下点累计**的,
     * 若基准取"当前值",同一个位移会被每一个 MOVE 反复叠加 —— 真机实测:185px 的滑动、
     * 13 次 MOVE,把亮度从 0.80 直接推到 0(即"轻轻一划就到底/跟闪光弹一样")。
     */
    private var brightnessBase: Float? = null

    /** 手势开始时的音量基准(同样必须固定,理由同上) */
    private var volumeBase: Int? = null

    private val audioManager: AudioManager? by lazy {
        host.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    override fun inPlayback(): Boolean = host.isInPlaybackState()

    /**
     * 组装本次会话快照。**宽高与边缘带每次现算**,不用组合期常量
     * (旧草稿把屏幕几何冻结在组合期,旋转后不重算 —— 复核中危项 ⑥)。
     */
    fun beginSession(width: Int, height: Int, screenWidth: Int, downY: Float): VideoGestureSession {
        seekTargetMs = -1
        // 基准在手势开始时取一次,整场手势复用(见字段注释)
        brightnessBase = host.playerActivity()?.window?.attributes?.screenBrightness?.let {
            if (it < 0f) 0.5f else it
        }
        volumeBase = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
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
            // 系统只把屏幕最顶端留给"下拉通知栏":从那一带起手的竖滑整段不参与亮度/音量,
            // 否则我们会在系统接管之前先把数值改掉(真机反馈 ④ 的根因)
            fromTopBand = downY < height * topBandFraction,
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
        if (target < 0) return
        host.seekToFromGesture(target.toLong())
        // 显式落盘:暂停态等不到下一跳,播放态也不该等到下一跳才更新历史页
        host.saveGestureProgress(target)
    }

    override fun onSeekCancel() {
        seekTargetMs = -1
    }

    override fun onBrightnessSlide(totalDeltaY: Float) {
        if (host.isLocked) return
        val activity = host.playerActivity() ?: return
        val window = activity.window
        val attrs = window.attributes
        val height = host.height
        if (height <= 0) return
        val base = brightnessBase ?: return
        // 下降 = 变暗、上升 = 变亮;按 verticalRangePerScreen 定标。
        // base 是**手势开始时的固定值**,totalDeltaY 是从按下点累计的位移 ⇒ 两者相加才正确。
        val delta = -totalDeltaY / (height * verticalRangePerScreen)
        var target = base + delta
        if (target < 0f) target = 0f
        if (target > 1f) target = 1f
        attrs.screenBrightness = target
        window.attributes = attrs
        LOG.i(
            "echo-slide: kind=brightness dy=" + totalDeltaY + " h=" + height +
                " base=" + base + " target=" + target,
        )
        host.showSlideHint(host.context.getString(R.string.player_gesture_percent, (target * 100).toInt()), brightness = true)
    }

    override fun onVolumeSlide(totalDeltaY: Float) {
        if (host.isLocked) return
        val am = audioManager ?: return
        val height = host.height
        if (height <= 0) return
        val streamMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (streamMax <= 0) return
        // 基准同样是手势开始时的固定值(理由见 brightnessBase 注释)
        val base = volumeBase ?: return
        // 与亮度同一把尺子:全量程(0..max)对应 verticalRangePerScreen 个屏高
        val delta = -totalDeltaY / (height * verticalRangePerScreen) * streamMax
        var index = base + delta
        if (index > streamMax) index = streamMax.toFloat()
        if (index < 0f) index = 0f
        am.setStreamVolume(AudioManager.STREAM_MUSIC, index.toInt(), 0)
        LOG.i(
            "echo-slide: kind=volume dy=" + totalDeltaY + " h=" + height +
                " base=" + base + " max=" + streamMax + " target=" + index,
        )
        host.showSlideHint(host.context.getString(R.string.player_gesture_percent, (index / streamMax * 100).toInt()), brightness = false)
    }
}
