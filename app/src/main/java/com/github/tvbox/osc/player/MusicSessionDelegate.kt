package com.github.tvbox.osc.player

import android.text.TextUtils
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.util.LOG
import org.json.JSONArray
import org.json.JSONObject
import java.util.HashMap

/**
 * 音乐会话与媒体通知:有音频轨就维护会话/通知(影视同样),纯音频才保留后台播放;
 * 另含封面兜底、弹幕地址、清晰度切换。
 */
class MusicSessionDelegate(private val host: Host) {

    interface Host {
        fun view(): PlaybackViewBridge?

        fun attemptState(): PlaybackAttemptState

        fun timeouts(): PlaybackTimeouts

        fun vod(): VodInfo?

        fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries?

        fun quality(): JSONObject?

        fun isStartedPlayState(state: PlayState): Boolean

        fun retryAfterStartedError(): Boolean

        fun initParse(flag: String?, useParse: Boolean, playUrl: String, url: String)

        fun playUrl(url: String, headers: HashMap<String, String>?)
    }

    /** 纯音频封面地址(影视绝不设置:否则视频被压成海报) */
    private var playArtwork: String? = null

    /** 当前集的弹幕地址(取流结果或弹幕搜索的产物;退页面重进时页面要重新拿一份) */
    private var playDanmu: String? = null

    /** 取流结果里的封面(音乐页等"后挂载页面"读它拿封面;通知用的 [playArtwork] 只允许纯音频) */
    private var currentArtwork: String? = null

    /** 换内容时的封面清场(同片接管不清,由调用方判) */
    fun clearArtworks() {
        playArtwork = null
        currentArtwork = null
    }

    /**
     * 本集播完会自动续下一集时提前登记切换中(须在 [PlaybackController.play] 之前调)。
     *
     * ⚠️ 事件同步派发且引擎监听先注册,故 COMPLETED 到达时 [updateMusicSession] 先跑:
     * 此刻仍为 false 就会按"播完"撤掉会话与通知(表现:一首放完通知消失,下一首在放却没通知)。
     * 自然播完那条路径靠 [handlePendingCompletionDrop] 延后一拍判定,本方法顺带撤销该消息。
     */
    fun beginSwitchPlayback() {
        host.attemptState().switchingPlayback = true
        host.timeouts().cancelPendingCompletionDrop()
    }

    fun playArtwork(): String? = playArtwork

    fun currentArtwork(): String? = currentArtwork

    fun setCurrentArtwork(artwork: String?) {
        currentArtwork = artwork
    }

    fun playDanmu(): String? = playDanmu

    fun setPlayDanmu(danmu: String?) {
        playDanmu = danmu ?: ""
    }

    /** 起播失败/换源点击即停:清会话标记并停掉通知 */
    fun stopMusicSessionForFailedPlayback() {
        host.attemptState().clearSessionFlags()
        host.timeouts().cancelPendingCompletionDrop()
        stopMusicSession()
    }

    /** 停掉媒体会话与前台通知 */
    fun stopMusicSession() {
        val view = host.view() ?: return
        PlaybackService.stopSession(view.context(), view.playbackHost())
    }

    /** 退后台是否保留播放:本次会话确认过纯音频才保留(粘滞标记见 [PlaybackAttemptState.audioOnlyConfirmed]) */
    fun isConfirmedAudioOnly(): Boolean {
        return java.lang.Boolean.TRUE == isAudioOnlyPlayback() || host.attemptState().audioOnlyConfirmed
    }

