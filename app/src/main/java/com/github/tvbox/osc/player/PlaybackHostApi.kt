package com.github.tvbox.osc.player

import android.net.Uri

/**
 * 播放指令面(播放服务化 Spec §2.1–2.3)。
 *
 * 页面只通过这些方法驱动播放;实现方在 P0/P1 是页面内的 PlayContainer,
 * P2 起改为前台服务持有的播放宿主 MyVideoView + PlaybackController。页面侧(Compose 覆盖层、
 * 详情页、媒体会话/通知)只依赖本接口,从而与"播放器由谁持有"解耦。
 */
interface PlaybackHostApi {

    /** 开始一次播放(页面组装的会话数据) */
    fun setData(session: PlaybackSession)

    /** 重播当前集(reset=true 时清除进度从头发起) */
    fun play(reset: Boolean)

    /** 下一集(rmProgress=true 时清掉上一集进度) */
    fun playNext(rmProgress: Boolean)

    /** 上一集 */
    fun playPrevious()

    /** 切换清晰度(position 多清晰度源的 url 数组下标) */
    fun selectQuality(position: Int): Boolean

    /** 全屏形态下是否允许自动换线(预览态启用,全屏态禁用 —— 见 DetailActivity.applyFullscreen) */
    fun setAutoSwitchLineEnabled(enabled: Boolean)

    /** 竖屏预览态样式(底栏菜单行/暂停钮/边距) */
    fun setPreviewMode(previewMode: Boolean)

    /** 唤出/收起控制器控件(返回键 YouTube 式两步退出用) */
    fun toggleControllerControls()

    /** 返回键交给播放层消费:true=已处理(不退出页面) */
    fun onBackPressed(): Boolean

    /** 标记"正在退出预览态"(退出页面时不自动暂停 —— 避免退后台暂停语义误伤) */
    fun setExitingPreview(exitingPreview: Boolean)

    /** 顶部标题显隐 */
    fun setPlayTitle(show: Boolean)

    /** 换源点击即停:停播并记住进度,抑制在途取流结果 */
    fun stopForSourceSwitch(tip: String)

    /** 清除"正在切换片源"提示 */
    fun clearSourceSwitchTip()

    /** 投屏面板(详情页标题行入口复用播放器底栏同一条链路) */
    fun showCast()

    /** SAf 本地字幕选择结果回调 */
    fun onLocalSubtitlePicked(uri: Uri)

    fun hostResume()

    fun hostPause()

    fun hostDestroy()

    fun resumeFromMediaSession()

    fun pauseFromMediaSession()

    fun stopFromMediaSession()

    fun seekFromMediaSession(position: Long)
}
