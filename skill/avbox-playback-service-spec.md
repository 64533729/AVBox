# AVBox 播放服务化 Spec:播放器所有权归前台服务(现状架构 + 走查清单)

> 项目:AVBox(TVBox OSC fork;仓库根目录 = 本文件所在目录的上一级)
> 配套:先读 `SKILL.md`(通用规范 + 文档地图)与 `avbox-mobile-ui-spec.md`(§4.4 播放页布局 / §6 关键技术约束)。
> 状态:**as-built 规范(2026-10-06 重写)**。播放服务化 P0–P5 已落地(2026-09-14,真机功能回归通过);**播放栈实现层已于 M7(A 路线 / D12)整体换代** —— 不再有 dkplayer,内核唯一 = media3 `ExoPlayer`,M10 已删除 `player` 模块。本文不再是"待执行方案",而是**现状说明书 + 走查清单**;历史方案、fongmi 对照与逐阶段实施记录压到 §7。
> 触发背景:2026-09-14 hprof 取证(`memory/2026-09-14.md`)——详情页快速换页 = 每页整套 ExoPlayer + 内部线程 + Looper + 文件句柄新建、退出仅异步释放;12 次进出后滞留 12 套详情页对象图、36 个 ExoPlayer、249 线程、RSS 692MB,finalizer 积压 97%。据此把播放器所有权从页面搬到前台服务。
> ⚠️ **改动"播放器归属 / 跨页复用 / 后台播放与通知 / 播放页职责划分 / 播放栈内核"前必读**;`§4` 是走查清单,**编号 1–14 与节号 §4 保持稳定**(`avbox-kotlin-migration-spec.md` §7.13–§7.16、`review/refactor-plan-20261005.md` 均按「§4 清单」引用)。

## 0. 摘要与范围

**一句话**:播放器实例与播放调度由**进程级引擎 `PlaybackEngine`** 持有、由**前台服务 `PlaybackService`** 托管;页面退化为「**显示宿主(容器槽位)+ Compose 控制器覆盖层**」,进出页面只做**挂摘**,不再重建内核。

**不变项(动它们等于动架构)**:
- 播放器实例的生命周期**长于页面**:页面 `onDestroy` 只停播 + 摘视图,**不释放实例**(这是跨页复用的收益点)。
- **退出播放页一律停播 + 撤通知 + 放锁**(影视与音乐都不后台继续,2026-09-14 用户选定);app 退后台时沿用原语义(纯音频继续、影视暂停)。
- 内核唯一 = `androidx.media3.exoplayer.ExoPlayer`,由 app 侧 `ExoPlayer`(适配器)/`KernelPlayer`(契约)/`AppPlayerView`+`MyVideoView`(宿主视图)三层承接,**无 doikki / 无 IJK / 无 `PlayerFactory` 泛型链**。
- 媒体会话**不用 media3 `MediaSessionService`**:保留 `MediaSessionCompat` + `MediaStyle`(见 §7.1 的理由)。

**非目标**:不换 UI 框架、不改数据层(Room/MMKV)、不引入 DI、不做画中画、**不追求"退页面继续播"**(除已确认的纯音频音乐会话)。

## 1. 组件与职责(as-built)

| 组件 | 位置 | 职责 |
|---|---|---|
| `PlaybackService` | `player/PlaybackService.kt` | 前台服务(FGS `mediaPlayback`);**托管引擎生命周期**;媒体会话/通知/wake+wifi 锁;通知栏动作入口;任务移除/服务销毁时释放引擎 |
| `PlaybackEngine` | `player/PlaybackEngine.kt` | **持有** `MyVideoView` + `PlaybackController`;**挂摘协议**主体(attach/detach/detachForHandover);空闲释放;内核预热;直播人格切换;无页面时实现 `PlaybackHostApi` + `HeadlessView` 桥 |
| `PlaybackController` | `player/PlaybackController.kt` | 会话与派生数据层:播什么(vod/`sourceKey`/`sourceBean`/播放器配置)、进度键与缓存键、清晰度、投屏地址改写、**已起播内容归属**;视图交互一律经 `PlaybackViewBridge` |
| `PlayUrlResolver` | `player/PlayUrlResolver.kt` | 取流/解析/嗅探调度(WebView 嗅探 + json/聚合/超级解析 + **代际闸门**) |
| `PlaybackRetryDelegate` | `player/PlaybackRetryDelegate.kt` | 重试与换线:同址重播、硬解→软解回退、自动切内核、下一条线路;取流超时/失败/换线超时三处入口 |
| `PlaybackFetch` | `player/PlaybackFetch.kt` | 取流结果观察者:收集 `SourceChannel.flow`,把结果落到会话数据(清晰度/进度键/字幕/歌词/封面) |
| `PlaybackPreload` + `PreloadCoordinator` + `PreloadManagerHolder` | `player/PlaybackPreload.kt` / `PreloadCoordinator.kt` / `PreloadManagerHolder.kt` | 下一集预载:喂快照/取结果/作废时机(协调器)**随引擎存活**;管理器是**进程级单例**(线程/注册表/就绪回调) |
| `MusicSessionDelegate` | `player/MusicSessionDelegate.kt` | 媒体会话与通知的维护实现(有音频轨就维护,影视同样);封面兜底、弹幕地址、清晰度切换 |
| `MyVideoView`(→ `AppPlayerView`) | `player/MyVideoView.kt` / `AppPlayerView.kt` | **播放器视图**:渲染容器 `mPlayerContainer` + 封面/黑帧/弹幕/渲染模式/内核重建语义;`AppPlayerView` 是 fork `VideoView` 的 app 侧等价物 |
| `KernelPlayer` / `ExoPlayer` | `player/KernelPlayer.kt` / `ExoPlayer.kt` | 内核契约(取代 `AbstractPlayer`)/ media3 适配器(取代 `ExoMediaPlayer`),**类名与方法面保留 ⇒ 宿主/控制器/调度层对内核换代透明** |
| `PlaybackSession` | `player/PlaybackSession.kt` | 一次播放的显式数据(vod/`sourceKey`/`userPickedLine`)+ **归属键 `playbackKey`** |
| `PlaybackHostApi` | `player/PlaybackHostApi.kt` | **指令面**:页面/通知栏只通过这些方法驱动播放(实现方 = 页面 `PlayContainer` 或引擎) |
| `PlaybackViewBridge` | `player/PlaybackViewBridge.kt` | **调度 → 视图**的动作面(提示/起播/封面/状态读取/嗅探/预载快照);实现方 = 页面桥 或 `HeadlessView` |
| `PlaybackPage` | `player/PlaybackPage.kt` | **页面最小契约**:`renderSlot()` / `viewBridge()` / `onServiceStopped()`;实现方 = `PlayContainer`、`MusicPlayerActivity` |
| `PageHost` | `player/PageHost.kt` | **页面能力**(旧 `instanceof DetailActivity` 的替代):context/存活判定/主线程/Toast/SAF 字幕选择器/通知权限/选集面板/线路耗尽换源兜底 |
| `engine/*` | `player/engine/` | media3 装配层:`PlayerEngine`(DataSource/Renderers/LoadControl/TrackSelector/缓存/HLS/效果)、`EngineRenderersFactory`、`MediaSources`、`PlayerCache`、`OkHttpDataSource`、`HlsErrorHandlingPolicy`、`CodecPreferences`、`SourcePolicy`、`NetworkSpeed` |
| `host/*` | `player/host/` | 渲染宿主与音频焦点:`PlayerRenderView`(契约)、`EngineSurfaceRenderView`/`EngineTextureRenderView`(+Factory)、`RenderMeasure`、`TextureRenderHost`、`PlayerAudioFocus` |
| `state/*` | `player/state/` | `PlayState` 枚举 + `PlaybackStateMachine`(StateFlow);`PlayerUiState`(控制层集中状态容器) |
| `PlayContainer` | `ui/player/PlayContainer.kt` | 页面侧:`PlaybackHostApi` + `PlaybackPage` 实现;容器槽位 `surfaceSlot`;`hostResume/hostPause/hostDestroy` 生命周期;`handOverToNextPage()` 交接音乐页 |
| `ComposeVideoController` / `ComposeLiveController` | `player/controller/` | Compose 覆盖层 + 手势(`VideoGestureLayer`);**不再继承 `BaseVideoController`**,自持进度定时器与锁定/显隐状态 |

