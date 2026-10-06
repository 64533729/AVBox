package com.github.tvbox.osc.ui.activity

sealed interface PlaybackCommand {

    data object StopForContentSwitch : PlaybackCommand

    data class StopForSourceSwitch(val tip: String) : PlaybackCommand

    data object ClearSourceSwitchTip : PlaybackCommand

    data class SetEpisodeSheetOpen(val open: Boolean) : PlaybackCommand

    data class SelectQuality(val position: Int) : PlaybackCommand
}

data class DetailPlaybackFacts(
    val landscape: Boolean,
    val portraitVideo: Boolean,
)

internal object DetailPlaybackCommands {

    fun fullScreenState(
        requested: Boolean,
        facts: DetailPlaybackFacts,
    ): Pair<Boolean, Boolean> {
        val landscapeTarget = requested && !facts.portraitVideo
        return requested to (landscapeTarget != facts.landscape)
    }}
