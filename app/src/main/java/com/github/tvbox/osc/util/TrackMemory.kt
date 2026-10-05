package com.github.tvbox.osc.util

import androidx.media3.common.C

import java.util.Locale

/**
 * 轨道与字幕来源的记忆。
 *
 * <p>两条不变量:① **键 = 源 + 片**(含集号/线路就换集读不到);② 值 = 轨道**指纹**,
 * 还原时在**当前**轨道里重新定位 —— `(渲染器,组,轨)` 下标只在一次起播的映射里有效,存了就是错的。
 *
 * <p>槽位取值(字幕槽是"来源决定",四者互斥):
 * <pre>
 * A/&lt;语言&gt;/&lt;编码&gt;/&lt;声道&gt;      音轨指纹
 * V/&lt;编码&gt;/&lt;宽&gt;x&lt;高&gt;         视轨指纹
 * T/&lt;语言&gt;/&lt;编码&gt;            内置字幕指纹
 * #off                      关闭字幕
 * #local:&lt;路径&gt;             本地字幕文件(路径在缓存目录,被系统清掉即按失效回落)
 * #online:&lt;发布页&gt;|&lt;文件名&gt;   在线字幕(VOD 直链只对当集有效,入库的是发布页)
 * </pre>
 *
 * <p>无状态工具类。动态键无法逐键登记 KVKeySpec,调用侧一律带具体默认值({@code ""})。
 */
object TrackMemory {

    private const val TYPE_AUDIO = C.TRACK_TYPE_AUDIO
    private const val TYPE_VIDEO = C.TRACK_TYPE_VIDEO
    private const val TYPE_TEXT = C.TRACK_TYPE_TEXT

    private const val KEY_PREFIX = "track_mem_"
    /** 内容键里"源"与"片"的分隔符(sourceKey/id 由源站给出,不含 '@') */
    private const val CONTENT_SEP = "@"
    private const val SLOT_AUDIO = "audio"
    private const val SLOT_VIDEO = "video"
    private const val SLOT_TEXT = "text"

    /** 字幕槽位里以它开头即"不是轨道指纹",而是来源决定 */
    private const val MARK = "#"
    const val SUBTITLE_OFF = "#off"
    private const val SUBTITLE_LOCAL = "#local:"
    private const val SUBTITLE_ONLINE = "#online:"
    /** 发布页与文件名的分隔符(URL/文件名里的 '|' 不可能出现) */
    private const val ONLINE_SEP = "|"

    // ==================== 内容键 ====================

    /** 记忆的作用域键:同一部片的所有集、所有线路共用一把;直播/无剧集信息返回空串(调用侧据此跳过读写) */
    @JvmStatic
    fun contentKey(sourceKey: String?, vodId: String?): String {
        val source = trim(sourceKey)
        val id = trim(vodId)
        if (source.isEmpty() || id.isEmpty()) return ""
        return source + CONTENT_SEP + id
    }

    // ==================== 指纹 ====================

    /** 音轨指纹:语言 + 编码 + 声道。三者缺一就分不开"同语言不同编码"(国语 AAC 与国语 E-AC3) */
    @JvmStatic
    fun audioFingerprint(language: String?, codec: String?, channels: Int): String {
        return "A" + "/" + field(language) + "/" + fieldCodec(codec) + "/" + (if (channels > 0) channels.toString() else "")
    }

    /** 视轨指纹:编码 + 分辨率。 */
    @JvmStatic
    fun videoFingerprint(codec: String?, width: Int, height: Int): String {
        val size = if (width > 0 && height > 0) width.toString() + "x" + height else ""
        return "V" + "/" + fieldCodec(codec) + "/" + size
    }

    /** 内置字幕指纹:语言 + 编码。 */
    @JvmStatic
    fun textFingerprint(language: String?, codec: String?): String {
        return "T" + "/" + field(language) + "/" + fieldCodec(codec)
    }

    /** 指纹是否"能定位":全字段为空(读不到轨信息时)会退化成"匹配第一条"而静默选错轨,故既不写库也不用它还原 */
    @JvmStatic
    fun usable(fingerprint: String?): Boolean {
        if (fingerprint == null) return false
        val parts = fingerprint.split("/")
        if (parts.size < 2) return false
        for (i in 1 until parts.size) {
            if (parts[i].isNotEmpty()) return true
        }
        return false
    }

    /**
     * 在当前轨道指纹列表里定位记忆中的那条。
     *
     * <p>精确匹配优先;失败后退化为按**第 2 个字段**(音频/字幕 = 语言,视轨 = 编码)匹配,
     * 且该字段在候选里必须唯一;有歧义时返回 -1 交给播放器默认选轨(猜哪条都可能错)。
     *
     * @return 下标,-1 = 无法定位
     */
    @JvmStatic
    fun pick(available: List<String>?, remembered: String?): Int {
        if (available == null || available.isEmpty() || !usable(remembered)) return -1
        for (i in available.indices) {
            if (remembered == available[i]) return i
        }
        val language = fieldAt(remembered, 1)
        if (language.isEmpty()) return -1
        var matched = -1
        for (i in available.indices) {
            if (language != fieldAt(available[i], 1)) continue
            if (matched >= 0) return -1
            matched = i
        }
        return matched
    }

    // ==================== 字幕来源记录 ====================

    @JvmStatic
    fun subtitleLocal(path: String?): String {
        val value = trim(path)
        return if (value.isEmpty()) "" else SUBTITLE_LOCAL + value
    }

    @JvmStatic
    fun subtitleOnline(releaseUrl: String?, fileName: String?): String {
        val release = trim(releaseUrl)
        if (release.isEmpty()) return ""
        return SUBTITLE_ONLINE + release + ONLINE_SEP + trim(fileName)
    }

