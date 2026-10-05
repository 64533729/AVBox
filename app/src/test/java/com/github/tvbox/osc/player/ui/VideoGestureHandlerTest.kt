package com.github.tvbox.osc.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势状态机的行为用例(纯 JVM,无 Android 依赖)。
 *
 * <p>每条用例对应一个**用户可见的手势需求**,而不是实现细节;命名即需求。
 */
class VideoGestureHandlerTest {

    /** 记录派发了哪些动作,便于断言"恰好一次/一次都没有" */
    private class Recorder : VideoGestureActions {
        val calls = mutableListOf<String>()
        var playback = true

        override fun inPlayback(): Boolean = playback
        override fun onSingleTap() { calls += "singleTap" }
        override fun onDoubleTapTogglePlay() { calls += "doubleTap" }
        override fun onLongPressStart() { calls += "longPressStart" }
        override fun onLongPressEnd() { calls += "longPressEnd" }
        override fun onSeekPreview(totalDeltaX: Float) { calls += "seekPreview:$totalDeltaX" }
        override fun onSeekCommit() { calls += "seekCommit" }
        override fun onSeekCancel() { calls += "seekCancel" }
        override fun onBrightnessSlide(totalDeltaY: Float) { calls += "brightness:$totalDeltaY" }
        override fun onVolumeSlide(totalDeltaY: Float) { calls += "volume:$totalDeltaY" }
    }

    private fun session(
        inPlayback: Boolean = true,
        canChangePosition: Boolean = true,
        enableInNormal: Boolean = true,
        fullScreen: Boolean = true,
        locked: Boolean = false,
        previewMode: Boolean = false,
        paused: Boolean = false,
        gestureEnabled: Boolean = true,
        verticalSlidingDisabled: Boolean = false,
        width: Int = 1000,
        height: Int = 600,
        screenWidth: Int = 1000,
        edge: Boolean = false,
    ) = VideoGestureSession(
        inPlayback, canChangePosition, enableInNormal, fullScreen, locked, previewMode,
        paused, gestureEnabled, verticalSlidingDisabled, width, height, screenWidth, edge,
    )

    // ---------- 单击 / 双击 ----------

