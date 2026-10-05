package com.github.tvbox.osc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.ArrayList

class CustomWebReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (action == intent.action && intent.extras != null) {
            val actionValue = intent.extras!!.getString("action")
            if (actionValue == REFRESH_PARSE) {
                /*val name = intent.getExtras().getString("name");
                val url = intent.getExtras().getString("url");*/
                return
            } else if (actionValue == REFRESH_LIVE) {
                return
            } else {
                return
            }
            /*if (callback != null) {
                for (Call call in callback) {
                    call.onChange(actionValue, refreshObj);
                }
            }*/
        }
    }

    companion object {
        @JvmField
        var action = "android.content.movie.custom.web.Action"

        @JvmField
        var REFRESH_SOURCE = "source"

        @JvmField
        var REFRESH_LIVE = "live"

        @JvmField
        var REFRESH_PARSE = "parse"

        @JvmField
        var callback: MutableList<Callback> = ArrayList()
    }

    interface Callback {
        fun onChange(action: String?, obj: Any?)
    }
}
