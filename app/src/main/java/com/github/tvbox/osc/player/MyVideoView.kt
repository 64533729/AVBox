package com.github.tvbox.osc.player

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import coil3.request.Disposable
import com.github.tvbox.osc.player.host.EngineTextureRenderViewFactory
import com.github.tvbox.osc.player.host.TextureRenderHost
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.util.ImgUtil
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.ui.widget.DanmakuView

/**
 * app 侧播放器视图(点播/直播/音乐共用实例):在 [AppPlayerView] 之上叠加本仓库特有的
 * 封面/黑帧/弹幕/渲染模式/内核重建 语义。
 *
 * <p>**去 doikki(M7e 起)**:基类由 `xyz.doikki.videoplayer.player.VideoView<AbstractPlayer>`
 * 换成 app 侧 [AppPlayerView];内核字段类型固定为 [ExoPlayer](media3 内核的唯一适配器),
 * 因此"读内核状态机"这类读口不再需要 `as? ExoPlayer` 判空降级。
 *
 * <p>**所有权**:实例由 [PlaybackEngine] 持有;渲染容器([AppPlayerView.playerContainer])在页面
 * 与引擎之间搬运,故本类持有的封面/黑帧视图不会随页面销毁而泄漏(它们挂在容器上,容器随引擎走)。
 */
class MyVideoView : AppPlayerView, DrawHandler.Callback {

    private var danmuView: DanmakuView? = null

    private var artworkView: ImageView? = null

    /** 封面的在途图片请求句柄:换图/隐藏前必须先取消,否则过期海报可能盖到画面上(见 [clearArtwork]) */
    private var artworkDisposable: Disposable? = null

    private var frameCover: View? = null

    /** 点播磁盘缓存标记(第二期扩展「边播边缓存」):默认 false(直播页不设置),点播容器启用 */
    private var mExoDiskCacheEnabled: Boolean = false

    /** "本次起播必须重建内核"标记(EXO 解码方式变更,见 `PlayerHelper.updateCfg`) */
    private var mKernelRebuildRequired: Boolean = false

    /** 本片记忆键(见 `TrackMemory`);存于播放器视图是因为内核重建后要把键推给新实例 */
    private var mTrackMemoryKey: String = ""

    @JvmOverloads
    constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        super(context, attrs, defStyleAttr)

    /** 本实例的内核(media3 唯一适配器;未建内核时为 null) */
    val exoPlayer: ExoPlayer?
        get() = mMediaPlayer as? ExoPlayer

    /**
     * 点播磁盘缓存标记:true 时 Exo 播放器对普通集也使用 cache 数据源(边播边缓存)。
     * 标志存于播放器视图(而非内核实例),内核切换/自动重试重建播放器后仍自动生效(见 [initPlayer])。
     */
    fun setExoDiskCacheEnabled(enabled: Boolean) {
        mExoDiskCacheEnabled = enabled
        applyExoDiskCacheFlag()
    }

    fun setTrackMemoryKey(key: String?) {
        mTrackMemoryKey = key ?: ""
        applyTrackMemoryKey()
    }

    override fun initPlayer() {
        super.initPlayer()
        applyExoDiskCacheFlag()
        applyTrackMemoryKey()
    }

    private fun applyTrackMemoryKey() {
        exoPlayer?.setContentKey(mTrackMemoryKey)
    }

    private fun applyExoDiskCacheFlag() {
        exoPlayer?.setUseDiskCache(mExoDiskCacheEnabled)
    }

    /**
     * 新栈播放状态:读内核状态机,承接旧 `currentPlayState`(doikki int)的读取面。
     * 内核不存在/未建时为 [PlayState.IDLE](旧读口的 -1 "无播放器"与"未在播"同判)。
     */
    val playState: PlayState
        get() = exoPlayer?.stateMachine?.currentState ?: PlayState.IDLE

