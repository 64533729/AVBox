---
name: AVBox 播放状态面统一 Spec（三面收敛为单一真源）
status: 全部完成（2026-10-07；步 1–4 已提交，§5 五道门全达成；可选步 5 未做，开放问题④按建议不做）
source: 2026-10-07 用户指令：「为了现代化和可维护性最终应该统一成什么样」「推荐分几个步骤」
---

# 结论摘要

- 现状是**三张面**（不是两张）：旧 int `playState`（真源在 `AppPlayerView`）、新 enum `PlayState`（真源在 `ExoPlayer.stateMachine`）、以及第三轴 `PLAYER_NORMAL/FULL_SCREEN/TINY_SCREEN`（屏幕形态）。前两张是**两个真源**而非"一源两读法"——同一动作在两处各判一次条件，且已具备同屏打架的条件（`LivePlayActivity` 一屏两源）。
- 终态 = **一个真源（`PlayState`）由内核契约持有、只被一个状态机写；上层全部改成读**：Compose 走 Flow、业务判定走同步属性、面板走派生 UI 模型；`int` 状态在生产代码中消失，`fromLegacy`/`STATE_*` 只留测试。
- 本计划同时兑现两条**既有登记项**（`avbox-kotlin-migration-spec.md` §7.18 登记 ①⑥）与补齐 `START_ABORT` 缺口（登记 ③）；不新建架构，只做收敛。
- 推荐 **4 步**（+1 可选）：① 契约与派生（零行为变更）→ ② 判据归一 + 缺口补齐（有行为决策，需走查）→ ③ 消费方换枚举（机械替换，需走查）→ ④ 清理与固化（纯删除 + 防回归门）。可选步 5 = 屏幕形态轴改名归位。
- 分步判据是"验证方式是否相同"：纯重构只需构建 + 单测，行为决策必须走查，机械替换走查面大但归因简单。混在一笔提交里，走查出问题无法定位。

# 1. 与既有文档 / 登记项的关系

| 既有文档 | 关系 |
| --- | --- |
| `skill/avbox-playback-service-spec.md` §1/§2 | 现状架构的权威描述。本计划不改所有权模型（Service 托管 / Engine 持有 / 页面挂摘），只收敛状态表达面；§2.1 的"两条状态面并存"一句在本计划完成后应随之改写。 |
| `skill/avbox-kotlin-migration-spec.md` §7.18 登记 ① | `PlaybackViewBridge.currentPlayState()` 原定"M7e 删 `ui/player` 4 Java 时一并删接口成员与 `HeadlessView` override"——**实测未兑现**（接口成员仍在，4 处读点存活，见 §2.3）。本计划步 3/4 收口。 |
| `skill/avbox-kotlin-migration-spec.md` §7.18 登记 ② | "`fromLegacy` 是迁移期适配，doikki 清零后整体删除"——doikki 已由 M10 清零，但 3 处消费点仍在。本计划步 3 兑现。 |
| `skill/avbox-kotlin-migration-spec.md` §7.18 登记 ③ | `START_ABORT` 语义缺口（`onStartAborted` 无接线）。本计划步 2 处理，口径见 §7 开放问题②。 |
| `skill/avbox-kotlin-migration-spec.md` §7.18 登记 ⑥ | `PlaybackEngine` 状态监听体内残余 int 比较与 `PlaybackPreload.onPlayerState(int)` 原定"留 M7f"——**实测未收口**。本计划步 3 兑现。 |
| `skill/avbox-kotlin-migration-spec.md` §7.14 登记项 | M7b 登记的"状态机未承接焦点 / 常亮 / 进度副作用"是**有意保留**：终态同样不让状态机做副作用（见 §6 R2）。 |

# 2. 现状基线（2026-10-07 实测）

## 2.1 三张面

