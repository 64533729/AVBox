package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerHelperTest {

    @Test
    fun displaySpeed_usesByteUnitsWithBinaryBase() {
        assertEquals("512B/s", PlayerHelper.getDisplaySpeed(512L, true))
        assertEquals("900KB/s", PlayerHelper.getDisplaySpeed(900L * 1024, true))
        assertTrue(PlayerHelper.getDisplaySpeed(3L * 1024 * 1024, true).endsWith("MB/s"))
    }

    @Test
    fun displaySpeed_zeroTextFollowsShowFlag() {
        assertEquals("0B/s", PlayerHelper.getDisplaySpeed(0L, true))
        assertEquals("", PlayerHelper.getDisplaySpeed(0L, false))
    }

    @Test
    fun exoDecodeApplied_onlySoftValueMatchesAppliedFlag() {
        assertTrue(PlayerHelper.isExoDecodeApplied("软解码", true))
        assertTrue(PlayerHelper.isExoDecodeApplied("硬解码", false))
        assertTrue(PlayerHelper.isExoDecodeApplied("", false))
        assertTrue(PlayerHelper.isExoDecodeApplied(null, false))
        assertFalse(PlayerHelper.isExoDecodeApplied("软解码", false))
        assertFalse(PlayerHelper.isExoDecodeApplied("硬解码", true))
        assertFalse(PlayerHelper.isExoDecodeApplied(null, true))
    }
}
