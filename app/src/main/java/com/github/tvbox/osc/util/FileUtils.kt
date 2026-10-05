package com.github.tvbox.osc.util

import android.os.Environment
import android.text.TextUtils
import android.util.Base64

import com.github.catvod.net.OkHttp
import com.github.tvbox.osc.server.ControlManager
import com.google.gson.Gson
import com.google.gson.JsonObject

import org.json.JSONObject

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

object FileUtils {

    @JvmStatic
    fun writeSimple(data: ByteArray, dst: File): Boolean {
        try {
            if (dst.exists())
                dst.delete()
            val bos = BufferedOutputStream(FileOutputStream(dst))
            bos.write(data)
            bos.close()
            return true
        } catch (e: IOException) {
            LOG.e("FileUtils", e)
        }
        return false
    }

    @JvmStatic
    fun readSimple(src: File): ByteArray? {
        try {
            val bis = BufferedInputStream(FileInputStream(src))
            val len = bis.available()
            val data = ByteArray(len)
            bis.read(data)
            bis.close()
            return data
        } catch (e: IOException) {
            LOG.e("FileUtils", e)
        }
        return null
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(source: File, dest: File) {
        var ins: InputStream? = null
        var os: OutputStream? = null
        try {
            ins = FileInputStream(source)
            os = FileOutputStream(dest)
            val buffer = ByteArray(1024)
            var length: Int
            while (ins.read(buffer).also { length = it } > 0) {
                os.write(buffer, 0, length)
            }
        } finally {
            ins!!.close()
            os!!.close()
        }
    }

    @JvmStatic
    fun recursiveDelete(file: File) {
        if (!file.exists())
            return
        if (file.isDirectory) {
            for (f in file.listFiles()!!) {
                recursiveDelete(f)
            }
        }
        file.delete()
    }

    @JvmStatic
    fun readFileToString(path: String, charsetName: String): String {
        // 定义返回结果
        var jsonString = ""

        var ins: BufferedReader? = null
        try {
            ins = BufferedReader(InputStreamReader(FileInputStream(File(path)), charsetName))// 读取文件
            var thisLine: String? = null
            while (ins.readLine().also { thisLine = it } != null) {
                jsonString += thisLine
            }
            ins.close()
        } catch (e: IOException) {
            LOG.e("FileUtils", e)
        } finally {
            if (ins != null) {
                try {
                    ins.close()
                } catch (el: IOException) {
                    LOG.d("FileUtils", "close reader failed")
                }
            }
        }
        // 返回拼接好的JSON String
        return jsonString
    }

    @JvmStatic
    fun getRootPath(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    @JvmStatic
    fun getLocal(path: String): File {
        return File(path.replace("file:/", getRootPath()))
    }

    @JvmStatic
    fun getCacheDir(): File {
        return AppContextHolder.context()!!.cacheDir
    }

    @JvmStatic
    fun getCachePath(): String {
        return getCacheDir().absolutePath
    }

    @JvmStatic
    fun getFilePath(): String {
        return AppContextHolder.context()!!.filesDir.absolutePath
    }

    @JvmStatic
    fun cleanDirectory(dir: File) {
        if (!dir.exists()) return
        val files = dir.listFiles()
        if (files == null || files.isEmpty()) return
        for (one in files) {
            try {
                deleteFile(one)
            } catch (e: Exception) {
                LOG.e("FileUtils", e)
            }
        }
    }

    @JvmStatic
    fun isWeekAgo(file: File): Boolean {
        val oneWeekMillis = 3L * 24 * 60 * 60 * 1000
        val timeDiff = System.currentTimeMillis() - file.lastModified()
        return timeDiff > oneWeekMillis
    }

    /**
     * 递归删除:文件直接删,目录先删空其内容。
     * ⚠️ 不能把 canWrite() 当删除前提(2026-09-12 修复 Bug):爬虫 jar 释放的加固库是 400 只读
     * (实测 cache/danMugo_v8.so 13,441,840B + cache/.ftyfnw3nr9fd9ykv,两者合计正好是设置页显示的 12.9MB),
     * canWrite() 为 false 时会被直接跳过,于是「清除缓存」怎么点都清不掉、大小恒定不变。
     * 删除只要求**父目录**可写,与文件自身只读无关,故这里先尝试解除只读再删。
     */
    @JvmStatic
    fun deleteFile(file: File) {
        if (!file.exists()) return
        if (file.isFile) {
            deleteSingle(file)
            return
        }
        if (file.isDirectory) {
            val files = file.listFiles()
            if (files == null || files.isEmpty()) {
                deleteSingle(file)
                return
            }
            for (one in files) {
                deleteFile(one)
            }
        }
        return
    }

    /**
     * 递归强删整个目录树:先删子项(文件/子目录),最后删目录本身。
     * 与 {@link #deleteFile} 的"只清内容、保留各级目录"语义不同,本方法连各级子目录一并删除,
     * 供 {@link #purgeExoCacheIfPending} 彻底删除 exo-video-cache 目录树——
     * 否则多级子目录需多次启动才能逐层清空(表现为 purge-incomplete 逐次递减 10→7→3→done)。
     */
    private fun deleteFileTree(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            val files = file.listFiles()
            if (files != null) {
                for (one in files) {
                    deleteFileTree(one)
                }
            }
        }
        // 此处 file 已是文件或已清空的空目录:解除只读再删
        deleteSingle(file)
    }

    /** 删除单个文件/空目录;只读项先解除只读再删,仍失败则留日志便于排查 */
    private fun deleteSingle(file: File) {
        if (!file.canWrite()) file.setWritable(true)
        if (!file.delete()) {
            LOG.i("clearCache: cannot delete " + file.absolutePath)
        }
    }

    /**
     * 启动自检:清掉私有目录里"假的原生库",避免爬虫把应用拖进开机必崩的死循环(2026-09-21)。
     *
     * <p>实测:第三方爬虫在静态初始化里从云存储下载 {@code libwexproxy.so},远端对象已删除时 CDN 返回
     * 313 字节 XML 报错,爬虫把报错原文当 .so 落盘再 {@code System.load} ⇒ {@code bad ELF magic}。
     * 坏文件留在原地 ⇒ 每次冷启动都崩一次,用户连换源都进不去;而那段初始化跑在爬虫自己的线程上,
     * 接不住异常,只能在**装载之前**清掉。
     *
     * <p>判据只认"ELF 魔数不符"(合法库必以 {@code 0x7F 'E' 'L' 'F'} 开头),名字只认 {@code *.so}
     * 与爬虫临时名 {@code .lib*};**.wexstring 之类的非库资源不碰**,也绝不按大小/时间去猜。
     * 扫描范围 = 私有 files 目录树,失败不抛。
     *
     * @return 删掉的坏文件个数
     */
    @JvmStatic
    fun repairBogusNativeLibs(): Int {
        try {
            return repairBogusNativeLibs(File(getFilePath()))
        } catch (e: Throwable) {
            LOG.i("native-lib-repair failed: " + e.message)
            return 0
        }
    }

    /** 扫描指定目录树并清掉假原生库,返回删除个数(公开重载是为了能在临时目录上单测) */
    @JvmStatic
    fun repairBogusNativeLibs(root: File): Int {
        try {
            return repairBogusNativeLibs(root, 0)
        } catch (e: Throwable) {
            LOG.i("native-lib-repair failed: " + e.message)
            return 0
        }
    }

    /** ELF 魔数:0x7F 后跟 ASCII 的 "ELF" */
    private val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    /** 目录深度上限:爬虫目录层级很浅(实测 1 层),限制深度避免在大目录上白跑 */
    private const val REPAIR_MAX_DEPTH = 3

    private fun repairBogusNativeLibs(dir: File?, depth: Int): Int {
        if (dir == null || depth > REPAIR_MAX_DEPTH) return 0
        val files = dir.listFiles() ?: return 0
        var repaired = 0
        for (file in files) {
            if (file.isDirectory) {
                repaired += repairBogusNativeLibs(file, depth + 1)
                continue
            }
            if (!looksLikeNativeLib(file.name)) continue
            if (hasElfMagic(file)) continue
            // 大小要在删之前取:删完 length() 恒为 0(实测这个值就是 313,直接指向 CDN 报错页)
            val size = file.length()
            deleteSingle(file)
            if (!file.exists()) {
                repaired++
                LOG.i("native-lib-repair removed bogus " + file.absolutePath + " size=" + size)
            }
        }
        return repaired
    }

    /** 名字像原生库才检查:{@code *.so} 或爬虫的临时名 {@code .lib*} */
    private fun looksLikeNativeLib(name: String?): Boolean {
        if (name == null || name.isEmpty()) return false
        val lower = name.lowercase()
        return lower.endsWith(".so") || lower.startsWith(".lib")
    }

    /** 读前 4 字节比对 ELF 魔数;读不到(不存在/无权限)一律当作"不是合法库"留给调用方决定 */
    private fun hasElfMagic(file: File): Boolean {
        try {
            FileInputStream(file).use { ins ->
                val magic = ByteArray(ELF_MAGIC.size)
                if (ins.read(magic) != ELF_MAGIC.size) return false
                for (i in ELF_MAGIC.indices) {
                    if (magic[i] != ELF_MAGIC[i]) return false
                }
                return true
            }
        } catch (e: Throwable) {
            return false
        }
    }

    @JvmStatic
    fun cleanPlayerCache() {
        val thunderCachePath = getCachePath() + "/thunder/"
        val thunderCacheDir = File(thunderCachePath)
        try {
            if (thunderCacheDir.exists()) cleanDirectory(thunderCacheDir)
        } catch (e: Exception) {
            LOG.e("FileUtils", e)
        }
    }

    /**
     * 外置缓存里属于**用户数据**、清理时必须保留的一级目录:config/ = 老的本地源配置副本
     * (2026-09-16 起新副本改放外置 files,见 {@link #getExternalFilesPath()};clan:// 地址直接指向该文件,
     * 删掉等于把订阅源弄丢)。⚠️ 系统「清除缓存」仍会删它(历史遗留:用户数据本不该放在 cacheDir)。
     */
    private const val EXTERNAL_CACHE_KEEP_DIR = "config"

    /**
     * 应用缓存占用(内部缓存 + 外部缓存,不含 {@link #EXTERNAL_CACHE_KEEP_DIR} 用户数据)。
     * 口径说明:Exo 视频缓存目录 exo-video-cache 位于外部缓存,只统计内部缓存会漏掉绝大部分占用;
     * 外部缓存不可用时 getExternalCacheDir() 返回 null,此时不叠加,避免与内部缓存重复计数。
     * 目录递归统计是耗时 IO,须在后台线程调用。
     */
    @JvmStatic
    fun getCacheSize(): Long {
        var size = directorySize(getCacheDir(), null)
        val externalCacheDir = AppContextHolder.context()!!.externalCacheDir
        if (externalCacheDir != null && externalCacheDir.absolutePath != getCachePath()) {
            size += directorySize(externalCacheDir, EXTERNAL_CACHE_KEEP_DIR)
        }
        return size
    }

    /** [skipChildName] 只在顶层生效:跳过该名字的一级子项(用于排除外置缓存里的用户数据) */
    private fun directorySize(dir: File?, skipChildName: String?): Long {
        if (dir == null || !dir.exists()) return 0
        if (dir.isFile) return dir.length()
        val files = dir.listFiles() ?: return 0
        var size = 0L
        for (one in files) {
            if (skipChildName != null && skipChildName == one.name) continue
            size += if (one.isDirectory) directorySize(one, null) else one.length()
        }
        return size
    }

    /** 字节数 → 展示文本(空缓存显示 0KB);与统计一致,须在后台线程调用 */
    @JvmStatic
    fun formatCacheSize(bytes: Long): String {
        if (bytes <= 0) return "0KB"
        if (bytes < 1024L * 1024L) return Math.max(1L, bytes / 1024).toString() + "KB"
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024)
        return String.format(Locale.US, "%.2fGB", bytes / 1024.0 / 1024 / 1024)
    }

