@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.activity

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.bean.Epginfo
import com.github.tvbox.osc.bean.LiveChannelGroup
import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.bean.LiveSettingGroup
import com.github.tvbox.osc.player.KernelDecision
import com.github.tvbox.osc.player.KernelReusePolicy
import com.github.tvbox.osc.player.LivePlayerManager
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.controller.ComposeLiveController
import com.github.tvbox.osc.player.engine.MediaSources
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.KV
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

internal class LiveListRow(
    val group: LiveChannelGroup?,
    val channel: LiveChannelItem?,
    val channelPos: Int,
    val key: String,
)

class LivePlayActivity : BaseActivity() {

    companion object {
        private const val TAG = "LivePlayActivity"
        private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L
        private const val CONNECT_TIMEOUT_SWITCH_DELAY = 3500L
        private val FORMAT_DATE1 = SimpleDateFormat("MM-dd", Locale.getDefault())
    }

    private val vm: LivePlayViewModel by viewModels()

    internal var pageState by VmVar(LivePlayViewModel::pageState)
    internal var playState by VmVar(LivePlayViewModel::playState)
    internal var snapshotVisible by VmVar(LivePlayViewModel::snapshotVisible)
    internal var snapshotBitmap by VmVar(LivePlayViewModel::snapshotBitmap)
    private var fullScreen by VmVar(LivePlayViewModel::fullScreen)
    private var rotating by VmVar(LivePlayViewModel::rotating)
    internal var overlayVisible by VmVar(LivePlayViewModel::overlayVisible)
    internal var isBackState by VmVar(LivePlayViewModel::isBackState)
    internal var epgSheetVisible by VmVar(LivePlayViewModel::epgSheetVisible)
    internal var settingsSheetVisible by VmVar(LivePlayViewModel::settingsSheetVisible)
    internal var passwordDialogTarget by VmVar(LivePlayViewModel::passwordDialogTarget)
    internal var settingsVersion by VmVar(LivePlayViewModel::settingsVersion)
    internal var channelVersion by VmVar(LivePlayViewModel::channelVersion)
    internal var epgVersion by VmVar(LivePlayViewModel::epgVersion)
    internal var scrollTick by VmVar(LivePlayViewModel::scrollTick)
    internal var resolutionText by VmVar(LivePlayViewModel::resolutionText)
    internal var resolutionVisible by VmVar(LivePlayViewModel::resolutionVisible)
    internal var showTimeOn by VmVar(LivePlayViewModel::showTimeOn)
    internal var showNetSpeedOn by VmVar(LivePlayViewModel::showNetSpeedOn)
    internal var timeText by VmVar(LivePlayViewModel::timeText)
    internal var netSpeedText by VmVar(LivePlayViewModel::netSpeedText)
    internal var gestureHintText by VmVar(LivePlayViewModel::gestureHintText)
    internal var tsPosition by VmVar(LivePlayViewModel::tsPosition)
    internal var tsDuration by VmVar(LivePlayViewModel::tsDuration)
    internal var channelInfoUi by VmVar(LivePlayViewModel::channelInfoUi)
    internal val expandedGroups by VmVal(LivePlayViewModel::expandedGroups)

