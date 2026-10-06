package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressTest {

    @Test
    fun stepAdvance_countsSmoothPlayback() {
        assertEquals(1_000, PlaybackProgress.stepAdvanceMs(1_000, 0))
        assertEquals(2_000, PlaybackProgress.stepAdvanceMs(3_000, 1_000))
    }

    @Test
    fun stepAdvance_ignoresStalledPosition() {
        assertEquals(0, PlaybackProgress.stepAdvanceMs(90_000, 90_000))
    }

    @Test
    fun stepAdvance_ignoresJumpAndRewind() {
        assertEquals(0, PlaybackProgress.stepAdvanceMs(90_000, 0))
        assertEquals(0, PlaybackProgress.stepAdvanceMs(0, 90_000))
    }

    @Test
    fun shouldMarkWatched_needsAccumulatedAdvance() {
        assertFalse(PlaybackProgress.shouldMarkWatched(999, "src|1#线路A#0", ""))
        assertTrue(PlaybackProgress.shouldMarkWatched(1_000, "src|1#线路A#0", ""))
    }

    @Test
    fun shouldMarkWatched_sendsOncePerEpisode() {
        assertFalse(PlaybackProgress.shouldMarkWatched(5_000, "src|1#线路A#0", "src|1#线路A#0"))
        assertTrue(PlaybackProgress.shouldMarkWatched(5_000, "src|1#线路A#1", "src|1#线路A#0"))
    }
}