    /** Exo 边播缓存目录名:与 ExoMediaSourceHelper 的共享 SimpleCache 目录保持一致 */
    private const val EXO_CACHE_DIR_NAME = "exo-video-cache"

    /** 「清除缓存」写下的待清理标记:由 {@link #purgeExoCacheIfPending()} 在下次启动早期执行 */
    private const val PENDING_EXO_CLEAR_FLAG = "clear_exo_cache.pending"

    /**
     * 清理应用缓存(内部 + 外部,含 exo-video-cache)。2026-09-14 调整:**改为直接删除 exo-video-cache**
     * —— 对齐 fongmi(FileUtil.clearCache → Path.clear(Path.cache) 递归强删整个 cacheDir 含 exo 子目录)。
     * 进程级共享 SimpleCache 常驻,目录被删会"内存索引/磁盘失配",但 CacheDataSource 已设
     * FLAG_IGNORE_CACHE_ON_ERROR 兜底(不崩溃),下次播放缓存 miss 回源,SimpleCache 自愈(移除不存在的 span)。
     * 故点「清除缓存」后数字立即归零,不再延迟到下次启动(此前 2026-09-13 的"写待清理标记 + 下次启动 purge"
     * 方案被本次替换;{@link #purgeExoCacheIfPending()} 保留以清理历史遗留标记)。
     * 用 {@link #deleteFileTree} 递归强删整个目录树(含各级子目录),避免 deleteFile "只清内容保留目录"留下空子目录。
     * 与系统设置里的「清除缓存」同语义,因此也会清掉已下载的爬虫 jar 缓存(cache/jar/),
     * 下次按需重新下载;{@link #EXTERNAL_CACHE_KEEP_DIR} 用户数据(config 订阅源)保留不动。
     * 耗时 IO,须在后台线程调用。
     */
    @JvmStatic
    fun clearCache() {
        // ① 内部缓存:逐项强删(含回落到内部的 exo-video-cache,不再跳过)
        val cacheDir = getCacheDir()
        val innerFiles = cacheDir.listFiles()
        if (innerFiles != null) {
            for (one in innerFiles) {
                try {
                    deleteFileTree(one)
                } catch (e: Exception) {
                    LOG.e("FileUtils", e)
                }
            }
        }
        // ② 外部缓存:逐项强删,跳过 config(用户订阅源数据)
        val externalCacheDir = AppContextHolder.context()!!.externalCacheDir
        if (externalCacheDir == null) return
        val files = externalCacheDir.listFiles() ?: return
        for (one in files) {
            if (EXTERNAL_CACHE_KEEP_DIR == one.name) continue
            try {
                deleteFileTree(one)
            } catch (e: Exception) {
                LOG.e("FileUtils", e)
            }
        }
    }

