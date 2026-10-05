package com.github.tvbox.osc.util

import android.app.Activity
import android.content.Context

import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.ExoMediaPlayerFactory
import com.github.tvbox.osc.player.ExoPlayer
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.render.SurfaceRenderViewFactory
import com.github.tvbox.osc.player.thirdparty.Kodi
import com.github.tvbox.osc.player.thirdparty.MXPlayer
import com.github.tvbox.osc.player.thirdparty.ReexPlayer
import com.github.tvbox.osc.player.thirdparty.VlcPlayer

import android.text.TextUtils

import org.json.JSONException
import org.json.JSONObject

import java.text.DecimalFormat

import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.render.RenderViewFactory
import xyz.doikki.videoplayer.render.TextureRenderViewFactory

object PlayerHelper {
    @JvmStatic
    fun updateCfg(videoView: VideoView<*>?, playerCfg: JSONObject) {
        updateCfg(videoView, playerCfg, -1)
    }

    /** forcePlayerType 为历史遗留(内核对仅剩 EXO,不再有可强制的目标),保留入参以稳定既有调用方 */
    @JvmStatic
    fun updateCfg(videoView: VideoView<*>?, playerCfg: JSONObject, forcePlayerType: Int) {
        var renderType = KV.get(HawkConfig.PLAY_RENDER, 1)
        var exoDecode = KV.get(HawkConfig.EXO_DECODE, "硬解码") // i18n: keep
        var scale = KV.get(HawkConfig.PLAY_SCALE, 0)
        try {
            renderType = playerCfg.getInt("pr")
            scale = playerCfg.getInt("sc")
        } catch (e: JSONException) {
            LOG.e("PlayerHelper", e)
        }
        // exo 键单独用 optString 读(2026-09-17):不塞进上面的 try —— 该 try 遇第一个缺失键即中断,
        // 老播放记录/直播配置没有 exo 键时会把后面的 sc 一起吞掉
        exoDecode = playerCfg.optString("exo", exoDecode)
        // EXO 解码方式下发(2026-09-17):进程级静态位,与 videoView 实例无关(故不放在下面的判空块里),
        // 每次起播前按"本剧配置 → 全局设置"的有效值推一次
        val exoDecodeChanged = applyExoDecode(exoDecode)
        val playerFactory = ExoMediaPlayerFactory.create()
        var renderViewFactory: RenderViewFactory? = null
        when (renderType) {
            1 -> renderViewFactory = SurfaceRenderViewFactory.create()
            else -> renderViewFactory = TextureRenderViewFactory.create()
        }
        if (videoView != null) {
            @Suppress("UNCHECKED_CAST")
            (videoView as VideoView<ExoPlayer>).setPlayerFactory(playerFactory)
            if (videoView is MyVideoView) {
                // EXO 解码方式变了且当前还活着一个 EXO 内核(换集复用路径):media3 不会重选解码器,
                // 只改静态位不生效 —— 标记本次起播必须重建内核(见 MyVideoView.consumeKernelRebuildRequired)
                if (exoDecodeChanged && videoView.mediaPlayer is ExoPlayer) {
                    videoView.requireKernelRebuild()
                    LOG.i("echo-exo-decode-changed: rebuild kernel on next start")
                }
            }
            videoView.setRenderViewFactory(renderViewFactory)
            videoView.setScreenScaleType(scale)
        }
    }

    private fun applyExoDecode(exoDecode: String): Boolean {
        val prefer = "软解码" == exoDecode // i18n: keep
        if (ExoPlayer.isPreferSoftwareDecode() == prefer) return false
        ExoPlayer.setPreferSoftwareDecode(prefer)
        return true
    }

    /**
     * 存活内核**已生效**的解码方式是否与 cfg 目标值一致;静态位只在起播链路下发,而 media3 不给复用内核重选解码器 ——
     * 不一致就只能重建内核(D6 同片接管这类不走起播的路径据此判断)。
     */
    @JvmStatic
    fun isExoDecodeApplied(playerCfg: JSONObject?): Boolean {
        val exoDecode = if (playerCfg == null) null else playerCfg.optString("exo", "硬解码") // i18n: keep
        return isExoDecodeApplied(exoDecode, ExoPlayer.isPreferSoftwareDecode())
    }

    /** 上一条的口径本体(exo 值只认"软解码",缺键/空串按硬解);独立出来供 JVM 单测锁真值表 */
    @JvmStatic
    fun isExoDecodeApplied(exoDecode: String?, preferSoftwareDecode: Boolean): Boolean {
        return ("软解码" == exoDecode) == preferSoftwareDecode // i18n: keep
    }

    @JvmStatic
    fun isLocalProxyUrl(url: String?): Boolean {
        if (url == null) return false
        return url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1")
                || url.startsWith("http://localhost") || url.startsWith("https://localhost")
    }

    @JvmStatic
    fun extractPlayHeaders(playResult: JSONObject?): HashMap<String, String>? {
        if (playResult == null) return null
        val headers = HashMap<String, String>()
        appendJsonHeaders(headers, playResult.opt("header"))
        appendJsonHeaders(headers, playResult.opt("headers"))
        return if (headers.isEmpty()) null else headers
    }

