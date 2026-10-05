package com.github.tvbox.osc.util

import android.os.Handler
import android.view.View

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */
object FastClickCheckUtil {
    /**
     * 相同视图点击必须间隔0.5s才能有效
     *
     * @param view 目标视图
     */
    @JvmStatic
    fun check(view: View) {
        check(view, 500)
    }

    /**
     * 设置间隔点击规则，配置间隔点击时长
     *
     * @param view  目标视图
     * @param mills 点击间隔时间（毫秒）
     */
    @JvmStatic
    fun check(view: View, mills: Int) {
        view.isClickable = false
        Handler().postDelayed({ view.isClickable = true }, mills.toLong())
    }
}
