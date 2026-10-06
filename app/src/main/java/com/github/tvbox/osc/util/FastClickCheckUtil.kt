package com.github.tvbox.osc.util

import android.os.Handler
import android.view.View

object FastClickCheckUtil {
    @JvmStatic
    fun check(view: View) {
        check(view, 500)
    }

    @JvmStatic
    fun check(view: View, mills: Int) {
        view.isClickable = false
        Handler().postDelayed({ view.isClickable = true }, mills.toLong())
    }
}
