package com.github.tvbox.osc.sourcedata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 门面构造器的接线锁:7 个通道实例必须**同一个**贯穿到消费方,`SourceRuntimeState` 的两张缓存
 * 必须是**同一个 map 实例**传给各 Loader。
 *
 * 为什么值得单独锁:
 * - `SourceResultParser` 靠**身份**分投(`result === detailResult` → 走 push/迅雷后处理;
 *   `searchResult === result` → 走 EventBus)。通道实例一旦被复制一份,分投静默走错分支 ——
 *   表现是"详情不解析 push://""搜索面板收不到结果",而编译与静态检查都看不出来。
 * - `SourceRuntimeStateTest` 里长期挂着一条未覆盖缺口:"5 个 Loader 拿到的是同一个 map 实例"
 *   没有用例(当时 `new SourceViewModel()` 在纯 JVM 单测里构造即挂 —— 真因是消费方
 *   `observeForever` 的 `assertMainThread`,M4a 把消费面换成 `flow` 后该阻塞已消失,
 *   于是这条缺口可以补上)。换源清理(`clearRuntimeCache`)只清这一份实例,接线断了就会"清了又生"。
 *
 * 用反射读私有字段:这些协作者是构造器内部细节,不为了测试把它们开成 public。
 */
class SourceViewModelWiringTest {

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target) as T
    }

    private fun loader(vm: SourceViewModel, name: String): Any = field<Any>(vm, name)

    @Test
    fun channelsAreTheSameInstancesAcrossFacadeParserAndResolver() {
        val vm = SourceViewModel()
        val parser = field<SourceResultParser>(vm, "resultParser")
        val resolver = field<PushDetailResolver>(vm, "pushDetailResolver")

        // 解析器的身份判定对象必须是门面暴露的那两个实例
        assertSame("search 通道身份不符 ⇒ 搜索会走错分投分支", vm.searchResult, field(parser, "searchResult"))
        assertSame("detail 通道身份不符 ⇒ 详情会跳过 push/迅雷后处理", vm.detailResult, field(parser, "detailResult"))
        assertSame("resolver 也必须拿同一个 detail 通道(它自己回投结果)", vm.detailResult, field(resolver, "detailResult"))
        assertSame("解析器与 resolver 必须是同一个 resolver 实例", resolver, field(parser, "pushDetailResolver"))
    }

    @Test
    fun everyLoaderGetsTheFacadeChannelInstance() {
        val vm = SourceViewModel()
        assertSame(vm.sortResult, field(loader(vm, "sortLoader"), "sortResult"))
        assertSame(vm.listResult, field(loader(vm, "listLoader"), "listResult"))
        assertSame(vm.detailResult, field(loader(vm, "detailLoader"), "detailResult"))
        assertSame(vm.searchResult, field(loader(vm, "searchLoader"), "searchResult"))
        assertSame(vm.playResult, field(loader(vm, "playLoader"), "playResult"))
        assertSame(vm.preloadResult, field(loader(vm, "playLoader"), "preloadResult"))
    }

    @Test
    fun sevenChannelsAreDistinctInstances() {
        val vm = SourceViewModel()
        val channels = listOf(
            vm.sortResult, vm.listResult, vm.searchResult, vm.detailResult,
            vm.actionResult, vm.playResult, vm.preloadResult,
        )
        assertEquals("通道被复用会互相串扰(如预载结果顶掉真实取流)", 7, channels.toSet().size)
    }

    @Test
    fun loadersShareTheRuntimeStateCacheInstances() {
        val vm = SourceViewModel()
        val sort = loader(vm, "sortLoader")
        assertSame(
            "extendCache 接线断了 ⇒ 换源清理清了副本、Loader 还在用旧的",
            SourceRuntimeState.extendCache,
            field(sort, "extendCache"),
        )
        assertSame("sortCache 必须就是运行期状态里那一份(上限/access-order 语义靠它)", SourceRuntimeState.sortCache, field(sort, "sortCache"))
        assertSame(SourceRuntimeState.extendCache, field(loader(vm, "listLoader"), "extendCache"))
        assertSame(SourceRuntimeState.extendCache, field(loader(vm, "detailLoader"), "extendCache"))
        assertSame(SourceRuntimeState.extendCache, field(loader(vm, "searchLoader"), "extendCache"))
        assertSame(SourceRuntimeState.extendCache, field(loader(vm, "playLoader"), "extendCache"))
    }
}
