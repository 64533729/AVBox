package com.github.tvbox.osc.player.engine

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

/**
 * 自定义 HLS 错误处理策略:跳过坏的切片继续播放(移植自 doikki `HlsErrorHandlingPolicy`)。
 *
 * <p>切片错误(IO 类)用较短重试延迟、限制重试次数,且不触发 fallback(直接跳到下一片);
 * 非切片错误完全沿用 media3 默认行为。
 */
class HlsErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (isChunkError(loadErrorInfo.exception)) {
            return RETRY_DELAY_MS
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        // 对于 HLS 切片,限制重试次数,避免无限重试
        if (dataType == C.DATA_TYPE_MEDIA) {
            return MAX_RETRIES
        }
        return super.getMinimumLoadableRetryCount(dataType)
    }

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        // 切片加载错误不使用 fallback:返回 null 表示跳过该切片继续播放
        if (isChunkError(loadErrorInfo.exception)) {
            return null
        }
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }

    companion object {
        /** 最多重试 3 次 */
        const val MAX_RETRIES = 3

        /** 切片错误重试延迟 500 毫秒 */
        const val RETRY_DELAY_MS = 500L

        /** 切片错误判定:IO 异常(网络错误、404 等) */
        @JvmStatic
        fun isChunkError(exception: Throwable?): Boolean = exception is IOException
    }
}
