package com.github.tvbox.osc.player

import android.app.Activity
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Parcelable
import android.text.TextUtils
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.tvbox.osc.player.host.EngineSurfaceRenderViewFactory
import com.github.tvbox.osc.player.host.EngineTextureRenderViewFactory
import com.github.tvbox.osc.player.host.PlayerRenderView
import com.github.tvbox.osc.player.host.PlayerRenderViewFactory
import com.github.tvbox.osc.player.host.PlayerAudioFocus
import com.github.tvbox.osc.player.host.AudioFocusTarget
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.PlayerUtils

/**
 * app 侧播放器视图基类(去 doikki 承接):逐行为等价移植 fork 的
 * `xyz.doikki.videoplayer.player.VideoView`,只保留本仓库真正用到的面。
 *
 * <p>**所有权**:本类由 [PlaybackEngine] 持有(服务侧),承载"渲染容器 [mPlayerContainer]" ——
 * 该容器可被页面经 [attachContainerTo]/[detachContainerFromHost] 搬运进页面的显示槽位,
 * 从而做到跨页复用同一个内核与渲染视图(见 `skill/avbox-playback-service-spec.md` §2.3)。
 *
 * <p>**与旧类的移植口径**(只减不增,逐条有据):
 * - 删 XML 属性读取(`R.styleable.VideoView`):本仓库从不以 XML 声明播放器视图(`MyVideoView` 全部代码创建),
 *   旧类的 `obtainStyledAttributes` 恒走默认值;
 * - 删全局配置单例(`VideoViewManager`/`VideoViewConfig`):全仓零调用点,其默认值就地内联为字段初值;
 * - 删 `PlayerFactory`/`setPlayerFactory`:内核只剩 media3 一个,[initPlayer] 直接建 [ExoPlayer];
 * - 删 `ProgressManager`(`setProgressManager`/`saveProgress`):进度读写改由 [ProgressSink] 承接,
 *   由 [PlaybackEngine] 注入(语义逐条保留:位置 > 0 才落盘、`onCompletion` 显式清 0、`release` 在释放内核后落盘);
 * - 删全屏/小屏搬运(`startFullScreen`/`stopFullScreen`/`startTinyScreen`/`stopTinyScreen`/`setTinyScreenSize`)
 *   与 `PLAYER_TINY_SCREEN`:**本仓库零调用点** —— 播放页的"全屏"是 Activity 级(方向 + 系统栏),
 *   容器始终留在页面槽位里(详见架构说明)。`mCurrentPlayerState` 因此恒为 [PLAYER_NORMAL];
 * - 删静音/循环/镜像/旋转/截图(`setMute`/`isMute`/`setVolume`/`setLooping`/`setMirrorRotation`/
 *   `setRotation`/`doScreenShot`/`setPlayerBackgroundColor`):同样零调用点;
 * - 删 `isLocalDataSource()` 的 asset 分支与整个 `setAssetFileDescriptor` 路径:零调用点,
 *   且 [ExoPlayer.setDataSource] 的 asset 重载本就是空实现;
 * - 删 `AudioFocusHelper` 内部类:改用 [PlayerAudioFocus](app 侧已复刻 + 单测),语义逐条对齐
 *   (GAIN 恢复 / LOSS 暂停待恢复 / DUCK 降音量 / 静音不请求不恢复 / 同值去重 / 主线程派发)。
 *
 * <p>保留的承重语义一个不丢:状态机与闸门([isInPlaybackState])、暂停记忆([mPausedBeforeSeek])、
 * 渲染视图 index 0、控制器置顶与状态回灌、`release()` 的分支顺序、`replay` 的复用分支、
 * 起播位置契约(经 [KernelPlayer])、`setUrl` 清尺寸并通知控制器。
 */