## 2. 架构与所有权

### 2.1 所有权模型

```
PlaybackService(前台服务,托管生命周期)
   └── PlaybackEngine(进程级,持有播放器)
         ├── MyVideoView → AppPlayerView(渲染容器 mPlayerContainer + 内核 ExoPlayer)
         └── PlaybackController(会话/派生数据/编排)
               ├── PlayUrlResolver / PlaybackRetryDelegate / PlaybackFetch
               └── PlaybackPreload(→ PreloadManagerHolder 进程单例)
页面(PlayContainer / MusicPlayerActivity)= 显示宿主 + 控制器覆盖层,只挂摘
```

- **引擎不是 Service 本体**(P2 的有意偏差):页面对引擎的取用必须与页面构造**同帧同步** —— 若引擎在 Service 里,就要处理"服务未就绪 → 控制器事后替换 → 在途取流结果/观察者双投递"的初始化竞态。故 `PlaybackService.engine(context)` 是**同步返回**的静态入口(首次调用顺带 `startService` 拉起宿主),Service 随后接管生命周期。
- **视图桥同一时刻只认一个**(引擎硬约束):有页面时 = 页面桥(提示/弹幕/字幕/控制器动作都在页面);无页面时 = `HeadlessView`(播放器机械动作照做,UI 动作空操作)。因此退页面后音频/通知/预载仍能继续维护。
- **所有权收口**:页面**不得**直接 `videoView.release()`;表达"我要换内核"只能走 `PlaybackEngine.releasePlayer()` —— 否则引擎会在不知情的情况下失去内核,而预载协调器/会话归属/"已起播内容"标记仍停在"还在播"的假象上。

### 2.2 线程模型(不可动)

| 面 | 线程 |
|---|---|
| 引擎/视图/控制器/挂摘 | 主线程(`PlaybackEngine` 的 `main` Handler;跨线程入口一律 `main.post`) |
| 取流/解析 | `SourceHelper.SPIDER_POOL` / `PREPARE_POOL` 共享池(**不得每请求新建**) |
| 播放内核 | media3 播放线程;**预载 looper 必须与播放 looper 对齐**(`PlayerEngineConfig.playbackLooper` = `DefaultPreloadManager` 的 `preloadLooper`) |
| 进度写盘 | `vod-progress-writer`;读回走 `awaitWrites` |
| 预载结果派发 | 主线程(`Dispatchers.Main.immediate`,与旧 `LiveData.observeForever` 投递线程一致) |
| Room | `allowMainThreadQueries()` 是显式设计(主线程查库),**不借机改** |

### 2.3 数据流与指令流

