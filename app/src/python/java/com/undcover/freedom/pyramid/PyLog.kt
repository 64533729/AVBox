package com.undcover.freedom.pyramid

import android.util.Log

class PyLog {
    fun setLogLevel(logLevel: Int): PyLog {
        try {
            checkInit()
        } catch (e: Exception) {
            return mLog!!
        }
        Companion.logLevel = logLevel
        return mLog!!
    }

    fun setFilter(filter: Int): PyLog {
        try {
            checkInit()
        } catch (e: Exception) {
            return mLog!!
        }
        isLifeCycleEnable = (filter and FILTER_LC) / FILTER_LC == 1
        isNetWorkEnable = (filter and FILTER_NW) / FILTER_NW == 1
        isFrameWorkEnable = (filter and FILTER_FW) / FILTER_FW == 1
        isAtyManagerEnable = (filter and FILTER_AM) / FILTER_AM == 1
        return mLog!!
    }

    class TagConstant {
        companion object {
            @JvmField
            var TAG_APP = "SmartSdk"

            @JvmField
            var TAG_LC = "-----LifeCycle-----"

            @JvmField
            var TAG_AM = "-----AtyManager-----"

            @JvmField
            var TAG_NW = "-----NetWork-----"

            @JvmField
            var TAG_FW = "-----FrameWork-----"

            @JvmField
            var TAG_DEF = ""

            @JvmField
            var TAG_REQ = "Request\n"

            @JvmField
            var TAG_RSP = "Response\n"
        }
    }

