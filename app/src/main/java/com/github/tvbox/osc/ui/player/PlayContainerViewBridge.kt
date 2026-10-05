package com.github.tvbox.osc.ui.player

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Toast
import com.github.tvbox.osc.player.KernelDecision
import com.github.tvbox.osc.player.KernelPlayer
import com.github.tvbox.osc.player.KernelReusePolicy
import com.github.tvbox.osc.player.PreloadCoordinator
import com.github.tvbox.osc.player.PlaybackHostApi
import com.github.tvbox.osc.player.PlaybackViewBridge
import com.github.tvbox.osc.player.host.EngineTextureRenderViewFactory
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.PermissionHelper
import com.github.tvbox.osc.util.PlayerHelper
import org.json.JSONObject
import java.util.HashMap

/**
 * 页面侧的视图桥实现(M7e 起为 Kotlin):把调度层的"视图动作"落到 [PlayContainer]。
 *
 * <p>移植口径 = 纯语言迁移,逐行等价。Kotlin 侧的形态差异只在"平台类型显式化":
 * ① Java 的 `if (x != null) x.m()` 在 Kotlin 侧写成 `x?.m()`(判空分支与早退点不变);
 * ② `x == null ? a : b` 这类三元改写成 `?.` + `?:`(回落值与原值一致);
 * ③ [PlaybackViewBridge] 的 Kotlin 声明已收窄可空性(`WebView?`/`String?`/`HashMap?`),
 *    按实现要求补齐 —— 这几处原先靠 Java 平台类型宽松通过;
 * ④ `container.new MyWebView(...)`(Java 内部类实例化)在 Kotlin 侧是 `container.MyWebView(...)`。
 */
class PlayContainerViewBridge(private val container: PlayContainer) : PlaybackViewBridge {

    override fun isPageAlive(): Boolean = container.isAttached()

    override fun runOnUi(action: Runnable) {
        val activity = container.mActivity
        if (container.isAttached() && activity != null) activity.runOnUiThread(action)
    }

    override fun toast(text: CharSequence) {
        Toast.makeText(container.context, text, Toast.LENGTH_SHORT).show()
    }

    override fun showTip(msg: String, loading: Boolean, error: Boolean) {
        container.setTip(msg, loading, error)
    }

    override fun hideTipOnUiThread() {
        container.hideTipOnUiThread()
    }

    override fun currentPlayState(): Int = container.mVideoView?.currentPlayState ?: -1

    override fun playState(): PlayState = container.mVideoView?.playState ?: PlayState.IDLE

    override fun currentPosition(): Long = container.mVideoView?.currentPosition ?: 0L

    override fun isPlaying(): Boolean = container.mVideoView?.isPlaying == true

    override fun duration(): Long = container.mVideoView?.duration ?: 0L

    override fun mediaPlayer(): KernelPlayer? = container.mVideoView?.mediaPlayer

    override fun isKernelErrored(): Boolean = container.mVideoView?.isKernelErrored() == true

    override fun context(): Context = container.context

    override fun playbackHost(): PlaybackHostApi = container

    override fun requestNotificationPermission() {
        val host = container.mPageHost
        val activity = container.mActivity
        if (host != null) {
            host.requestNotificationPermission()
        } else if (activity != null) {
            PermissionHelper.requestNotificationIfNeeded(activity)
        }
    }

    override fun switchRenderToTexture() {
        val view = container.mVideoView
        if (view != null && view.renderIsSurface) view.switchRenderToTexture()
    }

    override fun ensureRenderViewMatchesConfig() {
        container.mVideoView?.ensureRenderViewMatchesConfig()
    }

    override fun releasePlayer() {
        container.releasePlayerKernel()
    }

    override fun setTitle(title: String) {
        container.mController?.setTitle(title)
    }

    override fun stopOtherPlayers() {
        container.mController?.stopOther()
    }

    override fun resetDanmu() {
        container.resetDanmuState()
    }

    override fun startDanmuIfReady() {
        container.startDanmuIfReady()
    }

    override fun clearLyric() {
        container.clearLyricView()
    }

    override fun clearArtwork() {
        container.mVideoView?.clearArtwork()
    }

    override fun clearVideoFrame() {
        container.mVideoView?.clearVideoFrame()
    }

