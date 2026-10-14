\---  
name: 主线程查 Room 全面后台化 Spec（Repository 异步化收口）  
status: 片 0–4 代码改造完成（2026-10-14 立项；R1/R2/R4 达成，硬门禁已实测通过，构建与单测全绿）；**11 条人眼走查未做**；§8.7 P1 六项闭合（测量/B4/B2/C 2026-10-14 落地，B1/B3 前序已落，见 §8.9）；§8.7 P2 于 2026-10-14 落地 `penaltyDeath` **同日回退为 `penaltyLog`**（判据见 §8.10.11-A：详情页等正常路径闪退）；**§8.4 A 三批全部落地**（A6/A3 实测判"不动"，A2/A1/A5 按档位处置，onCreate 主线程 ≈55ms → ≈44ms，见 §8.10.7/8/9）；**2026-10-14 一轮独立回归审查已收口**（1 个 BLOCKER：`ThreadLocal.withInitial` 在 minSdk 24 上不可用，已修；1 个 HIGH：越界 DoH 列表回归，已修，见 §8.10.11-B）  
source: 2026-10-14 用户指令：「写一份重构spec，把所有的主线程查room行为都移动到后台线程，达到目标形态」  
\---

# 结论摘要

- 目标形态 = **三层归位**：① Repository 接口签名全部 `suspend` / `Flow`（签名即契约）；② DAO 全部 suspend 化（Room 自带执行器调度，repository 内不手挂 `Dispatchers.IO`，仅 Java 序列化等非 Room IO 例外）；③ **删除 `allowMainThreadQueries()`**——豁免口不在，所有调用点被迫就范，这是整个改造的完成判定。
- 关键结构决策：**进度面（读+写）收进 `vod-progress-writer` 单一串行队列**。写侧本就在队列里；读侧改为"入队-挂起-回值"，队列保证"clear 先于下一次 read"的顺序，`awaitWrites()` 契约（`skill/avbox-mobile-ui-spec.md` §6.16）整个废除，换成队列顺序保证。**顺序保证的前提**：队列任务在其 DB 操作完成前**不得释放队列线程**——故队列保持单线程 Executor、任务内以 `runBlocking` 同步等待 suspend DAO（R1 受控例外，见 §3.1/§3.2）。**不能改成 `asCoroutineDispatcher()`**：它在协程挂起点释放线程，若任务内部再挂起（suspend DAO 派发到 Room 执行器），相邻任务会并发，顺序失效。
- 主线程查库点实测基线（2026-10-14 全仓盘点）：**主线程 Room 读 7 组 / 11 个调用点；主线程 Room 写 5 组 / 10 个调用点；主线程 awaitWrites 阻塞 2 处**；另有起播热路径同步读链，以及**进度清理路径（`clearOwner`/`clearAll`）绕过写队列的同步直删**（非主线程，但违反 R2，见 §2.2 F）。封装外零 DAO 直取（已达成，本计划固化防回归）。
- 分 **4 片 + 1 基线**：片 0 StrictMode 基线取证 → 片 1 data 层异步化 + 低风险调用点 → 片 2 次热路径（字幕/预载/投播）→ 片 3 起播热路径流水线 → 片 4 删豁免口 + 防回归门。每片独立可走查、可回退。
- **口径先行**："主线程查 Room"要消灭的是两件事——**执行 DB 的线程在主线程**（`allowMainThreadQueries` 兜底下的同步调用）和**主线程阻塞等待 DB**（主线程上的 `awaitWrites()`）。主线程上的 fire-and-forget **提交**（`HistoryWriter.write`、`WatchProgressStore.save/clear` 的入队动作）不算违规，终态保留。

# 1. 与既有文档 / 登记项的关系

| 既有文档 / 记忆                                       | 关系                                                                                                                                                                                                                                                                                 |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `skill/review/refactor-plan-20261005.md` D6/D10 | D6：「迁移=语义等价，不借机改线程模型；改 suspend/后台**单独立项**」——**本计划就是那个立项**，兑现该登记。**D10 冲突声明**：D10「Repository 内出现 `withContext(`/`flowOn(` 即驳回（会改 awaitWrites 读序）」被**本计划取代**——本计划允许 `CacheRepository` 的 Java 序列化段包 `withContext(Dispatchers.IO)`，并把 `awaitWrites` 读序改为队列顺序。执行时须同步修订 D10 文字，避免新旧口径冲突。 |
| `skill/avbox-kotlin-migration-spec.md`          | §「`allowMainThreadQueries()` 主线程查库是显式设计；进度写入走 `vod-progress-writer`（读须 `awaitWrites`）」——本计划完成后该口径改写：查库不再允许主线程，读侧契约改为队列顺序。                                                                                                                                                          |
| `skill/avbox-playback-service-spec.md` §        | 「Room `allowMainThreadQueries()` 是显式设计，不借机改」——本计划完成后随之改写。                                                                                                                                                                                                                          |
| `skill/avbox-mobile-ui-spec.md` §6.16           | `awaitWrites()` 读侧契约被**废除并替换**：新契约 = 进度读必须经 `WatchProgressStore` 的挂起读通道（入队），禁止任何路径直接读 `cacheRepository` 的进度键。片 3 落地时同步改写 §6.16。                                                                                                                                                    |
| 数据面响应式统一（be689dc）                               | 六个数据面已收成 Flow，本计划复用其成果：列表页读侧已是 Flow 零改动；本计划只收口**命令式读写**残留点。                                                                                                                                                                                                                        |
| 2026-09-28 主线程审计（`skill/history/features.md`）   | 本计划以该审计的"主线程路径清单"为起点，但按 2026-10-14 全仓重新盘点为准（代码已迁 Kotlin、repository 已收口，点数比审计时少）。                                                                                                                                                                                                   |
| ZeroStartGate（删除进度后重进续播收口）                      | 语义依赖"删进度 → 下次读不到旧进度"的**顺序性**。异步化后顺序性由单一队列保证，走查判据沿用该记忆里的 4 条。                                                                                                                                                                                                                       |
| 起播内核复用判定（KernelReusePolicy）                     | 片 3 改 `startPlay` 时序时，内核复用判定点**不得挪动**，只允许其上游数据获取方式从同步读变挂起读。                                                                                                                                                                                                                        |

# 2. 现状基线（2026-10-14 全仓实测盘点）

## 2.1 结构现状

- 数据面已收口在 `data/` 包：`HistoryRepository` / `CollectRepository` / `FollowRepository` / `CacheRepository` 四接口 + Room 实现；`AppGraph` 是唯一直取 DAO 处（`data/` 包外零直取，`base/App.kt` 只做 init）。
- DAO 全同步方法（仅 `observeAll` 系返回 `Flow`）；`AppDataManager.kt:44` 仍 `allowMainThreadQueries()`。
- 三个专用单线程通道：`vod-history-writer`（HistoryWriter，fire-and-forget 落库）、`vod-progress-writer`（WatchProgressStore，写队列 + `awaitWrites()` 屏障）、`playback-progress`（PlaybackProgress 用其独立单线程 + KV，不碰 Room，与本计划无关）。
- `CurrentSubscription.cid()` 是纯 KV 读，不碰 Room，排除。

## 2.2 Room 访问点清单（行号为 2026-10-14 基线；A–D 为主线程点，F 为绕过队列项）

### A. 主线程 Room 读（7 组 / 11 个调用点；口径：同一行的两个调用计 2 点）

| 位置                                                | 调用点                                      | 上下文                                            |
| ------------------------------------------------- | ---------------------------------------- | ---------------------------------------------- |
| `ui/activity/DetailViewModel.kt:318/319`          | `isVodCollect` / `followRepository.find` | `loadDetail()`，Activity 直调，无协程                 |
| `ui/activity/DetailViewModel.kt:652/653`          | 同上                                       | `loadDetailInternal()`                         |
| `ui/activity/MusicPlayerActivity.kt:134`          | `isVodCollect`                           | `onCreate` 末尾                                  |
| `player/PlaybackProgressTracker.kt:39`            | `cacheRepository.get`（进度键）               | 见 §2.2 D 热路径                                   |
| `player/PreloadCoordinator.kt:226`                | `cacheRepository.get`                    | `handlePreloadResult()`，Main.immediate collect |
| `ui/player/PlayContainerSubtitles.kt:330/372/481` | `cacheRepository.get`（字幕/歌词路径）           | 播放器 track 回调                                   |
| `ui/player/PlayContainer.kt:410`                  | `scheduler.getSavedProgress`             | 投播启动链路                                         |

### B. 主线程 Room 写（5 组 / 10 个调用点）

| 位置                                                                                      | 调用点                                     | 上下文                                            |
| --------------------------------------------------------------------------------------- | --------------------------------------- | ---------------------------------------------- |
| `ui/activity/DetailViewModel.kt:789/792`                                                | `deleteVodCollect` / `insertVodCollect` | `toggleCollect()`                              |
| `ui/activity/DetailViewModel.kt:800/801`                                                | `followRepository.upsert` / `find`      | `saveFollow()`                                 |
| `ui/activity/MusicPlayerActivity.kt:297/300`                                            | `delete` / `insertVodCollect`           | `toggleCollect()`                              |
| `player/PlaybackStarter.kt:194`                                                         | `cacheRepository.delete`                | `play()` 换内容清字幕缓存                              |
| `subtitle/widget/SimpleSubtitleView.kt:158`、`subtitle/DefaultSubtitleEngine.kt:110/113` | `cacheRepository.delete` / `save`       | UI 菜单动作 / 下载成功回调（SubtitleLoader 回调被 post 回主线程） |

### C. 主线程阻塞等待 DB（2 处）

| 位置                                     | 阻塞点                           | 上下文                          |
| -------------------------------------- | ----------------------------- | ---------------------------- |
| `player/PlaybackProgressTracker.kt:38` | `awaitWrites()`（latch ≤500ms） | `getSavedProgress()`，调用方全主线程 |
| `player/PreloadCoordinator.kt:225`     | `awaitWrites()`               | 同 A 表                        |

### D. 起播热路径同步读链（片 3 的对象）

```
用户点播 / 换集
→ AppPlayerView.start() (主线程, UI 触发)
→ AppPlayerView.startPlay():125  mCurrentPosition = sink.getSavedProgress(progressKey())
→ PlaybackEngine:57-58 → PlaybackController:83-84 → PlaybackProgressTracker:35-39
   ├─ awaitWrites() 阻塞主线程
   └─ cacheRepository.get(MD5(url)) 主线程 Room 读
→ 返回值同步赋给 mCurrentPosition，直接决定 prepare 起播位置
```

**返回值被同步消费**（`startPrepare` 位置、`PlaybackStarter.kt:348` 投外部播放器、`PlaybackStarter.kt:366` 超时基线、`PlaybackRetryDelegate.kt:306` 换线 preProgress、`PlaybackController.kt:101` 换线迁移）。这是全计划唯一"不能只包一层"的地方，需流水线改造。**签名爆炸半径**：`getSavedProgress` 是同步签名，贯穿 `AppPlayerView.ProgressSink`（`player/AppPlayerView.kt:32-36`）→ `PlaybackEngine.progressSink`（`player/PlaybackEngine.kt:47-59`）→ `PlaybackController`（`:83-85`、`:265`、`:652`）→ `PlaybackProgressTracker`，且被 `PlaybackStarter.Host`/`PlaybackRetryDelegate.Host` 实现——改 suspend 会牵动上述接口与 HeadlessView/Service 边界。

### E. 已在后台 / 不碰 Room（勿动、勿重查）

- HistoryPage / FollowingPage / CollectPage / VodCardMenu 全部 DAO 访问已在 `Dispatchers.IO` 或 Flow。
- `HistoryWriter` 两处主线程**提交**（`DetailViewModel.kt:904`、`MusicPlayerActivity.kt:265`）：非阻塞入队，终态保留。
- `WatchProgressStore.save/clear/inherit` 主线程**提交**：非阻塞入队，终态保留。
- `CurrentSubscription.cid()`（KV）、`CollectPage.kt:241` / `FollowingPage.kt:225` 的 `currentCid()`（KV）。
- `AppBootstrap.start()` 主线程段：文件自检 + BootGuard，顺序屏障语义（须先于 jar 装载），一次性毫秒级，**不动区**。
- MMKV（`KV.get/put`）：mmap 内存访问非磁盘 IO，组合期依赖同步取值，**不动区**（§6.9 既有模式）。

### F. 进度清理路径（非主线程，但**绕过写队列**——R2 违反项，基线遗漏）

| 位置                                                                 | 调用点                                                    | 上下文                                                                                                                                                                               |
| ------------------------------------------------------------------ | ------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `data/WatchProgressStore.kt:152`、`:191` → `:209`                   | `clearTitleLocked` 内 `cache.delete` 同步直删               | 经 `clearOwner`（`ui/page/HistoryViewModel.kt:133`，IO 线程）/ `clearAll`（同文件 `:143`，IO 线程）到达 → **绕过队列**；`trimCapacityLocked`（`WatchProgressStore.kt:254`）内的同名调用在 writer 线程、属队列内，**合规** |
| `data/WatchProgressStore.kt:197` → `data/CacheRepository.kt:47-52` | `clearAllProgress` 内 `dao.getAll` + 逐行 `dao.delete` 同步 | 经 `clearAll`（`HistoryViewModel.kt:143`，IO 线程）→ **绕过队列**                                                                                                                           |
| `data/WatchProgressStore.kt:151`、`:182`                            | `awaitWrites()` 阻塞等待                                   | `clearOwner`/`clearAll` 内（非主线程，故未计入 C 表）                                                                                                                                          |


> 结论：`clearOwner`/`clearAll` 的删库**不经 `submit` 队列**，由调用线程同步执行；`trimCapacityLocked` 的删库在 writer 线程、属队列内，不在违反之列。片 1 须把 `clearOwner`/`clearAll` 改为入队。**副作用提醒**：改入队后，`clearOwner`/`clearAll` 内触发的 `PlaybackPorts.discardStartedContentOf` / `onProgressKeysCleared` 等回调将由**调用线程**变为**队列线程**，须逐点核对线程亲和。

# 3. 终态设计

## 3.1 结构

```
调用方（VM / Activity / 播放引擎）
        │  只见 suspend / Flow 签名，不知道也不关心线程
        ▼
四 Repository 接口（签名即契约：suspend fun / Flow）
        │
        ├─ Room 实现 ── suspend DAO（Room 事务执行器自调度）
        │        └─ CacheRepository 的 Java 序列化段：withContext(Dispatchers.IO)
        │
        └─ WatchProgressStore（进度面唯一门口）
                 └─ vod-progress-writer 单线程 Executor（**保持单线程，不改成 asCoroutineDispatcher**）
                      ├─ 写任务：save / clear / inherit（fire-and-forget submit，主线程可调）
                      ├─ 删任务：clearOwner / clearAll（含 clearTitleLocked 直删、clearAllProgress 逐行删）改为入队，替代原同步直删
                      └─ 读任务：getSavedProgress 等读（submitAndAwait 入队，回值）
                           ⇒ 任务内以 runBlocking 同步等待 suspend DAO（R1 受控例外）
                           ⇒ 队列线程在任务完成前不释放 ⇒ 同队列串行 ⇒ clear 先于后续 read 有序
                           ⇒ awaitWrites() 契约废除
```

## 3.2 宪法（完成判定 + 防回归门）

- **R1 签名即契约**：四 Repository 接口中**凡触达 DB 的方法**（除 `Flow` 返回外）全部 `suspend`；`data/` 包外不存在任何同步签名能摸到 DB。**不触达 DB 的方法保持同步**——`CollectRepository.currentCid()` / `FollowRepository.currentCid()` 是纯 KV 读，且被 Compose 在 `onClick` 内同步比较（`ui/page/CollectPage.kt:241`、`ui/page/FollowingPage.kt:225`），**不得改 `suspend`**（否则这两个调用点被迫改结构，属无收益的扩大）。临时同步桥接变体（若有）必须片 4 全删，禁止残留双 API。**受控例外（两处，均为专用非主线程单线程宿主，不计入"桥接变体"）**：① `data/WatchProgressStore` 队列任务用 `runBlocking` 等待 suspend DAO——维持队列顺序的必要手段；② `data/HistoryWriter.writeNow` 用 `runBlocking` 等待 `insertVodRecord`——宿主是 `vod-history-writer` 专用单线程。两者都不在主线程、都不被主线程同步等待。
- **R2 进度面单队列**：进度键的**读、写、删**只经 `WatchProgressStore` 队列，任务串行；任何绕过队列直接读/删 `cacheRepository` 进度键的路径视为违约（基线违反项见 §2.2 F，片 1 收口）。**串行的前提**：队列任务在 DB 操作完成前不得释放队列线程——故队列保持单线程 Executor 且任务内 `runBlocking`（`asCoroutineDispatcher()` 会在挂起点释放线程，使相邻任务并发、顺序失效）。队列任务内禁止再入队等待（死锁）。
- **R3 主线程零 DB**：主线程允许的只有"非阻塞提交"。判据分两层：① **硬门禁** = R4 删 `allowMainThreadQueries()` 后，任何残留的主线程同步调用会使 Room 直接抛 `IllegalStateException`（这是完成判定的最终依据）；② **软取证** = debug 包 StrictMode `penaltyLog()`（diskReads/diskWrites + 主线程），并按栈过滤出 Room 命中，作为片 0 基线与片 4 复测的对照清单。StrictMode 只打日志不阻断，故门禁结论以 ① 为准、② 用于定位残留点。**2026-10-14 实测修正**：②对 Room **无效**——StrictMode 看不到 Room 3 + bundled SQLite 的原生读写（证据见 §6 片 0 产出 3），故「Room 命中清单」只能由 ① 的 `IllegalStateException` 栈产出；StrictMode 降级用于非 Room 文件 IO 回归。
- **R4 封装固化**：DAO 只在 `data/` 包内可见（`AppGraph` 唯一出口）；`AppDataManager` 的 builder 不再含 `allowMainThreadQueries()`。
- **R5 不动区**：MMKV 读写、`AppBootstrap.start()` 主线程段、`CurrentSubscription`、`playback-progress`（KV）不在本计划范围；禁顺手扩大。
- **R6 时序判据不许动**：ZeroStartGate 判据、KernelReusePolicy 判定点、`detailBuildToken` 回包守卫、换源竞态守卫的**语义**保持不变；本计划只允许改变"数据获取方式"，不允许改变"判定发生时机"。

