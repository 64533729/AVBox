package com.github.tvbox.osc.util.kvcodec

/**
 * 编解码失败留痕出口。本包不依赖 Android(为可单测),故由 App 侧注入 `util.LOG` 实现;
 * 未注入时退化为 System.err,绝不静默丢弃(spec §4.6)。
 */
object KVLog {

    fun interface Sink {
        fun e(message: String)
    }

    private val FALLBACK: Sink = Sink { message -> System.err.println("[KV] " + message) }

    @Volatile
    private var sink: Sink = FALLBACK

    @JvmStatic
    fun setSink(newSink: Sink?) {
        sink = newSink ?: FALLBACK
    }

    @JvmStatic
    fun e(message: String) {
        sink.e(message)
    }
}