    @JvmStatic
    fun isSubtitleOff(record: String?): Boolean {
        return SUBTITLE_OFF == record
    }

    @JvmStatic
    fun isSubtitleLocal(record: String?): Boolean {
        return record != null && record.startsWith(SUBTITLE_LOCAL)
    }

    @JvmStatic
    fun isSubtitleOnline(record: String?): Boolean {
        return record != null && record.startsWith(SUBTITLE_ONLINE)
    }

    /** 是否为"内置字幕指纹"(字幕槽位里唯一以轨道指纹形式存的取值) */
    @JvmStatic
    fun isSubtitleTrack(record: String?): Boolean {
        return record != null && record.isNotEmpty() && !record.startsWith(MARK)
    }

    /** 本地字幕文件路径;非本地记录返回空串 */
    @JvmStatic
    fun localPath(record: String?): String {
        return if (isSubtitleLocal(record)) record!!.substring(SUBTITLE_LOCAL.length) else ""
    }

    /** 在线字幕的发布页地址(不是直链);非在线记录返回空串 */
    @JvmStatic
    fun onlineRelease(record: String?): String {
        if (!isSubtitleOnline(record)) return ""
        val rest = record!!.substring(SUBTITLE_ONLINE.length)
        val sep = rest.indexOf(ONLINE_SEP)
        return if (sep < 0) rest else rest.substring(0, sep)
    }

    /** 在线字幕当时所选文件的名称(跨集匹配用的线索);可为空串 */
    @JvmStatic
    fun onlineFileName(record: String?): String {
        if (!isSubtitleOnline(record)) return ""
        val rest = record!!.substring(SUBTITLE_ONLINE.length)
        val sep = rest.indexOf(ONLINE_SEP)
        return if (sep < 0) "" else rest.substring(sep + ONLINE_SEP.length)
    }

    // ==================== 读写 ====================

    /** 记住用户显式选择的音轨/视轨。空键、不可定位的指纹、字幕槽位一律跳过。 */
    @JvmStatic
    fun saveTrack(contentKey: String?, type: Int, fingerprint: String?) {
        if (type != TYPE_AUDIO && type != TYPE_VIDEO) return
        if (isEmpty(contentKey) || !usable(fingerprint)) return
        KV.put(slotKey(contentKey!!, type), fingerprint)
        LOG.i("echo-track-memory save " + slot(type) + "=" + fingerprint)
    }

    /**
     * 记住用户显式的字幕决定(内置指纹 / #off / #local / #online)。
     *
     * <p>不可定位的指纹(如读不到轨信息时的 `T//`)不能记:它会被当成"这个片有决定"而让默认选轨
     * 整条让位,这一集就什么字幕都没有 —— 宁可不记。
     */
    @JvmStatic
    fun saveSubtitle(contentKey: String?, record: String?) {
        if (isEmpty(contentKey) || isEmpty(record)) return
        if (isSubtitleTrack(record) && !usable(record)) {
            LOG.i("echo-track-memory skip unusable text=" + record)
            return
        }
        KV.put(slotKey(contentKey!!, TYPE_TEXT), record)
        LOG.i("echo-track-memory save text=" + record)
    }

    /** 读出音轨/视轨记忆;无记忆返回 null(调用侧据此保留播放器的自动选轨) */
    @JvmStatic
    fun loadTrack(contentKey: String?, type: Int): String? {
        if (type != TYPE_AUDIO && type != TYPE_VIDEO) return null // 字幕槽存的是来源决定,误读会被当指纹解析
        if (isEmpty(contentKey)) return null
        val value = KV.get(slotKey(contentKey!!, type), "")
        return if (usable(value)) value else null
    }

    /** 读出字幕来源决定;无记忆返回 null */
    @JvmStatic
    fun loadSubtitle(contentKey: String?): String? {
        if (isEmpty(contentKey)) return null
        val value = KV.get(slotKey(contentKey!!, TYPE_TEXT), "")
        return if (isEmpty(value)) null else value
    }

    @JvmStatic
    fun delete(contentKey: String?) {
        if (isEmpty(contentKey)) return
        val key = contentKey!!
        KV.delete(slotKey(key, TYPE_AUDIO))
        KV.delete(slotKey(key, TYPE_VIDEO))
        KV.delete(slotKey(key, TYPE_TEXT))
    }

    /** 清空全部轨道记忆(清空观看历史时一并清理,免得留下访问不到的孤儿键) */
    @JvmStatic
    fun deleteAll() {
        for (key in KV.keys(KEY_PREFIX)) {
            KV.delete(key)
        }
    }

    private fun slotKey(contentKey: String, type: Int): String {
        return KEY_PREFIX + contentKey + "_" + slot(type)
    }

    private fun slot(type: Int): String {
        if (type == TYPE_AUDIO) return SLOT_AUDIO
        if (type == TYPE_VIDEO) return SLOT_VIDEO
        return SLOT_TEXT
    }

    // ==================== 小工具 ====================

    private fun isEmpty(text: String?): Boolean {
        return text == null || text.isEmpty()
    }

    private fun trim(text: String?): String {
        return if (text == null) "" else text.trim { it <= ' ' }
    }

    /** 指纹字段:去掉字段分隔符,避免一个字段被拆成两个(字段值只作匹配用,不需要原样保留) */
    private fun field(text: String?): String {
        return trim(text).replace("/", " ")
    }

    private fun fieldCodec(codec: String?): String {
        return field(codec).lowercase(Locale.ROOT)
    }

    /** 取指纹的第 index 个字段(0 = 类型前缀);越界返回空串 */
    private fun fieldAt(fingerprint: String?, index: Int): String {
        if (fingerprint == null) return ""
        val parts = fingerprint.split("/")
        return if (index >= 0 && index < parts.size) parts[index] else ""
    }
}
