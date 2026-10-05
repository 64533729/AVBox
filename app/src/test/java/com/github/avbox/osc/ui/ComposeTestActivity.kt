package com.github.avbox.osc.ui

import androidx.activity.ComponentActivity

/**
 * Compose 测试宿主 Activity(**必须有**)。
 *
 * <p>为什么不能直接用 `ui-test-manifest` 提供的 `androidx.activity.ComponentActivity`:
 * Robolectric 的 `ActivityScenario` 要真正实例化它,而该类不在本应用的运行期 dex 里
 * (`androidx.activity` 只是经 Compose 测试 artifact 传递的 `implementation` 依赖),
 * 于是报 `Unable to resolve activity for Intent ...`。
 *
 * <p>**包名为什么是 `com.github.avbox.osc.ui`**:本工程 `applicationId`(com.github.avbox.osc)
 * 与 `namespace`(com.github.tvbox.osc)不一致。`ActivityScenario` 按 applicationId 发 Intent,
 * 而 Robolectric 把本类名规范化为相对 applicationId 的名字(`.ui.ComposeTestActivity`);
 * 清单里必须写成**同一个** `com.github.avbox.osc.ui.ComposeTestActivity` 才能命中,
 * 见 `app/src/test/AndroidManifest.xml`。
 */
class ComposeTestActivity : ComponentActivity()
