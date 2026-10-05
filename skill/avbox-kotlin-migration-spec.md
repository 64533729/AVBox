---
name: AVBox Java→Kotlin 迁移转换规范（活规范）
description: M0 立规产出——迁移流程与禁止项、Kotlin 静态/字段/构造器转换细则、Gson×data class 规范、并发不变量、等价性卡口与字节码卡口脚本用法、M0 实测基线
---

# 0. 适用范围与上游

- **上游计划**：`skill/review/refactor-plan-20261005.md`（M 系里程碑、D 系决策、“目标终态”验收清单）。本文是该计划 §7.1 / §8 的**落地细则**，冲突时以计划文档的决策为准、执行细则以本文为准。
- **本规范在以下场景必读**：任何 Java→Kotlin 迁移步；改动 `com.github.catvod.**` 或 `player` 模块（`xyz.doikki.videoplayer.**`）；改动被动态 jar / 第三方 AAR 按名字访问的宿主静态面（D9 Tier A/B）。
- **最高优先事项**：迁移的目标是**签名不变**。判据不是“用了某个注解”，而是 `javap -p -s` 输出等价。

# 1. M0 实测基线（2026-10-05，迁移起点）

| 项 | 实测值 | 命令 / 备注 |
| --- | --- | --- |
| `:app:assembleDebug` | BUILD SUCCESSFUL | `.\gradlew.bat :app:assembleDebug` |
| `:app:testDebugUnitTest` | **501 用例 / 0 失败 / 0 错误 / 0 跳过（65 个 suite）** | 计数取自 `app/build/test-results/testDebugUnitTest/*.xml` 的 `tests` 求和 |
| **M1 后复测（2026-10-05）** | `:app:assembleDebug` + `:app:assembleRelease` 绿；`:app:testDebugUnitTest` **516 用例 / 0 失败**；`app/src/main/java` **183 Java / 167 Kotlin** | `bean/` 21 个已全量迁 Kotlin（提交 `5d0a35e` 族 A + `1098119` 族 B + 复审修复 `7cad57f`）；用例数 = 基线 501 + 新增 15 例反序列化/等价性回归 |
| **M2 后复测（2026-10-05）** | `:app:assembleDebug` + `:app:assembleRelease` 绿；`:app:testDebugUnitTest` **516 用例 / 0 失败**；`app/src/main/java` **174 Java / 179 Kotlin**；`app/schemas` 逐字未变 | `data/` 建 3 个 Repository（`History`/`Collect`/`Cache`）+ `AppGraph` + `CurrentSubscription`；DAO/entity 7 个迁 Kotlin；`RoomDataManger`/`CacheManager` 两个 Java 门面删除（逻辑并入 Kotlin 实现）；提交 `cb14e6b`/`d608631`/`619a4fc`/`094e301`；`javap` 差异见 §7.4 |
| **M3 后复测（2026-10-05）** | `:app:assembleDebug` 绿；`:app:testDebugUnitTest` **523 用例 / 0 失败**（516 基线 + 7 例新增）；`app/src/main/java` **166 Java / 187 Kotlin**（本里程碑 -8 j / +8 kt） | 8 个叶子类迁 Kotlin：`util/{RegexUtils,EpisodeMatcher,MD5,StringUtils}` + `sourcedata/{SourceHelper,PushUrlParser,PushDetailResolver,SourceResultParser}`；7 笔迁移提交 `a342923`/`d2db4eb`/`b6f4a86`/`99e73ac`/`e06a079`/`300163b`/`27bbd3e` + 1 笔告警清理 `716de19` + 1 笔审查轮修复 `303c603`；实测规则、保留告警与审查账目见 §7.5 |
| **M4a 后复测（2026-10-05，含审查轮）** | `:app:assembleDebug` + `:app:assembleRelease` 绿；`:app:testDebugUnitTest` **534 用例 / 0 失败**（523 基线 + 11 例新增）；源文件计数不变（无语言迁移）；Tier B 11 符号未变 | 7 个通道换 `sourcedata/SourceChannel`（Flow 主面 + LiveData 兼容面），5 个 Loader 的输出参数、`SourceResultParser`/`PushDetailResolver` 参数、`SourceViewModel` 7 个字段随之改；3 个页面 VM 改收 `flow`；`LiveDataFlow.kt` 已删；实测规则与语义差异见 §7.6 |
| **M4b 后复测（2026-10-05，含审查轮）** | `:app:assembleDebug` + `:app:assembleRelease` 绿；`:app:testDebugUnitTest` **536 用例 / 0 失败**（534 基线 + 2 新增）；`app/src/main/java` **159 Java / 194 Kotlin**（-7 j / +7 kt）；Tier B 11 符号未变、`app/schemas` 未动 | 5 个 Loader + `SourceViewModel` + `SourceRuntimeState` 迁 Kotlin（纯语言迁移，生产面仍 `postValue`/`setValue`）；既有 534 用例（含 `SourceViewModelWiringTest`/`SourceRuntimeStateTest`/`PlayLoaderSeqTest`）**零改动全绿**；实测规则见 §7.7 |
| **M5 后复测（2026-10-05，含审查轮）** | `:app:assembleDebug` + `:app:assembleRelease` 绿；`:app:testDebugUnitTest` **536 用例 / 0 失败 / 0 错误 / 0 跳过（70 suite）**（与 M4b 基线持平，纯语言迁移无新增用例；另 `--no-build-cache` 与 `--rerun-tasks` 各跑一次确认）；`app/src/main/java` **151 Java / 202 Kotlin**（-8 j / +8 kt，`api/` 包 Java 清零）；Tier B 11 符号未变、`app/schemas` 未动 | `api/` 全包 8 个类迁 Kotlin（门面 `ApiConfig` + 7 个职责文件）；`javap -p -s` debug/release 两侧差异一致、**零公开成员消失或改名**（`ApiConfig.get()` 等静态入口与两个回调接口逐成员守恒）；`ApiConfig.get()` 调用点实测 **102 处 / 36 文件**（M0 记 104/37，差额是 M1–M4b 删/改文件造成的自然漂移，非本里程碑改动）；实测规则、Kotlin 侧改写清单与登记差异见 §7.8 |
| `app/src/main/java` 语言构成 | **204 Java / 146 Kotlin** | M0 起点快照（口径 = 仓库自有 main 源码）；后续切片计数见上表各行 |
| `player` 模块 | **27 Java / 0 Kotlin** | `player/src/main/java/xyz/doikki/videoplayer/**` |
| `app/src/python/java`（Chaquopy sourceSet） | **5 Java** | `com/github/catvod/crawler/pyLoader.java` + `com/undcover/freedom/pyramid/{PyLog,PythonLoader,PythonSpider,PyToast}.java`；**计划 §2 与 M11 口径未覆盖**，已登记入计划 |
| `app/src/test` | 12 Java / 53 Kotlin | **不在迁移范围**（§3.1 口径 = `main`），终态判据不含测试 |
| `libs/backdrop` 子模块 | 31 Kotlin / 0 Java | 无 Java |
| `ApiConfig.get()` 依赖面 | **104 命中 / 37 文件** | 计划 §2 原写 106/37，以本行为准 |
| `observeForever` 面 | 2 处 | `player/PlaybackFetch.java`、`player/PreloadCoordinator.java` |
| DAO / Manager 直连面 | 75 命中 / 16 文件 | `AppDataManager.get()` / `RoomDataManger` / `CacheManager` |

**行尾归一化说明**：仓库 `core.autocrlf=input`，索引里全部是 LF。工作区有 12 个既有 `.kt` 文件（`osc/util/` 下）落盘为 CRLF（`git ls-files --eol` = `i/lf w/crlf`），提交时会被归一化，**属既有状态、不顺手批量转换**；本次改动的文件一律保证落盘 LF。

# 2. 每个切片的四步与卡口

沿用计划结论摘要的四步：① Kotlin 定义 Repository 接口 + 薄委托 → ② 消费方改依赖接口 → ③ 背后 Java 实现迁 Kotlin → ④ 删旧入口。每步独立 commit、独立回滚。

**每切片收尾必跑**（不攒到最后）：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

判据 = `BUILD SUCCESSFUL` + 用例数 ≥ 基线 501（迁移步只许持平或增加）。

# 3. Kotlin 转换规范

## 3.1 通用（全库适用）

1. **禁止只靠 IDE 自动转换就提交**（D4）。自动产物常见 `!!`、冗余判空、把可空语义改成非空；必须逐行过等价性 + 手工收敛为 Kotlin 惯用写法。
2. **迁移 ≠ 重构**（§7 硬约束 5）：不改逻辑、不改命名、不收紧可见性（`private`→`public` 需在 commit 里登记理由）。要改逻辑单独立项。
3. **Kotlin 零值属性不生成 `putfield`**（§7 硬约束 6）：凡 `initView()` 之后才使用的协作对象，一律 `lateinit` 且**在 `initView()` 内构造**，别在属性初始化器里建（会造成虚调用时序变化）。
4. **行尾 LF**：改完立刻用卡口脚本 `-Action lf` 核查本次改动文件（工具倾向写 CRLF）。
5. **UI 层不新增注释**；既有 KDoc 只做最小事实同步（推翻的旧决策措辞要清掉。M4a 先例：`sourcedata/LiveDataFlow.kt` 里“不重写 Java→Kotlin”那句随该文件删除；`HomeViewModel`/`PartitionListViewModel`/`DetailViewModel` 里“`observeForever` 有主线程断言 / 桥接器的 `awaitClose` 摘观察者”这两句改成事实口径）。

## 3.2 契约层（`catvod` + `player` 模块）逐条清单

计划 §7.1 的 a–j 是硬规则，本节给可执行的判定：

| Java 形态 | 必须写成 | 写错的后果（产物差异） |
| --- | --- | --- |
| 类被子类继承/覆盖（`Spider`、`BaseVideoController`、`VideoView`…） | `open class` + 被覆盖成员 `open` | Kotlin 默认 `final` → jar/AAR 子类 **类加载期** `VerifyError: overrides final method` |
| `static` 方法 | `companion object { @JvmStatic fun m() }` | 只有 `companion object` → 叫 `X.INSTANCE.m()`；jar 按 `X.m()` 调 → `NoSuchMethodError` |
| `static` 可写字段 | `companion object { @JvmField var f }` | 缺 `@JvmField` → 变静态 getter/private field → `NoSuchFieldError` |
| `static final` 基本类型/String 常量 | `const val`（内联，等价）或 `@JvmField val` | 普通 `val` → 静态 getter，字段消失 |
| `public` 实例字段 | `@JvmField var f` | 普通属性 = private field + getter/setter → Java 子类/同包直接读字段编译失败或运行时 NoSuchField |
| `protected` 实例字段 | `@JvmField protected var f` | 同上（`VideoView.mVideoController`、`BaseVideoController.mControlWrapper` 是已知必中项） |
| 自定义 View / Java 侧按固定参数调用的类 | `@JvmOverloads constructor`，保 `(Context)` / `(Context, AttributeSet)` / `(Context, AttributeSet, Int)` 三重载 | Kotlin 默认参数只生成主构造器 + `$default` 合成方法 |
| 顶层函数 / 扩展函数 / `object` 单例 | **禁止**（契约层） | 生成 `XxxKt.m()` 或 `X.INSTANCE.m()` |
| `internal` 可见性 | **禁止** | 会给成员加 `$module` 后缀，签名被改 |
| 默认参数替代重载、`@JvmName` | **禁止** | 多出 `xxx$default` 合成方法或改掉描述符 |
| 包名/类名/方法名/字段名 | **一字不改**（含 `JarLoader` 里的 FQCN 字符串、`JsLoader` 的 `com.github.catvod.js.Function`/`Method`） | 名字本身就是契约 |

**`object` 的边界**：纯静态工具类（无实例语义、且**没有**被 jar/AAR/JS 按类名静态访问）允许 `object`；一旦在 D9 Tier A/B 清单里，一律 `companion object` + `@JvmStatic`/`@JvmField`。

## 3.3 非契约层的静态面

`sourcedata/SourceHelper.SPIDER_POOL`/`PREPARE_POOL` 这类**只被 Kotlin 调用方**使用的静态成员：迁 `object` 或 `companion object` 都行，但**必须同步改全部调用点**（同一 commit 内成对完成），并在 commit message 里点出调用点数量。风格先例 = `util/BoundedCall.kt`。

# 4. Gson × data class 规范

## 4.1 序列化契约不变

- **属性名 = 旧 Java 字段名**：Gson 走字段反射，Kotlin 属性名即 backing field 名。重命名必须用 `@SerializedName("旧名")` 固定，否则字段改名 = 序列化契约变更。
- **`@SerializedName` 原样保留**，不增不减不换值。
- **bean 属性必须有 backing field**：不要给 bean 写自定义 getter/setter 或纯计算属性（Gson 看不到，字段会从 JSON 里消失）。
- 静态字段、`@Expose`/`transient`/`@Transient` 的语义保持原样。

## 4.2 无参构造与默认值

Gson 用 `Unsafe` 直接分配实例、**不走构造器**，所以“没有无参构造”本身不会让 Gson 反序列化失败。但保底仍要做：

- 迁移时**给所有属性默认值**（`var x: T? = null`），这样 Kotlin 会生成无参构造，兼容其他反射路径（Kotlin 反射、`copy()`、未来换序列化器）。
- 不要引入 no-arg 插件（换栈/加插件都超出本 spec 范围）。

## 4.3 null 语义

- Java 字段没有 null 标注 ⇒ 一律按**可空**迁（`var x: T? = null`）。**不要**把 Java 的可空字段写成 Kotlin 非空类型：Gson 用 `Unsafe` 赋值会绕过 Kotlin 的 null 检查，缺字段的 JSON 会得到一个“非空类型但值是 null”的对象，之后第一次访问由编译器插桩抛 NPE——崩溃点从“解析时”挪到“使用时的任意位置”。
- 只有旧 Java 代码本来就必然崩/本来就有兜底的字段，才允许保持非空，并在 commit 里登记。

## 4.4 data class 的隐式语义变化（M1 必查项）

`data class` 会额外生成 `equals`/`hashCode`/`toString`/`copy`/`componentN`，而旧 Java POJO 默认是 **identity equals**。若该 bean 参与以下任意场景，行为会变：

- 进 `HashSet`/`HashMap`/`Set`/`distinct`/`contains`/`indexOf`/`==` 比较；
- 作为 `MutableState`/`remember` key 或 `diff` 依据；
- 被当作缓存 key。

**做法**：迁移前用 `search_content` 核查该 bean 的消费点；命中且语义敏感就改普通 `class` + 只补必要成员，或把“为什么结构相等是安全的”写进 commit。`copy()` 属新增 public API，对 Tier A 面要按“新增方法”处理。

## 4.5 反序列化回归（逐字段）

每个 bean 迁移时留一份**旧 Java bean 的 JSON 快照**（真实接口回包片段优先，其次手写全字段样例），比对三件事：① 反序列化后逐字段值一致（含 null / 缺字段 / 多余字段三种输入）；② 再序列化输出的字段集合与旧实现一致；③ 数值/日期/集合泛型的类型形态一致。回归放单测里，别靠人眼。

# 5. 并发与线程不变量（迁移不得改动，D6）

- `SourceHelper.SPIDER_POOL` / `PREPARE_POOL` 是**共享池**，不得变成每请求新建；迁 Kotlin 后仍指向同一实例。
- QuickJS 线程亲和：超时不能 cancel 掉正在跑脚本的线程。
- `allowMainThreadQueries()` 主线程查库是显式设计；进度写入走 `vod-progress-writer`（读须 `awaitWrites`）。
- `synchronized (sortCache)` 的 access-order 加锁语义原样保留。
- Repository 内出现 `withContext(` / `flowOn(` 即驳回（除 M0 白名单）——会改 D6 线程语义与读序。

# 6. 唯一授权的结构变更

仅 M2/M4 的“建 Repository 接口 + 消费方改注入点 + 实现藏到接口后”。其余（上帝类拆分、包改名、线程模型变更、DI 引入）一律单独立项。