    /** 合并单个 header(s) 字段:接受 JSONObject 或 JSON 文本;非法内容静默跳过(保持旧行为) */
    @JvmStatic
    fun appendJsonHeaders(headers: HashMap<String, String>?, rawHeaders: Any?) {
        if (headers == null || rawHeaders == null || rawHeaders === JSONObject.NULL) return
        try {
            var json: JSONObject? = null
            if (rawHeaders is JSONObject) {
                json = rawHeaders
            } else if (rawHeaders is String) {
                val text = (rawHeaders as String).trim { it <= ' ' }
                if (!TextUtils.isEmpty(text)) {
                    json = JSONObject(text)
                }
            }
            if (json == null) return
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!TextUtils.isEmpty(key)) {
                    headers[key] = json.optString(key, "")
                }
            }
        } catch (th: Throwable) {
            LOG.e("PlayerHelper", "play headers parse failed", th)
        }
    }

    /** 播放器名;每次调用重取文案(不缓存字符串 —— 缓存会让切语言后停在旧语言) */
    @JvmStatic
    fun getPlayerName(playType: Int): String {
        return when (playType) {
            10 -> str(R.string.player_mx)
            11 -> str(R.string.player_reex)
            12 -> str(R.string.player_kodi)
            13 -> str(R.string.player_nearby_tvbox)
            14 -> str(R.string.player_vlc)
            else -> str(R.string.player_exo)
        }
    }

    @JvmStatic
    fun getPlayersInfo(): HashMap<Int, String> {
        val playersInfo = HashMap<Int, String>()
        for (type in intArrayOf(2, 10, 11, 12, 13, 14)) {
            playersInfo[type] = getPlayerName(type)
        }
        return playersInfo
    }

    private var mPlayersExistInfo: HashMap<Int, Boolean>? = null

    @JvmStatic
    fun invalidatePlayersExistInfo() {
        mPlayersExistInfo = null
    }

    @JvmStatic
    fun getPlayersExistInfo(): HashMap<Int, Boolean> {
        if (mPlayersExistInfo == null) {
            val playersExist = HashMap<Int, Boolean>()
            playersExist[2] = true
            playersExist[10] = MXPlayer.getPackageInfo() != null
            playersExist[11] = ReexPlayer.getPackageInfo() != null
            playersExist[12] = Kodi.getPackageInfo() != null
            playersExist[13] = RemoteTVBox.getAvalible() != null
            playersExist[14] = VlcPlayer.getPackageInfo() != null
            mPlayersExistInfo = playersExist
        }
        return mPlayersExistInfo!!
    }

    @JvmStatic
    fun getPlayerExist(playType: Int): Boolean {
        val playersExistInfo = getPlayersExistInfo()
        if (playersExistInfo.containsKey(playType)) {
            return playersExistInfo[playType]!!
        } else {
            return false
        }
    }

    @JvmStatic
    fun getExistPlayerTypes(): ArrayList<Int> {
        val playersExistInfo = getPlayersExistInfo()
        val existPlayers = ArrayList<Int>()
        for (playerType in playersExistInfo.keys) {
            if (playersExistInfo[playerType]!!) {
                existPlayers.add(playerType)
            }
        }
        return existPlayers
    }

    @JvmStatic
    fun runExternalPlayer(playerType: Int, activity: Activity, url: String, title: String, subtitle: String, headers: HashMap<String, String>?): Boolean {
        return runExternalPlayer(playerType, activity, url, title, subtitle, headers)
    }

    @JvmStatic
    fun runExternalPlayer(playerType: Int, activity: Activity, url: String, title: String, subtitle: String, headers: HashMap<String, String>?, progress: Long): Boolean {
        var callResult = false
        when (playerType) {
            10 -> {
                callResult = MXPlayer.run(activity, url, title, subtitle, headers)
            }
            11 -> {
                callResult = ReexPlayer.run(activity, url, title, subtitle, headers)
            }
            12 -> {
                callResult = Kodi.run(activity, url, title, subtitle, headers)
            }
            13 -> {
                callResult = RemoteTVBox.run(activity, url, title, subtitle, headers)
            }
            14 -> {
                callResult = VlcPlayer.run(activity, url, title, subtitle, progress)
            }
        }
        return callResult
    }

    @JvmStatic
    fun getRenderName(renderType: Int): String {
        return if (renderType == 1) {
            "SurfaceView"
        } else {
            "TextureView"
        }
    }

    /** 画面缩放名;每次调用重取文案(不缓存字符串 —— 缓存会让切语言后停在旧语言) */
    @JvmStatic
    fun getScaleName(screenScaleType: Int): String {
        return when (screenScaleType) {
            VideoView.SCREEN_SCALE_16_9 -> "16:9"
            VideoView.SCREEN_SCALE_4_3 -> "4:3"
            VideoView.SCREEN_SCALE_MATCH_PARENT -> str(R.string.player_scale_fill)
            VideoView.SCREEN_SCALE_ORIGINAL -> str(R.string.player_scale_origin)
            VideoView.SCREEN_SCALE_CENTER_CROP -> str(R.string.player_scale_crop)
            else -> str(R.string.common_default)
        }
    }

    private fun str(resId: Int): String {
        val app: Context? = AppContextHolder.context()
        return if (app == null) "" else LanguageManager.localized(app).getString(resId)
    }

    @JvmStatic
    fun getDisplaySpeed(speed: Long, show: Boolean): String {
        return if (speed > 1048576)
            DecimalFormat("#.00").format(speed / 1048576.0) + "MB/s"
        else if (speed > 1024)
            (speed / 1024).toString() + "KB/s"
        else
            if (speed > 0) speed.toString() + "B/s" else (if (show) "0B/s" else "")
    }
}