    /**
     * 「清除缓存」遗留的 Exo 视频缓存清理(2026-09-13):在 App 启动早期调用,
     * **必须早于 ExoMediaSourceHelper.getSharedCache 的首次创建**(SimpleCache 尚未持有目录/索引时删除才安全)。
     * 无待清理标记时仅一次文件存在性检查,零开销;有标记时删除 exo-video-cache 目录(含索引),
     * 内容清空才清除标记,否则保留标记待下次启动重试(删除中途进程被杀也保留标记,下次继续)。
     * 目录删除为耗时 IO,须在后台线程调用。
     */
    @JvmStatic
    fun purgeExoCacheIfPending() {
        val flag = pendingExoClearFlag()
        if (!flag.exists()) return
        val exoDir = File(getExternalCachePath(), EXO_CACHE_DIR_NAME)
        LOG.i("echo-exo-cache-purge-start: " + exoDir.absolutePath)
        try {
            // 强删整个目录树(含各级子目录):deleteFile 只清内容、保留各级目录,会留空子目录导致
            // purge-incomplete 逐次递减(10→7→3→done),需多次启动才清干净
            deleteFileTree(exoDir)
        } catch (e: Exception) {
            LOG.e("FileUtils", e)
        }
        val remaining = if (exoDir.exists()) exoDir.listFiles() else null
        if (remaining == null || remaining.isEmpty()) {
            if (exoDir.exists()) exoDir.delete() // 兜底:理论上 deleteFileTree 已删,防御性再删一次
            flag.delete() // 确认清理完成后才清标记:删除失败/进程中途被杀都保留标记,下次启动继续
            LOG.i("echo-exo-cache-purge-done")
        } else {
            // 详情取证:逐项打剩余项的类型/可写/大小/路径,定位强删后仍删不掉的根因(疑似只读/被占用)
            for (r in remaining) {
                LOG.i("echo-exo-cache-remain: type=" + (if (r.isDirectory) "DIR" else "FILE")
                        + " writable=" + r.canWrite()
                        + " size=" + r.length()
                        + " path=" + r.absolutePath)
            }
            // 少量文件删除失败:保留标记,下次启动重试
            LOG.i("echo-exo-cache-purge-incomplete: " + remaining.size)
        }
    }

