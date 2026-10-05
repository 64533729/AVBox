package com.github.tvbox.osc.player.controller

import android.webkit.WebView
import androidx.media3.ui.SubtitleView

import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView
import org.json.JSONObject
import java.util.HashMap

interface PlayerControlApi {

    interface KernelProvider {
        fun get(): MyVideoView
    }

    /** 未实现方(直播控制器等)无需覆写:接口级默认空实现,与 Java 侧 default 方法等价 */
    fun setKernelProvider(provider: KernelProvider?) {
    }

    fun getSubtitleView(): SimpleSubtitleView

    fun getLyricView(): SimpleSubtitleView

    fun getExoSubtitleView(): SubtitleView

    fun getUiState(): PlayerUiState

    fun setListener(listener: VodControlListener?) {
    }

    fun setPlayerConfig(playerCfg: JSONObject)

    fun showParse(userJxList: Boolean)

    fun setPreviewMode(previewMode: Boolean)

    fun setTitle(playTitleInfo: String) {
    }

    fun setUrlTitle(playTitleInfo: String) {
    }

    fun setHasDanmu(hasDanmu: Boolean)

    fun setCanChangePosition(canChangePosition: Boolean)

    fun setEnableInNormal(enableInNormal: Boolean)

    fun setGestureEnabled(gestureEnabled: Boolean)

    fun toggleControlBar()

    fun hidePauseRoot()

    fun onNewPlayStarted()

    fun setLifecyclePaused(paused: Boolean)

    fun resetSpeed()

    fun onBackPressed(): Boolean

    fun switchPlayer(): Boolean

    fun stopOther()

    fun playM3u8(url: String?, headers: HashMap<String, String>?)

    fun encodeUrl(url: String?): String

    fun firstUrlByArray(url: String?): String

    fun evaluateScript(sourceBean: SourceBean?, url: String?, view: WebView?)

    fun getWebPlayUrlIfNeeded(webPlayUrl: String?): String
}