Repository 约定（D2/D7/D10/D11）：接口与 Room 域/内容域实现统一落 `com.github.tvbox.osc.data`（`sourcedata/` 是否保留为实现子包在 M5 定）；输出二分——一次性查询 `suspend fun`、持续观察 `Flow`；构造参数只接窄接口（DAO 接口/网络 API 接口），装配只走 `AppGraph`（Kotlin `object`），**实现内禁止直取 `AppDataManager.get()`/`ApiConfig.get()`**（项目无 Mockito/Robolectric，直取静态入口 = 无法构造替身 = 单测验收落不了地）。

# 7. 卡口

## 7.1 等价性 / 字节码 / 构建

| 卡口 | 用在哪 | 判据 |
| --- | --- | --- |
| 归一化多重集比对 | 同语言搬迁（移动、包改名、原样搬） | **新旧任一侧有增删即违规**（原样搬迁不该有任何内容变化，「新增了 2 行」也必须解释） |
| 跨语言 token 多重集 | Java→Kotlin 迁移 | **旧有新无 = 0**（标识符/字符串/数字字面量丢失 = 漏迁）；新有旧无 = 参考项 |
| 方法级存在性 | 纯搬迁步的粗网 | 旧侧**声明**（方法/构造函数名，不认调用点）在新文件全部命中 |
| `javap -p -s` 逐类描述符 | 契约层（M9/M10）与公开签名变更 | 与基线逐类一致；新增/消失/描述符变化即驳回；debug 与 release 各跑一次 |
| 构建 + 单测 | 每切片 | `BUILD SUCCESSFUL` + 用例数 ≥ 501 |

**关于 `javap` 的一侧可比性**：迁移后 Java 产物消失，所以**在动契约层之前**先把基线快照导出；要 debug/release 两侧就分别构建后各导一次。

**快照前必须重新构建对应变体**（`assembleDebug` / `assembleRelease`）：产物目录会残留已删除源码的陈旧 `.class`（2026-10-05 实测：`app/build/intermediates/javac/release/**` 里还留着 3 个已删类的 `ProtectedInitJar*`）。脚本遇到同名类出现在多个产物目录时会告警——出现告警就先重跑构建，否则可能拿到陈旧产物造成假通过。

## 7.2 脚本用法（`skill/scripts/verify-migration.ps1`）

```powershell
# 同语言搬迁：与 git 基线逐行去空白多重集比对
pwsh skill/scripts/verify-migration.ps1 -Action multiset -Path <旧路径>

# Java→Kotlin：标识符/字面量多重集（只看“旧有新无”）
pwsh skill/scripts/verify-migration.ps1 -Action tokens -Path <旧路径> -NewPath <新路径>

# 纯搬迁粗网：方法名存在性
pwsh skill/scripts/verify-migration.ps1 -Action methods -Path <旧路径> -NewPath <新路径>

# 契约层字节码基线（Java 产物；release 需先跑 assembleRelease 再指定 -ClassPath）
pwsh skill/scripts/verify-migration.ps1 -Action javap -Snapshot -Package com.github.catvod -Out <基线文件>
pwsh skill/scripts/verify-migration.ps1 -Action javap -Baseline <基线文件> -Package com.github.catvod

# 行尾 LF 核查
pwsh skill/scripts/verify-migration.ps1 -Action lf -Path <本次改动的文件/目录>

# D9 Tier B 清单：catvod → osc.* 逆向引用
pwsh skill/scripts/verify-migration.ps1 -Action tierb
```

- 退出码：`0` 通过 / `1` 有差异；`javap` 默认在 `app` + `player` 的 debug 产物目录里找 class，跨模块或 release 用 `-ClassPath` 显式指定。
- 匿名类与 lambda 合成类（`X$1`、`X$foo$1`）默认跳过——两侧命名规则本来就不同，比对无意义；命名内部类用 `-IncludeInner`。
- **`tokens` 的已知盲区（写清楚，别当它是全量保证）**：Java/Kotlin 关键字两侧一律剔除，因此与关键字同名的标识符（`in`/`out`/`it`/`data`/`open` 等）的丢失不被它覆盖 —— 这类遗漏由编译错误（未解析引用）、`methods`、`javap`、单测兜。反之 `tokens` 的「新有旧无」只是参考项（Kotlin 惯用写法会新增标识符），不要当违规计数。
- **`methods` 只认声明形态**：以 `return`/`if`/`for`/`Log.e(...)` 之类的语句行开头的一律不算成员，避免把调用点当成方法名（否则卡口会退化成「两个文件都有 `for` 就算通过」）。
- 四个子命令都做过正/反向自测（同文件对照 = 通过；与不相干文件或改动后基线对照 = 报差异且退出码 1），改脚本后请重跑这组对照。
- **Tier B 实测初值（2026-10-05，11 个符号）**：`osc.server.ControlManager`、`osc.server.RemoteServer`、`osc.util.AppContextHolder`、`osc.util.FileUtils`、`osc.util.KV`、`osc.util.LanguageManager`、`osc.util.LOG`、`osc.util.MD5`、`osc.util.OkGoHelper`、`osc.util.SSL.SSLSocketFactoryCompat`、`osc.util.StringUtils`。

## 7.3 M1 实测登记（2026-10-05，`bean/` 全量 21 个）

**结论**：`bean/` 21 个 Java 全部迁 Kotlin，**`javap -p -s` 逐类比对（含内部类，debug/release 两侧差异完全一致）无任何公开成员消失或改名**；新增 `app/src/test/.../bean/BeanSerializationRegressionTest.kt`（13 例）钉住 XStream/Gson 契约。

**`tokens` 卡口对 Java→Kotlin 不可当硬卡口（实测，待并入 M0 卡口修缮）**：`bean/` 21 个文件跑 `-Action tokens`，`旧有新无` 命中量 8–51 项，逐条核对后**没有一项是逻辑缺失**，来源只有四类：
1. **注释里的示例字符串** —— 脚本先抽全文字符串字面量、再剥注释，所以 `// : "20"` 这类注释里的示例值（`AbsJson.java` 30+ 处）被当成"丢失的字面量"；
2. **访问器名** —— `getXxx/setXxx` 在 Kotlin 里折成属性，`getName`/`setUrl` 之类 token 消失（描述符其实一字未改，这正是 `javap` 该管的事）；
3. **JDK 类型 / 导入拼写** —— `java` / `util` / `List` / `ArrayList` / `String` / `Integer`；
4. **Java 专有调用形态** —— `charAt`→`[]`、`length`→`.size`、`getAsJsonObject`→`.asJsonObject`、`isEmpty`→`isNullOrEmpty`、`i` 计数循环→`in 0 until`。

→ 建议给 `tokens` 加 `-Lang java2kotlin` 预处理（先剥注释再取字面量；`get[A-Z]`/`set[A-Z]` 归一成属性名；JDK 类型同义表 + `charAt/length/isEmpty` 映射），否则该子命令在 M2–M11 只能当"参考打印"，不能当"退出码即判据"。（本次以 `javap` + 行为回归作为硬门。）

**Kotlin 产物差异目录（M1 全量实测、已逐类审查通过）**：
- `public class` → `public final class`（方法同理补 `final`）—— bean 无子类，安全；
- 新增 `public static final int $stable` 与 `<clinit>`（Compose 编译器产物）；`VodInfo` 少了 `$assertionsDisabled`（Kotlin `assert` 不用该字段）；
- 类的静态成员改由伴生对象承载：新增 `Companion` 静态字段 + `X$Companion` 类（`Danmu`/`Depot`/`ProxyRule`），原 `private static` 助手移入伴生对象；
- 私有/包私有字段名跟随 Kotlin 属性名（`liveChannelItems`→`liveChannels`、`url`→`urlValue`、`name/url`→`rawName/rawUrl`、`itemSelected`→`isItemSelected`）；
- 包私有放宽为 public（`Epginfo.timeFormat`、`LivePlayerManager` 两个配置字段、`Depot.string`）——Kotlin 无包私有，按"只许放宽"取值。

**Java→Kotlin 语义陷阱（M1 实际踩到，后续里程碑逐条照查）**：
1. **`String.split(String)` 语义不同**：Java 按**正则**、Kotlin 按**字面量**。`"a$$$b".split("\\$\\$\\$")` 在 Kotlin 里切不开（静默退化成单元素）。必须写 `split(Regex("\\$\\$\\$"))`。（M1 靠新增回归用例抓到，属高危静默缺陷。）
2. **Java 公开字段被 Java 侧按字段语法读写时，Kotlin 必须 `@JvmField`**（否则 Java 侧编译不过，Gson 字段名也会变）。
3. **Kotlin 属性名与 Java 字段名不一致时，Gson/XStream 走的是属性对应的 backing field**：既要保字段名又要自定义 getter（`ParseBean.url` 走 `checkReplaceProxy`、`LiveChannelGroup.liveChannelItems` 的 getter 叫 `getLiveChannels`）时，只能把 backing field 换名并登记（本次 4 处）。
4. `getX()`/`setX()` 与 Kotlin 默认命名不同时（`getIsZip`/`setIsZip`、`getIsNew`/`setIsNew`），用 `@get:JvmName` / `@set:JvmName` 才能保住。
5. **XStream 不走构造器**：`Sun14ReflectionProvider` 分配实例时不执行字段初始化器（Kotlin 属性初始化器同理），所以 Java 的 `= -1` / `= new ArrayList<>()` 在反序列化结果里**本来就是 JVM 默认值**——别把它当迁移回归（生产 `SourceResultParser` 里 `if (sort.filters == null) sort.filters = new ArrayList<>()` 就是这个兜底）。
6. **XStream 1.4 在 JVM 单测里默认拒绝未放行类型**（`ForbiddenClassException`），生产 `SourceResultParser` 没有 `addPermission` 而是 try/catch 吞掉；单测里验证 XStream 映射必须显式 `addPermission(AnyTypePermission.ANY)`（属验证手段，不是改生产逻辑）。
7. 非静态内部类（`AbsJson.AbsJsonVod`、`AbsSortJson.AbsJsonClass`）必须 `inner class` 才能保住 `this$0` 与 `(Outer)V` 构造器；Gson 对这类类走 `Unsafe`，行为不变（`SourceResultParserRoutingTest` 已覆盖）。
8. Kotlin 非空属性被 Gson `Unsafe` 赋 null 时，Java 里那句显式判空（`if (filterSelect == null) return 0`）可能被编译期优化掉——语义敏感的兜底要用"可空类型的局部变量"承接，别直接对非空属性判空。
9. `@SerializedName(alternate = ...)` 的 `alternate` 是 `String[]`，Kotlin 要写 `["id"]`。
10. **Java 集合里的 null 元素（M1 复审实测，真踩到）**：`ArrayList<T>` 这类 **Java 具体集合**的元素在 Kotlin 侧是平台类型，`for (x in list)` 会插 `checkNotNull(next(...))`；而 Kotlin 自己的 `MutableList<T>` 不会插。所以 Java 版「跳过 null 元素」的判空（`if (x == null) continue`）在 Kotlin 侧有两种失效方式：被编译期判成**恒假/恒真**（`w: Condition is always 'false'/'true'`）而不再生成，或压根轮不到就已经在迭代处 NPE。保法有二：把**元素类型**写成可空（`ArrayList<T?>`，字段描述符与泛型签名都不变、Java 调用方无感），或用可空局部量承接（`val item: T? = x`）。实测两处：`AbsSortJson.classes`（需改元素类型，因为它用的是 `ArrayList`）、`ProxyRule.init` 的 hosts（用 `MutableList`，只需可空局部量）。反之 Java 本来就 NPE 的路径（如 `AbsJson.list` 的 `list：[null]`）不用动。
11. **复审手段（M1 靠它抓出上一条）**：`.\gradlew.bat :app:compileDebugKotlin --rerun-tasks` 后过滤 `^w:` 里本次改动文件 —— **`Condition is always 'true'/'false'` 这类告警就是"Java 的防御性判空在 Kotlin 侧可能不再执行"的定位器**，`Java type mismatch: inferred type is 'T?', but 'T' was expected` 则是"把可空值喂给了 Java 形参"（多数是 NPE 等价，逐条确认即可）。

# 7.4 M2 实测登记（2026-10-05，`data/` Room 域 Repository + DAO/entity 迁 Kotlin）

**结论**：四步走完（4 笔 commit），`app/schemas` 逐字未变（Room schema 兼容）、`javap -p -s` debug 与 release 差异完全一致、单测 516 持平。以下条目是 M2 现场核实出的规则，M3 起照查。

1. **Room 3 + KSP 生成的是 Kotlin 实现类（最重要）**：产物里可见 `kotlin.Lazy`、`kotlin.coroutines.Continuation`、`$stable` —— `VodRecordDao_Impl`/`AppDataBase_Impl` 是 Kotlin 写的。因此 **DAO 接口的 `String` 入参若写成非空**，生成实现会在 override 上插 `checkNotNullParameter`，把 Java 原平台类型下的「传 null ⇒ SQL 匹配不到行」变成崩溃。判据：原 Java 未标注的 `String` 入参一律迁成 `String?`（描述符不变、schema 不变）。
2. **实体字段要 `@JvmField`**：Java 侧按字段语法读写（`cache.key = ...`、`record.cid = ...`）时必须保留可见字段；属性名即字段名（Gson 与 Room 都按它走）。
3. **冗余 `@NonNull` 可以删**：Kotlin 非空类型已承载该语义，删注解既不进 `javap -p -s` 也不改 schema —— 判据是 `git status app/schemas` 干净。
4. **`@JvmStatic` 加在 `object` 的 `val` 上只保留静态 getter**（实例级 getter 消失，描述符相同只差 `static`）：Kotlin 调用方仍按属性访问，Java 因此可写 `AppGraph.getCacheRepository()`。
5. **Gson 的显式类型实参**：`fromJson(record.dataJson, type)` 推不出 `T`，必须 `fromJson<VodInfo>(...)`；推断结果保持平台类型，于是「解析出 null → 下一行 NPE → 被同一个 catch 吞掉并落日志」的 Java 语义原样成立。**不要**用 `!!` 补，也不要改成静默丢。
6. **Java 集合的防御判空**：`for (Cache row : rows) if (row == null || row.data == null)` 里的 `row == null` 在 Kotlin 侧（`List<Cache>` 元素非空）恒假、不可达，按不可达处理并登记；`row.data == null` 是真实分支必须保留（与 §7.3 陷阱 10 同源，区别是这里元素类型非空 ⇒ 现状真的不可达，故不改元素类型）。
7. **行尾**：`write_to_file` 写出的新文件是 **CRLF**，`replace_in_file` 保留原行尾。新建文件后必须转 LF 再过 `-Action lf`。（`util/HistoryMerge.kt` 属既有 `i/lf w/crlf` 的 12 个文件之一；本次改动后已落盘 LF。）
8. **`-ClassPath` 传数组**：脚本以原生进程调用时 `-ClassPath a,b` 会被当成一条路径，须写 `-ClassPath @('a','b')`，即从 PowerShell 里用 `& ./skill/scripts/verify-migration.ps1 ...` 调用。
9. **`AppGraph` 传 provider 而不是 DAO 实例**：`AppDataManager.backup/restore` 会 close 并重建 DB 实例，缓存 DAO = 恢复之后所有读写落到已关闭实例。provider 只在装配点取 DAO，满足 D11「实现内不直取静态入口」。
10. **M2 与 D10 的取舍已登记**（计划 §5 M2 偏差 ②）：Repository 方法一律阻塞式，未上 suspend/Flow —— D6（定案）「不改线程语义」优先于 D10（待拍板）；改 suspend 必须同时把消费方协程化（`DetailViewModel.toggleCollect`、`CollectPage` 的 `currentCid()` 都在主线程同步取值）。

11. **新增的非空入参 = 新增崩溃边界（M2 审查轮实测，最容易被漏）**：Java 平台类型入参在 Kotlin 侧写成非空后，实现的入口会插 `checkNotNullParameter`（`javap -c` 可见），null 从「旧实现的正常处理」变成 NPE。`CacheRepository` 三入口就栽在这里：`get`/`delete` 的 `key` 由 `MD5.string2MD5(...)` 提供、空串入参返回 null，旧门面对 null 是「查不到 / 空操作」，非空声明后成了新 NPE。**判据**：逐个入参问「旧实现遇到 null 做什么」——① 旧实现正常处理（null 查询 / 空操作 / 返回 null）⇒ 必须可空；② 旧实现本来就抛（如空主键写 SQLite）⇒ 保留非空，别顺手改成静默不落库（那属于行为变更，且违反「不静默吞错」）。落库类实体的主键**不能**为可空：`Cache.key` 声明 `String?` 时 Room 直接报 `Primary keys cannot be nullable`，且 KSP 会改写 `app/schemas/*.json`（试过，已回滚）。
12. **Java 平台值 → Kotlin 非空返回也会自动插 `checkNotNull`**：`CurrentSubscription.cid()` 的 `return apiUrl`（来自 `KV.get(key, "")`）带 `Intrinsics.checkNotNull`；本例不可达（`KVCodec.decode` 对非空默认值恒不返回 null），登记为「低/既有」，不改写。

