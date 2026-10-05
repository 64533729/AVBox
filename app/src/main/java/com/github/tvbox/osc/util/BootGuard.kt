package com.github.tvbox.osc.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayList

/**
 * 启动看门狗:坏源把应用锁进"一启动就崩"的死循环时,下次启动自动停用它(2026-09-21)。
 *
 * <p>场景:第三方爬虫在静态初始化里把 CDN 的报错页当 {@code .so} 加载 ⇒ 每次冷启动必崩,
 * 而源地址是持久化的,用户连"换源"都进不去,只能清数据。崩在爬虫自己的线程上,接不住异常。
 *
 * <p>判据:①崩溃发生在"开始加载 jar 后 10 秒内"⇒ 一次即停用;②同一源连续 3 次装载都以"与源有关"的
 * 崩溃收场 ⇒ 停用(见 {@link #MAX_LOAD_ATTEMPTS}:没有崩溃证据的会话会把计数清零,防计数被顶满)。
 * 判据①比的是**最近一次**开始装载的时刻,不是"本进程第一次装载" —— 会话中途换仓/换源切到坏源
 * 也是装载阶段崩,拿进程第一次装载当起点会把差值算成几分钟,于是要崩两次才停用。
 * 崩溃标记只由"可能与源有关"的崩溃写入(见 {@link #looksSourceRelated}),界面/平台 bug 不参与停用判定。
 * 停用只清启动指针与仓列表,**不动订阅列表**;同时把源地址记进黑名单
 * ({@link HawkConfig#BOOT_DISABLED_SOURCES}),让界面能标出来、仓改写能绕开它。
 */
object BootGuard {

    /** 开始加载 jar 后这么久之内崩溃,算"崩在装载阶段"(实测是 28 毫秒) */
    private const val QUICK_CRASH_MS = 10_000L

    /** 距上次同源装载超过这么久视为另一批问题,重新计数 */
    private const val ATTEMPT_WINDOW_MS = 10 * 60_000L

    /** 连续存活这么久即认定为稳定源,清掉计数 */
    private const val STABLE_RUN_MS = 10 * 60_000L

    /** 同一源连续这么多次"装载后崩(与源有关)"就认为在空转(兜底判据;正常启动同源只装 1~2 次) */
    private const val MAX_LOAD_ATTEMPTS = 3

    private const val CRASH_MARKER_NAME = "boot_crash.marker"

    /**
     * 判为与源无关的崩溃帧前缀:整条异常链都落在这里面才算"界面/平台问题"。
     * 刻意不含 {@code com.github.catvod.} —— 装载器与爬虫都在这条链上,判不准宁可算"有关"。
     *
     * <p>⚠️ {@code com.android.internal.} 是**必需的**:任何主线程未捕获异常的栈尾必然是
     * {@code com.android.internal.os.RuntimeInit.run} → {@code ZygoteInit.main}。漏了它,
     * 判据会对**每一次**主线程崩溃都返回 true,"界面崩溃不参与停用判定"这条保护等于从未生效
     * —— 2026-09-23 实机事故:设置页一个既有的 CME 崩在启动期,把用户正常的源误禁了。
     */
    private val IGNORABLE_FRAME_PREFIXES = arrayOf(
            "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "dalvik.", "libcore.",
            "com.google.android.", "com.android.internal.",
            "com.github.tvbox.osc.ui.", "com.github.tvbox.osc.base."
    )

    /** cause / suppressed 遍历上限,防人为构造的异常环 */
    private const val MAX_THROWABLE_CHAIN = 32

