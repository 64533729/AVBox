package com.github.tvbox.osc.util.thunder

import android.content.Context
import android.net.Uri
import android.text.TextUtils

import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.io.FileUtils
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.RegexUtils
import com.xunlei.downloadlib.XLDownloadManager
import com.xunlei.downloadlib.XLTaskHelper
import com.xunlei.downloadlib.android.XLUtil
import com.xunlei.downloadlib.parameter.TorrentFileInfo
import com.xunlei.downloadlib.parameter.XLTaskInfo

import com.github.tvbox.osc.util.AppContextHolder
import java.io.File
import java.util.ArrayList
import java.util.HashMap
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object Thunder {

    private fun str(resId: Int, vararg args: Any?): String {
        val app = AppContextHolder.context()
        return if (app == null) "" else LanguageManager.localized(app).getString(resId, *args)
    }

    private var cacheRoot = ""
    private var localPath = ""
    private var name = ""
    private var task_url = ""
    private var currentTask = 0L
    private var torrentFileInfoArrayList: ArrayList<TorrentFileInfo>? = null
    private var threadPool: ExecutorService? = null

    private fun init(context: Context) {
        var imei = KV.get(HawkConfig.THUNDER_IMEI, "")
        var mac = KV.get(HawkConfig.THUNDER_MAC, "")
        if (TextUtils.isEmpty(imei)) {
            imei = randomImei()
            KV.put(HawkConfig.THUNDER_IMEI, imei)
        }
        if (TextUtils.isEmpty(mac)) {
            mac = randomMac()
            KV.put(HawkConfig.THUNDER_MAC, mac)
        }

        XLUtil.mIMEI = imei
        XLUtil.isGetIMEI = true
        XLUtil.mMAC = mac
        XLUtil.isGetMAC = true
        val cd3 = "cee25055f125a2fde0"
        val base64Decode = "axzNjAwMQ^^yb==0^852^083dbcff^"
        val substring = base64Decode.substring(1)
        val substring2 = cd3.substring(0, cd3.length - 1)
        val cd = substring + substring2
        XLTaskHelper.init(context, cd, "21.01.07.800002")
        cacheRoot = context.cacheDir.absolutePath + File.separator + "thunder"
    }

    @JvmStatic
    fun stop(bool: Boolean) {
        if (currentTask > 0) {
            XLTaskHelper.instance().stopTask(currentTask)
            currentTask = 0L
        }
        if (bool) {
            torrentFileInfoArrayList = null
            val cache = File(if (task_url.isEmpty()) cacheRoot else localPath)
            recursiveDelete(cache)
            if (!cache.exists())
                cache.mkdirs()
            if (threadPool != null) {
                try {
                    threadPool!!.shutdownNow()
                    threadPool = null
                } catch (th: Throwable) {
                    LOG.e("Thunder", "thread pool shutdown failed", th)
                }
            }
        }
    }

    interface ThunderCallback {

        fun status(code: Int, info: String)

        fun list(urlMap: MutableMap<Int, String>)

        fun play(url: String)
    }

    private var playList: ArrayList<String>? = null
    private var ed2kList: ArrayList<String>? = null
    @JvmStatic
    fun parse(context: Context, urlBean: Movie.Video.UrlBean?, callback: ThunderCallback) {
        init(context)
        stop(true)
        threadPool = Executors.newSingleThreadExecutor()
        torrentFileInfoArrayList = ArrayList()
        playList = ArrayList()
        ed2kList = ArrayList()
        val urlMap = HashMap<Int, String>()
        threadPool!!.execute(ParseTask(urlBean, urlMap, callback))
    }

    private class ParseTask(
        private val urlBean: Movie.Video.UrlBean?,
        private val urlMap: MutableMap<Int, String>,
        private val callback: ThunderCallback
    ) : Runnable {

        override fun run() {
            for (idx in 0 until urlBean!!.infoList!!.size) {
                val urlInfo: Movie.Video.UrlBean.UrlInfo? = urlBean!!.infoList!![idx]
                if (urlInfo != null) {
                    var url = ""
                    for (infoBean in urlInfo.beanList!!) {
                        var isParse = false
                        url = infoBean.url!!
                        if (Thunder.isMagnet(url) || Thunder.isThunder(url) || Thunder.isTorrent(url)) {
                            if (Thunder.isThunder(url)) url = XLDownloadManager.getInstance().parserThunderUrl(url)
                            val link = if (Thunder.isThunder(url)) XLDownloadManager.getInstance().parserThunderUrl(url) else url
                            val p = Uri.parse(link)
                            if (p == null) {
                                continue
                            }
                            val fileName = XLTaskHelper.instance().getFileName(link)
                            val cache = File(Thunder.cacheRoot + File.separator + fileName)
                            try {
                                if (Thunder.currentTask > 0) {
                                    XLTaskHelper.instance().stopTask(Thunder.currentTask)
                                    Thunder.currentTask = 0L
                                }
                                Thunder.currentTask = if (Thunder.isMagnet(url))
                                    XLTaskHelper.instance().addMagentTask(url, Thunder.cacheRoot, fileName)
                                else
                                    XLTaskHelper.instance().addThunderTask(url, Thunder.cacheRoot, fileName)
                            } catch (exception: Exception) {
                                LOG.e("Thunder", exception)
                                Thunder.currentTask = 0L
                            }
                            if (Thunder.currentTask <= 0) {
                                continue
                            }
                            var count = 30
                            outerLoop@ while (true) {
                                count--
                                if (count <= 0) {
                                    break
                                }
                                val taskInfo = XLTaskHelper.instance().getTaskInfo(Thunder.currentTask)
                                if (taskInfo != null) {
                                    when (taskInfo.mTaskStatus) {
                                        2 -> {
                                            try {
                                                val torrentInfo = XLTaskHelper.instance().getTorrentInfo(cache.absolutePath)
                                                if (torrentInfo == null || TextUtils.isEmpty(torrentInfo.mInfoHash)) {

                                                } else {
                                                    val mSubFileInfo = torrentInfo.mSubFileInfo
                                                    if (mSubFileInfo != null) {
                                                        for (sub in mSubFileInfo) {
                                                            if (Thunder.isMedia(sub.mFileName) && sub.mFileSize > 1048576L * 30) {
                                                                sub.torrentPath = cache.absolutePath
                                                                Thunder.playList!!.add(sub.mFileName + "\$tvbox-torrent:" + Thunder.torrentFileInfoArrayList!!.size)
                                                                Thunder.torrentFileInfoArrayList!!.add(sub)
                                                            }
                                                        }
                                                        isParse = true
                                                        break@outerLoop
                                                    }
                                                }
                                            } catch (throwable: Throwable) {
                                                LOG.e("Thunder", throwable)
                                            }
                                            break@outerLoop
                                        }
                                        3 -> {
                                            break@outerLoop
                                        }
                                        else -> {
                                        }
                                    }
                                }
                                try {
                                    Thread.sleep(100)
                                } catch (e: InterruptedException) {
                                    LOG.e("Thunder", e)
                                }
                            }
                        } else {
                            url = infoBean.url!!
                            if (Thunder.isThunder(url)) url = XLDownloadManager.getInstance().parserThunderUrl(url)
                            if (Thunder.isNetworkDownloadTask(url)) {
                                Thunder.task_url = url
                                if (TextUtils.isEmpty(Thunder.task_url)) {
                                    continue
                                }
                                Thunder.name = XLTaskHelper.instance().getFileName(Thunder.task_url)
                                Thunder.playList!!.add(Thunder.name + "\$tvbox-oth:" + Thunder.ed2kList!!.size)
                                Thunder.ed2kList!!.add(Thunder.task_url)
                                isParse = true
                            }
                        }
                        if (!isParse) Thunder.playList!!.add(infoBean.name + "\$" + infoBean.url)
                    }
                    if (Thunder.playList!!.size > 0) {
                        urlMap.put(idx, TextUtils.join("#", Thunder.playList!!))
                        Thunder.playList!!.clear()
                    }
                }
            }

            if (urlMap.size > 0) {
                callback.list(urlMap)
            } else {
                callback.status(-1, Thunder.str(R.string.thunder_error_parse))
            }
        }
    }

    @JvmStatic
    fun play(url: String, callback: ThunderCallback): Boolean {
        if (url.startsWith("tvbox-torrent:")) {
            val idx = Integer.parseInt(url.substring(14))
            val info = torrentFileInfoArrayList!!.get(idx)
            if (currentTask > 0) {
                XLTaskHelper.instance().stopTask(currentTask)
                currentTask = 0L
            }
            threadPool!!.execute(object : Runnable {
                override fun run() {
                    val torrentName = File(info.torrentPath).name
                    val cache = cacheRoot + File.separator + torrentName.substring(0, torrentName.lastIndexOf("."))
                    currentTask = XLTaskHelper.instance().addTorrentTask(info.torrentPath, cache, info.mFileIndex)
                    if (currentTask < 0)
                        callback.status(-1, str(R.string.thunder_error_download))
                    var count = 30
                    while (true) {
                        count--
                        if (count <= 0) {
                            callback.status(-1, str(R.string.thunder_error_timeout))
                            break
                        }
                        val taskInfo = XLTaskHelper.instance().getBtSubTaskInfo(currentTask, info.mFileIndex).mTaskInfo
                        when (taskInfo.mTaskStatus) {
                            3 -> {
                                callback.status(-1, errorInfo(taskInfo.mErrorCode))
                                return
                            }
                            1, 4, 2 -> {
                                val pUrl = XLTaskHelper.instance().getLoclUrl(cache + File.separator + info.mFileName)
                                callback.play(pUrl)
                                return
                            }
                        }
                        try {
                            Thread.sleep(1000)
                        } catch (e: InterruptedException) {
                            LOG.e("Thunder", e)
                        }
                    }
                }
            })
            return true
        }
        if (url.startsWith("tvbox-oth:")) {
            stop(false)
            val idx = Integer.parseInt(url.substring(10))
            task_url = ed2kList!!.get(idx)
            name = XLTaskHelper.instance().getFileName(task_url)
            localPath = (File(cacheRoot + File.separator + "temp", FileUtils.getFileNameWithoutExt(name))).toString() + "/"
            currentTask = XLTaskHelper.instance().addThunderTask(task_url, localPath, null)

            threadPool!!.execute(object : Runnable {
                override fun run() {

                    var count = 20
                    while (true) {
                        count--
                        if (count <= 0) {
                            callback.status(-1, str(R.string.thunder_error_timeout))
                            break
                        }
                        val playUrl = getPlayUrl()
                        if (playUrl != null && !playUrl.isEmpty()) {
                            callback.play(playUrl)
                            return
                        }
                        try {
                            Thread.sleep(1000)
                        } catch (e: InterruptedException) {
                            LOG.e("Thunder", e)
                        }
                    }
                }
            })
            return true
        }
        if (isEd2k(url) || isFtp(url)) {
            if (threadPool == null) {
                init(AppContextHolder.context()!!)
                threadPool = Executors.newSingleThreadExecutor()
            }
            if (currentTask > 0) {
                XLTaskHelper.instance().stopTask(currentTask)
                currentTask = 0L
            }
            task_url = url
            name = XLTaskHelper.instance().getFileName(task_url)
            localPath = (File(cacheRoot + File.separator + "temp", FileUtils.getFileNameWithoutExt(name))).toString() + "/"
            currentTask = XLTaskHelper.instance().addThunderTask(task_url, localPath, null)

            threadPool!!.execute(object : Runnable {
                override fun run() {

                    var count = 20
                    while (true) {
                        count--
                        if (count <= 0) {
                            callback.status(-1, str(R.string.thunder_error_timeout))
                            break
                        }
                        val playUrl = getPlayUrl()
                        if (!TextUtils.isEmpty(playUrl)) {
                            callback.play(playUrl!!)
                            return
                        }
                        try {
                            Thread.sleep(1000)
                        } catch (e: InterruptedException) {
                            LOG.e("Thunder", e)
                        }
                    }
                }
            })
            return true
        }
        return false
    }

    private fun errorInfo(code: Int): String {
        return when (code) {
            9125 ->
                str(R.string.thunder_error_name_too_long)
            111120 ->
                str(R.string.thunder_error_path_too_long)
            111142 ->
                str(R.string.thunder_error_file_too_small)
            111085 ->
                str(R.string.thunder_error_no_space)
            111171 ->
                str(R.string.thunder_error_network_refused)
            9301 ->
                str(R.string.thunder_error_buffer)
            114001, 114004, 114005, 114006, 114007, 114011, 9304, 111154 ->
                str(R.string.thunder_error_copyright)
            114101 ->
                str(R.string.thunder_error_invalid_url)
            else ->
                "ErrorCode=" + code
        }
    }

    @JvmStatic
    fun isSupportUrl(url: String?): Boolean {
        val u = url!!
        return isMagnet(u) || isThunder(u) || isTorrent(u)
    }

    private fun isMagnet(url: String): Boolean {
        return url.lowercase().startsWith("magnet:")
    }

    private fun isThunder(url: String): Boolean {
        return url.lowercase().startsWith("thunder")
    }

    private fun isTorrent(url: String): Boolean {
        return RegexUtils.getPattern(";").split(url.lowercase())[0].endsWith(".torrent")
    }

    private fun isEd2k(url: String): Boolean {
        return url.lowercase().startsWith("ed2k:")
    }

    @JvmStatic
    fun isFtp(url: String): Boolean {
        return url.lowercase().startsWith("ftp://")
    }

    private fun recursiveDelete(file: File) {
        if (!file.exists())
            return
        if (file.isDirectory) {
            for (f in file.listFiles()) {
                recursiveDelete(f)
            }
        }
        file.delete()
    }

    private val formats: ArrayList<String> = ArrayList()

    private fun isMedia(name: String): Boolean {
        if (formats.size == 0) {
            formats.add(".rmvb")
            formats.add(".avi")
            formats.add(".mkv")
            formats.add(".flv")
            formats.add(".mp4")
            formats.add(".rm")
            formats.add(".vob")
            formats.add(".wmv")
            formats.add(".mov")
            formats.add(".3gp")
            formats.add(".asf")
            formats.add("mpg")
            formats.add("mpeg")
            formats.add("mpe")
        }
        for (f in formats) {
            if (name.lowercase().endsWith(f))
                return true

        }
        return false
    }

    private fun randomImei(): String {
        return randomString("0123456", 15)
    }

    private fun randomMac(): String {
        return randomString("ABCDEF0123456", 12).uppercase()
    }

    private fun randomString(base: String, length: Int): String {
        val random = Random()
        val sb = StringBuffer()
        for (i in 0 until length) {
            val number = random.nextInt(base.length)
            sb.append(base[number])
        }
        return sb.toString()
    }

    @JvmStatic
    fun isNetworkDownloadTask(url: String): Boolean {
        if (TextUtils.isEmpty(url)) return false
        if (isFtp(url) || isEd2k(url)) {
            return true
        } else {
            return false
        }
    }

    @JvmStatic
    fun stopTask() {
        if (currentTask != 0L) {
            XLTaskHelper.instance().deleteTask(currentTask, if (task_url.isEmpty()) cacheRoot else localPath)
            currentTask = 0L
        }
    }
    @JvmStatic
    fun getTaskInfo(): XLTaskInfo {
        return XLTaskHelper.instance().getTaskInfo(currentTask)
    }

    @JvmStatic
    fun getPlayUrl(): String? {
        if (currentTask != 0L) {
            if (isNetworkDownloadTask(task_url)) {
                return XLTaskHelper.instance().getLoclUrl(localPath + name)
            }
        }
        return null
    }

}