- **指令**:页面(Compose 按钮/手势)/通知栏/媒体键 → `PlaybackHostApi` → `PlaybackController` → `PlaybackViewBridge` → `MyVideoView`/内核。
- **状态**:内核事件 → `PlayerEngine` → `ExoPlayer`(适配器)→ **单一真源** `PlaybackStateMachine`(`PlayState`;`MyVideoView.playState` 同步读,`stateFlow` 为流式面)。派发面 `AppPlayerView.dispatchPlayState`(值变化去重 + `PREPARED`/`START_ABORT` 人造值;顺序 = 控制器 → flow):① `VideoControllerHost.setPlayState(PlayState)` → 页面控制器(UI)② `AppPlayerView.playStateFlow`(`SharedFlow`,`Dispatchers.Main.immediate` 订阅)→ 引擎状态订阅(业务:预载/进度/会话/揭黑帧)与音乐页。旧 int 面(`STATE_*`/`currentPlayState`/`OnStateChangeListener`/`fromLegacy`/`toLegacy`)已整体废除(2026-10-07,`skill/review/refactor-plan-20261007.md` 步 1–4;机械门 `.codebuddy/tools/playstate_gate.py`)。
- **页面数据**:`PlaybackSession` 由详情页组装后交给引擎 —— **播放会话本身**不再经 `App.getInstance().getVodInfo()` 全局单槽 + `Bundle` 这两个隐式通道传递(改造前正是它们导致"详情页 B 覆盖 A 时 A 的播放数据被动改掉")。⚠️ 该字段**并未全仓清除**:`RemoteServer`/`DetailViewModel` 仍直接读;进度侧(`data/PlaybackProgress`/`data/WatchProgressStore`)自 2026-10-07 起改经 `data.PlaybackPorts.currentVod`(由 `base.App` 注册为同一读取点)间接读,行为不变(登记见 `review/refactor-plan-20260928.md`),动它们要单独评估,别按"已废弃"处理。

## 3. 运行机制

### 3.1 挂摘协议

```
页面 onCreate/init:
  engine = PlaybackService.engine(activity)   // 同步取用(首次顺带拉起宿主服务)
  engine.attach(this)                          // ① 若在直播模式先 exitLiveState() ② 遮黑帧(见 3.2)
                                               // ③ 搬容器进 page.renderSlot() ④ controller.setViewBridge(page.viewBridge())
页面 onResume:hostResume()   → 必要则 reattachIfOwnedByOther() + 续播(仅当本页 ownsEngineContent())
页面 onPause :hostPause()    → 非"正在退出预览"且非"确认纯音频"时 pause()
页面 onDestroy:hostDestroy() → engine.detach(this)(除非 handedOver)
音乐页交接   :PlayContainer.handOverToNextPage() → engine.detachForHandover(this)(摘视图但不停播)
```

`detach(page, keepPlayback)` 的顺序是承重的(**顺序不可调换**):

1. **进度落盘先于归属判定** —— "快速返回再进入"时新页面可能已 attach,归属守卫会让后续收尾整段跳过;不在这里先存,这一集的观看进度就丢了。
2. 归属守卫:`attachedPage() != null && !== page` ⇒ 返回(**不是我的页面,不动播放器**)。
3. `keepPlayback=false` 时:① `pause()` ② `stopPlaybackKeepPlayer()`(刚点播放就退出:`pause()` 对 PREPARING/BUFFERING 无效,必须停内核,否则页面销毁后自己播起来)③ `controller.stopPlaybackForPageExit()`(撤取流/超时/解析 + 停会话)④ `PlaybackService.forceStopSession()`(绕过归属守卫再兜一次)。
4. 摘引用:`releaseController()` + `setDanmuView(null)` + `detachContainerFromHost()` + `setViewBridge(headlessView)`。
5. `scheduleIdleRelease()` —— 实例留着,但给它一个释放上界(见 3.3)。

**容器搬运**:`AppPlayerView.attachContainerTo(host)` / `detachContainerFromHost()` 只搬 `mPlayerContainer`(插到宿主 index 0),**不碰系统栏、不改全屏状态** —— 本仓库的"全屏"是 Activity 级(方向 + 系统栏),容器始终留在页面槽位。搬运会触发 SurfaceView 的 `surfaceDestroyed`/`surfaceCreated`,渲染宿主自己 `setDisplay(null)` 再重挂(安全性见 §6-R1)。

### 3.2 遮黑帧与揭开(两处真机 bug 的根因面)

- **挂载先遮黑**:`attach()` 搬容器前,内核**不是"正在播"**就先 `coverVideoFrame()`(只加黑遮罩、**不停内核**)。理由:页面挂载时还不知道要播什么(会话要等详情数据回来),旧内容停在 PAUSED 时 media3 会在新 Surface 重建时把上一帧**重渲染**出来 ⇒ 新页面闪上一部的画面。
- **揭开必须位于 `liveMode` 短路之前**:`STATE_PLAYING` 时揭(纯音频走 `hideVideoFrameCover()`)。直播页共用同一块容器,若跟着点播侧一起短路,直播重播频道时遮罩没人揭 = **有声无画**。
- **不能用 `clearVideoFrame()` 代替遮黑** —— 它内部停内核(`stopForFrameClear()`),会破坏"同片接管续播"。
- 遮罩是**追加**进容器的,会把控制器(顶栏/手势层/字幕/直播控制层)一起盖住 ⇒ `showFrameCover()` 内必须 `bringControllerToFront()`。用 `bringToFront` 而非按 index 插入:渲染视图永远被 `addDisplay()` 插到 index 0,index 方案在"渲染视图尚未创建"时会算错位。
- `showVideoFrame()` 顺带 `clearArtwork()` —— 保证"有画面"与"显示封面"互斥(封面与渲染面同层且盖在其上)。

### 3.3 归属判定、内核复用与空闲释放

