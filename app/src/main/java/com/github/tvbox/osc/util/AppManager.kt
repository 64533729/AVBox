package com.github.tvbox.osc.util

import android.app.Activity

import java.util.Stack

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
class AppManager private constructor() {

    /**
     * 添加Activity到堆栈
     */
    fun addActivity(activity: Activity) {
        if (activityStack == null) {
            activityStack = Stack<Activity>()
        }
        activityStack!!.add(activity)
    }

    /**
     * 是否有activity
     */
    fun isActivity(): Boolean {
        if (activityStack != null) {
            return !activityStack!!.isEmpty()
        }
        return false
    }

    /**
     * 获取当前Activity（堆栈中最后一个压入的）
     */
    fun currentActivity(): Activity {
        val activity = activityStack!!.lastElement()
        return activity
    }

    /**
     * 结束当前Activity（堆栈中最后一个压入的）
     */
    fun finishActivity() {
        val activity = activityStack!!.lastElement()
        if (!activity.isFinishing()) {
            activity.finish()
        }
    }

    fun finishActivity(activity: Activity) {
        activityStack!!.remove(activity)
    }


    /**
     * 结束指定类名的Activity
     */
    fun finishActivity(cls: Class<*>) {
        for (activity in activityStack!!) {
            if (activity.javaClass.equals(cls)) {
                if (!activity.isFinishing()) {
                    activity.finish()
                }
                break
            }
        }
    }

    fun backActivity(cls: Class<*>) {
        while (!activityStack!!.empty()) {
            val activity = activityStack!!.pop()
            if (activity.javaClass.equals(cls)) {
                activityStack!!.push(activity)
                break
            } else {
                activity.finish()
            }
        }
    }

    /**
     * 结束所有Activity
     */
    fun finishAllActivity() {
        if (activityStack != null && activityStack!!.size > 0) {
            for (i in 0 until activityStack!!.size) {
                val activity = activityStack!![i]
                if (null != activityStack!![i]) {
                    if (!activity.isFinishing()) {
                        activity.finish()
                    }
                }
            }
            activityStack!!.clear()
        }
    }

    /**
     * 获取指定的Activity
     */
    fun getActivity(cls: Class<*>): Activity? {
        if (activityStack != null) {
            for (activity in activityStack!!) {
                if (activity.javaClass.equals(cls)) {
                    return activity
                }
            }
        }
        return null
    }

    fun appExit(code: Int) {
        try {
            finishAllActivity()
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(code)
        } catch (e: Exception) {
            activityStack!!.clear()
            LOG.e("AppManager", e)
        }
    }

    companion object {
        private var activityStack: Stack<Activity>? = null

        private val instance = AppManager()

        @JvmStatic
        fun getInstance(): AppManager {
            return instance
        }
    }
}
