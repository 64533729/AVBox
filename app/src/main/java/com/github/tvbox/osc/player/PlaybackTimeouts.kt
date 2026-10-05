package com.github.tvbox.osc.player

import android.os.Handler
import android.os.Looper
import android.os.Message

/** 取流/换线/播完待撤三条定时消息的投递与撤销(超时后的判定与重试仍在宿主)。 */
class PlaybackTimeouts(private val callback: Callback) {

    interface Callback {
        fun onResolvePlayUrlTimeout()

        fun onSwitchLinePlayTimeout()

        fun onPendingCompletionDrop()
    }

    private val handler: Handler = Handler(Looper.getMainLooper(), Handler.Callback { msg: Message ->
        when (msg.what) {
            MSG_RESOLVE_PLAY_URL_TIMEOUT -> {
                callback.onResolvePlayUrlTimeout()
                true
            }

            MSG_SWITCH_LINE_PLAY_TIMEOUT -> {
                callback.onSwitchLinePlayTimeout()
                true
            }

            MSG_DROP_SESSION_AFTER_COMPLETED -> {
                callback.onPendingCompletionDrop()
                true
            }

            else -> false
        }
    })

    fun startResolvePlayUrlTimeout(timeoutMs: Long) {
        cancelPlayTimeout()
        handler.sendEmptyMessageDelayed(MSG_RESOLVE_PLAY_URL_TIMEOUT, timeoutMs)
    }

    fun startSwitchLinePlayTimeout() {
        cancelPlayTimeout()
        handler.sendEmptyMessageDelayed(MSG_SWITCH_LINE_PLAY_TIMEOUT, SWITCH_LINE_PLAY_TIMEOUT_MS)
    }

    fun cancelPlayTimeout() {
        handler.removeMessages(MSG_RESOLVE_PLAY_URL_TIMEOUT)
        handler.removeMessages(MSG_SWITCH_LINE_PLAY_TIMEOUT)
    }

    fun cancelResolvePlayUrlTimeout() {
        handler.removeMessages(MSG_RESOLVE_PLAY_URL_TIMEOUT)
    }

    fun cancelPendingCompletionDrop() {
        handler.removeMessages(MSG_DROP_SESSION_AFTER_COMPLETED)
    }

    fun armPendingCompletionDrop() {
        handler.sendEmptyMessage(MSG_DROP_SESSION_AFTER_COMPLETED)
    }

    companion object {
        @JvmField
        val RESOLVE_PLAY_URL_TIMEOUT_MS: Long = 15 * 1000L

        /** 取流超时/换线播放超时(与既有 mHandler 的三条定时消息拆开:解析超时留在页面/解析层) */
        private const val MSG_RESOLVE_PLAY_URL_TIMEOUT = 101
        private const val MSG_SWITCH_LINE_PLAY_TIMEOUT = 102

        /** 本集播完后的**延后一拍**撤会话判定(见 PlaybackController.handlePlayStateForMusicSession) */
        private const val MSG_DROP_SESSION_AFTER_COMPLETED = 103
        private const val SWITCH_LINE_PLAY_TIMEOUT_MS = 20 * 1000L
    }
}
