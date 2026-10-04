package com.github.tvbox.osc.sourcedata

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 取数通道:Flow 为主面,LiveData 为过渡兼容面。
 *
 * <p>原先是 7 个裸 `MutableLiveData`,消费侧(Kotlin 页面 VM)各自拿 `observeAsFlow()` 把 LiveData
 * 转成 Flow —— 桥散在每个收集点。本类把"一次投递同时喂两个面"收口到通道自身:取数侧(Java Loader)
 * 只认 [postValue]/[setValue],消费侧只认 [flow]。
 *
 * <p>与 `MutableLiveData` 逐条对齐的语义:
 * - [postValue] 任意线程可调,观察面落到主线程(等价 `postValue`);
 * - [setValue] 主线程同步投递(等价 `setValue`);
 * - [flow] 是热流且 `replay = 1`:新收集者先拿到最近一次的值,等价 LiveData 的粘性("有值即回放");
 *   缓冲 64 与请求回包量级匹配,溢出丢最旧(DROP_OLDEST),生产者(主线程派发)永不被阻塞。
 *
 * <p>[liveData] 是留给播放层 Java(`PlaybackFetch`/`PreloadCoordinator` 的 `observeForever`)的兼容面,
 * 播放层迁移(M7)后删除,届时本类只剩 [flow]。
 */
open class SourceChannel<T> {

    private val shared = MutableSharedFlow<T>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val live = MutableLiveData<T>()

    /** 消费面:热流,新收集者先收到最近一次的值(等价 LiveData 粘性)。 */
    val flow: Flow<T> = shared

    /** 兼容面:播放层 Java 的 `observeForever` 入口,M7 收口后删除。 */
    val liveData: LiveData<T> get() = live

    /** 等价 `MutableLiveData.postValue`:任意线程可调。 */
    open fun postValue(value: T) {
        dispatchToLiveData(value, sync = false)
        shared.tryEmit(value)
    }

    /** 等价 `MutableLiveData.setValue`:主线程同步投递。 */
    open fun setValue(value: T) {
        dispatchToLiveData(value, sync = true)
        shared.tryEmit(value)
    }

    /**
     * LiveData 兼容面的唯一投递点。抽成可覆盖(fake 替身)是因为单测环境没有 Looper:
     * `MutableLiveData.setValue` 的主线程断言在 JVM 单测里直接 NPE(架构组件的既有环境限制),
     * 于是"投递走向"由替身记录,Flow 面仍走真实实现。
     */
    protected open fun dispatchToLiveData(value: T, sync: Boolean) {
        if (sync) live.setValue(value) else live.postValue(value)
    }
}