# 7.5 M3 实测登记（2026-10-05，`util` 4 个 + `sourcedata` 4 个叶子类）

**结论**：8 个类全量迁 Kotlin（7 笔 commit + 审查轮修复 `303c603`），`:app:assembleDebug` 绿、`:app:testDebugUnitTest` **523 用例 / 0 失败**（516 基线 + `StringUtilsTest` 6 例 + `absXml` 切分 1 例）；新增/删除的源文件全部 LF；`tokens` 卡口的"旧有新无"逐条核对后仍全是已知盲区（`get`/`put`→下标、`Map` 类型、`toLowerCase`/`length` 等 Java 专有形态），**不作为判据**（同 §7.3）。审查轮结论与账目见本节末尾。

**本里程碑现场核实出的规则（后续照查）**：

1. **`\f` 不是 Kotlin 字符串转义**：Java 的 `'\f'`（form feed, 0x0C）在 Kotlin 里编译不过（`Unsupported escape sequence`），要写 `'\u000C'`。Kotlin 支持的转义只有 `\t \b \n \r \' \" \\ \$ \uXXXX`。（`StringUtils.trimBlanks` 两处。）
2. **`import java.lang.reflect.Array` 会遮蔽 `kotlin.Array`**：`StringUtils.isEmpty(Object)` 要 `java.lang.reflect.Array.getLength(...)`，一旦 import 了它，同文件的 `Array<String?>` 参数类型就会解析错 → 用**全限定名**，不 import。
3. **`ThreadLocal<T>.get()` 在 Kotlin 侧是 `T?`**（不是平台类型）：`sortXStream.get()` 直接 `.fromXML(...)` 会报 `Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver`。加 `!!` 后告警消失、且不产生新告警（javap 确认：加 `!!` 只多一个 `checkNotNull`，旧实现在 null 时同样是 NPE）。**别照抄成 `.get()`**。
4. **Kotlin 自己的非空类型属性上的判空对比不会被告警折掉**（重要）：`MovieSort.SortData.filters` 是 `@JvmField var filters: ArrayList<...> = ArrayList()`（非空类型），但 XStream 不走字段初始化器 ⇒ 解析出来真的是 null，Java 侧那句 `if (sort.filters == null) sort.filters = ArrayList()` 是**载荷**的。迁 Kotlin 后该行报 `Condition is always 'false'` 警告，但 **javap -c 实证 `ifnonnull` + `new ArrayList()` 仍然生成**（读 `@JvmField` 只是 `getfield`，比较没有被折叠）。结论：这类"非空类型但运行时可能为 null"的兜底**保留**，只登记告警；判据 = `javap -c` 看到分支，不是"有没有告警"。
5. **`!!` 与智能转换的相互作用**：Kotlin 2.x 里 `if (!x!!.isEmpty())` 之后的 `x` 已被智能转换 ⇒ 里面再写 `x!!` 会报 `Unnecessary non-null assertion`。本次删了 3 处（`SourceHelper` 1 + `SourceResultParser` 2），另外 2 处 `xstream.get()!!` 是**必需**的（见第 3 条）。
6. **Java 的 `if (x != null)` 迁移三分类（逐个判，不一律保留）**：
   - 元素/字段类型在 Kotlin 侧就是可空（如 `urlinfo.beanList: MutableList<...>?`）⇒ 检查是真分支，保留；
   - 元素类型非空、且集合只由 Kotlin 代码填（`VodInfo.seriesFlags`、`Movie.Video.videoList`、`Movie.Video.UrlBean.UrlInfo.beanList`）⇒ 旧检查不可达，删掉并在 commit 里登记；
   - 非空**类型**但运行时真可能为 null（XStream/Gson 绕过初始化器，如 `SortData.filters`）⇒ **必须保留**（第 4 条）。
7. **`split` 只有一种写法等价 Java（本条在 M3 审查轮被推翻重写，§7.3 陷阱 1 与本文旧版说法都不完整）**：
   - Java `String.split(regex)` = `Pattern.compile(regex).split(input, 0)` —— **尾部空串被丢掉**。
   - Kotlin `split("字面量")` = 字面量切分且**保留**尾部空串 ✗。
   - Kotlin `split(Regex(p))` / `split(p.toRegex())` = 走 Kotlin 自己的 `findAll` 逐段实现，**limit=0 时同样保留尾部空串** ✗ —— **只有正整数 limit 时与 Java 逐段一致**（`split(Regex(p), 2)` ✓；实测 `"a$b$c"` / `"a$"` / `"ab"` 三种输入两侧完全一致）。
   - 要逐字等价 Java 的 limit=0：**只能用 Java 的 `Pattern.split`** → `RegexUtils.getPattern(p).split(s)`（返回 `Array<String>`，对应 Java 的 `String[]`）。
   - ⚠️ 别用 `split(Regex(p)).dropLastWhile { it.isEmpty() }` 代替：空输入时 Java 的 `Pattern.split("")` 返回 `[""]`，`dropLastWhile` 会把它削成 `[]` ✗。
   - 实测证据（M3 审查轮回溯）：`"第1集$url#"` 用 `split(Regex("#"))` 得 **2** 段（多一条空集 ⇒ 多一个空剧集条目），用 `RegexUtils.getPattern("#").split(...)` 得 **1** 段 = Java。`StringUtils.getBaseUrl` 的 `split("/")[0]` 同理：退化输入 `"/"` 下 Java 抛 AIOOBE、Kotlin 的 `Regex.split` 返回 `""`。
   - `replaceAll(regex, repl)` → `replace(Regex(regex), repl)` ✓（Kotlin 的 `Regex.replace` 与 Java 的 `replaceAll` 同样把 replacement 里的 `$1`/`\` 当引用解释）；`String.replace(a, b)` 两侧都是字面量 ✓ 不需要改。
   - M1 的两处 `AbsJson` 用的也是 limit=0 的 `split(Regex("\\$\\$\\$"))`（同样保留尾部空串），但紧随其后有 `if (playFlags[i].trim().isEmpty() || playUrls[i].trim().isEmpty()) continue` 守卫 ⇒ 结果与 Java 一致，**登记不改**；`app/src/main` 其它 Kotlin 的 `split('\n')`/`split(':')`/`split(":", limit = 2)` 属 M1 之前的既有 Kotlin 代码（非迁移引入），不在迁移范围。
8. **`TextUtils.isEmpty(...)` 原样保留，不要顺手换成 `isNullOrEmpty()`**：单测环境 `unitTests.isReturnDefaultValues=true` 让 `TextUtils.isEmpty` 恒返 false，既有 Java 逻辑的分支走向被这个 quirk 影响过（`SourceHelper.getFixUrl`/`isHomeSource`、`PushUrlParser`、`PushDetailResolver`）。保留调用点 = 迁移前后**连单测环境的行为**都一致。（`sourceKey == x` 取代 `sourceKey.equals(x)` 这类改写仅在本处成立，且差异只在单测环境不可达路径上。）
9. **`android.util.Log.e(tag, msg)` 的 msg 是 `@NonNull`**：`Log.e(tag, e.message)`（可空）编译不过 → 用 `"${e.message}"`（null 落成字符串 "null"）。Java 传 null 会在 native 层抛 `println needs a message`，属不可达分支；登记为可空收紧。
10. **Kotlin 没有包私有**：4 个 `sourcedata` 包私有类（`SourceHelper`/`PushUrlParser`/`PushDetailResolver`/`SourceResultParser`）放宽为 `public`（同 M1 先例）。**不可用 `internal`**：`internal` 类的成员会被加 `$module` 后缀做名字修饰，同包 Java 调用方（8 个 Loader）与 `SourceResultParserRoutingTest` 会全部编译失败。
11. **静态面按"调用形态"选载体**：Java 按**字段**读的（`SourceHelper.SPIDER_POOL`/`PREPARE_POOL`、`PushUrlParser.PUSH_*`、`PushUrl.inner.url`）→ `@JvmField`；按**方法**调的（`siteGet`/`absXml`/`isPushFallback`/`createPushPlayResult`…）→ `@JvmStatic`；`static final String` 常量 → `const val`（保留 Java 的 ConstantValue 内联语义）。`object` + `@JvmStatic` 同时服务 Java 与 Kotlin 调用点，**本次 0 处调用点改动**（除 MD5 的可空收敛，见第 12 条）。
12. **Java 平台类型 → Kotlin 可空返回会连带改 Kotlin 调用点**：`MD5.string2MD5/encode/encrypt` 迁 Kotlin 后必须声明 `String?`（`CacheRepository` 依赖"空串→null"的既有语义，见 §7.4-11），于是 4 个 Kotlin 调用点（`LocalConfigHelper`×2、`AppBootstrap`、`WatchProgressStore`）补 `!!` = 原来平台类型下编译器插的那次隐式断言；Java 调用点（`SpiderLoader`/`JsLoader`/`FileUtils`…）完全无感。**迁移前先 `search_content 'MD5\.'` 数一遍 Kotlin 调用点**，别等编译报错。
13. **`Matcher.group(int)` 在 Kotlin 侧被判为可空**：`Integer.parseInt(matcher.group(1))` 报 `Java type mismatch: inferred type is 'String?', but 'String' was expected`（警告级，不是错误）。两条出口（null/非数字）都落进同一个 `catch (ignored: Exception)` → 都返回 -1，行为等价，保留。
14. **保留的 4 条告警（已逐条判定为无害，别再"顺手修"）**：① `URLDecoder.decode(String)` deprecated（Java 侧同样 deprecated；非弃用的 `decode(String, Charset)` 要 API 33）；② OkHttp `Response.body` 非空 ⇒ `PushDetailResolver` 里 `body != null` 报 `Condition is always 'true'`（Java 那句本来就恒真）；③ 第 3 条之外的 `SortData.filters` 恒假告警（有意保留）；④ 第 13 条。
15. **新增叶子回归测试**：`app/src/test/.../util/StringUtilsTest.kt`（6 例）——锁重载解析（`CharSequence` / `Object` 两个重载都被 Java 调过）、`trim`/`trimBlanks` 的空白字符集（含全角空格 U+3000 与 `\u000C`）、`getBaseUrl` 的 regex 切分、以及 `listToString`/`arrayToString`/`trimBlanks` 三个**旧实现能返回 null** 的出口（防止以后被插上非空断言）。`EpisodeMatcher` 有既有 `EpisodeMatcherTest`（Java，45 处断言）复跑通过；`MD5` 的非空契约由 `PySourcePackTest`/`SourceHelperExtendTest` 间接覆盖。

16. **`String.trim()` 的空白集不同**：Java `String.trim()` 只去 `<= 0x20`（`\t\n\u000B\u000C\r` + 空格），Kotlin `String.trim()` 去的是 Unicode 空白（多出 U+00A0、U+2000–200A、U+2028/29、U+3000…）。逐字等价要写 `s.trim { it <= ' ' }`（有 String 重载、直接返回 String，**不要再加 `.toString()`** —— 否则报 `Redundant call of conversion method`）。本次两处：`SourceHelper.tryMinifyJson`、`SourceResultParser.json` 的空体判定。

**M3 审查轮（2026-10-05，结论 = 可收尾）**：逐类对账 Java 原文（`git show 847265a:`）+ 全量扫"迁移陷阱面"（`split`/`replace`/`trim`/`lowercase`/`getBytes`/`remove`/`===`/`containsKey`/`substring`/`Array` 遮蔽/未使用 import），并用 `tokens` 卡口 8/8 比对、缺失项逐条核对（全部落在已知盲区：访问器折成属性、类型推断、正则形态转换、`remove(i)`→`removeAt`）。记账（严重度 × 本次引入/既有/口味）：

- **中（本次引入，已修 `303c603`）**：`split("#")` 语义偏差 —— `SourceHelper.absXml`、`PushDetailResolver.list` 两处 limit=0 切分写成 `split("#")`/`split(Regex("#"))`，都保留尾部空串 ⇒ 播放地址以 `#` 结尾时会多出一条空剧集；`StringUtils.getBaseUrl` 的 `split(Regex("/"))[0]` 在退化输入 `"/"` 下也不再与 Java 一致（AIOOBE → `""`）。三处统一改 `RegexUtils.getPattern(p).split(s)`。
- **低（本次引入，已修）**：Kotlin `trim()` 空白集大于 Java（2 处，见第 16 条）；4 个未使用 import（`PushDetailResolver` 的 `Spider`/`SourceBean`、`SourceResultParser` 的 `JsonArray`/`JsonElement`）。
- **低（本次引入，登记接受，不改）**：`util/RegexUtils` 由 `class`（隐式 public 无参构造器）变 `object`（构造器消失、多 `INSTANCE` 字段）。静态方法经 `@JvmStatic` 全保留；仓库内零调用点、也不在 D9 Tier B 清单（Tier B 的 `MD5`/`StringUtils` 用的是 `class` + `companion`，构造器形态守恒）。要完全保守可改回 `class` + `companion`。
- **低（既有，登记不改）**：`AbsJson` 两处 limit=0 `split(Regex(...))` 保留尾部空串（被 `trim().isEmpty()` 守卫兜住，结果等价）；`PushUrlParser`（org.json）与 `PushDetailResolver`（OkGo/迅雷/App）在 `unitTests.isReturnDefaultValues = true` 下无法做 JVM 单测（既有结构限制，靠真机走查）。
- **无 阻断 / 高 级发现**，剩余全部为低 / 既有 / 口味 ⇒ 按收敛终止线判定**可收尾**。
- 复核结果：`:app:assembleDebug` 绿、`:app:testDebugUnitTest` **523 用例 / 0 失败**（新增 1 例覆盖 `#` 切分）、改动文件 LF、编译告警仍只有下面第 14 条的 4 条。新增回归测试做了**正/反向自测**：退回 `split(Regex("#"))` 时报 `expected:<1> but was:<2>`，改回 `Pattern.split` 后通过。
- 审查覆盖面的诚实标注：`PushUrlParser`/`PushDetailResolver` 的 JSON/网络分支没有自动化覆盖（见上）；`SourceHelper`/`SourceResultParser` 有既有单测 + 新增 1 例。

**未验证面（诚实标注）**：真机走查未做 —— 首页分类/推荐（`sortXml`+filters 兜底）、详情起播（push:// 解析、迅雷改写）、搜索面板、换源与 extend（`getFixUrl` 本地/网络/超时三出口）、推送直链带 `@Headers=`、`#` 结尾的播放地址（多集/多线路）。

# 7.6 M4a 实测登记（2026-10-05，LiveData→Flow 收口）

**结论**：新增 `sourcedata/SourceChannel<T>`（**Flow 主面** + **LiveData 过渡兼容面**），`SourceViewModel` 的 7 个通道、5 个 Loader 的输出面、`SourceResultParser`/`PushDetailResolver` 的参数全部换到该类型；3 个页面 VM 由 `observeAsFlow()` 改成 `channel.flow`；`sourcedata/LiveDataFlow.kt` 零调用点后删除。`:app:assembleDebug` + `:app:assembleRelease` 绿、`:app:testDebugUnitTest` **534 用例 / 0 失败**（523 + `SourceChannelTest` 6 + `SourceResultParserRoutingTest` 1 + `SourceViewModelWiringTest` 4）、新增/删除文件全部 LF、无语言迁移、Tier B 11 符号未变。四处偏差、4 项审查发现与语义差异见 `refactor-plan-20261005.md` §5 M4a。

**本里程碑现场核实出的规则（M7 照查；M4b 已按这些规则执行，实测登记见 §7.7）**：

