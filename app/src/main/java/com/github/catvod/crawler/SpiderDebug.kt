package com.github.catvod.crawler

class SpiderDebug {

    companion object {

        @JvmStatic
        fun log(th: Throwable?) {
            try {
                android.util.Log.d("SpiderLog", "" + th!!.message, th)
            } catch (th1: Throwable) {
            }
        }

        @JvmStatic
        fun log(msg: String?) {
            try {
                android.util.Log.d("SpiderLog", "" + msg)
            } catch (th1: Throwable) {
            }
        }

        @JvmStatic
        fun ec(i: Int): String {
            return ""
        }
    }
}
