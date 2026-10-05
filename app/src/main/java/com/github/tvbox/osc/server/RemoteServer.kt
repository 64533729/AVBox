package com.github.tvbox.osc.server

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import android.text.TextUtils
import android.util.Base64

import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.event.ServerEvent
import com.github.tvbox.osc.util.AppContextHolder
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LocalSourceTree
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.Proxy
import com.github.tvbox.osc.util.RegexUtils
import com.google.gson.JsonArray
import com.google.gson.JsonObject

import org.greenrobot.eventbus.EventBus

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

import fi.iki.elonen.NanoHTTPD
import okio.Buffer

/**
 * @author pj567
 * @date :2021/1/5
 * @description:
 */
class RemoteServer(port: Int, context: Context) : NanoHTTPD(port) {
    private val mContext: Context = context

    private var isStarted = false

    private val getRequestList = ArrayList<RequestProcess>()
    private val postRequestList = ArrayList<RequestProcess>()

    init {
        addGetRequestProcess()
        addPostRequestProcess()
    }

    private fun addGetRequestProcess() {
        getRequestList.add(CacheRequestProcess())
    }

    private fun addPostRequestProcess() {
        postRequestList.add(CacheRequestProcess())
    }

    override fun start(timeout: Int, daemon: Boolean) {
        isStarted = true
        super.start(timeout, daemon)
        EventBus.getDefault().post(ServerEvent(ServerEvent.SERVER_SUCCESS))
    }

    override fun stop() {
        super.stop()
        isStarted = false
    }