1. **LiveData 在 JVM 单测里不能投递**（最重要）：单测环境 `Looper.getMainLooper()` 为 null，`MutableLiveData.setValue` 的主线程断言直接 `NPE: Cannot invoke "android.os.Looper.getThread()"`，`postValue` 也落进 stub 的 `Handler` 而静默 no-op。所以"兼容面投递走向"不能用真实 `MutableLiveData` 断言 —— 通道把 LiveData 投递抽成 `protected open fun dispatchToLiveData(value, sync)`，单测用替身记录（**先例**：`SourceResultParserRoutingTest` 里覆盖 `postValue` 的 `RecordingChannel` 就是同一手法）。想直接断言得引入 `androidx.arch.core:core-testing`，而 §6 禁新增依赖，故**登记为环境限制**（同 §7.5 第 14 条对 `PushUrlParser` 的处理）。
2. **`MutableSharedFlow(replay = 1, extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST)` 是"LiveData 等价面"的最小配方**：`replay = 1` 复刻 LiveData 粘性（新收集者立刻拿到最近一次的值 —— 页面 VM 因此不会空等已发生的结果）；`DROP_OLDEST` 让 `tryEmit` **永不失败也不挂起**，任意线程（OkGo 回调、`SPIDER_POOL`/`PREPARE_POOL`、main handler）都能安全投递。（要求 `replay > 0 || extraBufferCapacity > 0` 才允许非 SUSPEND 的溢出策略，本配方满足。）
3. **`postValue` 的合并语义是唯一已知语义差**：`MutableLiveData.postValue` 在同一主线程 tick 内多次调用只投**最后一次**（`mPendingData` + 一个 runnable），`MutableSharedFlow` 逐条投递。现有 5 条页面通道每次请求只投一次结果 ⇒ 等价；仅同通道**并发**多次投递时新实现会多投一条，由消费方守卫（`detailToken` 代次、`pending` 槽、`sourceKey` 比对）变成 no-op。**取舍理由**：合并的故障模式是丢结果（`PartitionLoader` 的 `pending` 永不回调 ⇒ 分区永停 Loading），多投一条的故障模式是可被守卫吸收的重复；故选"不合并"。要重新引入合并就把 `extraBufferCapacity` 降到 0（容量 = replay = 1，即 conflated）。
4. **通道对象必须同实例贯穿**：`SourceResultParser` 靠**身份**分投（`searchResult === result`、`result === detailResult`），所以 `SourceViewModel` 里每条通道只有一个实例、原样传给 Loader 与 Parser。Kotlin 泛型可空（`SourceChannel<AbsXml?>`）与 Java 侧实参（`SourceChannel<AbsXml>`）在 JVM 签名上同一，不影响 Java 调用方，也不影响 `===`。
5. **泛型参数取可空形态**：null 是合法载荷（取数失败/无结果都投 null），所以通道声明为 `SourceChannel<AbsXml?>`/`SourceChannel<JSONObject?>`；`postValue(null)` 必须作为一次投递送达（`SourceChannelTest.nullPayloadIsDeliveredNotSwallowed` 锁住）。
6. **`setValue` 的主线程约束原样保留**：Loader 里唯一的 `setValue` 在 `PlayLoader.postPlayResult`（`mainHandler.post` 内），兼容面仍按 LiveData 规则（`setValue` 主线程同步 / `postValue` 任意线程）。`flow` 面则无此约束 —— 收集者跑在自己的 dispatcher（页面 VM 全是 `viewModelScope` / `Main.immediate`），与旧 `observeAsFlow` 的"必须有主线程 dispatcher"断言相比是**放宽**（旧注释里"`Main.immediate` 是因为 `observeForever` 有主线程断言"已随 M4a 更新为"让回包仍在主线程处理"）。

7. **门面构造器在纯 JVM 单测里可实例化（本轮实测推翻旧注释）**：`new SourceViewModel()` 能跑通 —— 那 7 个 `MutableLiveData`/`MutableSharedFlow` 只是字段装配，**不碰 Looper**。历史注释（`SourceRuntimeStateTest`、`history/features.md:2648`）把"单测挂掉"归因于 `MutableLiveData` 初始化器是误记：真因是**消费方**的 `observeForever`（`LiveData.assertMainThread` → `Looper.getMainLooper()` 为 null 再解引用）。因此"能不能在单测里测某个 VM"看的是它的**消费面**：`DetailViewModel` 至今仍不可实例化，真因是 `init` 里 `viewModelScope.launch` → `Dispatchers.Main` 抛 `The main looper is not available`（M4a 换掉 `observeForever` 只消掉了旧阻塞中的一条）。判据改用 `Dispatchers.Main`/`observeForever` 是否在构造路径上，别再看 `MutableLiveData`。
8. **转换后要找出"转换本身新承重的不变量"并补测试**：M4a 的分投完全依赖 `SourceResultParser` 的**身份判定**（`result === detailResult` / `searchResult === result`），通道实例一旦被复制一份就静默走错分支（表现为"详情不解析 `push://`""搜索面板收不到结果"，编译与静态检查都看不出来）。补 `SourceViewModelWiringTest`（反射读私有字段：门面 → Parser/Resolver → 5 个 Loader 的通道同实例、7 通道互异、共享 `SourceRuntimeState` 的两张缓存），并做**正/反向自测**（把通道换成 `new SourceChannel<>()` ⇒ 必须报失败）。这类"接线型"不变量对反射读字段是可测的，不要因为字段是 private 就放弃。

**未验证面（诚实标注）**：真机走查未做 —— 首页分类/推荐、详情回包与换源 fallback（代次链路）、搜索面板、`action` 消息、起播取流与下一集预载（`playResult`/`preloadResult` 的 LiveData 兼容面）。

# 7.7 M4b 实测登记（2026-10-05，`sourcedata` 取数侧迁 Kotlin）

**结论**：7 个类迁 Kotlin（5 个 Loader + `SourceViewModel` + `SourceRuntimeState`）+ 审查轮修复 1 笔；`:app:assembleDebug` + `:app:assembleRelease` 绿、`:app:testDebugUnitTest` **536 用例 / 0 失败**（534 基线 + `SortLoaderActionVideoTest` 2 例）、改动/新增文件全 LF、Tier B 11 符号未变、`app/schemas` 未动。**既有 3 个测试文件（`SourceViewModelWiringTest`/`SourceRuntimeStateTest`/`PlayLoaderSeqTest`）一行未改且全绿** —— 它们是本里程碑的等价性主门（通道接线、access-order 缓存、双通道序号）。交付、偏差与未验证面见 `refactor-plan-20261005.md` §5 M4b。

**本里程碑现场核实出的规则（M5/M6 照查）**：

1. **可见性与静态面按 Kotlin 现实放宽/换载（只许放宽，逐条登记）**：5 个 Loader 由包私有 `final class` 变 `public class`；`ListLoader.HomeRecCallback` 由包私有嵌套接口变 public；`SourceRuntimeState.sortCache`/`extendCache` 由包私有 static 变 `public static final`（`@JvmField`）；`PlayLoader.isStaleResult` 由包私有 static 变 `public static final`（`companion object` + `@JvmStatic`，单测按 `PlayLoader.isStaleResult(...)` 调用）。**不可用 `internal`**（成员会被加 `$module` 后缀，同 §7.5 第 10 条）。`SourceViewModel` 的 7 个通道字段保持 `@JvmField`（Java 播放层走 `playResult.getLiveData()` 的字段读法），但由非 final 变 `public final`（Java 侧不能再整体替换通道实例 —— 零调用点，登记接受）。
2. **`SourceRuntimeState` 由 `final class` + 私有构造器 → `object`**：产物多 `INSTANCE` 与 `$stable`（原构造器本就 private，零调用点）；两个缓存经 `@JvmField` 保字段读法、`clearRuntimeCache()` 经 `@JvmStatic` 保静态调用，Java/Kotlin 调用点均无须改。`javap -p` 实证三个成员全在；**access-order 语义（上限 5 + `removeEldestEntry`）由 `SourceRuntimeStateTest` 3 例锁定**（含"清空不换新实例"）。
3. **`sortCache` 的 value 类型声明为可空（`MutableMap<String, AbsSortXml?>`）**：Java 的 `Map<String, AbsSortXml>` 允许 null 值（`cacheSort` 的入参本就是可能为 null 的 `sortXml`）；Kotlin 若写成非空 V，`sortCache[key] = sortXml` 需要 `!!` = **新增崩溃边界**（判据 §7.4 第 11 条）。泛型擦除与 Signature 都不变（javap 显示 `Map<String, AbsSortXml>`）。
4. **"Java 集合里可能为 null 的元素"在**入参**上同样按可空迁（§7.3 第 10 条的补充）**：`ListLoader.getHomeRecList(ids: ArrayList<String?>?)` —— Java 原文是 `ArrayList<String> ids`，收的是 `vod.id`（bean 里是 `String?`，XStream 可绕过初始化器），Java 会静默放进 null 元素；声明非空元素就得在 `ids.add(vod.id)` 处写 `!!`。**形参描述符 `java.util.ArrayList` 与 Java 逐字相同，只有元素可空性变（字节码不可见）**。同源还有 `SortLoader.hasActionVideo(videos: List<Movie.Video?>?)`（元素判空是真分支，写成非空元素会被编译器折掉 ⇒ 载荷含 null 元素时 NPE；已补 `SortLoaderActionVideoTest` 锁住）。
5. **保留非空的判据在本里程碑出现了两个方向的样本**：`DetailLoader.getDetail` 的 `urlid` 保留非空（Java 第一个动作就是 `urlid.startsWith("push://")`，null 即 NPE；调用点全传非空）；`SortLoader.cacheSort` 的 `sourceKey` 也声明非空（`MutableMap.set` 要求非空 key，且调用链上 `getSort` 已在 `sourceKey == null` 处早退）。其余引用型入参（`sourceKey`/`wd`/`playFlag`/`progressKey`/`subtitleKey`/`sortData`）一律可空 —— 旧实现在 null 上走的是正常分支。
6. **私有 static 助手 → 私有实例方法（只有 `isStaleResult` 保静态）**：Java 的 `private static`（`SortLoader` 的 5 个判定助手、`DetailLoader.createEmptyDetail`、`PlayLoader` 的 `shouldDirectPlay`/`normalizePlayerResult`/`mergeSiteHeaders`）落成 Kotlin 私有实例方法 —— 私有成员无外部契约，`javap` 差异只在此；Java 侧本来也有 `access$xxx` 合成访问器（Kotlin 用 lambda + `access$<方法名>`，命名不同、冲突面为零）。
7. **`assert`、SAM 与 lambda 提前退出的写法**：POST 分支保留 `assert(body != null)`（Kotlin `assert` 不吃 `$assertionsDisabled`，断言关闭时同为 no-op）；`SourceHelper.*_POOL.execute { … }`、`mainHandler.post { … }`、`RemoteTVBox.post(…, object : okhttp3.Callback { … })` 分属 Java 接口的 SAM 与 Kotlin 接口的 `object :`；提前退出写 `return@execute` / `return@post` / `return@Callable`。
8. **三处 `Charsets` 逐字等价改写（Kotlin 的 `String` 没有 `getBytes(String)` 重载，编译不过）**：`new String(bytes, "UTF-8")` → `String(bytes, Charsets.UTF_8)`；`s.getBytes("UTF-8")` → `s.toByteArray(Charsets.UTF_8)`；`"{}".getBytes()` → `"{}".toByteArray(Charset.defaultCharset())`（**Java 的 `getBytes()` 用平台默认字符集，别顺手写成 `toByteArray()` 的默认 UTF-8**）。`catch (UnsupportedEncodingException)` 原样保留（Java 侧也只是检查异常声明）。同 M3 `PushDetailResolver` 先例。
9. **`trim` 的空白集差异在取数侧也会踩到**：`PlayLoader.playFromApi` 的 `sourceBean.getPlayerUrl().trim()` 必须写 `trim { it <= ' ' }`（§7.5 第 16 条）。**逐类扫 `trim(` 是必需动作**（本处由审查轮抓到）。
10. **分支重排只允许"短路语义逐条等价"的形态**：Java 的 `if (withRec && sortXml != null && sortXml.list != null && …) … else if (sortXml != null && sortXml.classes != null) … else postSortFailure()`，因 `var` 被 lambda 捕获后不能智能转换，改成 `val sortXml: AbsSortXml? = if (…) … else null` + `if (sortXml != null) { … } else postSortFailure()`：**`else` 出口与 `classes == null` 的落点必须逐条对齐**（对错就是把"解析成功但没推荐"误判成失败或反之，属静默回归）。同类：`recVideoList` 提前取出替代 `sortXml.list.videoList` 的三层判空（顺序无副作用）。
11. **`tokens` 卡口在 Java→Kotlin 仍是参考打印（同 §7.3）**：7 个文件的"旧有新无"逐条核对后全是已知盲区 —— 访问器折成属性（`getKey`/`getExt`/`getHeader`/`getPlayerUrl`/`getMessage`）、`Map.Entry` 循环折成 `for ((k, v) in map)`（`Entry`/`entrySet`/`getKey`/`getValue` 消失）、`Runnable`/`Callable`/`AsyncCallback` 匿名类变 lambda/object 表达式、`GetRequest` 显式类型变推断、`Integer` → `Int?`、字符串拼接变模板（`STR:"…: "` → `STR:"…:$x"`）、`x.put(k, v)` → `x[k] = v`、`getBytes("UTF-8")` → `Charsets.UTF_8`。**旧文件已删除时必须显式给 `-Ref <迁移前那一笔 commit>`**（脚本缺省取 `HEAD`，而 `HEAD` 上 `.java` 已不存在 ⇒ 直接抛异常）。
12. **`isReturnDefaultValues = true` 下 `org.json` 同样是桩** ⇒ `PlayLoader.normalizePlayerResult`/`mergeSiteHeaders`、`DetailLoader.createPushDetail` 这些**依赖 JSONObject 的私有助手做不了 JVM 单测**（与 §7.5 对 `PushUrlParser`/`PushDetailResolver` 的登记同源）。能测的只有不碰 Android/JSON 的纯判定 —— 本里程碑的 `SortLoaderActionVideoTest`（反射调 `hasActionVideo`，元素判空守卫）即属此类，并做了正/反向自测（撤掉判空 ⇒ 用例 NPE 失败）。

13. **保留的编译告警（逐条判定无害，勿"顺手修"）**：① `URLDecoder.decode(String)` / `URLEncoder.encode(String)` deprecated（Java 侧同样 deprecated；非弃用重载要 API 33）；② OkHttp 4 的 `Response.body` 是非空类型 ⇒ `if (body != null) … else throw …` 报 `Condition is always 'true'`（8 处；Java 那句本来就恒真，**删掉 else 出口才是重构**）；③ `SortData.filterSelect` 的两条恒真/恒假（第 4 条 / §7.5 第 4 条，分支已 `javap -c` 实证保留）；④ `PlayLoader` 的 `Java type mismatch: inferred type is 'String?'`（`JSONObject(json)` 的可空入参 —— 与 Java 的 NPE/JSONException 落在同一个 `catch`，行为等价，同 §7.5 第 13 条）。

**未验证面（诚实标注）**：真机走查未做 —— 首页分类/推荐、详情回包与换源 fallback（`detailToken` 代次链路）、搜索面板、`action` 消息、起播取流与下一集预载（`playResult`/`preloadResult` 的 LiveData 兼容面）、t4 源 extend 的 GET/POST 双出口、推送直链与迅雷改写。

# 7.8 M5 实测登记（2026-10-05，`api/` 全包 8 个类）

**结论**：`api/` 全包迁 Kotlin（门面 `ApiConfig` + `ConfigParser`/`ConfigApplier`/`ConfigLoader`/`SpiderLoader`/`WarmQueue`/`ProxyEntry`/`DanmakuApi`），8 笔迁移 commit + 审查轮 1 笔；`:app:assembleDebug` + `:app:assembleRelease` 绿、`:app:testDebugUnitTest` **536 用例 / 0 失败**（与 M4b 基线持平）、`app/src/main/java` 159 j / 194 kt → **151 j / 202 kt**、`api/` 包 Java 清零。**冻结口径达成**：`javap -p -s` debug/release 两侧差异一致，逐成员配对后**零公开成员消失、零描述符变化**（未配对的 55 条=51 个 `DanmakuApi` 私有助手迁入 Companion + 3 个 Java lambda 合成改名 + `$assertionsDisabled`）。交付、偏差与未验证面见 `refactor-plan-20261005.md` §5 M5。

