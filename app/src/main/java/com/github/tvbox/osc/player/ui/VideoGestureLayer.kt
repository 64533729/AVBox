package com.github.tvbox.osc.player.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 视频手势的判定状态机(纯 Kotlin、无 Compose/Android 依赖,可直接 JVM 单测)。
 *
 * <p>承接旧 `GestureController`(基于 `android.view.GestureDetector`)的语义,把**判定**与
 * **副作用**分离:指针事件由 [videoGestureLayer] 喂进来,状态机只决定"这一下算单击/双击/长按/
 * 横滑/竖滑"与"派发哪个动作";播放器副作用(控制条显隐、播放暂停、倍速、seek、亮度音量)
 * 由 [VideoGestureActions] 的宿主实现。
 *
 * <p>**两轮独立复核对旧 Compose 草稿指出的缺陷,在此逐条作为硬约束**:
 * 1. 子控件消费:接线层用 `awaitFirstDown(requireUnconsumed = true)` 取 DOWN —— 被控制条按钮/
 *    进度条消费的触摸**根本不会进来**。这是旧草稿"点按钮被处理两遍/拖进度条被二次 seek"的正解。
 * 2. 双击可达:抬手后等第二下,等到的 DOWN **不消费**,交给下一轮 `awaitEachGesture` 重新起会话,
 *    状态机按 [lastTapTime] 判定双击。旧草稿把第二下取走后直接 return,又被收尾排空 ⇒ 双击不可达。
 * 3. 单击恰好一次:只有"等到超时都没来第二下"才确认单击;判成双击时**不再**派发单击。
 * 4. ACTION_CANCEL 不当抬手:接线层识别 CANCEL 后传 `cancelled = true`,横滑走取消而**不提交**。
 * 5. 长按不依赖 MOVE:由接线层计时触发,手指完全静止也能到点;越过 slop 则取消计时。
 * 6. 屏幕几何不冻结:宽高由接线层每次 DOWN 现算后传入,不用组合期捕获的常量。
 */
interface VideoGestureActions {

    /** 目前是否可手势(起播中:播放/暂停/缓冲) */
    fun inPlayback(): Boolean

    /** 单击确认(双击窗口超时后,恰好一次) */
    fun onSingleTap()

    /** 双击 */
    fun onDoubleTapTogglePlay()

    /** 长按开始(倍速) */
    fun onLongPressStart()

    /** 长按结束(恢复倍速;CANCEL 也必须走到) */
    fun onLongPressEnd()

    /** 横滑预览:totalDeltaX = 当前 x − 按下 x(>0 = 右滑 = 前进) */
    fun onSeekPreview(totalDeltaX: Float)

    /** 横滑提交(正常抬手) */
    fun onSeekCommit()

    /** 横滑取消(ACTION_CANCEL) */
    fun onSeekCancel()

    /** 竖滑亮度:totalDeltaY = 当前 y − 按下 y */
    fun onBrightnessSlide(totalDeltaY: Float)

    /** 竖滑音量:totalDeltaY = 当前 y − 按下 y */
    fun onVolumeSlide(totalDeltaY: Float)
}

/** 一次手势会话的快照。宽高与边缘判定由接线层**每次 DOWN 现算**,避免几何被冻结。 */
data class VideoGestureSession(
    val inPlayback: Boolean,
    /** 是否允许横滑调进度(旧 setCanChangePosition) */
    val canChangePosition: Boolean,
    /** 普通态(非全屏)是否允许竖滑(旧 setEnableInNormal) */
    val enableInNormal: Boolean,
    /** 当前是否全屏 */
    val fullScreen: Boolean,
    /** 锁屏:旧实现吞掉一切手势、只在抬手唤锁屏钮 */
    val locked: Boolean,
    /** 预览态:竖滑与长按不响应,单击/双击/横滑照常 */
    val previewMode: Boolean,
    /** 是否暂停(旧实现显式排除暂停态的长按倍速) */
    val paused: Boolean,
    /** 手势总开关(关闭后只有横滑 seek 放行) */
    val gestureEnabled: Boolean,
    /** 宿主的"禁用手势控制"设置:只拦竖滑,不拦横滑 */
    val verticalSlidingDisabled: Boolean,
    val width: Int,
    val height: Int,
    /** 半屏分侧用的屏幕宽度(px) */
    val screenWidth: Int,
    /** 是否落在四边边缘带内 */
    val edge: Boolean,
)

