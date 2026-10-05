package com.github.tvbox.osc.testing

import android.app.Application

/**
 * Robolectric 用的**极简 Application**。
 *
 * <p>为什么必须有它:真实入口 `com.github.tvbox.osc.base.App.attachBaseContext` 会调
 * `KV.init(base)` → `MMKV.initialize` → `System.loadLibrary("mmkv")`。MMKV 是 native 库,
 * 纯 JVM(Robolectric)里不存在,于是每个 Compose 测试都会在 Robolectric 建 Application 阶段
 * 直接 `UnsatisfiedLinkError` 而根本跑不到测试体。
 *
 * <p>本类不初始化任何东西 —— 接线层测试只需要 Compose 的指针分发与 Android 框架桩,
 * 不需要 KV/OkGo/爬虫/播放器这些真实启动链。
 */
class TestApplication : Application()
