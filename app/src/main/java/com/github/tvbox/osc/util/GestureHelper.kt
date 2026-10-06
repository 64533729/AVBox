package com.github.tvbox.osc.util

object GestureHelper {

    @JvmStatic
    fun isControlDisabled(): Boolean {
        return KV.get(HawkConfig.GESTURE_CONTROL_DISABLED, false)
    }

    @JvmStatic
    fun setControlDisabled(disabled: Boolean) {
        KV.put(HawkConfig.GESTURE_CONTROL_DISABLED, disabled)
    }
}
