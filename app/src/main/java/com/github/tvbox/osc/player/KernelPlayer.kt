package com.github.tvbox.osc.player

import android.view.Surface
import android.view.SurfaceHolder

/**
 * 内核播放器契约(去 doikki 承接):取代 fork 的 `xyz.doikki.videoplayer.player.AbstractPlayer`。
 *
 * <p>为什么保留"抽象类 + 同名同形方法"而不是改写成 Kotlin 接口:宿主 [AppPlayerView] 与
 * [MyVideoView] 对内核的读写面(起播位置契约、渲染视图重置策略、选轨复位、音量/循环/速度)
 * 已按本形状逐条对齐旧实现(见 M7b/M7c 登记),形状不变 ⇒ 宿主侧逻辑可逐行保留。
 *
 * <p>**与旧类的等价性**:方法名/参数/返回值/时序全部照抄 `AbstractPlayer`,仅去掉 doikki 包依赖。
 * 旧类里 app 侧零调用点的成员(`setDataSource(AssetFileDescriptor)` 虽有调用点但旧实现即空操作)
 * 保留同形,避免宿主分支被改动。
 *
 * <p>**起播位置契约**(旧类的 `mStartPosition`/`mStartPositionApplied`):`prepareAsync` 前宿主写入
 * 起始位置,内核负责 seek 后标记已应用;宿主 `onPrepared` 里据此决定是否补一次 seek(自定义内核可能
 * 不实现该契约)。两个成员都用 `private/protected` 收口,与旧类一致。
 */
abstract class KernelPlayer {

    /**
     * 内核事件回调(取代旧 `AbstractPlayer.PlayerEventListener`)。
     *
     * <p>刻意放在 [KernelPlayer] **外部**:避免"内核契约"里出现指向宿主的循环类型,
     * 也便于宿主单测里构造替身(项目只有 JUnit,无 Mockito)。
     */
    interface Listener {
        fun onError()
        fun onCompletion()
        fun onInfo(what: Int, extra: Int)
        fun onPrepared()
        fun onVideoSizeChanged(width: Int, height: Int)
    }

    /** 宿主注册的播放事件回调(旧 doikki `protected PlayerEventListener mPlayerEventListener`,取同名同形态) */
    @JvmField
    protected var mPlayerEventListener: Listener? = null

    /** 起播位置(旧 `mStartPosition`);volatile 与旧实现齐平 */
    @Volatile
    private var mStartPosition: Long = 0

    /** 起播位置是否已被内核应用(旧 `mStartPositionApplied`);volatile 与旧实现齐平 */
    @Volatile
    private var startPositionApplied: Boolean = false

    // ==================== 内核生命周期 ====================

    abstract fun initPlayer()

    abstract fun setDataSource(path: String, headers: Map<String, String>?)

    abstract fun start()

    abstract fun pause()

    abstract fun stop()

    abstract fun prepareAsync()

    /** 写入起播位置(旧 `setStartPosition`):由宿主在 `prepareAsync` 前调用。**负数钳到 0**(与旧实现同) */
    fun setStartPosition(position: Long) {
        mStartPosition = maxOf(0L, position)
        startPositionApplied = false
    }

    /** 读取起播位置(旧 `protected final long getStartPosition()`;子类 `prepareAsync` 用它下发内核) */
    protected val startPosition: Long
        get() = mStartPosition

    /** 标记"起播位置已应用"(旧 `protected final markStartPositionApplied`) */
    protected fun markStartPositionApplied() {
        startPositionApplied = true
    }

    /** 起播位置是否已应用(旧 `public final isStartPositionApplied`,宿主 `onPrepared` 读) */
    fun isStartPositionApplied(): Boolean = startPositionApplied

    abstract fun reset()

    /**
     * `reset()` 是否保留渲染视图(旧 `keepRenderViewOnReset`,默认 false)。
     * 新栈内核返回 true ⇒ 宿主 `replay` 走"保留渲染视图"的复用分支。
     */
    open fun keepRenderViewOnReset(): Boolean = false

    /** 内容边界:同一选轨器接着用时清掉上一段选过的轨(旧 `resetTrackSelection`,默认空实现) */
    open fun resetTrackSelection() {}

    abstract val isPlaying: Boolean

    abstract fun seekTo(time: Long)

    abstract fun release()

    /** 当前播放位置(Kotlin 属性) */
    abstract val currentPosition: Long

    /** 总时长(Kotlin 属性) */
    abstract val duration: Long

    /** 缓冲百分比(Kotlin 属性) */
    abstract val bufferedPercentage: Int

    // ==================== 渲染面 ====================

    abstract fun setSurface(surface: Surface?)

    abstract fun setDisplay(holder: SurfaceHolder?)

    abstract fun setVolume(leftVolume: Float, rightVolume: Float)

    abstract fun setLooping(isLooping: Boolean)

    abstract fun setOptions()

    abstract fun setSpeed(speed: Float)

    /** 当前倍速(Kotlin 属性) */
    abstract val speed: Float

    /** 实时网速(Kotlin 属性) */
    abstract val tcpSpeed: Long

    /** 注入事件回调(旧 `setPlayerEventListener`) */
    fun setPlayerEventListener(listener: Listener?) {
        mPlayerEventListener = listener
    }

    companion object {

        /** 首帧开始渲染(旧 `MEDIA_INFO_RENDERING_START`) */
        const val MEDIA_INFO_RENDERING_START = 3

        /** 缓冲开始(旧 `MEDIA_INFO_BUFFERING_START`) */
        const val MEDIA_INFO_BUFFERING_START = 701

        /** 缓冲结束(旧 `MEDIA_INFO_BUFFERING_END`) */
        const val MEDIA_INFO_BUFFERING_END = 702

        /** 视频旋转角度变化(旧 `MEDIA_INFO_VIDEO_ROTATION_CHANGED`) */
        const val MEDIA_INFO_VIDEO_ROTATION_CHANGED = 10001
    }
}