    /** 内核存在且停在错误态:复用判定用它兜底 —— 复用一个坏内核没有意义,必须强制重建(无内核时为 false) */
    fun isKernelErrored(): Boolean = mMediaPlayer != null && playState == PlayState.ERROR

    fun requireKernelRebuild() {
        mKernelRebuildRequired = true
    }

    /** 取出并复位"必须重建内核"标记(起播处消费;true 时走非复用路径,先释放再新建) */
    fun consumeKernelRebuildRequired(): Boolean {
        val required = mKernelRebuildRequired
        mKernelRebuildRequired = false
        return required
    }

    /** 当前渲染视图是否为 SurfaceView(见 [switchRenderToTexture] 的纯音频兜底) */
    override val renderIsSurface: Boolean
        get() = renderView()?.getView() is SurfaceView

    /** 当前渲染工厂对应的渲染方式(1=Surface,0=Texture):"本次起播实际会用哪种视图"的唯一取值口 */
    override fun factoryRenderType(): Int =
        if (renderViewFactory() is EngineTextureRenderViewFactory) 0 else 1

    fun switchRenderToTexture() {
        setRenderViewFactory(EngineTextureRenderViewFactory.create())
        addDisplay()
    }

    fun ensureRenderViewMatchesConfig() {
        // 渲染视图空 = 尚未挂载(下次 start() 会按工厂创建);
        // 内核空 = 无播放器可挂载 —— attachToPlayer(null) 属未定义调用,直接返回更稳(与 clearVideoFrame 的判空风格一致)
        if (renderView() == null || mMediaPlayer == null) return
        val expectedSurface = renderViewFactory() !is EngineTextureRenderViewFactory
        if (expectedSurface == renderIsSurface) return
        // addDisplay() 内部已 release 旧实例并按新工厂重建 + attachToPlayer(当前内核)
        addDisplay()
    }

    /** 纹理渲染路径没有 SurfaceHolder:补发输出分辨率信令的时机靠这里挂钩(交面之后),漏挂 = 效果链拿不到输出面 */
    override fun addDisplay() {
        super.addDisplay()
        val render = renderView()
        if (render is TextureRenderHost) {
            render.setOnSurfaceReadyListener { pushRenderOutputResolution() }
        }
    }

