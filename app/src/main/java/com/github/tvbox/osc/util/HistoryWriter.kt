package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.event.RefreshEvent
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object HistoryWriter {

    private val writer: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "vod-history-writer") }
    }

    fun write(sourceKey: String, info: VodInfo) {
        writer.execute { writeNow(sourceKey, info) }
    }

    private fun writeNow(sourceKey: String, info: VodInfo) {
        try {
            AppGraph.historyRepository.insertVodRecord(sourceKey, info)
        } catch (th: Throwable) {
            LOG.e("HistoryWriter", "echo-history insert failed: " + th, th)
            return
        }
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
    }
}
