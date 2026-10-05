package com.github.tvbox.osc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import com.github.tvbox.osc.event.ServerEvent
import com.github.tvbox.osc.ui.activity.SearchActivity
import com.github.tvbox.osc.util.AppManager

import org.greenrobot.eventbus.EventBus

class SearchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (action == intent.action && intent.extras != null) {
            if (AppManager.getInstance().getActivity(SearchActivity::class.java) != null) {
                AppManager.getInstance().backActivity(SearchActivity::class.java)
                EventBus.getDefault().post(ServerEvent(ServerEvent.SERVER_SEARCH, intent.extras!!.getString("title")))
            } else {
                val newIntent = Intent(context, SearchActivity::class.java)
                newIntent.putExtra("title", intent.extras!!.getString("title"))
                newIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                context.startActivity(newIntent)
            }
        }
    }

    companion object {
        @JvmField
        var action = "android.content.movie.search.Action"
    }
}
