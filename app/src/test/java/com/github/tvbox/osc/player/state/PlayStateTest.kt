package com.github.tvbox.osc.player.state

import org.junit.Assert.assertEquals
import org.junit.Test

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
        val mapped = (-1..8).map { PlayState.fromLegacy(it) }.toSet()
        assertEquals(10, mapped.size)
    }

    @Test
    fun unknownStateFallsBackToIdle() {
        assertEquals(PlayState.IDLE, PlayState.fromLegacy(9))
        assertEquals(PlayState.IDLE, PlayState.fromLegacy(Int.MIN_VALUE))
    }

    @Test
    fun enumMembersArePinned() {
        assertEquals(
            listOf("IDLE", "PREPARING", "PREPARED", "PLAYING", "PAUSED", "COMPLETED", "BUFFERING", "BUFFERED", "ERROR", "START_ABORT"),
            PlayState.entries.map { it.name },
        )
    }
}