- **归属键** = `PlaybackSession.playbackKey()` = `源|片id|线路|集号`。页面 attach 时判断"引擎里正在播的是不是我要的这一集"(同键 = 只接管续播;不同 = 用户显式换片,走 `setData` 重播)。页面侧另有 `handedOver`(交给音乐页后 `hostDestroy` 不得再 detach)与 `ownsEngineContent()`。
- **内核复用** = `KernelReusePolicy.decide(kernelPresent, rebuildRequired, dedicatedPath, reuseAllowed)`,是**唯一判定**:调度层的释放决策与各起播点的 `replay`/`start` 决策共用它(分散判定会造成"上游保留、下游又释放"的动作分裂)。`REBUILD` 是强结论;`REUSE` 是弱结论,起播点现场发现"必须重建"标记时允许升级。**换线/换源/换片不再重建内核**(2026-10-03 D8);只有 `isKernelErrored()` 与 `requireKernelRebuild()`(渲染方式/EXO 解码方式变更)两条强重建条件。
- `isCrossContentSwitch(startedKey, targetKey)` 只用来分辨提示语与进度落盘口径(换片/换源/换线 vs 同片同线路换集)。
- **空闲释放**(`IDLE_RELEASE_DELAY_MS = 60_000`):摘下页面后若一直没人再来取,到点释放内核并回调 `PlaybackService.onEngineReleased`(清静态引擎引用 + 撤会话,**不 stopSelf** —— 服务为托管引擎而常驻,引擎可重建;`stopSelf` 到 `onDestroy` 之间有窗口会把新引擎误释放 ⇒ 黑屏)。**预热开关开启时抑制空闲释放**(`PrewarmPolicy`)。
- **服务常驻语义**:`onStartCommand` 恒返回 `START_NOT_STICKY`(进程被回收后引擎已不存在,重启服务只会留下空壳);`onTaskRemoved` = 停会话 + `stopSelf`;`onDestroy` = 停会话 + `releaseEngine()`。

### 3.4 进度落盘(三处)

1. **切集/换源前**(键易主前先落旧键);
2. **页面 detach 时**显式 `saveCurrentProgress()`(不 release 就没人触发落盘);
3. **引擎 release 时**(释放内核之后落盘)。

`ProgressSink` 由 `PlaybackEngine` 注入 `MyVideoView`,语义逐条保留:位置 > 0 才落盘、`onCompletion` 显式清 0、release 在释放内核后落盘。**直播期间摘下进度管理器**(`setProgressSink(null)`),且 `release()` 必须在还回点播进度管理器**之前**执行 —— 否则会把直播 position 写进点播进度缓存(看剧→进直播→返回,续播位置被污染)。

### 3.5 媒体会话、通知与前台服务

- 有音频轨就维护会话(影视/音乐一视同仁),由 `PlaybackController.updateMusicSession()` 驱动;`owner = WeakReference<PlaybackHostApi>`,通知栏动作全部打到它(`resumeFromMediaSession`/`pauseFromMediaSession`/`stopFromMediaSession`/`seekFromMediaSession`/`playPrevious`/`playNext`)。
- 通知:channel `music_playback`、id `1001`、`MediaStyle` 三键(上一集/播放暂停/下一集)+ 占位键;动作走 `PendingIntent.getService` + `ACTION_*`(`com.github.tvbox.osc.playback.*`)。
- **会话结束不 stopSelf、不释放引擎**:`stopPlaybackSession()` 只撤通知 + 放锁 + 释放 `mediaSession`。`ACTION_UPDATE` 若发现 `mediaSession == null` 会**重建**会话(服务为托管引擎而常驻,不能靠 `onCreate` 重建)。
- **`updateSession` 的两道丢弃闸门**:`engine == null` 时丢弃(否则建出空通知 + 无人释放的 wake/wifi 锁);`!isSupported` 时丢弃(`O` 以下 / TV)。
- **`startForeground` 被拒的两个恢复点**(2026-09-19):`foregroundDenied` 置位后,① `ACTION_UPDATE` 每次会话更新机会性重试;② `promoteIfForegroundDenied()` —— 用户动通知/媒体键那一刻(系统此刻一定允许提前台)。**不能只打日志了事**,否则一路走到"回页面点击无反应"都没痕迹。
- **FGS 启动竞态**(`pendingStart`/`stopWhenStarted`):`startForegroundService` 已发出但服务未就绪的窗口内**绝不能 stopService**(AOSP 竞态 ⇒ `ForegroundServiceDidNotStartInTimeException` 杀进程);改为登记"起来就停",让服务自己走「startForeground → stop」的合法时序。
- 锁:`PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF`,均 `setReferenceCounted(false)`;会话开始时 acquire、`stopPlaybackSession` 时 release。**`WAKE_LOCK` 权限声明在 app manifest**(M10 从 `player` 模块清单归位)。

### 3.6 直播接管(点播 ↔ 直播共用一个播放器)

- `enterLive()`:撤点播会话归属(`session = null` + `clearStartedContent()`,否则"点播→直播→回到同一部点播"会被判同片接管而跳过取流 ⇒ **真机 bug:点播页播着直播**)→ 摘点播页容器 → `liveMode = true` + `setLiveFlag(true)` + `cancelIdleRelease()` → **停死内核**(`releasePlayer()`;只 `pause()` 是空操作,旧内容会被直播页 `onResume` 的 `resume()` 恢复出声)→ 摘进度管理器 + 关边播缓存 + 清封面 → `forceStopSession` 绕过归属守卫撤点播通知与锁。
- `enterLiveState()`:从后台回直播前台时用。能执行到方法体(`liveMode == false`)就说明**必然被点播接管过** ⇒ 必须停死内核,返回 `true` 让直播页**重播当前频道**(直播流地址无法续播)。
- `exitLive()`:⚠️ 归属判定必须在 `exitLiveState()` **之前**(后者会把 `liveMode` 置 false ⇒ 放后面判恒为 true,直播退出永不 release)。直播页销毁必须**停流 + 释放内核**(直播无后台播放语义),顺序 = `videoView.release()` → `exitLiveState()` → `releaseController()` → `scheduleIdleRelease()`。
- **`setLiveFlag` 的唯一写入点是引擎的模式切换**(不再跟直播页 `onCreate`/`onDestroy`):`ApiConfig.proxyLocal()` 取流时读它决定爬虫路由,`ExoPlayer.setDataSource` 用它决定 rtmp 是否补 `live=1`;Activity 销毁时机与"引擎已切回点播"没有时序关系。
- 已知边界(有意保留):直播自身的 release(切台/换解码器)路径不动;因此"点播→直播→点播"回点播会重建一次内核(同改造前);"内核实例不增长"的准确含义 = 直播与点播共用同一个 `MyVideoView`/播放器对象 + **点播↔点播多次进出不重建**。