/** DOWN 的归属判定:接线层据此决定要不要消费这次触摸 */
enum class GestureVerdict {
    /** 不归手势管:不消费、不派发任何动作,原样留给子控件/系统 */
    IGNORE,

    /** 已认领:接线层继续喂事件并消费 */
    CLAIMED,
}

/**
 * 手势状态机。**非线程安全**,只在 pointerInput 的协程里使用。
 *
 * @param doubleTapTimeoutMs 双击窗口
 * @param doubleTapMinTimeMs 两次按下的最小间隔(防抖)
 */
class VideoGestureHandler(
    private val actions: VideoGestureActions,
    val longPressTimeoutMs: Long = 500L,
    val doubleTapTimeoutMs: Long = 300L,
    val doubleTapMinTimeMs: Long = 40L,
) {

    enum class Mode { UNDECIDED, SEEK, BRIGHTNESS, VOLUME, NONE }

    private var session: VideoGestureSession? = null
    private var mode = Mode.UNDECIDED
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var moved = false
    private var longPressed = false

    /** 上一次"点击成立"的时间戳(-1 = 无) */
    var lastTapTime: Long = -1L
        private set

    /**
     * 是否存在"待定单击"(已抬手、还没等到第二下)。
     *
     * <p>⚠️ 这是**必须由状态机持有**的状态:接线层抬手即返回,由宿主的定时器在双击窗口后补发单击;
     * 若第二下先到,状态机会判成双击并清掉这个标记,宿主据此**不能再补一次单击** ——
     * 否则"双击"会连带触发一次控制条显隐(真机实测:点一下暂停后,控制条再也收不回去)。
     */
    var tapPending: Boolean = false
        private set

    /** 当前模式(接线层判断 CANCEL 时该回退还是提交) */
    val currentMode: Mode get() = mode

    /** 长按是否已触发 */
    val isLongPressing: Boolean get() = longPressed

    /** 是否已越过 slop */
    val hasMoved: Boolean get() = moved

    /**
     * DOWN:决定本次触摸归不归手势管,并记下会话。
     *
     * @return [GestureVerdict.CLAIMED] = 认领(接线层继续喂并消费);
     *         [GestureVerdict.IGNORE] = 不归手势管(原样放行)
     */
    fun beginSession(session: VideoGestureSession, x: Float, y: Float): GestureVerdict {
        this.session = session
        this.downX = x
        this.downY = y
        this.lastX = x
        this.mode = Mode.UNDECIDED
        this.moved = false
        this.longPressed = false

        // 锁屏:旧实现认领并吞掉全部事件(抬手才唤锁屏钮)
        if (session.locked) return GestureVerdict.CLAIMED
        // 非播放态不参与手势判定
        if (!session.inPlayback) return GestureVerdict.IGNORE
        // 四边边缘带:旧 PlayerUtils.isEdge 直接不响应(由接线层现算后传入)
        if (session.edge) return GestureVerdict.IGNORE
        return GestureVerdict.CLAIMED
    }

    /**
     * MOVE:更新位移并决定模式。
     *
     * @return true = 本次位移已被手势接管(接线层应 consume())
     */
    fun onMove(x: Float, y: Float, slop: Float): Boolean {
        val s = session ?: return false
        lastX = x
        val dx = x - downX
        val dy = y - downY

        if (!moved) {
            if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                moved = true
            } else {
                return false
            }
        }

        if (mode == Mode.UNDECIDED) {
            mode = decideMode(dx, dy, s)
        }
        return when (mode) {
            Mode.SEEK -> {
                actions.onSeekPreview(dx)
                true
            }
            Mode.BRIGHTNESS -> {
                actions.onBrightnessSlide(dy)
                true
            }
            Mode.VOLUME -> {
                actions.onVolumeSlide(dy)
                true
            }
            // NONE = 本次被判为不处理(总开关关闭下的竖滑、未启用竖滑的普通态)
            Mode.NONE, Mode.UNDECIDED -> false
        }
    }

    private fun decideMode(dx: Float, dy: Float, s: VideoGestureSession): Mode {
        if (s.locked) return Mode.NONE
        val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
        if (horizontal) {
            // 横滑 seek:受 setCanChangePosition 约束;总开关关闭时旧实现仍放行横滑
            return if (s.canChangePosition) Mode.SEEK else Mode.NONE
        }
        // 竖滑:预览态不响应;非全屏需 enableInNormal;"禁用手势控制"只拦竖滑
        if (s.previewMode) return Mode.NONE
        if (!s.fullScreen && !s.enableInNormal) return Mode.NONE
        if (s.verticalSlidingDisabled) return Mode.NONE
        // 右半屏音量 / 左半屏亮度(按屏幕宽度的一半分侧)
        return if (lastX >= s.screenWidth / 2f) Mode.VOLUME else Mode.BRIGHTNESS
    }

    /**
     * 长按计时到点。**由接线层的定时器调用**,不依赖 MOVE 事件。
     *
     * @return true = 已触发长按
     */
    fun maybeLongPress(): Boolean {
        val s = session ?: return false
        if (moved || longPressed) return false
        // 旧实现显式排除暂停态;预览态与锁屏也不提速
        if (s.locked || s.previewMode || s.paused) return false
        if (!s.inPlayback) return false
        longPressed = true
        actions.onLongPressStart()
        return true
    }

    /**
     * UP / CANCEL 收尾。
     *
     * @param cancelled true = ACTION_CANCEL
     * @param nowMs 当前时间(注入便于单测)
     */
    fun endSession(cancelled: Boolean, nowMs: Long): EndResult {
        val s = session ?: return EndResult.NONE
        // 先把本次模式取出来再复位:否则下面的 seek 提交流程永远看不到 SEEK
        val endedMode = mode
        session = null
        mode = Mode.UNDECIDED

        // 长按必须恢复(即便 CANCEL)
        if (longPressed) {
            longPressed = false
            tapPending = false
            actions.onLongPressEnd()
            return EndResult.NONE
        }

        if (moved) {
            tapPending = false
            if (endedMode == Mode.SEEK) {
                if (cancelled) actions.onSeekCancel() else actions.onSeekCommit()
            }
            return EndResult.NONE
        }

        // 未移动 = 点击。锁屏抬手唤锁屏钮(旧实现语义)
        if (s.locked) {
            tapPending = false
            actions.onSingleTap()
            return EndResult.NONE
        }

        // 双击判定:与上一次点击的时间差落在 [min, timeout] 内
        val last = lastTapTime
        if (last > 0 && nowMs - last in doubleTapMinTimeMs..doubleTapTimeoutMs) {
            // 判成双击 ⇒ 取消待定单击,宿主不能再补发(否则会多显隐一次控制条)
            lastTapTime = -1L
            tapPending = false
            actions.onDoubleTapTogglePlay()
            return EndResult.DOUBLE_TAP
        }
        // 单击待定:记基准 + 立标记;宿主在双击窗口后调 markSingleTapConfirmed
        lastTapTime = nowMs
        tapPending = true
        return EndResult.TAP_PENDING
    }

    /** 一次会话的收尾结果(接线层据此决定"还要不要等第二下") */
    enum class EndResult {
        /** 已派发双击 */
        DOUBLE_TAP,

        /** 已成"待定单击":接线层需等第二下,超时后调 [markSingleTapConfirmed] */
        TAP_PENDING,

        /** 长按结束 / 滑动 / 无需点击处理 */
        NONE,
    }

    /**
     * 单击确认(宿主在双击窗口后调用)。
     *
     * <p>**幂等**:若窗口内来了第二下,这里已经是双击,`tapPending` 已被清掉 ⇒ 直接返回,
     * 不会再多派一次单击。
     *
     * @return true = 确实派发了单击
     */
    fun markSingleTapConfirmed(): Boolean {
        if (!tapPending) return false
        tapPending = false
        lastTapTime = -1L
        actions.onSingleTap()
        return true
    }

    /** 是否还在双击窗口内(接线层据此决定要不要等第二下) */
    fun withinDoubleTapWindow(nowMs: Long): Boolean {
        val last = lastTapTime
        return last > 0 && nowMs - last <= doubleTapTimeoutMs
    }
}

