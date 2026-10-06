package com.github.tvbox.osc.ui.activity

import android.text.TextUtils
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.Epginfo
import com.github.tvbox.osc.player.PlaybackTimes
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Date
import java.util.regex.Pattern

internal class LiveCatchupController(private val host: LivePlayActivity) {

    private var catchup: JsonObject? = null
    private var playUrl: String? = null
    private var shiyiTimeC = 0

    fun initLiveObj() {
        catchup = null
        host.logoUrl = null
        val position = ApiConfig.getLiveGroupIndex()
        val liveGroups = KV.get(HawkConfig.LIVE_GROUP_LIST, JsonArray())
        if (liveGroups == null || liveGroups.size() == 0 || position < 0 || position >= liveGroups.size()) {
            return
        }
        val livesOBJ = liveGroups.get(position).asJsonObject
        val type = if (livesOBJ.has("type")) livesOBJ.get("type").asString else "0"
        if (livesOBJ.has("catchup") && livesOBJ.get("catchup").isJsonObject) {
            catchup = livesOBJ.getAsJsonObject("catchup")
            LOG.i("echo-catchup :$catchup")
        }
        if (livesOBJ.has("logo")) {
            host.logoUrl = livesOBJ.get("logo").asString
        }
        if (type == "3") {
            var pyJar = ""
            if (livesOBJ.has("jar")) {
                pyJar = livesOBJ.get("jar").asString
            } else if (livesOBJ.has("api")) {
                pyJar = livesOBJ.get("api").asString
                val ext = if (livesOBJ.has("ext") &&
                    (livesOBJ.get("ext").isJsonObject || livesOBJ.get("ext").isJsonArray)
                ) {
                    livesOBJ.get("ext").toString()
                } else {
                    DefaultConfig.safeJsonString(livesOBJ, "ext", "")
                }
                LOG.i("echo-ext:$ext")
                if (ext.isNotEmpty()) pyJar = "$pyJar?extend=$ext"
            }
            ApiConfig.get().setLiveJar(pyJar)
        }
    }

    fun onEpgRowClicked(position: Int): Boolean {
        if (position == host.currentLiveLookBackIndex) return false
        val selectedData = host.epgdata.getOrNull(position) ?: return false
        if (selectedData.startdateTime == null || selectedData.enddateTime == null) return false
        val now = Date()
        if (now.before(selectedData.startdateTime)) return false
        if (now.after(selectedData.enddateTime) && !canCurrentChannelCatchup()) return false
        host.currentLiveLookBackIndex = position
        var switched = false
        if (!now.before(selectedData.startdateTime) && !now.after(selectedData.enddateTime)) {
            backToLiveFromEpg()
            switched = true
        } else if (canCurrentChannelCatchup()) {
            startCatchupReplay(selectedData)
            switched = true
        }
        host.epgVersion++
        return switched
    }

    private fun startCatchupReplay(epg: Epginfo) {
        val item = host.currentLiveChannelItem ?: return
        val videoView = host.mVideoView ?: return
        host.releasePlayerKernel()
        host.isSHIYI = true
        val shiyiUrl = buildCatchupUrl(item.url, epg)
        if (TextUtils.isEmpty(shiyiUrl)) return
        LOG.i("echo-回看地址playUrl :$shiyiUrl")
        playUrl = shiyiUrl
        videoView.setUrl(shiyiUrl, host.liveChannelHeader())
        videoView.start()
        shiyiTimeC = LiveEpgParser.getCatchupDurationSeconds(epg)
        host.tsDuration = PlaybackTimes.safeTimeMs(shiyiTimeC.toLong() * 1000)
        host.tsPosition = PlaybackTimes.safeTimeMs(videoView.currentPosition)
        host.overlay.startTimeshiftTicker()
        host.isBackState = true
        host.overlayVisible = true
        host.overlay.scheduleOverlayHide()
        host.epgVersion++
    }

    fun backToLiveFromEpg() {
        val item = host.currentLiveChannelItem ?: return
        val videoView = host.mVideoView ?: return
        host.overlay.stopTimeshiftTicker()
        host.releasePlayerKernel()
        host.isSHIYI = false
        host.isBackState = false
        host.overlayVisible = false
        videoView.setUrl(item.url, host.liveChannelHeader())
        videoView.start()
        host.epgVersion++
    }

    fun onTimeshiftSeek(progress: Float) {
        host.overlay.onTimeshiftSeek(progress)
    }

    fun onTimeshiftTogglePlay() {
        host.overlay.onTimeshiftTogglePlay()
    }

    private fun currentChannelHasCatchup(): Boolean {
        return host.currentLiveChannelItem != null && LiveEpgParser.hasCatchupSource(host.currentLiveChannelItem?.channelCatchup)
    }

    private fun currentCatchup(): JsonObject? {
        if (currentChannelHasCatchup()) return host.currentLiveChannelItem!!.channelCatchup
        return catchup
    }

    fun canCurrentChannelCatchup(): Boolean {
        val item = host.currentLiveChannelItem ?: return false
        val url = item.url
        val catchupObj = currentCatchup()
        if (LiveEpgParser.hasCatchupSource(catchupObj)) {
            val regex = LiveEpgParser.getCatchupValue(catchupObj, "regex")
            if (TextUtils.isEmpty(regex)) return true
            return try {
                url.contains(regex) || Pattern.compile(regex).matcher(url).find()
            } catch (ignored: Throwable) {
                false
            }
        }
        return url.contains("/PLTV/")
    }

    private fun buildCatchupUrl(url: String, epg: Epginfo?): String {
        if (TextUtils.isEmpty(url) || epg == null || epg.startdateTime == null || epg.enddateTime == null) return ""
        val catchupObj = currentCatchup()
        if (LiveEpgParser.hasCatchupSource(catchupObj)) {
            return LiveEpgParser.formatCatchupUrl(url, catchupObj!!, epg)
        }
        if (!url.contains("/PLTV/")) return ""
        val source = "?playseek=" + LiveEpgParser.formatCatchupTime(epg.startdateTime!!, "yyyyMMddHHmmss") +
                "-" + LiveEpgParser.formatCatchupTime(epg.enddateTime!!, "yyyyMMddHHmmss")
        return LiveEpgParser.appendCatchupUrl(url, "/PLTV/,/TVOD/", source)
    }

    fun loadEpgAfterChannelStarted() {
        host.epgController.loadAfterChannelStarted()
    }
}
