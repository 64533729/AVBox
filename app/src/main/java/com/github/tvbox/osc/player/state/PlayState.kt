package com.github.tvbox.osc.player.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlayState {
    IDLE,

    PREPARING,

    PREPARED,

    PLAYING,

    PAUSED,

    COMPLETED,

    BUFFERING,

    BUFFERED,

    ERROR,

    START_ABORT,
    ;

    companion object {

        fun fromLegacy(state: Int): PlayState = when (state) {
            -1 -> ERROR
            0 -> IDLE
            1 -> PREPARING
            2 -> PREPARED
            3 -> PLAYING
            4 -> PAUSED
            5 -> COMPLETED
            6 -> BUFFERING
            7 -> BUFFERED
            8 -> START_ABORT
            else -> IDLE
        }
    }
}

class PlaybackStateMachine {

    private val _state = MutableStateFlow(PlayState.IDLE)

    val state: StateFlow<PlayState> = _state.asStateFlow()

    val currentState: PlayState
        get() = _state.value

    private var pausedBeforeSeek = false

    fun onPrepareRequested() {
        pausedBeforeSeek = false
        _state.value = PlayState.PREPARING
    }

    fun onPrepared() {
        _state.value = PlayState.PREPARED
    }

    fun onRenderingStart() {
        applyUnlessPausedBeforeSeek(PlayState.PLAYING)
    }

    fun onBufferingStart() {
        applyUnlessPausedBeforeSeek(PlayState.BUFFERING)
    }

    fun onBufferingEnd() {
        applyUnlessPausedBeforeSeek(PlayState.BUFFERED)
    }

    fun onPlayRequested() {
        pausedBeforeSeek = false
        _state.value = PlayState.PLAYING
    }

    fun onPauseRequested() {
        pausedBeforeSeek = true
        _state.value = PlayState.PAUSED
    }

    fun onSeekWhilePaused() {
        pausedBeforeSeek = true
    }

    fun onContentReplaced() {
        pausedBeforeSeek = false
    }

    fun onCompletion() {
        _state.value = PlayState.COMPLETED
    }

    fun onError() {
        _state.value = PlayState.ERROR
    }

    fun onStopRequested() {
        pausedBeforeSeek = false
        _state.value = PlayState.IDLE
    }

    fun onStartAborted() {
        pausedBeforeSeek = false
        _state.value = PlayState.START_ABORT
    }

    fun onReset() {
        pausedBeforeSeek = false
        _state.value = PlayState.IDLE
    }

    private fun applyUnlessPausedBeforeSeek(next: PlayState) {
        if (pausedBeforeSeek) {
            if (_state.value != PlayState.PAUSED) {
                _state.value = PlayState.PAUSED
            }
            return
        }
        _state.value = next
    }
}
