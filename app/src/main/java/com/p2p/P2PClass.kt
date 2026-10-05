package com.p2p

import java.io.File
import java.nio.charset.Charset

class P2PClass(str: String) {
    @JvmField
    var path: String? = null

    init {
        val p = str + "/jpali"
        path = p
        val file = File(p)
        if (!file.exists()) {
            file.mkdirs()
        }
        port = doxstarthttpd("TEST3E63BAAECDAA79BEAA91853490A69F08".toByteArray(Charset.defaultCharset()), str.toByteArray(Charset.defaultCharset()))
    }

    inner class `init`(private val cardPath: String) : Thread() {
        override fun run() {
            val p2PClass = this@P2PClass
            p2PClass.path = cardPath + "/jpali"
            val file = File(this@P2PClass.path!!)
            if (!file.exists()) {
                file.mkdirs()
            }
            port = this@P2PClass.doxstarthttpd("TEST3E63BAAECDAA79BEAA91853490A69F08".toByteArray(Charset.defaultCharset()), cardPath.toByteArray(Charset.defaultCharset()))
        }
    }

    private external fun XGFilmCloseFile(j: Long)

    private external fun XGFilmOpenFile(bArr: ByteArray): Long

    private external fun XGFilmReadFile(j: Long, j2: Long, i: Int, bArr: ByteArray): Int

    private external fun dosetupload(i: Int): Int

    private external fun doxSetP2PPauseUpdate(i: Int)

    private external fun doxadd(bArr: ByteArray): Int

    private external fun doxcheck(bArr: ByteArray): Int

    private external fun doxdel(bArr: ByteArray): Int

    private external fun doxdelall(): Int

    private external fun doxdownload(bArr: ByteArray): Int

    private external fun doxendhttpd(): Int

    private external fun doxgetVersion(): String

    private external fun doxgethostbynamehook(str: String): String

    private external fun doxgetlocalAddress(): String

    private external fun doxgettaskstat(i: Int): String

    private external fun doxpause(bArr: ByteArray): Int

    private external fun doxsave(): Int

    private external fun doxsetduration(i: Int): Int

    private external fun doxstart(bArr: ByteArray): Int

    private external fun doxstarthttpd(bArr: ByteArray, bArr2: ByteArray): Int

    private external fun doxterminate(): Int

    private external fun getdownsize(i: Int): Long

    private external fun getfilesize(i: Int): Long

    private external fun getlocalfilesize(bArr: ByteArray): Long

    private external fun getpercent(): Int

    private external fun getspeed(i: Int): Long

    fun P2Pdoxstart(bArr: ByteArray): Int {
        return doxstart(bArr)
    }

    fun P2Pdoxdownload(bArr: ByteArray): Int {
        return doxdownload(bArr)
    }

    fun P2Pdoxterminate(): Int {
        return doxterminate()
    }

    fun P2Pdosetupload(i: Int): Int {
        return dosetupload(i)
    }

    fun P2Pdoxcheck(bArr: ByteArray): Int {
        return doxcheck(bArr)
    }

    fun P2Pdoxadd(bArr: ByteArray): Int {
        return doxadd(bArr)
    }

    fun P2Pdoxpause(bArr: ByteArray): Int {
        return doxpause(bArr)
    }

    fun P2Pdoxdel(bArr: ByteArray): Int {
        return doxdel(bArr)
    }

    fun P2PdoxdelAll(): Int {
        return doxdelall()
    }

    fun P2Pgetspeed(i: Int): Long {
        return getspeed(i)
    }

    fun P2Pgetdownsize(i: Int): Long {
        return getdownsize(i)
    }

    fun P2Pgetfilesize(i: Int): Long {
        return getfilesize(i)
    }

    fun P2Pgetpercent(): Int {
        return getpercent()
    }

    fun P2Pgetlocalfilesize(bArr: ByteArray): Long {
        return getlocalfilesize(bArr)
    }

    fun P2Pdosetduration(i: Int): Long {
        return doxsetduration(i).toLong()
    }

    fun getServiceAddress(): String {
        return doxgethostbynamehook("xx0.github.com")
    }

    fun P2Pdoxstarthttpd(bArr: ByteArray, bArr2: ByteArray): Int {
        return doxstarthttpd(bArr, bArr2)
    }

    fun P2Pdoxsave(): Int {
        return doxsave()
    }

    fun P2Pdoxendhttpd(): Int {
        return doxendhttpd()
    }

    fun getVersion(): String {
        return doxgetVersion()
    }

    fun xGFilmOpenFile(bArr: ByteArray): Long {
        return XGFilmOpenFile(bArr)
    }

    fun xGFilmCloseFile(j: Long) {
        XGFilmCloseFile(j)
    }

    fun xGFilmReadFile(j: Long, j2: Long, i: Int, bArr: ByteArray): Int {
        return XGFilmReadFile(j, j2, i, bArr)
    }

    fun setP2PPauseUpdate(i: Int) {
        doxSetP2PPauseUpdate(i)
    }

    fun getTouPingUrl(): String {
        return doxgetlocalAddress()
    }

    fun P2Pdoxgettaskstat(i: Int): String {
        return doxgettaskstat(i)
    }

    companion object {
        private const val TAG = "P2PClass"

        @JvmField
        var port = 8087

        init {
            System.loadLibrary("p2p")
        }
    }
}