    /**
     * 播放状态回调里的"音乐会话"部分(页面状态监听里调用)。
     *
     * @return true = 切换集期间本集已播完(仅保留会话,调用方应直接 return,不再走弹幕等后续逻辑)
     */
    fun handlePlayStateForMusicSession(playState: PlayState): Boolean {
        val st = host.attemptState()
        if (st.switchingPlayback) {
            if (playState == PlayState.COMPLETED) {
                LOG.i("echo-music keep session while resolving next episode")
                return true
            } else if (playState == PlayState.ERROR) {
                // 只解除"切换中"抑制:在此清 audioPlayback 会让后续既不能重试也不能重建会话
                st.switchingPlayback = false
            } else if (host.isStartedPlayState(playState)) {
                // 起播成功:有音频轨则维护会话/通知(影视同样,见 updateMusicSession 的语义拆分)
                if (hasPlayableAudio() || st.audioPlayback) {
                    st.switchingPlayback = false
                    st.audioPlayback = true
                }
            }
        }
        if (!st.switchingPlayback) {
            if (playState == PlayState.COMPLETED) {
                // ⚠️ **不能在此直接 updateMusicSession()**。
                // 引擎的状态监听器注册在页面之前(见 PlaybackEngine.createPlayerView 与
                // MusicPlayerActivity.initView),所以 COMPLETED 到达时**本方法总是先跑**,
                // 而"要续播下一集"的登记(beginSwitchPlayback)在页面监听器里(onSongCompleted
                // → playAt/replayCurrent),此刻尚未执行 ⇒ switchingPlayback 读到的必然是 false,
                // 于是按"播完"撤了会话。后果不是"少一条通知"这么轻:
                //   ① stopForeground(true) 撤掉唯一的前台通知;
                //   ② 服务随之失去前台身份;紧接着下一集起播要重新进前台,而此刻 App 通常已在后台
                //      ⇒ 系统拒绝(真机原文 Service.startForeground() not allowed due to
                //      mAllowStartForeground false)⇒ **通知永久回不来**;
                //   ③ audioPlayback 被清 ⇒ 退后台判定也不再豁免。
                // 真机复现:后台播完一首自动切歌,19 秒后
                // 通知消失、连两次 startForeground 被拒、回到页面点击无反应。
                // 改为**延后一拍**再判:让同一次状态分发里页面的 beginSwitchPlayback() 有机会先执行。
                host.timeouts().cancelPendingCompletionDrop()
                host.timeouts().armPendingCompletionDrop()
                return false
            }
            updateMusicSession()
        }
        return false
    }

    /**
     * 本集播完后的**延后一拍**撤会话判定(配合 [handlePlayStateForMusicSession] 的播完分支)。
     *
     * 执行时页面侧的收尾已经跑完,可据三件事决定是否真的撤会话:
     * ① 页面是否登记了"切换中"([beginSwitchPlayback] 会清掉本消息);
     * ② 内核是否已经进入新的起播态(实时读,不依赖事件到达顺序);
     * ③ 页面是否已不存活。
     */
    fun handlePendingCompletionDrop() {
        val st = host.attemptState()
        if (st.switchingPlayback) {
            LOG.i("echo-music completion drop skipped: page registered switching")
            return
        }
        val view = host.view() ?: return
        // ⚠️ 必须与 updateMusicSession 同一道支持性前置:旧路径的撤会话
        // 是经 updateMusicSession 走的,那里有 `if (!PlaybackService.isSupported(context)) return;`。
        // 本方法直接调 stopSession 会绕过它 —— 在 isSupported()==false 的设备(API<26)
        // 上就变成"每次播完都去 release 一个 onCreate 建好、与页面同生命周期的 MediaSessionCompat
        // 并把 owner 置空"(后续用法都有判空,不会崩,但媒体键会话被无谓拆掉),属本轮引入的行为变化。
        if (!PlaybackService.isSupported(view.context())) return
        val state = view.playState()
        if (host.isStartedPlayState(state)) {
            LOG.i("echo-music completion drop skipped: kernel already started, state=$state")
            return
        }
        // 页面**已销毁**时让位给既有收尾路径(页面退出会走 onHostDestroy/stopPlaybackForPageExit,
        // 那两条自己撤会话并放锁):这里不再插手,以免与它们重复撤会话、或撤在"随后 attach 的新会话"上。
        // ⚠️ 本判据**不**负责"防止迟到消息打到新会话" —— 那是各会话边界 cancelPendingCompletionDrop 的职责:
        // startSession / beginNewPlay / beginSwitchPlayback / stopMusicSessionForFailedPlayback /
        // stopPlaybackForPageExit / onHostDestroy。
        if (!view.isPageAlive()) {
            LOG.i("echo-music completion drop skipped: page not alive, state=$state")
            return
        }
        // 页面还活着且内核仍停在"播完"⇒ 这是真的没人接续(队列末尾 / 非音乐页的影视播完),照旧撤会话。
        // 这一步与原实现等价(原实现是在 COMPLETED 时同步走 updateMusicSession 的撤会话分支)。
        if (state != PlayState.COMPLETED) {
            LOG.i("echo-music completion drop skipped: state moved on, state=$state")
            return
        }
        LOG.i("echo-music session drop after completed (deferred): no next episode registered")
        PlaybackService.stopSession(view.context(), view.playbackHost())
        st.audioPlayback = false
    }

