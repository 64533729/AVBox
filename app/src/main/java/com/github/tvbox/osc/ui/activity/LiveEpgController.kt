package com.github.tvbox.osc.ui.activity

import android.os.Handler
import android.os.Looper
import com.github.tvbox.osc.bean.Epginfo
import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.net.OkGoHelper
import com.github.tvbox.osc.util.EpgUtil
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Hashtable
import java.util.Locale
import java.util.TimeZone
import org.json.JSONException

internal class LiveEpgController(private val host: Host) {

    internal interface Host {
        fun currentChannel(): LiveChannelItem?

        fun currentChannelHasLogo(): Boolean

        fun onEpgListChanged(list: ArrayList<Epginfo>)

        fun onEpgSettled()
    }

    companion object {
        private const val EPG_LOAD_DELAY = 1200L
        private const val DEFAULT_EPG_ADDRESS = "http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}"
    }

    private val mHandler = Handler(Looper.getMainLooper())
    private val hsEpg = Hashtable<String, ArrayList<Epginfo>>()
    private var epgStringAddress = ""
    private var epgDayPresented = ""
    private var firstLiveEpgLoad = true

    private val mLoadEpgRun = Runnable {
        if (host.currentChannel() != null) getEpg(Date())
    }

    fun reloadAddress() {
        val userEpgAddress: String = KV.get(HawkConfig.EPG_URL, "")
        epgStringAddress = if (userEpgAddress.trim { it <= ' ' }.length >= 5) {
            userEpgAddress.trim { it <= ' ' }
        } else {
            DEFAULT_EPG_ADDRESS
        }
    }

    fun setDayKey(dayKey: String) {
        epgDayPresented = dayKey
    }

    fun cachedEpg(channelName: String): ArrayList<Epginfo>? = hsEpg[channelName + "_" + epgDayPresented]

    fun cancelPending() {
        mHandler.removeCallbacks(mLoadEpgRun)
    }

    fun cancelAll() {
        mHandler.removeCallbacksAndMessages(null)
    }

    fun loadAfterChannelStarted() {
        mHandler.removeCallbacks(mLoadEpgRun)
        if (!hasEpgAddress()) {
            host.onEpgListChanged(ArrayList())
            return
        }
        if (hasCurrentEpgCache()) {
            firstLiveEpgLoad = false
            return
        }
        if (firstLiveEpgLoad) {
            firstLiveEpgLoad = false
            mHandler.postDelayed(mLoadEpgRun, EPG_LOAD_DELAY)
        } else {
            getEpg(Date())
        }
    }