    /** 「清除缓存」遗留的待清理标记文件(见 {@link #clearCache()} 的说明) */
    private fun pendingExoClearFlag(): File {
        return File(getFilePath(), PENDING_EXO_CLEAR_FLAG)
    }

    @JvmStatic
    fun clearSpiderCacheFiles() {
        cleanDirectory(File(getFilePath() + "/csp/"))
        cleanDirectory(File(getCachePath() + "/jar/"))
        cleanDirectory(File(getCachePath() + "/py/"))
        cleanDirectory(File(getCachePath() + "/catvod_jsapi/"))
        clearJsModuleCache()
    }

    private fun clearJsModuleCache() {
        val externalCacheDir = File(getExternalCachePath())
        val files = externalCacheDir.listFiles() ?: return
        for (file in files) {
            if (file != null && file.name.startsWith("qjscache_")) {
                deleteFile(file)
            }
        }
    }

    @JvmStatic
    fun read(path: String): String {
        try {
            val br = BufferedReader(InputStreamReader(FileInputStream(getLocal(path))))
            val sb = StringBuilder()
            var text: String? = null
            while (br.readLine().also { text = it } != null) sb.append(text).append("\n")
            br.close()
            return sb.toString()
        } catch (e: Exception) {
            return ""
        }
    }