    /** 纹理渲染路径没有 SurfaceHolder:交面后与每次布局都补发(漏发 = 效果管线出画异常) */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        pushRenderOutputResolution()
    }

    /** 视频尺寸就绪必须当帧补发(不能等下一次布局),输出面尺寸就取它 */
    override fun onVideoSizeReported(videoWidth: Int, videoHeight: Int) {
        pushRenderOutputResolution()
    }

    /** 纹理路径只推视频原生尺寸(推视图尺寸会被管线等比适应进画布 = 丢「铺满/裁剪」;取流前不下发) */
    private fun pushRenderOutputResolution() {
        val player = exoPlayer ?: return
        if (renderView() == null || renderIsSurface) return
        val width = mVideoSize[0]
        val height = mVideoSize[1]
        if (width <= 0 || height <= 0) return
        val render = renderView()
        if (render is TextureRenderHost) {
            render.setOutputSize(width, height)
        }
        player.notifyVideoOutputResolution(width, height)
    }

    fun setArtwork(url: String?) {
        if (TextUtils.isEmpty(url)) {
            clearArtwork()
            return
        }
        var view = artworkView
        if (view == null) {
            view = ImageView(context)
            view.setBackgroundColor(Color.BLACK)
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.isClickable = false
            view.isFocusable = false
            val index = if (renderView() == null) 0 else Math.min(1, mPlayerContainer.childCount)
            mPlayerContainer.addView(
                view,
                index,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
            artworkView = view
        }
        view.visibility = VISIBLE
        // 先撤旧请求再发新请求:封面每换一集/一线都要换图,上一个请求的迟到回调会把过期海报盖上来
        cancelArtworkRequest()
        artworkDisposable = ImgUtil.loadPlayerArtwork(url!!, view)
    }

    fun clearArtwork() {
        // 取消在途请求,再隐藏:Coil 的 onSuccess 不检查视图可见性,只置 GONE 挡不住晚到的位图
        cancelArtworkRequest()
        artworkView?.visibility = GONE
        artworkView?.setImageDrawable(null)
    }

    /** 取消播放器封面的在途图片请求(Coil dispose 会同步置 isDisposed 并取消 job,晚到回调不再落地) */
    private fun cancelArtworkRequest() {
        artworkDisposable?.dispose()
        artworkDisposable = null
    }

    fun isPortraitVideo(): Boolean = VideoOrientation.isPortrait(mVideoSize[0], mVideoSize[1])

    fun clearVideoFrame() {
        // 直调内核停播放(不进桥的"停止"命令):旧实现同样不改基类状态,状态机不得被这次动作翻成 IDLE
        val player = mMediaPlayer
        if (player is ExoPlayer) player.stopForFrameClear() else player?.stop()
        showFrameCover()
    }

    fun coverVideoFrame() {
        showFrameCover()
    }

    private fun showFrameCover() {
        var cover = frameCover
        if (cover == null) {
            cover = View(context)
            cover.setBackgroundColor(Color.BLACK)
            mPlayerContainer.addView(
                cover,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
            frameCover = cover
        }
        cover.visibility = VISIBLE
        // 遮罩是追加的,会把控制器(顶栏/手势层/字幕/直播控制层)一起盖住;控制器属 UI 层必须压在最上。
        // 用 bringToFront 而不是按 index 插:addDisplay() 永远把渲染视图插到 index 0,index 方案在
        // "渲染视图尚未创建"时会算错位(此时容器里可能只有控制器)。
        // 无控制器(服务侧无人挂载 / 已 detach)时无事可抬,遮罩照常置 VISIBLE —— 它在当前窗口里本就不显示。
        bringControllerToFront()
    }

    fun showVideoFrame() {
        hideVideoFrameCover()
        // 画面已出 → 顺手撤掉封面:artworkView 与渲染 Surface 同层且盖在其上,
        // 任何「画面已就绪却仍显示封面」的时序都会把视频压成一张海报(有声无画)。
        // 这里做终极兜底,保证「有画面」与「显示封面」互斥。
        clearArtwork()
    }

    fun hideVideoFrameCover() {
        frameCover?.visibility = GONE
    }

    fun isVideoFrameCleared(): Boolean = frameCover?.visibility == VISIBLE

    override fun seekTo(pos: Long) {
        super.seekTo(pos)
        if (haveDanmu()) danmuView?.seekTo(pos)
    }

    override fun resume() {
        super.resume()
        if (haveDanmu()) danmuView?.resume()
    }

    override fun start() {
        super.start()
        if (haveDanmu()) danmuView?.resume()
    }

    override fun pause() {
        super.pause()
        if (haveDanmu()) danmuView?.pause()
    }

    override fun release() {
        super.release()
        // 键随内核一起作废:直播页/音乐页直接 setUrl 起播从不推键,留着上一部片的键会吃掉它们的默认字幕
        mTrackMemoryKey = ""
        if (haveDanmu()) danmuView?.release()
    }

    private fun haveDanmu(): Boolean = danmuView?.isPrepared == true

    fun setDanmuView(view: DanmakuView?) {
        danmuView = view
        danmuView?.setCallback(this)
    }

    fun getDanmuView(): DanmakuView? = danmuView

    override fun prepared() {
        post {
            val view = danmuView ?: return@post
            if (isPlaying && view.isPrepared) {
                view.start(currentPosition)
            }
        }
    }

    override fun updateTimer(timer: DanmakuTimer?) {
    }

    override fun danmakuShown(danmaku: BaseDanmaku?) {
    }

    override fun drawingFinished() {
    }
}