    private inner class VmVar<T>(private val ref: KMutableProperty1<LivePlayViewModel, T>) : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = ref.get(vm)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = ref.set(vm, value)
    }

    private inner class VmVal<T>(private val ref: KProperty1<LivePlayViewModel, T>) : ReadOnlyProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = ref.get(vm)
    }

    internal var mVideoView: MyVideoView? = null
    private var liveController: ComposeLiveController? = null
    private val mHandler = Handler(Looper.getMainLooper())
    internal val liveChannelGroupList = ArrayList<LiveChannelGroup>()
    internal var currentChannelGroupIndex: Int by VmVar(LivePlayViewModel::currentChannelGroupIndex)
    internal var currentLiveChannelIndex: Int by VmVar(LivePlayViewModel::currentLiveChannelIndex)
    internal var currentLiveLookBackIndex = -1
    internal var currentLiveChangeSourceTimes = 0
    internal var currentLiveChannelItem: LiveChannelItem? = null
    internal val livePlayerManager = LivePlayerManager()
    internal val channelGroupPasswordConfirmed = ArrayList<Int>()
    internal var channelName: LiveChannelItem? = null
    internal var epgdata = ArrayList<Epginfo>()
    internal var logoUrl: String? = null
    internal var isSHIYI = false
    internal var selectedChannelGroupIndex = 0
    private var exitingLivePlay = false
    private var liveSettingGroupList: List<LiveSettingGroup> = ArrayList()
    private var nowday = Date()

    internal val epgController = LiveEpgController(object : LiveEpgController.Host {
        override fun currentChannel(): LiveChannelItem? = channelName

        override fun currentChannelHasLogo(): Boolean = !logoUrl.isNullOrEmpty()

        override fun onEpgListChanged(list: ArrayList<Epginfo>) {
            epgdata = list
            epgVersion++
        }

        override fun onEpgSettled() {
            overlay.updateChannelInfoUi()
        }
    })

    internal val overlay = LiveOverlayController(this, mHandler)

    internal val catchupController = LiveCatchupController(this)

    internal val channelSourceLoader = LiveChannelSourceLoader(this, mHandler)

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        if (fullScreen) super.hideSysBar()
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        applyStatusBarAppearance()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    epgSheetVisible -> epgSheetVisible = false
                    settingsSheetVisible -> settingsSheetVisible = false
                    fullScreen -> applyFullscreen(false)
                    isBackState -> catchupController.backToLiveFromEpg()
                    else -> {
                        exitingLivePlay = true
                        finish()
                    }
                }
            }
        })
        epgController.reloadAddress()
        nowday = Date()
        epgController.setDayKey(FORMAT_DATE1.format(nowday))
        initVideoView()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme(manageStatusBarIcons = false) {
                SheetHostScaffold {
                    LiveScreen(activity = this)
                }
            }
        }
        channelSourceLoader.initLiveChannelList()
        initLiveSettingGroupList()
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarAppearance()
        exitingLivePlay = false
        val takenOverByVod = PlaybackService.peek()?.enterLiveState() ?: false
        rebindLiveControllerIfNeeded()
        if (takenOverByVod) {
            replayCurrentChannelAfterTakeover()
        } else {
            mVideoView?.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!exitingLivePlay) mVideoView?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        overlay.hideSwitchChannelSnapshot()
        PlaybackService.peek()?.exitLive()
        mVideoView = null
        mHandler.removeCallbacksAndMessages(null)
        epgController.cancelAll()
        channelSourceLoader.cancelAll()
    }

    private fun initVideoView() {
        val controller = ComposeLiveController(this)
        controller.setListener(liveControlListener)
        liveController = controller
        val view = PlaybackService.engine(this).also { it.enterLive() }.player()
        view.setVideoController(controller)
        view.setProgressSink(null)
        mVideoView = view
    }

    private fun rebindLiveControllerIfNeeded() {
        val view = mVideoView ?: return
        val controller = liveController ?: return
        if (view.videoController !== controller) {
            view.setVideoController(controller)
            LOG.i("echo-p4 re-bind live controller")
        }
    }

    private fun replayCurrentChannelAfterTakeover() {
        val item = currentLiveChannelItem ?: return
        val videoView = mVideoView ?: return
        currentLiveLookBackIndex = -1
        isSHIYI = false
        isBackState = false
        overlayVisible = false
        overlay.stopTimeshiftTicker()
        overlay.hideSwitchChannelSnapshot()
        videoView.setUrl(item.url, liveChannelHeader())
        videoView.start()
        overlay.showResolutionAfterChannelSwitch()
        catchupController.loadEpgAfterChannelStarted()
        epgVersion++
    }

    internal fun releasePlayerKernel() {
        val eng = PlaybackService.peek()
        if (eng != null && !eng.isReleased()) eng.releasePlayer()
        else mVideoView?.release()
    }

    private val liveControlListener = object : ComposeLiveController.LiveControlListener {
        override fun onSingleTap(): Boolean {
            if (fullScreen) {
                overlayVisible = !overlayVisible
                if (overlayVisible) overlay.scheduleOverlayHide()
            } else {
                applyFullscreen(true)
            }
            return true
        }

        override fun onLongPress() {
            if (isBackState) {
                overlayVisible = true
                overlay.scheduleOverlayHide()
            } else {
                openSettingsSheet()
            }
        }

        override fun onPlayStateChanged(playState: PlayState) {
            this@LivePlayActivity.playState = playState
            handleAutoSourceSwitch(playState)
        }

        override fun onHorizontalFling(direction: Int) {
            if (direction > 0) playNext() else playPrevious()
        }

        override fun onGesturePercent(isBrightness: Boolean, percent: Int) {
            overlay.showGestureHint(isBrightness, percent)
        }
    }

    private fun handleAutoSourceSwitch(state: PlayState) {
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
        when (state) {
            PlayState.IDLE, PlayState.PAUSED -> {}
            PlayState.PREPARED, PlayState.BUFFERED, PlayState.PLAYING -> {
                overlay.onPlaybackStarted()
                currentLiveChangeSourceTimes = 0
            }
            PlayState.ERROR, PlayState.COMPLETED -> {
                overlay.hideSwitchChannelSnapshot()
                mHandler.postDelayed(mConnectTimeoutChangeSourceRun, CONNECT_TIMEOUT_SWITCH_DELAY)
            }
            PlayState.PREPARING, PlayState.BUFFERING -> {
                mHandler.postDelayed(
                    mConnectTimeoutChangeSourceRun,
                    (KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5000L,
                )
            }
            else -> LOG.i("echo-Unexpected live_play state: $state")
        }
    }

    fun applyFullscreen(full: Boolean) {
        if (fullScreen == full) return
        rotating = (full != (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE))
        fullScreen = full
        requestedOrientation = if (full) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            orientationPolicyValue()
        }
        if (full) {
            overlayVisible = true
            overlay.scheduleOverlayHide()
            super.hideSysBar()
        } else {
            overlayVisible = false
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
    }

    private fun applyStatusBarAppearance() {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !AppThemeState.isDark(systemDark)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rotating = false
        applyStatusBarAppearance()
    }

    fun isFullBox(): Boolean {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (rotating) landNow else fullScreen
    }

    private fun playChannel(channelGroupIndex: Int, liveChannelIndex: Int, changeSource: Boolean): Boolean {
        if ((channelGroupIndex == currentChannelGroupIndex && liveChannelIndex == currentLiveChannelIndex && !changeSource)
            || (changeSource && currentLiveChannelItem?.sourceNum == 1)
        ) {
            return true
        }
        val groupChannels = channelSourceLoader.getLiveChannels(channelGroupIndex)
        if (groupChannels == null || groupChannels.isEmpty() || liveChannelIndex < 0 || liveChannelIndex >= groupChannels.size) {
            return false
        }
        val showPreviousFrame = currentLiveChannelItem != null && mVideoView?.isPlaying == true
        val previousLivePlayerType = livePlayerManager.livePlayerType
        if (!changeSource) {
            currentChannelGroupIndex = channelGroupIndex
            currentLiveChannelIndex = liveChannelIndex
            currentLiveChannelItem = channelSourceLoader.getLiveChannels(currentChannelGroupIndex)?.get(currentLiveChannelIndex)
            KV.put(HawkConfig.LIVE_CHANNEL, currentLiveChannelItem?.channelName ?: "")
            scrollTick++
        }
        channelName = currentLiveChannelItem
        currentLiveLookBackIndex = -1
        isSHIYI = false
        isBackState = false
        overlayVisible = false
        overlay.stopTimeshiftTicker()
        val item = currentLiveChannelItem ?: return false
        item.include_back = canCurrentChannelCatchup()
        overlay.updateChannelInfoUi()
        val videoView = mVideoView
        if (videoView != null) {
            val rebuildKernel = videoView.consumeKernelRebuildRequired()
            val reusePlayer = KernelReusePolicy.decide(videoView.mediaPlayer != null, rebuildKernel, false,
                    canReusePlayer(previousLivePlayerType)) == KernelDecision.REUSE
            val keepExoFrame = reusePlayer
            if (showPreviousFrame && !keepExoFrame) {
                overlay.showSwitchChannelSnapshot()
            } else {
                overlay.hideSwitchChannelSnapshot()
            }
            val liveUrl = item.url
            if (reusePlayer) {
                videoView.setUrl(liveUrl, liveChannelHeader())
                videoView.replay(true)
            } else {
                releasePlayerKernel()
                videoView.setUrl(liveUrl, liveChannelHeader())
                videoView.start()
            }
            overlay.showResolutionAfterChannelSwitch()
        }
        catchupController.loadEpgAfterChannelStarted()
        epgVersion++
        return true
    }

    private fun canReusePlayer(previousLivePlayerType: Int): Boolean {
        val videoView = mVideoView ?: return false
        return videoView.playState != PlayState.IDLE &&
                previousLivePlayerType == livePlayerManager.livePlayerType
    }

    private fun playNext() {
        if (!isCurrentLiveChannelValid()) return
        val next = getNextChannel(1)
        playChannel(next[0], next[1], false)
    }

    private fun playPrevious() {
        if (!isCurrentLiveChannelValid()) return
        val next = getNextChannel(-1)
        playChannel(next[0], next[1], false)
    }

    private fun playNextSource() {
        if (!isCurrentLiveChannelValid()) return
        currentLiveChannelItem?.nextSource()
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true)
    }

    internal val mConnectTimeoutChangeSourceRun = Runnable {
        currentLiveChangeSourceTimes++
        if (currentLiveChannelItem?.sourceNum == currentLiveChangeSourceTimes) {
            currentLiveChangeSourceTimes = 0
            val next = getNextChannel(if (KV.get(HawkConfig.LIVE_CHANNEL_REVERSE, false)) -1 else 1)
            playChannel(next[0], next[1], false)
        } else {
            playNextSource()
        }
    }

    private fun getNextChannel(direction: Int): IntArray {
        return LiveChannelNavigator.nextPosition(
            groups = liveChannelGroupList,
            currentGroupIndex = currentChannelGroupIndex,
            currentChannelIndex = currentLiveChannelIndex,
            direction = direction,
            crossGroup = KV.get(HawkConfig.LIVE_CROSS_GROUP, false),
            channelsOf = { groupIndex -> channelSourceLoader.getLiveChannels(groupIndex) },
        )
    }

    private fun isCurrentLiveChannelValid(): Boolean {
        if (currentLiveChannelItem == null) {
            Toast.makeText(App.getInstance()!!, getString(R.string.live_please_select_channel), Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    internal fun selectChannelGroup(groupIndex: Int, liveChannelIndex: Int) {
        selectedChannelGroupIndex = groupIndex
        if (isNeedInputPassword(groupIndex)) {
            showPasswordDialog(groupIndex, liveChannelIndex)
            return
        }
        if (liveChannelIndex > -1) {
            loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex)
        } else {
            if (!expandedGroups.contains(groupIndex)) expandedGroups.add(groupIndex)
            channelVersion++
        }
    }

    fun toggleChannelGroup(groupIndex: Int) {
        if (expandedGroups.contains(groupIndex)) {
            expandedGroups.remove(groupIndex)
            return
        }
        if (isNeedInputPassword(groupIndex)) {
            showPasswordDialog(groupIndex, -1)
            return
        }
        expandedGroups.add(groupIndex)
    }

    fun selectChannel(groupIndex: Int, position: Int) {
        selectedChannelGroupIndex = groupIndex
        clickLiveChannel(position)
    }

    private fun clickLiveChannel(position: Int) {
        playChannel(selectedChannelGroupIndex, position, false)
    }

    private fun loadChannelGroupDataAndPlay(groupIndex: Int, liveChannelIndex: Int) {
        selectedChannelGroupIndex = groupIndex
        if (!expandedGroups.contains(groupIndex)) expandedGroups.add(groupIndex)
        channelVersion++
        scrollTick++
        if (liveChannelIndex > -1) {
            clickLiveChannel(liveChannelIndex)
        }
    }

    private fun showPasswordDialog(groupIndex: Int, liveChannelIndex: Int) {
        passwordDialogTarget = groupIndex to liveChannelIndex
    }

    internal fun onPasswordConfirmed(password: String) {
        val target = passwordDialogTarget ?: return
        passwordDialogTarget = null
        val groupIndex = target.first
        if (password == liveChannelGroupList.getOrNull(groupIndex)?.groupPassword) {
            channelGroupPasswordConfirmed.add(groupIndex)
            channelVersion++
            loadChannelGroupDataAndPlay(groupIndex, target.second)
        } else {
            Toast.makeText(App.getInstance()!!, getString(R.string.live_wrong_password), Toast.LENGTH_SHORT).show()
        }
    }

    internal fun isNeedInputPassword(groupIndex: Int): Boolean {
        val group = liveChannelGroupList.getOrNull(groupIndex) ?: return false
        return group.groupPassword.orEmpty().isNotEmpty() && !isPasswordConfirmed(groupIndex)
    }

    private fun isPasswordConfirmed(groupIndex: Int): Boolean {
        for (confirmed in channelGroupPasswordConfirmed) {
            if (confirmed == groupIndex) return true
        }
        return false
    }

    fun getLiveChannels(groupIndex: Int): ArrayList<LiveChannelItem>? =
        channelSourceLoader.getLiveChannels(groupIndex)

    internal fun loadLiveConfigOnEnter() = channelSourceLoader.loadLiveConfigOnEnter()

    private fun setEmptyLiveChannelList(releasePlayer: Boolean = true) =
        channelSourceLoader.setEmptyLiveChannelList(releasePlayer)

    internal fun initLiveSettingGroupList() {
        liveSettingGroupList = ApiConfig.get().liveSettingGroupList
    }

    private fun loadCurrentSourceList() {
        liveSettingGroupList.getOrNull(0)?.liveSettingItems =
            LiveSettingsRules.sourceItems(currentLiveChannelItem?.channelSourceNames)
    }

    fun visibleSettingGroups(): List<LiveSettingGroup> {
        return LiveSettingsRules.visibleGroups(liveSettingGroupList, hasCurrentLiveChannelSource())
    }

    private fun hasCurrentLiveChannelSource(): Boolean {
        return LiveSettingsRules.hasChannelSource(currentLiveChannelItem)
    }

    internal fun openSettingsSheet() {
        ApiConfig.get().refreshLiveApiHistoryItems()
        loadCurrentSourceList()
        settingsVersion++
        settingsSheetVisible = true
    }

    fun settingSelectedIndex(groupIndex: Int): Int {
        return when (groupIndex) {
            0 -> currentLiveChannelItem?.sourceIndex ?: -1
            1 -> livePlayerManager.livePlayerScale
            2 -> livePlayerManager.livePlayerType
            3 -> KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1)
            5 -> ApiConfig.getLiveGroupIndex()
            6 -> getCurrentLiveConfigIndex()
            else -> -1
        }
    }

    internal fun isLiveApiLineMode(): Boolean = ApiConfig.get().isLiveApiLineMode()

    fun removeLiveConfigHistory(itemIndex: Int) {
        if (ApiConfig.get().isLiveApiLineMode()) {
            Toast.makeText(this, getString(R.string.live_repo_entry_not_deletable), Toast.LENGTH_SHORT).show()
            return
        }
        val history = KV.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>())
        if (itemIndex < 0 || itemIndex >= history.size) return
        if (history[itemIndex] == KV.get(HawkConfig.LIVE_API_URL, "")) {
            Toast.makeText(this, getString(R.string.live_active_config_not_deletable), Toast.LENGTH_SHORT).show()
            return
        }
        history.removeAt(itemIndex)
        KV.put(HawkConfig.LIVE_API_HISTORY, history)
        ApiConfig.get().refreshLiveApiHistoryItems()
        settingsVersion++
        Toast.makeText(this, getString(R.string.toast_removed_from_history), Toast.LENGTH_SHORT).show()
    }

    fun settingChecked(position: Int): Boolean {
        return when (position) {
            0 -> KV.get(HawkConfig.LIVE_SHOW_TIME, false)
            1 -> KV.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)
            2 -> KV.get(HawkConfig.LIVE_CHANNEL_REVERSE, false)
            3 -> KV.get(HawkConfig.LIVE_CROSS_GROUP, false)
            else -> false
        }
    }

    private fun getCurrentLiveConfigIndex(): Int {
        return LiveSettingsRules.currentConfigIndex(
            ApiConfig.isLiveFollowVod(),
            ApiConfig.get().getLiveConfigUrls(),
            KV.get(HawkConfig.LIVE_API_URL, ""),
        )
    }

    internal fun clickSettingItem(groupIndex: Int, position: Int) {
        vm.onSettingClicked(groupIndex, position, settingHost)
    }

    private val settingHost = object : LivePlayViewModel.Host {
        override fun currentChannelItem(): LiveChannelItem? = currentLiveChannelItem

        override fun currentPlayerScale(): Int = livePlayerManager.livePlayerScale

        override fun currentPlayerType(): Int = livePlayerManager.livePlayerType

        override fun replayCurrentChannel() {
            playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true)
        }

        override fun applyPlayerScale(position: Int) {
            mVideoView?.let { livePlayerManager.changeLivePlayerScale(it, position) }
        }

        override fun applyPlayerType(position: Int) {
            val videoView = mVideoView ?: return
            releasePlayerKernel()
            livePlayerManager.changeLivePlayerType(videoView, position)
            currentLiveChannelItem?.let { videoView.setUrl(it.url, liveChannelHeader()) }
            videoView.start()
        }

        override fun releasePlayerKernel() {
            this@LivePlayActivity.releasePlayerKernel()
        }

        override fun refreshTimeOverlay() {
            overlay.showTime()
        }

        override fun refreshNetSpeedOverlay() {
            overlay.showNetSpeed()
        }

        override fun refreshChannelListAndPlay(channelName: String?, sourceIndex: Int) {
            channelSourceLoader.refreshLiveChannelListAndPlay(channelName, sourceIndex)
        }

        override fun setEmptyChannelList(releasePlayer: Boolean) {
            setEmptyLiveChannelList(releasePlayer)
        }

        override fun toast(msg: String) {
            Toast.makeText(this@LivePlayActivity, msg, Toast.LENGTH_SHORT).show()
        }

        override fun isFinishing(): Boolean = this@LivePlayActivity.isFinishing

        override fun postToMain(action: Runnable) {
            mHandler.post(action)
        }
    }

    private fun liveWebHeader(): HashMap<String, String>? {
        return KV.get(HawkConfig.LIVE_WEB_HEADER)
    }

    internal fun liveChannelHeader(): HashMap<String, String>? {
        val item = currentLiveChannelItem ?: return liveWebHeader()
        val header = HashMap<String, String>()
        liveWebHeader()?.let { header.putAll(it) }
        item.headers?.let { header.putAll(it) }
        if (item.channelFormat.orEmpty().isNotEmpty()) {
            header[MediaSources.HEADER_FORMAT] = item.channelFormat.orEmpty()
        }
        return if (header.isEmpty()) null else header
    }

    internal fun onEpgRowClicked(position: Int): Boolean = catchupController.onEpgRowClicked(position)

    fun onTimeshiftSeek(progress: Float) {
        catchupController.onTimeshiftSeek(progress)
    }

    fun onTimeshiftTogglePlay() {
        catchupController.onTimeshiftTogglePlay()
    }

    internal fun canCurrentChannelCatchup(): Boolean = catchupController.canCurrentChannelCatchup()

    internal fun buildChannelRows(): List<LiveListRow> = channelSourceLoader.buildChannelRows()

    fun isPasswordConfirmedForUi(groupIndex: Int): Boolean = isPasswordConfirmed(groupIndex)

}
