package com.github.catvod.crawler

class SpiderDebug {

    companion object {

        @JvmStatic
        fun log(th: Throwable?) {
            try {
                android.util.Log.d("SpiderLog", "" + th!!.message, th)
            } catch (th1: Throwable) {
                // 日志通道自身兜底:Log 失败不再上报,防递归
            }
        }

        @JvmStatic
        fun log(msg: String?) {
            try {
                android.util.Log.d("SpiderLog", "" + msg)
            } catch (th1: Throwable) {
                // 日志通道自身兜底:Log 失败不再上报,防递归
            }
        }

        @JvmStatic
        fun ec(i: Int): String {
            return ""
        }
    }
}