| 面 | 真源 | 生产者 | 消费者 |
| --- | --- | --- | --- |
| 旧 int `playState` | `AppPlayerView.mCurrentPlayState`（常量 `STATE_*` 9 个 + `PLAYER_*` 3 个，`AppPlayerView.kt:647-660`） | `AppPlayerView` 动作方法（122/206/223/235/252/263/285/400）+ `kernelEventListener`（321/330/333/337/348/355） | `AppPlayerView` 内部守卫（261/303-307/310/312/390/399）、`VideoControllerHost.setPlayState(Int)` → `ComposeVideoController.kt:308` / `ComposeLiveController.kt:66`、`OnStateChangeListener.onPlayStateChanged(Int)` → `PlaybackEngine.kt:90-124` / `MusicPlayerActivity.kt:192`、`PlayerUiState.kt:16` → `playStateRes()`（`ComposeVideoController.kt:1264`）、`PlaybackViewBridge.currentPlayState()` → `PlayContainer.kt:205/1053` / `PlayContainerViewBridge.kt:43` / `TrackSelectorDelegate.kt:68/77`（日志）、`PlaybackController.onPlayerStateForPreload(Int)`（875）→ `PlaybackPreload.kt:49` |
| 新 enum `PlayState` | `ExoPlayer.stateMachine`（`ExoPlayer.kt:26`；出口 = `MyVideoView.playState`（69-70）→ `PlaybackViewBridge.playState()`（31）） | `ExoPlayer` 适配器动作（74/84/90/95/100/110/118/129）+ `dispatchPlaybackState`（171-199）+ error listener（65-68） | `PlaybackController`（432/719）、`MusicSessionDelegate`（115/185）、`PlaybackRetryDelegate`（314）、`PlaybackEngine`（440，HeadlessView）、`PlayContainerViewBridge`（45）、`DanmuLoadController`（266）、`DetailActivity`（170）、`LiveOverlayController`（92-94）、`LivePlayActivity`（301/437）、`LiveScreens`（226/328）、`MusicPlayerActivity`（185） |
| 第三轴（屏幕形态） | `AppPlayerView.mCurrentPlayerState`（`PLAYER_NORMAL/FULL_SCREEN/TINY_SCREEN`） | `AppPlayerView`（207） | `PlayerUiState.kt:17`、`VideoGestureActionsImpl.kt:70`、`ComposeVideoController.kt:345`、`ComposeLiveController.kt:71`（空实现） |

缝合层：`PlayState.fromLegacy`（`PlayState.kt:31-43`，与旧 int 逐值 1:1，含 `-1 → ERROR`）在 3 处被用作适配（`PlaybackEngine.kt:93`、`MusicPlayerActivity.kt:194`、`ComposeLiveController.kt:68`）。**这说明新面从一开始就是按"接替旧面"设计的。**

## 2.2 三处判据分叉（同动作、两份判定）

| 动作 | 旧面判据 | 新面判据 |
| --- | --- | --- |
| `pause()` | `AppPlayerView.kt:230-241`：`isInPlaybackState() && player.isPlaying == true` 才置 PAUSED | `ExoPlayer.kt:93-96`：无条件 `onPauseRequested()` |
| `start()` | `AppPlayerView.kt:112-118`：idle/startAbort 走 `startPlay()`、playbackState 走 `startInPlaybackState()` | `ExoPlayer.kt:88-91`：无条件 `onPlayRequested()` |
| `stop()` | `AppPlayerView.kt:259-264`：`STATE_PAUSED` 早退（不置 IDLE、不调内核 stop） | `ExoPlayer.kt:98-101`：无条件 `onStopRequested()` |

后果（不是理论风险）：`LivePlayActivity` 同一屏两源并存——`playState` 字段由控制器回调喂（旧面派生，301），同时页内多处直读 `videoView.playState`（437、`LiveOverlayController.kt:92-94`）。一旦"缓冲中暂停"这类场景两面分叉，同屏 UI 会自相矛盾。此类"两个真源"的语义、以及"每处 `isInPlaybackState()` 之类的 int 判定都是第二份真源"是"两个 int 真源"的本质，`AppPlayerView.kt:301-308` 的 `isInPlaybackState()` 也是其中之一。

## 2.3 两个缺口 + 两条未兑现登记

- **`START_ABORT` 无生产**：`PlayState.kt:110` 的 `onStartAborted()` 全仓零调用；旧面写点 `AppPlayerView.kt:122` 被 `showNetWarning()`（`AppPlayerView.kt:140`，恒 `false`，全库无覆写）挡住 ⇒ **int 8 在本仓库恒不可达**；但新面读点已按"可达"预留分支（登记③列的 3 处：`isIdleKernelReusable` / `LivePlayActivity.canReusePlayer` / `DanmuLoadController.isVideoReady`）。
- **双份 `pausedBeforeSeek`**：`AppPlayerView.mPausedBeforeSeek`（75/390-402）与状态机 `pausedBeforeSeek`（`PlayState.kt:56/120-128`）同语义两份实现。
- **登记 ① 未兑现**：`PlaybackViewBridge.currentPlayState()`（29）仍有 4 处读点（`PlayContainer.kt:205/1053`、`TrackSelectorDelegate.kt:68/77`）。
- **登记 ⑥ 未兑现**：`PlaybackEngine.kt:94/103/111/115` 与 `PlaybackPreload.kt:53/55` 仍是 int 比较。

## 2.4 契约自由度

