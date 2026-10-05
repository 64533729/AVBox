package com.github.tvbox.osc.player.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 新栈播放状态(M7b):承接旧 doikki `VideoView.STATE_*` 的读取面,不沿用旧常量名。
 * 迁移语义逐条对齐旧 `VideoView`(起播=PREPARING、首帧=PLAYING、缓冲对、完成/错误等),
 * M7c 起由调度层直读本状态机替换 `PlaybackViewBridge.currentPlayState()`。
 */
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

    /** 旧 `VideoView.STATE_START_ABORT`:移动网络提示中止起播;新栈由调度/UI 闸门决定,状态机只保留读取面 */
    START_ABORT,
    ;

    companion object {

        /**
         * 旧 doikki `VideoView.STATE_*` → 本枚举(M7d):只用于把旧状态通知的 int 参数转成枚举比较
         * (监听回调参数、控制器转发),读值请走 `MyVideoView.playState`。
         * 注意 -1 在旧通知语义下是 `STATE_ERROR`(无播放器的 -1 只出现在读值口,不会出现在通知里)。
         */
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

/**
 * 播放状态机(M7b):输入 = 桥转发的内核事件与播放命令,输出 = [state] StateFlow。
 *
 * <p>「暂停记忆」语义照搬旧 `VideoView.keepPausedStateAfterSeek`:暂停态 seek 后内核仍会发
 * 缓冲/首帧回调,这些回调不得把状态顶回在播;标记由用户播放动作(start/resume/新起播/换内容)清除。
 */
class PlaybackStateMachine {

    private val _state = MutableStateFlow(PlayState.IDLE)

    val state: StateFlow<PlayState> = _state.asStateFlow()

    val currentState: PlayState
        get() = _state.value

    private var pausedBeforeSeek = false

    /** 起播请求(旧 `startPlay` 的 setPlayState(STATE_PREPARING)) */
    fun onPrepareRequested() {
        pausedBeforeSeek = false
        _state.value = PlayState.PREPARING
    }

    /** 内核就绪(旧 `onPrepared` 的 STATE_PREPARED) */
    fun onPrepared() {
        _state.value = PlayState.PREPARED
    }

    /** 首帧渲染(旧 `onInfo(MEDIA_INFO_RENDERING_START)`) */
    fun onRenderingStart() {
        applyUnlessPausedBeforeSeek(PlayState.PLAYING)
    }

    /** 缓冲开始(旧 `onInfo(MEDIA_INFO_BUFFERING_START)`) */
    fun onBufferingStart() {
        applyUnlessPausedBeforeSeek(PlayState.BUFFERING)
    }

    /** 缓冲结束(旧 `onInfo(MEDIA_INFO_BUFFERING_END)`) */
    fun onBufferingEnd() {
        applyUnlessPausedBeforeSeek(PlayState.BUFFERED)
    }

    /** 继续播放命令(旧 `startInPlaybackState`/`resumePlay`) */
    fun onPlayRequested() {
        pausedBeforeSeek = false
        _state.value = PlayState.PLAYING
    }

    /** 暂停命令(旧 `pause`) */
    fun onPauseRequested() {
        pausedBeforeSeek = true
        _state.value = PlayState.PAUSED
    }

    /** 暂停态 seek:记下暂停记忆(旧 `mPausedBeforeSeek = true`) */
    fun onSeekWhilePaused() {
        pausedBeforeSeek = true
    }

    /** 换内容(旧 `setUrl` 清暂停记忆) */
    fun onContentReplaced() {
        pausedBeforeSeek = false
    }

    /** 播放完成(旧 `onCompletion`) */
    fun onCompletion() {
        _state.value = PlayState.COMPLETED
    }

    /** 播放错误(旧 `onError`) */
    fun onError() {
        _state.value = PlayState.ERROR
    }

    /** 停止但保留内核(旧 `stopPlaybackKeepPlayer` → STATE_IDLE) */
    fun onStopRequested() {
        pausedBeforeSeek = false
        _state.value = PlayState.IDLE
    }

    /** 移动网络提示中止起播(旧 `STATE_START_ABORT`) */
    fun onStartAborted() {
        pausedBeforeSeek = false
        _state.value = PlayState.START_ABORT
    }

    /** 内核重置/释放回初始态 */
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