**本里程碑现场核实出的规则（M6/M6b 照查；M7/M9/M10 也适用）**：

1. **迁移顺序受"可见性闭包"约束**：Kotlin 没有包私有类，包私有 Java 类一旦被 public Kotlin 类的**签名**引用就编译不过（`'public' function exposes its 'public/*package*/' parameter type`）。例：`WarmQueue(ApiConfig, SpiderLoader)` 里的 `SpiderLoader` 必须先迁。**动作**：动手前先画「参数类型 → 是否包私有」的依赖图，把包私有 Java 类排在被引用者之前；纯内部使用的类（`ConfigParser`/`ConfigApplier`）不受此限。
2. **`object` / `class` + `companion` 的选择**：`@JvmStatic` **只能**写在 `companion object` 或 `object` 成员上（写在普通类体里是编译错误 `@JvmStatic annotation is not applicable`）；纯静态工具类用 `object`（`ConfigParser`/`ConfigApplier`），有实例语义或需要保住隐式公开构造器的用 `class` + `companion`（`ApiConfig` 私有构造器、`DanmakuApi` 公开构造器、`SpiderLoader`）。
3. **冻结 Java 名时，Kotlin 属性与显式 `getX()` 函数不能共存**（同一个 JVM 签名）。若既有 Kotlin 调用点两种写法都有，只能二选一：本里程碑按**多数派**选形态 —— 已用属性语法的 4 个成员（`channelGroupList`/`parseBeanList`/`liveSettingGroupList`/`liveConnectTimeoutSeconds`）落成 Kotlin 属性，其余（含 `getHomeSourceBean`：显式 4 处 vs 属性 2 处）落成显式函数，少数派调用点改 1–2 行。**清点命令**：`Select-String 'ApiConfig\.get\(\)\.[A-Za-z_]+' *.kt | Group-Object`。
4. **只有 setter 的 Java 字段**（`SpiderLoader.liveSpider`/`jarCache`）：`private var x` + 显式 `fun setX(v)` **不冲突**（私有属性不生成访问器），这样能保住"零新增成员"；反之写成公开属性会平白多一个 getter。getter-only 的字段用 `var x; private set`（Kotlin 会省掉未用的私有 setter）。
5. **Kotlin 接口默认方法是真 JVM default**（Kotlin 2.4 默认 `-jvm-default=enable`），但会**额外**生成 `X$DefaultImpls` 与 `access$m$jd` 桥 ⇒ `javap` 会报"新增类"，登记即可；Java 实现方（`PlaybackFetch` 只覆写 `onFound`/`onNotFound` 中的部分）不受影响。判据用 `javap -p` 看 `public default void m();`。
6. **接口参数的可空性必须与既有覆写逐字一致**：Java 接口是平台类型，Kotlin 覆写既能写 `String` 也能写 `String?`；一旦接口迁 Kotlin，两种覆写不能共存。**只能放宽为可空**（收紧会把"传 null"变成新崩溃边界），故改声明显式 `String` 的那几处（本次 `LivePlayActivity`/`LivePlayViewModel` 4 行），并连带处理 `host.toast(msg)` → `msg ?: ""`。**动作**：迁接口前先 `grep 'override fun <方法名>\('` 清点两侧写法。
7. **`const val` 在 companion 里仍落在外层类的静态字段**（`WARM_ITEM_TIMEOUT_MS` 经 `javap` 实证两字节码一致）；但**非编译期常量**（`TimeUnit.SECONDS.toMillis(20)`）只能 `val`，字段会移到 `X$Companion`（私有，登记）。
8. **`new String(byte[])` 是 M4b 规则 8 的镜像坑**：Kotlin 把 `String(bytes)` 编译成 **UTF-8** 构造器，而 Java 的 `new String(byte[])`/`String.getBytes()` 用**平台默认字符集** ⇒ 逐字等价必须写 `String(bytes, Charset.defaultCharset())`。本次 `ApiConfig.FindResult` 两处由审查轮抓出并修复。
9. **Java 字段初始化早于构造器体，Kotlin 按声明顺序与 `init` 交错**：带初值的字段必须声明在 `init {}` **之前**，否则构造期间会出现"字段是 null 而 Java 是空集合"的空窗（`ApiConfig.liveSettingGroupList` 由审查轮抓出并前移）。**动作**：迁类时把 Java 的字段声明位置逐个对照（Java 里"声明在方法之间"的字段，初始化仍然最早）。
10. **`javap` 逐成员配对脚本口径**（本次自写）：去掉 `public/private/protected/final/static/abstract/…` 修饰符与 `throws …`，**跨类全局**配对 `-`/`+` 行。这样能把"私有方法搬进 `Companion`（同名同描述符）"与"真丢失"分开 —— 前者在逐类比对里会假报为删除（本次 51 条）。
11. **`!!` 的判据仍是"复刻 Java 平台类型的隐式解引用"**（§7.7 第 5 条）：本次 29 处逐条核对后全部落在同一条路径上；特别注意 `!TextUtils.isEmpty(x)` 守卫后的解引用 —— 单测桩（`isReturnDefaultValues=true`）下 Java 也在同一行 NPE，故 `!!` 连单测环境的行为都一致。
12. **私有方法的 `throws` 随 Kotlin 消失是预期项**（`Exceptions` 属性不属描述符，`javap -s` 看不出；`javap -p` 会少一段）。有 Java 调用点的 public 方法若要保留受检异常声明，必须 `@Throws`。
13. **保留的编译告警（逐条判定无害，勿"顺手修"）**：`response.body == null` 恒假 ×9（`DanmakuApi` 8 + `SpiderLoader` 1，OkHttp 4 的 `Response.body` 非空；`ConfigLoader` 1 处同源）—— Java 那句本来就恒真，与 §7.7 第 13 条同源。本里程碑**无**未使用 import / 冗余 `!!` 告警。

**登记的产物差异（debug 与 release 一致）**：`class` → `public final class`；包私有类/成员 → `public`；`private static` 助手 → `private` 实例/伴生方法（`DanmakuApi` 的 51 个搬进 `DanmakuApi$Companion`）；新增 `Companion`/`$stable`/`access$*` 桥；`ApiConfig` 私有构造器旁新增 `DefaultConstructorMarker` 合成构造器、`instance` 私有静态字段移入 `Companion`；3 个私有方法丢 `throws`；`ConfigParser$ConfigUrl` 与 `DanmakuApi$EpisodeList|EpisodeMatch` 字段/构造器放宽为 `public final`（类私有/包内）；`SearchCallback` 新增 `DefaultImpls` 与 `access$onNotFound$jd`。

**调用点改写清单（Kotlin 源，字节码零影响；共 12 行，其中 1 行含新增 `!!`）**：`sourcedata/ListLoader.kt`・`SourceHelper.kt` 的 `homeSourceBean` → `getHomeSourceBean()`（2 行，属性语法→显式 getter，字节码同）；`ui/page/AppBootstrap.kt` 的 `getSpider()` → `getSpider()!!`（1 行，复刻 Java 平台解引用）；`ui/activity/LivePlayActivity.kt`・`LivePlayViewModel.kt` 的 `error`/`notice` 覆写 `String` → `String?`（4 行）+ `host.toast(msg)` → `msg ?: ""`（2 行，接口可空性，见规则 6）；`api/ConfigLoader.kt` 的 `activity: Activity` → `Activity?`（1 行，`AppBootstrap` 传 `null`）；`api/ConfigParser.kt` 的 `parseLiveChannelName` 形参 `ArrayList<String?>` → `ArrayList<String>` 并删不可达元素判空（2 行，§7.7 规则 6 口径 —— Java 形参本就声明 `ArrayList<String>`，唯一生产者是 `Pattern.split`）。

**审查轮（2026-10-05，结论 = 可收尾）**：3 个只读子代理独立逐方法复核（`ApiConfig` / `DanmakuApi`+`SpiderLoader` / 其余 5 类 + 全包陷阱扫描），**均判语义等价（high confidence）、0 条阻断/高/中**；本机复跑 `tokens` 8/8 + `lf` + `javap` 双变体 + 逐成员配对 + `--no-build-cache`/`--rerun-tasks` 全量单测。审查轮修复 4 条低级项（规则 8/9 + `parseLiveConfigContent(String, File)` 恢复"先关流再解析" + 删 3 个未使用 import）；登记不改的项：`ConfigParser.parseLiveChannelName` 的不可达判空删除（恢复可空元素需在 `loadLives` 强转或复制列表，更差）、29 处 `!!`、`LivePlayActivity`/`LivePlayViewModel` 的"null → 空 toast/空串"放宽（生产不可达）、`SpiderLoader.getCSP` 系列非空返回（加载器失败出口全返回 `SpiderNull`）。

**未验证面（诚实标注）**：真机走查未做 —— 换源成功/失败（多仓分流、`;pk;` 密钥、`clan://`/`file://`/局域网地址）、本地源不可读/已删除两条报错路径、快照回落与 TTL、广告拦截与 rules 不真空、doh、解析器列表与「超级解析」、直播配置三条入口（JSON/文本/多仓）、预热队列不回归、jar 下载/重试/`img+`/`jarCache`、js/py 源加载、/proxy 四级兜底路由、弹幕搜索（内置 API + 占位符/自定义 API + retry 代次）、DLNA/局域网服务地址。

# 7.9 M6 实测登记（2026-10-05，`subtitle/` 22 个类）

**结论**：`subtitle/` 22 个 Java 全量迁 Kotlin（1 笔 commit `dabd7ff`），`:app:assembleDebug` 绿、`:app:testDebugUnitTest` **536 用例 / 0 失败 / 0 错误 / 0 跳过**（与 M4b/M5 基线持平，纯语言迁移未新增用例）、`lf` 卡口 22/22、`tokens` 卡口抽查 5 个代表文件（`Time`/`SubtitleLoader`/`FormatSTL`/`SimpleSubtitleView`/`TimedTextObject`，缺失项全落在 §7.3 的已知盲区）。M6 计划里 **`util/` 45 个 Java（含 D9 Tier B 的 `LOG`/`KV`/`FileUtils`/`OkGoHelper`/`AppContextHolder`/`SSLSocketFactoryCompat`）仍未开始**，续做时按本节规则 + §7.8 规则 1（可见性闭包）排序。

**本里程碑现场核实出的规则（M6 续做 / M6b / M7 照查）**：

1. **`AppGraph` 的 repository 是 Kotlin 属性，不是 getter（本次构建失败的头号原因）**：`AppGraph` 是 `object`，三个 repository 声明为 `@JvmStatic val`。Java 侧写 `AppGraph.getCacheRepository()`（`@JvmStatic` 生成静态 getter），**Kotlin 侧必须写 `AppGraph.cacheRepository`**；写 `getCacheRepository()` 会 `Unresolved reference`。同类还有 `AppGraph.historyRepository` / `collectRepository`（既有 Kotlin 调用点已是属性写法）。
2. **禁止 `import java.util.Iterator` / `java.util.Collection`**：① 触发 deprecation 警告；② `java.util.Iterator<T>` 无变型，`MutableIterator<T>` 赋不进去 ⇒ 报 `Initializer type mismatch: expected 'Iterator<Subtitle>', actual 'MutableIterator<Subtitle>'`。要么用 Kotlin 默认导入的 `Iterator`/`Collection`，要么干脆省掉显式类型标注（`val c = tto.captions!!.values`）。**子代理产出的 Java 风格显式类型标注要在复核时清掉。**
3. **`TimedTextFileFormat` 是接口**（Java `Object toFile(TimedTextObject)` ⇒ Kotlin `Any?`）。四个 `Format*.toFile` 都有 `if (!tto.built) return null` ⇒ Kotlin 返回类型必须可空（`Array<String>?` / `ByteArray?`），连带 `TimedTextObject.toSRT/toASS/toSTL/toSCC/toTTML` 一起放宽为可空 —— 先 `grep` 确认全库零调用点再放宽（本次确认零调用点）。
4. **自定义 View 的三构造器不能合并成 `@JvmOverloads`**：`SimpleSubtitleView` 的背景描边 `TextView` 要逐个镜像 Java 的 `TextView(context)` / `TextView(context, attrs)` / `TextView(context, attrs, defStyleAttr)`；用 `@JvmOverloads` + 单构造器会改变描边层的默认样式解析，属**观感变化**（本项目红线）。同时 `@JvmField` 保住 `isInternal`/`hasInternal` 两个被 Java 按字段读写的公开字段。
5. **父类构造期回调的守卫用 `lateinit` + `isInitialized`**：`onTextChanged` 可能被 `TextView` 构造器触发，此时 `backGroundText` 还是 null（Java 用 `!= null` 守卫）。Kotlin 写 `private lateinit var backGroundText: TextView` + `if (this::backGroundText.isInitialized)`，行为等价；会产生一条 `'lateinit' is unnecessary: definitely initialized in constructors` 警告，**判定无害**。
6. **Java 里没写 `@Override` 的接口实现，迁 Kotlin 必须补 `override`**：`SimpleSubtitleView.setPlaySubtitleCacheKey` 在 Java 中未标注，Kotlin 报 `hides member of supertype 'SubtitleEngine' and needs an 'override' modifier`。
7. **冗余 `!!` 只删被点名的那些**：编译器报 `Unnecessary non-null assertion` 的才删；同一段里**前一处 `!!` 建立的智能转换可能正是后一处的依赖**（`FormatSTL` 的 `currentCaption`：`if` 分支与 `else` 分支各自需要一次 `!!`，分支内的后续行才不需要）。
8. **静态持有类 → `object` + `@JvmStatic`（沿用 M3 `RegexUtils` 先例）**：`SubtitleFinder`、`SubtitleLoader`、`AppTaskExecutor` 都是「私有构造器（`SubtitleLoader`/`SubtitleFinder` 的构造器还 `throw AssertionError`）+ 全静态成员」形态 ⇒ 落成 `object`，静态成员 `@JvmStatic`（`SubtitleLoader` 另有一个**实例**方法 `loadSubtitle(String)`，在 `object` 里不加 `@JvmStatic` 即保住实例形态）。登记产物差异：新增 `INSTANCE`、丢失不可达的抛异常构造器。
9. **接口的平台类型入参一律放宽为可空**（同 §7.8 规则 6）：`SubtitleEngine` 的 `path`/`milliseconds`/`cacheKey`/`listener`/`mediaPlayer` 全部 `?`，`getPlaySubtitleCacheKey(): String?`；实现侧该 `!!` 的地方 `!!`（如 `DefaultSubtitleEngine` 的 `val p = path!!`，复刻 `TextUtils.isEmpty` 在单测桩下恒 false 的既有 quirk）。
10. **`List<Subtitle>` 的模型字段用 `@JvmField var ... : MutableList<Subtitle>? = null`**：`Subtitle.lines` 被 Java/Kotlin 双方按字段读写（`previous.lines = ArrayList()`），`@JvmField` + 可空保住字段形态与 Java 的「未赋值即 null」语义；`Subtitle.start`/`end`/`style` 同理可空，消费侧 `!!` 复刻 Java 的隐式解引用（`SubtitleFinder`/`buildSubtitles`）。
11. **本批次其它登记项**：`TimedTextObject.cleanUnusedStyles` 引入局部 `val style = current.style`（语义等价，`current` 的 token 计数因此下降）；`SimpleSubtitleView.setSubtitleDelay` 的形参由 `mseconds` 改名 `milliseconds` 以对齐 Kotlin 接口（形参名不进描述符）；`SubtitleLoader` 的 `loadAndParse`/`openBomAwareStream` 形参沿用 Java 的 `is`（Kotlin 用反引号 `` `is` ``）；`String.toLowerCase()` → `lowercase()`（与 §7.5 先例一致，仅土耳其语等少数 locale 有差异，本项目无实际影响）。

**保留的编译告警（逐条判定无害，勿"顺手修"）**：`URLDecoder.decode(String)` deprecated ×2、commons-io `ReaderInputStream(Reader, Charset)` deprecated ×2、`Html.fromHtml(String)` deprecated ×2（三者在 Java 侧同样 deprecated）、第 5 条的 `lateinit` 提示。