播放层类与方法**不在** D9 Tier B 清单（13 个符号全在 `osc.util`/`osc.server`，见 `avbox-kotlin-migration-spec.md` "Tier B 变化（D9）"），`fromLegacy` / `STATE_*` / `VideoControllerHost` 均为 app 内部面 ⇒ 改签名自由。唯一外部可见影响是 **logcat 取证格式**（登记 ⑤：`state=<int>` 变枚举名；`PlaybackEngine` 的 `echo-player error` 仍为 int）。

# 3. 终态设计

## 3.1 结构

```
media3 Player.Listener  ← 唯一事件源
        │
   PlayerEngine（只上交原始事件，不判状态）
        │
   ExoPlayer 适配器 ──意图命令（start / pause / stop / reset / seekTo / prepare / 换内容）
        │                        │
        └──> PlaybackStateMachine（唯一写点：PlayState + 可选 failure）
                    ├── StateFlow<PlayState>   → Compose 覆盖层
                    ├── currentState           → 业务同步判定（预载 / 会话 / 重试 / 换线 / 归属）
                    └── 派生 PlayerUiState      → OSD / 面板 / 顶栏底栏

KernelPlayer（契约）：playState + stateFlow + 命令面
AppPlayerView / MyVideoView：纯消费者（渲染 / 容器 / 封面 / 弹幕），不再写状态
PlaybackController / PlaybackViewBridge / PlaybackPage：读状态做决策，不写状态
```

## 3.2 五条"宪法"

1. **单一真源**：`PlayState` 只允许在 `PlaybackStateMachine` 内被赋值，且只能经它的意图方法（`onPrepareRequested` / `onPauseRequested` / …）改。
2. **单一事件漏斗**：内核回调只从适配器进状态机；视图层不再有第二份 `when (what)` 去写状态（`AppPlayerView.kernelEventListener` 现在正是第二份）。
3. **动作即意图，条件只判一次**："动作是否生效"的判定只存在一处（搬进状态机或适配器），不再两面各判一次。
4. **两轴分离**：播放生命周期（`PlayState`）与屏幕形态（建议改名 `ScreenMode`，归 UI 层）互不写对方。
5. **分面不分源**：Flow / 同步属性 / 派生 UI 模型是三种读法，不是三个真源；`fromLegacy`、`toLegacy`、`STATE_*` 在生产代码中全部删除，映射只留在测试。

## 3.3 终态各面清单

| 面 | 类型 | 位置 | 说明 |
| --- | --- | --- | --- |
| 内核契约 | `val playState: PlayState`（+ `stateFlow`） | `player/KernelPlayer.kt` | 新增；取代 doikki 的 `getCurrentPlayState()`；`MyVideoView` 不再直探 `exoPlayer.stateMachine` |
| 真源 | `PlaybackStateMachine` | `player/state/PlayState.kt` | 唯一写点；`fromLegacy` 降级为测试专用 |
| 派生 UI 模型 | `PlayState` + `ScreenMode` | `player/state/PlayerUiState.kt` | 取代 `Int` / `PLAYER_*`；`when` 由编译器保穷尽 |
| 业务读面 | `PlaybackViewBridge.playState(): PlayState` | `player/PlaybackViewBridge.kt` | 已是解耦面，保留 |
| 错误 | `ERROR` + `failure: PlaybackFailure?` | 状态机 | 把 `ERROR_KIND_*` int 常量收成枚举；UI 不再解析裸 int |
| 查询（**不进状态**） | `isPlaying` / `currentPosition` / `duration` / `bufferedPercentage` / `speed` | `KernelPlayer` | 按需查询，不镜像进状态对象 |

## 3.4 删除清单

- `AppPlayerView`：`mCurrentPlayState`、`STATE_*`（9）、`mPausedBeforeSeek`、`setPlayState`、`OnStateChangeListener`（含 `SimpleOnStateChangeListener`）、`currentPlayState` 读口。
- `PlaybackViewBridge.currentPlayState()`（含 `PlaybackEngine.kt:438` 的 `HeadlessView` override 与 4 处读点）。
- 3 处 `fromLegacy` 缝合与各级 `curPlayState`（`ComposeVideoController.kt:105/175`、`ComposeLiveController.kt:59`）。
- `PlaybackController.onPlayerStateForPreload(Int)`（875）→ `PlaybackPreload.onPlayerState(PlayState)`。
- `PlaybackEngine.kt:90-124` 监听体内的 int 比较（改枚举后自然消失）。

## 3.5 明确不做

