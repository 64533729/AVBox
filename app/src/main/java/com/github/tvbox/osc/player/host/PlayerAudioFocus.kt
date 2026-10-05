package com.github.tvbox.osc.player.host

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** 焦点受控端(新栈宿主/桥):音频焦点语义的动作出口 */
interface AudioFocusTarget {

    fun isPlaybackPlaying(): Boolean

    fun isPlaybackMuted(): Boolean

    fun startPlayback()

    fun pausePlayback()

    fun setPlaybackVolume(volume: Float)
}

/**
 * 音频焦点核心(M7b):逐条对齐旧 doikki `AudioFocusHelper` 的语义(GAIN 恢复播放/音量、
 * LOSS 与 LOSS_TRANSIENT 暂停待恢复、CAN_DUCK 降音量、静音时不请求不恢复);纯逻辑,JVM 可测。
 */
class AudioFocusActions(private val target: AudioFocusTarget) {

    private var startRequested = false

    private var pausedForLoss = false

    /** 是否持有焦点(不能用"上次焦点事件==GAIN"代替:焦点被收回后会失真,暂停后恢复将不再请求焦点) */
    var focusGranted = false
        private set

    /** 上次派发过的焦点事件(仅用于同值去重,与持有状态无关) */
    var lastFocusChange = 0
        private set

    /** 是否要派发本次焦点事件(同值去重);返回 true 时已记录事件值 */
    fun shouldDispatch(focusChange: Int): Boolean {
        if (lastFocusChange == focusChange) return false
        lastFocusChange = focusChange
        return true
    }

    fun handle(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                focusGranted = true
                // 播放中再调 start() 会重复派发 PLAYING 状态(媒体通知被无谓重发)
                if ((startRequested || pausedForLoss) && !target.isPlaybackPlaying()) {
                    target.startPlayback()
                }
                startRequested = false
                pausedForLoss = false
                if (!target.isPlaybackMuted()) {
                    target.setPlaybackVolume(1.0f)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusGranted = false
                if (target.isPlaybackPlaying()) {
                    pausedForLoss = true
                    target.pausePlayback()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (target.isPlaybackPlaying() && !target.isPlaybackMuted()) {
                    target.setPlaybackVolume(0.1f)
                }
            }
        }
    }

    /** 新一次播放开始(实例被复用):清掉上次会话遗留的"待焦点恢复"状态,防被陈旧 GAIN 自动起播 */
    fun onNewPlayback() {
        startRequested = false
        pausedForLoss = false
    }

    fun onRequestResult(granted: Boolean) {
        if (granted) {
            focusGranted = true
            return
        }
        startRequested = true
    }

    fun onAbandon() {
        // 必须复位持有状态:否则下次 requestFocus() 误判"已持有"直接返回(暂停→播放后再也不会申请焦点)
        focusGranted = false
        startRequested = false
    }
}

/**
 * 新栈音频焦点(M7b 组件):Android 壳 —— 焦点事件切主线程后交给 [AudioFocusActions]。
 *
 * <p>M7b 期间生效实现仍是旧 `VideoView` 的 `AudioFocusHelper`(宿主未换),本组件与 [AudioFocusActions]
 * 随 M7c 宿主直持后接线(登记见 `avbox-kotlin-migration-spec.md` §7.14)。
 */
class PlayerAudioFocus(context: Context, target: AudioFocusTarget) : AudioManager.OnAudioFocusChangeListener {

    private val handler = Handler(Looper.getMainLooper())

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val actions = AudioFocusActions(target)

    override fun onAudioFocusChange(focusChange: Int) {
        if (!actions.shouldDispatch(focusChange)) {
            return
        }
        // onAudioFocusChange 可能在子线程调用,统一切到主线程执行
        handler.post { actions.handle(focusChange) }
    }

    fun onNewPlayback() {
        actions.onNewPlayback()
    }

    fun requestFocus() {
        if (actions.focusGranted) {
            return
        }
        val manager = audioManager ?: return
        val status = manager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        actions.onRequestResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED == status)
    }

    fun abandonFocus() {
        val manager = audioManager ?: return
        actions.onAbandon()
        manager.abandonAudioFocus(this)
    }
}