**M6 subtitle 审查轮（2026-10-05，结论 = 可收尾）**：4 个只读子代理独立逐方法逐语句对账（分工：model/exception/runtime/根小类 13 个；`SubtitleLoader`+`DefaultSubtitleEngine`+`SimpleSubtitleView`；`TimedTextFileFormat`+`FormatSRT`+`FormatSCC`；`FormatASS`+`FormatTTML`+`FormatSTL`），**0 条 阻断/高/中**。本机机械卡口：`lf` 22/22、`methods` 20/22、`tokens` 22/22、`prune_imports` 22/22 为 0、`--rerun-tasks` 全量构建 + 单测 536/0/0/0。报告落盘 `skill/review/review-20261005-m6-subtitle.md`。记账：**低（本次引入，不可达，登记不改）** 2 条 —— `DefaultSubtitleEngine.setSubtitleDelay` 的 `milliseconds!!` 落点后移（Java 在 `Integer == 0` 解引用，Kotlin 在 `!!`；唯一调用方传 `int`）、`SubtitleLoader` 的 `Charset.forName` 异常类型与 `UnsupportedEncodingException` 不同（都被外层 `catch (Exception)` 吞）；**低（本次引入，口味差异）** 2 条 —— 可空化放宽、`lowercase()` 的 locale；**低（既有，登记不改）** 6 条 —— 两处 DCL 缺 volatile/二次判空、`SubtitleFinder` 死分支、`Style` 的 `magenta`/`cyan` 尾随空格、`FormatASS.parseStyleForASS` 的 `var warnings = warnings` 导致警告被丢弃（Java 同样）、`TimedTextObject` 的 `style.iD!!`。**卡口误报（写清楚，别当违规）**：① `methods` 会把 Java 匿名类（`new Executor(){…}`/`new Handler.Callback(){…}`）的方法名（`execute`/`handleMessage`）报成"新文件中不存在"，Kotlin 落成 SAM/lambda 后源文件无声明形态；② `tokens` 对 `when` 分支的 `92 ->` / `10 ->` 这类**数字后紧跟 `->`** 的写法判成"新侧 0"（实证字面量存在），且多行字符串/注释会被折成 `STR:"…` 碎片条目。**附录 A（既有维护负担，非本次引入）**：文件 >500 行 4 个（`FormatSCC.kt` 990 / `FormatSTL.kt` 633 / `FormatASS.kt` 574 / `FormatTTML.kt` 547，Java 侧同样 >500）；方法 >100 行 12 个（估算 ±5 行，含 `FormatSCC.parseFile` 353、`Time.getTime` 143）；同文件重复模板 1 组（`SubtitleLoader` 的三个 `loadFromXxxAsync`）。

**未验证面（诚实标注）**：真机走查未做 —— 外挂字幕加载（本地/`data:`/远端三条入口）、BOM 与编码探测、`content-disposition` 文件名解析、srt/ass/stl/ttml/scc 五种格式解析与 `#` 结尾地址、歌词模式（`lines` 合并与 `lyricCurrent` 高亮）、字幕延时、字幕缓存读写与清理、字幕刷新循环不回归。

# 7.10 M6 util 批次实测登记（2026-10-05，`util/` 45 个类，含 6 个 D9 Tier B）

**结论**：`util/` 45 个 Java 全量迁 Kotlin（8348 行），原 `.java` 全删、`util` 包 Java 清零。`:app:assembleDebug` 绿、`:app:testDebugUnitTest` **536 用例 / 0 失败 / 0 错误 / 0 跳过（70 suite）**（与 M5/M6-subtitle 基线持平，纯语言迁移无新增用例）、**12 个 util 相关既有测试文件一行未改**、`methods` 卡口 **45/45 全部命中**、`lf` 45/45、`prune_imports` 清 4 个未使用 import。交付、记账与未验证面见 `skill/review/review-20261005-m6-util.md`。**修掉 1 条阻断级新回归（见规则 6）。**

**本里程碑现场核实出的规则（M6b / M7 / M9 / M10 照查）**：

1. **`AppContextHolder.context()` 的可空性会传导一整批文件**：Java 里它是平台类型，Kotlin 声明 `Context?` 后，`LOG`/`EpgUtil`/`ImgUtil`/`FileUtils`/`OkGoHelper`/`Jianpian`/`Thunder`/`UA` 等 20+ 处 `AppContextHolder.context().xxx` 全部要补 `!!`（复刻 Java 隐式解引用）。**动作**：迁一个"返回可空 Context 的门面"时，先 grep 它的全部调用点，别等编译报错逐个补。
2. **`String.equalsIgnoreCase` 在 Kotlin 不存在** ⇒ `equals(x, ignoreCase = true)`。`LocalIPAddress` 2 处、`RemoteTVBox` 4 处、`TxtSubscribe` 1 处、`OkProxySelector` 1 处。
3. **`HashMap.keySet()` 在 Kotlin 侧不可用**：`java.util.HashMap` 被映射成 Kotlin 的 `MutableMap`，只暴露 `keys` 属性 ⇒ 写 `checked.keys`。报错形态很迷惑（同时报 `Unresolved reference 'keySet'` 与 `Method 'iterator()' is ambiguous`，并列出 `Map.iterator()` 等一堆候选）。
4. **Kotlin 没有 `Int + String`**（`None of the following candidates is applicable`，候选只列 `plus(Byte/Short/Int/Long/Float/Double)`）⇒ 必须 `x.toString() + "…"`。`LocalIPAddress.intToIp`、`TrackMemory.videoFingerprint`、`FileUtils.formatCacheSize`（`Math.max(1L, …).toString() + "KB"`）。
5. **`VideoView<P>.setPlayerFactory(PlayerFactory<P>)` 的泛型捕获**：`VideoView<*>` 的捕获类型喂不进 `PlayerFactory<CapturedType(*)`> ⇒ 只能 `@Suppress("UNCHECKED_CAST") (videoView as VideoView<ExoPlayer>).setPlayerFactory(playerFactory)`。**注意是 app 侧的 `com.github.tvbox.osc.player.ExoPlayer`**（`ExoMediaPlayerFactory extends PlayerFactory<ExoPlayer>`），不是 doikki 的 `xyz.doikki.videoplayer.exo.ExoMediaPlayer` —— 用错会报 `actual type is 'ExoMediaPlayerFactory!', but 'PlayerFactory<ExoMediaPlayer!>!' was expected`。参数类型仍保持 `VideoView<*>?`（Java 调用方传的是 raw `MyVideoView extends VideoView`，Kotlin 视作 `VideoView<*>`）。
6. **⚠️ `KV.get(key, defaultValue)` 不能带 `T : Any` 上界（本批次唯一阻断级回归的根因）**：`fun <T : Any> get(key, defaultValue: T?): T` 的 `as T` 会被编译器插入 `Intrinsics.checkNotNull` ⇒ `KV.get(key, null)`（Java 原文 `RemoteTVBox.getAvalible()` 就这么写）从"返回 null"变成**抛 NPE**，打断 `getExistPlayerTypes()`→`getPlayersExistInfo()`→`getAvalible()` 这条链上的"单击播放器按钮 / 打开播放器参数面板 / 打开投屏面板 / 投屏播放"四条常用路径。**正解**：去掉上界写 `fun <T> get(key: String, defaultValue: T?): T` —— 上界变 `Any?` 后 unchecked cast 不再插空检查，非空默认值的调用点仍推断出非空 `T`（既有 Kotlin 调用点零改动），JVM 描述符 `<T:Ljava/lang/Object;>(Ljava/lang/String;TT;)TT;` 与 Java 逐字相同。**判据**：这类"返回非空但要能返回 null"的桥接重载，一律 `javap -p -c` 看 `getInternal` 之后有没有 `checkNotNull`。
7. **`AES.CBC/ECB` 诚实地声明 `String?` 会传导到 `ApiConfig.FindResult`**：Java 里它返回平台 `String`、解密失败时 `json = null` 并 `return null`。Kotlin 落成 `FindResult(...): String?`（内部用局部 `out`），调用点 `ConfigLoader.kt` 补 `!!` —— 因为下游 `ConfigParser.clanContentFix`/`fixContentPath` 的 `content` 是非空形参，Java 在传 null 时也走 `checkNotNullParameter` NPE → 外层 `catch (th: Throwable)` → `error`，`!!` 复刻的就是这条路径。
8. **`String` 的 `trim`/`split`/字符集在 45 个文件里逐处核对是必需动作**（本批次结果：`.trim()` 0 残留、`replaceAll(` 0、`charAt(` 0、`toLowerCase/UpperCase(` 0、`equalsIgnoreCase` 0、`new String(`/`.getBytes()` 0 无 charset、`TextUtils.isEmpty` 计数逐文件与 Java 完全一致）。Java 无 limit 的 `split(regex)` 丢尾部空串 ⇒ `RegexUtils.getPattern(p).split(s)`；Java `split(x, -1)` ⇒ Kotlin `split("字面量")`（Kotlin limit=0 保留尾部空串）。**同一文件里两种方向会并存**（`M3u8` 11 处里 9 处走 `RegexUtils`、2 处走 `split("\n")`），必须逐处判。
9. **依赖版本坑（okhttp 5.5.0）**：`HttpUrl.get(s)` / `OkHttpClient.dispatcher()` / `Dispatcher.setMaxRequestsPerHost(n)` 都是 **ERROR 级**弃用 ⇒ `s.toHttpUrl()`（`import okhttp3.HttpUrl.Companion.toHttpUrl`）/ `.dispatcher` / `.dispatcher.maxRequestsPerHost = 10`。`Headers.of(Map)` 同源（`JsonParallel` 改用 `Headers.Builder().apply{ forEach{add(k,v)} }.build()`）。另：`Response.body` 在 Kotlin 侧是**非空属性**，写 `response.body`（不带括号）。
10. **依赖版本坑（coil 3.6.3）**：`OkHttpNetworkFetcher.factory(...)` 在 Kotlin 侧是**顶层函数** `coil3.network.okhttp.OkHttpNetworkFetcherFactory`（`@JvmName("factory")`），且**只接受函数类型** `() -> Call.Factory` —— 写成 `Function0<Call.Factory>` 会报 `None of the following candidates is applicable`。`coil3.Image_androidKt.asDrawable` ⇒ `import coil3.asDrawable` + `image.asDrawable(resources)`；`ImageLoader.getMemoryCache()` ⇒ `.memoryCache`（可空，`!!` 复刻 Java 隐式解引用）。
11. **`SSLSocketFactoryCompat` 的 `static {}` 落 companion `init {}` 会被编入外层类 `<clinit>`**（`javap` 实证），与 Java `static {}` 执行时机一致 —— **静态初始化块放 companion 的 `init` 是安全的**。
12. **`@Synchronized` 与 Java `static synchronized` 的锁对象不同**（INSTANCE vs `Class`），但本批次 5 个同步方法彼此仍共用同一把锁；**前提是全仓没有外部 `synchronized(OkGoHelper.class)`**（grep 确认）。迁"静态同步方法"前先 grep 有没有外部按 Class 加锁。
13. **`@JvmField` + `@Volatile` 可以并用**（`OkGoHelper.dnsOverHttps`/`dnsHttpsList`/`myHosts` 实证编译通过且 `javap` 显示 `public static volatile`）。
14. **`static ArrayList<Integer> hisNumArray = {30,50,100}` 这类"基本类型装箱数组"**：Kotlin `arrayOf(30, 50, 100)` 编译出 `java.lang.Integer[]`（`javap` 已证），与 Java `Integer[]` 一致 —— 不要画蛇添足写 `intArrayOf`。
15. **`assert` 一律保留**（`Proxy` 3 处、`OkGoHelper.CustomDns` 1 处）；但 `assert x != null` 在形参已非空时会报"恒真"告警，登记即可。反之 Java 里"**判空出现在解引用之后**"的死判空（`AES.rightPadding` 的 `if (key != null && …)`）可以删，登记。
16. **`java.lang.String.valueOf` / `java.lang.Long.parseLong` / `java.lang.Double.parseDouble` 必须写全限定名**（Kotlin 的 `String`/`Long`/`Double` 是映射类型，没有这些静态方法）。
17. **`PlayerHelper.runExternalPlayer(6 参)` 的无限递归是既有缺陷，原样保留**（迁移 ≠ 重构，全仓 0 调用方）。同类：`PlayerHelper.getPlayerExist` 与两个 `runExternalPlayer` 的返回类型由 `java.lang.Boolean` 变原生 `boolean`（描述符变化、无调用方受影响，登记）。
18. **`Thunder.ParseTask.run()` 的 `switch` fallthrough**（Java `case 2:` 无 break ⇒ 落到 `case 3: break outerLoop`）：Kotlin `when` 没有 fallthrough，但 `when` 对 `break` 是透明的 ⇒ 在 `2 -> {}` 分支的 try/catch **之后**补一句 `break@outerLoop` 即可精确复刻（try 内命中时另有一处 `break@outerLoop`）。**迁移前逐 `switch` 判有没有漏 break。**
19. **`object` 化的纯静态类里，Java 的 `private static` 助手落成私有实例方法即可**（`private` 无外部契约）；但**包私有**成员要逐个 grep：有外部调用方就放宽为 `public`（`DefaultConfig.pickByCategories`、`PlayerHelper.isExoDecodeApplied`、`Proxy.resolveRedirectLocation`/`joinUrl`、`BootGuard.*`、`OkGoHelper.indexOfDohUrl` 都是同包测试在用），没有就收紧为 `private` 并登记（`OkGoHelper` 的 5 个、`Thunder` 的 6 个、`KVDecoder.parse`/`assignable`）。
20. **既有 Java 测试用"实例引用调静态方法"的写法会挡住 `@JvmStatic`**：`KVDecoderTest.java` 的 `decoder.coerceNumber(int.class, …)` 要求 `coerceNumber` 在 Kotlin 侧是**实例方法**（`@JvmStatic` 只生成外层类静态方法 + companion 实例方法，不会给外层类生成实例方法）⇒ 落成 public 实例方法，Java 测试一行不改仍可编译。**动作**：迁类前先 grep 测试里的 `<实例>.<方法>(` 形态。
21. **`@Throws` 只在本类有 Java 调用方时需要**（`UnicodeReader` 的 5 个构造器/`close`/`init`/`read`、`Proxy.itv`/`removeBOMFromM3U8`/`getRedirectedUrl`/`getM3U8Content`、`Utils.fixJsonVodHeader`/`jsonParse`、`SSLSocketFactoryCompat` 的 `createSocket` 系列）；纯 Kotlin 调用方的受检异常声明可以丢（登记）。

**调用点改写清单（非 util 文件，共 4 处）**：`api/ApiConfig.kt` 的 `FindResult(...)` 返回类型改 `String?` + 内部局部 `out`（1 处）；`api/ConfigLoader.kt:393` 的 `ApiConfig.FindResult(...)!!`（1 处，复刻 Java 在下游非空形参处的 NPE→外层 catch→error）；`bean/ParseBean.kt:18` 的 `DefaultConfig.checkReplaceProxy(urlValue!!)`（1 处，Java 同样 NPE）；`util/SubtitleHelper.kt` 的 `getTextSize`/`getSubtitleTextAutoSize` 形参改 `Activity?` + 内部 `ScreenUtils.getSqrt(activity!!)`（适配既有 Kotlin 调用方传 `findActivityOrNull()`）。

**审查轮（2026-10-05，结论 = 修 1 条阻断后可收尾）**：5 个只读子代理独立逐方法逐语句对账（分工：Tier B+kv 栈 8 个；`FileUtils`+`OkGoHelper`+`M3u8`；`Proxy`+thunder+live+parser 7 个；播放/图片/网络/配置 12 个；叶子小类+net/SSL 15 个），**0 条高 / 0 条中（除已修的阻断项）**；本机复跑 `lf` 45/45 + `methods` 45/45 + `tokens` 45/45 + `prune_imports` + 定向陷阱面扫描 + `javap -p -c` 双变体实证。报告落盘 `skill/review/review-20261005-m6-util.md`。记账：**阻断/本次引入 1 条（已修）** = `KV.get(key, null)` 的 NPE；**中/本次引入 1 条** = `PlayerHelper` 的 `Boolean`→`boolean` 描述符变化（0 调用方）；**低/本次引入 7 条** = `DefaultConfig.pickByCategories` 的 `==` 取代 `equals`（更宽容）、`KVDecoder.coerceNumber` static→实例 + public、`KVKeySpec` 两常量/构造器放宽、`OkGoHelper`/`Thunder` 若干包私有收紧、`UnicodeReader` final 化、`Proxy` 的日志/异常类型差异、`VideoParseRuler` 丢 `assert`；**低/既有 2 条** = `KV` KDoc 与实现本就不一致、`PlayerHelper` 无限递归；**口味 2 条** = `lowercase()` 的 locale、`SubtitleHelper` 形参放宽。

