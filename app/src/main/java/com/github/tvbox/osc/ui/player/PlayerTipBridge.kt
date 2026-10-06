package com.github.tvbox.osc.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class PlayerTipState(
    val msg: String = "",
    val loading: Boolean = false,
    val err: Boolean = false,
)

fun interface TipStateListener {
    fun onTipStateChanged(state: PlayerTipState)
}

object PlayerTipBridge {
    var state by mutableStateOf(PlayerTipState())
        private set

    @Volatile
    private var listener: TipStateListener? = null

    @JvmStatic
    fun setTipStateListener(listener: TipStateListener?) {
        this.listener = listener
    }

    @JvmStatic
    fun clearTipStateListener(listener: TipStateListener?) {
        if (listener != null && this.listener === listener) this.listener = null
    }

    @JvmStatic
    fun setTip(msg: String, loading: Boolean, err: Boolean) {
        state = PlayerTipState(msg, loading, err)
        listener?.onTipStateChanged(state)
    }

    @JvmStatic
    fun hide() {
        state = PlayerTipState()
        listener?.onTipStateChanged(state)
    }
}