    @JvmStatic
    fun getFileName(filePath: String): String {
        if (TextUtils.isEmpty(filePath)) return ""
        var fileName = filePath
        val p = fileName.lastIndexOf(File.separatorChar)
        if (p != -1) {
            fileName = fileName.substring(p + 1)
        }
        return fileName
    }

    @JvmStatic
    fun getFileNameWithoutExt(filePath: String): String {
        if (TextUtils.isEmpty(filePath)) return ""
        var fileName = filePath
        var p = fileName.lastIndexOf(File.separatorChar)
        if (p != -1) {
            fileName = fileName.substring(p + 1)
        }
        p = fileName.indexOf('.')
        if (p != -1) {
            fileName = fileName.substring(0, p)
        }
        return fileName
    }

    @JvmStatic
    fun hasExtension(path: String): Boolean {
        val lastDotIndex = path.lastIndexOf(".")
        val lastSlashIndex = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"))
        // 如果路径中有点号，并且点号在最后一个斜杠之后，认为有后缀
        return lastDotIndex > lastSlashIndex && lastDotIndex < path.length - 1
    }

    @JvmStatic
    fun saveCache(cache: File, json: String) {
        try {
            val cacheDir = cache.parentFile
            if (!cacheDir.exists())
                cacheDir.mkdirs()
            if (cache.exists())
                cache.delete()
            val fos = FileOutputStream(cache)
            fos.write(json.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.close()
        } catch (th: Throwable) {
            LOG.e("FileUtils", th)
        }
    }

    //JS  工具方法
    private val URL_JOIN = Pattern.compile("^http.*\\.(js|txt|json)", Pattern.MULTILINE or Pattern.CASE_INSENSITIVE)

    @JvmStatic
    fun loadModule(name: String): String? {
        var rel: String? = null
        try {
            var name = name
            if (name.contains("gbk.js")) {
                name = "gbk.js"
            } else if (name.contains("模板.js")) { // i18n: keep(R4:模板.js 文件名约定)
                name = "模板.js" // i18n: keep(R4:模板.js 文件名约定)
            } else if (name.contains("cat.js")) {
                name = "cat.js"
            }
            LOG.i("echo-loadModule " + name)
            val m = URL_JOIN.matcher(name)
            if (m.find()) {
                val cache = getCache(MD5.encode(name)!!)
                rel = cache
                if (StringUtils.isEmpty(cache)) {
                    val netStr = get(name)
                    if (!TextUtils.isEmpty(netStr)) {
                        setCache(604800, MD5.encode(name)!!, netStr)
                    }
                    rel = netStr
                }
            } else if (name.startsWith("assets://")) {
                rel = getAsOpen(name.substring(9))
            } else if (isAsFile(name, "js/lib")) {
                rel = getAsOpen("js/lib/" + name)
            } else if (name.startsWith("file://")) {
                rel = get(ControlManager.get()
                        .getAddress(true) + "file/" + name.replace("file:///", "")
                        .replace("file://", ""))
            } else if (name.startsWith("clan://localhost/")) {
                rel = get(ControlManager.get()
                        .getAddress(true) + "file/" + name.replace("clan://localhost/", ""))
            } else if (name.startsWith("clan://")) {
                val substring = name.substring(7)
                val indexOf = substring.indexOf('/')
                rel = get("http://" + substring.substring(0, indexOf) + "/file/" + substring.substring(indexOf + 1))
            }
        } catch (e: Exception) {
            LOG.e("FileUtils", e)
        }
        return rel
    }

    // BugReview #26:被 NanoHTTPD 线程、主线程、QuickJS 线程并发读写,改并发容器
    private val cachedDirFiles: MutableMap<String, Set<String>> = ConcurrentHashMap()

    @JvmStatic
    fun isAsFile(name: String, dir: String): Boolean {
        // 1. 先从缓存里取目录列表
        var files: Set<String>? = cachedDirFiles[dir]
        if (files == null) {
            LOG.i("echo-读取AssetsList")
            try {
                val list = AppContextHolder.context()!!.assets.list(dir)
                files = HashSet(Arrays.asList(*list!!))
            } catch (e: IOException) {
                files = Collections.emptySet()
            }
            cachedDirFiles[dir] = files
        }
        // 2. 内存查找
        return files.contains(name.trim { it <= ' ' })
    }

    @JvmStatic
    fun getAsOpen(name: String): String {
        try {
            val ins = AppContextHolder.context()!!.assets.open(name)
            val data = ByteArray(ins.available())
            ins.read(data)
            return String(data, Charsets.UTF_8)
        } catch (e: Exception) {
            LOG.e("FileUtils", e)
        }
        return ""
    }

    @JvmStatic
    fun getCache(name: String): String {
        try {
            var code = ""
            val file = open(name)
            if (file.exists()) {
                code = String(readSimple(file)!!, Charset.defaultCharset())
            }
            if (TextUtils.isEmpty(code)) {
                return ""
            }
            val asJsonObject = (Gson().fromJson(code, JsonObject::class.java)).asJsonObject
            if (asJsonObject.get("expires").asInt.toLong() <= System.currentTimeMillis() / 1000) {
                recursiveDelete(open(name))
            }
            return asJsonObject.get("data").asString
        } catch (e4: Exception) {
            return ""
        }
    }

    @JvmStatic
    fun setCache(time: Int, name: String, data: String) {
        try {
            val jSONObject = JSONObject()
            jSONObject.put("expires", (time + (System.currentTimeMillis() / 1000)).toInt())
            jSONObject.put("data", data)
            writeSimple(jSONObject.toString().toByteArray(Charset.defaultCharset()), open(name))
        } catch (e: Exception) {
            LOG.e("FileUtils", e)
        }
    }

    @JvmStatic
    fun setCacheByte(name: String, data: ByteArray) {
        try {
            writeSimple(byteMerger("//DRPY".toByteArray(Charset.defaultCharset()), Base64.encode(data, Base64.URL_SAFE)), open("B_" + name))
        } catch (e: Exception) {
            LOG.e("FileUtils", e)
        }
    }

    @JvmStatic
    fun byteMerger(bt1: ByteArray, bt2: ByteArray): ByteArray {
        val bt3 = ByteArray(bt1.size + bt2.size)
        System.arraycopy(bt1, 0, bt3, 0, bt1.size)
        System.arraycopy(bt2, 0, bt3, bt1.size, bt2.size)
        return bt3
    }

    @JvmStatic
    fun get(str: String): String {
        return get(str, null)
    }

    @JvmStatic
    fun get(str: String, headerMap: Map<String, String>?): String {
        val headers = headerMap ?: HashMap<String, String>().also {
            it["User-Agent"] = if (str.startsWith("https://gitcode.net/")) UA.random() else "okhttp/3.15"
        }
        return OkHttp.string(str, headers)
    }

    @JvmStatic
    fun open(str: String): File {
        return File(getExternalCachePath() + "/qjscache_" + str + ".js")
    }

    @JvmStatic
    fun getExternalCachePath(): String {
        val externalCacheDir = AppContextHolder.context()!!.externalCacheDir
        if (externalCacheDir == null) {
            return getCachePath()
        }
        return externalCacheDir.absolutePath
    }

    /**
     * 外置私有 files 目录:本地源配置副本等用户数据的落点(2026-09-16 起由外置 cache 迁来,避免被
     * 系统「清除缓存」删掉后只能静默回落到 filesDir 旧快照);外置不可用时回落内部 files 目录。
     */
    @JvmStatic
    fun getExternalFilesPath(): String {
        val externalFilesDir = AppContextHolder.context()!!.getExternalFilesDir(null)
        if (externalFilesDir == null) {
            return getFilePath()
        }
        return externalFilesDir.absolutePath
    }
}
