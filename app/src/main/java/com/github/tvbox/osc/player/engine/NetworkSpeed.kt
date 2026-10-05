package com.github.tvbox.osc.player.engine

import android.content.Context
import android.net.TrafficStats

/**
 * 实时网速采样(移植自 doikki `PlayerUtils.getNetSpeed`):进程接收流量差 / 时间差,
 * 供播放信息浮层(OSD)的"网速"读取。静态位共享 —— 两次调用间隔越短数值越抖,由调用方控制采样节奏。
 */
object NetworkSpeed {

    private var lastTotalRxBytes = 0L
    private var lastTimeStamp = 0L

    @JvmStatic
    fun getNetSpeed(context: Context?): Long {
        if (context == null) {
            return 0
        }
        // 先取该进程总接收量;取不到(UNSUPPORTED)时按 0 计,否则取系统总接收量
        val nowTotalRxBytes = if (TrafficStats.getUidRxBytes(context.applicationInfo.uid) == TrafficStats.UNSUPPORTED.toLong()) {
            0L
        } else {
            TrafficStats.getTotalRxBytes()
        }
        val nowTimeStamp = System.currentTimeMillis()
        val calculationTime = nowTimeStamp - lastTimeStamp
        if (calculationTime == 0L) {
            return calculationTime
        }
        // 两次接收量差 / 时间差(毫秒 => 秒)
        val speed = (nowTotalRxBytes - lastTotalRxBytes) * 1000 / calculationTime
        lastTimeStamp = nowTimeStamp
        lastTotalRxBytes = nowTotalRxBytes
        return speed
    }
}