- **不改 `sealed class`**：状态无载荷（进度 / 速度按需查询），枚举 + 独立 `failure` 字段足够，还能保住廉价相等性与穷尽 `when`。将来真需要载荷（如 `Buffering(percent)`）再升。
- **不把事件塞进状态**：字幕 `onCues`、尺寸变化、视频帧回调是事件，不是状态。
- **不做状态镜像**：不给 `PlayState` 加 `position` / `isPlaying` 快照字段——那是把真源退化成缓存。
- **不让状态机做副作用**（延续 M7b 登记口径）：焦点 / 常亮 / 进度落盘留在订阅方，见 §6 R2。

# 4. 分步计划

## 步 1｜契约与派生（零行为变更）

- 内容：`KernelPlayer` 增 `val playState: PlayState`（+ `stateFlow`），`ExoPlayer` 实现；`MyVideoView.playState` 改走契约；新增 `PlayState.toLegacy()`，`AppPlayerView.mCurrentPlayState` 降级为**派生只读值**（`mMediaPlayer?.playState?.toLegacy() ?: STATE_IDLE`），`setPlayState` 保留但只做"对外派发"。
- 文件：`KernelPlayer.kt` / `ExoPlayer.kt` / `MyVideoView.kt` / `AppPlayerView.kt` / `PlayState.kt`。
- 提交：1 笔（全英文小写 + scope）。
- 验证：`assembleDebug` + `:app:testDebugUnitTest`；核验"`stateMachine` 直探零命中"（除 `ExoPlayer` 自身）。
- 走查：**无**（行为逐字不变，纯重构）。
- 回滚：单提交 revert，UI 与外部接口零变化。

## 步 2｜判据归一 + 缺口补齐（有行为决策）

- 内容：`pause()` / `start()` / `stopPlaybackKeepPlayer()` 的前置条件搬进唯一入口（口径见 §7 开放问题①）；处理 `START_ABORT`（见开放问题②）；`release()` 路径与"无内核时写状态"两条旧面独有写点找到新家；合并双份 `pausedBeforeSeek`。
- 文件：`PlayState.kt` / `ExoPlayer.kt` / `AppPlayerView.kt`。
- 提交：1 笔。
- 验证：`PlaybackStateMachineTest` 扩成"动作 × 内核事件 → 期望状态"表驱动；`PlayStateTest` 的成员表断言（§7.18 规则 7）随之更新。
- 走查：3 项 —— 缓冲中暂停、起播、`START_ABORT` 路径（若按"维持现状"处理则只核不打）。
- 回滚：单提交 revert。**本步完成后"两个真源"降级为"一真源两读法"，后续可随时做、随时停。**

## 步 3｜消费方换枚举（机械替换）

- 内容：`VideoControllerHost.setPlayState(PlayState)`、`OnStateChangeListener.onPlayStateChanged(PlayState)`、`PlaybackController.onPlayerStateForPreload` / `PlaybackPreload.onPlayerState`、`PlayerUiState.playState` 换类型；删 3 处 `fromLegacy` 与各级 `curPlayState`；顺带把 `ComposeVideoController.playerState()`（105，实为 playState 的错名）改名。
- 文件：`ComposeVideoController.kt` / `ComposeLiveController.kt` / `PlayerUiState.kt` / `VideoGestureActionsImpl.kt` / `PlaybackEngine.kt` / `PlaybackController.kt` / `PlaybackPreload.kt` / `MusicPlayerActivity.kt`（8 个）。
- 提交：2 笔（接口层一笔、UI 层一笔），便于走查归因。
- 验证：构建 + 单测 + **走查**：控制层全部按钮、直播页、音乐页、OSD。
- 排期建议：**与下次播放器 UI 改动合并**，省一次走查；若 UI 排期不明，也可独立做（本步已无真源风险）。

## 步 4｜清理与固化（纯删除 + 防回归门）

- 内容：删 §3.4 剩余项（`STATE_*` 常量、`OnStateChangeListener` 监听 API、`mPausedBeforeSeek`、`PlaybackViewBridge.currentPlayState()` 及其 4 读点）；`fromLegacy` 迁进测试；加机械核验门（§5）。
- 提交：1 笔。
- 验证：构建 + 单测 + grep 归零，**无需走查**。

## 可选步 5｜屏幕形态轴分离

`PLAYER_NORMAL/FULL_SCREEN/TINY_SCREEN` 改名 `ScreenMode` 并归位 UI 层（唯一消费者 `VideoGestureActionsImpl.kt:70`）。与状态统一无关，可独立做，也可不做。

# 5. 验收门（机械核验）