    /**
     * 当前媒体是否有音频轨(与"是否纯音频"是两个判定)。
     *
     * 拆分的理由:两件事被混在了一个判定里 ——
     * ① **要不要建 MediaSession / 前台服务通知**(用户要求播放影视也能下拉看到)→ 只要**有音频轨**即可;
     * ② **退后台是否保持播放**(见 [PlaybackController.isConfirmedAudioOnly])→ 只有**纯音频**才保留。
     */
    private fun hasPlayableAudio(): Boolean {
        val trackInfo = currentTrackInfo()
        return trackInfo != null && trackInfo.getAudio().isNotEmpty()
    }

    /**
     * 是否为「纯音频」——**三态**:TRUE=有音轨且无视频轨、FALSE=确定是影视、**null=取不到轨道信息(未知)**。
     *
     * 「未知」必须与「假」分开(继承自旧的 `getAudioOnlyPlayback()` 语义)。各调用点的正确用法:
     * - [isConfirmedAudioOnly] 退后台是否保持播放:用 `Boolean.TRUE.equals(...)` —— 只有确定是纯音频才不停,
     *   null 落到 pause 分支(与迁移前一致);
     * - 播放器封面兜底(见 [updateMusicSession]):同一口径 —— 只有确定是纯音频才显示封面。
     */
    private fun isAudioOnlyPlayback(): Boolean? {
        val trackInfo = currentTrackInfo()
        if (trackInfo == null || trackInfo.getAudio().isEmpty()) return null
        return trackInfo.getVideo().isEmpty()
    }

    /** 取当前播放器的轨道信息;拿不到(未起播/不支持)返回 null */
    private fun currentTrackInfo(): TrackInfo? {
        val view = host.view() ?: return null
        try {
            val mediaPlayer = view.mediaPlayer()
            if (mediaPlayer is ExoPlayer) {
                return mediaPlayer.getTrackInfo()
            }
        } catch (ignored: Throwable) {
            LOG.d("PlaybackController", "track info unavailable")
        }
        return null
    }

    /**
     * 渲染类型与轨道类型对齐(双向兜底):
     * 确认纯音频 → 热切 TextureView(URL 预判漏网的无后缀音乐直链);确认有视频轨 → 按用户设置恢复渲染视图
     * (回放走复用路径时 fork 的 replay 不重建 RenderView,纯音频热切后播视频集会一直留在 TextureView)。
     * 轨道信息未知(null:未起播/不支持)时两边都不动,避免误切。
     */
    fun ensureAudioOnlyRender() {
        val view = host.view() ?: return
        val audioOnly = isAudioOnlyPlayback()
        if (java.lang.Boolean.TRUE == audioOnly) {
            view.switchRenderToTexture()
        } else if (java.lang.Boolean.FALSE == audioOnly) {
            view.ensureRenderViewMatchesConfig()
        }
    }

