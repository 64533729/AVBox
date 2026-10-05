package com.github.tvbox.osc.player.engine

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

/**
 * HlsErrorHandlingPolicy 单测:锁住「切片错误判定」与「重试次数」的真值表。
 *
 * <p>不可覆盖面(诚实标注):`getRetryDelayMsFor`/`getFallbackSelectionFor` 需要构造
 * `LoadErrorInfo`(链上要 `android.net.Uri`/`DataSpec`),JVM 单测里 Uri 是桩(returnDefaultValues)
 * 构造不出,由真机走查覆盖。
 */
class HlsErrorHandlingPolicyTest {

    private val policy = HlsErrorHandlingPolicy()

    @Test
    fun isChunkError_onlyForIoExceptions() {
        assertTrue(HlsErrorHandlingPolicy.isChunkError(IOException("net")))
        assertTrue(HlsErrorHandlingPolicy.isChunkError(InterruptedIOException()))
        assertFalse(HlsErrorHandlingPolicy.isChunkError(RuntimeException("boom")))
        assertFalse(HlsErrorHandlingPolicy.isChunkError(null))
    }

    /**
     * 判别力说明(诚实标注):`DATA_TYPE_MEDIA` 期望值与 media3 默认值同为 3,
     * 该断言对"覆写被整段删掉"无判别力(只有 [C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE]=6 能区分覆写还在);
     * 故一并钉住 media3 默认值本身 —— 将来 media3 升版改默认值时这里会失败,提示恢复判别力。
     */
    @Test
    fun minimumRetryCount_mediaIsCappedAtThree() {
        assertEquals(3, DefaultLoadErrorHandlingPolicy.DEFAULT_MIN_LOADABLE_RETRY_COUNT)
        assertEquals(3, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun minimumRetryCount_otherTypesFollowMedia3Default() {
        // 非切片数据(manifest 等)沿用默认重试次数
        assertEquals(3, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
        // 进度流直播是 media3 默认的 6,必须与切片档(3)区分开 —— 本条是覆写存在与否的判别项
        assertEquals(6, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE))
    }
}
