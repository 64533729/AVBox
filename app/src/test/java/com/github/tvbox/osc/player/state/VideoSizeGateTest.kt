package com.github.tvbox.osc.player.state

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSizeGateTest {

    @Test
    fun noSession_kernelValueIsShown() {
        val gate = VideoSizeGate()
        assertEquals("1920 X 1080", gate.textFor(1920, 1080))
    }

    @Test
    fun newSession_showsPlaceholderWhileKernelStillHoldsPreviousSize() {
        val gate = VideoSizeGate()
        assertEquals(VideoSizeGate.UNKNOWN, gate.onNewSession(1920, 1080))
        assertEquals(VideoSizeGate.UNKNOWN, gate.textFor(1920, 1080))
        assertEquals(VideoSizeGate.UNKNOWN, gate.textFor(1920, 1080))
    }

    @Test
    fun newSession_kernelResetShowsPlaceholder() {
        val gate = VideoSizeGate()
        gate.onNewSession(1920, 1080)
        gate.onKernelContentReplaced()
        assertEquals(VideoSizeGate.UNKNOWN, gate.textFor(0, 0))
        assertEquals("1280 X 544", gate.textFor(1280, 544))
        assertEquals("1280 X 544", gate.textFor(1280, 544))
    }

    @Test
    fun newSession_reportBeforeNextPollIsShownAtOnce() {
        val gate = VideoSizeGate()
        gate.onNewSession(1920, 1080)
        gate.onKernelContentReplaced()
        assertEquals("1280 X 544", gate.textFor(1280, 544))
    }

    @Test
    fun newSession_sameSizeAsPreviousNeedsContentReplaced() {
        val gate = VideoSizeGate()
        gate.onNewSession(1920, 1080)
        assertEquals(VideoSizeGate.UNKNOWN, gate.textFor(1920, 1080))
    }

    @Test
    fun contentReplaced_sameSizeAsPreviousIsShown() {
        val gate = VideoSizeGate()
        gate.onNewSession(1920, 1080)
        gate.onKernelContentReplaced()
        assertEquals("1920 X 1080", gate.textFor(1920, 1080))
    }

    @Test
    fun newSession_unknownKernelSnapshotTrustsNextReport() {
        val gate = VideoSizeGate()
        assertEquals(VideoSizeGate.UNKNOWN, gate.onNewSession(0, 0))
        assertEquals(VideoSizeGate.UNKNOWN, gate.textFor(0, 0))
        assertEquals("1920 X 1080", gate.textFor(1920, 1080))
    }

    @Test
    fun sessionEnded_laterSizesAreShownDirectly() {
        val gate = VideoSizeGate()
        gate.onNewSession(1920, 1080)
        gate.onKernelContentReplaced()
        gate.textFor(1280, 544)
        assertEquals("1920 X 1080", gate.textFor(1920, 1080))
        assertEquals(VideoSizeGate.UNKNOWN, gate.textFor(0, 0))
    }
}