    private fun getProxy(rs: Array<Any?>?): NanoHTTPD.Response {
        try {
            if (rs == null || rs.size < 3) {
                LOG.e("echo-proxy error: empty proxy result")
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "500")
            }
            if (rs[0] is NanoHTTPD.Response) return rs[0] as NanoHTTPD.Response
            val code = rs[0] as Int
            val mime = rs[1] as String
            val stream = if (rs[2] != null) rs[2] as InputStream else null
            val response = NanoHTTPD.newChunkedResponse(
                NanoHTTPD.Response.Status.lookup(code),
                mime,
                stream
            )
            // 添加头部信息
            if (rs.size >= 4 && rs[3] is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val mapHeader = rs[3] as Map<String, String>
                if (mapHeader.isNotEmpty()) {
                    for (key in mapHeader.keys) {
                        response.addHeader(key, mapHeader[key])
                    }
                }
            }
            return response
        } catch (th: Throwable) {
            LOG.e("echo-proxy error: " + th.message)
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "500")
        }
    }

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        EventBus.getDefault().post(ServerEvent(ServerEvent.SERVER_CONNECTION))
        if (!session.uri.isEmpty()) {
            var fileName = session.uri.trim { it <= ' ' }
            if (fileName.indexOf('?') >= 0) {
                fileName = fileName.substring(0, fileName.indexOf('?'))
            }
            if (session.method == NanoHTTPD.Method.GET) {
                if (isProxyRequest(fileName, session.parms)) {
                    return handleProxy(session)
                }
                for (process in getRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.parms, null)
                    }
                }
                if (fileName.startsWith("/file/")) {
                    try {
                        val f = fileName.substring(6)
                        // BugReview P3:路径遍历防护——拒绝含 ".." 的路径,防上溯读取 app 私有目录
                        if (f.contains("..")) {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "Forbidden")
                        }
                        val root = Environment.getExternalStorageDirectory().absolutePath
                        val file = "$root/$f"
                        val localFile = File(file)
                        if (localFile.isDirectory) {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, fileList(root, f))
                        }
                        if (localFile.isFile) {
                            try {
                                return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", FileInputStream(localFile))
                            } catch (ignored: Throwable) {
                                // 文件在但读不到(没开「所有文件访问」等)⇒ 交给下面的目录授权兜底
                                LOG.d("RemoteServer", "file open failed, fallback to tree grant")
                            }
                        }
                        // 本地源目录授权(SAF):应用自己读不到原目录时靠它直引原目录,副本不必搬。
                        // 只服务回环请求 —— 应用读原目录走的就是 127.0.0.1,没必要把"应用都读不到的目录"再开给局域网客户端
                        val granted = if (isLocalRequest(session)) LocalSourceTree.open(AppContextHolder.context()!!, f) else null
                        if (granted != null) {
                            return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", granted)
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "File $file not found!")
                    } catch (th: Throwable) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, th.message)
                    }
                } else if (fileName == "/dns-query") {
                    val name = session.parms["name"]
                    var rs = ByteArray(0)
                    try {
                        val dnsOverHttps = OkGoHelper.dnsOverHttps
                        if (dnsOverHttps != null && !TextUtils.isEmpty(name)) {
                            val host = name!!
                            rs = buildDnsResponse(host, dnsOverHttps.lookup(host))
                        }
                    } catch (th: Throwable) {
                        rs = ByteArray(0)
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/dns-message", ByteArrayInputStream(rs), rs.size.toLong())
                } else if (fileName.startsWith("/proxyM3u8")) {
                    // 2026-09-13:按请求携带的键取内容;键缺失/不匹配(旧播放器的重试/重连)=404,
                    // 让它走失败链路,而不是串到"最后一次净化"的新一集列表
                    val content = getM3u8Content(session.parms["k"])
                    if (content == null) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "m3u8 slot not found")
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/vnd.apple.mpegurl", content)
                } else if (fileName.startsWith("/dash/")) {
                    val dashData = App.getInstance()?.getDashData()
                    try {
                        val data = String(Base64.decode(dashData, Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
                        return NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.OK,
                            "application/dash+xml",
                            data
                        )
                    } catch (th: Throwable) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, dashData)
                    }
                }
            } else if (session.method == NanoHTTPD.Method.POST) {
                val files = HashMap<String, String>()
                try {
                    if (session.headers.containsKey("content-type")) {
                        val hd = session.headers["content-type"]
                        if (hd != null) {
                            // cuke: 修正中文乱码问题
                            if (hd.lowercase().contains("multipart/form-data") && !hd.lowercase().contains("charset=")) {
                                val matcher = RegexUtils.getPattern("[ |\t]*(boundary[ |\t]*=[ |\t]*['|\"]?[^\"^'^;^,]*['|\"]?)", Pattern.CASE_INSENSITIVE).matcher(hd)
                                val boundary = if (matcher.find()) matcher.group(1) else null
                                if (boundary != null) {
                                    session.headers["content-type"] = "multipart/form-data; charset=utf-8; " + boundary
                                }
                            }
                        }
                    }
                    session.parseBody(files)
                } catch (ioExc: IOException) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + ioExc.message)
                } catch (rex: NanoHTTPD.ResponseException) {
                    return createPlainTextResponse(rex.status, rex.message)
                }
                for (process in postRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.parms, files)
                    }
                }
            }
        }
        return createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Not Found")
    }

    private fun isProxyRequest(fileName: String, params: Map<String, String>?): Boolean {
        if (params == null) return false
        if (!params.containsKey("do") && !params.containsKey("go")) return false
        return fileName == "/proxy" || fileName == "/"
    }

    private fun handleProxy(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        // BugReview P3:请求头先并入、URL 参数后并入(URL 参数优先),防名为 do/go/url/header/siteKey
        // 的请求头覆盖同名 query 参数改写代理路由
        val params = HashMap<String, String>(session.headers)
        params.putAll(session.parms)
        if (params.containsKey("do")) {
            val isDanmuProxy = "danmu" == params["do"]
            if (isDanmuProxy) normalizeDanmuParams(params)
            if (isDanmuProxy) LOG.i("echo-proxy-danmu params: " + params.toString())
            val rs = ApiConfig.get().proxyLocal(params)
            return getProxy(rs)
        }
        if (params.containsKey("go")) {
            val rs = Proxy.proxy(params)
            return getProxy(rs)
        }
        return getProxy(null)
    }

    private fun normalizeDanmuParams(params: MutableMap<String, String>) {
        try {
            val vodInfo = App.getInstance()?.getVodInfo() ?: return
            if (!TextUtils.isEmpty(vodInfo.name)) params["vodName"] = vodInfo.name!!
            if (!isNumeric(params["vodIndex"])) {
                val episode = getCurrentEpisodeIndex(vodInfo)
                if (!TextUtils.isEmpty(episode)) params["vodIndex"] = episode!!
            }
        } catch (th: Throwable) {
            LOG.e("echo-proxy-danmu normalize error: " + th.message)
        }
    }

    private fun getCurrentEpisodeIndex(vodInfo: VodInfo): String? {
        if (vodInfo.seriesMap != null && !TextUtils.isEmpty(vodInfo.playFlag)) {
            val series = vodInfo.seriesMap!![vodInfo.playFlag]
            if (series != null && vodInfo.playIndex >= 0 && vodInfo.playIndex < series.size) {
                val current = series[vodInfo.playIndex]
                if (!TextUtils.isEmpty(current.name)) {
                    val number = extractNumber(current.name)
                    return if (TextUtils.isEmpty(number)) current.name else number
                }
            }
        }
        return (Math.max(0, vodInfo.playIndex) + 1).toString()
    }

    private fun isNumeric(text: String?): Boolean {
        return !TextUtils.isEmpty(text) && text!!.matches("\\d+".toRegex())
    }

    private fun extractNumber(text: String?): String {
        if (TextUtils.isEmpty(text)) return ""
        val matcher = RegexUtils.getPattern("\\d+").matcher(text)
        return if (matcher.find()) matcher.group() else ""
    }

    fun isStarting(): Boolean {
        return isStarted
    }

    fun getServerAddress(): String {
        val ipAddress = getLocalIPAddress(mContext)
        return "http://" + ipAddress + ":" + RemoteServer.serverPort + "/"
    }

    fun getLoadAddress(): String {
        return "http://127.0.0.1:" + RemoteServer.serverPort + "/"
    }

    /** 请求是否来自应用本机(回环);局域网客户端不算 */
    private fun isLocalRequest(session: NanoHTTPD.IHTTPSession): Boolean {
        val address = session.remoteIpAddress
        return address != null && (address.startsWith("127.") || address == "::1")
    }

    private fun fileTime(time: Long, fmt: String): String {
        val calendar = Calendar.getInstance()
        calendar.setTimeInMillis(time)
        val date = calendar.time
        val sdf = SimpleDateFormat(fmt)
        return sdf.format(date)
    }

    private fun fileList(root: String, path: String): String {
        val file = File("$root/$path")
        val list = file.listFiles()
        val info = JsonObject()
        info.addProperty("remote", getServerAddress().replace("http://", "clan://"))
        info.addProperty("del", 0)
        if (path.isEmpty()) {
            info.addProperty("parent", ".")
        } else {
            info.addProperty("parent", file.parentFile.absolutePath.replace("$root/", "").replace(root, ""))
        }
        if (list == null || list.isEmpty()) {
            info.add("files", JsonArray())
            return info.toString()
        }
        list.sortWith { o1, o2 ->
            if (o1.isDirectory && o2.isFile) -1
            else if (o1.isFile && o2.isDirectory) 1
            else o1.name.compareTo(o2.name)
        }
        val result = JsonArray()
        for (f in list) {
            if (f.name.startsWith(".")) {
                if (f.name == ".tvbox_folder") {
                    info.addProperty("del", 1)
                }
                continue
            }
            val fileObj = JsonObject()
            fileObj.addProperty("name", f.name)
            fileObj.addProperty("path", f.absolutePath.replace("$root/", ""))
            fileObj.addProperty("time", fileTime(f.lastModified(), "yyyy/MM/dd aHH:mm:ss"))
            fileObj.addProperty("dir", if (f.isDirectory) 1 else 0)
            result.add(fileObj)
        }
        info.add("files", result)
        return info.toString()
    }

    companion object {
        @JvmField
        var serverPort = 9978

        /**
         * 去广告 m3u8 内容槽位(key → 内容;2026-09-13 起带键,取代此前的无参单槽)。
         * 背景:proxyUrl 原先不带任何身份参数、服务端直接吐"最后一次净化"的内容 ⇒ 切集后旧播放器的
         * 重试/重连请求会拿到新一集的列表。现按请求 {@code ?k=} 取:键不匹配/缺失返 404,旧播放器走
         * 失败链路而不是串集。保留最近 [M3U8_SLOT_LIMIT] 条(访问序 LRU)——在播集反复重拉不会被冲掉。
         * 内容是小文本(几 KB),纯内存、无持久化;进程重启后重新净化即产生新键。
         */
        private const val M3U8_SLOT_LIMIT = 4

        private val m3u8Seq = AtomicLong(0)

        private val m3u8Slots: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(8, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
                    return size > M3U8_SLOT_LIMIT
                }
            })

        /** 写入净化结果并返回本次的键(proxyUrl 用 {@code ?k=<key>} 取);仅在真正走代理播放时调用 */
        @JvmStatic
        fun putM3u8Content(content: String): String {
            val key = "${System.currentTimeMillis()}-${m3u8Seq.incrementAndGet()}"
            m3u8Slots[key] = content
            return key
        }

        /** 按键取净化结果;键缺失或已被 LRU 淘汰返回 null(调用方应答 404,不返回错误进度) */
        @JvmStatic
        fun getM3u8Content(key: String?): String? {
            return if (key == null) null else m3u8Slots[key]
        }

        @JvmStatic
        fun createPlainTextResponse(status: NanoHTTPD.Response.IStatus, text: String?): NanoHTTPD.Response {
            return NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text)
        }

        @SuppressLint("DefaultLocale")
        @JvmStatic
        fun getLocalIPAddress(context: Context?): String {
            val wifiManager = context!!.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipAddress = wifiManager.connectionInfo.ipAddress
            if (ipAddress == 0) {
                try {
                    val enumerationNi = NetworkInterface.getNetworkInterfaces()
                    while (enumerationNi.hasMoreElements()) {
                        val networkInterface = enumerationNi.nextElement()
                        val interfaceName = networkInterface.displayName
                        if (interfaceName == "eth0" || interfaceName == "wlan0") {
                            val enumIpAddr = networkInterface.inetAddresses
                            while (enumIpAddr.hasMoreElements()) {
                                val inetAddress = enumIpAddr.nextElement()
                                if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                                    return inetAddress.hostAddress!!
                                }
                            }
                        }
                    }
                } catch (e: SocketException) {
                    LOG.e("RemoteServer", e)
                }
            } else {
                return String.format("%d.%d.%d.%d", (ipAddress and 0xff), (ipAddress shr 8 and 0xff), (ipAddress shr 16 and 0xff), (ipAddress shr 24 and 0xff))
            }
            return "0.0.0.0"
        }

        /** 把 DoH 解析结果编码为合法的 DNS 应答报文(单条 question + 每条地址按自身地址族写 TYPE/RDLENGTH) */
        @JvmStatic
        fun buildDnsResponse(hostname: String, addresses: List<InetAddress>): ByteArray {
            // 客户端只给 name、拿不到它请求的 QTYPE:非纯 IPv6(含无地址的 SERVFAIL)一律按 A 标
            var ipv6Only = addresses.isNotEmpty()
            for (address in addresses) {
                if (address !is Inet6Address) {
                    ipv6Only = false
                    break
                }
            }
            // 无地址时回 SERVFAIL(rCode=2),避免返回空报文
            val rCode = if (addresses.isEmpty()) 2 else 0
            val buffer = Buffer()
            buffer.writeShort(0) // ID
            buffer.writeShort(0x8180 or rCode) // 标准响应 + 递归可用
            buffer.writeShort(1) // QDCOUNT
            buffer.writeShort(addresses.size) // ANCOUNT
            buffer.writeShort(0) // NSCOUNT
            buffer.writeShort(0) // ARCOUNT
            for (label in RegexUtils.getPattern("\\.").split(hostname)) {
                val raw = label.toByteArray(Charsets.UTF_8)
                buffer.writeByte(raw.size)
                buffer.write(raw)
            }
            buffer.writeByte(0) // 名字结束
            buffer.writeShort(if (ipv6Only) 0x001c else 0x0001) // QTYPE: A / AAAA
            buffer.writeShort(1) // CLASS_IN
            for (address in addresses) {
                val ipv6 = address is Inet6Address
                val raw = address.address
                buffer.writeByte(0xc0)
                buffer.writeByte(0x0c) // 名字指针 → 指向 question 中的名字
                buffer.writeShort(if (ipv6) 0x001c else 0x0001) // TYPE: A / AAAA
                buffer.writeShort(1) // CLASS_IN
                buffer.writeInt(60) // TTL 60s
                buffer.writeShort(raw.size) // RDLENGTH 必须等于实际写入的地址字节数
                buffer.write(raw)
            }
            return buffer.readByteString().toByteArray()
        }
    }
}
