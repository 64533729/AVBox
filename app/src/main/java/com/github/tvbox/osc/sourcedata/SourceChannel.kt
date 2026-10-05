package com.github.tvbox.osc.sourcedata

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 取数通道:取数侧投递,消费侧收 [flow]。
 *
 * <p>原先是 7 个裸 `MutableLiveData`,消费侧(Kotlin 页面 VM)各自拿 `observeAsFlow()` 把 LiveData
 * 转成 Flow —— 桥散在每个收集点。本类把投递收口到通道自身:取数侧(Java Loader)只认 [postValue]/[setValue],
 * 消费侧只认 [flow]。
 *
 * <p>语义(与旧 `MutableLiveData` 逐条对齐):
 * - [postValue]/[setValue] 任意线程可调,收集者在其自身调度器上恢复 —— 需要"主线程投递"的消费方
 *   自带 `Dispatchers.Main.immediate`(播放层与预载层即如此);
 * - [flow] 是热流且 `replay = 1`:新收集者先拿到最近一次的值,等价 LiveData 的粘性("有值即回放");
 *   缓冲 64 与请求回包量级匹配,溢出丢最旧(DROP_OLDEST),生产者永不被阻塞。
 */
open class SourceChannel<T> {

    private val shared = MutableSharedFlow<T>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 消费面:热流,新收集者先收到最近一次的值(等价 LiveData 粘性)。 */
    val flow: Flow<T> = shared

    /** 等价 `MutableLiveData.postValue`:任意线程可调。 */
    open fun postValue(value: T) {
        shared.tryEmit(value)
    }

    /** 等价 `MutableLiveData.setValue`:主线程同步投递。 */
    open fun setValue(value: T) {
        shared.tryEmit(value)
    }
}
