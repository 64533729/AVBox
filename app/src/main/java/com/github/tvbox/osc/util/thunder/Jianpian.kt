package com.github.tvbox.osc.util.thunder

import com.github.tvbox.osc.util.LOG
import android.net.Uri
import android.text.TextUtils

import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.util.LocalIPAddress
import com.github.tvbox.osc.util.RegexUtils
import com.p2p.P2PClass

import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import com.github.tvbox.osc.util.AppContextHolder


object Jianpian {

    @JvmStatic
    fun JPUrlDec(url: String): String? {
        if (App.getp2p() != null) {
            try {
                val decode = URLDecoder.decode(url, "UTF-8")
                val split = RegexUtils.getPattern("\\|").split(decode)
                var replace = split[0].replace("xg://", "ftp://")
                if (replace.contains("xgplay://")) {
                    replace = split[0].replace("xgplay://", "ftp://")
                }
                if (!TextUtils.isEmpty(App.burl)) {
                    App.getp2p().P2Pdoxpause(App.burl.toByteArray(Charset.forName("GBK")))
                    App.getp2p().P2Pdoxdel(App.burl.toByteArray(Charset.forName("GBK")))
                }
                App.burl = replace
                App.getp2p().P2Pdoxstart(replace.toByteArray(Charset.forName("GBK")))
                App.getp2p().P2Pdoxadd(replace.toByteArray(Charset.forName("GBK")))
                return "http://" + LocalIPAddress.getIP(AppContextHolder.context()!!) + ":" + P2PClass.port + "/" + URLEncoder.encode(Uri.parse(replace).getLastPathSegment(), "GBK")
            } catch (e: Exception) {
                return e.localizedMessage
            }
        } else {
            return ""
        }
    }

    @JvmStatic
    fun finish() {
        if (!TextUtils.isEmpty(App.burl) && App.getp2p() != null) {
            try {
                App.getp2p().P2Pdoxpause(App.burl.toByteArray(Charset.forName("GBK")))
                App.getp2p().P2Pdoxdel(App.burl.toByteArray(Charset.forName("GBK")))
                App.burl = ""
            } catch (e: UnsupportedEncodingException) {
                LOG.e("Jianpian", e)
            }
        }
    }

    @JvmStatic
    fun isJpUrl(url: String): Boolean {
        return url.startsWith("tvbox-xg:") || (Thunder.isFtp(url) && url.contains("gbl.114s"))
    }
}
