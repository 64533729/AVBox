package com.github.tvbox.osc.player

/** 播放尝试/线路/解码/会话标记(纯字段,无 IO/视图/handler 依赖)。 */
class PlaybackAttemptState {

    // ==================== 重试/尝试状态 ====================

    @JvmField
    var allowSwitchPlayer: Boolean = true

    @JvmField
    var hasAutoSwitchedPlayer: Boolean = false

    @JvmField
    var autoSwitchedPlayerType: Int = -1

    /** 自动"硬解→软解"是否已用过;兼任"用户显式选过解码 ⇒ 本次不再自动回退"的阻断标记,只有确有自动态可回滚时才清除 */
    @JvmField
    var hasAutoSwitchedDecode: Boolean = false

    /** 自动切软解前的 cfg.exo(仅回滚/落库剔除用;null = 不在自动软解态) */
    @JvmField
    var autoSwitchedDecodeOld: String? = null

    /** 自动软解改的解码键(内核只剩 EXO):回滚与落库剔除按它还原 */
    @JvmField
    var autoSwitchedDecodeKey: String = "exo"

    /** "起播后错误"自动重播是否已用过(每轮一次) */
    @JvmField
    var hasRetriedAfterStart: Boolean = false

    /** 本次起播用的是预解析缓存结果(直链可能已过期):失败时允许一次"丢弃并重新取流" */
    @JvmField
    var usedPreloadedResult: Boolean = false

    @JvmField
    var hasRetriedSameUrlOnBoot: Boolean = false

    @JvmField
    var playbackStarted: Boolean = false

    @JvmField
    var playTimeoutBasePosition: Long = 0

    @JvmField
    val triedLineFlags: MutableSet<String> = HashSet()

    @JvmField
    var lastRetryTime: Long = 0

    /** 用户手动点选线路:取流失败/超时不自动换线换源,直接报错停留(避免覆盖用户选择) */
    @JvmField
    var userPickedLine: Boolean = false

    @JvmField
    var allowAutoSwitchLine: Boolean = true

    // ==================== 切换意图(点一次生效一次) ====================

    /** 三态代替原先"reuse/release 两个独立 boolean":REBUILD 覆盖 REUSE,避免两个字段各自为政 */
    enum class SwitchIntent { NONE, REUSE, REBUILD }

    @JvmField
    var switchIntent: SwitchIntent = SwitchIntent.NONE

    /** 换源点击即停:置位后抑制在途取流结果/超时/嗅探回调把已停的旧源拉起 */
    @JvmField
    var switchStopPending: Boolean = false

    /** 换源停播时记下的进度(键+毫秒):取流后写进新源的进度键 */
    @JvmField
    var pendingInheritKey: String? = null

    @JvmField
    var pendingInheritProgress: Long = 0

    // ==================== 会话标记 ====================

    /** 取流/起播期间不更新通知(避免"旧集通知 → 新集"的中间态) */
    @JvmField
    var switchingPlayback: Boolean = false

    /** 是否维护了媒体会话(有音频轨就维护;见 updateMusicSession) */
    @JvmField
    var audioPlayback: Boolean = false

    /**
     * 本次会话确认过"纯音频"的粘滞标记:轨道信息读不到时不得让退后台判定翻转成影视;
     * 不得用于封面判定(封面须实时读取,否则影视被压成海报);复位点仅内容边界(见 beginNewPlay/startSession)。
     */
    @JvmField
    var audioOnlyConfirmed: Boolean = false

    // ==================== 具名转移(每个赋值只在本段出现一次) ====================

    /** 会话边界:清已起播/停播标记与自动软解原值(留着会把上一轮解码方式回填落库;内容边界标记由调用方管) */
    fun beginSession() {
        playbackStarted = false
        switchStopPending = false
        autoSwitchedDecodeOld = null
    }

    /** 新一次播放的清场:重试阶梯 + 内核/解码自动态 + 起播标记 */
    fun beginNewPlay() {
        playbackStarted = false
        playTimeoutBasePosition = 0
        allowSwitchPlayer = true
        hasAutoSwitchedPlayer = false
        hasAutoSwitchedDecode = false
        hasRetriedAfterStart = false
        hasRetriedSameUrlOnBoot = false
        usedPreloadedResult = false
    }

    /** 用户自救(重播/切解析/切内核/切解码)后:允许再兜一次底 */
    fun userSelfRescue() {
        hasAutoSwitchedPlayer = false
        hasRetriedAfterStart = false
        hasRetriedSameUrlOnBoot = false
    }

    /** 换源点击即停:清起播标记与复用意图,置"在途结果作废" */
    fun stoppedForSourceSwitch() {
        playbackStarted = false
        playTimeoutBasePosition = 0
        toggleReuseIntent(false, false)
        switchStopPending = true
    }

    /** 重试阶梯复位(60s 窗口过期 / 关自动换线):切内核额度 + 已试线路 */
    fun resetAutoRetryLadder() {
        allowSwitchPlayer = true
        hasAutoSwitchedPlayer = false
        hasRetriedSameUrlOnBoot = false
        clearTriedLines()
    }

    /** 换线成功:阶梯复位 + 置复用意图(不覆盖 REBUILD:自动切过内核回滚时该意图必须保持) */
    fun onLineSwitched() {
        allowSwitchPlayer = true
        hasAutoSwitchedPlayer = false
        if (switchIntent != SwitchIntent.REBUILD) switchIntent = SwitchIntent.REUSE
    }

    /** 无路可走(无剧集数据 / 线路耗尽):清已试线路 */
    fun linesExhausted() {
        clearTriedLines()
    }

    /** 清空已尝试线路(切集/换线/关自动换线) */
    fun clearTriedLines() {
        triedLineFlags.clear()
    }

    /** 单字段写,另一个意图不动(避免读-改-写冲掉并发改动);REBUILD 不被复用意图覆盖 */
    fun setReuseIntent(reuse: Boolean) {
        if (reuse) {
            if (switchIntent == SwitchIntent.NONE) switchIntent = SwitchIntent.REUSE
        } else if (switchIntent == SwitchIntent.REUSE) {
            switchIntent = SwitchIntent.NONE
        }
    }

    fun setReleaseIntent(release: Boolean) {
        if (release) {
            switchIntent = SwitchIntent.REBUILD
        } else if (switchIntent == SwitchIntent.REBUILD) {
            switchIntent = SwitchIntent.NONE
        }
    }

    /** 两个意图一起写 */
    fun toggleReuseIntent(reuse: Boolean, release: Boolean) {
        switchIntent = if (release) SwitchIntent.REBUILD else if (reuse) SwitchIntent.REUSE else SwitchIntent.NONE
    }

    /** 取出并复位(REBUILD 优先:true = 复用) */
    fun consumeReuseIntent(): Boolean {
        val reuse = switchIntent == SwitchIntent.REUSE
        switchIntent = SwitchIntent.NONE
        return reuse
    }

    /** 清会话标记(退出页面/起播失败/页面销毁三处同集) */
    fun clearSessionFlags() {
        switchingPlayback = false
        audioPlayback = false
    }
}
