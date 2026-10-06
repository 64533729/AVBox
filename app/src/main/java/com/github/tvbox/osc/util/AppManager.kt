package com.github.tvbox.osc.util

import android.app.Activity

import java.util.Stack

class AppManager private constructor() {

    fun addActivity(activity: Activity) {
        if (activityStack == null) {
            activityStack = Stack<Activity>()
        }
        activityStack!!.add(activity)
    }

    fun isActivity(): Boolean {
        if (activityStack != null) {
            return !activityStack!!.isEmpty()
        }
        return false
    }

    fun currentActivity(): Activity {
        val activity = activityStack!!.lastElement()
        return activity
    }

    fun finishActivity() {
        val activity = activityStack!!.lastElement()
        if (!activity.isFinishing()) {
            activity.finish()
        }
    }

    fun finishActivity(activity: Activity) {
        activityStack!!.remove(activity)
    }

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