    fun getEpg(date: Date) {
        val channel = host.currentChannel() ?: return
        val channelNameStr = channel.channelName ?: return
        val channelNameReal = LiveEpgParser.normalizeEpgChannelName(LiveEpgParser.getFirstPartBeforeSpace(channelNameStr) ?: "")
        val timeFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("GMT+8:00")
        }
        var epgTagName = channelNameReal
        if (!host.currentChannelHasLogo()) {
            val epgInfo = EpgUtil.getEpgInfo(channelNameReal)
            if (epgInfo != null && epgInfo[1].isNotEmpty()) {
                epgTagName = epgInfo[1]
            }
        }
        if (!hasEpgAddress()) {
            host.onEpgListChanged(ArrayList())
            return
        }
        val epgQueryNames = LiveEpgParser.buildEpgQueryNames(channelNameStr, channelNameReal, epgTagName)
        val url = LiveEpgParser.buildEpgUrl(epgStringAddress, epgQueryNames[0], date, timeFormat)
        val savedEpgKey = channelNameStr + "_" + epgDayPresented
        if (hsEpg.containsKey(savedEpgKey)) {
            showEpg(hsEpg[savedEpgKey])
            host.onEpgSettled()
            return
        }
        host.onEpgListChanged(ArrayList())
        requestEpg(url, date, channelNameReal, epgTagName, savedEpgKey, epgQueryNames, timeFormat, 0)
    }

    private fun requestEpg(
        url: String,
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ) {
        var client = OkGoHelper.getDefaultClient()
        if (client == null) client = com.github.catvod.net.OkHttp.client()
        client.newCall(okhttp3.Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                mHandler.post {
                    onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.code != 200) {
                    response.close()
                    mHandler.post {
                        onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)
                    }
                    return
                }
                val body = try {
                    response.body.string()
                } finally {
                    response.close()
                }
                mHandler.post {
                    onEpgRequestResponse(body, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)
                }
            }
        })
    }

    private fun onEpgRequestFailure(
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ) {
        if (!isCurrentEpgRequest(savedEpgKey)) return
        if (requestNextEpgQueryName(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return
        }
        if (requestDefaultEpgOnFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return
        }
        host.onEpgListChanged(ArrayList())
    }

    private fun onEpgRequestResponse(
        paramString: String?,
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ) {
        if (!isCurrentEpgRequest(savedEpgKey)) return
        if (paramString.isNullOrEmpty() || paramString.trim { it <= ' ' }.isEmpty()) {
            host.onEpgListChanged(ArrayList())
            return
        }
        LOG.i("echo-epgTagName:$channelNameReal")
        var arrayList = ArrayList<Epginfo>()
        try {
            if (LiveEpgParser.isXmlEpgResponse(paramString)) {
                arrayList = LiveEpgParser.parseXmlEpg(paramString, finalEpgTagName, date)
            } else if (paramString.contains("epg_data") || paramString.trim { it <= ' ' }.startsWith("{")) {
                arrayList = LiveEpgParser.parseJsonEpg(paramString, date)
            }
        } catch (jsonException: JSONException) {
            LOG.e("LiveEpgController", jsonException)
        }
        if (arrayList.isEmpty() && requestNextEpgQueryName(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return
        }
        hsEpg[savedEpgKey] = arrayList
        if (!isCurrentEpgRequest(savedEpgKey)) return
        showEpg(arrayList)
        host.onEpgSettled()
    }

    private fun requestDefaultEpgOnFailure(
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ): Boolean {
        if (DEFAULT_EPG_ADDRESS == epgStringAddress || queryIndex >= epgQueryNames.size) {
            return false
        }
        val fallbackUrl = LiveEpgParser.buildEpgUrl(DEFAULT_EPG_ADDRESS, epgQueryNames[0], date, timeFormat)
        LOG.i("echo-epg fallback default address")
        requestEpg(fallbackUrl, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, epgQueryNames.size)
        return true
    }

    private fun requestNextEpgQueryName(
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ): Boolean {
        if (!LiveEpgParser.isTemplateEpgAddress(epgStringAddress) || queryIndex + 1 >= epgQueryNames.size) {
            return false
        }
        val nextIndex = queryIndex + 1
        val nextUrl = LiveEpgParser.buildEpgUrl(epgStringAddress, epgQueryNames[nextIndex], date, timeFormat)
        LOG.i("echo-epg retry query name:" + epgQueryNames[nextIndex])
        requestEpg(nextUrl, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, nextIndex)
        return true
    }

    private fun showEpg(arrayList: ArrayList<Epginfo>?) {
        host.onEpgListChanged(if (arrayList != null && arrayList.isNotEmpty()) arrayList else ArrayList())
    }

    private fun hasEpgAddress(): Boolean {
        return epgStringAddress.isNotEmpty() && epgStringAddress.trim { it <= ' ' }.isNotEmpty()
    }

    private fun hasCurrentEpgCache(): Boolean {
        val channel = host.currentChannel() ?: return false
        return hsEpg.containsKey(channel.channelName + "_" + epgDayPresented)
    }

    private fun isCurrentEpgRequest(savedEpgKey: String): Boolean {
        val channel = host.currentChannel() ?: return false
        return savedEpgKey == channel.channelName + "_" + epgDayPresented
    }
}
