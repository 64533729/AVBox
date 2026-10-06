package com.github.tvbox.osc.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayList

object BootGuard {

    private const val QUICK_CRASH_MS = 10_000L

    private const val ATTEMPT_WINDOW_MS = 10 * 60_000L

    private const val STABLE_RUN_MS = 10 * 60_000L

    private const val MAX_LOAD_ATTEMPTS = 3

    private const val CRASH_MARKER_NAME = "boot_crash.marker"

    private val IGNORABLE_FRAME_PREFIXES = arrayOf(
            "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "dalvik.", "libcore.",
            "com.google.android.", "com.android.internal.",
            "com.github.tvbox.osc.ui.", "com.github.tvbox.osc.base."
    )

    private const val MAX_THROWABLE_CHAIN = 32

    @JvmStatic
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (looksSourceRelatedSafely(throwable)) {
                writeCrashMarker()
            } else {
                LOG.i("boot-guard: crash unrelated to source, marker skipped")
            }
            if (previous != null) previous.uncaughtException(thread, throwable)
        }
    }

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
        }
    }

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

    private fun looksSourceRelatedSafely(throwable: Throwable?): Boolean {
        try {
            return looksSourceRelated(throwable)
        } catch (ignored: Throwable) {
            return true
        }
    }

    private fun collectThrowables(throwable: Throwable?, out: ArrayList<Throwable>) {
        if (throwable == null || out.size >= MAX_THROWABLE_CHAIN || out.contains(throwable)) return
        out.add(throwable)
        try {
            for (suppressed in throwable.suppressed) collectThrowables(suppressed, out)
        } catch (ignored: Throwable) {
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

    @JvmStatic
    fun scheduleStableRunReset() {
        try {
            mainHandler.removeCallbacks(stableReset)
            mainHandler.postDelayed(stableReset, STABLE_RUN_MS)
        } catch (ignored: Throwable) {
        }
    }

    private fun recordCurrentSource() {
        KV.put(HawkConfig.BOOT_VOD_SOURCE, KV.get(HawkConfig.API_URL, ""))
        KV.put(HawkConfig.BOOT_LIVE_SOURCE, KV.get(HawkConfig.LIVE_API_URL, ""))
    }

    @JvmStatic
    fun disableBootLoopingSource(): String {
        try {
            val loading = KV.get(HawkConfig.BOOT_LOADING_JAR, "")
            val crashElapsed = takeCrashMarkerElapsed()
            if (crashElapsed <= 0) {
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

    @JvmStatic
    fun shouldDisable(jar: String?, count: Long, crashElapsed: Long, startupCrash: Boolean): Boolean {
        if (isEmpty(jar) || crashElapsed <= 0) return false
        return startupCrash || count >= MAX_LOAD_ATTEMPTS
    }

    @JvmStatic
    fun crashedDuringStartup(crashElapsed: Long, loadStartElapsed: Long): Boolean {
        return loadStartElapsed > 0 && crashElapsed >= loadStartElapsed
                && crashElapsed - loadStartElapsed <= QUICK_CRASH_MS
    }

    private fun isEmpty(text: String?): Boolean {
        return text == null || text.length == 0
    }

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

    @JvmStatic
    fun isDisabledSource(url: String?): Boolean {
        if (isEmpty(url)) return false
        try {
            return disabledSources().contains(url!!.trim { it <= ' ' })
        } catch (ignored: Throwable) {
            return false
        }
    }

    @JvmStatic
    fun disabledSources(): ArrayList<String> {
        try {
            return ArrayList<String>(KV.get(HawkConfig.BOOT_DISABLED_SOURCES, ArrayList<String>()))
        } catch (ignored: Throwable) {
            return ArrayList()
        }
    }

    @JvmStatic
    fun enableSource(url: String?) {
        if (isEmpty(url)) return
        try {
            KV.put(HawkConfig.BOOT_DISABLED_SOURCES, removeDisabledSource(disabledSources(), url))
        } catch (ignored: Throwable) {
        }
    }

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

    @JvmStatic
    fun addDisabledSource(list: ArrayList<String>?, url: String?): ArrayList<String> {
        val next = ArrayList<String>(if (list == null) ArrayList<String>() else list)
        if (isEmpty(url)) return next
        val value = url!!.trim { it <= ' ' }
        if (!next.contains(value)) next.add(value)
        return next
    }

    @JvmStatic
    fun removeDisabledSource(list: ArrayList<String>?, url: String?): ArrayList<String> {
        val next = ArrayList<String>(if (list == null) ArrayList<String>() else list)
        if (isEmpty(url)) return next
        next.remove(url!!.trim { it <= ' ' })
        return next
    }
}