| 门 | 判据 |
| --- | --- |
| 旧面零残留 | 全仓 `AppPlayerView.STATE_` 零命中（测试除外）；`currentPlayState` 零命中 |
| 缝合层清零 | `fromLegacy` 零生产调用（仅测试引用）；`toLegacy` 仅测试引用 |
| 穷尽性 | `ComposeVideoController` 的 `when (playState)` 为表达式形态（编译器强制穷尽） |
| 单测 | `PlaybackStateMachineTest` 表驱动（动作 × 内核事件 → 期望状态）；`PlayStateTest` 成员表断言 |
| 状态机纯度 | `PlaybackStateMachine` 内零 Android / 零副作用调用（纯 Kotlin 可测） |

# 6. 风险

- **R1 判据口径选错**：四条已走查链路（直播 / 音乐 / DLNA / 第三方出口）读新面，控制层与 OSD 读旧面。若按新面语义统一，等于同时改控制层 + 四条链路行为，走查面从 1 屏扩到 6 屏。**对策：默认以旧面语义为准**（见 §7 ①）。
- **R2 副作用触发点丢失**（M7b 登记项）：状态机只表达状态，副作用留在订阅方。统一时必须逐条映射现有副作用触发点：`keepScreenOn`（PLAYING 置位 / PAUSED / ERROR / COMPLETED 清位）、音频焦点（`requestFocus` / `abandonFocus`）、进度落盘（IDLE / PAUSED / COMPLETED → `saveProgress`）、`startProgress()`（控制器挂载）、封面切换（PLAYING → `showVideoFrame` / 音频-only → `hideVideoFrameCover`）、预载调度（PLAYING / BUFFERED / BUFFERING）。**漏一条 = 静默降级**（M7a 的 `also` 空转就是同类事故）。
- **R3 取证格式变化**（登记 ⑤）：走查手册里按 `state=<int>` 的 logcat grep 条目需改枚举名；`PlaybackEngine` 的 `echo-player error` 仍为 int，不要顺手改（会与历史日志断层）。
- **R4 走查面扩散**：步 3 若与播放器 UI 改动同期进行，走查归因会变难 ⇒ 步 3 内部拆 2 笔提交，且先接口层后 UI 层。

# 7. 开放问题（待拍板）

1. **判据口径**：缓冲中调 `pause()`，控制层应显示"暂停"还是"缓冲中"？（推荐：**维持旧面语义**——控制层/OSD 是按旧面调出来的，且它是被真机走查过的路径。）
2. **`START_ABORT` 闸门**：`showNetWarning()` 恒 `false`，int 8 恒不可达。是"把写点接到状态机、维持语义等价"（推荐，零行为变更 + 兑现登记③），还是"连闸门一起删"（需要另一次走查确认无回归）？
3. **步 3 排期**：是否与下次播放器 UI 改动合并？（合并省一次走查，但会推迟 `fromLegacy` 清零。）
4. **`failure` 字段**：本次是否把 `ERROR_KIND_*`（`ExoPlayer.kt:297-299`）一并收成枚举？（推荐：**不在本计划内**，避免与状态统一耦合；可作为独立小项。）

# 附录 A｜现状证据索引

| 类别 | 位置 |
| --- | --- |
| 旧面写点 | `AppPlayerView.kt` 122/206/223/235/252/263/285/321/330/333/337/348/355/400；`setPlayState` 500-508 |
| 旧面内部守卫 | `AppPlayerView.kt` 261/303-307/310/312/390/399（`isInPlaybackState` / `isInIdleState` / `isInStartAbortState` / `keepPausedStateAfterSeek`） |
| 旧面接口 | `AppPlayerView.kt:539-549`（`VideoControllerHost`）、30-37 + 528-537（`OnStateChangeListener`） |
| 旧面消费（控制器） | `ComposeVideoController.kt` 105/175/308-343/421-426/1264；`ComposeLiveController.kt` 59/66-68/85-90 |
| 旧面消费（引擎/预载） | `PlaybackEngine.kt` 90-124；`PlaybackController.kt:875`；`PlaybackPreload.kt` 49-57 |
| 旧面消费（页面/UI 状态） | `PlayerUiState.kt` 16；`VideoGestureActionsImpl.kt` 65/70；`MusicPlayerActivity.kt` 192-204 |
| 旧面读口 | `PlaybackViewBridge.kt:29`；`PlaybackEngine.kt:438`；`PlayContainerViewBridge.kt:43`；`PlayContainer.kt` 205/1053；`TrackSelectorDelegate.kt` 68/77 |
| 新面生产 | `ExoPlayer.kt` 26/65-68/74/84/90/95/100/110/118/129/171-199 |
| 新面出口 | `MyVideoView.kt` 69-72；`PlaybackViewBridge.kt:31`；`PlaybackEngine.kt:440`；`PlayContainerViewBridge.kt:45` |
| 新面消费 | `PlaybackController.kt` 420-432/719；`MusicSessionDelegate.kt` 115/185；`PlaybackRetryDelegate.kt:314`；`DanmuLoadController.kt:266`；`DetailActivity.kt:170`；`LiveOverlayController.kt` 92-94；`LivePlayActivity.kt` 301/437；`LiveScreens.kt` 226/328；`MusicPlayerActivity.kt:185` |
| 缝合层 | `PlayState.kt` 31-43（`fromLegacy`）；调用点 `PlaybackEngine.kt:93` / `MusicPlayerActivity.kt:194` / `ComposeLiveController.kt:68` |
| 分叉证据 | `AppPlayerView.kt` 112-118/230-241/259-264 vs `ExoPlayer.kt` 88-91/93-96/98-101 |
| 缺口 | `PlayState.kt:110`（`onStartAborted` 零调用）；`AppPlayerView.kt:121-122/140`（闸门恒 false）；`AppPlayerView.kt:75/390-402` vs `PlayState.kt:56/120-128`（双份 pausedBeforeSeek） |