# 4. 分片实施

分片判据与 20261007 计划相同：**验证方式是否相同**。片 0 取证；片 1 纯构建+单测；片 2/3 有行为时序变化，必须走查；片 4 纯删除+门禁。

## 片 0：StrictMode 基线取证

- debug 变体 `App` 开 StrictMode：`detectDiskReads/detectDiskWrites + penaltyLog`（仅 debug，release 不带）。
- 冷启动→进详情→起播→切集→退后台跑一轮，收集 logcat 中主线程 DB 命中清单（按栈过滤出 Room），作为片 4 门禁的**对照基线**。
- **队列顺序验证（决定 R2 的实现方式）**：写一个最小断言——`save(pos=A)` 入队后立即 `submitAndAwait(read)`，断言读到 A（而非旧值/空），再压入多次读写删交错，验证顺序。预期"`asCoroutineDispatcher()` 失败、单线程 Executor + `runBlocking` 通过"，据此固化 §3.1 的实现选择。**前置条件**：`WatchProgressStore` 现为硬编码单例（`private val cache … = AppGraph.cacheRepository`），须先支持注入 `CacheRepository`（或经 Robolectric），该断言才可落——一并纳入片 0。
- 产出：基线记录追加到本文件 §6。

## 片 1：data 层异步化 + 低风险调用点（编译器驱动）

**data 层：**

1. 四张 DAO（`VodRecordDao`/`VodCollectDao`/`VodFollowDao`/`CacheDao`）同步方法全改 `suspend`（Flow 方法不动）。
2. 四 Repository 接口签名 `suspend` 化（**`currentCid()` 为纯 KV 读、被 Compose 同步消费，保持同步**，见 R1）；Room 实现直调 suspend DAO，不套 `withContext(Dispatchers.IO)`；`RoomCacheRepository` 的 `toObject/toByteArray` 序列化段包 `withContext(Dispatchers.IO)`。
3. `WatchProgressStore`：`vod-progress-writer` **保持单线程 Executor**（不引入 `asCoroutineDispatcher()`）；队列任务内以 `runBlocking` 同步等待 suspend `cacheRepository`（R1 受控例外），确保任务完成前不释放队列线程；新增挂起读通道 `suspend fun <T> submitAndAwait(block)`；`save/clear/inherit` 语义不变（入队）。**同时把 §2.2 F 的 `clearOwner`/`clearAll` 清理路径（`clearTitleLocked` 直删、`clearAllProgress` 逐行删、二者的 `awaitWrites()`）改为入队**，使读/写/删同队列串行；`trimCapacityLocked` 已在 writer 线程执行、无需改。**改入队后须核对 `PlaybackPorts.discardStartedContentOf`/`onProgressKeysCleared` 等回调的线程亲和**（原在调用线程、将变为队列线程）。**此片不删 `awaitWrites()`**（主线程读点还没迁完），只标注 deprecated。
4. `AppDataManager` 本片**不删豁免口**（存量主线程点未清完），登记到片 4。

**低风险调用点（A/B 表中非播放层全部，机械迁移）：**

- `DetailViewModel` 318/319/652/653 → 并入既有 `viewModelScope.launch { withContext(Dispatchers.IO) }` 样板（`DetailViewModel.kt:386` 是现成范式）；789/792/800/801 → `viewModelScope.launch(IO)` + `LOG.e` 兜底。**注意**：`collected`/`follow` 是本地 StateFlow **手工赋值**（`DetailViewModel.kt:795`/`:801`），**并非既有 Flow**——`toggleCollect` 的本地翻转（795）保留以保即时反馈；`saveFollow` 必须把 `upsert`+`find` 放在**同一 IO 块内先后执行**后再回写 `follow.value`，否则会读到写前旧值。
- `MusicPlayerActivity` 134/297/300 → `lifecycleScope.launch(IO)`。
- `SimpleSubtitleView:158`、`DefaultSubtitleEngine:110/113` → `scope.launch(IO)` fire-and-forget。
- `PlaybackStarter:194`、`PlayContainerControlListener:58` 等进度写/清提交点 → 改经 `WatchProgressStore` 新通道（提交语义不变）。

**验收**：全量单测绿；编译器清零同步签名调用；本片不动任何播放时序。走查面：详情页收藏/追剧状态显示与切换、音乐页收藏、字幕缓存清理生效。

## 片 2：次热路径（字幕 / 预载 / 投播）

- `PreloadCoordinator:225/226`：`handlePreloadResult` 改为在 IO 层完成 `submitAndAwait` 读，主线程只收结果——**顺带消灭一处主线程 awaitWrites 阻塞**。
- `PlayContainerSubtitles:330/372/481`：字幕/歌词路径改为**播放准备阶段后台预读**存内存，track 回调只读内存变量；回调查询点不再摸 DB。
- `PlayContainer:410 getCastPosition`：改挂起读（投播启动链路本就有挂起点可挂）。
- `PlaybackRetryDelegate:306` / `PlaybackController:101`：换线链路读改挂起（这两个函数已有协程上下文或可自然挂起，属低风险挂起点迁移，非时序重排）。

> **2026-10-14 重切片**：上面两项（投播 `getCastPosition`、换线 `PlaybackRetryDelegate:306`/`PlaybackController:101`）经实施发现与片 3 的 `getSavedProgress` 签名爆炸半径**不可分**（都经 `PlaybackProgressTracker.getSavedProgress`，挂起化会连带 `AppPlayerView.startPlay`），已**并入片 3**，见 §6「片 2 偏差 ①」。片 2 实际只做「字幕 / 预载」两项。

**验收**：构建+单测绿；走查判据——预载起播位置正确（预载读点在写入后仍读到新值，验证队列顺序）、换线续播位置正确、字幕在 track 就绪时已可解析、投播起播位置正确。

## 片 3：起播热路径流水线（唯一真正的重排）

> **2026-10-14 扩容**：本片并入三项（见 §6「片 2 偏差 ①」）——① `PlaybackProgressTracker.getSavedProgress` 挂起化及其签名爆炸半径；② `CacheRepository.get` + `CacheDao.getCache` 挂起化（兑现片 1 偏差 ①）；③ 投播 `PlayContainer.getCastPosition` 与换线 `PlaybackRetryDelegate:306`/`PlaybackController:101` 两处读点。

- `AppPlayerView.startPlay` 改**先读后播**流水线：`start()` 触发后先挂起读 `getSavedProgress`（经 `WatchProgressStore` 的 `submitAndAwait` 通道），拿到位置后再 `startPrepare(position)`。**精确定义**：`initPlayer()`/`addDisplay()` 保持**同步先执行**，只有 `mMediaPlayer.setStartPosition(mCurrentPosition)` 之前的那次读改为挂起——让 DB 读与播放器初始化**重叠**，避免给冷起播多出一次队列往返（否则与验收判据⑤"起播耗时不劣化"冲突）。
- **签名爆炸半径（本片真实工作量）**：`getSavedProgress` 是同步签名，贯穿 `AppPlayerView.ProgressSink`、`PlaybackEngine.progressSink`、`PlaybackController`（`:83`、`:265`、`:652`）、`PlaybackStarter.Host`、`PlaybackRetryDelegate.Host`；改 suspend 需牵引这些接口及 HeadlessView/Service 边界，须逐点改造，**不能只改 `AppPlayerView`**（详见 §2.2 D）。
- 同链消费点改造：`PlaybackStarter.kt:348`（投外部播放器，基线漏列）、`PlaybackStarter.kt:366`（超时基线，建议复用 `startPlay` 已读到的值而非重读）、`PlaybackRetryDelegate` 换线 preProgress、`PlaybackController.migrateProgressOnLineSwitch`（读+写在同一挂起链内，顺序保持"先读旧、后写新"）。
- **禁改项（R6）**：KernelReusePolicy 四起播点分工、ZeroStartGate 判定时机、`detailBuildToken`——只换数据来源，不挪判定点。起播过渡动画/加载态如有依赖同步 startPlay 的假设，逐点核对。
- 收口动作：删 `awaitWrites()` 及其调用（含 `WatchProgressStore` 内部 drain 语义改为队列顺序保证）；改写 `skill/avbox-mobile-ui-spec.md` §6.16 契约文字。

**验收**：构建+单测绿 + **走查**：①删除进度后重进仍从 0 起（ZeroStartGate 判据）；②正常续播位置正确；③换线迁移位置正确；④预载接管位置正确；⑤起播耗时对照片 0 基线无劣化（重点看冷起播首帧）。

- **R6 自动化护栏**：既有 `player/ZeroStartGateTest.kt`、`player/KernelReusePolicyTest.kt`、`player/PlaybackProgressSamplerTest.kt`、`player/PlaybackTimesTest.kt`、`data/PlaybackProgressTest.kt` 必须保持绿——它们是"判定时机未变"的机器化证据，与人工走查并列。

## 片 4：删豁免口 + 防回归门

- 删 `AppDataManager.kt:44` `allowMainThreadQueries()`。
- 删片 1 若引入的任何过渡桥接变体（R1 清零核查）。
- StrictMode 片 0 基线复测：冷启动→详情→起播→切集→投播→字幕→音乐页全路径零主线程 DB 命中。
- 防回归：StrictMode 常驻 debug 包；可加单测/静态检查断言四 Repository 接口无同步 DB 方法（按项目测试惯例决定形式）。
- 收尾：改写 §6.16、`avbox-kotlin-migration-spec`、`avbox-playback-service-spec` 三处旧口径；本文件 status 改"已完成"；`skill/history/features.md` 归档。

# 5. 风险与反模式

| 风险                                                  | 对策                                                                                                           |
| --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| 主线程 `runBlocking` 桥接 DB                             | **禁止**。它是把"主线程执行 DB"换成"主线程等 DB"，jank 依旧且掩盖问题；StrictMode 虽不报但违反 R3 精神。唯一例外：`WatchProgressStore` 队列内部任务（专用线程）。 |
| 双 API 残留（同步 + suspend 并存）                           | 片 1 若为编译过渡引入桥接变体，必须登记在 §6 清单，片 4 强制全删（R1）。                                                                   |
| 异步化引入时序回归                                           | 本项目时序回归史重（detailBuildToken、换源竞态、awaitWrites 本身都是教训）。对策 = R6 + 片 3 集中走查 + 每片独立提交可单独回退。                        |
| `submitAndAwait` 死锁（队列任务内再入队等待）                     | R2 写明；block 在队列线程内**阻塞等待**（`runBlocking`）suspend cacheRepository，不重入队列，无死锁。                                  |
| 队列顺序保证失效（`asCoroutineDispatcher()` 在挂起点释放线程，相邻任务并发） | **已规避**：队列保持单线程 Executor、任务内阻塞等待（§3.1/R2）；片 0 顺序断言验证（§4 片 0）。                                                |
| 队列读排在大量 pending 写之后 → 起播读延迟                         | 队列任务量级小（进度为小对象）；片 0 记录起播读实测耗时；若偏大再评估，但读优先级不能破坏"读必须晚于其前写入"的顺序约束。                                              |
| 进度清理路径绕过队列（§2.2 F）导致删/读交错                           | 片 1 把 `clearOwner`/`clearAll` 改为入队（R2 收口）；改后核实 `PlaybackPorts` 回调的线程亲和。                                      |
| `cacheRepository.get` 返回 `Any?`（Java 序列化）的锁与兼容      | 本计划不改序列化格式，只改线程；格式替换属独立项，禁顺手做。                                                                               |
| 三表无 `@Index`                                        | 独立 schema 迁移项（DB version 升级），**不在本计划**，见开放问题①。                                                               |


# 6. 过渡登记与开放问题

**过渡登记**（片 1 引入、片 4 强制删除的桥接变体）：**无**（片 1 实施后确认未引入桥接变体——改用「切片与调用点对齐」推迟 `CacheRepository.get` 的挂起化，见片 1 偏差 ①）。注：`WatchProgressStore` 队列内的 `runBlocking` 与 `HistoryWriter.writeNow` 的 `runBlocking` 是 §3.2 R1 的**常驻受控例外**（见 §3.2 与片 1 偏差 ②），不属于需删除的过渡变体。

**片 0 基线记录（2026-10-14 起）：**

产出 1 — StrictMode 开关（已落地）：

- `app/src/main/java/com/github/tvbox/osc/base/App.kt` 的 `onCreate()` 首部调用 `installStrictMode()`；`BuildConfig.DEBUG` 守卫，策略 = `detectDiskReads().detectDiskWrites().penaltyLog()`。
- release 侧不带：`BuildConfig.DEBUG` 是编译期常量，R8 静态裁剪整段。

产出 2 — 队列顺序断言（已落地）：

- `app/src/test/java/com/github/tvbox/osc/data/WatchProgressQueueOrderTest.kt`，三条断言：
  1. `asCoroutineDispatcher` 在挂起点释放队列线程 ⇒ 第二个任务在第一个任务完成前即开跑（顺序失效）；
  2. 单线程 Executor + 任务内 `runBlocking` 持有线程 ⇒ 第二个任务必须等第一个完成（顺序保持）；
  3. 读写删交错入队（写 → 读 → 删 → 读）⇒ 读到「写后新值」「删后空」，队列序即读写序。
- 结论：**固化 §3.1/R2 的实现方式为「单线程 Executor + 任务内 `runBlocking`」**，排除 `asCoroutineDispatcher()`。

产出 3 — 主线程 DB 命中基线（2026-10-14 采集，**方法已被证伪，待换方法重采**）：

- 首次走查无效：设备上装的 APK 是 07:35 的旧构建（`base.apk` md5 `3f3725b2…`，dex 内无 `installStrictMode`），不含 StrictMode，故日志为空。已重装本次 debug 构建（md5 `073858989b…`，与 `app/build/outputs/apk/debug/AVBox_debug.apk` 一致）后重采。
- 实测结果（设备 = `emulator-5554`）：
  - 冷启动（force-stop → `am start MainActivity`）：**12 条** DiskReadViolation，**Room 命中 0**。分布：`FileUtils.repairBogusNativeLibs` ×4、`KV.init ← App.initParams` ×2、`OkGoHelper.initDnsOverHttps` ×1、`App.onCreate` ×1、`VodImages.init` ×1、`FileUtils.cleanPlayerCache`/`getCacheDir` ×2、`FileUtils.getFilePath ← AppBootstrap.start` ×1。
  - 进详情（点卡片 → `DetailActivity`）：**5 条** DiskReadViolation，**Room 命中 0**；栈全部落在 `com.github.catvod.spider.merge.*` 的 `onActivityResume`。
- **关键反证**：同一时刻日志有 `echo-detail-open collect=false key=咕噜 id=983609`（tid == pid，主线程），证明 `DetailViewModel.applyTarget → loadDetail:318/319` 确实执行了 `collectRepository.isVodCollect` + `followRepository.find`；而这两个方法在 `RoomCollectRepository:31-34` / `RoomFollowRepository:24-25` 内是**真实 DAO 查询**。即：**主线程确实查了 Room，StrictMode 却一条都没报**。
- 机制：Room 3 + `BundledSQLiteDriver` 的 SQLite 走 JNI 原生实现，而 StrictMode 的磁盘检测挂在 Java 层的 `libcore.io.BlockGuardOs`（对比可见：KV/FileUtils 的命中栈都是 `File.exists → BlockGuardOs.access`）——原生 sqlite 读写对 StrictMode 不可见。全量 logcat 中匹配 `androidx.room3|androidx.sqlite|SQLiteConnection|RoomOpenHelper` 的帧数为 **0**，与此一致。
- 替代 API 核查（`javap` 反编译 `room3-runtime` 3.0.3 AAR）：`RoomDatabase$Builder` **无** `setQueryCallback` / query interceptor（Room 2.x 的该 API 在 room3 已移除），故无法用查询回调自建检测器。
- **结论与建议（待确认后定案）**：§3.2 R3 ② 的「StrictMode 软取证出 Room 命中」不成立，StrictMode 无法为 Room 提供基线。建议 片 0 产出 3 改用**硬门禁探针**：本地临时移除 `AppDataManager.kt:44` 的 `allowMainThreadQueries()` → 同路径重跑 → 从 logcat 收 Room 抛的 `IllegalStateException` 栈（栈顶即精确调用点）→ 与 §2.2 静态盘点对账 → 采完立即还原。StrictMode 的保留用途降级为**非 Room 文件 IO 回归**（KV/FileUtils/爬虫）。
- 基线清单（改用硬门禁探针后回填）：