    companion object {
        const val LEVEL_V = 5
        const val LEVEL_D = 4
        const val LEVEL_I = 3
        const val LEVEL_W = 2
        const val LEVEL_E = 1
        const val LEVEL_RELEASE = 0

        private var logLevel = LEVEL_RELEASE
        private var mLog: PyLog? = null

        @JvmStatic
        fun getInstance(): PyLog {
            synchronized(PyLog::class.java) {
                mLog = PyLog()
                return mLog!!
            }
        }

        const val FILTER_LC = 0x01

        const val FILTER_NW = 0x02

        const val FILTER_AM = 0x04

        const val FILTER_FW = 0x08

        private var isLifeCycleEnable = false
        private var isNetWorkEnable = false
        private var isFrameWorkEnable = false
        private var isAtyManagerEnable = false

        @JvmStatic
        fun V(tag: String, msg: String) {
            if (logLevel < LEVEL_V)
                return
            Log.v(tag, msg)
        }

        @JvmStatic
        fun D(tag: String, msg: String) {
            if (logLevel < LEVEL_D)
                return
            Log.d(tag, msg)
        }

        @JvmStatic
        fun I(tag: String, msg: String) {
            if (logLevel < LEVEL_I)
                return
            Log.i(tag, msg)
        }

        @JvmStatic
        fun W(tag: String, msg: String) {
            if (logLevel < LEVEL_W)
                return
            Log.w(tag, msg)
        }

        @JvmStatic
        fun E(tag: String, msg: String) {
            if (logLevel < LEVEL_E)
                return
            Log.e(tag, msg)
        }

        private var segmentSize = 3 * 1024

        private fun longV(tag: String, msg: String) {
            if (logLevel < LEVEL_V)
                return
            var m = msg
            while (m.length > segmentSize) {
                val logContent = m.substring(0, segmentSize)
                m = m.replace(logContent, "\t\t")
                Log.v(tag, logContent)
            }
            Log.v(tag, m)
        }

        private fun longD(tag: String, msg: String) {
            if (logLevel < LEVEL_D)
                return
            var m = msg
            while (m.length > segmentSize) {
                val logContent = m.substring(0, segmentSize)
                m = m.replace(logContent, "\t\t")
                Log.d(tag, logContent)
            }
            Log.d(tag, m)
        }

        private fun longI(tag: String, msg: String) {
            if (logLevel < LEVEL_I)
                return
            var m = msg
            while (m.length > segmentSize) {
                val logContent = m.substring(0, segmentSize)
                m = m.replace(logContent, "\t\t")
                Log.i(tag, logContent)
            }
            Log.i(tag, m)
        }

        private fun longW(tag: String, msg: String) {
            if (logLevel < LEVEL_W)
                return
            var m = msg
            while (m.length > segmentSize) {
                val logContent = m.substring(0, segmentSize)
                m = m.replace(logContent, "\t\t")
                Log.w(tag, logContent)
            }
            Log.w(tag, m)
        }

        private fun longE(tag: String, msg: String) {
            if (logLevel < LEVEL_E)
                return
            var m = msg
            while (m.length > segmentSize) {
                val logContent = m.substring(0, segmentSize)
                m = m.replace(logContent, "\t\t")
                Log.e(tag, logContent)
            }
            Log.e(tag, m)

        }

        @JvmStatic
        fun v(msg: String) {
            v(TagConstant.TAG_DEF, msg)
        }

        @JvmStatic
        fun d(msg: String) {
            d(TagConstant.TAG_DEF, msg)
        }

        @JvmStatic
        fun i(msg: String) {
            i(TagConstant.TAG_DEF, msg)
        }

        @JvmStatic
        fun w(msg: String) {
            w(TagConstant.TAG_DEF, msg)
        }

        @JvmStatic
        fun e(msg: String) {
            e(TagConstant.TAG_DEF, msg)
        }

        @JvmStatic
        fun v(tag: String, msg: String) {
            val msgStr = tag + " " + msg
            if (msgStr.length > segmentSize) {
                longV(TagConstant.TAG_APP, msgStr)
            } else {
                V(TagConstant.TAG_APP, msgStr)
            }
        }

        @JvmStatic
        fun d(tag: String, msg: String) {
            val msgStr = tag + " " + msg
            if (msgStr.length > segmentSize) {
                longD(TagConstant.TAG_APP, msgStr)
            } else {
                D(TagConstant.TAG_APP, msgStr)
            }
        }

        @JvmStatic
        fun i(tag: String, msg: String) {
            val msgStr = tag + " " + msg
            if (msgStr.length > segmentSize) {
                longI(TagConstant.TAG_APP, msgStr)
            } else {
                I(TagConstant.TAG_APP, msgStr)
            }
        }

        @JvmStatic
        fun w(tag: String, msg: String) {
            val msgStr = tag + " " + msg
            if (msgStr.length > segmentSize) {
                longW(TagConstant.TAG_APP, msgStr)
            } else {
                W(TagConstant.TAG_APP, msgStr)
            }
        }

        @JvmStatic
        fun v(vararg args: String?) {
            val msg = getArgsStr(*args)
            v(TagConstant.TAG_DEF, msg)
        }

        @JvmStatic
        fun d(vararg args: String?) {
            val msg = getArgsStr(*args)
            d(msg)
        }

        @JvmStatic
        fun i(vararg args: String?) {
            val msg = getArgsStr(*args)
            i(msg)
        }

        @JvmStatic
        fun w(vararg args: String?) {
            val msg = getArgsStr(*args)
            w(msg)
        }

        @JvmStatic
        fun e(vararg args: String?) {
            val msg = getArgsStr(*args)
            e(msg)
        }

        @JvmStatic
        fun lc(tag: String, msg: String) {
            if (isLifeCycleEnable) {
                d(tag, TagConstant.TAG_LC, msg)
            }
        }

        @JvmStatic
        fun am(tag: String, msg: String) {
            if (isAtyManagerEnable) {
                d(tag, TagConstant.TAG_AM, msg)
            }
        }

        @JvmStatic
        fun fw(tag: String, msg: String) {
            if (isFrameWorkEnable) {
                d(tag, TagConstant.TAG_FW, msg)
            }
        }

        @JvmStatic
        fun nw(tag: String, msg: String) {
            if (isNetWorkEnable) {
                nw(tag, msg, false)
            }
        }

        @JvmStatic
        fun nw(tag: String, msg: String, isError: Boolean) {
            if (isNetWorkEnable) {
                if (isError) {
                    e(tag, TagConstant.TAG_NW, msg)
                } else {
                    i(tag, TagConstant.TAG_NW, msg)
                }
            }
        }

        @JvmStatic
        fun getStackTraceString(tr: Throwable): String {
            return Log.getStackTraceString(tr)
        }

        private fun getArgsStr(vararg args: String?): String {
            var ret = ""
            if (args.isNotEmpty()) {
                for (str in args) {
                    ret += str + " "
                }
            }
            return ret
        }

        private fun checkInit() {
            if (mLog == null) {
                throw Exception("SDK未初始化")
            }
        }
    }
}