### 3.7 预载(下一集)

- `PreloadManagerHolder` 是**进程级单例**(预载管理器/线程/注册表/就绪回调,全静态面),`PreloadCoordinator` **随引擎存活**(归 `player` 不归 `ui.player`,否则 `player` 反向依赖 UI)。
- 触发时机:正片稳定播放(`STATE_PLAYING`)延迟评估、弱网 `BUFFERING` 让路 + 冷却、`BUFFERED` **补一次评估**(内核的"首帧 PLAYING"只发一次,不补枪则拖一次进度条就永久停摆)。
- 快照由**页面桥**组装(`buildPreloadSnapshot()` 需要页面上下文与集信息);无页面时留空、跳过评估。
- 预载结果订阅:`PlaybackPreload` 收集 `vm.preloadResult.flow`(收集域随本对象,`destroy()` 取消),**不是** `PreloadCoordinator` 自己订阅。
- 硬约束:预载 looper 与播放 looper 对齐;CacheKey/headers 口径不得改动(磁盘预缓存死锁的历史见 `history/preload-toast-deadlock.md`)。

### 3.8 渲染宿主与画面效果

- 双模式宿主(`EngineSurfaceRenderView` / `EngineTextureRenderView`),由 `HawkConfig.PLAY_RENDER` 选(1 = Surface,0 = Texture;默认 1);契约 = `PlayerRenderView`,测量算法 = app 侧 `RenderMeasure`。
- **引擎无页面起播必须自带渲染宿主**:`PlaybackEngine.createPlayerView()` 显式按设置建工厂 —— 无页面桥不会注入播放器配置,否则落到默认 Texture 工厂,新宿主的交面/输出尺寸钩子静默失效(历史上开调色黑屏的根因面)。
- 输出分辨率信令:Surface 路径靠内核 `setDisplay` 补发(唯一补发点);Texture 路径没有 SurfaceHolder,靠 `TextureRenderHost.setOnSurfaceReadyListener` + `onLayout` + 视频尺寸就绪**当帧**补发,且**只推视频原生尺寸**(推视图尺寸会被管线等比适应进画布 = 丢「铺满/裁剪」)。三条不变量详见 `avbox-mobile-ui-spec.md` §6.17。
- 音频焦点:`PlayerAudioFocus`(纯逻辑 `AudioFocusActions` 可单测 + Android 壳),语义照抄旧 `AudioFocusHelper`(GAIN 恢复 / LOSS 暂停待恢复 / DUCK 降音量 / 静音不请求不恢复 / 同值去重 / 主线程派发)。

## 4. 真机验收清单

> **结果(2026-09-14)**:真机回归确认无问题(功能项与稳定性项经日常操作走查)。**§4-6/7 的量化数据未采集**。
> **2026-09-21 追加 11–14**(旧内容残留缺陷),**尚未执行**。
> **2026-10-06 追加说明**:第 6 条的埋点口径已随 M7 换代 —— 旧 `echo-player-instance` 埋点**已不存在**,改用引擎自身的文件日志(见第 6 条)。

**功能**
1. 详情页播放:起播/暂停/seek/倍速/长按/双击、切集、切线路、换源(含失败回滚)、切清晰度、全屏↔预览、旋转。
2. 弹幕(加载/搜索/开关/字号)、内嵌字幕/本地字幕/字幕搜索/歌词、封面占位(纯音频)。
3. 投屏(DLNA + TVBox 推送)、外部播放器、边播缓存开关、预载「下一集已就绪」。
4. 音乐:详情页内播放 → 退后台(通知可控)→ 回前台;退出详情页 → 停播且通知消失 → 重进详情页从上次进度续播(**不重建内核**)。
5. 直播:切台/切源/时移/EPG/后台返回。

**跨页复用(本 Spec 的核心收益)**
6. 计数**引擎创建次数**:`PlaybackEngine` 的 `echo-p2 engine create` / `engine release` 已落 App 文件日志(`files/preload_debug.log`,白名单前缀含 `echo-p2`)。「详情A → 相关推荐 → 详情B → 返回 A → 返回首页」这一串里,**引擎创建次数应 ≤ 1**。
7. 连续 12 次详情页进出后:`dumpsys activity exit-info` 无异常退出;hprof 里 `DetailActivity/PlayContainer/MyVideoView/ExoPlayer` 实例数与线程数明显低于改造前基线(改造前:各 ×12、ExoPlayer×36、249 线程)。
8. 空窗期行为:服务在无页面时(音乐播放中)旋转/回前台/进其他页面,无黑屏闪烁、无白块(`SurfaceView` 洞穿问题不得回归;纯音频仍走 Texture 热切)。

**稳定性**
9. 进程被杀后重启;任务卡片划掉(`onTaskRemoved`);通知栏停止;锁屏媒体键;通知权限被拒时播放**不得抽搐式卡顿**(2026-09-19 缺陷)。
10. 快速连点进出详情页 20 次、播放中来回切 10 次,无崩溃/无 ANR、无声音叠音。

**旧内容残留(2026-09-21 修复后必测)**
11. 音乐播放中 → 退出音乐页 → **立刻**进直播 → 直播起播前不得有残留音乐声。⚠️ 要**先清一次直播配置或换个直播源**,逼出「频道列表异步加载」那条路径 —— 列表已缓存时 `playChannel` 在 `onCreate` 内同步跑完,这条路径测不到。
12. 影视 A 播放中 → 退出 → 进影视 B → 加载期不得闪出 A 的画面。⚠️ B 要选**详情数据需等网络**的条目(秒回的缓存条目会让内核释放抢在 Surface 重建之前,同样测不到)。预期画面保持黑,直到 B 的 `STATE_PLAYING`。
13. 直播页 → 进任意点播详情页(**进去就返回,不点播放**)→ 回直播页:**必须仍有画面**。这条专测遮黑帧的揭开路径(它位于 `liveMode` 短路之前);漏揭的症状是"有声无画"。
14. 反向确认未改坏:退出详情页 → 重进**同一部**仍直接续播且不重建内核;详情页 ↔ 音乐页交接;直播切台/时移;点播→直播→回点播。