**未验证面（诚实标注）**：真机走查未做 —— 换源成功/失败与 `clan://` 解密、播放器参数面板与内核切换、投屏与 DLNA、本地/在线字幕加载与轨道记忆、m3u8 去广告全链路与 `/proxy` 四级路由、迅雷/荐片下载、启动看门狗与黑名单、原生库修复与清除缓存、KV 全链路（集合与嵌套泛型还原、doh 合并去重）。详见审查报告第四节。

# 7.11 M6 追加批次实测登记（2026-10-05，`sourcedata/SubtitleViewModel` + `data/AppDataManager`）

**结论**：M6 收尾的 2 个自有 Java 全量迁 Kotlin（共 **409 行 Java**，产出 297 行 Kotlin），原 `.java` 全删 ⇒ **`data/` 与 `sourcedata/` 两包 Java 清零**；`app/src/main/java` 由 **84 j / 269 kt → 82 j / 271 kt**。`:app:assembleDebug` 绿、`:app:testDebugUnitTest` **536 用例 / 0 失败 / 0 错误 / 0 跳过（70 suite）**（与 M5 / M6-subtitle / M6-util 基线持平，纯语言迁移无新增用例）、**4 处调用点一行未改**、`methods` 卡口 **2/2 全部命中**（5 + 18 个成员）、`lf` 2/2、`tokens` 2/2（缺失项逐条判为「类型推断 / 属性语法 / import 精简 / 注释」四类，见下）、`prune_imports` 无需处理。交付、记账与未验证面见 `skill/review/review-20261005-m6-tail.md`。**本批次免 `javap` 卡口**（非 D9 Tier A/B；`AppDataManager.get()` 调用点全在仓内、由构建覆盖）—— 但因 M2 已留 `javap-data-*` 基线，仍跑了一次作信息性核对，见本节末尾。

**本批次现场核实出的规则（M6b / M7 照查）**：

1. **Kotlin 允许「属性与函数同名」**：Java 的 `public MutableLiveData<SubtitleData> searchResult;` + `public void searchResult(String, int)` 在 Kotlin 里可以原样并存（属性 getter = `getSearchResult()`，方法 = `searchResult(String,int)`，JVM 无冲突）。**迁移前不必为了改名而改调用点** —— 已用 kotlinc 2.4.20 实测通过。
2. **⚠️ Kotlin 接口（非 `fun interface`）不支持 Kotlin 侧的 SAM 转换**：`SubtitleLoader` 若落成普通 `interface`，`SubtitleSheets.kt` 的 `viewModel.getSubtitleUrl(item) { subtitle -> … }` 会编译失败 ⇒ 必须 `fun interface`。**Java 侧向 Kotlin 接口传 lambda 不受语言影响**（两种写法 Java 都能用）。
3. **接口形参是否放宽为可空，判据是「调用点是否可能传 null」而不是「实现方是否判空」**：`SubtitleLoader.loadSubtitle` 的 Java 声明是平台类型，`PlayContainer.java` 的实现里写了 `subtitle == null`（防御性死分支），但**全部调用点都传非空** ⇒ 保持非空更安全 —— 抽象接口方法不生成 `checkNotNullParameter`（没有方法体），保持非空既无新崩溃边界，也让 Kotlin 实现方（`SubtitleSheets.kt` 的 lambda）不加 `?.`/`!!`；改成可空反而要在调用点补 `!!`。
4. **`jsoup` 的 `Elements.last()` 在 Kotlin 侧是 `Element?`**（jsoup 有 `@Nullable`）⇒ `pages.last()!!.text()`。同理 `selectFirst()` 返回 `Element?`（本文件已按 null 分支处理）。
5. **`String.split(delimiter, limit)` 的第二位置参数是 `ignoreCase: Boolean`**，写 `split("/", 2)` 会报类型不符 ⇒ 必须具名 `split("/", limit = 2)`。Java `split(regex, 2)` 的正 limit 语义与 Kotlin `split("字面量", limit = 2)` 一致（§7.5 规则 7）。
6. **`object` 里引用自身类型、并用 `this` 赋值是合法的**：`object O { private var m: O? = null; fun init() { …; m = this } }`。这是 Java「`private static AppDataManager manager` + DCL `new AppDataManager()`」这类**纯旗标实例**的自然落法（`manager` 在本类内从不使用，只作「是否已 init」的判据）。
7. **⚠️ `@Synchronized` 不等于 Java 的 `static synchronized`**：前者锁 `INSTANCE`，后者锁 `Class`。`AppDataManager` 的 `init()` 用 `synchronized(AppDataManager.class)`、`get()` 是 `static synchronized`，两者在 Java 里共用同一把 Class 锁 ⇒ Kotlin 侧**两侧都写成显式 `synchronized(AppDataManager::class.java)`**（与 §7.10 规则 12 同源）。
8. **`object` 的私有属性编译出来仍是 `private static` 字段**（`javap` 实证：`manager`/`dbInstance`/`DB_FILE_VERSION`/`DB_NAME` 与 Java 逐字相同），不必担心"变成实例字段"带来的行为差异。
9. **`File.getParentFile()` 在 Kotlin 侧是 `File?`**，直接 `.exists()` 只报 **warning**（不报 error）⇒ 沿用 `util/FileUtils.kt:526-527` 的既有处理，保留告警登记即可，不要"顺手"改成 `!!`。
10. **`text.split` / `toLowerCase(Locale)` / `Integer.valueOf(x.trim())`** 的 Kotlin 形态：`split("/", limit = 2)` / `lowercase(Locale.ROOT)` / `x.trim { it <= ' ' }.toInt()`（前两条同 §7.5 规则 16 / §7.10 规则 8）。
11. **`OkGo` 的 `params(key, value)` 在 Kotlin 侧是平台类型参数**，传 `String?` 不需要 `!!`（`SearchLoader.kt` 已实证可传 null）；`params(key, int)` 有独立重载 ⇒ `.params("page", page)` 可直接写。
12. **`builder.readTimeout(15, TimeUnit.SECONDS)` 的整数字面量会按 Long 推断**（`RemoteTVBox.kt:143` 先例），不必写 `15L`。

**`tokens` 卡口缺失项逐条判定**（判据：只看「旧有新无」）：

| 文件 | 缺失项 | 判定 |
| --- | --- | --- |
| `AppDataManager` | `dbInstance` 10→8 | `if (dbInstance != null) dbInstance.close()` → `dbInstance?.close()`（等价；`?.` 只读一次字段，**收掉了 check-then-act 的竞态窗口**） |
| | `AppDataManager` 5→4 | 类声明 + 私有构造器 → `object` 单声明 |
| | `File`/`String` 各减 | Kotlin 类型推断去掉显式类型（`val db = …`、`val DB_NAME = "tvbox"`） |
| | `getParentFile` 2→0 | 属性语法 `db.parentFile` |
| `SubtitleViewModel` | `Document`/`Element`/`Elements`/`OkHttpClient`/`Request`/`Builder` 减 | 同上：类型推断 + import 精简（`org.jsoup.nodes.*`、`java.util.regex.Matcher`、`java.util.{ArrayList,List}` 均未使用） |
| | `getUrl`/`setUrl` 各 1→0 | Kotlin 属性语法 `subtitle.url` |
| | `IOException` 3→2 | Kotlin 不写 `throws` |
| | `SubtitleViewModel` 2→1 | 无显式构造器 |
| | `java`/`org`/`jsoup`/`regex`/`util` 减 | import 精简 |
| | 若干巨大 `STR:` 项 | **脚本噪音**：PowerShell 5.1 取 `git show` 输出按 GBK 解码，中文注释乱码导致字符串字面量正则误匹配（标识符级比对不受影响） |

**信息性 `javap` 核对（非卡口）**：`javap-data-debug-baseline.txt` 是 **M0 期快照**（仍含 M2 已删的 `CacheManager`/`RoomDataManger`、尚无 `CurrentSubscription`），故 diff 里绝大部分是 M0→今的累计差异（M2 已登记）。**本批次真正的新增差异只有 `AppDataManager` 一类**：`public class` → `public final class`；4 个静态方法加 `final`；`get()` 的 `synchronized` 修饰符从签名消失（改为方法内 `synchronized(Class)` 块，锁对象不变）；`static String dbPath()`（包私有）→ `private final String dbPath()`；新增 `public static final INSTANCE` / `$stable` / `static {}`；私有构造器与 4 个私有静态字段（`manager`/`dbInstance`/`DB_FILE_VERSION`/`DB_NAME`）**逐字不变**。4 个公开入口的**名字与描述符完全未变**（`init()V`、`get()Lcom/…/AppDataBase;`、`backup(Ljava/io/File;)Z throws IOException`、`restore(Ljava/io/File;)Z throws IOException`）。

**审查轮 1（2026-10-05，本机逐方法逐语句对账 2 个文件）**：**0 条 阻断 / 高 / 中**；记账 —— **低 / 本次引入 5 条**（`manager` 旗标实例 → `this`、`dbPath()` 包私有 → private、`dbInstance?.close()` 的读次数、局部 `url` → `downloadUrl` 2 处、`SubtitleLoader` 形参保持非空）、**低 / 既有 1 条**（`URLDecoder.decode` 弃用告警）、**口味 1 条**（`File?` 告警沿用 `FileUtils` 处理）。

**审查轮 2（2026-10-05，3 个只读子代理 + 本机复核，结论 = 达到收敛终止线）**：同样 **0 条 阻断 / 高 / 中**，且 **0 条「既有被放大」**。新增条目全部低危 —— **低 / 本次引入 2 条**（`AppContextHolder.context()!!` ×3 的 NPE 抛出点前移，同 §7.10 规则 1；`AppDataManager` 变 `final` + 新增 `INSTANCE`，`object` 化固有形态）、**低 / 既有 3 条**（302 直链 `Response` 从不 `close()` 且每次新建 `OkHttpClient` 不复用；`pagesTotal` 只在 `page == 1` 重置、换片名可能沿用旧总页数；`manager`/`dbInstance` 非 volatile 的 DCL）、**口味 1 条**（`object` 的 `INSTANCE` 与 final）。子代理实证补充：`javap -c` 确认 `init()` 与 `get()` 用的是**同一个 Class 常量**（非 INSTANCE）；`object` 的私有属性仍编译为 `private static` 字段；`TextUtils.isEmpty` 出现次数 Kotlin 9 / Java 9 逐行一致；10 处 `!!` 逐处判为复刻 Java 隐式解引用。**附录 A/B（度量盘点 + 上轮对账，含 M6-util 阻断项已修确认）落盘 `skill/review/review-20261005-m6-tail.md` 第 5 节。**

**未验证面（诚实标注）**：真机走查未做（用户红线：需授权）—— 在线字幕搜索（assrt 搜索 / zip 展开 / 分页）、记忆还原路径 `pickEpisodeSubtitle`（集号/变体/同名/单文件四条规则）、字幕直链 302 解析与 `onFailed` 回落、DB `backup`/`restore`（全库 0 调用方，仅由构建覆盖）。

# 7.12 M6b 实测登记（2026-10-05，`base`/`server`/`dlna`/`event`/`receiver` + `com.p2p` + `app/src/python/java` 共 23 个类）

**结论**：23 个 Java 类全量迁 Kotlin（6 笔 commit `4e448d5`/`89a7c3d`/`cbbd7d4`/`ce231af`/`3d3cc4e`/`7fddf70` + 11 个调用点适配），原 `.java` 全删。`:app:assembleDebug` + `:app:assembleRelease` 绿；`:app:testDebugUnitTest` **536 用例 / 0 失败 / 0 错误 / 0 跳过（70 suite）**（与 M5/M6 基线持平，纯语言迁移无新增用例）；`app/src/main/java` **64 Java / 289 Kotlin**（本批 -18 j / +18 kt），`app/src/python/java` **0 Java / 5 Kotlin**（**Chaquopy sourceSet Java 清零**）；`methods` 卡口 23/23、`tokens` 23/23（缺失项全为已知盲区 + 模板折串/注解串）、`lf` 34/34、`prune_imports` 清 2 个未使用 import、`tierb` 11 符号未变；**`javap -p -s` debug 与 release 双变体逐类比对 + 跨类归一化逐成员配对**（未配对仅 20 条，逐条为"私有 helper 迁 Companion / lambda 改名 / throws 消失 / 星投影 / 私有字段类型"五类，零公开成员消失）。交付、记账与未验证面见 `skill/review/review-20261005-m6b.md`。

**本里程碑现场核实出的规则（M7 / M9 / M10 照查）**：

1. **自定义 java srcDir 会被 KGP 纳入 Kotlin 编译（实测）**：`app/build.gradle.kts` 的 `sourceSets.main.java.directories += "src/python/java"` 之后，`kotlin.sourceSets.getByName("main").kotlin.srcDirs` 实测为 `[src/main/kotlin, src/main/java, src/python/java]` ⇒ **无需改构建**（计划为"若不纳入则单独一笔 commit"的预案未触发）。验证手段：Gradle init script 打印两个 sourceSet 的 srcDirs。
2. **无主构造器 + 继承 Java 类：类头写 `: Spider`（不带括号）并由某个次级构造器 `: super()` 初始化父类**。写成 `: Spider()` 会报 `Supertype initialization is impossible without a primary constructor`，并连带产生 `error_constructor` 的"Conflicting overloads"假错误（`PythonSpider` 实测）。
3. **Java 里的"隐式覆写"必须补 `override`**：`PythonSpider.init(Context, String)` 在 Java 无 `@Override` 但确实覆写 `Spider.init(Context, String)`（同名同描述符）⇒ Kotlin 必须 `override`，否则分派改变；三参 `init(Context, String, String)` 是新重载（不覆写）。
4. **Java 接口常量迁 Kotlin 后落在 `Companion`**（`RequestProcess.KEY_ACTION_*` → `RequestProcess$Companion` 的静态字段），不再挂接口本身；本批零调用点、登记接受。要在接口上保字段只能放弃 `const val`。
5. **静态同步的锁对象**：`synchronized(PyLog::class.java)` 才是 Java `static synchronized` 的 Class 锁（`@Synchronized` 锁 INSTANCE）；但 `@Synchronized` 加在**实例**方法上锁 `this`，与 Java 方法级 `synchronized` 等价（`pyLoader.clear/getSpider`）。
6. **排序 API 对照**：`Arrays.sort(arr, comparator)` → `arr.sortWith { … }`（都稳定）；`Comparator.comparing(f, nullsLast(naturalOrder()))` → `compareBy(nullsLast<String>()) { … }`（null 键两侧都"排末尾"；Java 遇 null 键会 NPE，Kotlin 更宽容）。
7. **okhttp 5 API 对照（本批实测）**：`MediaType.parse(s)` → `s.toMediaTypeOrNull()`（非法串同返 null）；`RequestBody.create(mt, bytes)` → `bytes.toRequestBody(mt)`；`HttpUrl.parse(url)` → `url.toHttpUrlOrNull()`；但 **`Request.Builder.url(String)` 可以原样保留**（它内部保留 `ws://`→`http://` 静默替换与非法 URL 抛 IAE，改 `toHttpUrl()` 会丢 ws 替换）。`Response.body` 是**非空 Kotlin 属性**，保留 Java 的 `if (body != null)` 会得恒真告警（Java 那句本就恒真）。
8. **私有 static 助手迁 companion 后，`javap` 逐类比对会把它们报成"消失"**：必须做**跨类归一化配对**（去修饰符 + 去 `throws` 后全局配对）才能把"搬进 `Companion`"与"真丢失"分开（本批 15 条属此类：`DLNACastManager.str`、`PyLog` 的 7 个助手、`SocketHttpStreamServer.ISO_8859_1`、`PythonLoader` 的两个 lambda 等）。
9. **`private val devices = ConcurrentHashMap<...>()`** 会把私有字段类型由 Java 的 `Map` 变 `ConcurrentHashMap`（描述符变化、私有、零外部影响）；**并注意**该字段的 null key 语义：Java 底层就是 `ConcurrentHashMap` ⇒ `put(null, v)` 抛 NPE，Kotlin 的 `devices[device.id!!]` 与 Java 同址同因，**不是**新崩溃边界（审查时勿只据 javap 的声明类型误判为"HashMap 容忍 null"）。
10. **Java 的 `public static` 字段一律 `@JvmField var`**：`App.burl`、`P2PClass.port`、`RemoteServer.serverPort`、`ControlManager.mContext`、`CustomWebReceiver` 的 5 个、`PyLog$TagConstant` 的 8 个；**只读字段若 Java 侧非 final 也必须 `var`**（`val` 会把字段 final 化，Java 侧重新赋值编译失败；`CustomWebReceiver.callback` 是审查轮抓到的实例）。
11. **JNI 包装类迁 Kotlin**：`private external fun` 保名保描述符（`P2PClass` 26/26 与 Java 逐字一致，`javap` 实证）；`static {}` 的 `loadLibrary` 放 companion `init` 落外层 `<clinit>`；内部类调用 private native 时会生成 `access$xxx` 合成桥（不影响 JNI 按名解析）。native 方法名/类名/包名一律禁改。
12. **`app/src/python/java` 里的类混着 `Spider` 继承与 Tier B 静态调用**（`PythonSpider extends Spider`、`LOG`/`App`），迁移口径与主包一致；`PythonLoader.getInstance().pyApp` 这类**同包按字段访问**的成员只能放宽为 `public @JvmField`（Kotlin 无包私有、`internal` 会加 `$module` 后缀）。
13. **契约面有嵌套类要 `javap -IncludeInner` 才覆盖**（`PyLog$TagConstant`、`P2PClass$init`）：本批以"编译期调用点（`PyLog.TagConstant.TAG_APP = …`）+ 手工 `javap`"补证；建议后续里程碑把 `-IncludeInner` 纳入默认流程。
14. **`String.getBytes()`/`new String(byte[])` 的平台默认字符集**本批 5 处逐一对齐（`toByteArray(Charset.defaultCharset())`；`String(bytes, Charsets.UTF_8)` 只用于显式 UTF-8 的 `/dash/` 解码）。
15. **`split` 的等价写法本批 3 处**：`RegexUtils.getPattern("\\.").split(hostname)`（Java `split("\\.")` 丢尾部空串）、`getPattern("base64,").split(content)[1]`、`split(" ", limit = 3)`（正 limit 与 Java 一致）。
16. **可空性判据仍按 §7.4 规则 11**：入参非空=新崩溃边界，本批非空入参（`ControlManager.mContext!!`、构造器参数、`setApplication/setConfig/getUrlByApi/getSpider(key)`、`CastVideo.url`）逐条核为"调用点全部传非空/已有守卫"，登记为低；`!!` 约 40 处全部复刻 Java 隐式解引用。

