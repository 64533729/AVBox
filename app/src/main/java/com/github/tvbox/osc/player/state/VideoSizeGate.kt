package com.github.tvbox.osc.player.state

class VideoSizeGate {

    companion object {
        const val UNKNOWN = "0 X 0"

        fun format(width: Int, height: Int): String = "$width X $height"
    }

    private var awaitingNewSession = false
    private var kernelContentReplaced = false
    private var priorWidth = 0
    private var priorHeight = 0

    fun onNewSession(kernelWidth: Int, kernelHeight: Int): String {
        awaitingNewSession = true
        kernelContentReplaced = false
        priorWidth = kernelWidth
        priorHeight = kernelHeight
        return UNKNOWN
    }

    fun onKernelContentReplaced() {
        kernelContentReplaced = true
    }

    fun textFor(kernelWidth: Int, kernelHeight: Int): String {
        if (isPreviousSessionValue(kernelWidth, kernelHeight)) return UNKNOWN
        awaitingNewSession = false
        return if (kernelWidth > 0 && kernelHeight > 0) format(kernelWidth, kernelHeight) else UNKNOWN
    }

    private fun isPreviousSessionValue(width: Int, height: Int): Boolean =
        awaitingNewSession && !kernelContentReplaced && width == priorWidth && height == priorHeight
}