**M7 换代后新增的走查重点(2026-10-06,自研栈首次走查)**
15. 起播/首帧/暂停记忆/旋转(旋转源必须转 —— 桥靠 `videoSizeListener` 回发 `onInfo(10001)`)/双渲染模式热切/纯音频强制 Texture。
16. 输出分辨率与效果链(调色/超分)在 Surface 与 Texture 两模式下的几何与铺满语义;R8(release)下 `media3-effect` 反射查找是否可用。
17. 错误与 HLS 重试(切片错误跳过)、自动软解回退、切集/换线/换源的内核复用几何。
18. 直播/音乐/DLNA/第三方播放器出口(状态读取面已从 doikki int 切到 `PlayState`);音乐页"调用外部播放器"必须真正拉起。

## 5. 设计决策记录

| 编号 | 问题 | 结论 | 状态 |
|---|---|---|---|
| D1 | 视图层保留 fork 播放器(β)还是换 media3 官方 `PlayerView`(α) | 曾选 **β**(复用现有双内核/渲染/预载对齐) | **已作废并升级**:M7 走**第三条路** —— 自研 app 侧 Kotlin 栈(见 D9)。理由:内核只剩 media3 一个,"多内核抽象"已无对象 |
| D2 | 控制器归属 | **页面持有**(Compose 覆盖层依赖页面 owners/主题) | ✅ 保持。attach 时 `setVideoController(controller)`、detach 时 `releaseController()` |
| D3 | 是否引入三档后台设置(关/后台音频/PiP) | 先不做,保持现状 | ✅ 未做;退出播放页即停播(影视与音乐都停 + 撤通知,**但保留实例**);PiP 未引入 |
| D4 | 独立音乐服务(`MusicPlaybackService`)何时并入 | P3 一次并入,P5 删壳 | ✅ 已执行(文件与门面已删) |
| D5 | 是否迁 media3 `MediaSessionService` | 不迁 | ✅ 保持 `MediaSessionCompat` + `MediaStyle`。**注意**:内核现在是 media3 `Player` 了,技术上可接 media3 session,但会牵动通知/前台服务/媒体键整链,**当前无收益不重做**;若要重做需单独立项 |
| D6 | 详情页叠加的"同片续播 / 换片重播"判定 | 按 `playbackKey`(同片只 attach) | ✅ 已落地(`PlayContainer.setData` + `ownsEngineContent()`) |
| D7 | 是否执行本 Spec | 全程 P0→P5(2026-09-14 一天内分批落地) | ✅ 完成(真机功能回归通过) |
| D8 | 内核复用是否继续由「内核预热」开关当总闸 | 拆开:复用一律放行,开关只管预建与常驻 | ✅ 已执行(2026-10-03):只留 `isKernelErrored()` 与 `requireKernelRebuild()` 两条强重建;预热开 = 启动预建 + 空闲常驻,关 = 首次起播才建 + 摘下页面后 60s 回收。判定细节见 `avbox-mobile-ui-spec.md` §6.19 |
| D9 | 只剩 media3 一个内核后,播放栈怎么收 | **A 路线:自研替换 + 整体删除 doikki fork**(M7 / D12) | ✅ 已执行(M7-0/M7a–M7f + M10):app 侧 Kotlin 播放层重写,`player` 模块与 `dkplayer-ui` 已删除。纪律:① 替换步不夹带无关逻辑改动;② 逐类行为对照(承接面见 §7.3) |

## 6. 风险登记

| 风险 | 说明 | 缓解 |
|---|---|---|
| R1 跨窗口 View 搬运 | `mPlayerContainer` 在页面/引擎间搬运会触发 Surface 销毁重建 | fork 时代已有全屏搬运先例(自证可行);内核 `setDisplay(null)` 安全性有二进制级结论;attach/detach 幂等;真机压测 §4-10 |
| R2 Compose 控制器上下文 | 控制器依赖页面 owners/主题 | 控制器留页面(D2);detach 强制 `releaseController()`,防引擎持页面 View |
| R3 泄漏 | 引擎持有页面 View(弹幕/控制器)、页面持有引擎 | `releaseController()`/`setDanmuView(null)` 必做;`PageHost`/`playbackHost` 弱引用或短生命周期;`onServiceStopped()` 让页面放弃视图引用 |
| R4 谁在播(A/B 叠加) | 旧语义"每页一套、各播各的",新语义共享一个播放器 | D6 `playbackKey` 归属判定 + `handedOver` + `ownsEngineContent()`;`reattachIfOwnedByOther()` 兜回前台时的接管 |
| R5 WebView/嗅探与 Activity | 服务侧不得持有 Activity | `PageHost` 抽象;解析线程池里碰视图一律 `view.runOnUi`(非主线程写 ViewGroup 会让 `mChildren` 出 null 洞) |
| R6 FGS 限制 | Android 12+ 禁止后台应用提前台;Android 14+ 需类型 | `foregroundServiceType="mediaPlayback"`;`startForegroundSafely()` 捕获 + `foregroundDenied` 两处恢复点;`pendingStart/stopWhenStarted` 处理启动竞态 |
| R7 既有硬约束被破坏 | 预载 looper / CacheKey / 本地代理跳过缓存(`SourcePolicy.isLocalProxyUrl`)/ M3U8 净化 4 槽 LRU + `?k=` 键(`RemoteServer.M3U8_SLOT_LIMIT`) | 逐条列为不可变项(§2.2/§3.7);搬迁或改写时"只改位置不改逻辑" |
| R8 进度写入时机 | 只在页面销毁落盘会丢进度 | 三处落盘(§3.4);直播期摘进度管理器 + release 先于还回管理器 |
| R9 直播接管复杂度 | 直播自带自动切源/时移/EPG | 独立人格切换(§3.6);归属判定前置;直播自身 release 路径不动 |
| R10 引擎常驻的资源上界 | 保留实例 ⇒ 内核(含线程/解码器句柄)可能永久常驻 | `IDLE_RELEASE_DELAY_MS = 60s` 空闲释放;预热开关开启时按 `PrewarmPolicy` 抑制;`onTaskRemoved`/`onDestroy` 释放 |
| R11 换代期的可观测差异 | M7 收口引入 3 条(取流不再经 LiveData 合并、预载结果订阅方变更、字幕搜索 `data == null` 走"未找到") | 已登记进走查清单(§4-15~18);`SourceChannelTest` 锁住不合并语义 |