| #  | 触发场景 | 异常栈顶调用点 | 是否已在 §2.2 清单 |
| -- | ---- | ------- | ------------ |
| 待采 |      |         |              |

**片 0 偏差登记：** §4 片 0 原写「须先支持注入 `CacheRepository`，该断言才可落」，**本片未实施注入缝**。理由：① 断言针对的风险（挂起点释放队列线程）在 DAO 仍为同步时不会出现，注入了也无从触发；② 挂起读通道 `submitAndAwait` 是片 1 交付物（§4 片 1 第 3 条），片 0 提前引入等于越界改生产 API。故顺序验证改为**独立设计断言**（零生产代码改动）；注入缝 + `submitAndAwait` 在片 1 落地后，再补一版「真实 `WatchProgressStore` 队列」的顺序断言。

**片 1 记录（2026-10-14，代码改造完成；构建与单测全绿，待走查）：**

落地清单：

1. **DAO 挂起化**：`VodRecordDao`/`VodCollectDao`/`VodFollowDao` 的非 Flow 方法全部 `suspend`（`observeAll` 系保持 `Flow`）。例外：`CacheDao.getCache` 保留同步（偏差 ①）。
2. **Repository 挂起化**：`HistoryRepository`/`CollectRepository`/`FollowRepository` 凡触达 DB 的方法全部 `suspend`；`currentCid()`（纯 KV、被 Compose 在 `onClick` 内同步消费）保持同步。`CacheRepository` 的 `save`/`delete`/`clearAllProgress` 已 `suspend`，`get` 保留同步（偏差 ①）；`RoomCacheRepository` 的 `toByteArray`/`toObject` 序列化段包 `withContext(Dispatchers.IO)`。
3. **WatchProgressStore 队列**：`vod-progress-writer` 仍为单线程 Executor（未引入 `asCoroutineDispatcher()`）；`submit` 任务类型改为 `suspend () -> Unit`，任务体内以 `runBlocking` 同步等待 suspend DAO（R1 受控例外，任务完成前不释放队列线程）；新增挂起读通道 `suspend fun <T> submitAndAwait(block: suspend () -> T): T`；`clearOwner`/`clearAll` 的清理路径由「调用线程同步直删」改为**入队**（§2.2 F 的 R2 违反项消除）；`awaitWrites()` 标 `@Deprecated`（本片不删）。
4. **低风险调用点迁移（15 处，编译器驱动清零）**：
   - `DetailViewModel`：`loadDetail`/`loadDetailInternal` 的状态读 → 新增 `refreshCollectState()`（IO 读 + 回主线程写，带 `sourceKey`/`vodId` 相等守卫防陈旧回写）；`toggleCollect` 删/插、`saveFollow` 的 `upsert`+`find`（同一 IO 块内先后执行后再回写 `follow.value`）→ `viewModelScope.launch(IO)` + `LOG.e` 兜底；本地翻转与 toast 保持同步以保即时反馈。
   - `MusicPlayerActivity`：`ui.collected` 初始化 → `scope.launch { withContext(IO) {...} }`；`toggleCollect` 删/插 → fire-and-forget。
   - `SimpleSubtitleView.clearSubtitleCache`、`DefaultSubtitleEngine` 下载成功回调的两处 `save`、`PlaybackStarter` 换内容清字幕缓存 → `CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ... }` fire-and-forget（沿用 `PlaybackFetch`/`SubtitleLoader` 既有写法）；`requireNotNull` 断言仍在协程外同步求值，不被吞。
   - `PlayContainerControlListener:58` **无需改动**（`WatchProgressStore.clear` 本就是非阻塞提交）；`VodCardMenu`/`CollectPage`/`FollowingViewModel`/`HistoryViewModel` 原本已在协程/Flow 内，零改动。
   - `HistoryWriter.writeNow` → `runBlocking`（偏差 ②）。

片 1 偏差登记：  
① **`CacheRepository.get` + `CacheDao.getCache` 未挂起化（保留同步）**。该读的调用点**全部**在片 2/3（`PlaybackProgressTracker:39`、`PreloadCoordinator:226`、`PlayContainerSubtitles:481`）；在片 1 一并挂起会迫使片 1 提前改播放热路径（违反 §4「本片不动任何播放时序」），而让它们编译的唯一替代是主线程 `runBlocking` 桥接（§5 明令禁止）或新增同步 DAO 桥接变体（R1 禁双 API）。故按「切片与调用点对齐」把 `get` 的挂起化连同这 3 个读点留给片 2/3。**代价**：片 1 结束时 `CacheRepository` 处于部分迁移态，R1「`data/` 包外无同步签名能摸到 DB」尚未达成，由片 2/3 收口。**收益**：片 1 零桥接变体、零时序改动。  
② **`HistoryWriter.writeNow` 新增 `runBlocking`** = 第二处受控例外，性质同 WatchProgressStore 队列（宿主是专用非主线程单线程 Executor），非「同步桥接变体」。  
③ **`synchronized` 与 suspend 冲突**：Kotlin 2.4 报「suspension point is inside a critical section」，故把锁内状态操作与锁外 DB 操作拆开——新增 `takeTitleLocked`/`indexEpisodeLocked`（非挂起、锁内），`clearTitleLocked`/`remember` 改为「锁内取状态 → 锁外做 DB」。副作用：DB 删不再持锁（比原实现更安全）；`clearTitleLocked` 内「删索引键」与「删 cache 行」先后与原来相反——因读/写/删已同队列串行，终态等价。  
④ **`clearAll` 索引键遍历**改为先物化 `KV.keys(...)` 列表（原实现边遍历边删键，依赖 `keys()` 返回快照）。

片 1 验证：`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL；单测 **962 用例 / 0 失败 0 错误 / 1 skip（既有）**；`null_assert_gate.py` → PASS。

片 1 待走查（§7 #1/#2/#3/#3b）：详情页收藏/追剧状态显示与切换、音乐页收藏与清进度、字幕缓存清理生效、历史页删单条/全清进度后顺序正确。

**片 2 记录（2026-10-14，代码改造完成；构建与单测全绿，待走查）：**

落地清单：

1. **预载读点**（`PreloadCoordinator.handlePreloadResult`）：改为 `suspend`；原 `awaitWrites()` + 主线程 `cacheRepository.get` 换成 `WatchProgressStore.submitAndAwait { cacheRepository.get(md5) }` —— 读在队列线程完成、主线程只收结果，同时消灭 §2.2 C 表 2 处主线程 awaitWrites 阻塞中的一处。调用方 `PlaybackPreload.kt:34` 的 `collect` lambda 本就是挂起上下文，零改动。
2. **字幕缓存读点**（`PlayContainerSubtitles`）：`cachedPlayPath` 的两处 DB 读（原 `:330` 歌词 / `:372` 字幕）合并为一次 `async(Dispatchers.IO)`，与 `initSubtitleView` 的同步 UI/track 设置**重叠**执行；`initSubtitleView()` 内部异步化（**对外签名不变**，`PlayContainerControlListener.prepared()` 与 `PlayContainer.rebindPlaybackOverlay()` 零改动），新增 `subtitleInitSeq` 代次守卫防陈旧初始化回写；解析出的字幕缓存路径改为**参数传递**（`applySubtitleDecision` → `applyDefaultSubtitle`）而非落字段，避免跨内容读到陈旧值。

片 2 偏差登记（重切片）：

① **`PlayContainer:410 getCastPosition`（投播）与 `PlaybackRetryDelegate:306` / `PlaybackController:101`（换线）未在片 2 做，并入片 3。** 三者都经 `scheduler.getSavedProgress` → `PlaybackProgressTracker.getSavedProgress`；把它挂起化会连带 `AppPlayerView.ProgressSink`、`PlaybackEngine`、`PlaybackController`（含 `:265`/`:652` 两处 Host 实现）、`PlaybackStarter.Host`、`PlaybackRetryDelegate.Host`，以及 `AppPlayerView.startPlay():125`——**最后一项正是片 3 的起播流水线重排**。即「片 2 的投播/换线读点」与「片 3 的签名爆炸半径」是同一件事，无法在片 2 内独立完成。故片 2 只做不经过 `getSavedProgress` 的两项（字幕 / 预载）；**片 3 的范围扩为**：`PlaybackProgressTracker.getSavedProgress` 挂起化 + `CacheRepository.get`/`CacheDao.getCache` 挂起化（兑现片 1 偏差 ①）+ 投播/换线读点 + 起播流水线。  
② **字幕读点未采用「跨调用预读存内存」的原始设计**，改为「同调用内 `async(IO)` 重叠 + 挂起等待」。理由：跨调用预读（更早时点启动、`initSubtitleView` 只读内存变量）在预读未完成时会让 `initSubtitleView` 读到空值 → **字幕缓存被忽略**，直接触发 §7 #5 回归。同调用内重叠无此风险，且同样达成「DB 不在主线程」与「与 track 设置重叠、不额外串行等待」两个目标。若走查发现字幕挂载延迟，再评估跨调用预读 + 完成屏障。

片 2 验证：`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL；单测 **962 用例 / 0 失败 0 错误 / 1 skip（既有）**；`null_assert_gate.py` → PASS。

片 2 待走查（§7 #4/#5）：预载起播位置正确（队列顺序验证）、字幕 track 就绪时路径解析不滞后且字幕正常挂载。

**片 3 记录（2026-10-14，代码改造完成；构建与单测全绿，待走查）：**

落地清单：

1. **`CacheRepository.get` + `CacheDao.getCache` 挂起化**（兑现片 1 偏差 ①）：`get` 改 `suspend`、序列化段包 `withContext(Dispatchers.IO)`。至此四 Repository 中凡触达 DB 的方法（除 `currentCid()` 纯 KV）全部为 `suspend`/`Flow` —— **R1「`data/` 包外无同步签名能摸到 DB」达成**。
2. **`PlaybackProgressTracker.getSavedProgress` 挂起化**：`awaitWrites()` + 同步 `cacheRepository.get` 换成 `WatchProgressStore.submitAndAwait { ... }`（队列线程读 + 队列序保证）。§2.2 C 表两处主线程 `awaitWrites` 阻塞**全部消灭**。
3. **签名爆炸半径（编译器驱动逐点改造）**：`AppPlayerView.ProgressSink.getSavedProgress` → `suspend`；`PlaybackEngine` 的 ProgressSink 实现 → `suspend`；`PlaybackController.getSavedProgress` → `suspend`，其两处 Host 实现（`:265`/`:652`）→ `suspend`；`PlaybackStarter.Host` 与 `PlaybackRetryDelegate.Host` 的 `getSavedProgress` → `suspend`；`PlaybackRetryDelegate` 的 `autoRetry`/`tryNextLineIfEnabled`/`tryNextLine`/`handleResolvePlayUrlTimeout`/`handleResolvePlayUrlFailed`/`handleSwitchLinePlayTimeout` → `suspend`；`PlaybackController` 中 `autoRetry`/`tryNextLineIfEnabled`/`tryNextLine` → `suspend`，`handleResolvePlayUrlTimeout`/`handleResolvePlayUrlFailed`/`handleSwitchLinePlayTimeout` **保持非挂起签名、内部 `scope.launch`**（`PlaybackStarter`/`PlaybackFetch` 调用方零改动）。
4. **起播流水线（唯一真正的重排，按 §4 精确定义）**：`AppPlayerView.startPlay()` 改为「**同步先 `release` + `initPlayer()` + `addDisplay()`** → 挂起读 `getSavedProgress` → `applyStartPosition`（含 ZeroStartGate）→ `startPrepare(false)`」，DB 读与播放器初始化重叠；新增 `startPlaySeq` 代次守卫防陈旧起播回写；`mProgressSink == null` 时保持原语义（不动 `mCurrentPosition`、仍过 ZeroStartGate）。
5. **投播 / 换线读点**：`PlayContainer.getCastPosition` → `suspend`、`showCastDialog` 改 `scope.launch`；`PlaybackRetryDelegate:306` 换线 preProgress 随链挂起。
6. **收口**：删 `WatchProgressStore.awaitWrites()` 及随之失效的成员（`pendingWrites`/`DRAIN_TIMEOUT_MS`/`writerThread`/`CountDownLatch`/`TimeUnit`/`AtomicInteger`）；改写 `skill/avbox-mobile-ui-spec.md` §6.16 契约文字（屏障 → 队列序，并写明"禁止改成 `asCoroutineDispatcher()`"）。

片 3 偏差登记：

① **`PlaybackController.migrateProgressOnLineSwitch` 保持非挂起签名**，内部按 `live > 0 || started.isNullOrEmpty()` 走同步分支、否则 `scope.launch` 挂起读。理由：该函数被 `PlaybackStarter.play()`（中央入口）调用且不消费返回值，挂起化会波及整条 `play()` 链；改为内部异步后调用方零改动。**代价**：`live <= 0 且 started 非空` 这条支路的迁移落盘延后一次 IO 往返 ⇒ 走查 §7 #6 需重点确认。  
② **`PlaybackStarter.goPlayUrl` 由 `bridge.runOnUi(Runnable{...})` 改为 `scope.launch`（`Dispatchers.Main`）**，并在协程首部补 `host.view()` 存活守卫以保持「已摘挂则整段跳过」的旧语义。`runOnUi` 的两种实现（容器侧 `runOnUiThread` 内联 / 服务侧 `main.post` 延迟）本就不一致，现统一为「不内联、派发到主线程」。  
③ **`PlayContainerOverlays.errorWithRetry` 与 `PlaybackController` 超时回调**改为在协程内继续（`autoRetry` 之后的分支）；`retryAfterStartedError()` 仍同步 ⇒ 「先同步重试一次、失败再异步换线」的顺序不变，仅换线分支延后一次派发。  
④ `AppPlayerView`/`PlaybackStarter`/`PlaybackController`/`PlayContainer`/`PlayContainerOverlays` 各新增一个 `CoroutineScope(SupervisorJob() + Dispatchers.Main[.immediate])` 字段，均未随生命周期显式 cancel（与 `PlaybackFetch`/`PlaybackPreload`/`SubtitleLoader` 既有写法一致）。

片 3 验证：`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL；单测 **962 用例 / 0 失败 0 错误 / 1 skip（既有）**；`null_assert_gate.py` → PASS。**R6 自动化护栏**（`ZeroStartGateTest`/`KernelReusePolicyTest`/`PlaybackProgressSamplerTest`/`PlaybackTimesTest`/`PlaybackProgressTest`）全绿。

片 3 待走查（§4 片 3 五条 + §7 #6/#7/#8/#9）：① 删除进度后重进仍从 0 起（ZeroStartGate）；② 正常续播位置正确；③ 换线迁移位置正确；④ 预载接管位置正确；⑤ 起播耗时对照片 0 基线无劣化（重点冷起播首帧）。

**片 4 记录（2026-10-14，代码改造完成；构建与单测全绿；硬门禁已在模拟器实测通过；人眼走查未做）：**

落地清单：

1. **删豁免口**：`data/AppDataManager.kt` 的 `.allowMainThreadQueries()` 已删 ⇒ 硬门禁生效（**R4 达成**）。
2. **防回归门**：新增 `app/src/test/java/com/github/tvbox/osc/data/DataLayerSuspendContractTest.kt`（8 条）—— 反射断言**四 Repository 接口 + 四张 DAO** 中，除「返回 `Flow` 的方法」与 `currentCid()` 外，其余方法的末参必须是 `kotlin.coroutines.Continuation`（即 `suspend`）。覆盖 DAO 层是必要的：DAO 若回退成同步方法，Repository 的 `suspend` 函数调用它会**阻塞调用线程**（若调用方是主线程 ⇒ 等于主线程查库）。
3. **StrictMode 常驻**：片 0 的 debug 守卫实现保留（`base/App.kt`）。
4. **旧口径改写**：`avbox-kotlin-migration-spec.md` §5 两条（主线程查库 / `withContext` 限制）、`avbox-playback-service-spec.md` 线程模型表两条 + §7.3 承接面注记、`refactor-plan-20261005.md` D10（标注硬约束作废并指向本计划）、`avbox-mobile-ui-spec.md` §6.16（片 3 已改）。
5. **硬门禁实测（设备 `emulator-5554`）**：装 debug 构建（md5 与本地一致）→ 冷启动 → 首页点卡片 → 列表 → 进详情 → 收藏 / 追剧 → 历史页「清空历史」→ 收藏页。全程 `Cannot access database on the main thread` = **0**、`FATAL EXCEPTION` = **0**。关键证据：
   - **详情页**：`echo-detail-open`（tid == pid，主线程）证明 `loadDetail` 已执行 —— 正是原 §2.2 A 的两处主线程 Room 读点，零异常；
   - **收藏**：详情页点收藏后，收藏页读到该条目 ⇒ 异步写入落库 + Flow 面刷新正确；
   - **历史页清空**：`echo-progress clear-all titles=2 eps=2 stale=0`（在队列线程执行，tid ≠ pid）⇒ 页面转为「暂无观看历史」，§2.2 F 的入队改造端到端生效。
   - **范围说明**：这**不能**替代人眼走查（起播 / 换线 / 预载 / 字幕 / 音乐页未覆盖，且本机是模拟器非目标 TV 交互面），但已把「主线程零 DB」这条硬门禁从推理升级为**实测**。
6. **StrictMode 复测**：冷启动 12 条 DiskReadViolation，全部为非 Room 文件 IO（`KV.init` ×2 / `FileUtils.*` ×5 / `OkGoHelper.initDnsOverHttps` / `VodImages.init` / `App.onCreate` / `AppBootstrap.start`），与片 0 基线一致；Room 命中仍为 0（StrictMode 对 Room 不可见，见片 0 产出 3）。

片 4 偏差登记：

① **本文件 status 未改"已完成"**：§4 片 4 收尾要求改"已完成"，但 §7 的 **11 条人眼走查全部未做**（片 1×4、片 2×2、片 3×5）。代码改造与硬门禁已闭合，人眼走查留待真交互面完成后收尾。  
② **`skill/history/features.md` 归档未做**：该文件当前带着**非本次工作**的未提交改动（16 行），此时追加会与既有改动混在同一 diff，故暂缓并登记。

片 4 验证：`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL；单测 **970 用例 / 0 失败 0 错误 / 1 skip（既有）**（较片 3 的 962 增加 8 条 = 新增的数据层挂起契约测试）；`null_assert_gate.py` → PASS。