    /** 在 Application.onCreate 里装:先记录,再原样交回原处理器 */
    @JvmStatic
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 不写标记的崩溃不参与停用判定,否则界面 bug 也会把源算成"崩过"
            if (looksSourceRelatedSafely(throwable)) {
                writeCrashMarker()
            } else {
                LOG.i("boot-guard: crash unrelated to source, marker skipped")
            }
            if (previous != null) previous.uncaughtException(thread, throwable)
        }
    }

    /**
     * 同步写崩溃标记(内容 = 崩溃时的开机计时)。
     *
     * <p>不用 KV:MMKV 是异步写、2.4.2 又没有同步写 flag,实测崩溃路径上的 KV 写入会丢,
     * 导致判据从未成立。崩溃路径必须用同步文件 IO。
     */
    private fun writeCrashMarker() {
        try {
            val file = crashMarkerFile()
            if (file == null) return
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            FileOutputStream(file).use { out ->
                out.write(java.lang.String.valueOf(SystemClock.elapsedRealtime()).toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        } catch (ignored: Throwable) {
            // 崩溃路径上不能再抛
        }
    }

    /**
     * 崩溃栈是否可能与源有关(纯函数,便于单测):整条链的帧都在平台/界面层内才算无关。
     *
     * <p>界面 bug 崩在装载后 10 秒内会被判成"装载阶段崩"、崩一次就停用源;而判不出来(无帧/null)
     * 一律按"有关"处理 —— 漏判会让坏源重新把应用锁进启动崩溃,比误禁更难救。
     */
    @JvmStatic
    fun looksSourceRelated(throwable: Throwable?): Boolean {
        if (throwable == null) return true
        val chain = ArrayList<Throwable>()
        collectThrowables(throwable, chain)
        var sawFrame = false
        for (item in chain) {
            val frames = item.stackTrace
            if (frames == null) continue
            for (frame in frames) {
                if (frame == null) continue
                sawFrame = true
                val className = frame.className
                if (className == null || !isIgnorableFrame(className)) return true
            }
        }
        return !sawFrame
    }

    /** 过滤自身出错时按"有关"处理:崩溃路径上不能因为判定失败而漏记 */
    private fun looksSourceRelatedSafely(throwable: Throwable?): Boolean {
        try {
            return looksSourceRelated(throwable)
        } catch (ignored: Throwable) {
            return true
        }
    }

    /** 收集 cause 链与 suppressed(带环与规模保护) */
    private fun collectThrowables(throwable: Throwable?, out: ArrayList<Throwable>) {
        if (throwable == null || out.size >= MAX_THROWABLE_CHAIN || out.contains(throwable)) return
        out.add(throwable)
        try {
            for (suppressed in throwable.suppressed) collectThrowables(suppressed, out)
        } catch (ignored: Throwable) {
            // 取不到 suppressed 不影响判定
        }
        collectThrowables(throwable.cause, out)
    }

    private fun isIgnorableFrame(className: String): Boolean {
        for (prefix in IGNORABLE_FRAME_PREFIXES) {
            if (className.startsWith(prefix)) return true
        }
        return false
    }

    private fun crashMarkerFile(): File? {
        try {
            return File(AppContextHolder.context()!!.filesDir, CRASH_MARKER_NAME)
        } catch (e: Throwable) {
            return null
        }
    }

    /** 读取并删除崩溃标记,返回其中的开机计时;无标记返回 -1(读后即删:一次崩溃只算一次) */
    private fun takeCrashMarkerElapsed(): Long {
        try {
            val file = crashMarkerFile()
            if (file == null || !file.exists()) return -1L
            val buf = ByteArray(32)
            var len = 0
            FileInputStream(file).use { input ->
                len = input.read(buf)
            }
            var value = -1L
            if (len > 0) {
                try {
                    value = java.lang.Long.parseLong(String(buf, 0, len, StandardCharsets.UTF_8).trim { it <= ' ' })
                } catch (ignored: NumberFormatException) {
                    value = -1L
                }
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete()
            return value
        } catch (e: Throwable) {
            return -1L
        }
    }

    /**
     * 开始加载某个 jar:记下"最近一次装载起点",同源累计计数,换源则从 1 重新计。
     *
     * <p>起点**每次装载都覆盖**(与 {@code BOOT_LOADING_JAR} 保持同一批数据):只记本进程第一次的话,
     * 会话中途换仓切到坏源崩掉时,崩溃时刻减装载起点是几分钟 ⇒ 判不出装载阶段崩溃,要崩两次才停用。
     * 代价是"非装载期的崩溃若正好落在某次装载后 10 秒内"会被误算 —— 但误算的后果已被压到
     * "源被标记已禁用、二次确认即可恢复"(见黑名单),不再是静默清掉启动指针。
     */
    @JvmStatic
    fun onJarLoadStart(jarUrl: String) {
        try {
            if (isEmpty(jarUrl)) return
            recordCurrentSource()
            val now = System.currentTimeMillis()
            KV.put(HawkConfig.BOOT_LOAD_START_ELAPSED, SystemClock.elapsedRealtime())
            val previous = KV.get(HawkConfig.BOOT_LOADING_JAR, "")
            val lastAttemptAt = KV.get(HawkConfig.BOOT_LAST_ATTEMPT_AT, 0L)
            val stale = lastAttemptAt > 0 && now - lastAttemptAt > ATTEMPT_WINDOW_MS
            val count = if (previous == jarUrl && !stale) KV.get(HawkConfig.BOOT_LOADING_COUNT, 0L) + 1L else 1L
            KV.put(HawkConfig.BOOT_LOADING_COUNT, count)
            KV.put(HawkConfig.BOOT_LOADING_JAR, jarUrl)
            KV.put(HawkConfig.BOOT_LAST_ATTEMPT_AT, now)
        } catch (ignored: Throwable) {
            // 看门狗自身失败不能影响正常加载
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val stableReset = Runnable {
        try {
            KV.put(HawkConfig.BOOT_LOADING_JAR, "")
            KV.put(HawkConfig.BOOT_LOADING_COUNT, 0L)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 存活满 {@link #STABLE_RUN_MS} 未崩 ⇒ 清计数。
     *
     * <p>刻意不在"装载成功"时就清:爬虫的 {@code <clinit>} 在另一个线程,装载线程先报成功、
     * 28 毫秒后才崩,早清等于擦掉唯一证据。
     */
    @JvmStatic
    fun scheduleStableRunReset() {
        try {
            mainHandler.removeCallbacks(stableReset)
            mainHandler.postDelayed(stableReset, STABLE_RUN_MS)
        } catch (ignored: Throwable) {
        }
    }

    /** 记下"此刻哪个源是启动源":崩了就是它(拿 jar 地址反查源对不上号,同一个 jar 常被多个源引用) */
    private fun recordCurrentSource() {
        KV.put(HawkConfig.BOOT_VOD_SOURCE, KV.get(HawkConfig.API_URL, ""))
        KV.put(HawkConfig.BOOT_LIVE_SOURCE, KV.get(HawkConfig.LIVE_API_URL, ""))
    }

    /**
     * 启动早期(加载任何 jar 之前)调用。
     *
     * @return 被停用的源地址;没有则空串
     */
    @JvmStatic
    fun disableBootLoopingSource(): String {
        try {
            val loading = KV.get(HawkConfig.BOOT_LOADING_JAR, "")
            // takeCrashMarkerElapsed 读完即删,故判定结果只算一次再复用
            val crashElapsed = takeCrashMarkerElapsed()
            if (crashElapsed <= 0) {
                // 无"与源有关"的崩溃证据 = 上一轮不是崩溃循环。普通重启与界面崩溃同样会装载 jar,
                // 不清零就会把兜底计数推过阈值,让一次无关崩溃停用正常源。
                KV.put(HawkConfig.BOOT_LOADING_COUNT, 0L)
                return ""
            }
            val count = KV.get(HawkConfig.BOOT_LOADING_COUNT, 0L)
            val loadStartElapsed = KV.get(HawkConfig.BOOT_LOAD_START_ELAPSED, 0L)
            val startupCrash = crashedDuringStartup(crashElapsed, loadStartElapsed)
            if (!shouldDisable(loading, count, crashElapsed, startupCrash)) {
                return ""
            }
            LOG.i("boot-guard: disable looping source attempt=" + count
                    + " startupCrash=" + startupCrash + " jar=" + loading)
            disableRecordedSource()
            KV.put(HawkConfig.BOOT_LOADING_JAR, "")
            KV.put(HawkConfig.BOOT_LOADING_COUNT, 0L)
            KV.put(HawkConfig.BOOT_LOAD_START_ELAPSED, 0L)
            return loading
        } catch (e: Throwable) {
            LOG.i("boot-guard failed: " + e.message)
            return ""
        }
    }

    /**
     * 停用判定(纯函数,便于单测):崩在装载阶段 **或** 崩溃收场的装载达 {@link #MAX_LOAD_ATTEMPTS} 次。
     *
     * <p>{@code startupCrash} 由调用方传入:算它要读并删除崩溃标记,只能读一次。
     */
    @JvmStatic
    fun shouldDisable(jar: String?, count: Long, crashElapsed: Long, startupCrash: Boolean): Boolean {
        if (isEmpty(jar) || crashElapsed <= 0) return false
        return startupCrash || count >= MAX_LOAD_ATTEMPTS
    }

    /**
     * 崩溃是否落在"开始加载 jar 后 {@link #QUICK_CRASH_MS} 内"(两个值同为开机计时,可跨进程比较)。
     *
     * <p>名字与日志字段里的 "startup" 是沿用的历史叫法,别按字面理解成"应用启动":判据现在比的是
     * **最近一次**装载(见 {@link #onJarLoadStart}),会话中途换仓/换源同样是装载阶段崩。
     * 名字与日志字段刻意不改 —— 既有真机日志与 `history/` 归档里都是这个字段名,改了对不上号。
     */
    @JvmStatic
    fun crashedDuringStartup(crashElapsed: Long, loadStartElapsed: Long): Boolean {
        return loadStartElapsed > 0 && crashElapsed >= loadStartElapsed
                && crashElapsed - loadStartElapsed <= QUICK_CRASH_MS
    }

    /**
     * 与 TextUtils.isEmpty 等价。
     *
     * <p>不用 {@code android.text.TextUtils}:单测开了 returnDefaultValues,它会静默返回 false
     * (本仓已踩三次:ConfigParser、Depot、这里)。
     */
    private fun isEmpty(text: String?): Boolean {
        return text == null || text.length == 0
    }

    /** 停用崩溃时正在使用的启动源;直播优先(独立直播源时点播不受影响),只清指针与仓列表 */
    private fun disableRecordedSource() {
        val liveSource = KV.get(HawkConfig.BOOT_LIVE_SOURCE, "")
        val vodSource = KV.get(HawkConfig.BOOT_VOD_SOURCE, "")
        if (!liveSource.isEmpty()) {
            KV.put(HawkConfig.LIVE_API_URL, "")
            HistoryHelper.clearLiveApiLineList()
            KV.put(HawkConfig.BOOT_SAFE_DISABLED, liveSource)
            rememberDisabled(liveSource)
        } else if (!vodSource.isEmpty()) {
            KV.put(HawkConfig.API_URL, "")
            HistoryHelper.clearApiLineList()
            KV.put(HawkConfig.BOOT_SAFE_DISABLED, vodSource)
            rememberDisabled(vodSource)
        }
        KV.put(HawkConfig.BOOT_VOD_SOURCE, "")
        KV.put(HawkConfig.BOOT_LIVE_SOURCE, "")
    }

    /** 上一次自动停用的源地址(空串表示没有);UI 读后即清 */
    @JvmStatic
    fun takeSafeDisabledNotice(): String {
        try {
            val url = KV.get(HawkConfig.BOOT_SAFE_DISABLED, "")
            if (!url.isEmpty()) KV.put(HawkConfig.BOOT_SAFE_DISABLED, "")
            return url
        } catch (ignored: Throwable) {
            return ""
        }
    }

    // ---- 风险源黑名单 ----
    // 停用只是"这次别用它",源地址还在订阅列表里、还能被用户点中 —— 名单让界面能标出"这个源崩过",
    // 并让仓改写绕开它,否则重新启用那个仓又会被改写到坏子源、再崩一次。

    /** 这个源地址是否在黑名单里 */
    @JvmStatic
    fun isDisabledSource(url: String?): Boolean {
        if (isEmpty(url)) return false
        try {
            return disabledSources().contains(url!!.trim { it <= ' ' })
        } catch (ignored: Throwable) {
            return false
        }
    }

    /** 黑名单快照(源地址);返回副本,调用方改它不会写回存储 */
    @JvmStatic
    fun disabledSources(): ArrayList<String> {
        try {
            return ArrayList<String>(KV.get(HawkConfig.BOOT_DISABLED_SOURCES, ArrayList<String>()))
        } catch (ignored: Throwable) {
            return ArrayList()
        }
    }

    /** 用户二次确认后把源移出名单(允许再试;真修好了就不会再进来,仍坏则下次启动重新记入) */
    @JvmStatic
    fun enableSource(url: String?) {
        if (isEmpty(url)) return
        try {
            KV.put(HawkConfig.BOOT_DISABLED_SOURCES, removeDisabledSource(disabledSources(), url))
        } catch (ignored: Throwable) {
        }
    }

    /** 订阅被删除时一起清掉名单记录,免得名单里堆着用户已经不要的地址 */
    @JvmStatic
    fun forgetSources(urls: Collection<String>?) {
        if (urls == null || urls.isEmpty()) return
        try {
            var list = disabledSources()
            for (url in urls) list = removeDisabledSource(list, url)
            KV.put(HawkConfig.BOOT_DISABLED_SOURCES, list)
        } catch (ignored: Throwable) {
        }
    }

    private fun rememberDisabled(url: String?) {
        if (isEmpty(url)) return
        try {
            KV.put(HawkConfig.BOOT_DISABLED_SOURCES, addDisabledSource(disabledSources(), url))
        } catch (ignored: Throwable) {
        }
    }

    /** 记入名单(已存在则不重复);纯函数,便于单测 */
    @JvmStatic
    fun addDisabledSource(list: ArrayList<String>?, url: String?): ArrayList<String> {
        val next = ArrayList<String>(if (list == null) ArrayList<String>() else list)
        if (isEmpty(url)) return next
        val value = url!!.trim { it <= ' ' }
        if (!next.contains(value)) next.add(value)
        return next
    }

    /** 移出名单;不在名单里就原样返回 */
    @JvmStatic
    fun removeDisabledSource(list: ArrayList<String>?, url: String?): ArrayList<String> {
        val next = ArrayList<String>(if (list == null) ArrayList<String>() else list)
        if (isEmpty(url)) return next
        next.remove(url!!.trim { it <= ' ' })
        return next
    }
}