    /**
     * 维护媒体会话与前台通知(有音频轨就维护,影视/音乐一视同仁)。
     *
     * ⚠️ 封面只给「纯音频」兜底,绝不能给影视占位(一旦 setArtwork,视频被压成海报):①实时读轨道确定纯音频
     * (**不能用** [PlaybackAttemptState.audioOnlyConfirmed] 粘滞标记);②画面未就绪;③audioPlayback。
     *
     * audioPlayback 在此**只置位不清零**(清零只在会话边界),否则一次读取失败会让通知永久消失。
     */
    fun updateMusicSession() {
        val view = host.view() ?: return
        if (!view.isPageAlive()) return
        val context = view.context()
        if (!PlaybackService.isSupported(context)) return
        val st = host.attemptState()
        if (st.switchingPlayback) return
        val trackInfo = currentTrackInfo()
        val hasAudio: Boolean? = trackInfo != null && trackInfo.getAudio().isNotEmpty()
        val audioOnly: Boolean? = if (trackInfo == null || trackInfo.getAudio().isEmpty()) {
            null
        } else {
            trackInfo.getVideo().isEmpty()
        }
        // 只置位不清零:"读到轨道列表但 audio 为空"≠"没有音频"(Exo 在 IDLE/重取流期、音频渲染器
        // 未选中时同样给空 audio 列表),据此清零会让通知与退后台判定双双失效。清零只在会话边界。
        if (java.lang.Boolean.TRUE == hasAudio) {
            st.audioPlayback = true
            if (java.lang.Boolean.TRUE == audioOnly) st.audioOnlyConfirmed = true
        }
        val state = view.playState()
        LOG.i(
            "echo-music session gate: state=" + state + " playing=" + view.isPlaying()
                + " hasAudio=" + hasAudio + " audioOnly=" + audioOnly + " audioPlayback=" + st.audioPlayback
                + " audioOnlyConfirmed=" + st.audioOnlyConfirmed + " switching=" + st.switchingPlayback
                + " pos=" + view.currentPosition()
        )
        if (st.audioPlayback && java.lang.Boolean.TRUE == audioOnly
            && !host.isStartedPlayState(state)
            && TextUtils.isEmpty(playArtwork) && host.vod() != null && !TextUtils.isEmpty(host.vod()!!.pic)
        ) {
            playArtwork = host.vod()!!.pic
            view.setArtwork(playArtwork!!)
        }
        if ((state == PlayState.ERROR && st.audioOnlyConfirmed) && host.retryAfterStartedError()) {
            // 已确认纯音频的会话遇可重试错误:同地址重播一次并**保留会话**(撤会话会连锁清 audioPlayback,
            // 而重建只认 STATE_PLAYING 事件 ⇒ 后台失败后点播放再也不出通知)。重试无路可走则照旧撤会话。
            // ⚠️ 判据不得放宽成 audioPlayback:影视也带音轨,会抢在详情页 errorWithRetry 之前
            // 消耗掉 hasRetriedAfterStart(引擎状态监听先注册 ⇒ 总是本方法先跑),使影视丢失"同地址重播"这一档。
            LOG.i("echo-music session keep: auto retry after started error (audio-only)")
            return
        }
        if (host.vod() == null || !st.audioPlayback
            || state == PlayState.ERROR
            || state == PlayState.COMPLETED
        ) {
            LOG.i(
                "echo-music session drop: vod=" + (host.vod() != null) + " audioPlayback=" + st.audioPlayback
                    + " state=" + state + " (ERROR=" + PlayState.ERROR
                    + " COMPLETED=" + PlayState.COMPLETED + ")"
            )
            PlaybackService.stopSession(context, view.playbackHost())
            st.audioPlayback = false
            return
        }
        // 通知权限兜底(启动时已在 MainActivity 申请过一次):覆盖"启动那次被拒、后来手动开启"的路径
        view.requestNotificationPermission()
        val currentSeries = host.currentSeries(host.vod()!!.playFlag, host.vod()!!.playIndex)
        val episode = if (currentSeries == null || TextUtils.isEmpty(currentSeries.name)) "" else currentSeries.name
        PlaybackService.updateSession(
            context, view.playbackHost(),
            if (TextUtils.isEmpty(host.vod()!!.name)) "TVBox" else host.vod()!!.name,
            episode, host.vod()!!.pic, view.currentPosition(), view.duration(), view.isPlaying()
        )
    }

    /** 切换清晰度(多清晰度源 url 数组下标;取流链路与首次起播一致) */
    fun selectQuality(position: Int): Boolean {
        val quality = host.quality() ?: return false
        return try {
            val urls = JSONArray(quality.optString("url"))
            val url = urls.optString(position * 2 + 1)
            if (TextUtils.isEmpty(url)) return false
            val playUrl = quality.optString("playUrl", "")
            val flag = quality.optString("flag")
            val parse = quality.optString("parse", "1") == "1"
            val jx = quality.optString("jx", "0") == "1"
            val headers = PlaybackController.extractHeaders(quality)
            if (parse || jx) {
                val flags = ApiConfig.get().getVipParseFlags() ?: mutableListOf()
                val userJxList = (playUrl.isEmpty() && flags.contains(flag)) || jx
                host.initParse(flag, userJxList, playUrl, url)
            } else {
                host.view()?.showParse(false)
                host.playUrl(playUrl + url, headers)
            }
            true
        } catch (th: Throwable) {
            false
        }
    }
}