**结构化核查（2026-10-14，回答"现在还有没有主线程查 Room"）：**

- 全仓唯一 Room 实例：`data/AppDataBase`，仅由 `data/AppDataManager` 构建（builder 已无 `allowMainThreadQueries`）。
- `AppDataManager.get()` 仅出现在 `data/AppGraph.kt`（4 处，装配 DAO）⇒ `data/` 包外零 DAO 直取。
- 四张 DAO 的非 `suspend` 方法**只剩 `observeAll` 系（返回 `Flow`）**；四 Repository 接口的非 `suspend` 方法只剩 `observeVodRecords`/`observeAll`（`Flow`）与 `currentCid()`（纯 KV）。上述由 `DataLayerSuspendContractTest` 机器化锁定。
- 机制：所有 DAO 访问都是 `suspend`，Room 生成代码经 `CoroutinesRoom` 派发到自己的查询/事务执行器 ⇒ **即使从主线程调用，查询也不在主线程执行**。
- 另一处 SQLite（**非 Room**）：`player/engine/PlayerCache` 的 media3 `SimpleCache` + `StandaloneDatabaseProvider`，调用点只有 `MediaSources.kt:109` 与 `PreloadManagerHolder.kt:189`（均在播放/引擎路径，非启动主线程路径）。本计划不覆盖它。
- 残余的非 Room 主线程磁盘 IO（StrictMode 实测）：`FileUtils.*`、`KV.init`、`OkGoHelper.initDnsOverHttps`、`VodImages.init`、`AppBootstrap.start`、`MainScreenKt`、`DetailActivity/BaseActivity.onResume`、爬虫 `onActivityResumed`。**与本计划范围无关**，留档 —— 完整全景（含"被显式放行"的开关与静态盘点的解析/调度项）见 **§8**。

**开放问题：**

1. **三表 `@Index`**：与线程化正交但同属数据面健康；改 schema 需 version 迁移。建议本计划完成后再单独立项，用 StrictMode 实测数据决定是否值得（历史上限 100 行、进度表主键查，预期收益有限）。
2. **`CacheRepository.get` 的 `Any?` 弱类型签名**：序列化对象改强类型/JSON 是更深的重构，本计划不碰，留档。
3. **HistoryWriter 是否并入统一队列**：现两通道职责清晰（历史 vs 进度），合并无收益，维持分离。
4. **R1 与 R2 的边界（已在 §3.2 定案）**：进度面内部以 `runBlocking` 作为 R1 受控例外；若片 0 的顺序断言发现该方案仍不成立，再评估引入显式同步 DAO 通道（届时须重新界定 R1 的"禁双 API"边界）。
5. **非 Room 的主线程违规（2026-10-14 追加盘点，见 §8）**：本计划只收口了 Room 一族。§8 记录了放行开关（`App.kt:76-85` 的 StrictMode 配置本身就是一份放行清单）、规范批准的阻塞点，以及 EPG 主线程解析 / `PartitionListActivity` 组合期 Gson / `PlayerCache` 无门禁 SQLite / Detail-Search 调度不一致这四项**待单独立项**的问题。

# 7. 走查清单汇总（发布判据）

| #  | 场景             | 判据                                                                           | 所属片 |
| -- | -------------- | ---------------------------------------------------------------------------- | --- |
| 1  | 详情页收藏/追剧       | 状态显示正确、切换即时反馈、刷新无错配                                                          | 片 1 |
| 2  | 音乐页收藏/清进度      | 同上 + 重进续播正确                                                                  | 片 1 |
| 3  | 字幕缓存清理         | 清理后重启不再命中旧缓存                                                                 | 片 1 |
| 3b | 历史页删除单条 / 全清进度 | 删库入队后顺序正确：后续起播读不到旧进度、不被残留覆盖                                                  | 片 1 |
| 4  | 预载起播           | 预载集起播位置正确（队列顺序验证）                                                            | 片 2 |
| 5  | 字幕 track 就绪    | 路径解析不滞后、字幕正常挂载                                                               | 片 2 |
| 6  | 自动换线           | 续播位置正确、迁移不丢进度                                                                | 片 2 |
| 7  | 删进度重进          | 从 0 起（ZeroStartGate 4 条判据）                                                   | 片 3 |
| 8  | 常规续播/切集        | 位置正确、进度条不回缩（双入口判据）                                                           | 片 3 |
| 9  | 投播             | 起播位置正确                                                                       | 片 2 |
| 10 | 删豁免口后全路径       | 主线程零 DB：Room 不再抛 `IllegalStateException`（硬门禁）＋ StrictMode 无 Room 命中，对照片 0 基线 | 片 4 |

# 8. 附录：主线程违规全景（2026-10-14 会话追加盘点）

> **范围声明**：本计划（片 0–4）只收口「主线程查 Room」一族。本节记录的是**同一天做的全仓主线程盘点**，覆盖 Room 之外的所有类别 —— 用于回答两个问题：① 哪些不该在主线程的行为是**被显式放行**的；② 还有哪些**仍在主线程**。本节内容是**只读盘点，未改任何代码**，也不属于片 0–4 的完成判据。

## 8.1 唯一的放行开关：StrictMode 的配置本身就是一份放行清单

全仓唯一的"主线程违规检测装置"是 `base/App.kt:76-85` 的 `installStrictMode()`。它的**写法**决定了放行了什么：

| 写法                                           | 放行了什么                                                                                                                                  |
| -------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `if (!BuildConfig.DEBUG) return`             | **release 包完全不装策略**，零检测                                                                                                                |
| 只 `detectDiskReads()` / `detectDiskWrites()` | 网络、`detectCustomSlowCalls`、`detectUnbufferedIo`、`detectResourceMismatches` 一律不检测。注意 `setThreadPolicy` 是**替换语义（不是叠加）**：装上去的那条就是主线程的全部规则 |
| 只 `penaltyLog()`                             | 命中也**只打日志**，不是 `penaltyDeath` / `penaltyFlashScreen` ⇒ 记录但放行                                                                           |
| 全仓无 `StrictMode.setVmPolicy` 调用              | Activity 泄漏、SQLiteCursor 泄漏、Closable 泄漏、BroadcastReceiver 注册泄漏、`file://` 暴露全部不检测                                                       |

⇒ 现状是：**主线程只有磁盘读写会被看到，且看到了也不拦**。

## 8.2 已从放行清单移除（别再按旧文档判断）

- Room `allowMainThreadQueries()` **已删**（`data/AppDataManager.kt` 的 builder 内确认无）⇒ 主线程查库现在是 `IllegalStateException` 硬门禁，这是片 4 的完成判定（§3.2 R4）。
- **旧口径文字未清理，容易误导**（后续立项时顺手改）：`skill/avbox-kotlin-migration-spec.md:122`、`skill/review/refactor-plan-20261005.md:88/142/408`、`skill/history/features.md:421` 仍写着「`allowMainThreadQueries` 主线程查库是显式设计」。`avbox-playback-service-spec.md` § 已随本计划改写，可对照其措辞。

## 8.3 规范点名批准的阻塞点（受控例外，不算违规，勿"顺手修"）

| 位置                                        | 行为                       | 为什么留                                                                                            |
| ----------------------------------------- | ------------------------ | ----------------------------------------------------------------------------------------------- |
| `data/WatchProgressStore.kt:38`           | 队列任务内 `runBlocking`      | 宿主是 `vod-progress-writer` 专用单线程 Executor，靠"占住线程"保证顺序（§3.1 R2；**禁止**改 `asCoroutineDispatcher()`） |
| `data/HistoryWriter.kt:23`                | `runBlocking`            | 同上，专用非主线程单线程宿主                                                                                  |
| `util/net/Http.kt` `getSync`              | 阻塞同步网络出口                 | R10 明令「N4 不许改成 `runBlocking` 桥接」；调用点 `crawler/JarLoader.kt:342`、`crawler/JsLoader.kt:115`       |
| `crawler/js/Async.kt:96`                  | `CountDownLatch.await()` | QuickJS 线程亲和，超时不能 cancel                                                                        |
| `util/thunder/Thunder.kt:197/269/302/341` | `Thread.sleep`           | 都在自建 `Thread.run()` 里，**非主线程，不是问题**                                                             |
| 播放引擎 / 视图 / 控制器                           | 强制主线程 `main.post`        | ExoPlayer 契约，非违规                                                                                |

**卡口**：全仓 `app/src/main` 里 `runBlocking` 只有上述 2 处（`skill/avbox-okgo-removal-spec.md` 的 grep 卡口进过验收）。新增第三处应视为回归。

## 8.4 仍在主线程、且不该在（待单独立项）

### A. 主线程磁盘 IO（StrictMode 实测命中，置信度最高）

> **2026-10-14 整理（两处修订，本节已成为 A 组的权威基线表）**：
>
> 1. **执行范围、判据、批次与回退点单独立项，见 §8.10**（范围 = 本表 **A1–A7**，A7 为已达标对照项）。
> 2. **本表已按 §8.9 实测修订**：原表 5 条漏掉了**最大单项 `OkGoHelper.init`（实测 34–52ms，占 onCreate 主线程 50–54ms 的 2/3）**，且 `AppBootstrap.start` 只写了"文件自检"、实际是三步；现补为 A1/A2，并把 `EpgUtil.init` 标为已达标（A7）。原表内容与本次修订的差异见下。

| 项 | 位置 | 行为 |
| --- | --- | --- |
| A1 | `base/App.kt:71` `OkGoHelper.init()` | 含 `initDnsOverHttps`；**实测 34–52ms，onCreate 主线程最大单项**（§8.9）。⚠️ 处置是"内部拆分 + lazy / 真并行"，**不是整段挪后台**（§8.10.3） |
| A2 | `ui/page/AppBootstrap.kt:53-55` `start()` 三步 | `repairBogusNativeLibs()`（文件自检）、`BootGuard.disableBootLoopingSource()`、`ControlManager.get().startServer()`；由 `MainScreen.kt:102` 的 `LaunchedEffect` 触发 ⇒ **Main dispatcher 上做文件自检 + 起服务**。三步须**整体**挪 IO。**✅ 2026-10-14 已落地**（§8.10.7）：整体挪入 `scope.launch`（IO），三步仍串行且仍在 `startInit` 之前 |
| A3 | `base/App.kt:36` `attachBaseContext` | `KV.init`（MMKV 初始化）；量级预期 <5ms，待 P-1 数据结案 |
| A4 | `base/App.kt:75-95` | `ControlManager.init`、`AppDataManager.init`、`AutoSize`、`PlayerCache.setSharedCacheSizeBytes`、`QuickJSLoader.init`（实测 5–10ms）、`VodImages.init`（已实测命中 DiskRead）逐项初始化 —— 按档位分别 lazy / 真并行 / 跳过 |
| A5 | `base/App.kt:96` | `FileUtils.cleanPlayerCache()` 递归删目录 —— 对照**下一行** `Thread(FileUtils::purgeExoCacheIfPending)` 特意起线程，是显式的取舍。**✅ 2026-10-14 已落地**（§8.10.9）：实测缓存有内容时 5–12ms（空目录 1–2ms），已挪进 `exo-cache-boot` 线程；三项前置验证（Thunder 懒加载 / 无写盘窗口 / 删除范围不相交）全部通过 |
| A6 | `BaseActivity.kt:102` / `DetailActivity.onResume` | 每次回前台主线程 `KV.get`；**稳态路径（有渲染在等）**，是 A 组里优先级最高的一类。**✅ 2026-10-14 实测：全部 <5ms（稳态 0.3–1.6ms / 冷启首读 4.03ms）⇒ 判"不动"**（§8.10.7） |
| A7 | `base/App.kt:73` `util/EpgUtil.init` | ✅ **已是目标形态**（`EpgUtil.kt:13/25` 后台单线程加载 + `:19` `@Volatile emptyMap()` 兜底），实测 0–1ms。作为反例留档：**勿改 lazy**（§8.8 风险 2） |

> **2026-10-14 用户裁决：原第 6 条「爬虫 `onActivityResumed` 主线程」从本表删除** —— 行为发生在混淆 jar（`com.github.catvod.spider.merge.*`）内部，代码库侧不可修，按现状接受、不列入立项范围。实测命中链路为 `Application.dispatchActivityResumed → Z.b.onActivityResumed → m.k.d → getCacheDir`。
>
> **2026-10-14 追加裁决：P-1 埋点不覆盖爬虫 jar** —— 埋点范围只到 App 侧（onCreate / AppBootstrap / onResume），不因"可观测"把该条重新拉回范围（见 §8.10.2）。

### B. 主线程解析 / 重计算（本轮静态读码确认，是主要漏网区）

| #  | 位置                                                       | 行为                                                                                                                                                                              | 备注                                                                                         |
| -- | -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| B1 | `ui/activity/LiveEpgController.kt:146-152` → `:195/:197` | `response.body.string()` 在 OkHttp 回调线程（这步对），但随后 `mHandler.post { onEpgRequestResponse(body, …) }` 把**整包 EPG 文本 post 回主线程**，`:195/:197` 在**主线程**跑 `parseXmlEpg` / `parseJsonEpg` | xmltv 是全天节目单，几百 KB 量级。**与已修的「okgo 回调主线程解析」（`avbox-okgo-removal-spec.md` §1.3 E5）同族，这批漏改了** |
| B2 | `ui/activity/PartitionListActivity.kt:107` 与 `:152-159`  | `:107` 主线程序列化整个搜索结果 `Gson().toJson(videos)` 塞 Intent；`:152-159` 又用 `remember(videosJson) { Gson().fromJson<List<Movie.Video>>(…) }` 在**组合期**反序列化整列表                             | 既占主线程，又违 Compose「组合期不做重活」约定                                                                |
| B3 | `util/EpgUtil.kt:24-44`                                  | 主线程 `assets.open("epg_data.json")` 全文读取 + Gson 解析 + 建 HashMap；由 `App.onCreate:47` 同步调用                                                                                          | 启动路径，实测命中。⚠️ 处置方式**不能只做 lazy**，见 §8.8                                                      |
| B4 | `player/engine/PlayerCache.kt:29-33`                     | 首次 `getSharedCache()` 构造 `SimpleCache` + `StandaloneDatabaseProvider` ⇒ **非 Room 的 SQLite 磁盘 IO**；调用点 `MediaSources.kt:109`、`PreloadManagerHolder.kt:189`                       | 在播放路径上，**既无 `allowMainThreadQueries` 类门禁，也无 suspend 封装**（§6.3 已留档"本计划不覆盖"）                 |

### C. 同族路径调度不一致（最易漏看）

| 位置                                       | 现状                                                                                          |
| ---------------------------------------- | ------------------------------------------------------------------------------------------- |
| `ui/activity/DetailViewModel.kt:500-511` | 搜索直接挂 `viewModelScope`（`Main.immediate`）发 `searchCaller.getSearch`，**未包 `withContext(IO)`** |
| `ui/activity/SearchViewModel.kt:249-254` | 完全同款逻辑，**包了** `withContext(Dispatchers.IO)`                                                 |

⇒ 一半对一半，建议对齐（改哪边都行，但必须同形）。