## 7. 历史:方案来源与实施记录

### 7.1 为什么沿用 fongmi 的"所有权模型"而不接 media3 `MediaSession`

参照实现 = fongmi/OK 影视 的本地只读副本(**只读参考,不改**)。我们**只沿用它的所有权模型**(播放器归前台服务、页面 `attach/detach` 视图、`isOwner()` 归属判定),**没有沿用它的 media3 API**:fongmi 的 `PlaybackService` 是 `MediaLibraryService`(内核即 media3 `Player`),而我们当时的抽象是 fork 的 `AbstractPlayer`(Exo/IJK 双内核),接不上 media3 `MediaSession` ⇒ 保留 `MediaSessionCompat + MediaStyle`。**内核现已换成 media3**(M7),这条理由**不再成立**,但重做 session 属独立立项(D5)。

fongmi 的关键实现点(仍具参考价值):服务侧建/释放内核、`bindPlayerView`/`detachPlayerView` 的挂摘、`ensureEngine(spec)` 的"同内核换内容 = `setMediaItem`"、`getPlaybackKey()/isOwner()/shouldReclaim()/reclaimPlayback()` 归属判定、后台档位 `PlayerSetting.getBackground()`(0=关/1=后台音频/2=PiP)。**边界**:即使 fongmi,退出播放页且**无任何持有者**(PiP/后台音频/媒体 client)时服务 `shutdown()` ⇒ "跨页续播"的真实范围 = 页面仍在栈里 / PiP / 后台播放开启。

### 7.2 分阶段实施(P0–P5,2026-09-14 全部落地)

| 阶段 | 内容 | 出口 |
|---|---|---|
| P0 接口抽取 | `PlaybackSession`/`PlaybackHostApi`/`PageHost`;`PlayContainer` 实现 API,`DetailActivity` 实现 `PageHost`;行为零变化 | ✅ |
| P1 调度层抽离 | `PlayContainer` 的播放调度段搬进 `PlaybackController`(取流/解析/换线/换源/清晰度/DASH/净化/预载/进度);`PlayContainer` 只留"视图 + 控制器 + 挂摘" | ✅ 3074 → 1553 行 |
| P2 服务持有引擎 | `PlaybackEngine`(引擎持有播放器)+ `PlaybackService`(宿主托管)+ 容器挂摘 API + 页面 `surfaceSlot` | ✅ **偏差(有意)**:落地为"引擎持有 + 服务托管"而非"服务直接持有",理由见 §2.1 |
| P3 音频/通知/后台 | 通知/媒体会话/锁/通知栏动作并入 `PlaybackService`(FGS `mediaPlayback`;会话结束不 stopSelf、`ACTION_UPDATE` 按需重建会话)+ D6 同片接管 | ✅ |
| P4 直播页接入 | 直播与点播共用同一引擎播放器(`enterLive/exitLive`),直播期点播侧状态监听短路 | ✅ 边界见 §3.6 |
| P5 清理固化 | 删 `PlaybackNotification` 门面与 `MusicPlaybackService`、删 `HawkConfig.PLAYBACK_SERVICE` 开关与全部双路径;控制器直连 `PlaybackService` | ✅ `PlayContainer` 1551 行 |

**P5 后四轮静态审查**共修 16+ 处回归/加固(D6 判定改 `startedPlaybackKey`、空闲 TTL 释放引擎、页面所有权收口 `releasePlayer()`、迟到回调防线、`exitLive` 归属守卫前置等),过程与逐条理由见 `history/features.md` 2026-09-14 各节。

### 7.3 M7｜播放栈自研替换(A 路线 / D12)+ M10 拆除

