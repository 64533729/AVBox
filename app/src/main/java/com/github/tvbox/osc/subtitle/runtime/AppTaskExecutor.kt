/*
 *                       Copyright (C) of Avery
 *
 *                              _ooOoo_
 *                             o8888888o
 *                             88" . "88
 *                             (| -_- |)
 *                             O\  =  /O
 *                          ____/`- -'\____
 *                        .'  \\|     |//  `.
 *                       /  \\|||  :  |||//  \
 *                      /  _||||| -:- |||||-  \
 *                      |   | \\\  -  /// |   |
 *                      | \_|  ''\- -/''  |   |
 *                      \  .-\__  `-`  ___/-. /
 *                    ___`. .' /- -.- -\  `. . __
 *                 ."" '<  `.___\_<|>_/___.'  >'"".
 *                | | :  `- \`.;`\ _ /`;.`/ - ` : | |
 *                \  \ `-.   \_ __\ /__ _/   .-` /  /
 *           ======`-.____`-.___\_____/___.-`____.-'======
 *                              `=- -='
 *           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *              Buddha bless, there will never be bug!!!
 */

package com.github.tvbox.osc.subtitle.runtime

import java.util.concurrent.Executor

/**
 * @author AveryZhong.
 */

class AppTaskExecutor private constructor() : TaskExecutor() {

    private var mDelegate: TaskExecutor
    private var mDefaultTaskExecutor: TaskExecutor

    init {
        mDefaultTaskExecutor = DefaultTaskExecutor()
        mDelegate = mDefaultTaskExecutor
    }

    fun setDelegate(taskExecutor: TaskExecutor?) {
        mDelegate = taskExecutor ?: mDefaultTaskExecutor
    }

    override fun executeOnDeskIO(task: Runnable) {
        mDelegate.executeOnDeskIO(task)
    }

    override fun executeOnMainThread(task: Runnable) {
        mDelegate.executeOnMainThread(task)
    }

    override fun postToMainThread(task: Runnable) {
        mDelegate.postToMainThread(task)
    }

    override fun isMainThread(): Boolean {
        return mDelegate.isMainThread()
    }

    companion object {

        private var sInstance: AppTaskExecutor? = null

        @JvmStatic
        fun getInstance(): TaskExecutor {
            if (sInstance == null) {
                synchronized(AppTaskExecutor::class.java) {
                    sInstance = AppTaskExecutor()
                }
            }
            return sInstance!!
        }

        private val sDeskIO: Executor = Executor { command ->
            getInstance().executeOnDeskIO(command)
        }

        private val sMainThread: Executor = Executor { command ->
            getInstance().executeOnMainThread(command)
        }

        @JvmStatic
        fun deskIO(): Executor {
            return sDeskIO
        }

        @JvmStatic
        fun mainThread(): Executor {
            return sMainThread
        }
    }
}