### D. 主线程同步 KV（MMKV）读写

- `KV.get/put` 遍布 UI 点击与 `onResume`（如 `BaseActivity.kt:102`）。单次成本低，属"被允许"的量级，但仍是同步磁盘 mmap。
- **`skill/history/features.md:3747` 记录的未做项**：`PlaybackProgress.flush()` 的 MMKV 读改写仍在**调用线程**（历史页快照紧接着要读它，异步化会读到旧百分比）⇒ 有意保留，勿当 bug 修。

## 8.5 已合规对照（勿误伤）

| 位置                                               | 已做对的地方                                                                    |
| ------------------------------------------------ | ------------------------------------------------------------------------- |
| `ui/page/SettingsPage.kt:131` / `:143`           | 缓存大小递归计算（`FileUtils.getCacheSize()`）包了 `withContext(Dispatchers.IO)`      |
| `data/HistoryWriter.kt`                          | 历史落库（整剧 JSON 序列化 + SQLite 提交）已挪出主线程（2026-09-28 条目）                        |
| `util/net/Http.kt`                               | 响应体读取已挪 IO（单测 `get_readsResponseBodyOffCallerThread` 锁定）                  |
| `ui/activity/DetailViewModel.kt:330/810/819/835` | 续播记录读取等已用 `viewModelScope(Dispatchers.IO)`                                |
| `subtitle/DefaultSubtitleEngine.kt:258`          | `mWorkHandler` 挂 main looper，但每 100ms 只做 `SubtitleFinder.find` 内存二分，成本可忽略 |

## 8.6 决策口径：不是"该不该挪"，是「先量后动 + 分类处置」

**三问判据**（回答"这个主线程行为该不该迁移"）：

1. 是否在**用户可见路径**（有渲染/交互在等）？
2. 延迟是否**可预测**？
3. 挪走要付多少**复杂度**（竞态 / loading 态 / 超时兜底）？

**冷启动 vs 稳态 —— 两回事，解法不同：**

- **冷启动期间没有渲染在跑**，主线程阻塞 = 启动变慢，但**不掉帧**。所以冷启动磁盘 IO 是"启动耗时"问题，不是"卡顿"问题。
- ⇒ **冷启动的正确答案是「延迟（lazy）/ 真并行 / 跳过」，不是「挪后台」**。丢进线程池后如果后面立刻等着用它，只是把阻塞换了个地方，总时长一毫秒没少。只有"能 lazy、能真并行、能跳过"才真正省时间。
- **稳态路径（onResume / 点击 / 起播帧 / 组合期）才是真正该挪后台的**，因为那里有渲染在等。

**阈值（先量后动的裁决标准）：**

| 单步耗时        | 场景      | 处置                  |
| ----------- | ------- | ------------------- |
| > 16 ms（一帧） | 在用户可见路径 | **必挪**              |
| > 100 ms    | 仅在启动路径  | 延迟 / 并行 / 跳过（不是挪后台） |
| < 5 ms      | 任意      | **不动**              |

**⚠️ 不许凭 StrictMode 命中条数决策** —— 它只报"发生了"，不报"贵不贵"。最低成本的量化手段：给 `App.onCreate` 各步与起播路径套 `Trace.beginSection("boot:xxx")`，Perfetto 抓一次冷启动，看真实占比。本节 8.4 的全部条目在此之前都只是**静态推断**。

## 8.7 执行批次

### P0 —— 已完成

| 项    | 内容                                                                                                                     | 状态                                                              |
| ---- | ---------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| P0-1 | 删掉 `base/App.kt` `initParams()` 里重复的 `KV.init(this)`（`attachBaseContext()` 已做 `MMKV.initialize` + `mmkvWithID`，第二次纯冗余） | ✅ 已改，`:app:assembleDebug` **BUILD SUCCESSFUL**（56s）。留一行说明注释。未提交 |

### P1 —— 拿到测量数据后按耗时排序执行（✅ 六项全部闭合 2026-10-14，执行记录见 §8.9）

1. ✅ **测量**（§8.6）：`Trace.beginSection` 埋点 + 冷启动采集。**落地有偏差**：API 35 模拟器 atrace 无 `app` 分类，APP 标记段（ATRACE_TAG_APP）无法经 atrace 打开，实测数值由 `echo-boot` logcat 产出（同名同线程，口径等价）；trace 段保留给真机/标准 Perfetto 流程。
2. ✅ **B4 `PlayerCache`** —— lazy 原已存在，本次补**空闲预热**：`exo-cache-boot` 线程先 `purgeExoCacheIfPending` 再 `warmup`（串行，防预热目录被启动期清理删除）；SimpleCache 构造命名段 `playerCache:buildSimpleCache`。实测预热 5–7ms / 后台线程，`exoplayer_internal.db` 于启动期创建。
3. ✅ **B1 EPG 解析** —— **前序会话已落地**（工作区未提交改动 + `EpgUtilParseTest`）：`parseEpgBody` 在 OkHttp 回调线程解析，仅 post 结果集。
4. ✅ **B3 `EpgUtil.init`** —— **前序会话已落地**：`epg-loader` 单线程执行器后台加载 + `@Volatile emptyMap()` 兜底（正是 §8.8 要求的形态）；实测 `epgUtil` 步 0–1ms。
5. ✅ **B2 `PartitionListActivity` 组合期 Gson** —— 读侧 `searchVideos` 改 `produceState` + IO（先渲染空网格再填充）；写侧 `startForSearch` 序列化挪 IO、完成后回主线程 `startActivity`。
6. ✅ **C Detail / Search 调度对齐** —— 已确认 `SearchLoader.getSearch` 全程 `withContext(IO)`（网络 `Http.get` 亦 suspend 内部切 IO），选**删冗余**方向：移除 SearchViewModel 外层 `withContext(IO)`（包的是非挂起入队调用，零效果）。**覆盖前序"无害保留"裁决**（§8.9），代码遵守零注释规范。

### P2 —— 最后做（⚠️ **2026-10-14 落地当天即回退 `penaltyDeath`**，结论见 §8.10.11）

1. ⚠️ **§8.1 加强门禁** —— `penaltyLog()` 升级为 debug 期 `penaltyDeath()`，补 `detectNetwork()` + `setVmPolicy`。

   **执行与回退经过**：2026-10-14 在 A 组三批落地后按本条实施（`penaltyDeath` + `detectNetwork` + `setVmPolicy`，记录见 §8.10.10），实测冷启动主线程磁盘 IO 命中归零；**但当天在正常使用路径（点击进入详情页等）反复闪退** ⇒ 同日回退为 `penaltyLog()`（保留新增的检测项与三处缺漏修复），**完整判据、价值评估与回退后的形态见 §8.10.11**。

   **缓行缘由（2026-10-14 复核，已由 §8.10.11 取代）**：字面 `penaltyDeath` 与 §8.4 A 存量直接冲突——debug 包会在 `OkGoHelper.init` 冷启动即崩、每起必崩。「放最后」隐含的前提（存量已清）不成立；P2 应在该单独立项（§8.4 A，**整理后为 7 项，立项内容见 §8.10**）落地后执行。爬虫 jar 条目已按裁决从 A 表删除（jar 内部行为，不可修，无需门禁豁免）。

## 8.8 明确不做 + 两处风险提示

**明确不做（勿"顺手修"）：**

| 项                                       | 为什么不做                                                                                   |
| --------------------------------------- | --------------------------------------------------------------------------------------- |
| 单次 `KV.get` / `KV.put`                  | `util/KV.kt` 走 MMKV（`SINGLE_PROCESS_MODE`），稳态读写是 **mmap 内存级**，µs 量级；异步化引入 loading 态是净亏损。**2026-10-14 实测确认**（§8.10.7 A6）：`onResume` 的 1 次 `KV.get` 稳态 0.3–1.6ms、冷启动首读 4.03ms，全部 <5ms 档 ⇒ **判"不动"，本条从"预期"升级为"实测结论"** |
| 字幕 tick（`DefaultSubtitleEngine.kt:258`） | 每 100 ms 只做 `SubtitleFinder.find` 内存二分                                                  |
| 两处受控 `runBlocking`（§8.3）                | 是 §3.1/R2 的顺序保证手段，改了会破坏进度面顺序                                                            |
| 播放引擎主线程约束                               | ExoPlayer 契约，非违规                                                                        |

> **MMKV 的真实风险不是平均耗时，而是扩容**：mmap 区写满时触发 `ftruncate` + 重新 mmap，偶发几十毫秒。**这个挪后台也解决不了**（挪了只是在后台卡），只能减小单次写入量、避免主线程批量写。所以"主线程 KV 写"该管的是**批量**，不是单次。

**两处风险提示（修正前面的乐观表述）：**

1. **`FileUtils.cleanPlayerCache()`（8.4 A 表 `App.kt:59`）异步化不是零风险。** 原同步语义保证了"清理先于后续 thunder 缓存写入"；改为后台线程后，理论上存在"新写入的缓存被删除"的竞态。**须先确认 Thunder 的初始化时机**（`Thunder.init` 是否为懒加载、是否可能在清理完成前写盘）再改，不能直接并进 `App.kt:60` 那个线程。
   > **✅ 2026-10-14 已按本条完成验证并落地（§8.10.9）**：`Thunder.init` 只在 `parse`/`play` 被调用（启动期无写盘窗口）、删除范围只含内部 `cache/thunder`（与 DoH 的 `dohcache`、Coil 图片缓存、外部 `exo-video-cache` 全不相交）⇒ 该竞态**不成立**，清理已安全挪进 `exo-cache-boot` 线程。本条警告保留为决策依据留档。
2. **`EpgUtil.init` 单纯 lazy 是负收益。** 延迟到首次 `getEpgInfo()` 会把「assets 全文读 + Gson 解析 + 建 HashMap」从启动路径挪到**直播页主线程**（用户可见路径），比现状更糟。必须配：IO 线程一次性加载 + 未就绪时的兜底返回值。

**另一处冗余（与主线程无关，顺手记录）**：`MMKV.initialize` 被调了两次（`App.kt:34` 与已删的 `App.kt:64`），后者已随 P0-1 删除。

## 8.9 P1 执行记录（2026-10-14）

**前置发现**：开工盘点发现 B1/B3 已在前序会话完成（工作区未提交改动 + 新增 `EpgUtilParseTest`），与 §8.4/§8.7 原描述的"现状"不符；以代码为准，P1 本次实际落地项 = 测量埋点、B4、B2、C。

**改动清单**：

| 文件 | 改动 |
| --- | --- |
| `base/App.kt` | 新增 `traceSection()`；onCreate 各步套 `boot:*` 段；`exo-cache-purge` 线程扩为 `exo-cache-boot`（purge → `PlayerCache.warmup` 串行，防预热建好的目录被启动期清理删掉） |
| `player/engine/PlayerCache.kt` | 新增 `warmup()`（失败仅记日志，懒加载兜底路径不变）；SimpleCache 构造套 `playerCache:buildSimpleCache` 段；warmup 加 `echo-playercache-warmup` 耗时日志 |
| `ui/activity/PartitionListActivity.kt` | `searchVideos` 改 `produceState` + `Dispatchers.IO`（组合期零重活，先渲染空网格再填充）；`startForSearch` 整表序列化挪 IO、完成后回主线程 `startActivity` |
| `ui/activity/SearchViewModel.kt` | 删除 `getSearch` 外层冗余 `withContext(IO)`（包的是非挂起入队调用）。**覆盖前序会话"无害保留"裁决**：§8.7 原文"改哪边都行，但必须同形"，删包裹后两处调用点真正同形（均靠 SearchLoader 内部调度）；行为零变化 |
| `ui/activity/DetailViewModel.kt` | 搜索调用点保持原样（与 SearchViewModel 删后形态一致） |
| `.codebuddy/tools/parse_atrace_sections.py` | 新增：atrace 文本 dump 的段耗时解析工具（B/E 事件配对） |

**埋点门控（2026-10-14 追加裁决）**：`bootMark`/`traceSection`/`echo-playercache-warmup` 均已用 `BuildConfig.DEBUG` 门控——release 零日志零成本（`BuildConfig.DEBUG` 为编译期常量，release 分支被常量折叠消除），debug 行为不变（重装机冷启动冒烟复验：echo-boot/echo-playercache-warmup 照常输出）。

**冷启动实测（emulator-5554 / API 35，debug 构建，两轮，数值稳定）**：

- onCreate 主线程合计 ≈ **50–54ms**。分布：`okGoHelper` 34–52ms（最大步，不在 P1 清单，留档）；`epgUtil` 0–1ms（**B3 实证生效**）；`quickJSLoader` 5–10ms；`initParams` 5–6ms；其余 ≤2ms。
- `echo-playercache-warmup dt=7ms / 5ms`，日志线程号 ≠ pid ⇒ **B4 预热在后台线程完成**，SimpleCache 构造成本（SQLite 打开 + 目录扫描）已离开首次起播帧。
- StrictMode：DiskReadViolation 共 10 条，栈顶全为已知非 Room 项（`repairBogusNativeLibs` / `MainScreenKt` / `AppBootstrap.start` / `cleanPlayerCache` / `VodImages.init` / `OkGoHelper`），与片 4 基线一致；**零崩溃、零 Room 主线程异常**。

**工具链坑（留档）**：

