package com.github.avbox.osc

import androidx.activity.ComponentActivity

/**
 * Compose 测试宿主 Activity。
 *
 * <p>为什么必须存在,以及为什么落在**主源集**:
 * ① Robolectric 的 `ActivityScenario` 要真正实例化宿主 Activity。`ui-test-manifest` 提供的
 *   `androidx.activity.ComponentActivity` 不在本应用的运行期 dex(`androidx.activity` 只是经
 *   Compose 测试 artifact 传递的 `implementation` 依赖),于是报
 *   `Unable to resolve activity for Intent ...`。
 * ② 本工程 **`applicationId`(com.github.avbox.osc) ≠ `namespace`(com.github.tvbox.osc)**。
 *   若把宿主放在测试源集,`ActivityScenario` 按 applicationId 发 Intent、而 Robolectric 把类名
 *   规范化成相对 applicationId 的名字,清单合并侧却按 namespace 展开,**两边对不上**。
 *   放进主源集 + 主清单写绝对名后,两侧的名字天然一致。
 * ③ 包名取 `com.github.avbox.osc`(= applicationId),与上一条同一目的。
 *
 * <p>它没有任何逻辑、也不被任何产品代码引用,对发布包的唯一影响是清单里多一个
 * `exported=false` 的空 Activity。若后续要彻底去掉,可改为 `debug` 源集 + 调试清单。
 */
class ComposeTestActivity : ComponentActivity()
