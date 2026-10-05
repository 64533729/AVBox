package com.undcover.freedom.pyramid

import android.content.Context
import android.widget.Toast

/**
 * Created by UndCover on 16/9/7.
 */
class PyToast {
    companion object {
        private var innerToast: Toast? = null
        private var mContext: Context? = null
        private var sInstance: PyToast? = null

        @JvmStatic
        fun init(context: Context) {
            mContext = context
        }

        /**
         * 在Application中 用于初始化
         *
         * @return
         */
        @JvmStatic
        fun getInstance(): PyToast {
            if (sInstance == null) {
                synchronized(PyToast::class.java) {
                    if (sInstance == null) {
                        sInstance = PyToast()
                    }
                }
            }
            return sInstance!!
        }

        @JvmStatic
        fun showCancelableToast(msg: String?) {
            showCancelableToast(msg, Toast.LENGTH_SHORT)
        }

        /**
         * 快速显示Toast,无需排队等待
         *
         * @param msg
         * @param duration
         */
        @JvmStatic
        fun showCancelableToast(msg: String?, duration: Int) {
            innerToast?.cancel()
            innerToast = Toast.makeText(mContext, msg, duration)
            innerToast!!.show()
        }

        @JvmStatic
        fun showMessage(msg: String?, duration: Int) {
            Toast.makeText(mContext, msg, duration).show()
        }
    }
}