- API 35 模拟器 `atrace --list_categories` **无 `app` 分类**，`android.os.Trace`（ATRACE_TAG_APP = 0x20）段经 atrace 打不开；手动把 0x20 OR 进 `debug.atrace.tags.enableflags` 亦无效（疑 zygote 缓存进程内 tag 快照）。`boot:*` 段保留，真机/标准 Perfetto 流程可用。
- `run-as` 无法列外部存储（FUSE 拦截）；验证 SimpleCache 建库改查内部 `databases/exoplayer_internal.db`（mtime = 启动期，实证预热路径执行）。

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest` → **974 用例 / 0 失败 / 1 skip**（既有）；`null_assert_gate.py` → PASS。

**待走查（不阻塞登记，并入既有 11 条人眼走查批次）**：B2 两条路径（搜索结果 → 跳分区列表：空网格 → 填充、返回键）、C 的详情页聚合搜索。建议换源或真机（模拟器咕噜源撞 Cloudflare）。

## 8.10 §8.4 A 单独立项（2026-10-14 整理；**P1 批 + P2 批已落地**，执行记录见 §8.10.7 / §8.10.8）

> **关系声明**：本节兑现 §8.7 P2「缓行」条目里的前置条件——P2 须在 §8.4 A 单独立项落地后执行；该条当时写作"现为 5 项"，本次整理后**范围定为 7 项**，并已按裁决同步修订 §8.4 A 表（现为 A1–A7 权威基线）。本节是**该立项的完整范围 + 判据 + 批次 + 回退点**，不是执行记录：**未动任何代码**。立项编号建议对外称 **A 组（非 Room 主线程收口）**，以免与片 0–4 混淆。
>
> **与 §8.4 的差异（新增 2 项）**：§8.4 A 表原 5 项，本次整理按 §8.9 实测补入 **`OkGoHelper.init` 内部量化**（实测 34–52ms，是最大单项却没进表）与 **`AppBootstrap.start` 的另两步**（原表只写了"文件自检"）。故 A 组实际范围 = **7 项**。

### 8.10.1 处置定性表（对应 §8.4 A 的 A1–A7）

> **基线表在 §8.4 A**（已按本次整理修订为 A1–A7，含实测与 ⛔ 约束）。本节只补「处置定性」一列，避免两处表内容漂移。

| 项 | 位置（基线见 §8.4 A） | 实测/静态现状 | 处置定性 | 不这么做（关键约束） |
| --- | --- | --- | --- | --- |
| A1 | `OkGoHelper.init()`（`App.kt:71`，含 `initDnsOverHttps`） | **34–52ms**（§8.9 实测，onCreate 主线程合计 50–54ms 的 2/3） | 待 P-1 内部拆分后：**lazy / 真并行**（唯一"必须处置"量级，见 §8.6 阈值） | 不许整段 `Thread{}`——后面初始化就要用它，只是把阻塞换个地方，总时长不减 |
| A2 | `AppBootstrap.start()` 三步（`AppBootstrap.kt:53-55`）：`repairBogusNativeLibs` / `BootGuard.disableBootLoopingSource` / `ControlManager.get().startServer()` | 由 `MainScreen.kt:102` 的 `LaunchedEffect` 触发 ⇒ **Main dispatcher 上做文件自检 + 起服务** | 三步**整体**挪 IO（`AppBootstrap.scope` 已是 `Dispatchers.IO`），`start()` 立刻返回，状态机续走 `Loading` | 只挪第一步就收工；须先确认 `ControlManager.startServer()` 失败路径与 `BootGuard` 的顺序耦合 |
| A3 | `base/App.kt:36` `KV.init`（`attachBaseContext`） | MMKV 初始化（mmap + `mmkvWithID`） | 量级预期 <5ms ⇒ **大概率判"不动"**，但须由 P-1 数据说话 | — |
| A4 | `App.kt:75-95` 逐个初始化：`ControlManager` / `AppDataManager` / `AutoSize` / `PlayerCache.setSharedCacheSizeBytes` / `QuickJSLoader` / `VodImages.init` | quickJS 5–10ms（**实测**）；`VodImages.init` 已实测命中 DiskRead；其余待测 | 逐项量级判定 ⇒ lazy / 真并行 / 跳过（跳过冷启动非必需项） | 无脑 `Thread{}`（§8.6：启动路径上"挪后台"不省时间） |
| A5 | `FileUtils.cleanPlayerCache()`（`App.kt:96`） | 递归删目录；**下一行**才起 `exo-cache-boot` 线程做 purge，是显式取舍 | **三类之一：异步（须先验竞态）/ 跳过（改"起播前清"标记）** | ⛔ 不许直接并进 `App.kt:98` 那个线程（§8.8 风险 1：会删掉新写入的 thunder 缓存） |
| A6 | `BaseActivity.kt:102` / `DetailActivity.onResume` 每次回前台主线程 `KV.get` | 稳态路径，**有渲染在等** ⇒ A 组内优先级最高 | 实测 → >16ms 则异步或加**内存缓存/快照** | ⛔ 不许把 `KV.get` 逐个改异步（§8.8 明确不做单次 KV 异步化，会引入 loading 态） |
| A7 | `EpgUtil.init`（`App.kt:73`） | ✅ **已是目标形态**：`epg-loader` 单线程执行器后台加载（`EpgUtil.kt:13/25`）+ `@Volatile emptyMap()` 兜底（`:19`），实测 0–1ms | **无需再做**，作为 A 表反例留档（证明"后台加载 + 未就绪兜底"是可行形态） | ⛔ 不许改成 lazy（§8.8 风险 2：会把 assets 全文读 + Gson 挪到**直播页主线程**，比现状更糟） |
| 范围外 | 爬虫 `onActivityResumed`（混淆 jar `com.github.catvod.spider.merge.*` 内部） | 链路 `Application.dispatchActivityResumed → Z.b.onActivityResumed → m.k.d → getCacheDir` | **已按 2026-10-14 裁决从 A 表删除**（不可修），**且 P-1 埋点不覆盖**（同日追加裁决） | ⛔ 不许为"可观测"把它重新拉回范围；埋点只到 App 侧 |

### 8.10.2 P-1 · 量化（**先做，否则 A 组全部是静态推断**）

§8.4 结尾已自陈「本节 8.4 的全部条目在此之前都只是**静态推断**」，§8.6 又明令「**不许凭 StrictMode 命中条数决策**」。故本立项的第一步不是改代码，是拿数：

> **埋点范围（2026-10-14 用户裁决：不覆盖爬虫 jar）**：只到 App 侧 —— `App.onCreate` / `AppBootstrap.start` / `BaseActivity`·`DetailActivity.onResume`。爬虫 `onActivityResumed`（混淆 jar 内部）不埋点、不修、只在 §8.4 A 留档。

1. **补内部拆分计时**（沿用既有 `bootMark`/`traceSection` 形态，`BuildConfig.DEBUG` 门控，release 零成本）—— ✅ **已全部完成**（P1 批补了 onResume / `AppBootstrap.start`，P2 批补了 `OkGoHelper.init` 内部与 `App.kt:75-95`）：
   - `OkGoHelper.init` 内部（`initDnsOverHttps` 单列）——✅ 见 §8.10.8（最大项 = DoH 客户端 26–28ms，已挪后台）；
   - `AppBootstrap.start` 三步各一段 + `ControlManager.startServer()` —— ✅ 见 §8.10.7；
   - `BaseActivity.onResume` / `DetailActivity.onResume` 的 KV 读合计段 —— ✅ 见 §8.10.7（`Probe.measure`，含 KV 调用计数）；
   - `App.kt:75-95` 已具名的段补**段内拆分** —— ✅ 见 §8.10.8（实测六项只有 `quickJSLoader` 过 2ms）。
   - **采集方式**：`Probe.step(label){}` 直接落 `echo-boot <label> dt=Nms`（DEBUG 门控）。原因：API 35 模拟器 atrace 无 `app` 分类（§8.9 工具链坑），ATRACE 段读不出来；perfetto 需要解析 protobuf，成本高于收益。
2. **采集口径**：沿用 §8.9 的 `echo-boot` logcat（API 35 模拟器 atrace 打不开 `ATRACE_TAG_APP`，见 §8.9 工具链坑），**两轮取稳定值**。
3. **判据（§8.6 阈值，逐项落格）**：

   | 单步耗时 | 场景 | 处置 |
   | --- | --- | --- |
   | > 16 ms | 用户可见路径（`onResume` / 点击 / 起播帧 / 组合期） | **必挪** |
   | > 100 ms | 仅在启动路径 | 延迟 / 真并行 / 跳过（**不是**挪后台） |
   | < 5 ms | 任意 | **不动** |

4. **产出**：一张"步骤 → 主线程耗时 → 档位 → 处置"的表，替换 §8.10.1 里的推测列。**这一步做完之前，冷启动组（A1 / A3 / A4 / A5）的处置都不得拍板**；稳态组（A6 / A2）不依赖 P-1 数据，可并行开工（见 §8.10.3）。

### 8.10.3 批次排序（**稳态优先于冷启动**）

理由（§8.6）：冷启动没有渲染在跑，主线程阻塞 = 启动变慢但**不掉帧**；稳态路径（`onResume` / 点击 / 起播帧 / 组合期）有渲染在等，才是掉帧来源。

**P1 批（稳态，独立于 P-1 结果即可开工）—— ✅ 2026-10-14 已落地，记录见 §8.10.7**

1. ✅ **A6**：`onResume` KV 读 → 量级判定 → **全部样本 <5ms（最差 4.03ms 为冷启动首读，稳态 0.3–1.6ms）⇒ 判"不动"**，登记进 §8.8 "明确不做"。
2. ✅ **A2**：`AppBootstrap.start` 三步整体挪 IO；`start()` 立刻返回，UI 的 `Loading` 态承接（Boot 状态机已有 `Loading/Ready/Error` 三态，无需新增 loading 面）。实测控制服务器（9978）起得来、首页正常出内容。
   - **回退点**：`start()` 恢复为顺序同步三步即回到现状；无 schema / 契约变化。

**P2 批（冷启动，须 P-1 数据支持）—— ✅ 2026-10-14 已落地，记录见 §8.10.8**

3. ✅ **A1**：`OkGoHelper.init` 内部拆分后，**DoH 客户端整体（26–28ms，含 12–15ms 的 100MB `Cache` 目录扫描）挪到 `okgo-doh-init` 后台线程**，主线程只留 2–3ms 的选项名列表（供设置页同步读取）⇒ `okGoHelper` 31–37ms → **20–22ms**，onCreate 合计 ≈55ms → **≈43–45ms**。
4. ✅ **A4**：`App.kt:75-95` 逐项实测 —— `autoSize`/`playerCacheSize`/`vodImages`/`controlManager`/`appDataManager` 全 ≤2ms ⇒ **不动**；`quickJSLoader` 4–8ms（档位"仅启动路径 <100ms" ⇒ 可延迟），全仓无消费点、延迟化零风险，但按最小化修改**本轮不做**、登记为可选后续项。

**P3 批（有风险项，做不完可整体缓）—— ✅ 2026-10-14 已落地，记录见 §8.10.9**

5. ✅ **A5**：`cleanPlayerCache()` 挪进已有 `exo-cache-boot` 线程（**覆盖 §8.8 "不许并进那个线程"的原警告** —— 三项前置验证全部通过：`Thunder.init` 只在 parse/play 被调用、启动期无写盘窗口；删除范围只含 `cache/thunder`，与 DoH 的 `dohcache` / Coil 的图片缓存 / 外部 `exo-video-cache` 全不相交）。实测缓存有 122 条目时该步 5–12ms，已离开首帧路径，且清理仍生效（冷启后 0 文件）。
6. ✅ **A3**：`KV.init` 首次实测 119–140ms，但**口径污染**（进程起步 + 类加载 + 首访 mmap 混在一个窗口里，时间戳交叉核对已证）⇒ **判"不动"**（硬前置、无可并行项、净成本不可测）；登记为后续冷启动优化的下一个量化对象。
7. ✅ **A7**：只补留档 —— 不改为 lazy（§8.8 风险 2），补实测：后台 `epgUtilLoad` 112–125ms，主线程侧 0–1ms。

### 8.10.4 验收口径

- **量化前置**：P-1 表格产出，且每项处置都能对到 §8.6 三档之一。
- **不回归**：第 4 段起播帧时序、稳态帧率不变；`exo-cache-boot`（purge → warmup 串行）不得被 A5 破坏。
- **构建/单测**：`:app:assembleDebug` + `:app:testDebugUnitTest`（基线 **974 用例 / 0 失败 / 1 skip**）+ `null_assert_gate.py` → PASS。
- **冷启动对照**：§8.9 基线 onCreate 主线程 ≈ **50–54ms**，逐项复测。
- **解除 P2 门禁的判据**：A 组落地后，debug 冷启动须**不再出现** `OkGoHelper.init` 等主线程磁盘 IO 触发 `penaltyDeath()` 的路径，方可把 `App.kt:123` 的 `penaltyLog()` 升级为 `penaltyDeath()` 并补 `detectNetwork()` + `setVmPolicy`（§8.7 P2）。

### 8.10.5 范围外（勿"顺手修"）

- §8.3 全部受控例外：两处 `runBlocking`、`Http.getSync`、`Async.kt` 的 `CountDownLatch`、播放引擎主线程约束。
- §8.8 明确不做：单次 `KV.get/put`、字幕 tick（`DefaultSubtitleEngine.kt:258`）、`PlaybackProgress.flush()` 的调用线程读改写。
- §8.4 B/C/D 的已完成项（B1–B4、C 已随 P1 闭合）与 8.4 D 的"有意保留"条目。
- `player/engine/PlayerCache` 的 SimpleCache（B4 已用"空闲预热"处置，本立项不再动）。
- 非 Room 一族的其他主线程解析/调度问题——本立项只管主线程 IO 与调度归位，不重开 §8.4 B/C 的架构建模。

### 8.10.6 开工前裁决记录（2026-10-14，两条均已定案）

1. ✅ **§8.4 A 表已同步修订**（用户裁决：改）—— 补入 A1（`OkGoHelper.init`）与 A2 的另两步、把 `EpgUtil.init` 标为已达标对照（A7），并统一重编号为 A1–A7。**§8.4 A 现为该立项的权威基线表，§8.10.1 只补"处置定性"，避免两处漂移。**
2. ✅ **P-1 埋点不覆盖爬虫 jar**（用户裁决：不覆盖）—— 埋点范围限定 App 侧（`App.onCreate` / `AppBootstrap.start` / `BaseActivity`·`DetailActivity.onResume`）；爬虫 `onActivityResumed` 保持"从 A 表删除、只留档不修"，不因"可观测"被拉回范围。

### 8.10.7 P1 批执行记录（2026-10-14，**已落地**）

**改动清单（5 个文件，未提交）**：

| 文件 | 改动 |
| --- | --- |
| `util/Probe.kt`（新增） | 埋点工具：`measure(label){}`（DEBUG 门控的"本次耗时 + 期间 KV 调用数"），`trace(section){}` / `call(section){}`（ATRACE 段，DEBUG 门控，`inline` 以支持挂起/非局部返回）；`countKvCall()` 由 `KV` 的读路径调用 |
| `base/App.kt` | 私有 `traceSection` 删除，改调 `Probe.trace`（段名不变，行为等价）；顺带删掉因此失效的 `import android.os.Trace`；`bootMark` 保留（它还要维护 `bootLastMs` 游标） |
| `util/KV.kt` | `getInternal` 首行 `Probe.countKvCall()`（DEBUG 门控内即返，release 零成本） |
| `base/BaseActivity.kt` | `onResume` / `onWindowFocusChanged` 整体包 `Probe.measure("<Activity>.onResume\|onWindowFocusChanged")` —— 这两处是 A6 的主线程 KV 读入口 |
| `ui/page/AppBootstrap.kt` | **A2**：`start()` 的三步（`repairBogusNativeLibs` / `BootGuard.disableBootLoopingSource` / `ControlManager.get().startServer()`）挪进 `scope.launch`（`Dispatchers.IO`），各套 `boot:bootstrap*` 段；三步**仍串行、且仍在 `startInit` 之前** ⇒ 语义与改动前一致，只是不再占主线程 |

**A6 实测（MuMuPlayer 模拟器 / API 35 / debug 构建，埋点 `echo-frame`，logcat 口径）**：

| 场景 | dt | 期间 KV 调用数 | 样本数 |
| --- | --- | --- | --- |
| 冷启动首次 `MainActivity.onResume` | **4.03ms**（最差样本） | 1 | 1 |
| 后台→前台 `onResume` 稳态 | 0.31 / 0.39 / 0.42 / 0.51 / **1.64**（峰值）ms | 1 | 5（共 10 样本含冷启） |
| 进详情/搜索页首次 `onResume` | 1.21ms | 1 | 1 |
| `onWindowFocusChanged`（有焦点） | 0.20–0.27ms | 1 | 7 |
| `onWindowFocusChanged`（无焦点） | 0.004–0.009ms | 0 | 6 |

**A6 判定（按 §8.6 三档）**：**全部样本 < 5ms ⇒ 档位"不动"，不迁移**（§8.8 "明确不做"清单追加一项：主线程单次 KV 读）。两条支撑证据：

- 探针的"调用数"列直接对上了：`onResume` 固定 1 次 KV 读（`applyHideStatusBarPref` 的 `KV.get(HIDE_STATUS_BAR)`），`onWindowFocusChanged` 有焦点时 1 次、无焦点时 0 次 —— 说明测到的时间就是这笔读的成本，不是被别的活污染。
- 唯一接近阈值的是冷启动首读 4.03ms：它是**进程首访 MMKV 文件**的一次性成本（mmap 建立 + 页缺页），稳态立刻回落到亚毫秒。把它异步化只能省掉这一次、却要为每次 `onResume` 引入 loading 态，净亏损 —— 与 §8.8 "管批量、不管单次" 的口径一致。

> **口径偏差声明**：数据来自 x86_64 模拟器（宿主 SSD），非目标 TV 的 eMMC。MMKV 稳态是 mmap 内存读、不吃盘速，故档位判定（<5ms）预期在真机成立；但**真机复测仍是 P-1 的收尾项**（§8.10.2）。

**A2 实测（同一设备，冷启动）**：

- `ControlManager` 起的控制服务器**确实起来了**：设备侧 `/proc/net/tcp6` 有 `26FA`（= 9978）的监听套接字，属主 uid 10053（= 应用）⇒ 从 IO 线程 `start()` 成功。
- 启动链路走通：`echo-boot` 13 行（12 步 + `onCreateDone`）全部正常、首页内容正常加载（`echo-site` 106 行）⇒ `Boot.Loading → Ready` 状态机未受影响。
- 主线程 `echo-boot` 里**不再有任何一行是 `AppBootstrap` 的活**（该段在 IO 线程），改动前它由 `MainScreen` 的 `LaunchedEffect` 在主线程串行执行三步。
- 一个副产物修正：改动前 `disableBootLoopingSource()` 与 `repairBogusNativeLibs()` 同在 `startInit()` 之前（保证禁用坏源先于读配置）；本次挪动**保持了这个顺序**，未借机改成并行。

**起点实测（复测 §8.9 基线，确认无回归）**：`okGoHelper` **37ms**（§8.9 记 34–52ms）、`quickJSLoader` 7ms（5–10ms）、`initParams` 5ms（5–6ms）、`epgUtil` 0ms（0–1ms）、其余 ≤2ms；**onCreate 主线程合计 ≈ 55ms**（§8.9 记 50–54ms）⇒ 与基线同量级。

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest` → **BUILD SUCCESSFUL**；单测 **124 suite / 974 用例 / 0 失败 / 0 错误 / 1 跳过**（与 §8.9 基线一致）；`null_assert_gate.py` → **PASS**（`scanned=589 hits=2 baseline_files=2`）；新增文件与改动文件均**零注释**（无 `// i18n: keep` / `// null-assert: keep` 需求）。

**本轮未做（诚实标注）**：

1. **未走查**：A2 的页面级表现（启动 loading 是否正常消失、错误弹窗 `Boot.Error` 路径、换源 `onApiUrlChanged` 路径）只做了冷启动冒烟，未按 §7 清单走查。
2. **A6 的真机复测**未做（模拟器 only）。
3. **P-1 其余埋点**（`OkGoHelper.init` 内部拆分、`App.kt:75-95` 段内拆分）当时未开工——已在 **P2 批补齐**（§8.10.8）。
4. **未提交**：改动留在工作区，未 `git commit`。

### 8.10.8 P2 批执行记录（2026-10-14，**已落地**）

#### A1 · `OkGoHelper.init` 内部拆分（先量后动）

拆分明细（3 轮冷启动稳定，§8.6 口径：主线程耗时）：