    /** 点一下画面:等过双击窗口后,恰好派发一次单击 */
    @Test
    fun singleTapFiresExactlyOnceAfterDoubleTapWindow() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        assertEquals(
            "抬手应被记为一笔待定点击",
            VideoGestureHandler.EndResult.TAP_PENDING,
            h.endSession(cancelled = false, nowMs = 1000L),
        )
        assertEquals("此时还不该派发单击(要先等第二下)", emptyList<String>(), r.calls)
        h.markSingleTapConfirmed()
        assertEquals(listOf("singleTap"), r.calls)
    }

    /** 快速双击:第二下判为双击,**不得**再派发单击 */
    @Test
    fun doubleTapTogglesPlayAndSuppressesSingleTap() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        h.endSession(cancelled = false, nowMs = 1000L)
        // 第二下在窗口内(间隔 120ms,落在 [40, 300])
        h.beginSession(session(), 505f, 302f)
        assertEquals(
            "第二下应判为双击",
            VideoGestureHandler.EndResult.DOUBLE_TAP,
            h.endSession(cancelled = false, nowMs = 1120L),
        )
        assertEquals(listOf("doubleTap"), r.calls)
        // 且双击基准被清掉,不会连着再判一次双击
        assertFalse(h.withinDoubleTapWindow(1120L))
    }

    /** 第二下超出窗口:不得判双击 */
    @Test
    fun secondTapOutsideWindowIsNotDoubleTap() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        h.endSession(cancelled = false, nowMs = 1000L)
        h.beginSession(session(), 500f, 300f)
        h.endSession(cancelled = false, nowMs = 1400L) // 400ms > 300ms 窗口
        assertEquals("超窗不该有双击", emptyList<String>(), r.calls)
    }

    // ---------- 长按 ----------

    /** 长按到点:派发倍速开始;抬手恢复 */
    @Test
    fun longPressBoostsAndRestoresOnRelease() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        assertTrue("静止不动应能触发长按", h.maybeLongPress())
        h.endSession(cancelled = false, nowMs = 1500L)
        assertEquals(listOf("longPressStart", "longPressEnd"), r.calls)
    }

    /** 已滑动:长按不得触发(滑动与长按互斥) */
    @Test
    fun longPressDoesNotFireAfterMovement() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        h.onMove(600f, 300f, slop = 8f)
        assertFalse("动过了就不该长按", h.maybeLongPress())
        assertTrue("没有 longPressStart", r.calls.none { it.startsWith("longPress") })
    }

    /** 暂停态:旧实现显式排除,不得提速 */
    @Test
    fun longPressIgnoredWhilePaused() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(paused = true), 500f, 300f)
        assertFalse("暂停态不该提速", h.maybeLongPress())
        assertTrue(r.calls.isEmpty())
    }

    /** 长按中 CANCEL:倍速也必须恢复 */
    @Test
    fun longPressRestoredEvenWhenCancelled() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        h.maybeLongPress()
        h.endSession(cancelled = true, nowMs = 1500L)
        assertEquals(listOf("longPressStart", "longPressEnd"), r.calls)
    }

    // ---------- 横滑 seek ----------

    /** 右滑 = 前进,deltaX 为正;抬手提交 */
    @Test
    fun horizontalDragPreviewThenCommit() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        assertTrue(h.onMove(600f, 300f, slop = 8f))
        h.endSession(cancelled = false, nowMs = 1200L)
        assertEquals(listOf("seekPreview:100.0", "seekCommit"), r.calls)
    }

    /** CANCEL:横滑必须走取消,**不得提交**(复核高危项 ④) */
    @Test
    fun cancelledSeekIsNotCommitted() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        h.onMove(600f, 300f, slop = 8f)
        h.endSession(cancelled = true, nowMs = 1200L)
        assertEquals(listOf("seekPreview:100.0", "seekCancel"), r.calls)
        assertTrue("绝不能提交", r.calls.none { it == "seekCommit" })
    }

    /** 不允许改进度:横滑被拒 */
    @Test
    fun horizontalDragRejectedWhenPositionChangeDisabled() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(canChangePosition = false), 500f, 300f)
        assertFalse(h.onMove(600f, 300f, slop = 8f))
        h.endSession(cancelled = false, nowMs = 1200L)
        assertTrue("不该有 seek", r.calls.none { it.startsWith("seek") })
    }

    // ---------- 竖滑亮度/音量 ----------

    /** 左半屏下滑 = 亮度;deltaY 为正 */
    @Test
    fun leftHalfVerticalDragAdjustsBrightness() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 200f, 300f) // x=200 < 500
        assertTrue(h.onMove(200f, 400f, slop = 8f))
        assertEquals(listOf("brightness:100.0"), r.calls)
    }

    /** 右半屏下滑 = 音量 */
    @Test
    fun rightHalfVerticalDragAdjustsVolume() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 800f, 300f) // x=800 >= 500
        assertTrue(h.onMove(800f, 400f, slop = 8f))
        assertEquals(listOf("volume:100.0"), r.calls)
    }

    /** 普通态(非全屏)未开 enableInNormal:竖滑被拒 */
    @Test
    fun verticalDragRejectedInNormalStateWhenDisabled() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(fullScreen = false, enableInNormal = false), 200f, 300f)
        assertFalse(h.onMove(200f, 400f, slop = 8f))
        assertTrue(r.calls.isEmpty())
    }

    /** "禁用手势控制":只拦竖滑,**不拦横滑** */
    @Test
    fun verticalSlidingDisabledBlocksOnlyVertical() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(verticalSlidingDisabled = true), 200f, 300f)
        assertFalse("竖滑应被拦", h.onMove(200f, 400f, slop = 8f))
        h.endSession(cancelled = false, nowMs = 1200L)

        val r2 = Recorder()
        val h2 = VideoGestureHandler(r2)
        h2.beginSession(session(verticalSlidingDisabled = true), 200f, 300f)
        assertTrue("横滑仍应放行", h2.onMove(320f, 300f, slop = 8f))
        assertTrue(r2.calls.any { it.startsWith("seekPreview") })
    }

    /** 预览态:竖滑不响应 */
    @Test
    fun previewModeRejectsVerticalDrag() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(previewMode = true), 200f, 300f)
        assertFalse(h.onMove(200f, 400f, slop = 8f))
        assertTrue(r.calls.isEmpty())
    }

    // ---------- 归属判定(不归手势管就不该有任何副作用) ----------

    /** 非播放态:不认领 */
    @Test
    fun notInPlaybackIsIgnored() {
        val r = Recorder()
        r.playback = false
        val h = VideoGestureHandler(r)
        assertEquals(GestureVerdict.IGNORE, h.beginSession(session(inPlayback = false), 500f, 300f))
    }

    /** 四边边缘带:不认领 */
    @Test
    fun edgeBandIsIgnored() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        assertEquals(GestureVerdict.IGNORE, h.beginSession(session(edge = true), 2f, 300f))
    }

    /** 锁屏:认领(吞掉事件),抬手唤锁屏钮 */
    @Test
    fun lockedSessionClaimsAndCallsSingleTapOnRelease() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        assertEquals(GestureVerdict.CLAIMED, h.beginSession(session(locked = true), 500f, 300f))
        h.endSession(cancelled = false, nowMs = 1000L)
        assertEquals(listOf("singleTap"), r.calls)
    }

    /** 锁屏横滑:不得 seek */
    @Test
    fun lockedSessionDoesNotSeek() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(locked = true), 500f, 300f)
        assertFalse(h.onMove(700f, 300f, slop = 8f))
        h.endSession(cancelled = false, nowMs = 1200L)
        assertTrue(r.calls.none { it.startsWith("seek") })
    }

    /** 小于 slop 的抖动不算滑动,也不取消点击 */
    @Test
    fun jitterBelowSlopStaysATap() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        assertFalse("未越 slop", h.onMove(503f, 302f, slop = 8f))
        assertEquals(
            VideoGestureHandler.EndResult.TAP_PENDING,
            h.endSession(cancelled = false, nowMs = 1000L),
        )
        h.markSingleTapConfirmed()
        assertEquals(listOf("singleTap"), r.calls)
    }

    // ---------- 真机反馈回归(2026-10-06) ----------

    /**
     * 真机反馈 ③:横滑到一半下拉通知栏,旧接线层会把系统中断当成正常抬手 ⇒ 提交 seek 或转去调音量。
     * 状态机侧必须:被标为 cancelled 的横滑**只取消、不提交**。
     */
    @Test
    fun systemInterruptedSeekOnlyCancels() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        h.onMove(500f, 300f, slop = 8f)
        h.endSession(cancelled = true, nowMs = 1200L)
        assertEquals(listOf("seekPreview:200.0", "seekCancel"), r.calls)
        assertTrue("中断绝不能提交 seek", r.calls.none { it == "seekCommit" })
        assertTrue("中断绝不能转成竖滑", r.calls.none { it.startsWith("volume") || it.startsWith("brightness") })
    }

    /** 长按态被系统中断:倍速必须恢复,且不该被当成点击 */
    @Test
    fun systemInterruptDuringLongPressRestoresSpeedOnly() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        h.maybeLongPress()
        h.endSession(cancelled = true, nowMs = 1500L)
        assertEquals(listOf("longPressStart", "longPressEnd"), r.calls)
    }

    /** 单击在双击窗口内不应被确认(宿主定时器据此判"是否已被双击消化") */
    @Test
    fun pendingTapIsStillInsideDoubleTapWindowShortlyAfter() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        h.endSession(cancelled = false, nowMs = 1000L)
        assertTrue("刚抬手时仍在窗口内(不能立刻发单击)", h.withinDoubleTapWindow(1100L))
        assertFalse("超过窗口后不再是双击候选", h.withinDoubleTapWindow(1400L))
    }

    // ---------- 真机反馈 ②(2026-10-06):双击不得遗留一次单击 ----------

    /**
     * 真机现象:点一下暂停后,控制条再也收不回去。
     *
     * <p>根因是双击的第二下**也走了单击路径**(以及第一下的单击确认定时器在双击之后才补发),
     * 于是同一个手势序列里"显隐"与"播放暂停"互相踩。这里钉住:判成双击后,
     * 待定标记必须被清掉,宿主再调 [VideoGestureHandler.markSingleTapConfirmed] 也**不能再派发**。
     */
    @Test
    fun doubleTapLeavesNoStraySingleTap() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        // 第一下
        h.beginSession(session(), 300f, 300f)
        assertEquals(
            VideoGestureHandler.EndResult.TAP_PENDING,
            h.endSession(cancelled = false, nowMs = 1000L),
        )
        assertTrue("第一下后应存在待定单击", h.tapPending)
        // 第二下(窗口内)⇒ 双击
        h.beginSession(session(), 300f, 300f)
        assertEquals(
            VideoGestureHandler.EndResult.DOUBLE_TAP,
            h.endSession(cancelled = false, nowMs = 1120L),
        )
        assertFalse("判成双击后不得再留待定单击", h.tapPending)
        // 宿主(控制器)随后仍会调一次确认 ⇒ 必须是空操作
        assertFalse(
            "双击之后补发的单击确认必须无效",
            h.markSingleTapConfirmed(),
        )
        assertEquals("整个序列只应有双击", listOf("doubleTap"), r.calls)
    }

    /** 纯单击:宿主确认后恰好一次 */
    @Test
    fun singleTapConfirmedOnceAndIdempotent() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        h.endSession(cancelled = false, nowMs = 1000L)
        assertTrue(h.markSingleTapConfirmed())
        assertFalse("重复确认必须无效", h.markSingleTapConfirmed())
        assertEquals(listOf("singleTap"), r.calls)
    }

    /** 锁屏是立即派发,不该再留下待定(否则会多唤一次锁屏钮) */
    @Test
    fun lockedTapDispatchesImmediatelyWithoutPending() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(locked = true), 300f, 300f)
        h.endSession(cancelled = false, nowMs = 1000L)
        assertFalse("锁屏点击应立即派发,不留待定", h.tapPending)
        assertFalse("确认必须是空操作", h.markSingleTapConfirmed())
        assertEquals(listOf("singleTap"), r.calls)
    }

    // ---------- 真机反馈 ③(2026-10-06):长按倍速期间位移不得改成滑动 ----------

    /**
     * 真机现象:长按画面倍速时,手指只要有轻微移动就会变成调进度或调音量。
     *
     * <p>正确逻辑:长按是一次**独占**会话 —— 直到抬手为止,任何位移都不该进入 seek/亮度/音量。
     */
    @Test
    fun longPressOwnsTheSessionUntilRelease() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        assertTrue(h.maybeLongPress())
        // 长按成立后的各种位移方向:都不该产生任何滑动动作
        assertFalse("横移不该 seek", h.onMove(600f, 300f, slop = 8f))
        assertFalse("竖移不该调亮度", h.onMove(300f, 500f, slop = 8f))
        assertFalse("右半屏竖移不该调音量", h.onMove(800f, 500f, slop = 8f))
        assertEquals("长按期间根本不该选出滑动模式", VideoGestureHandler.Mode.UNDECIDED, h.currentMode)
        h.endSession(cancelled = false, nowMs = 1500L)
        assertEquals(
            "整个会话只应有倍速开始/恢复,不得夹带滑动动作",
            listOf("longPressStart", "longPressEnd"),
            r.calls,
        )
    }

    /** 长按 + 位移后抬手:倍速必须恢复(不能因为位移被当成滑动而丢掉恢复) */
    @Test
    fun longPressStillRestoresAfterMovement() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        h.maybeLongPress()
        h.onMove(700f, 400f, slop = 8f)
        h.endSession(cancelled = false, nowMs = 1500L)
        assertEquals(listOf("longPressStart", "longPressEnd"), r.calls)
    }

    /** 长按恢复后再抬手:不应被判成单击(不会多显隐一次控制条) */
    @Test
    fun longPressDoesNotBecomeATap() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        h.maybeLongPress()
        h.endSession(cancelled = false, nowMs = 1500L)
        assertFalse("长按不该留下待定单击", h.tapPending)
        assertFalse(h.markSingleTapConfirmed())
        assertTrue(r.calls.none { it == "singleTap" })
    }

    // ---------- 真机反馈 ④(2026-10-06):系统手势不得触发亮度/音量 ----------

    /**
     * 真机现象:下拉通知栏 / 上滑退出应用会触发亮度或音量。
     *
     * <p>系统手势抢走触摸前会先送来少量 MOVE,若竖滑"一动就生效",等在系统取消时数值已被改。
     * 这里钉住:纵向位移未越过起判阈值(默认 height 的 12%)时**不得**产生任何亮度/音量动作。
     */
    @Test
    fun smallVerticalMoveBelowCommitThresholdDoesNothing() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        // 12% of 600 = 72px;走 40px(系统手势的典型抖动量级)
        assertFalse(h.onMove(300f, 340f, slop = 8f))
        assertFalse(h.onMove(300f, 360f, slop = 8f))
        assertTrue("未越阈值不该改亮度", r.calls.none { it.startsWith("brightness") })
        assertTrue("未越阈值不该改音量", r.calls.none { it.startsWith("volume") })
    }

    /** 越过阈值后才真正开始调 */
    @Test
    fun verticalMoveBeyondCommitThresholdStartsAdjusting() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        assertTrue(h.onMove(300f, 450f, slop = 8f)) // 150px > 72px
        assertTrue(r.calls.any { it.startsWith("brightness") })
    }

    /** 起判阈值只管竖滑:横滑仍按 slop 立即响应 */
    @Test
    fun horizontalMoveBelowCommitThresholdStillSeeks() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        assertTrue("横滑不该被竖滑阈值牵制", h.onMove(340f, 305f, slop = 8f))
        assertTrue(r.calls.any { it.startsWith("seekPreview") })
    }
}
