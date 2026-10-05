package com.undcover.freedom.pyramid

import android.util.Log

/**
 * Created by UndCover on 16/12/15.
 */
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

        /**
         * 用于生命周期
         */
        const val FILTER_LC = 0x01

        /**
         * 用于网络请求 默认为 LEVEL_I
         */
        const val FILTER_NW = 0x02

        /**
         * ActivityManager内置Log
         */
        const val FILTER_AM = 0x04

        /**
         * 用于FrameWork内置log
         */
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
            while (m.length > segmentSize) {// 循环分段打印日志
                val logContent = m.substring(0, segmentSize)
                m = m.replace(logContent, "\t\t")
                Log.v(tag, logContent)
            }
            Log.v(tag, m)// 打印剩余日志
        }

        private fun longD(tag: String, msg: String) {
            if (logLevel < LEVEL_D)
                return
            var m = msg
            while (m.length > segmentSize) {// 循环分段打印日志
                val logContent = m.substring(0, segmentSize)
                m = m.replace(logContent, "\t\t")
                Log.d(tag, logContent)
            }
            Log.d(tag, m)// 打印剩余日志
        }

        private fun longI(tag: String, msg: String) {
            if (logLevel < LEVEL_I)
                return
            var m = msg
            while (m.length > segmentSize) {// 循环分段打印日志
                val logContent = m.substring(0, segmentSize)
                m = m.replace(logContent, "\t\t")
                Log.i(tag, logContent)
            }
            Log.i(tag, m)// 打印剩余日志
        }

        private fun longW(tag: String, msg: String) {
            if (logLevel < LEVEL_W)
                return
            var m = msg
            while (m.length > segmentSize) {// 循环分段打印日志
                val logContent = m.substring(0, segmentSize)
                m = m.replace(logContent, "\t\t")
                Log.w(tag, logContent)
            }
            Log.w(tag, m)// 打印剩余日志
        }

        private fun longE(tag: String, msg: String) {
            if (logLevel < LEVEL_E)
                return
            var m = msg
            while (m.length > segmentSize) {// 循环分段打印日志
                val logContent = m.substring(0, segmentSize)
                m = m.replace(logContent, "\t\t")
                Log.e(tag, logContent)
            }
            Log.e(tag, m)// 打印剩余日志

//        if (tag == null || tag.length() == 0
//                || msg == null || msg.length() == 0)
//            return;

//        int segmentSize = 3 * 1024;
//        long length = msg.length();
//        if (length <= segmentSize) {// 长度小于等于限制直接打印
//            Log.e(tag, msg);
//        } else {
//            while (msg.length() > segmentSize) {// 循环分段打印日志
//                String logContent = msg.substring(0, segmentSize);
//                msg = msg.replace(logContent, "");
//                Log.e(tag, logContent);
//            }
//            Log.e(tag, msg);// 打印剩余日志
//        }
        }

        /**
         * 默认Tag
         *
         * @param msg
         */
        @JvmStatic
        fun v(msg: String) {
            v(TagConstant.TAG_DEF, msg)
        }

        /**
         * 默认Tag
         *
         * @param msg
         */
        @JvmStatic
        fun d(msg: String) {
            d(TagConstant.TAG_DEF, msg)
        }

        /**
         * 默认Tag
         *
         * @param msg
         */
        @JvmStatic
        fun i(msg: String) {
            i(TagConstant.TAG_DEF, msg)
        }

        /**
         * 默认Tag
         *
         * @param msg
         */
        @JvmStatic
        fun w(msg: String) {
            w(TagConstant.TAG_DEF, msg)
        }

        /**
         * 默认Tag
         *
         * @param msg
         */
        @JvmStatic
        fun e(msg: String) {
            e(TagConstant.TAG_DEF, msg)
        }

        /**
         * 添加AppTag
         *
         * @param tag
         * @param msg
         */
        @JvmStatic
        fun v(tag: String, msg: String) {
            val msgStr = tag + " " + msg
            if (msgStr.length > segmentSize) {
                longV(TagConstant.TAG_APP, msgStr)
            } else {
                V(TagConstant.TAG_APP, msgStr)
            }
        }

        /**
         * 添加AppTag
         *
         * @param tag
         * @param msg
         */
        @JvmStatic
        fun d(tag: String, msg: String) {
            val msgStr = tag + " " + msg
            if (msgStr.length > segmentSize) {
                longD(TagConstant.TAG_APP, msgStr)
            } else {
                D(TagConstant.TAG_APP, msgStr)
            }
        }

        /**
         * 添加AppTag
         *
         * @param tag
         * @param msg
         */
        @JvmStatic
        fun i(tag: String, msg: String) {
            val msgStr = tag + " " + msg
            if (msgStr.length > segmentSize) {
                longI(TagConstant.TAG_APP, msgStr)
            } else {
                I(TagConstant.TAG_APP, msgStr)
            }
        }

        /**
         * 添加AppTag
         *
         * @param tag
         * @param msg
         */
        @JvmStatic
        fun w(tag: String, msg: String) {
            val msgStr = tag + " " + msg
            if (msgStr.length > segmentSize) {
                longW(TagConstant.TAG_APP, msgStr)
            } else {
                W(TagConstant.TAG_APP, msgStr)
            }
        }

        /**
         * 多参数,使用默认Tag
         *
         * @param args
         */
        @JvmStatic
        fun v(vararg args: String?) {
            val msg = getArgsStr(*args)
            v(TagConstant.TAG_DEF, msg)
        }

        /**
         * 多参数,使用默认Tag
         *
         * @param args
         */
        @JvmStatic
        fun d(vararg args: String?) {
            val msg = getArgsStr(*args)
            d(msg)
        }

        /**
         * 多参数,使用默认Tag
         *
         * @param args
         */
        @JvmStatic
        fun i(vararg args: String?) {
            val msg = getArgsStr(*args)
            i(msg)
        }

        /**
         * 多参数,使用默认Tag
         *
         * @param args
         */
        @JvmStatic
        fun w(vararg args: String?) {
            val msg = getArgsStr(*args)
            w(msg)
        }

        /**
         * 多参数,使用默认Tag
         *
         * @param args
         */
        @JvmStatic
        fun e(vararg args: String?) {
            val msg = getArgsStr(*args)
            e(msg)
        }

        /**
         * 打印生命周期
         *
         * @param tag
         * @param msg
         */
        @JvmStatic
        fun lc(tag: String, msg: String) {
            if (isLifeCycleEnable) {
                d(tag, TagConstant.TAG_LC, msg)
            }
        }

        /**
         * 打印ActivityManager管理
         *
         * @param tag
         * @param msg
         */
        @JvmStatic
        fun am(tag: String, msg: String) {
            if (isAtyManagerEnable) {
                d(tag, TagConstant.TAG_AM, msg)
            }
        }

        /**
         * 打印框架信息
         *
         * @param tag
         * @param msg
         */
        @JvmStatic
        fun fw(tag: String, msg: String) {
            if (isFrameWorkEnable) {
                d(tag, TagConstant.TAG_FW, msg)
            }
        }

        /**
         * 打印网络请求
         *
         * @param tag
         * @param msg
         */
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