# 附录 B｜既有测试资产

`app/src/test/java/com/github/tvbox/osc/player/` 下 26 个测试文件中与本计划直接相关：`PlaybackStateMachineTest.kt`、`PlayStateTest.kt`、`KernelReusePolicyTest.kt`、`PlayerUiStateVisibilityTest.kt`。步 2 扩表驱动用例；步 3 的 UI 层不做单测（Compose 层无既有测试面），靠走查。

# 附录 C｜步 1–2 复核轮（2026-10-07，本轮可收尾）

**范围**：`22704bc`（步 1）、`898a030`（步 2）、`432b692`（复核修复）。方法：契约调用点全集 / START_ABORT 接线闭环 / 谓词消费面本机自查 + 1 个独立只读子代理交叉复核（初始计 4 中 7 低，逐条核验后 1 中转为修复、2 中降级、1 中为测试面限制）。

**已修**
- `AppPlayerView.release()` 清理守卫由真值快照 `!isInIdleState()` 改为 `mLastReportedPlayState != STATE_IDLE`（`432b692`）。旧面语义 = "最后一次写入值"，last 快照与之逐字等价；真值快照在"闸门启用 + 无内核 → 手写派发 START_ABORT"时会让派发面与真值永久分叉（清理块死代码）。修复后该路径自愈。

**核验后降级/证伪**
- "去重派发吞掉重复通知导致预载/渲染副作用丢失"：旧 `onInfo` 三支均有 `keepPausedStateAfterSeek()` 守卫，压制分支旧面同样零派发；旧的自愈派发与 `reportPlayState` 的 last 比较逐位等价。重复派发被吞的场景（重复 ERROR / RENDERING_START）副作用均幂等。降为低。
- "起播中切轨（PREPARING）的 pause→start dance 消失"：属实但非缺陷（旧行为会把 PREPARING/COMPLETED 乱写成 PLAYING；PREPARING 期 `getTrackInfo()` 多为空、面板入口先 Toast 返回）。列入走查，不改代码。

**登记（不改，随步 3/4 或走查处理）**
- `TrackSelectorDelegate` 直调内核 4 处（63/74/111/118）：新判定下 PREPARING/COMPLETED/ERROR 边界行为变化，返回值被忽略、无提示 → 走查项 4。
- `onPrepared` 的人造 `dispatchPlayState(STATE_PREPARED)`：与真值（已 PLAYING/PAUSED）的撕裂窗口限于同一回调内，随后 RENDERING_START 事件自愈。
- `setVideoController` 挂载播种不更新 last（自发自愈；"控制器与监听器不同步窗口"记入步 3 走查注记）。
- `MyVideoView.clearVideoFrame` 的 else 兜底（非 Exo 内核，当前不可达）语义不对等。
- `LivePlayActivity.canReusePlayer` 把 START_ABORT 归"可复用"（与 `isIdleKernelReusable` 相反）——既有分叉、闸门不可达、两种处理均可用。
- 新判定代码无单测面（依赖 media3，player 适配层一贯靠走查）。
- 步 3/4 收口时需一并决定 `mLastReportedPlayState` / `dispatchPlayState` / `reportPlayState` 的归宿（步 2 新增过渡机制，§3.4 删除清单未列）。

**复验**：`assembleDebug` 通过；682 用例 0 失败；6 文件全 LF。

**收尾判定**：无阻断 / 高 / 中（剩余均为既有、不可达路径或口味差异）。步 2 走查项由 3 项扩为 4 项（新增"轨道切换"）。

# 附录 D｜步 3 实施与复核轮（2026-10-07，本轮收尾）

