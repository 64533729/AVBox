package com.github.tvbox.osc.player.state

import org.junit.Assert.assertEquals
import org.junit.Test

/** 新栈状态读口单测(M7d):旧 doikki `VideoView.STATE_*` int → [PlayState] 的适配映射 */
class PlayStateTest {

    @Test
    fun legacyConstantsMapOneToOne() {
        assertEquals(PlayState.ERROR, PlayState.fromLegacy(-1))
        assertEquals(PlayState.IDLE, PlayState.fromLegacy(0))
        assertEquals(PlayState.PREPARING, PlayState.fromLegacy(1))
        assertEquals(PlayState.PREPARED, PlayState.fromLegacy(2))
        assertEquals(PlayState.PLAYING, PlayState.fromLegacy(3))
        assertEquals(PlayState.PAUSED, PlayState.fromLegacy(4))
        assertEquals(PlayState.COMPLETED, PlayState.fromLegacy(5))
        assertEquals(PlayState.BUFFERING, PlayState.fromLegacy(6))
        assertEquals(PlayState.BUFFERED, PlayState.fromLegacy(7))
        assertEquals(PlayState.START_ABORT, PlayState.fromLegacy(8))
    }

    @Test
    fun everyKnownLegacyValueRoundTrips() {
        // 旧数值域 -1..8 全量往返:不得有洞(漏一个值会让通知参数落进 IDLE 兜底)
        val mapped = (-1..8).map { PlayState.fromLegacy(it) }.toSet()
        assertEquals(10, mapped.size)
    }

    @Test
    fun unknownStateFallsBackToIdle() {
        // 越界值只可能来自异常上游;按"未在播"兜底,不得映射成 ERROR(会让复用判定误判坏内核)
        assertEquals(PlayState.IDLE, PlayState.fromLegacy(9))
        assertEquals(PlayState.IDLE, PlayState.fromLegacy(Int.MIN_VALUE))
    }

    @Test
    fun enumMembersArePinned() {
        // 成员表被无 else 的 when 语句消费(MusicPlayerActivity 状态监听):新增成员必须同步核对全部 when 读点
        assertEquals(
            listOf("IDLE", "PREPARING", "PREPARED", "PLAYING", "PAUSED", "COMPLETED", "BUFFERING", "BUFFERED", "ERROR", "START_ABORT"),
            PlayState.entries.map { it.name },
        )
    }
}
