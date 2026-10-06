package com.github.tvbox.osc.player

class PlaybackAttemptState {

    @JvmField
    var allowSwitchPlayer: Boolean = true

    @JvmField
    var hasAutoSwitchedPlayer: Boolean = false

    @JvmField
    var autoSwitchedPlayerType: Int = -1

    @JvmField
    var hasAutoSwitchedDecode: Boolean = false

    @JvmField
    var autoSwitchedDecodeOld: String? = null

    @JvmField
    var autoSwitchedDecodeKey: String = "exo"

    @JvmField
    var hasRetriedAfterStart: Boolean = false

    @JvmField
    var usedPreloadedResult: Boolean = false

    @JvmField
    var hasRetriedSameUrlOnBoot: Boolean = false

    @JvmField
    var playbackStarted: Boolean = false

    @JvmField
    var playTimeoutBasePosition: Long = 0

    @JvmField
    val triedLineFlags: MutableSet<String> = HashSet()

    @JvmField
    var lastRetryTime: Long = 0

    @JvmField
    var userPickedLine: Boolean = false

    @JvmField
    var allowAutoSwitchLine: Boolean = true

    enum class SwitchIntent { NONE, REUSE, REBUILD }

    @JvmField
    var switchIntent: SwitchIntent = SwitchIntent.NONE

    @JvmField
    var switchStopPending: Boolean = false

    @JvmField
    var pendingInheritKey: String? = null

    @JvmField
    var pendingInheritProgress: Long = 0

    @JvmField
    var switchingPlayback: Boolean = false

    @JvmField
    var audioPlayback: Boolean = false

    @JvmField
    var audioOnlyConfirmed: Boolean = false

    fun beginSession() {
        playbackStarted = false
        switchStopPending = false
        autoSwitchedDecodeOld = null
    }

    fun beginNewPlay() {
        playbackStarted = false
        playTimeoutBasePosition = 0
        allowSwitchPlayer = true
        hasAutoSwitchedPlayer = false
        hasAutoSwitchedDecode = false
        hasRetriedAfterStart = false
        hasRetriedSameUrlOnBoot = false
        usedPreloadedResult = false
    }

    fun userSelfRescue() {
        hasAutoSwitchedPlayer = false
        hasRetriedAfterStart = false
        hasRetriedSameUrlOnBoot = false
    }

    fun stoppedForSourceSwitch() {
        playbackStarted = false
        playTimeoutBasePosition = 0
        toggleReuseIntent(false, false)
        switchStopPending = true
    }

    fun resetAutoRetryLadder() {
        allowSwitchPlayer = true
        hasAutoSwitchedPlayer = false
        hasRetriedSameUrlOnBoot = false
        clearTriedLines()
    }

    fun onLineSwitched() {
        allowSwitchPlayer = true
        hasAutoSwitchedPlayer = false
        if (switchIntent != SwitchIntent.REBUILD) switchIntent = SwitchIntent.REUSE
    }

    fun linesExhausted() {
        clearTriedLines()
    }

    fun clearTriedLines() {
        triedLineFlags.clear()
    }

    fun setReuseIntent(reuse: Boolean) {
        if (reuse) {
            if (switchIntent == SwitchIntent.NONE) switchIntent = SwitchIntent.REUSE
        } else if (switchIntent == SwitchIntent.REUSE) {
            switchIntent = SwitchIntent.NONE
        }
    }

    fun setReleaseIntent(release: Boolean) {
        if (release) {
            switchIntent = SwitchIntent.REBUILD
        } else if (switchIntent == SwitchIntent.REBUILD) {
            switchIntent = SwitchIntent.NONE
        }
    }

    fun toggleReuseIntent(reuse: Boolean, release: Boolean) {
        switchIntent = if (release) SwitchIntent.REBUILD else if (reuse) SwitchIntent.REUSE else SwitchIntent.NONE
    }

    fun consumeReuseIntent(): Boolean {
        val reuse = switchIntent == SwitchIntent.REUSE
        switchIntent = SwitchIntent.NONE
        return reuse
    }

    fun clearSessionFlags() {
        switchingPlayback = false
        audioPlayback = false
    }
}