**范围**：10 文件（9 生产 + 1 测试）。`AppPlayerView.kt`（接口 `OnStateChangeListener.onPlayStateChanged(PlayState)`、`VideoControllerHost.setPlayState(PlayState)`、`dispatchPlayState`/`mLastReportedPlayState`/`setPlayState` 换枚举、`setVideoController` 播种改真值枚举）、`PlaybackEngine.kt`（监听体删 `fromLegacy`）、`PlaybackController.kt` / `PlaybackPreload.kt`（`onPlayerStateForPreload` / `onPlayerState` 换枚举）、`MusicPlayerActivity.kt`（监听体删 `fromLegacy`）、`ComposeVideoController.kt`（`setPlayState` 换枚举 + 穷尽、删 `curPlayState`、`isInPlaybackState` 改读 `state.playState`、删错名 `playerState()`、OSD 两处换枚举）、`ComposeLiveController.kt`（删 `curPlayState` 改 `playState: PlayState`、删 `fromLegacy`）、`PlayerUiState.kt`（`playState: PlayState` + 5 个派生属性）、`VideoGestureActionsImpl.kt`（`host.curPlayState`/`host.playerState()` 归位 `host.state.*`）、`PlayerUiStateVisibilityTest.kt`（同步）。

**等价性推导（本次"零行为变化"的四条支点）**：
1. `toLegacy`/`fromLegacy` 在可达域上是双射（10 值互异，`PlayStateTest` 已断言），故 `reportPlayState` 去重、`mLastReportedPlayState` 比较、监听侧取值在换枚举后逐值不变。
2. `setVideoController` 播种新旧同源：旧 `mCurrentPlayState`（`mMediaPlayer?.playState?.toLegacy() ?: STATE_IDLE`）与新 `mMediaPlayer?.playState ?: PlayState.IDLE` 只差一层恒等映射；守卫集合 `{IDLE, ERROR}` 一致。
3. `isInPlaybackState()`（控制器）与 `gesturePlaybackState()`（直播）允许集合与改前"排除法"完全一致：`{PLAYING, PAUSED, BUFFERING, BUFFERED}`（改前明确排除 `PREPARED`）。
4. `VideoGestureActionsImpl` 的 `fullScreen` 新旧同恒 `false`：改前 `playerState()`（实为 playState int）恒不等 11；改后第三轴 `state.playerState` 无 `PLAYER_FULL_SCREEN` 生产者（唯一写点恒 `PLAYER_NORMAL`）。

**提交**：计划原定"接口层一笔、UI 层一笔"——**不可行**：接口定义（`AppPlayerView`）↔ 实现（两个 Controller）↔ `PlayerUiState` 为强类型闭环，任何二分产生的中间态都编译不过；拆"先改名后换类型"则纯碎化。已合并为 **1 笔**。

**验证**：`assembleDebug` 通过（23s）；682 用例 0 失败；10 文件全 LF（CR=0）；生产面 `curPlayState` / `playerState()` / `fromLegacy` 零命中（`fromLegacy` 仅剩定义 + 测试）。

**复核轮（独立只读子代理，medium）**：0 阻断。1 存疑（`isInPlaybackState` 是否丢 `PREPARED`）对改前原文定案为等价；3 低：① `setPlayState` 的 `when` 为语句形态（10 成员已列全），"表达式形态"门留步 4；② `fullScreen` 判据为死条件（第三轴恒 `PLAYER_NORMAL`，计划 §3.4 未列该轴，可并入可选步 5）；③ `ComposeLiveController.videoView` 无赋值点恒 null ⇒ 直播页纵向手势（音量/亮度）整条为既有死路径（本次未动，独立缺陷候选）。

**步 3 走查项（归走查）**：① 控制层全部按钮（含 OSD 文案 "就绪/播放中/暂停/结束/异常" 分支）② 直播页 ③ 音乐页 ④ 控制器与监听器不同步窗口（挂载播种不更新 last，步 2 登记项顺延）。

**遗留步 4 决定**：`mLastReportedPlayState` / `dispatchPlayState` / `reportPlayState` 的归宿（随 `OnStateChangeListener` 删除一并处理）；`mCurrentPlayState`（int 读口）与 `toLegacy` 的清理。

# 附录 E｜步 4 实施与复核轮（2026-10-07，本计划收尾）

**范围**：10 文件（9 生产 + 1 测试）+ 新增机械门脚本（本地工具，`.codebuddy/` 不入库）。