- **口径**:不再迁移 app 内 `player/` 36 Java 与 `player` 模块 27 Java,改为**新写 app 侧 Kotlin 播放层 + 整体删除 fork**(2026-10-05 拍板)。**这是"迁移 ≠ 重构"的明示授权例外(仅限播放栈)**,纪律 = ① 替换步不夹带无关逻辑改动;② 逐类行为对照。
- **切片**:M7-0(删 `dkplayer-ui` 死依赖)/ M7a(新内核适配层 `player/engine` 9 文件)/ M7b(内核桥切换 + 双模式渲染宿主 + 状态机 + 音频焦点)/ M7c(点播全链切换 9 片,`observeForever` → `flow`)/ M7d(直播/音乐/DLNA/第三方出口的状态读取面切 `PlayState`)/ M7e(控制器去 View + 手势改 Compose `pointerInput` + 全量去 doikki)/ M7f(`PreloadCoordinator`/`PreloadManagerHolder` 迁 Kotlin)。
- **必须承接、不许丢的行为**:① media3 适配(`OkHttpDataSource` 的 DoH/hosts/headers、磁盘缓存数据源、`HlsErrorHandlingPolicy`、track selector、硬/软解选择 + 自动软解回退);② 渲染与效果(裸 Surface 的 `MSG_SET_VIDEO_OUTPUT_RESOLUTION` 信令"交面之后立即补发"、双渲染模式、调色/超分效果链与 `RedrawPolicy`);③ 播放语义(内核复用判定四起播点、自动重试阶梯与换线、RTMP 直播 `live=1` 后缀与缓存跳过、进度写 `vod-progress-writer`/读 `awaitWrites`);④ 外设与出口(媒体通知/会话、弹幕时间轴、字幕两路与延迟、DLNA 与第三方播放器)。
- **M10 拆除**:`player` 模块(27 Java / 5313 行)+ `dkplayer-ui` 依赖项 + 3 个原生库(`libp2p`/`libxl_stat`/`libxl_thunder_sdk` → `app/src/main/jniLibs`)+ `WAKE_LOCK` 权限(→ app manifest)+ media3 依赖(9 项从 `:player` 的 `api` 面搬进 app 直接声明)。实测 655 用例 / 0 失败,debug APK 全部 29 个 dex 零 `xyz/doikki`。
- **逐阶段交付、复核轮账目与未验证面**见 `avbox-kotlin-migration-spec.md` §7.13–§7.21 与 `review/refactor-plan-20261005.md` §5 M7/M10。

### 7.4 其它真机缺陷修复(归档)

| 日期 | 缺陷 | 根因与修法 |
|---|---|---|
| 2026-09-19 | 未授予 `POST_NOTIFICATIONS` 时播放"抽搐式"卡顿 | 通知会话的唯一入口 `updateMusicSession()` 是**热路径**(起播期 8~9 次/秒),未授权时每次回调拉起一个 `GrantPermissionsActivity` 抢焦点打断渲染 Surface(真机 3.2 秒 22 次)。修法 = `PermissionHelper` 加进程级一次性闸门 + 未授权提前返回。**FGS 与解码器无关**(`am_foreground_service_start` 当时正常) |
| 2026-09-21 | 音乐页退出→进直播漏音 | `enterLive()` 原只 `pause()`,而 PAUSED 时是空操作 ⇒ 改 `releasePlayer()` 停死旧内核 |
| 2026-09-21 | 影视页退出→进新影视页闪上一部画面 | `attach()` 搬容器前先 `coverVideoFrame()` 遮黑(新增"只遮黑不停内核"的 API;`clearVideoFrame()` 会停内核,不能用于此),起播由 `STATE_PLAYING` 揭开 |
| 2026-10-03 | 内核复用总闸从「预热」开关上摘除 | 换线/换源/换片一律走复用,不再重建;预热开关收敛为"启动预建 + 空闲常驻"(D8) |

## 8. 修订记录

| 日期 | 变更 |
|---|---|
| 2026-09-14 | 初稿:依据 fongmi 读码 + hprof 取证(`memory/2026-09-14.md`)起草;未动代码 |
| 2026-09-14 | P0 ✅ / P1 ✅(六批调度层搬迁,`PlayContainer` 3074 → 1553 行);编译/单测通过,未装机 |
| 2026-09-14 | P2 ✅ 代码层:`PlaybackEngine`(引擎持有)+ `PlaybackService`(宿主托管)+ 容器挂摘 API + 页面 `surfaceSlot` 双路径 + 开关 `HawkConfig.PLAYBACK_SERVICE` |
| 2026-09-14 | P3 ✅ 代码层:通知/媒体会话/锁/通知栏动作并入 `PlaybackService` + D6 同片接管落地 |
| 2026-09-14 | P4 ✅ 代码层:直播与点播共用同一引擎播放器;直播自身 release 路径按 R9 不动 |
| 2026-09-14 | P5 ✅ 代码层:删门面与 `MusicPlaybackService`、删开关与全部双路径(`PlayContainer` 1551 行) |
| 2026-09-14 | P5 后四轮静态审查共修 16+ 处回归/加固;hprof 量化复测未采集 |
| 2026-09-14 | **真机功能回归通过(已确认)**;spec 状态收口为 P0–P5 ✅。第五轮审查另修 `exitLive()` 顺序缺陷、直播接管后回直播页停死内核并重播当前频道、`play()` 裸取崩溃防护、`HeadlessView.startVideoPlayback` 复用/释放防线 |
| 2026-09-19 | 缺陷修复(真机确认):未授予 `POST_NOTIFICATIONS` 时播放"抽搐式"卡顿(见 §7.4) |
| 2026-09-21 | 两处"旧内容残留"缺陷修复(见 §7.4);挂摘协议新增遮黑帧条目(§3.2) |
| 2026-10-03 | 内核复用总闸从「预热」开关上摘除(D8);连带改动见 `avbox-mobile-ui-spec.md` §4.4/§6.10/§6.14/§6.19 |
| 2026-10-05~06 | **M7 播放栈自研替换(A 路线 / D12)全切片落地**:doikki fork 退出历史,内核唯一 = media3 `ExoPlayer`;`app/src` 零 `xyz.doikki` 引用;`PlayState` 取代 doikki `STATE_*` 读取面。逐切片记录见 `avbox-kotlin-migration-spec.md` §7.13–§7.18 |
| 2026-10-06 | **M10 拆除**:`player` 模块 + `dkplayer-ui` 删除;media3 依赖 / `WAKE_LOCK` 权限 / 3 个原生库归位 app(见 §7.3) |
| 2026-10-06 | **本文重写为 as-built 规范**:组件表、所有权/线程/数据流、挂摘协议与运行机制按现栈重述;§4 走查清单保留原编号并补 M7 换代后的走查重点(15–18);D1 作废、新增 D9;**§4 节号与 1–14 编号保持不变**(外部文档按「§4 清单」引用)。历史方案与 fongmi 对照压到 §7 |
