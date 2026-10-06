package com.github.tvbox.osc.player.state

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStateMachineTest {

    @Test
    fun prepareSequence_reachesPlaying() {
        val machine = PlaybackStateMachine()
        assertEquals(PlayState.IDLE, machine.currentState)

        machine.onPrepareRequested()
        assertEquals(PlayState.PREPARING, machine.currentState)

        machine.onPrepared()
        assertEquals(PlayState.PREPARED, machine.currentState)

        machine.onRenderingStart()
        assertEquals(PlayState.PLAYING, machine.currentState)
    }

    @Test
    fun pauseAndResume() {
        val machine = freshPlayingMachine()

        machine.onPauseRequested()
        assertEquals(PlayState.PAUSED, machine.currentState)

        machine.onPlayRequested()
        assertEquals(PlayState.PLAYING, machine.currentState)
    }

    @Test
    fun bufferingPair() {
        val machine = freshPlayingMachine()

        machine.onBufferingStart()
        assertEquals(PlayState.BUFFERING, machine.currentState)

        machine.onBufferingEnd()
        assertEquals(PlayState.BUFFERED, machine.currentState)
    }

    @Test
    fun completionAndError() {
        val machine = freshPlayingMachine()

        machine.onCompletion()
        assertEquals(PlayState.COMPLETED, machine.currentState)

        machine.onError()
        assertEquals(PlayState.ERROR, machine.currentState)
    }

    @Test
    fun stopAndResetGoIdle() {
        val machine = freshPlayingMachine()

        machine.onStopRequested()
        assertEquals(PlayState.IDLE, machine.currentState)

        machine.onPrepareRequested()
        machine.onReset()
        assertEquals(PlayState.IDLE, machine.currentState)
    }

    @Test
    fun pausedBeforeSeek_keepsPausedOnBufferingAndRenderingCallbacks() {
        val machine = freshPlayingMachine()

        machine.onPauseRequested()
        assertEquals(PlayState.PAUSED, machine.currentState)

        machine.onSeekWhilePaused()
        machine.onBufferingStart()
        assertEquals(PlayState.PAUSED, machine.currentState)

        machine.onBufferingEnd()
        assertEquals(PlayState.PAUSED, machine.currentState)

        machine.onRenderingStart()
        assertEquals(PlayState.PAUSED, machine.currentState)
    }

    @Test
    fun playRequestClearsPauseMemory() {
        val machine = freshPlayingMachine()

        machine.onPauseRequested()
        machine.onSeekWhilePaused()
        machine.onPlayRequested()
        assertEquals(PlayState.PLAYING, machine.currentState)

        machine.onBufferingStart()
        assertEquals(PlayState.BUFFERING, machine.currentState)
    }

    @Test
    fun contentReplacedClearsPauseMemory() {
        val machine = freshPlayingMachine()

        machine.onPauseRequested()
        machine.onContentReplaced()
        machine.onPrepareRequested()
        machine.onPrepared()
        machine.onRenderingStart()
        assertEquals(PlayState.PLAYING, machine.currentState)
    }

    @Test
    fun forwardMigrationFromPlaying_toPausedViaSeekCallback() {
        val machine = freshPlayingMachine()

        machine.onPauseRequested()
        machine.onSeekWhilePaused()
        machine.onPrepareRequested()
        assertEquals(PlayState.PREPARING, machine.currentState)
    }

    @Test
    fun stateFlowExposesCurrentValue() {
        val machine = PlaybackStateMachine()

        machine.onPrepareRequested()
        machine.onPrepared()

        assertEquals(PlayState.PREPARED, machine.state.value)
    }

    private fun freshPlayingMachine(): PlaybackStateMachine {
        val machine = PlaybackStateMachine()
        machine.onPrepareRequested()
        machine.onPrepared()
        machine.onRenderingStart()
        assertEquals(PlayState.PLAYING, machine.currentState)
        return machine
    }
}