/**
 * 把视频手势挂到覆盖层根节点上。
 *
 * <p>**三条硬要求**(违反即复现复核阻断项):
 * 1. DOWN 必须 requireUnconsumed = true —— 子控件(控制条按钮、进度条)已消费的触摸不能认领;
 * 2. 判成单击/双击时才消费 UP;等第二下期间**不消费**取到的 DOWN,否则双击不可达;
 * 3. 长按用独立定时器分支,不靠 MOVE 事件。
 *
 * @param handler 状态机(应在组合间 remember)
 * @param sessionProvider 每次 DOWN 现算会话快照;返回 null = 本次不参与
 */
fun Modifier.videoGestureLayer(
    handler: VideoGestureHandler,
    sessionProvider: (IntSize) -> VideoGestureSession?,
    /** 出现"待定单击"(已抬手、等第二下中)⇒ 宿主应在双击窗口后调 `handler.markSingleTapConfirmed()` */
    onTapPending: () -> Unit = {},
): Modifier = composed {
    var size = IntSize.Zero
    this
        .onSizeChanged { size = it }
        .pointerInput(handler) {
            awaitEachGesture {
                // 1. 子控件(控制条按钮、进度条)消费过的 DOWN 不认领 ⇒ 从不进入手势层
                val down = awaitFirstDown(requireUnconsumed = true)
                val snapshot = sessionProvider(size) ?: return@awaitEachGesture
                if (handler.beginSession(snapshot, down.position.x, down.position.y) ==
                    GestureVerdict.IGNORE
                ) {
                    return@awaitEachGesture
                }

                // 2. "抬手"与"长按到点"赛跑。
                //    长按靠这个窗口计时(手指静止也能到点);抬手通常就落在窗口内,必须一并处理。
                var firstUp = false
                var sawMove = false
                val longPressWon = withTimeoutOrNull(handler.longPressTimeoutMs) {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Final)
                        val c = e.changes.firstOrNull { it.id == down.id } ?: continue
                        if (!c.pressed) {
                            firstUp = true
                            return@withTimeoutOrNull false
                        }
                        sawMove = true
                        if (!c.isConsumed) handler.onMove(c.position.x, c.position.y, 8f)
                        if (handler.hasMoved) return@withTimeoutOrNull false
                    }
                    @Suppress("UNREACHABLE_CODE") true
                }

                if (longPressWon == null && !firstUp) {
                    // 超时 = 长按成立(maybeLongPress 内部还会挡掉暂停/预览/锁屏)
                    handler.maybeLongPress()
                }

                // 3. 等抬手(首下已抬手则跳过)
                if (!firstUp) {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Final)
                        val c = e.changes.firstOrNull { it.id == down.id } ?: continue
                        if (!c.pressed) break
                        sawMove = true
                        // 丢弃被子控件消费的位移
                        if (!c.isConsumed) {
                            if (handler.onMove(c.position.x, c.position.y, 8f)) c.consume()
                        }
                    }
                }

                // 4. CANCEL 判定:下拉通知栏/来电等系统中断时,Compose 会直接把我们还在按的指针
                //    置为 up,**全程没有 MOVE** —— 这正是与"正常抬手"的区别。
                //    旧实现(CANCEL ⇒ 不提交 seek)靠的就是这个;丢了它就会把中断当成正常抬手,
                //    于是横滑到一半下拉通知栏会被判成正常结束(实测:还会转去调音量)。
                val cancelled = !sawMove

                val result = handler.endSession(cancelled, System.currentTimeMillis())

                // 5. 单击确认:**不在这里阻塞等第二下**。
                //    若在此 awaitPointerEvent 等第二下,那一轮 awaitEachGesture 结束时的收尾会把
                //    第二下吃掉,双击永远判不出来(实测:双击播放/暂停失效)。
                //    改成"先返回 + 让状态机在下一个 DOWN 上按 lastTapTime 判双击":
                //    单击由 [onTapConfirmed] 在宿主侧用定时器补发。
                if (result == VideoGestureHandler.EndResult.TAP_PENDING) {
                    onTapPending()
                }
            }
        }
}

/** 供接线层/宿主计算边缘带(基于手势区局部坐标,左上为原点) */
internal fun isInEdgeBand(x: Float, y: Float, width: Int, height: Int, bandPx: Float): Boolean =
    x < bandPx || y < bandPx || x > width - bandPx || y > height - bandPx
