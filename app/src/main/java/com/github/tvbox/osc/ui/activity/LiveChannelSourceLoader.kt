package com.github.tvbox.osc.ui.activity

import android.os.Handler
import android.widget.Toast
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.LiveChannelGroup
import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import java.util.ArrayList

internal class LiveChannelSourceLoader(
    private val host: LivePlayActivity,
    private val handler: Handler,
) {

    private var refreshingLiveChannelList = false
    private var loadingLiveConfigOnEnter = false
    private var pendingLiveRefreshChannelName: String? = null
    private var pendingLiveRefreshSourceIndex = -1

    private val proxyLoader = LiveProxyLoader(object : LiveProxyLoader.Host {
        override fun isRefreshing(): Boolean = refreshingLiveChannelList

        override fun onLoading() {
            host.pageState = PageState.LOADING
        }

        override fun onEmpty() {
            setEmptyLiveChannelList()
        }

        override fun onGroupsLoaded(groups: List<LiveChannelGroup>) {
            applyLiveChannelGroups(groups)
        }
    })

    fun cancelAll() {
        proxyLoader.cancelAll()
    }

    fun getLiveChannels(groupIndex: Int): ArrayList<LiveChannelItem>? {
        val group = host.liveChannelGroupList.getOrNull(groupIndex) ?: return null
        return if (!host.isNeedInputPassword(groupIndex)) group.liveChannels else ArrayList()
    }

    fun initLiveChannelList() {
        if (ApiConfig.get().shouldReloadLiveConfig()) {
            loadLiveConfigOnEnter()
            return
        }
        val list = ApiConfig.get().channelGroupList
        if (list.isEmpty()) {
            loadLiveConfigOnEnter()
            return
        }
        host.catchupController.initLiveObj()
        if (list.size == 1 && list[0].groupName.orEmpty().startsWith("http://127.0.0.1")) {
            loadProxyLives(list[0].groupName.orEmpty())
        } else {
            applyLiveChannelGroups(ArrayList(list))
        }
    }

    fun loadLiveConfigOnEnter() {
        if (loadingLiveConfigOnEnter) return
        loadingLiveConfigOnEnter = true
        host.pageState = PageState.LOADING
        ApiConfig.get().loadLiveConfig(true, object : ApiConfig.LoadConfigCallback {
            override fun success() {
                handler.post {
                    loadingLiveConfigOnEnter = false
                    initLiveChannelList()
                    host.initLiveSettingGroupList()
                }
            }

            override fun error(msg: String?) {
                handler.post {
                    loadingLiveConfigOnEnter = false
                    setEmptyLiveChannelList()
                }
            }

            override fun notice(msg: String?) {
                handler.post {
                    Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun loadProxyLives(url: String) {
        proxyLoader.load(url)
    }

    private fun applyLiveChannelGroups(groups: List<LiveChannelGroup>) {
        host.liveChannelGroupList.clear()
        host.liveChannelGroupList.addAll(groups)
        host.pageState = PageState.READY
        initLiveState()
    }

    private fun initLiveState() {
        refreshingLiveChannelList = false
        val lastChannelName = pendingLiveRefreshChannelName ?: KV.get(HawkConfig.LIVE_CHANNEL, "")
        val sourceIndex = pendingLiveRefreshSourceIndex
        pendingLiveRefreshChannelName = null
        pendingLiveRefreshSourceIndex = -1

        var lastChannelGroupIndex = -1
        var lastLiveChannelIndex = -1
        var lastLiveChannelItem: LiveChannelItem? = null
        for (group in host.liveChannelGroupList) {
            val groupChannels = group.liveChannels
            if (groupChannels == null || groupChannels.isEmpty()) continue
            for (item in groupChannels) {
                if (item.channelName == lastChannelName) {
                    lastChannelGroupIndex = group.groupIndex
                    lastLiveChannelIndex = item.channelIndex
                    lastLiveChannelItem = item
                    break
                }
            }
            if (lastChannelGroupIndex != -1) break
        }
        if (lastChannelGroupIndex == -1) {
            val cctv1Channel = LiveChannelNavigator.firstChannelByName(
                host.liveChannelGroupList, "CCTV1"
            ) { groupIndex -> host.isNeedInputPassword(groupIndex) }
            if (cctv1Channel != null) {
                lastChannelGroupIndex = cctv1Channel[0]
                lastLiveChannelIndex = cctv1Channel[1]
            } else {
                lastChannelGroupIndex = LiveChannelNavigator.firstUnlockedGroupIndex(host.liveChannelGroupList)
                if (lastChannelGroupIndex == -1) lastChannelGroupIndex = 0
                lastLiveChannelIndex = 0
            }
        }
        if (lastLiveChannelItem != null && sourceIndex >= 0 && lastLiveChannelItem.sourceNum > 0) {
            lastLiveChannelItem.sourceIndex = minOf(sourceIndex, lastLiveChannelItem.sourceNum - 1)
        }

        host.mVideoView?.let { host.livePlayerManager.init(it) }
        host.overlay.showTime()
        host.overlay.showNetSpeed()
        host.currentLiveChannelIndex = -1
        host.expandedGroups.clear()
        host.channelVersion++
        host.selectChannelGroup(lastChannelGroupIndex, lastLiveChannelIndex)
    }

    fun refreshLiveChannelListAndPlay(channelName: String?, sourceIndex: Int) {
        refreshingLiveChannelList = true
        pendingLiveRefreshChannelName = channelName
        pendingLiveRefreshSourceIndex = sourceIndex
        host.currentLiveLookBackIndex = -1
        host.currentLiveChangeSourceTimes = 0
        host.channelGroupPasswordConfirmed.clear()
        handler.removeCallbacks(host.mConnectTimeoutChangeSourceRun)
        host.epgController.cancelPending()
        host.overlay.hideSwitchChannelSnapshot()
        host.expandedGroups.clear()
        host.isBackState = false
        host.overlayVisible = false
        host.channelVersion++
        host.epgVersion++
        initLiveChannelList()
        host.initLiveSettingGroupList()
    }

    private fun clearLiveChannelList(releasePlayer: Boolean) {
        refreshingLiveChannelList = false
        pendingLiveRefreshChannelName = null
        pendingLiveRefreshSourceIndex = -1
        host.currentLiveChannelItem = null
        host.currentLiveChannelIndex = -1
        host.currentLiveLookBackIndex = -1
        host.currentLiveChangeSourceTimes = 0
        host.liveChannelGroupList.clear()
        ApiConfig.get().clearLiveChannelGroups()
        handler.removeCallbacks(host.mConnectTimeoutChangeSourceRun)
        host.epgController.cancelPending()
        host.overlay.hideSwitchChannelSnapshot()
        if (releasePlayer) host.releasePlayerKernel()
        host.expandedGroups.clear()
        host.selectedChannelGroupIndex = 0
        host.channelName = null
        host.epgdata = ArrayList()
        host.channelInfoUi = ChannelInfoUi()
        host.channelVersion++
        host.epgVersion++
        host.pageState = PageState.EMPTY
    }

    fun setEmptyLiveChannelList(releasePlayer: Boolean = true) {
        clearLiveChannelList(releasePlayer)
    }

    fun buildChannelRows(): List<LiveListRow> {
        val rows = ArrayList<LiveListRow>()
        for (group in host.liveChannelGroupList) {
            rows.add(LiveListRow(group, null, -1, "g" + group.groupIndex))
            if (host.expandedGroups.contains(group.groupIndex)) {
                val channels = getLiveChannels(group.groupIndex)
                if (channels != null) {
                    for (i in channels.indices) {
                        rows.add(LiveListRow(group, channels[i], i, "c" + group.groupIndex + "_" + channels[i].channelIndex))
                    }
                }
            }
        }
        return rows
    }
}