    override fun setSubtitleViewVisible(visible: Boolean) {
        val controller = container.mController ?: return
        controller.getSubtitleView().visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onNewPlayStarted() {
        container.mExitingPreview = false
        container.mController?.onNewPlayStarted()
    }

    override fun applyPlayerConfigToView(forceKernel: Int) {
        val view = container.mVideoView ?: return
        // Java 侧是平台类型直传(`playerCfg()` 在 Kotlin 声明为 `JSONObject?`):无配置时下发空对象,
        // 与 MusicPlayerActivity 的同类实现同一兜底口径(updateCfg 内部按缺键回落到全局设置)
        PlayerHelper.updateCfg(view, container.scheduler.playerCfg() ?: JSONObject())
    }

    override fun useTextureRenderForAudio() {
        container.mVideoView?.setRenderViewFactory(EngineTextureRenderViewFactory.create())
    }

    override fun playExternalPlayer(
        playerType: Int,
        url: String,
        title: String,
        subtitle: String?,
        headers: HashMap<String, String>?,
        progress: Long,
    ): Boolean {
        val activity = container.mActivity ?: return false
        // 接口已放宽 subtitle 可空(parseSubtitle 可能未产出):直传非空 Kotlin 形参会 NPE,与音乐页实现同一兜底口径
        return PlayerHelper.runExternalPlayer(
            playerType,
            activity,
            url,
            title,
            subtitle ?: "",
            headers,
            progress,
        )
    }

    override fun playM3u8(url: String, headers: HashMap<String, String>) {
        container.mController?.playM3u8(url, headers)
    }

    override fun playM3u8(url: String, headers: HashMap<String, String>?, gen: Int) {
        if (!container.scheduler.isParseResultCurrent(gen)) {
            LOG.i("echo-ignore stale m3u8 result")
            return
        }
        // 与 Java 侧一致:此处不补 headers 默认值(调用方保证非空)
        playM3u8(url, headers!!)
    }

    override fun startVideoPlayback(
        url: String,
        headers: HashMap<String, String>?,
        forceExoPlayer: Boolean,
    ) {
        val view = container.mVideoView ?: return
        container.mController?.hidePauseRoot()
        // 渲染方式变更:复用内核不会重建渲染视图,必须走非复用路径
        if (view.mediaPlayer != null && view.needsRenderRebuild(view.factoryRenderType())) {
            view.requireKernelRebuild()
            LOG.i("echo-render-changed: rebuild kernel on next start")
        }
        // 错误态兜底:许可放行后内核仍可能在本轮取流期间才报错,到这里必须补强结论,不能把坏内核接着 reset 用
        if (view.isKernelErrored()) {
            view.requireKernelRebuild()
            LOG.i("echo-kernel-error: rebuild errored kernel on start")
        }
        // 复用内核不会重选解码器:标记无条件消费一次,避免残留到下一次无关起播
        val rebuildKernel = view.consumeKernelRebuildRequired()
        val kernelPresent = view.mediaPlayer != null
        val reusePlayer =
            KernelReusePolicy.decide(kernelPresent, rebuildKernel, forceExoPlayer, true) == KernelDecision.REUSE
        if (!reusePlayer) container.hideTip()
        if (!reusePlayer && kernelPresent) {
            container.releasePlayerKernel()
        } else if (reusePlayer && container.scheduler.isSameStartedContent()) {
            // 同内容重播走 replay、不经 release(该方法内部才有 saveProgress 兜底),位置在此补落一次;
            // 换内容不能在此落盘:键与起点都已属新内容,落盘会把新内容的起点写进旧键
            view.saveCurrentProgress()
        }
        view.setProgressKey(container.scheduler.progressKey())
        // 记忆键与进度键同处下发:内核重建后是新实例,起播前必须推给它
        view.setTrackMemoryKey(container.trackMemoryKey())
        container.scheduler.markContentStarted()
        if (headers != null) {
            view.setUrl(url, headers)
        } else {
            view.setUrl(url)
        }
        container.scheduler.startSwitchLinePlayTimeout()
        if (reusePlayer) {
            view.skipPositionWhenPlay(container.scheduler.playTimeoutBasePosition().toInt())
            view.replay(false)
        } else {
            view.start()
        }
        container.mController?.resetSpeed()
    }

    override fun buildPreloadSnapshot(): PreloadCoordinator.Snapshot? = container.buildPreloadSnapshot()

    override fun showPreloadReadyTip() {
        container.showPreloadReady()
    }

    override fun hidePreloadReadyTip() {
        container.hidePreloadReady()
    }

    override fun firstUrlByArray(url: String): String =
        container.mController?.firstUrlByArray(url) ?: url

    override fun setArtwork(url: String) {
        container.mVideoView?.setArtwork(url)
    }

    override fun showParse(show: Boolean) {
        container.mController?.showParse(show)
    }

    override fun checkDanmu(danmaku: String, onFailed: Runnable?) {
        container.checkDanmu(danmaku) { onFailed?.run() }
    }

    override fun encodeUrl(url: String): String =
        container.mController?.encodeUrl(url) ?: url

    override fun evaluateScript(url: String, webView: WebView?) {
        container.mController?.evaluateScript(container.scheduler.sourceBean(), url, webView)
    }

    override fun newSniffWebView(): WebView = container.MyWebView(container.context)

    override fun attachSniffWebView(webView: WebView) {
        val activity = container.mActivity
        if (container.isAttached() && activity != null) {
            activity.addContentView(webView, ViewGroup.LayoutParams(1, 1))
        }
    }

    override fun showErrorWithRetry(err: String, finish: Boolean) {
        container.errorWithRetry(err, finish)
    }

    override fun switchPlayerKernel(): Boolean =
        container.mController != null && container.mController.switchPlayer()

    override fun applyPlayerConfig(cfg: JSONObject) {
        container.mController?.setPlayerConfig(cfg)
    }

    override fun onLinesExhausted(): Boolean {
        val host = container.mPageHost ?: return false
        return host.onPlaybackLinesExhausted()
    }
}