open class AppPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    // ==================== 契约面(与旧 VideoView 同名同形) ====================

    /** 播放状态变化监听(取代旧 `VideoView.OnStateChangeListener`) */
    interface OnStateChangeListener {
        fun onPlayerStateChanged(playerState: Int)
        fun onPlayStateChanged(playState: Int)
    }

    /** 监听器空实现(取代旧 `VideoView.SimpleOnStateChangeListener`) */
    open class SimpleOnStateChangeListener : OnStateChangeListener {
        override fun onPlayerStateChanged(playerState: Int) = Unit
        override fun onPlayStateChanged(playState: Int) = Unit
    }

    /**
     * 进度读写出入口(逐形承接旧 doikki `ProgressManager`):[PlaybackEngine] 注入。
     *
     * <p>两个入口的语义与旧 `VideoView` 一一对应:`getSavedProgress` 在 [startPlay] 里读续播位置;
     * `saveProgress` 由 [saveProgress](位置 > 0 闸门)、[release] 与 `onCompletion`(显式清 0)调用。
     */
    interface ProgressSink {
        fun saveProgress(url: String?, progress: Long)

        fun getSavedProgress(url: String?): Long
    }

    // ==================== 内核 ====================

    /** 当前内核(每次 [initPlayer] 重建;[release] 后置空) */
    protected var mMediaPlayer: KernelPlayer? = null

    /** 注入的进度落盘出口;null = 不落盘(直播人格,或引擎未注入) */
    protected var mProgressSink: ProgressSink? = null

    // ==================== 视图 ====================

    /**
     * 真正承载播放器视图的容器:渲染视图 + 门面封面 + 控制器都在它里面。
     *
     * <p>容器实例在页面与引擎之间被搬运(见 [attachContainerTo]),因此它的 background/childCount/
     * LayoutParams 都会被页面沿用 —— 不要在搬运路径上重建它。
     */
    protected val mPlayerContainer: FrameLayout = FrameLayout(context)

    protected var mRenderView: PlayerRenderView? = null

    protected var mRenderViewFactory: PlayerRenderViewFactory =
        if (com.github.tvbox.osc.util.KV.get(com.github.tvbox.osc.util.HawkConfig.PLAY_RENDER, 1) == 1) {
            EngineSurfaceRenderViewFactory.create()
        } else {
            EngineTextureRenderViewFactory.create()
        }

    /** 画面比例([SCREEN_SCALE_*]);由 `PlayerHelper.updateCfg` 下发 */
    protected var mCurrentScreenScaleType = SCREEN_SCALE_DEFAULT

    /** 内核上报的视频原生尺寸(0/0 = 未知);[setUrl] 与 [release] 会清零 */
    protected var mVideoSize = intArrayOf(0, 0)

    // ==================== 数据源 ====================

    protected var mUrl: String? = null

    /** 进度键:非空时取代 [mUrl] 作为落盘身份(旧语义,调用方按内容设置) */
    protected var mProgressKey: String? = null

    protected var mHeaders: Map<String, String>? = null

    /** 当前播放位置(双重用途:续播起点 / 最近一次读数) */
    protected var mCurrentPosition = 0L

    // ==================== 状态机 ====================

    protected var mCurrentPlayState = STATE_IDLE

    /**
     * 暂停记忆:内核围绕 seek 会发缓冲/首帧回调把播放状态顶回"在播",而 `setPlayState(STATE_PAUSED)`
     * 只在 [pause] 里发 ⇒ 暂停语义丢失(中央播放暂停图标与实际画面相反)。
     * 暂停生效即记(含"seek 中暂停"),播放意图动作清除。
     */
    private var mPausedBeforeSeek = false

    protected var mCurrentPlayerState = PLAYER_NORMAL

    /** 音频焦点:开关与协助者(旧 `mEnableAudioFocus`/`AudioFocusHelper`) */
    protected var mEnableAudioFocus = true

    private var mAudioFocusHelper: PlayerAudioFocus? = null

    /** 状态变化监听器(旧实现懒建 + null 即"无监听") */
    protected var mOnStateChangeListeners: MutableList<OnStateChangeListener>? = null

    private val audioFocusTarget = object : AudioFocusTarget {
        override fun isPlaybackPlaying(): Boolean = isPlaying

        override fun isPlaybackMuted(): Boolean = false

        override fun startPlayback() {
            start()
        }

        override fun pausePlayback() {
            pause()
        }

        override fun setPlaybackVolume(volume: Float) {
            mMediaPlayer?.setVolume(volume, volume)
        }
    }

    init {
        mPlayerContainer.setBackgroundColor(Color.BLACK)
        addView(
            mPlayerContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        // 旧类在构造器里读全局配置(xml 属性恒走默认),这里只保留"默认开启音频焦点"这一条
        mEnableAudioFocus = true
    }

    // ==================== 起播 ====================

    /**
     * 开始播放。旧语义逐条保留:`IDLE`/`START_ABORT` → [startPlay];
     * `ERROR`/`PREPARING`/`PLAYBACK_COMPLETED` 一律**空操作**(只有 [replay] 能从这三态重起)。
     */
    open fun start() {
        if (isInIdleState() || isInStartAbortState()) {
            startPlay()
        } else if (isInPlaybackState()) {
            startInPlaybackState()
        }
    }

    /** 第一次播放(旧 `startPlay` 的顺序逐条保留) */
    protected open fun startPlay(): Boolean {
        // 移动网络提示:旧实现经控制器拿配置,而 dkplayer 的 `VideoViewManager.instance().playOnMobileNetwork()`
        // 在本仓库恒 false(从没人调 setConfig/setPlayOnMobileNetwork)⇒ 该闸门在本仓库"非本地源 + 移动网"
        // 时会中止起播。去 doikki 后没有控制器可问,故按同一恒值口径落成字段:保持"不中止"的现状。
        if (showNetWarning()) {
            setPlayState(STATE_START_ABORT)
            return false
        }
        // 监听音频焦点改变
        if (mEnableAudioFocus) {
            ensureAudioFocusHelper()
            mAudioFocusHelper?.onNewPlayback()
        }
        // 读取播放进度(须在 initPlayer() 之前:读到位置后经 setStartPosition 交给内核,挪后会从 0 起播)
        mProgressSink?.let { sink ->
            mCurrentPosition = sink.getSavedProgress(progressKey())
        }
        // 新建前必释放旧实例:本路径可能在「IDLE + 内核仍在」下被调用,直接 initPlayer() 会覆盖旧实例而不 release
        mMediaPlayer?.release()
        mMediaPlayer = null
        initPlayer()
        addDisplay()
        startPrepare(false)
        return true
    }

    /**
     * 是否显示移动网络提示。旧实现的判据 = 控制器侧配置(在本仓库恒"不提示"),
     * 去 doikki 后无控制器可问,保留同值口径的钩子供宿主覆盖(本地源恒不提示)。
     */
    protected open fun showNetWarning(): Boolean = false

    /**
     * 初始化播放器。顺序 = 建内核 → 注册事件 → `setInitOptions()` → `initPlayer()` → `setOptions()`
     * (旧 doikki `VideoView.initPlayer` 逐条一致)。
     *
     * <p>字段赋值比旧实现提前一行:`mMediaPlayer` 在 `setInitOptions()` 前就位,好让
     * `player.initPlayer()` 期间到达的内核回调(尺寸/首帧)能通过 [mMediaPlayer] 找到渲染视图 ——
     * 旧实现把 `initPlayer()` 的产物赋给 `mMediaPlayer` 时,`VideoView` 的 `mRenderView` 此时**尚未创建**
     * (`addDisplay()` 在 `startPlay` 里更晚才调),而新实现允许子类在 `setInitOptions()` 里先挂渲染视图。
     */
    protected open fun initPlayer() {
        val player = createPlayer()
        player.setPlayerEventListener(kernelEventListener)
        mMediaPlayer = player
        setInitOptions()
        player.initPlayer()
        setOptions()
    }

    /** 建内核实例(旧 `mPlayerFactory.createPlayer` 的等价物:内核只剩 media3 一个) */
    protected open fun createPlayer(): KernelPlayer = ExoPlayer(context)

    /** 旧 `setInitOptions`:建内核**之前**的下发点(当前无人使用,保留钩子) */
    protected open fun setInitOptions() = Unit

    /** 旧 `setOptions`:建内核**之后**的下发点(循环/音量);`reset()` 后也要重发 */
    protected open fun setOptions() {
        mMediaPlayer?.setLooping(false)
        mMediaPlayer?.setVolume(1.0f, 1.0f)
    }

    /**
     * 预热内核:建内核与渲染视图但不 prepare(须主线程调用)。已有内核时幂等 ——
     * 预热后首次起播命中复用分支,省去内核构造与渲染视图创建两段。
     */
    open fun prewarmKernel() {
        if (mMediaPlayer != null) return
        // 起播走 replay 不经 startPlay:助手不预建,onPrepared 的判空会让首次会话没有音频焦点
        ensureAudioFocusHelper()
        initPlayer()
        addDisplay()
    }

    /** 音频焦点监听只建一次(覆盖引用会留下永不释放的旧 listener,它们仍会响应焦点事件去 pause/start) */
    private fun ensureAudioFocusHelper() {
        if (mEnableAudioFocus && mAudioFocusHelper == null) {
            mAudioFocusHelper = PlayerAudioFocus(context, audioFocusTarget)
        }
    }

    /** 初始化视频渲染视图(旧 `addDisplay`:先摘旧视图,再插到 index 0) */
    protected open fun addDisplay() {
        mRenderView?.let { render ->
            mPlayerContainer.removeView(render.getView())
            render.release()
        }
        val render = mRenderViewFactory.createRenderView(context)
        mMediaPlayer?.let { render.attachToPlayer(it) }
        mRenderView = render
        mPlayerContainer.addView(
            render.getView(),
            0,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
    }

    /** 开始准备播放(直接播放) */
    protected fun startPrepare(reset: Boolean) {
        startPrepare(reset, false)
    }

    protected fun startPrepare(reset: Boolean, rebindRenderView: Boolean) {
        // 新一次起播(replay 也走这里):seek 前的暂停记忆随之作废,否则会把在播的新内容按回暂停
        mPausedBeforeSeek = false
        if (reset) {
            mMediaPlayer?.reset()
            // 重新设置 option,内核 reset 之后 option 会失效
            setOptions()
            if (rebindRenderView) {
                mMediaPlayer?.let { player -> mRenderView?.attachToPlayer(player) }
            }
        }
        if (prepareDataSource()) {
            mMediaPlayer?.let { player ->
                player.setStartPosition(mCurrentPosition)
                player.prepareAsync()
            }
            setPlayState(STATE_PREPARING)
            // 全屏/小屏搬运已随去 doikki 删除 ⇒ 恒为普通态(与旧实现"未进过全屏"时的取值一致)
            setPlayerState(PLAYER_NORMAL)
        }
    }

    /** 设置播放数据;返回是否设置成功(asset 路径已随零调用点删除) */
    protected open fun prepareDataSource(): Boolean {
        val url = mUrl
        if (!TextUtils.isEmpty(url)) {
            mMediaPlayer?.setDataSource(url!!, mHeaders)
            return true
        }
        return false
    }

    /** 播放状态下开始播放 */
    protected fun startInPlaybackState() {
        mPausedBeforeSeek = false
        mMediaPlayer?.start()
        setPlayState(STATE_PLAYING)
        if (!isMute()) {
            mAudioFocusHelper?.requestFocus()
        }
        mPlayerContainer.keepScreenOn = true
    }

    // ==================== 播控 ====================

    /**
     * 暂停播放。旧语义:只在"处于播放态且内核真在播"时生效 —— 其余情况(含 PREPARING/BUFFERING)
     * 是**完全空操作**,需要停内核请用 [stopPlaybackKeepPlayer]。
     */
    open fun pause() {
        val player = mMediaPlayer
        if (isInPlaybackState() && player?.isPlaying == true) {
            // 暂停生效即记:seek 后立刻暂停时状态停在 BUFFERING(不是 PAUSED),不记的话随后的首帧回调会把 UI 顶回在播
            mPausedBeforeSeek = true
            player.pause()
            setPlayState(STATE_PAUSED)
            if (!isMute()) {
                mAudioFocusHelper?.abandonFocus()
            }
            mPlayerContainer.keepScreenOn = false
        }
    }

    /**
     * 继续播放。旧实现在 Surface 未就绪时挂一次性 `SurfaceHolder.Callback` 等交面;
     * 去 doikki 后渲染宿主自己负责交面([PlayerRenderView] 的 `surfaceCreated` 会 `setDisplay`),
     * 这里直接续播 —— 渲染视图不在时旧实现本就会走到 `resumePlay()`(非 SurfaceView 分支)。
     */
    open fun resume() {
        if (isInPlaybackState() && mMediaPlayer?.isPlaying == false) {
            resumePlay()
        }
    }

    private fun resumePlay() {
        mPausedBeforeSeek = false
        mMediaPlayer?.start()
        setPlayState(STATE_PLAYING)
        if (!isMute()) {
            mAudioFocusHelper?.requestFocus()
        }
        mPlayerContainer.keepScreenOn = true
    }

    /**
     * 停止播放但**保留播放器实例**。
     *
     * <p>与 [release] 的区别:只 stop 内核(会打断 PREPARING/BUFFERING 中的起播),
     * 不销毁实例、不卸载渲染视图 —— 因此实例可跨页面复用(下一次起播走复用分支的 reset 路径)。
     *
     * <p>已被 [pause] 停住的保持 PAUSED:PAUSED + 实例仍在 = 可复用状态;置 IDLE 会变成
     * "IDLE + 实例仍在"这个危险组合(此后 [start] 会走 [startPlay] 新建内核)。
     */
    open fun stopPlaybackKeepPlayer() {
        val player = mMediaPlayer ?: return
        if (mCurrentPlayState == STATE_PAUSED) return
        player.stop()
        setPlayState(STATE_IDLE)
    }

    /**
     * 显式落盘当前进度。服务化后页面退出不再 release(否则跨页复用就没了),因此必须有这个入口,
     * 否则"退出详情页 → 再进"会丢失上次位置。无落盘出口(直播人格)或位置为 0 时是空操作。
     */
    open fun saveCurrentProgress() {
        saveProgress()
    }

    /** 释放播放器(旧 `release()` 的分支顺序逐条保留) */
    open fun release() {
        mPausedBeforeSeek = false
        // 焦点监听与 IDLE 无关:stopPlaybackKeepPlayer 置 IDLE 后再 release 也必须清,否则 listener 残留在系统里
        mAudioFocusHelper?.abandonFocus()
        mAudioFocusHelper = null
        // 内核释放不随播放状态跳过:"IDLE + 内核仍在"时若跳过释放,后续 startPlay() 会覆盖旧实例而不 release
        mMediaPlayer?.release()
        mMediaPlayer = null
        if (!isInIdleState()) {
            // 释放渲染视图
            mRenderView?.let { render ->
                mPlayerContainer.removeView(render.getView())
                render.release()
            }
            mRenderView = null
            // 关闭屏幕常亮
            mPlayerContainer.keepScreenOn = false
            // 保存播放进度(与旧实现同:此刻内核已释放,时长读作 0 由落盘侧自行兜底)
            saveProgress()
            // 重置播放进度
            mCurrentPosition = 0
            // 切换状态
            setPlayState(STATE_IDLE)
        }
        mVideoSize[0] = 0
        mVideoSize[1] = 0
    }

    /** 保存播放进度:位置 > 0 才有意义(旧实现同闸门) */
    protected fun saveProgress() {
        val sink = mProgressSink ?: return
        if (mCurrentPosition > 0) {
            LOG.d("AppPlayerView", "saveProgress: " + mCurrentPosition)
            sink.saveProgress(progressKey(), mCurrentPosition)
        }
    }

    /** 进度身份:进度键非空时取代 URL(旧语义) */
    protected fun progressKey(): String? = mProgressKey ?: mUrl

    // ==================== 状态闸门 ====================

    /** 是否处于播放态(旧 `isInPlaybackState`) */
    protected fun isInPlaybackState(): Boolean {
        return mMediaPlayer != null &&
            mCurrentPlayState != STATE_ERROR &&
            mCurrentPlayState != STATE_IDLE &&
            mCurrentPlayState != STATE_PREPARING &&
            mCurrentPlayState != STATE_START_ABORT &&
            mCurrentPlayState != STATE_PLAYBACK_COMPLETED
    }

    /** 是否处于未播放状态 */
    protected fun isInIdleState(): Boolean = mCurrentPlayState == STATE_IDLE

    /** 播放中止状态 */
    private fun isInStartAbortState(): Boolean = mCurrentPlayState == STATE_START_ABORT

    // ==================== 内核事件 ====================

    private val kernelEventListener = object : KernelPlayer.Listener {

        /**
         * 视频缓冲完毕、准备开始播放。
         *
         * <p>自定义内核可能不实现起播位置契约:未被内核应用时在这里补一次 seek
         * (app 内核在 `prepareAsync` 里就标记已应用,故该分支通常不走到,但契约必须留)。
         */
        override fun onPrepared() {
            val player = mMediaPlayer ?: return
            if (mCurrentPosition > 0 && !player.isStartPositionApplied()) {
                player.seekTo(mCurrentPosition)
            }
            setPlayState(STATE_PREPARED)
            if (!isMute()) {
                mAudioFocusHelper?.requestFocus()
            }
        }

        /** 缓冲开始/结束、首帧渲染、旋转角变化 */
        override fun onInfo(what: Int, extra: Int) {
            when (what) {
                KernelPlayer.MEDIA_INFO_BUFFERING_START ->
                    if (!keepPausedStateAfterSeek()) setPlayState(STATE_BUFFERING)

                KernelPlayer.MEDIA_INFO_BUFFERING_END ->
                    if (!keepPausedStateAfterSeek()) setPlayState(STATE_BUFFERED)

                KernelPlayer.MEDIA_INFO_RENDERING_START ->
                    // 暂停中的 seek 也会渲染出新位置的帧,不能据此判成"在播"
                    if (!keepPausedStateAfterSeek()) {
                        setPlayState(STATE_PLAYING)
                        mPlayerContainer.keepScreenOn = true
                    }

                KernelPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED ->
                    mRenderView?.setVideoRotation(extra)
            }
        }

        override fun onError() {
            mPlayerContainer.keepScreenOn = false
            setPlayState(STATE_ERROR)
        }

        override fun onCompletion() {
            mPlayerContainer.keepScreenOn = false
            mCurrentPosition = 0
            // 播放完成,清除进度(显式写 0,不是"跳过落盘")
            mProgressSink?.saveProgress(progressKey(), 0L)
            setPlayState(STATE_PLAYBACK_COMPLETED)
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            mVideoSize[0] = width
            mVideoSize[1] = height
            // 子类钩子(MyVideoView 据此补发输出分辨率信令)
            onVideoSizeReported(width, height)
            // 同步给控制器:控制层原先只能轮询取值,换片后要等下一次轮询才刷新
            mVideoController?.onVideoSizeChanged(width, height)
            mRenderView?.let { render ->
                render.setScaleType(mCurrentScreenScaleType)
                render.setVideoSize(width, height)
            }
        }
    }

    /** 内核上报视频尺寸的扩展钩子(默认空;旧 doikki 的 `onVideoSizeChanged` 覆写面) */
    protected open fun onVideoSizeReported(width: Int, height: Int) = Unit

    // ==================== 读口 ====================

    /** 取内核(旧 `getMediaPlayer`) */
    @get:JvmName("getMediaPlayer")
    val mediaPlayer: KernelPlayer?
        get() = mMediaPlayer

    /** 视频总时长(非播放态为 0;与旧 doikki `VideoView.getDuration()` 同义) */
    open val duration: Long
        get() = if (isInPlaybackState()) mMediaPlayer?.duration ?: 0L else 0L

    /** 当前播放位置(同时刷新 [mCurrentPosition];非播放态返回 0 且**不**刷新 —— 旧语义如此) */
    open val currentPosition: Long
        get() {
            if (isInPlaybackState()) {
                mCurrentPosition = mMediaPlayer?.currentPosition ?: 0L
                return mCurrentPosition
            }
            return 0
        }

    /** 调整播放进度(非播放态静默忽略) */
    open fun seekTo(pos: Long) {
        if (isInPlaybackState()) {
            // 暂停中的 seek:内核不会因此续播,但随后的缓冲回调会把状态顶离 PAUSED,先记下
            if (mCurrentPlayState == STATE_PAUSED) {
                mPausedBeforeSeek = true
            }
            mMediaPlayer?.seekTo(pos)
        }
    }

    /**
     * 暂停记忆生效期间(seek/缓冲回调)是否仍按"暂停"呈现:返回 true 时调用方不得再改播放状态。
     * 用户按下播放(start/resume)会先清掉标记,不会被误按回暂停。
     */
    private fun keepPausedStateAfterSeek(): Boolean {
        if (!mPausedBeforeSeek) return false
        if (mCurrentPlayState != STATE_PAUSED) {
            setPlayState(STATE_PAUSED)
        }
        return true
    }

    /** 是否在播 */
    open val isPlaying: Boolean
        get() = isInPlaybackState() && mMediaPlayer?.isPlaying == true

    /** 缓冲百分比(不受播放态闸门约束) */
    val bufferedPercentage: Int
        get() = mMediaPlayer?.bufferedPercentage ?: 0

    /** 缓冲速度/网速(不受播放态闸门约束) */
    open val tcpSpeed: Long
        get() = mMediaPlayer?.tcpSpeed ?: 0L

    /** 设置播放速度(非播放态静默忽略) */
    open fun setSpeed(speed: Float) {
        if (isInPlaybackState()) mMediaPlayer?.setSpeed(speed)
    }

    /** 当前倍速(非播放态为 1f) */
    open val speed: Float
        get() = if (isInPlaybackState()) mMediaPlayer?.speed ?: 1f else 1f

    /** 是否静音。去 doikki 后不再有静音开关(零调用点),恒 false;音频焦点判据仍读它 */
    open fun isMute(): Boolean = false

    // ==================== 数据源入口 ====================

    open fun setUrl(url: String) {
        setUrl(url, null)
    }

    open fun setUrl(url: String, headers: Map<String, String>?) {
        mPausedBeforeSeek = false
        mUrl = url
        mHeaders = headers
        mVideoSize[0] = 0
        mVideoSize[1] = 0
        // 换内容:旧尺寸作废(控制层据此丢弃上一会话的残留值)
        mVideoController?.onVideoSizeCleared()
    }

    open fun setProgressKey(key: String?) {
        mProgressKey = key
    }

    /** 注入进度落盘出口(取代旧 `setProgressManager`;传 null = 不落盘) */
    open fun setProgressSink(sink: ProgressSink?) {
        mProgressSink = sink
    }

    /** 一开始播放就 seek 到预先设置好的位置 */
    open fun skipPositionWhenPlay(position: Int) {
        mCurrentPosition = position.toLong()
    }

    // ==================== 复用 / 重播 ====================

    /**
     * 重新播放。`resetPosition = true` 时从头开始。
     *
     * <p>内核缺失是**不应发生**的调用(调用方须先确认内核还在):旧实现静默转 [start] 会把判定错误
     * 吞成"看起来正常",这里保留同一兜底与告警。
     */
    open fun replay(resetPosition: Boolean) {
        if (resetPosition) {
            mCurrentPosition = 0
        }
        val player = mMediaPlayer
        if (player == null) {
            LOG.i("replay() called without kernel, fallback to start()")
            start()
            return
        }
        // 复用内核不经 startPlay():补一次"新一次播放开始",否则上一段遗留的"待焦点恢复"会被陈旧 GAIN 兑现成自动起播
        if (mEnableAudioFocus) {
            mAudioFocusHelper?.onNewPlayback()
        }
        // 内核复用的内容边界:同一选轨器接着用,上一段选过的轨(轨道组按内容比相等)会串到这一段
        player.resetTrackSelection()
        if (player.keepRenderViewOnReset()) {
            player.reset()
            setOptions()
            player.setOptions()
            startPrepare(false)
        } else {
            startPrepare(true, true)
        }
    }

    /** 渲染方式(1 = Surface,0 = Texture):"本次起播实际会用哪种视图"的唯一取值口 */
    open fun factoryRenderType(): Int =
        if (mRenderViewFactory is EngineTextureRenderViewFactory) 0 else 1

    /** 切换渲染工厂;生效于下一次 [addDisplay] */
    open fun setRenderViewFactory(factory: PlayerRenderViewFactory) {
        mRenderViewFactory = factory
    }

    /** 渲染视图是否与目标渲染方式不一致(不一致 = 必须重建内核才会生效);未挂载时算已就绪 */
    open fun needsRenderRebuild(targetRenderType: Int): Boolean {
        val render = mRenderView ?: return false
        return (targetRenderType == 1) != (render.getView() is SurfaceView)
    }

    /** 设置画面比例(同时下发渲染视图) */
    open fun setScreenScaleType(screenScaleType: Int) {
        mCurrentScreenScaleType = screenScaleType
        mRenderView?.setScaleType(screenScaleType)
    }

    /** 视频原生宽高([0] = 宽,[1] = 高) */
    open val videoSize: IntArray
        get() = mVideoSize

    /** 当前播放状态([STATE_*]) */
    val currentPlayState: Int
        get() = mCurrentPlayState

    /** 当前播放器状态([PLAYER_*];去 doikki 后恒为 [PLAYER_NORMAL]) */
    fun getCurrentPlayerState(): Int = mCurrentPlayerState

    /** 设置静音(去 doikki 后不再有开关;保留入口以承接旧调用点语义,当前无人调用) */
    @Suppress("UNUSED_PARAMETER")
    open fun setMute(isMute: Boolean) = Unit

    /** 向控制器设置播放状态(控制层 UI 展示) */
    protected fun setPlayState(playState: Int) {
        mCurrentPlayState = playState
        mVideoController?.setPlayState(playState)
        mOnStateChangeListeners?.let { listeners ->
            for (listener in it2snapshot(listeners)) {
                listener?.onPlayStateChanged(playState)
            }
        }
    }

    /** 向控制器设置播放器状态(全屏/普通/小屏;去 doikki 后恒为普通) */
    protected fun setPlayerState(playerState: Int) {
        mCurrentPlayerState = playerState
        mVideoController?.setPlayerState(playerState)
        mOnStateChangeListeners?.let { listeners ->
            for (listener in it2snapshot(listeners)) {
                listener?.onPlayerStateChanged(playerState)
            }
        }
    }

    /** 旧 `PlayerUtils.getSnapshot`:遍历前做一份"跳过 null"的浅拷贝(监听器可重入增删) */
    private fun it2snapshot(source: List<OnStateChangeListener>): List<OnStateChangeListener> {
        val result = ArrayList<OnStateChangeListener>(source.size)
        for (item in source) {
            if (item != null) result.add(item)
        }
        return result
    }

    open fun addOnStateChangeListener(listener: OnStateChangeListener) {
        val listeners = mOnStateChangeListeners ?: ArrayList<OnStateChangeListener>().also {
            mOnStateChangeListeners = it
        }
        listeners.add(listener)
    }

    open fun removeOnStateChangeListener(listener: OnStateChangeListener) {
        mOnStateChangeListeners?.remove(listener)
    }

    // ==================== 控制器挂载 ====================

    /**
     * 控制器覆盖层(去 doikki 后不再是 doikki `BaseVideoController`,而是本接口的实现)。
     *
     * <p>**视图侧**:实现方需自行是 [View](挂进渲染容器、由本类调用 `bringToFront`)。
     * **契约面**:逐条承接旧 doikki 控制器的三类交互 ——
     * ① 状态推送(播放状态/播放器状态/尺寸/尺寸作废/进度刷新点);
     * ② 控制包装类查询(旧 `ControlWrapper`:`duration`/`currentPosition`/`bufferedPercentage`/
     * `videoSize`/`tcpSpeed`/`isPlaying`/`isFullScreen`/`getSpeed`/`seekTo`/`setSpeed`/
     * `setScreenScaleType`/`togglePlay`)—— 直接落在播放器视图上实现,不再有中间包装对象;
     * ③ 底栏闲置计时(旧 `BaseVideoController.startFadeOut`/`stopFadeOut`,由控制器自己实现)。
     */
    interface VideoControllerHost {
        fun setPlayState(playState: Int)

        fun setPlayerState(playerState: Int)

        /** 内核上报视频尺寸(0/0 = 尺寸未知/已作废) */
        fun onVideoSizeChanged(width: Int, height: Int)

        /** 内核换了内容([setUrl]):此前上报的尺寸不再属于当前会话 */
        fun onVideoSizeCleared()

        /** 进度刷新点(旧 `BaseVideoController.startProgress()` 挂上来的定时器一跳) */
        fun startProgress()
    }

    /** 当前挂载的控制器(null = 无) */
    protected var mVideoController: VideoControllerHost? = null

    /** 当前挂载的控制器(null = 无) */
    open val videoController: VideoControllerHost?
        get() = mVideoController

    /**
     * 设置控制器,传 null 表示移除。
     *
     * <p>旧实现的三件事一件不少:① 先把旧控制器从容器里摘掉(触发其 `onDetachedFromWindow`);
     * ② 新控制器追加进容器(最后一个孩子 = 最上层);③ **状态回灌** —— 挂上来的控制器可能是全新的
     * (新页面 / 摘下后重挂),而播放器此时也许已经在播或已暂停;内核只在状态**变化**时下发事件,
     * 新控制器会一直停在初始 IDLE(duration/position = 0、播放暂停图标反相),
     * 故必须补发当前状态,并在非 IDLE/ERROR 时补一跳进度。
     */
    open fun setVideoController(controller: VideoControllerHost?) {
        val old = mVideoController
        if (old is View) {
            mPlayerContainer.removeView(old)
        }
        mVideoController = controller
        if (controller != null) {
            if (controller is View) {
                mPlayerContainer.addView(
                    controller,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                )
            }
            controller.setPlayState(mCurrentPlayState)
            controller.setPlayerState(mCurrentPlayerState)
            if (mCurrentPlayState != STATE_IDLE && mCurrentPlayState != STATE_ERROR) {
                controller.startProgress()
            }
        }
    }

    /** 摘掉控制器([setVideoController] 传 null 的显式入口,供 Java/引擎侧调用)。返回被摘下的控制器 */
    open fun releaseController(): VideoControllerHost? {
        val old = mVideoController
        setVideoController(null)
        return old
    }

    // ==================== 控制器查询面(旧 doikki `ControlWrapper` 的 app 侧等价物) ====================

    /** 是否全屏。去 doikki 后容器不再搬进 DecorView,"全屏"由页面(Activity 方向 + 系统栏)承担 ⇒ 恒 false */
    open fun isFullScreen(): Boolean = false

    /** 播放/暂停切换(旧 `ControlWrapper.togglePlay`) */
    open fun togglePlay() {
        if (isPlaying) pause() else start()
    }

    /** 渲染视图截图(Surface 路径返回 null;供直播页切台快照) */
    open fun doScreenShot(): Bitmap? = mRenderView?.doScreenShot()

    open fun onBackPressed(): Boolean = false

    // ==================== 容器挂摘(播放服务化,§2.3) ====================

    /**
     * 把承载播放画面的容器挂到外部宿主(服务持有播放器与渲染视图,页面只提供显示宿主)。
     *
     * <p>只搬运 [mPlayerContainer],**不碰系统栏**;先判 parent 再 `addView`,幂等且不会重复挂载。
     * 搬运会触发 SurfaceView 的 surfaceDestroyed/surfaceCreated,渲染宿主自己会 `setDisplay(null)`
     * 再重挂(安全性结论见播放服务化 Spec §2.3/R1)。
     *
     * @param host 页面侧显示宿主(插到 index 0:宿主内的弹幕/覆盖层都在其之上)
     */
    open fun attachContainerTo(host: ViewGroup?) {
        if (host == null) return
        val parent = mPlayerContainer.parent as? ViewGroup
        if (parent === host) return
        parent?.removeView(mPlayerContainer)
        val lp = mPlayerContainer.layoutParams ?: LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        host.addView(mPlayerContainer, 0, lp)
    }

    /**
     * 把渲染容器从外部宿主摘回自身(服务侧),供页面 detach/销毁调用;幂等(未挂出时为空操作)。
     * 摘回后渲染视图失去 Surface(画面不可见),音频不受影响。
     */
    open fun detachContainerFromHost() {
        val parent = mPlayerContainer.parent as? ViewGroup
        if (parent == null || parent === this) return
        parent.removeView(mPlayerContainer)
        addView(
            mPlayerContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    /** 渲染容器当前是否挂在指定宿主上(挂摘幂等判定;宿主已销毁时为 false) */
    open fun isContainerAttachedTo(host: ViewGroup?): Boolean =
        host != null && mPlayerContainer.parent === host

    /** 渲染容器(供引擎/宿主读子视图与做控制器置顶;去 doikki 后不再隐式暴露) */
    open fun playerContainer(): FrameLayout = mPlayerContainer

    // ==================== 其它 ====================

    /** 控制器置顶(盖黑帧/封面追加后会压住控制器,必须显式抬回) */
    open fun bringControllerToFront() {
        (mVideoController as? View)?.bringToFront()
    }

    /** Activity 解析(旧实现优先经控制器上下文;去 doikki 后按 view 自身上下文解析) */
    fun hostActivity(): Activity? = PlayerUtils.scanForActivity(context)

    /** 当前渲染面是否 SurfaceView(Surface 模式);Texture 模式为 false */
    open val renderIsSurface: Boolean
        get() = mRenderView?.getView() is SurfaceView

    /** 渲染视图(子类做输出尺寸信令用;null = 尚未挂载) */
    protected fun renderView(): PlayerRenderView? = mRenderView

    /** 当前渲染工厂(子类做"本次起播用哪种渲染方式"判定用) */
    protected fun renderViewFactory(): PlayerRenderViewFactory = mRenderViewFactory

    override fun onSaveInstanceState(): Parcelable? {
        // 旧实现:切后台可能被系统回收,在此落一次进度
        saveProgress()
        return super.onSaveInstanceState()
    }

    companion object {

        // 画面比例(与 host/RenderMeasure.SCALE_* 逐值对齐)
        const val SCREEN_SCALE_DEFAULT = 0
        const val SCREEN_SCALE_16_9 = 1
        const val SCREEN_SCALE_4_3 = 2
        const val SCREEN_SCALE_MATCH_PARENT = 3
        const val SCREEN_SCALE_ORIGINAL = 4
        const val SCREEN_SCALE_CENTER_CROP = 5

        // 播放状态
        const val STATE_ERROR = -1
        const val STATE_IDLE = 0
        const val STATE_PREPARING = 1
        const val STATE_PREPARED = 2
        const val STATE_PLAYING = 3
        const val STATE_PAUSED = 4
        const val STATE_PLAYBACK_COMPLETED = 5
        const val STATE_BUFFERING = 6
        const val STATE_BUFFERED = 7
        const val STATE_START_ABORT = 8

        // 播放器状态(全屏/小屏搬运已删,只留普通态)
        const val PLAYER_NORMAL = 10
        const val PLAYER_FULL_SCREEN = 11
        const val PLAYER_TINY_SCREEN = 12
    }
}