| 内部步骤 | 改动前 | 改动后 | 线程 |
| --- | --- | --- | --- |
| `okGoDohSelector`（读选择项 + 建选项名列表） | — | **2–3ms** | 主线程 |
| `okGoDohClient`（DoH 客户端整体：builder + TLS + `Cache` 目录扫描 + `DohIps`） | **26–28ms** | **26–31ms** | **`okgo-doh-init`（已挪出）** |
| ↳ 其中 `okGoDohCache`（100MB `Cache` 构造 = 目录扫描） | 12–15ms | 12–15ms（后台） | 后台 |
| `okGoClients`（默认/免跳转 client 的 builder 配置） | 0ms | **3ms** | 主线程 |
| `okGoExoClient`（Exo 用 client） | 0–1ms | 0–1ms | 主线程 |
| **`okGoHelper` 合计（主线程）** | **31–34ms** | **20–22ms** | — |

**处置（§8.6"启动路径 >100ms ⇒ 延迟/真并行/跳过，不是挪后台"的例外说明）**：DoH 客户端按"**真并行 + 延迟到首次需要**"处置 —— 主线程只保留 2–3ms 的选项名列表（供设置页同步读取），其余整体交给 `okgo-doh-init` 单线程。它比"纯挪后台"更进一步：`dnsOverHttps` 只在网络请求时才被消费（`CustomDns.lookup` / `/dns-query` 路由），而首个网络请求最早要等配置拉取回来，远晚于这 30ms。

**为什么这不是"把阻塞换个地方"**（§8.6 的原话反例）：原实现把 27ms 挡在 `onCreate` 的主线程上、即挡在首帧之前；现在这 27ms 与 `epg-loader` / `exo-cache-boot` 并行，且 `onCreate` 总时长实测少掉 ~11ms（见下）。**净省时间**，不是位移。

**风险处置（逐条）**：

- `dnsOverHttps` 未就绪时 `CustomDns.lookup` 回落 `Dns.SYSTEM` —— 该分支**原本就存在**（`dnsOverHttps` 声明可空、DoH=关闭时旧代码同样赋 null）⇒ 无新增契约。
- `dnsHttpsList` 保持**同步写**（主线程），并发可见性由它原有的 `@Volatile` 保证 ⇒ 设置页不会读到空列表。
- 后台线程整体包 `try/catch(Throwable)` + `LOG.e` ⇒ 失败只表现为"DoH 不生效"，不崩、不影响其他初始化。
- **未改**：`dnsOverHttps` 的类型与空语义、`getDohUrl`/`applyDohConfig` 的行为、`is_doh` 字段（全仓无消费点，未动）。

#### A4 · `App.kt:75-95` 六项段内拆分

| 步骤 | 实测（主线程） | 处置 |
| --- | --- | --- |
| `autoSize` | 0–1ms | **不动**（<5ms） |
| `playerCacheSize`（KV 读 + 设置共享缓存上限） | 0–1ms | **不动** |
| `quickJSLoader` | **4–8ms**（多在 4–6ms） | **不动 + 登记**：加载 .so 是确定成本、只跑一次；档位是"仅启动路径、<100ms" ⇒ 允许延迟/跳过。但全仓无 `QuickJSLoader` 消费点（只有 `App.onCreate` 这一处调用），延迟到首次用 JS 源是**零风险**的后续可选优化；本轮按"最小化修改"不做 |
| `vodImages` | 0–2ms | **不动** |
| `controlManager` / `appDataManager` | 0–1ms | **不动** |
| 附：`EpgUtil` 后台加载（`epgUtilLoad`） | 112–125ms（`epg-loader` 线程） | **不动**：B3 已落地，且实测确认它在后台、主线程侧 `epgUtil` 步 0–1ms |

#### 前后对比（onCreate 主线程，3 轮冷启动）

| 指标 | P2 前（§8.10.7 起点实测） | P2 后 | 变化 |
| --- | --- | --- | --- |
| `okGoHelper` | 31–37ms | **20–22ms** | **−11 ~ −15ms** |
| onCreate 主线程合计 | ≈55ms | **≈43–45ms** | **−10 ~ −12ms** |
| 最大单项 | `okGoHelper`（37ms） | `okGoHelper`（21ms，主线程部分只剩类首次加载 + `okGoClients` 3ms） | — |
| 其余各步 | `initParams` 5ms / `quickJSLoader` 4–8ms / 其余 ≤2ms | 同左（未回归） | — |

#### 验证

- `:app:assembleDebug` + `:app:testDebugUnitTest` → **BUILD SUCCESSFUL**；单测 **124 suite / 974 用例 / 0 失败 / 0 错误 / 1 跳过**（与基线一致）。
- `null_assert_gate.py` → **PASS**（`scanned=589 hits=2 baseline_files=2`）；`i18n_check_keys.py` → `declared=575 referenced=575`、无未声明/未使用。
- 装机冷启动 3 轮 + 首页加载冒烟：首页内容正常（`echo-site` 99 行）、`FATAL/AndroidRuntime/UnknownHost/SSLHandshake/ClassNotFound` **零命中**。
- 新增/改动文件**零注释**、LF、无 BOM。

#### 本轮未做（诚实标注）

1. **DoH 打开态未实测**：模拟器上 DoH 选择项为「关闭」（默认），故 `dnsOverHttps != null` 那条构建分支只做了代码级审查（含 `try/catch` 兜底），**未在真机/模拟器上开 DoH 验证**。
2. **未走查**：设置页 DoH 选项列表、播放链路（DNS 改动影响面）未按 §7 清单走查。
3. **`quickJSLoader` 的延迟化**未做（见 A4 表，登记为可选后续项）。
4. **未提交**：改动留在工作区。

### 8.10.9 P3 批执行记录（2026-10-14，**已落地**）

#### A5 · `cleanPlayerCache()` —— 挪进已有后台线程（**覆盖 §8.8 风险 1 的"不许并进那个线程"**）

**先量后动**：空目录时该步 1–2ms，但**缓存有内容时完全不同** —— 实测在 `cache/thunder` 造 122 个条目（40 个 256KB 文件 + 40 个带文件的子目录，13MB）后，该步变成 **5–12ms**（§8.6 的 `>5ms` 线以上）。这正是 §8.8 当初担心的形态，现在有了数据。

**三项前置验证（全部通过，故原警告的竞态不成立）**：

| 验证 | 结论 | 证据 |
| --- | --- | --- |
| ① `Thunder.init` 是否懒加载、能否在清理完成前写盘 | **懒加载，且启动期绝不可能写盘** | `Thunder.init`（`Thunder.kt:44`，内部在 `:66` 设 `cacheRoot = context.cacheDir + "/thunder"`）只有两个调用点：`parse()`（`:105`）与 `play()` 路径（`:313`）⇒ 都要求用户在播放。启动瞬间没有播放在跑，**不存在"清理前/清理中被写盘"的窗口** |
| ② 是否与新写入竞态 | **不可能** | 启动期唯一会写文件的初始化（`VodImages.init`）只注册 lambda，首次图片加载才建 `pic_http_cache`；`OkGoHelper` 的 DoH 缓存目录名是 `dohcache`（非 `thunder`）；`PlayerCache.warmup` 在**外部**缓存写 `exo-video-cache`（见③）⇒ 全部与 `cache/thunder` 不相交 |
| ③ 删除范围 | **只删 `cache/thunder/` 的内容** | `cleanPlayerCache` 用 `cleanDirectory`（遍历删子项、**保留目录本身**），路径 `getCachePath() + "/thunder/"` = 内部 `cacheDir/thunder`；而 `purgeExoCacheIfPending` 删的是 `getExternalCachePath() + "/exo-video-cache"` = **外部**缓存 ⇒ **两者连盘都不同**，且顺序无关 |

**处置**：`FileUtils.cleanPlayerCache()` 调用点从 `App.onCreate` 主线程移入已有的 `exo-cache-boot` 线程，排在 `purgeExoCacheIfPending()` → `PlayerCache.warmup()` **之后**（保持串行以免并发删目录互相干扰；三个目标目录互不相交，故"清理先于 thunder 写入"的原始语义不受影响）。`cleanPlayerCache()` 函数本身**一字未改**（连它自己的 `try/catch` 都保留）。

**实测确认**：

- 主线程 `echo-boot` 里 **`cleanPlayerCache` 已消失**，`onCreate` 各步不变（含 122 条目的缓存下，`onCreateDone` 前合计 ≈44ms，与空缓存时一致）⇒ **该步的 5–12ms 已离开首帧路径**。
- 清理**仍然生效**：预置 122 条目 → 冷启动后 `find cache/thunder -type f | wc -l` = **0**、`cache/thunder/temp` 子项 = **0**（连跑两轮均为 0）。
- `exo-cache-boot` 线程按预期活着：`echo-playercache-warmup dt=12ms`（tid ≠ pid）。

#### A3 · `KV.init`（`attachBaseContext`）—— 判"不动"

**首次实测**（此前 §8.4 A 表只有静态推断）：`Probe.step("kvInit")` 落 **119 / 132 / 135 / 140ms**。

**但这个数字有测量口径污染，不能直接当"主线程阻塞 119ms"用**。时间戳交叉核对（同一轮冷启动）：

```
12:41:26.370 ActivityManager: Start proc 41896:com.github.avbox.osc   ← 进程 fork
12:41:27.469 TVBox-runtime: echo-boot kvInit dt=119ms                  ← kvInit 结束
12:41:27.554 TVBox-runtime: echo-boot installStrictMode dt=0ms
12:41:29.299 ActivityTaskManager: Displayed MainActivity +2s946ms
```

⇒ 这 119ms 的窗口里装的是**整个进程的起步**（spawn + Application 类加载 + `attachBaseContext` 调用路径 + 首次 MMKV 类加载 + 首访 `avbox_kv` 文件），不是 `KV.init` 自己的净成本。旁证：`initParams`（5 次 KV 调用）实测 5–6ms ⇒ **温态单次 KV 调用 ≈ 1ms**，说明 store 打开后并不慢。

**处置：不动**（按 §8.6）：

1. 它是**硬前置** —— 全仓 `KV.get/put` 都 `requireStore()`，推迟 `KV.init` 就得先改掉这个契约；而 `attachBaseContext` 阶段**没有可并行的兄弟工作**（Application 的前置步骤）。
2. 档位是"仅启动路径"，量级在 100ms 线附近且**无法确定净成本**（见上的口径污染）；冷启动总耗时 2.9s，该步非主因。
3. 更重要的实测事实：**本次 A 组改动后它已成为 `onCreate` 之外的最大单项** —— 虽然仍在总时长里占比不高，但后续若要再动冷启动，这一项是下一个该被严肃量化的对象（登记为后续项，**不在本轮范围**）。

**保留埋点** `Probe.step("kvInit")`（一行，DEBUG 门控）便于后续复核。

#### A7 · `EpgUtil` 留档说明

**不改代码**。目标形态已在 §8.4 A 的 A7 行写明（`epg-loader` 后台单线程 + `@Volatile emptyMap()` 兜底）并实测；本轮补充一条实测数据：**后台加载 `epgUtilLoad` = 112–125ms**，主线程侧 `epgUtil` 步 0–1ms ⇒ 加载确实全在后台、且不阻塞首帧。

#### 验证（P3 批）

- `:app:assembleDebug` + `:app:testDebugUnitTest` → **BUILD SUCCESSFUL**；单测 **124 suite / 974 用例 / 0 失败 / 0 错误 / 1 跳过**。
- `null_assert_gate.py` → **PASS**；改动文件零注释、LF、无 BOM。
- `FileUtils.kt` 最终 **0 改动**（临时埋点已撤，`git diff` 为空）。

#### 本轮未做（诚实标注）

1. **A5 的走查未做**：只验证了"缓存被清空 + 主线程不再有该步"，未按 §7 清单走查迅雷播放链路（真实下载 → 缓存 → 重启清理 → 再播放）。
2. **A5 的收益是"移出首帧路径"而非"省时间"**：这是 §8.6 明确允许的例外（原语义要求清理先于使用），但与 A1 的"真并行"性质不同，故未计入 §8.10.8 的耗时对比表。
3. **A3 的净成本未测得**（受进程起步口径污染），登记为后续项。
4. **未提交**：改动留在工作区。

### 8.10.10 §8.7 P2（StrictMode 门禁加强）执行记录（2026-10-14：**先落地 `penaltyDeath`，同日回退**，裁决见 §8.10.11-A）

**落地前置：把门禁会立刻引爆的存量清干净。** 装 `penaltyDeath` 之前先实测"谁会在冷启动打死 debug 包"，逐个处置：

| # | 触发点 | 类型/成本 | 处置 |
| --- | --- | --- | --- |
| 1 | `VodImages.init`（`App.kt:84`）⇒ `context.getCacheDir()` | DiskRead 3ms | 改**懒解析**：`init` 不再取 `cacheDir`，改由 `picCache()` 在首次真正建 Coil client 时解析（`VodImages.kt`）；`init` 只注册 lambda |
| 2 | `QuickJSLoader.init()`（`App.kt:82`）⇒ `System.loadLibrary` | DiskRead 9–12ms（native .so 查找） | **就地抑制**（见下） |
| 3 | `SpiderLoader` 的 jar 下载回调 `file.exists()`（`SpiderLoader.kt:255`） | DiskRead，在**主线程 Handler** 里 | 改判据：`file == cache` 等价替换 `file != null && file.exists()`（该 file 只可能是 worker 刚下载/复用的 cache，identity 足以判定，零磁盘调用） |

**为什么 #2 用 `StrictMode.allowThreadDiskReads()` 而不是挪线程** —— 这是本轮唯一一个"看起来该挪、实际挪不动"的点：

- `QuickJSLoader` 是**外部 AAR**（`quickjs` 模块只 `api(libs.quickjs.wrapper.*)`，源码不在仓库），无法确认"不 init 就能建 JS context"的契约，故**不敢**像 A1 的 DoH 那样延迟到首次使用；而 `JsSpider` 的报错文案（dex 里实测）是 `"The so library must be initialized before createContext!"` ⇒ 延迟化若猜错就是"JS 源全废"。
- **挪后台线程也没用**：StrictMode 的 ThreadPolicy 是 `ThreadLocal`，**新线程继承当前策略**，`penaltyDeath()` 在后台线程同样会打死进程。实测确认：把 init 放进 `quickjs-loader` 线程后依然 `FATAL EXCEPTION`。
- 故按框架惯例就地抑制：`loadQuickJs()` = `val previous = StrictMode.allowThreadDiskReads()` → `QuickJSLoader.init()` → `finally { StrictMode.setThreadPolicy(previous) }`。这是 Android 官方给"不可避免的一次性系统调用"的标准出口，抑制范围只包 `loadLibrary` 这一句；且 `penaltyLog()` 仍在，将来若这里多出别的读盘，日志里照样看得见。**不改 A4 的档位判定**（原判"4–8ms、不动"仍成立，只是补了门禁豁免）。

**门禁最终形态**（`base/App.kt` `installStrictMode()`，全部 `BuildConfig.DEBUG` 门控；**注：`penaltyDeath()` 当日已回退，见 §8.10.11-A**）：

```kotlin
StrictMode.setThreadPolicy(ThreadPolicy.Builder()
    .detectDiskReads().detectDiskWrites().detectNetwork()
    .penaltyLog().penaltyDeath().build())
StrictMode.setVmPolicy(VmPolicy.Builder()
    .detectLeakedSqlLiteObjects().detectLeakedClosableObjects().detectActivityLeaks()
    .detectFileUriExposure().penaltyLog().build())
```

**两个刻意的取舍（与 §8.7 字面口径的偏差，需登记）**：

1. **`detectCustomSlowCalls()` 未纳入**：实测它会在正常流程里对 OkHttp 的 `buildTrustRootIndex`（`CustomViolation: buildTrustRootIndex`，16–17ms）触发，而 `penaltyDeath` 会因此**每轮启动都打死 debug 包**。它是"慢调用"提示而非正确性门禁，按"debug 包必须可用、日志可读"的原则排除；将来要查慢调用可单独开一次会话临时加回。
2. **`VmPolicy` 只用 `penaltyLog()`**：`detectFileUriExposure()` 若配 `penaltyDeath` 会在 Android 7+ 抛 `FileUriExposedException`；实测已有一条 `LeakedClosableViolation`（`FileInputStream` 未关，finalizer 线程上报 `FileInputStream.finalize`），属既有面、非本轮引入 ⇒ 保持 log 记录、不动 penalty。

**实测验证（MuMuPlayer / API 35 / debug 构建 / 全新安装）—— `penaltyDeath` 生效期间**：

| 场景 | StrictMode 违规 | 进程 |
| --- | --- | --- |
| 冷启动（`penaltyDeath` 生效） | **0**（`installStrictMode` 后到首页渲染完，零 DiskRead/DiskWrite/Network） | 存活 |
| 后台→前台 + 首页点卡片 → 返回 | 1 条 `LeakedClosableViolation`（VM policy，log 级） | 存活（pid 47940） |
| 二次前后台切换 | 同上，无新增 DiskRead | 存活 |

