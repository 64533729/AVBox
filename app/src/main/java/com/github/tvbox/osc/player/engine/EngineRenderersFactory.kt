package com.github.tvbox.osc.player.engine

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.github.tvbox.osc.player.effect.ReplayableCacheVideoRenderer
import com.github.tvbox.osc.util.LOG
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 内核渲染器工厂(移植自旧 `osc.player.ExoPlayer.SubtitleOffsetRenderersFactory`):
 * ① 视频渲染器用 [CodecPreferences] 驱动的解码器选择器(软解偏好),并替换为带可重放帧缓存的
 * [ReplayableCacheVideoRenderer](暂停态重绘的前提);
 * ② 文本渲染器用反射代理包一层字幕延迟([subtitleDelayUsProvider] 返回微秒);
 * ③ 收集视频渲染器实例供后续下发渲染器消息(帧率匹配关闭/输出分辨率信令)。
 *
 * @param dynamicScheduling 动态调度开关(时长→进度,对应隐藏设置 exo_video_dynamic_scheduling)
 * @param videoRendererSink 本工厂创建的视频渲染器收集处
 */
class EngineRenderersFactory(
    context: Context,
    private val subtitleDelayUsProvider: () -> Long,
    private val videoRendererSink: MutableList<Renderer>,
    private val dynamicScheduling: Boolean,
) : DefaultRenderersFactory(context) {

    companion object {

        /** 视频解码器选择器:软解偏好经 [CodecPreferences] 动态读取(与旧内核同一真值源) */
        private val VIDEO_CODEC_SELECTOR = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val preferSoft = CodecPreferences.isPreferSoftwareDecode()
            val infos: List<MediaCodecInfo> =
                (if (preferSoft) MediaCodecSelector.PREFER_SOFTWARE else MediaCodecSelector.DEFAULT)
                    .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            LOG.i(
                "echo-exo-selector: mime=$mimeType preferSoft=$preferSoft count=${infos.size} " +
                    "first=${if (infos.isEmpty()) "none" else infos[0].name}",
            )
            infos
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val firstRendererIndex = out.size
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            VIDEO_CODEC_SELECTOR,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
        replaceWithReplayableRenderer(
            context,
            VIDEO_CODEC_SELECTOR,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            firstRendererIndex,
            out,
        )
        for (i in firstRendererIndex until out.size) {
            videoRendererSink.add(out[i])
        }
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val firstRendererIndex = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (i in firstRendererIndex until out.size) {
            out[i] = Proxy.newProxyInstance(
                Renderer::class.java.classLoader,
                arrayOf<Class<*>>(Renderer::class.java),
                SubtitleOffsetRendererHandler(out[i], subtitleDelayUsProvider),
            ) as Renderer
        }
    }

    /**
     * 把 super 建的默认 MediaCodecVideoRenderer 换成带可重放帧缓存的子类。构建设置须与上游 1.11.1 的
     * `DefaultRenderersFactory.createMediaCodecVideoRenderer` 逐项对齐(升级 media3 时回来对账);
     * 唯一例外:selector 必须下发 [VIDEO_CODEC_SELECTOR](软解偏好靠它,上游形参恒为 DEFAULT)。
     * 被换下的实例未 init/enable、不持编解码器与显示面,丢弃安全。
     */
    private fun replaceWithReplayableRenderer(
        context: Context,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        firstRendererIndex: Int,
        out: ArrayList<Renderer>,
    ) {
        for (i in firstRendererIndex until out.size) {
            val renderer = out[i]
            if (renderer !is MediaCodecVideoRenderer || renderer is ReplayableCacheVideoRenderer) {
                continue
            }
            val lateThresholdToDropDecoderInputUs =
                MediaCodecVideoRenderer.DEFAULT_LATE_THRESHOLD_TO_DROP_DECODER_INPUT_US
            var builder = MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                .experimentalSetParseAv1SampleDependencies(true)
                .experimentalSetLateThresholdToDropDecoderInputUs(lateThresholdToDropDecoderInputUs)
                .setEarlySchedulingThresholdUs(MediaCodecVideoRenderer.DEFAULT_EARLY_SCHEDULING_THRESHOLD_US)
                .setEnableDurationToProgressUs(dynamicScheduling)
            if (Build.VERSION.SDK_INT >= 34) {
                builder = builder.experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(false)
            }
            out[i] = ReplayableCacheVideoRenderer(builder, lateThresholdToDropDecoderInputUs)
            LOG.i(
                "echo-exo-video-renderer: replayable cache on, usesExoSelector=true " +
                    "preferSoft=${CodecPreferences.isPreferSoftwareDecode()}",
            )
            return
        }
        // 未换成功:保持上游默认渲染器,暂停态重绘随之失效(redrawReady 会挡掉),不影响播放
        LOG.i("echo-exo-video-renderer: media codec renderer not found, redraw disabled")
    }

    /** 文本渲染器代理:render() 的位置参数按字幕延迟回拨(delayUs 为负时按 0 夹取) */
    private class SubtitleOffsetRendererHandler(
        private val renderer: Renderer,
        private val delayUsProvider: () -> Long,
    ) : InvocationHandler {

        @Suppress("UNCHECKED_CAST")
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            var invokeArgs: Array<out Any>? = args
            if (method.name == "render" && args != null && args.isNotEmpty() && args[0] is Long) {
                // 运行时数组是 Object[](Java 传参),取可写副本后只替换位置参数
                val cloned = (args as Array<Any>).clone()
                cloned[0] = maxOf(0L, (args[0] as Long) - delayUsProvider())
                invokeArgs = cloned
            }
            try {
                return if (invokeArgs == null) method.invoke(renderer) else method.invoke(renderer, *invokeArgs)
            } catch (e: InvocationTargetException) {
                throw e.cause!!
            }
        }
    }
}
