package com.github.tvbox.osc.bean

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.PlayerHelper
import org.json.JSONException
import org.json.JSONObject
import com.github.tvbox.osc.player.MyVideoView

class LivePlayerManager {
    @JvmField
    var defaultPlayerConfig: JSONObject = JSONObject()

    @JvmField
    var currentPlayerConfig: JSONObject? = null

    fun init(videoView: MyVideoView) {
        try {
            defaultPlayerConfig.put("exo", KV.get(HawkConfig.EXO_DECODE, "硬解码")) // i18n: keep
            defaultPlayerConfig.put("pr", KV.get(HawkConfig.PLAY_RENDER, 1))
            defaultPlayerConfig.put("sc", KV.get(HawkConfig.LIVE_PLAY_SCALE, 0))
        } catch (e: JSONException) {
            LOG.e("LivePlayerManager", e)
        }
        getDefaultLiveChannelPlayer(videoView)
    }

    fun getDefaultLiveChannelPlayer(videoView: MyVideoView) {
        PlayerHelper.updateCfg(videoView, defaultPlayerConfig)
        try {
            currentPlayerConfig = JSONObject(defaultPlayerConfig.toString())
        } catch (e: JSONException) {
            LOG.e("LivePlayerManager", e)
        }
    }

    private fun currentOrDefaultConfig(): JSONObject {
        return currentPlayerConfig ?: defaultPlayerConfig
    }

    val livePlayerType: Int
        get() {
            val decode = currentOrDefaultConfig().optString("exo", KV.get(HawkConfig.EXO_DECODE, "硬解码")) // i18n: keep
            return if ("软解码" == decode) 1 else 0 // i18n: keep
        }

    val livePlayerScale: Int
        get() = currentOrDefaultConfig().optInt("sc", 0)

    fun changeLivePlayerType(videoView: MyVideoView, playerType: Int) {
        var playerConfig: JSONObject
        try {
            playerConfig = JSONObject(currentOrDefaultConfig().toString())
        } catch (e: JSONException) {
            playerConfig = JSONObject()
        }
        try {
            val decode = if (playerType == 1) "软解码" else "硬解码" // i18n: keep
            playerConfig.put("exo", decode) // i18n: keep
            defaultPlayerConfig.put("exo", decode) // i18n: keep
        } catch (e: JSONException) {
            LOG.e("LivePlayerManager", e)
        }
        PlayerHelper.updateCfg(videoView, playerConfig)
        currentPlayerConfig = playerConfig
    }

    fun changeLivePlayerScale(videoView: MyVideoView, playerScale: Int) {
        videoView.setScreenScaleType(playerScale)
        KV.put(HawkConfig.LIVE_PLAY_SCALE, playerScale)

        var playerConfig: JSONObject
        try {
            playerConfig = JSONObject(currentOrDefaultConfig().toString())
        } catch (e: JSONException) {
            playerConfig = JSONObject()
        }
        try {
            playerConfig.put("sc", playerScale)
            defaultPlayerConfig.put("sc", playerScale)
        } catch (e: JSONException) {
            LOG.e("LivePlayerManager", e)
        }

        currentPlayerConfig = playerConfig
    }
}
