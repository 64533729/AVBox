package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailPlaybackCommandsTest {

    private fun decide(requested: Boolean, landscape: Boolean, portraitVideo: Boolean = false) =
        DetailPlaybackCommands.fullScreenState(
            requested,
            DetailPlaybackFacts(landscape = landscape, portraitVideo = portraitVideo),
        )

    @Test
    fun enterFromPortraitWithLandscapeVideoRotates() {
        val (full, rotating) = decide(requested = true, landscape = false)
        assertTrue(full)
        assertTrue(rotating)
    }

    @Test
    fun enterFromPortraitWithPortraitVideoDoesNotRotate() {
        val (full, rotating) = decide(requested = true, landscape = false, portraitVideo = true)
        assertTrue(full)
        assertFalse(rotating)
    }

    @Test
    fun enterFromLandscapeWithLandscapeVideoDoesNotRotate() {
        val (full, rotating) = decide(requested = true, landscape = true)
        assertTrue(full)
        assertFalse(rotating)
    }

    @Test
    fun enterFromLandscapeWithPortraitVideoRotatesBack() {
        val (full, rotating) = decide(requested = true, landscape = true, portraitVideo = true)
        assertTrue(full)
        assertTrue(rotating)
    }

    @Test
    fun exitFromLandscapeKeepsFullBoxUntilRotationLands() {
        val (full, rotating) = decide(requested = false, landscape = true, portraitVideo = true)
        assertFalse(full)
        assertTrue(rotating)
    }

    @Test
    fun exitFromLandscapeWithLandscapeVideoAlsoKeepsFullBox() {
        val (full, rotating) = decide(requested = false, landscape = true)
        assertFalse(full)
        assertTrue(rotating)
    }

    @Test
    fun exitFromPortraitWithLandscapeVideoDoesNotRotate() {
        val (full, rotating) = decide(requested = false, landscape = false)
        assertFalse(full)
        assertFalse(rotating)
    }

    @Test
    fun exitFromPortraitWithPortraitVideoDoesNotRotate() {
        val (full, rotating) = decide(requested = false, landscape = false, portraitVideo = true)
        assertFalse(full)
        assertFalse(rotating)
    }
}