**登记的产物差异（debug 与 release 一致）**：`class` → `public final class`（需要被匿名子类继承的 `DLNAServiceConfiguration` / `OkHttpStreamClient.Configuration` 保留 `open`）；包私有类/成员放宽为 `public` 或收紧为 `private`（清单见审查报告 §2）；私有 static 助手迁 `Companion`；新增 `Companion`/`$stable`/`access$*` 桥/`DefaultConstructorMarker`；`Object` 桥接方法的 `protected`→`public`（泛型覆盖产物）；`throws` 从 4 处方法消失（无 Java 调用方）；泛型签名由裸类型变星投影（`StreamClient<*>`/`StreamServer<*>`/`Map<*,*>`）。

**审查轮（2026-10-05，结论 = 可收尾）**：6 个只读子代理独立逐方法复核 + 本机 `git show 9010c69:` 逐条闭环（子代理无 shell，其"待核对"项全部由本机结论），**0 条 阻断 / 高 / 中**；修复 2 条低级本次引入项（`RemoteServer.getCurrentEpisodeIndex` 恢复 `current != null` 防御、`CustomWebReceiver.callback` 改回非 final 字段）；登记项 = 9 条低×本次引入 + 5 条低×既有 + 4 条口味差异（逐条见 `skill/review/review-20261005-m6b.md` §2）。**卡口口径补充**：`methods` 的 5 条"未命中"全部是属性化 getter/匿名类变 lambda（`CastDevice`/`CastVideo` 的 3+3 个 getter、`RemoteServer.compare`、`BaseActivity` 的 4 个）——写清楚，别当违规。

**未验证面（诚实标注）**：真机走查未做 —— 启动、局域网服务地址/端口与五条路由、DLNA 投屏、广播接收、P2P 原生库、py 源加载（详见审查报告 §6）。

# 7.13 M7a 实测登记（2026-10-05，播放栈自研替换：M7-0 + 新内核适配层 `osc.player.engine`）

**结论**：M7-0 删 `player/build.gradle.kts` 的 `api(libs.dkplayer.ui)`（死依赖，全库源码零 `xyz.doikki.videocontroller` 类引用；toml 项留 M10 删）；M7a 新建 **`com.github.tvbox.osc.player.engine`** 包 9 个 Kotlin 文件 = 移植 4 件（`OkHttpDataSource`/`HlsErrorHandlingPolicy`/`MediaSources`/共享缓存委派 `PlayerCache`）+ 装配 2 件（`EngineRenderersFactory`/`PlayerEngine`）+ 策略 3 件（`SourcePolicy`/`CodecPreferences`/`NetworkSpeed`），另 4 个单测文件。`:app:assembleDebug` + `:app:assembleRelease` 绿；`:app:testDebugUnitTest` **569 用例 / 0 失败 / 0 错误 / 0 跳过（74 suite）**（536 基线 + 33 新增）。**M7a 不接 UI/不接调用方（双栈并存，doikki 仍是回退面）**，新旧共享状态收口 2 处：旧 `ExoPlayer.setPreferSoftwareDecode/isPreferSoftwareDecode` 改读写 `CodecPreferences`（选择器同源）、app 侧 `PlayerCache` 反向委派旧 `ExoMediaSourceHelper`（模块依赖方向 app→player）。独立子代理逐类对照复核（18 条结论）：**1 阻断 + 1 高（同根因）+ 6 中低全部已修**，2 条登记（私改公为单测、`usesExoSelector` 日志恒真）。

**本切片现场核实出的规则（M7b–M7f 照查）**：

1. **Kotlin override Java 方法的参数必须声明为非空**（平台类型不许写 `?`）：`DefaultRenderersFactory.buildVideoRenderers/buildTextRenderers` 的 `Handler`/`VideoRendererEventListener`/`TextOutput`/`Looper` 写可空会直接报 `NOTHING_TO_OVERRIDE`；`javap -c` 实证三个 override 方法入口均插了 `Intrinsics.checkNotNullParameter`，但 media3 上游实参恒非空（`ExoPlayerImpl` 传 `new Handler(builder.looper)` 与 `componentListener`，`buildTextRenderers` 的 looper = `eventHandler.getLooper()`）⇒ **不可达、不构成崩溃面**（升级 media3 时按本条复核上游传值）。
2. **Kotlin 不能直接访问"继承来的 Java 静态常量"**：`MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY` 必须写 `DefaultRenderersFactory.XXX`。
3. **`val x = f().also { … 读 x … }` 陷阱（本轮抓到阻断级真缺陷）**：`also` 块先于赋值执行 ⇒ 块内 `internalPlayer` 仍是 null，`applyPlaybackParameters()`/`disableFrameRateMatching()`/`applyFrameRateTracking()` 三处全部空转（隧道/AAC 偏好/帧率匹配静默失效）。**正解 = 先 `val exo = createPlayer(); internalPlayer = exo;` 再逐项下发**（旧 doikki `super.initPlayer()` 先建实例的顺序）。
4. **`C.LENGTH_UNSET` 是 Int**：Kotlin 里与 `Long` 比较/赋值要显式 `.toLong()`（`dataSpec.length != C.LENGTH_UNSET.toLong()`），Java 的隐式提升不再成立。
5. **`InvocationHandler.invoke` 的 `args` 是 `Array<out Any>?`**（不可写）：要改元素需 `@Suppress("UNCHECKED_CAST") (args as Array<Any>).clone()`；`method.invoke(renderer)` 与 `method.invoke(renderer, *invokeArgs)` 必须分两支（Kotlin 不能把 null 数组展开成"无参调用"）。
6. **JVM 单测（`isReturnDefaultValues`）里 `Uri`/`Bundle`/`TextUtils` 全是桩**：依赖 `Uri.parse` 的路径判定（`/live.php`、`/live/`）、`TextUtils.isEmpty` 的空键过滤、Bundle 序列化（`buildMediaItem`/`getHeadersFrom`）**都不可 JVM 测**；`PlaybackException(message, cause, code, Bundle())`（4 参）可构造（3 参构造走 `Bundle.EMPTY` 桩 null 会被内部 `checkNotNull` 拒绝）；`LoadErrorInfo` 链上要 `DataSpec`/`Uri` ⇒ HLS 策略的重试延迟/fallback 分支同样不可 JVM 测（只测 `isChunkError` 与重试次数）。
7. **跨模块依赖方向**：`player` 模块不能引用 app 的类（`com.github.tvbox.osc.*`）。共享缓存的实现只能留在 `ExoMediaSourceHelper`，app 侧 `PlayerCache` 做反向委派；**M10 拆除 player 模块时把实现整体搬进 `PlayerCache`（唯一改动点）**。
8. **`setDisplay` 的自动补发是承重行为**：旧 `ExoPlayer.setDisplay` 在 `super.setDisplay` 后按 `holder.getSurfaceFrame()` 补发 `MSG_SET_VIDEO_OUTPUT_RESOLUTION`（SurfaceView 路径唯一补发点，漏发 = 效果管线黑屏）；新层必须照做，且 `reset()` 会把 `playWhenReady` 停到 false —— 复用内核起播须补 `setOptions()`（旧链路 `reset → setOptions → prepare`）。
9. **配置默认值要对齐"旧缺键口径"而非 media3 默认**：`bufferTimes` 缺省 = `HawkConfig.BUFFER_TIMES_DEFAULT`（3），不是 1（写 1 会让 M7c 漏传时缓冲缩到 1/3）。
10. **新层遗漏项已补**：`getTcpSpeed()`（旧 `PlayerUtils.getNetSpeed`，OSD 网速）→ `NetworkSpeed`（TrafficStats 差值法，脱离 doikki 依赖）；`MediaSources` 构造即归一 `applicationContext`（旧单例的防泄漏语义）；client 未注入时回落 `OkGoHelper.getItvClient()`（旧栈全局注入，避免静默走裸 client 丢 DoH/hosts/代理/SSL）。

**M7a 未承接 / M7b–M7f 待接线清单（登记，防丢；② 之后为第二轮审查补录 2026-10-05）**：① 渲染宿主（Surface/Texture 双模式、`setVideoSurface`/`setDisplay` 调用、尺寸/比例/挖孔、音频焦点、进度保存）；② 状态机与事件面（`onPrepared`/`RENDERING_START`/`BUFFERING_*`/completion/error、`videoSizeListener` 与 `ErrorListener` 是现成钩子）；③ 接线面 = `PlayerEngineConfig` 全字段 + `setDataSource(..., isLive = KV<PLAYER_IS_LIVE>)` + `setContentKey`/`setUseDiskCache`/`setStartPosition`（旧 `MyVideoView` 注入路径）；④ `PictureEffects` 接线（`onPrepare`/`onPlayerReleased`，参数类型待在 M7c 改为新引擎类型；`PictureEffects` 现仍面向旧 `ExoPlayer`）；⑤ `OkGoHelper.initExoOkHttpClient` 的 `setOkClient` 注入点（M7c/M7f 改指向 `MediaSources`）；⑥ `FileUtils` 清缓存目录名与共享缓存实现搬迁（M10）；⑦ **旋转事件**：旧 `MEDIA_INFO_VIDEO_ROTATION_CHANGED`（`ExoMediaPlayer` 发、`VideoView` 驱动 `RenderView.setVideoRotation`）的等价物 = `videoSizeListener` 第 3 参（rotation 度），**M7b 桥接必须回发 onInfo(10001)**，漏了则旋转源静默不转；⑧ **`keepRenderViewOnReset` 必须为 true**（旧 `ExoMediaPlayer` 覆写；宿主 `VideoView.replay` 据此选复用分支，M7b 桥接固定返回 true）；⑨ **调用点改写清单（4 处 `instanceof/as ExoPlayer`，漏改即静默降级）**：`ui/player/TrackSelectorDelegate`（选轨菜单）、`ui/player/PlayContainer`（OSD/参数面板）、`player/controller/ComposeVideoController.kt`（解码名/帧率/丢帧/重缓冲/隧道）、`player/PlaybackRetryDelegate`（重试阶梯按 `lastErrorKind` 1/2 分支）；⑩ M7b 须**新增 `AbstractPlayer` 子类桥**（`VideoView.mMediaPlayer` 类型为 `AbstractPlayer`，新引擎不是它）；⑪ `setDataSource(AssetFileDescriptor)`（旧为空实现）无对应，登记即可；⑫ 注入面收窄：旧 `setTrackSelector/setRenderersFactory/setLoadControl` 中 **TrackSelector 不再可注入**（新层内部建 `DefaultTrackSelector`）；⑬ `OkHttpDataSource` 便捷构造由 3 个 public 收窄为 `Factory`（仓内零调用方，产出契约收窄）；⑭ 预载侧旧 helper 三 API 的切换点（`PreloadManagerHolder` 的 `buildPreloadMediaItem`/`createDataSourceFactory`/`getHeadersFrom`，与 ⑤ 同批）；⑮ 单测缺口登记：`getRetryDelayMsFor`(500ms)/`getFallbackSelectionFor`(不 fallback) 承重分支与 `retriedAsHls` 单次重试在 JVM 测不可达（`LoadErrorInfo` 链依赖 `Uri`），入 M7b 真机走查清单（后者可抽纯函数补测）。

**审查轮（第二轮，2026-10-05，结论 = 可收尾）**：两个独立只读子代理（A = 逐项复审首轮 10 条修复；B = 独立收尾判据审查：公开契约面/语义/单测质量/代码卫生）+ 本机 `javap` 闭环。**0 阻断 / 0 高**：首轮 10 条修复 9 条确证落地、1 条（`@Volatile` 清单）本轮补齐；新层 9 文件逐方法对读未发现功能性偏差。本轮修订（全部为低风险口径对齐，无行为变更）：① `@Volatile` 齐平旧栈 10 个 volatile 字段（补 `frameRateWindowStartMs`，补旧 `AbstractPlayer` 的 `startPositionMs`/`startPositionApplied`）；② 删恒假判空 `tracks == null`（非空形参）；③ `MediaSources` KDoc 与 client 回落链实现对齐；④ 删 `PlayerCache` 两个无引用死常量（容量/目录名真值源保留在旧实现，避免多处声明）；⑤ `isLocalProxyUrl` 三处重复实现收敛 —— `PlayerHelper.isLocalProxyUrl` 改为委派 `SourcePolicy`（旧调用点零行为变化），`PreloadCoordinator` 的私有实现登记留 M7f；⑥ `PlayerEngine` 6 处 `lowercase()/uppercase()` 对齐旧 Java 默认 locale（§7.12 规则 14 口径，`Locale.getDefault()`）；⑦ 单测加判别力声明并钉跨层契约值（`ERROR_KIND_*` = 0/1/2；HLS 切片档 3 与 media3 默认 3 数值巧合 ⇒ 判别力只在 progressive-live=6 一条）。**登记未改**（低）：单测对 `Uri`/`Bundle` 依赖分支不可覆盖（规则 6）、`usesExoSelector` 日志恒真、`OkHttpDataSource` 整类无 JVM 单测（入 M7d 真机走查）。

**未验证面（诚实标注）**：M7a 不接 UI ⇒ 真机走查无从执行，门 = 双变体构建 + 单测 + 逐类对照复核；新栈的起播/渲染/效果/字幕/轨道全部行为留待 M7b/M7c 切换后随 `avbox-playback-service-spec.md` §4 清单走查。

# 8. 回滚

每切片一 commit，出问题 `git revert` 或 `git reset` 到上一切片；不推远程除非明确许可。契约层切片回滚前先确认 `javap` 基线仍可比对（产物与源码一致）。