对照：装门前实测冷启动有 **2 条 DiskRead**（正是上表 #1/#2），其中一条会让 debug 包 `FATAL EXCEPTION: main`（`RuntimeException: StrictMode ThreadPolicy violation`）⇒ 冷启动路径确实被清干净了。

> **⚠️ 但"冷启动干净"不等于"存量已清"**：同一开关在**详情页等正常使用路径**上反复闪退 ⇒ 同日回退，**当前代码里没有 `penaltyDeath`**。本节的表格因此只说明"冷启动路径的存量已被清掉"，不代表开关可以常开。完整判据见 §8.10.11-A。

**踩坑登记（比结论更值钱，避免下次重踩）**：

1. **Gradle 增量/构建缓存会把改动吃掉**：本轮多次出现 `compileDebugKotlin FROM-CACHE` / `packageDebug UP-TO-DATE`，而 APK mtime 停在旧时间、设备上跑的是旧字节码，导致"改了代码但现象不变"被误判成"改动无效"。**结论：验证门禁这类全局行为时必须 `.\gradlew.bat clean` + `--no-build-cache` 全量构建，并核对 APK mtime/length 与设备侧 `ls -l /data/app/*/com.github.avbox.osc*/base.apk` 的长度一致**。核对尺寸是本轮唯一可靠的"设备真的在跑新包"证据（`md5sum`/`dumpsys package` 都不给 APK 摘要）。
2. **`dexdump -d ... | grep 'onCreate$lambda$7'` 会先命中 `D8$$SyntheticClass` 包装类**（`App$$ExternalSyntheticLambda*` 的 `invoke()` 里也有同名 invoke-static），据此判断"主线程还在 loadLibrary"会得到**错误结论**。要判断实现体，得看 `App` 类自身的方法体或用 `javap -p -c` 看 class 文件。
3. **`Probe.step` 的注释/日志确实把改动暴露了出来**：本轮正是因为 `echo-boot quickJSLoader` **连续打印两行**，才发现 `App.kt` 里"移到 `loadQuickJs()`"的编辑**残留了旧的 `QuickJSLoader.init()` 直调**（重复代码），而这正是门禁一直被打死的真因 —— 排查过程中曾误判为"设备跑旧包/Gradle 缓存"。**教训：症状持续不变时，先读源文件本身，再怀疑工具链。**

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest`（`--no-build-cache`）→ **BUILD SUCCESSFUL**；单测 **124 suite / 974 用例 / 0 失败 / 0 错误 / 1 跳过**；`null_assert_gate.py` → **PASS**；`i18n_check_keys.py` → `declared=575 referenced=575`、无未声明/未使用；装机冷启动 + 交互冒烟均无 `FATAL`。

**本轮未做（诚实标注）**：

1. **未覆盖的路径**：设置页内部、播放链路、换源、字幕/弹幕、投屏、下载等长尾路径**未逐一走查** ⇒ 这些路径上仍有未清洁的存量违规。**这就是 `penaltyDeath` 被回退的直接原因**（不是"预期代价"，而是不可接受的代价）：常开会让正常使用闪退。要用硬门禁时的正确姿势见 §8.10.11-A —— **单独开一次会话临时打开、走完关键路径再关掉**。
2. **`LeakedClosableViolation` 的 `FileInputStream` 未定位到具体调用点**（finalizer 线程上报，栈里只有 `FileInputStream.finalize`；需抓分配栈），登记为后续项。
3. **A4 的 `quickJSLoader` 仍未离开主线程**（本轮只做了门禁豁免，未挪线程/未延迟化），与 §8.10.8 的登记一致。
4. **未提交**：改动留在工作区。

### 8.10.11 `penaltyDeath` 回退裁决 + 一轮独立回归审查（2026-10-14）

#### A. 为什么回退 `penaltyDeath`（覆盖 §8.7 P2 的字面口径）

`penaltyDeath` 落地当天，在**正常使用路径（点击进入详情页等）反复闪退**。判据不是"感觉"，而是本条自己写的前提：**「放最后」= 存量已清**，而实际只清了**冷启动**一条路径；详情页 / 播放 / 设置 / 换源 / 字幕 / 弹幕 / 投屏 / 下载一律没有验证过。把开关常开，等于把这些路径当碰撞测试场。

**回退形态（当前代码状态）**：

| 项 | 状态 |
| --- | --- |
| `penaltyDeath()` | **已删** |
| `detectDiskReads/detectDiskWrites/detectNetwork` + `penaltyLog` | 保留（仓库原有 + 本轮补充的检测项） |
| `setVmPolicy(...)` | 保留，但只 `penaltyLog` |
| `detectCustomSlowCalls()` | **不纳入** —— 实测它在正常流程对 OkHttp `buildTrustRootIndex`（16–17ms）触发，配上 `penaltyDeath` 就是"每轮启动必崩"；它是慢调用提示，不是正确性门禁 |

**价值评估（不是全盘否定）**：门禁确实逼出了三处真实缺漏（`QuickJSLoader` 的 native 加载 9–12ms、`VodImages.init` 的 `getCacheDir` 3ms、`SpiderLoader` 主线程 `file.exists()`）与 A 组全部量化数据。但**用"闪退"换取这些信息不划算** —— `penaltyLog` 能给出同样的栈，且不打断开发。要用硬门禁时的正确姿势：**单独开一次会话临时打开，走一遍关键路径再关掉**，不作为常驻配置。

#### B. 独立审查发现与处置（本轮改动 + 周边）

审查方式：一份独立子代理逐文件审计 + 一份 QuickJS 时序核查 + 我自己的逐处核对 + 用户真实会话的 logcat 挖取。

| # | 级别 | 发现 | 处置 |
| --- | --- | --- | --- |
| 1 | **BLOCKER** | `Probe.kt` 用了 `ThreadLocal.withInitial` —— 该 API **`since="26"`**（已在 `platforms/android-35/data/api-versions.xml` 核实），而 `minSdk = 24`；核心库脱糖**不**回填它。`Probe` 在 `attachBaseContext`（`App.kt:36`）就被触达 ⇒ **API 24/25 上必抛 `NoSuchMethodError`** | ✅ 已删掉 `ThreadLocal` 与嵌套深度逻辑（`measure` 只有两个调用点、不需支持嵌套），改用"进入时取计数快照、退出时算差" |
| 2 | HIGH | `initDohSelector` 重构时把"越界重置"写成了 if/else —— 跳过循环却仍执行 `dnsHttpsList = list` ⇒ **越界时 DNS 下拉只剩「关闭」**（旧代码会重置索引**并**照常建完整列表） | ✅ 已恢复旧语义：`effective = 0` 后**照样跑完整循环** |
| 3 | MEDIUM | `buildDohClient` 在后台线程**重新** `KV.get(DOH_URL)`，而 `ips` 来自主线程那次读 ⇒ 若两次之间设置被改，会出现"用 N 号配置建 client、却带 M 号 ips" | ✅ 已把 `selector` 作为参数传入，后台线程不再重读 KV |
| 4 | MEDIUM | `Probe.measure` 的嵌套计数用 `addAndGet(-nested)` ⇒ 内层退出会扣掉外层的计数，外层少报、且 `AtomicInteger` 留下**永久残留**（每帧增长） | ✅ 随 #1 一并重构为快照差值；并**移除嵌套支持**（"测量套测量"本就是不该有的用法） |
| 5 | LOW | `BaseActivity` 在 release 下仍要为每次 `onResume` 构造标签（`this::class.java.simpleName` 反射 + 字符串拼接）—— 门控在 `measure` 内部、参数已先求值 | ✅ `measure(owner, label)` 改签名，反射与拼接移到门控之后（只在 debug 执行） |
| 6 | NIT | `VodImages.init(context)` 的 `context` 参数已无用（全仓唯一调用点） | ✅ 移除参数与 `android.content.Context` import |
| 7 | NIT | `okGoDohClient` 标签被外层/内层复用、语义不同，启动耗时序列会误导 | ✅ 内层改名 `okGoDohBuild` |
| 8 | — | `ControlManager.startServer()` 现在跑在 IO 线程，与 `getAddress()` 的懒启动可能并发（`mServer` 非 volatile、`startServer` 无同步、重试循环改共享 `serverPort`） | ⚠️ **登记为既有问题、非本轮引入**：`startServer()` 在此之前**已经**在 `exo-cache-boot` 线程被调用（§8.10.7 P2 批落地），竞态窗口与今天相同；且该窗口内没有任何 `getAddress()` 调用者（全部在配置加载之后）。**真正的缺陷是 `ControlManager` 本身缺少同步**，登记为后续项 |
| 9 | — | `dnsOverHttps` 在 `init()` 返回时仍为 null，首次 DNS 查询会回落 `Dns.SYSTEM`（绕过 DoH 与 `bootstrapDnsHosts` 钉定） | ⚠️ **已知取舍**（§8.10.8 记录为"真并行 + 延迟到首次需要"）。实测背景 DoH 构建 26–32ms，而首个网络请求在配置加载之后（启动后数百 ms）⇒ 窗口为零。**不改** |
| 10 | — | `App.kt:86-90` 的 `cleanPlayerCache()` 挪到 `exo-cache-boot` 且在 `warmup` 之后 | 有意为之，三项前置验证见 §8.10.9；`KV.init` 去重亦为 P0-1 既有改动 |

**审查确认"无回归"的部分**：`SpiderLoader` 的 `file == cache` 等价（`replaceCache` 原样返回 `cache` 参数 ⇒ 恒等于"下载成功"）；`AppBootstrap.start()` 三步顺序保持且 `startInit` 仍在最后；`OkGoHelper.init()` builder 顺序无影响（setter 相互独立，`build()` 与赋值序列未变）；`Probe` 的 release 空操作性质不变。

#### C. 顺带挖出的真实性能问题（既有面，非本轮引入）

审查时挖了**用户真实使用会话**的 logcat（5639 行）：

- **崩溃 0、`Cannot access database on the main thread` 0**（Room 硬门禁实测干净）；违规分布：13ms×5 / 28ms×5 / **134ms×4 / 161ms×1**。
- 大户全部指向同一条链：**`PlaybackStarter.play` → `PlayerSwitchUseCase.stopOther` → `Jianpian.finish()` → `App.getp2p()` → `P2PClass.<clinit>` 加载 native 库（161ms）+ 构造函数 `File.exists()/mkdirs()`、`getExternalCacheDirs`（134ms×4）**，并伴随 `Choreographer: Skipped 90 frames!`。
- **机制**：`Jianpian.finish()` 在**每次起播**都被调用，其第一个动作 `App.getp2p()` 副作用式懒加载 26 个 JNI 方法的 native 库 —— 哪怕播的是无关的普通源也要付这 134–161ms。
- **定性**：A 组没碰 P2P、`App.onCreate` 也没预载它 ⇒ **既有问题**。但它是当前最大单笔主线程开销，**登记为后续项**（修法轮廓：`finish()` 先判断上次地址是否荐片地址，再决定要不要碰 p2p）。

#### D. 本轮验证

- `:app:assembleDebug` + `:app:testDebugUnitTest`（`--no-build-cache`）→ **BUILD SUCCESSFUL**；单测 **124 suite / 974 用例 / 0 失败 / 0 错误 / 1 跳过**；`null_assert_gate.py` → **PASS**。
- 装机冷启动：`echo-boot` 序列完整（`okGoDohBuild dt=32ms` 在后台线程）、控服 9978 正常、`echo-frame kv:MainActivity.onResume dt=4.90ms calls=1`（`measure` 重构后标签与计数正常）⇒ **fatal=0 / DiskRead=0 / Room=0**，进程存活。
- `Probe.kt` 复查：`ThreadLocal` / `withInitial` / `java.util.function` / `stream` / `Optional` **全部 0 命中** ⇒ API 26+ 用法已清除。

#### E. 本轮未做（诚实标注）

1. **API 24/25 真机未验证**：`NoSuchMethodError` 那条是**静态核实**（api-versions.xml + 脱糖 jar 无回填），模拟器是 API 35 ⇒ 未在 24/25 上实跑。修复方式（去掉 `ThreadLocal`）本身不依赖任何 API 等级。
2. **`quickJSLoader` 挪线程仍无法端到端验证**（无 JS 源可播）；时序核查结论为 SAFE（唯一创建点 `JsSpider.kt:363`，触发链全部在 `Application.onCreate` 之后）。
3. **§8.10.8 的 DoH 打开态**、**§7 的 11 条人眼走查**仍未做。
4. **未提交**：改动留在工作区。

#### F. 第二轮独立复查（2026-10-14，单子代理，聚焦上述修复本身）

复查范围：只看修复**有没有修对、有没有修出新问题**。结论：**无 BLOCKER / HIGH / MEDIUM**；逐条判定 Fix A–E 全部成立。

**逐项判定**：

| 修复 | 判定 | 依据 |
| --- | --- | --- |
| A（`ThreadLocal.withInitial` → 快照差值） | ✅ 正确 | 逐个 API 核对 `since` 值：`AtomicInteger` 1、`SystemClock` 1（`elapsedRealtimeNanos` 17）、`Trace` 18、`Class.getSimpleName` 1，**全部 ≤ 24**；`ThreadLocal` 已从源码与字节码中消失。计数只增不减、`measure` 不持有需释放的状态、`finally` 在异常路径也执行 ⇒ **不可能漂移或变负**。且 `measure` 只有 2 个调用点（Activity 生命周期回调），**嵌套不可能发生** |
| B（越界 DoH 列表） | ✅ 等价 | 与工作区 diff 的 `-` 侧（= 原实现）逐项比对：`KV.put` 条件与位置、循环范围、`ips` 选取 `effective == i+1`、`dnsHttpsList` 内容、`catch` 行为 —— **在"范围内 / 0 / 越界 / 空配置 / 抛异常"五种情形下全部一致**；越界时 `getDohUrl` 自校验范围 ⇒ 仍是 `null`，与原实现相同 |
| C（`selector` 参数化） | ✅ 安全 | 所有可达情形都与旧 `getDohUrl(KV.get(DOH_URL,0))` 等价；且在"后台重读拿到新值、`ips` 来自旧数组"这一理论上会错配的情形下**严格更优** |
| D（`measure(owner, label)`） | ✅ release 干净 | 字节码确认：release 分支只有 `block.invoke(); return`，`getSimpleName` 与字符串拼接只存在于 debug 分支。**唯一保留**：调用点每次仍分配一个捕获型 `Function0`（可忽略，但"零分配"只对方法体成立） |
| E（`VodImages.init()` 去参 + `@Volatile`） | ✅ 完整 | 全仓唯一调用点已同步；无残留 import、无 Java/test/python 引用；`AppContextHolder.install`（`App.kt:55`）先于 `VodImages.init`（`App.kt:84`），而 `picCache()` 只能经 Coil 的延迟 fetcher lambda 在首次取图时到达 ⇒ `requireNotNull` 不可达 |

**新发现并已修（1 条 LOW，是我引入的）**：`AppBootstrap.start()` 的 `startInit` 用的是默认参数 `generation = bootGeneration.next()`，而它现在在 `scope.launch` **内部**求值 ⇒ 三步（repair/bootGuard/startServer）期间若用户触发 `retry()`/`switchVodSubscription()`，代次顺序会反转：retry 拿 N、被推迟的首次 init 拿 N+1，于是 `isLatest` 判断会把**强制刷新**丢掉（可能复用 <12h 缓存），或让两者都跑（双份配置加载）。**旧代码是主线程同步三步、先取代次，UI 无法插队。** 修法：把 `bootGeneration.next()` 提到 `launch` 之前的主线程上求值并作参数传入 ⇒ 恢复原时序。（复查确认该窗口实际不可达 —— 调用者全是用户驱动 —— 但按"机制正确"修掉，零风险。）

**另有 2 条 LOW / NIT 登记不改**：

- `Probe` 的 `calls=` 是**进程级差值**，不是按 Activity 归属：`okgo-doh-init` / `exo-cache-boot` / BootGuard 线程在 `onResume` 窗口内的 KV 读也会被算进来 ⇒ 该数字会高估。**登记为口径限制**（探针本来只用来看量级，`onResume` 实测固定 `calls=1` 时未受污染）。
- `SpiderLoader` 的 `file == cache` 依赖 `replaceCache` 返回**同一实例**；若将来有人改成返回别的 `File` 对象，成功路径会静默落进错误/重试分支。**登记为维护注意点**（复查确认当前语义正确，且被移除的主线程 `File.exists()` 成本确实消失）。

**复查无法验证的**：release/R8 产物（本地无 release 构建 ⇒ 无法确认每调用点的 `Function0` 被内联消除、`countKvCall` 被死代码消除）；Lint/NewApi 未跑（只读复查，API 兼容性依赖人工核对 `api-versions.xml`，用的是 android-35 数据，因 `compileSdk = 37` 而本地无 37 平台）。

**修后验证**：`:app:assembleDebug` + `:app:testDebugUnitTest`（`--no-build-cache`）→ **BUILD SUCCESSFUL**；单测 **124 suite / 974 用例 / 0 失败 / 0 错误 / 1 跳过**；`null_assert_gate.py` → **PASS**。