- `AppPlayerView.kt`：删 `OnStateChangeListener`/`SimpleOnStateChangeListener`/`addOnStateChangeListener`/`removeOnStateChangeListener`/`mOnStateChangeListeners`/`it2snapshot`/`protected setPlayState`/`currentPlayState`/`mCurrentPlayState`/`STATE_*`(9)；新增 `playStateFlow: SharedFlow<PlayState>`（`MutableSharedFlow(extraBufferCapacity = 8)`）；`dispatchPlayState` = 写 last → `mVideoController?.setPlayState` → `tryEmit`（失败落 `echo-player playState-flow-drop` 日志）。
- `PlaybackEngine.kt`：删 `HeadlessView.currentPlayState()` override；监听体由匿名 listener 搬为 `stateScope.launch { view.playStateFlow.collect { onPlayStateChanged(it) } }`（`Dispatchers.Main.immediate`；`release()` 末尾 `stateScope.cancel()`）。
- `MusicPlayerActivity.kt`：删 listener 注册/注销，改 `scope.launch(Dispatchers.Main.immediate) { player.playStateFlow.collect {...} }`（`onDestroy` 的 `scope.cancel()` 覆盖）。
- `PlaybackViewBridge.kt` / `PlayContainerViewBridge.kt`：删 `currentPlayState()` 成员与 override。
- `PlayContainer.kt`（2 读点）/ `TrackSelectorDelegate.kt`（2 日志）：改读 `playState` 枚举（登记⑤兑现：`state=` 由 int 变枚举名；`echo-player` 相关日志仍 int，未动）。
- `PlayState.kt`：删 `toLegacy()` 与 `fromLegacy()`（映射随旧面废除）；`PlayStateTest.kt` 删 4 个 legacy 映射测试，留成员表 + `isInPlaybackState` 白名单。
- `ComposeVideoController.kt`：`setPlayState` 的 `when` 提为 `private fun applyPlayState(playState: PlayState) = when (...)`（表达式形态、无 else、10 成员列全——附录 D 低①收口）。
- 新增 `.codebuddy/tools/playstate_gate.py`：对 `AppPlayerView.STATE_` / `currentPlayState` / `curPlayState` / `fromLegacy|toLegacy` / `OnStateChangeListener` 五模式扫 `app/src/main`，零命中退出 0。

**等价性支点**：① `dispatchPlayState` 仍是唯一出口，值序列与旧 listener 派发逐点相同（`mLastReportedPlayState` 去重与 `PREPARED`/`START_ABORT` 人造值保持）② `MutableSharedFlow(replay=0)` = "不补发历史、订阅后只收后续"，与原 listener 注册语义一致 ③ `Main.immediate` 在 emit 线程=主线程时同栈执行（预载关闭即默认场景，逐字节等价）④ 派发顺序保持"控制器 → 订阅方"（`skill/history/features.md` 2026-09-13 条目记录的旧派发序）。

**已知行为偏差（登记 + 走查）**：预载开启时内核回调在 `avbox-preload` looper（`PlayerEngineConfig.playbackLooper`），旧 listener 在该线程直接执行消费体，新 flow 会把消费体投递回主线程（方向更安全，时序异步化）；连发超过 8 个未消费值会丢值并落日志。

**提交**：1 笔代码（`player: drop legacy int play-state surface`）+ 1 笔文档（两处活规范同步：`avbox-playback-service-spec.md` §2.3 状态面 as-built 改写、`avbox-kotlin-migration-spec.md` 登记 ①②③⑥ 关闭）。

**验证**：`assembleDebug` ✓；`testDebugUnitTest` 678 用例 0 失败（682 − 4 个已删 legacy 测试）；核验门 0 残留；全 LF。

**复核轮（独立只读子代理，medium）**：0 阻断 / 0 高 / 0 中。采纳 2 条低：派发顺序恢复"控制器先"（否则引擎副作用先于控制器 UI，次序翻转）；丢弃日志加 `echo-player` 前缀（进 `preload_debug.log` 白名单）。其余低为既有登记：三件套归宿 = 保留（去重 + flow 发射器，`setVideoController` 播种仍绕过 flow、不更新 last——"控制器与订阅方不同步窗口"照旧）；第三轴 `PLAYER_*` 命名易混（可选步 5）；收集协程不持 Job（当前取消点覆盖完备）。

**§5 门对账**：旧面零残留 ✓（五模式 grep 全零）；缝合层清零 ✓（比计划更强：`fromLegacy`/`toLegacy` 连测试引用都无）；穷尽性 ✓（`applyPlayState` 表达式形态）；单测 ✓（表驱动 + 成员表）；状态机纯度 ✓。

**步 4 走查项（归用户）**：预载开/关两档配置下的起播、切集、暂停、进度（新 flow 订阅链）；音乐页状态流（缓冲转圈/播放暂停图标/播完续播）。
