package com.github.tvbox.osc.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.TextUtils
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import coil3.Image
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.target.Target
import coil3.toBitmap
import com.github.tvbox.osc.R
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.ScreenUtils
import java.lang.ref.WeakReference

/**
 * 播放宿主服务(P2 播放服务化 + P3 通知/媒体会话并入,`skill/avbox-playback-service-spec.md` §2.1/§3)。
 *
 * **职责**:
 * 1. **托管播放引擎** [PlaybackEngine](P2):播放器实例的生命周期长于页面 ——
 *    页面进出只做挂摘,不再重建 ExoPlayer/RenderView;任务被移除/服务销毁时释放。
 * 2. **前台服务 + 媒体通知 + 媒体会话**(P3 起职责在本服务,原独立音乐服务的壳已在 P5 删除):
 *    有音频轨就维护会话(影视/音乐一视同仁),通知栏可播放/暂停/上一集/下一集/拖动;播放期间持
 *    wake/wifi 锁。由控制器(`PlaybackController.updateMusicSession`)驱动,宿主是页面或引擎。
 *
 * **为什么引擎不是 Service 本体**:页面对引擎的取用必须与页面构造**同帧同步**(否则要处理
 * "服务未就绪 → 控制器事后替换 → 在途取流结果/观察者双投递"的竞态)。故引擎由页面同步取用
 * ([engine]),本服务随后托管其生命周期。
 *
 * **唯一形态**(P5 起):引擎 + 本服务即播放层;旧路径(页面自建播放器 + 独立音乐服务)已下线。
 */
class PlaybackService : Service() {

    // ---- 会话资源(媒体会话/通知/锁) ----
    private var mediaSession: MediaSessionCompat? = null
    private var sessionActivity: PendingIntent? = null
    private var title: String? = "TVBox"
    private var subtitle: String? = ""
    private var artworkUrl: String = ""
    private var artwork: Bitmap? = null
    private var position: Long = 0
    private var duration: Long = 0
    private var playing: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * 上一次 `startForeground` 被系统拒绝(2026-09-19)。
     *
     * 真机原文:`Service.startForeground() not allowed due to mAllowStartForeground false`
     * —— Android 12+ 禁止**后台应用**把服务提为前台。一旦被拒,服务会以"在跑但没进前台"的状态
     * 存活:唯一的前台通知已撤、前台身份丢失,进程随即可能被降级/冻结 —— 这就是"通知消失后再也
     * 回不来 + 回页面点击无反应"的起点。置位后由下一次会话更新自动重试(见 [handleSessionIntent])。
     */
    private var foregroundDenied: Boolean = false
    private var foregroundRetryLogged: Boolean = false

    // ==================== 生命周期 ====================

    /** 通知文案走 Service 自身 Context,不包裹会是系统语言 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrap(newBase))
    }

    /** Service 的 base 在创建时固化(切语言不会重挂)→ 文案改取 App 级(已按语言包裹过)的 Context */
    private fun text(resId: Int): String {
        val app: Context? = applicationContext
        return if (app == null) getString(resId) else LanguageManager.localized(app).getString(resId)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        pendingStart = false
        LOG.i(TAG + " host onCreate (engine=" + (if (engine == null) "none" else "alive") + ")")
        createNotificationChannel()
        createMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (ACTION_UPDATE == action) {
            // 会话请求:必须立刻进前台(否则 O+ 的 startForegroundService 超时杀进程)
            startForegroundSafely()
            handleSessionIntent(intent!!)
        } else if (action != null) {
            // 通知栏动作/AOSP 重建:已在 FGS 上则无事,否则补一次前台(持有通知才允许被后续 startForeground 覆盖)
            startForegroundSafely()
            handleSessionIntent(intent!!)
        }
        if (stopWhenStarted) {
            // 起播抖动期登记过"起来就停":先合法进前台,再收尾(见 pendingStart 注释)
            stopWhenStarted = false
            stopPlaybackSession()
        }
        // 不自动重启:进程被回收后引擎已不存在,重启服务只会留下一个空壳
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        LOG.i(TAG + " host onTaskRemoved → release engine")
        stopPlaybackSession()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        LOG.i(TAG + " host onDestroy")
        instance = null
        stopPlaybackSession()
        releaseEngine()
        super.onDestroy()
    }

    // ==================== 会话内部实现 ====================

    private fun createMediaSession() {
        mediaSession = MediaSessionCompat(this, "TVBoxPlayback")
        mediaSession?.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSession?.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                val host = getOwner()
                if (host != null) host.resumeFromMediaSession()
            }

            override fun onPause() {
                val host = getOwner()
                if (host != null) host.pauseFromMediaSession()
            }

            override fun onSkipToPrevious() {
                val host = getOwner()
                if (host != null) {
                    pauseForSwitch()
                    host.playPrevious()
                }
            }

            override fun onSkipToNext() {
                val host = getOwner()
                if (host != null) {
                    pauseForSwitch()
                    host.playNext(false)
                }
            }

            override fun onStop() {
                val host = getOwner()
                if (host != null) host.stopFromMediaSession()
                stopPlaybackSession()
            }

            override fun onSeekTo(pos: Long) {
                val host = getOwner()
                if (host != null) host.seekFromMediaSession(pos)
            }
        }, Handler(Looper.getMainLooper()))
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
            sessionActivity = PendingIntent.getActivity(this, 0, launchIntent, flags)
            mediaSession?.setSessionActivity(sessionActivity)
        }
    }

    private fun startForegroundSafely() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (mediaSession == null) return
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            if (foregroundDenied) {
                // 之前被拒过、这次成了:前台身份已恢复(通知重新可见)
                foregroundDenied = false
                foregroundRetryLogged = false
                LOG.i(TAG + " startForeground recovered after denial")
            }
        } catch (th: Throwable) {
            // ⚠️ 不能只打日志了事(2026-09-19):startForeground 被拒 ⇒ 服务**没进前台**,
            // 唯一的前台通知已被前一次 stopForeground 撤掉、且不会有新的 notification_enqueue,
            // 系统也不再给 FGS 的进程优先级。历史上这里静默吞掉,于是一路走到
            // 「回页面点击无反应」(引擎随进程被降级/回收而失效)都没有任何痕迹。
            // 现在置位并明确留痕,由后续会话更新自动重试。
            foregroundDenied = true
            if (!foregroundRetryLogged) {
                foregroundRetryLogged = true
                LOG.i(
                    TAG + " startForeground DENIED (service stays non-foreground, will retry on"
                        + " next session update): " + th.message
                )
            }
        }
    }

    /**
     * 通知栏 / 媒体键动作触发的"补一次前台"。
     *
     * 为什么单列一个入口(2026-09-19 审查补):修复的目标场景里**根本没有状态变化可供重试** ——
     * 已确认纯音频的会话退后台**不会被暂停**(`MusicPlayerActivity.hostPause` 对纯音频让路),
     * 所以回到前台时 `hostResume()` 也不产生任何播放状态事件 ⇒ 拿不到 ACTION_UPDATE ⇒
     * "回到前台即自动救回"是不成立的。真正的可达恢复点是**用户与通知/媒体键交互**这一刻:
     * 系统此刻一定允许提升前台(应用正在响应用户动作),且这一下正好是用户最可能做的操作。
     * 另有 ACTION_UPDATE 那条机会性重试(见其分支),二者互补。
     */
    private fun promoteIfForegroundDenied() {
        if (!foregroundDenied) return
        LOG.i(TAG + " media action while foreground denied → retry startForeground")
        startForegroundSafely()
    }

    private fun handleSessionIntent(intent: Intent) {
        val action = intent.action
        if (ACTION_STOP == action) {
            val host = getOwner()
            if (host != null) host.stopFromMediaSession()
            stopPlaybackSession()
            return
        }
        if (ACTION_PLAY == action) {
            val host = getOwner()
            if (host != null) host.resumeFromMediaSession()
            promoteIfForegroundDenied()
            return
        }
        if (ACTION_PAUSE == action) {
            val host = getOwner()
            if (host != null) host.pauseFromMediaSession()
            promoteIfForegroundDenied()
            return
        }
        if (ACTION_PREVIOUS == action) {
            val host = getOwner()
            if (host != null) {
                pauseForSwitch()
                host.playPrevious()
            }
            promoteIfForegroundDenied()
            return
        }
        if (ACTION_NEXT == action) {
            val host = getOwner()
            if (host != null) {
                pauseForSwitch()
                host.playNext(false)
            }
            promoteIfForegroundDenied()
            return
        }
        if (ACTION_SEEK == action) {
            val host = getOwner()
            if (host != null) host.seekFromMediaSession(intent.getLongExtra(EXTRA_SEEK, 0))
            return
        }
        if (ACTION_UPDATE == action) {
            // 会话已结束(通知已撤、mediaSession 已释放)但引擎/服务仍在:本次是**新会话** → 重建媒体会话。
            // (原实现靠 stopSelf 后由 onCreate 重建;并入后服务为托管引擎而常驻,必须自己重建)
            if (mediaSession == null) createMediaSession()
            // 重建失败(理论上不会)时不能再走下去:acquirePlaybackLocks 会重新持锁且无人释放(电量泄漏)、
            // buildNotification 曾在真机上直接 NPE 崩溃(2026-09-13 实锤路径)
            if (mediaSession == null) return
            acquirePlaybackLocks()
            title = intent.getStringExtra(EXTRA_TITLE)
            subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
            val newArtworkUrl = intent.getStringExtra(EXTRA_ARTWORK)
            position = intent.getLongExtra(EXTRA_POSITION, 0)
            duration = intent.getLongExtra(EXTRA_DURATION, 0)
            playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
            updateArtwork(newArtworkUrl)
            updateSessionState()
            // 每次会话更新都顺手补一次前台:若上一次 startForeground 被系统拒(见 startForegroundSafely
            // 注释),服务此刻在跑但没有前台身份、也没有通知 —— 这次调用就是机会性重试,成功时
            // startForegroundSafely 内部会清标记并打 recovered 日志。**不在此打"正在重试"的日志**:
            // 被持续拒绝时那会每次更新刷一行,而本次修复的用意正是"别再静默、也别刷屏"。
            // 另一个更可靠的恢复点见 promoteIfForegroundDenied(用户动通知/媒体键那一刻)。
            startForegroundSafely()
        }
    }

    private fun updateArtwork(url: String?) {
        if (TextUtils.equals(artworkUrl, url)) return
        artworkUrl = url ?: ""
        artwork = null
        if (TextUtils.isEmpty(artworkUrl)) return
        // 封面地址的 @Headers= 等附加参数由 VodImages 的 OkHttp 拦截器剥离并注入请求头
        val request = ImageRequest.Builder(this)
            .data(artworkUrl)
            .size(256, 256)
            .target(object : Target {
                override fun onSuccess(result: Image) {
                    // toBitmap 保证软件位图,通知 RemoteViews 不接受硬件位图
                    artwork = result.toBitmap()
                    // 图片下载期间会话可能已被结束(mediaSession 被置 null):此时再刷新通知会
                    // 在 buildNotification() 内对 null mediaSession 取 sessionToken 而崩溃(2026-09-13 修复)
                    if (mediaSession == null) return
                    updateSessionState()
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification())
                }
            })
            .build()
        SingletonImageLoader.get(this).enqueue(request)
    }

    private fun updateSessionState() {
        val session = mediaSession ?: return
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, subtitle)
        if (duration > 0) metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        if (!TextUtils.isEmpty(artworkUrl)) {
            metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUrl)
        }
        val art = artwork
        if (art != null) metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
        session.setMetadata(metadata.build())
        val action = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(action)
                .setState(state, position, if (playing) 1f else 0f)
                .build()
        )
        session.setActive(true)
    }

    private fun pauseForSwitch() {
        playing = false
        position = 0
        updateSessionState()
        startForegroundSafely()
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationCompat.Builder(this, CHANNEL_ID)
        } else {
            NotificationCompat.Builder(this)
        }
        builder.setSmallIcon(R.drawable.ic_notification_music)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(sessionActivity)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .setDeleteIntent(actionIntent(ACTION_STOP))
            // mediaSession 可能已被 stopPlaybackSession 置 null(封面异步回调晚于停止):
            // 这里判空兜底,防 getSessionToken() NPE(2026-09-13 修复)
            .setStyle(
                MediaStyle().setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
        val art = artwork
        if (art != null) builder.setLargeIcon(art)
        builder.addAction(
            NotificationCompat.Action(R.drawable.media_action_placeholder, "", actionIntent(ACTION_PLACEHOLDER))
        )
        builder.addAction(
            NotificationCompat.Action(
                R.drawable.exo_icon_previous, text(R.string.player_notification_previous), actionIntent(ACTION_PREVIOUS)
            )
        )
        builder.addAction(
            NotificationCompat.Action(
                if (playing) R.drawable.exo_icon_pause else R.drawable.exo_icon_play,
                text(if (playing) R.string.common_pause else R.string.common_play),
                actionIntent(if (playing) ACTION_PAUSE else ACTION_PLAY)
            )
        )
        builder.addAction(
            NotificationCompat.Action(
                R.drawable.exo_icon_next, text(R.string.player_notification_next), actionIntent(ACTION_NEXT)
            )
        )
        return builder.build()
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, text(R.string.player_notification_channel_name), NotificationManager.IMPORTANCE_LOW
        )
        channel.description = text(R.string.player_notification_channel_desc)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager != null) manager.createNotificationChannel(channel)
    }

    private fun acquirePlaybackLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager != null) {
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVBox:Playback")
                    wakeLock?.setReferenceCounted(false)
                }
            }
            val lock = wakeLock
            if (lock != null && !lock.isHeld) {
                lock.acquire()
                LOG.i("echo-music wake lock acquired")
            }
        } catch (th: Throwable) {
            LOG.i("echo-music wake lock acquire failed: " + th.message)
        }
        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifiManager != null) {
                    wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TVBox:Playback")
                    wifiLock?.setReferenceCounted(false)
                }
            }
            val lock = wifiLock
            if (lock != null && !lock.isHeld) {
                lock.acquire()
                LOG.i("echo-music wifi lock acquired")
            }
        } catch (th: Throwable) {
            LOG.i("echo-music wifi lock acquire failed: " + th.message)
        }
    }

    private fun releasePlaybackLocks() {
        try {
            val lock = wifiLock
            if (lock != null && lock.isHeld) {
                lock.release()
                LOG.i("echo-music wifi lock released")
            }
        } catch (th: Throwable) {
            LOG.i("echo-music wifi lock release failed: " + th.message)
        } finally {
            wifiLock = null
        }
        try {
            val lock = wakeLock
            if (lock != null && lock.isHeld) {
                lock.release()
                LOG.i("echo-music wake lock released")
            }
        } catch (th: Throwable) {
            LOG.i("echo-music wake lock release failed: " + th.message)
        } finally {
            wakeLock = null
        }
    }

    private fun getOwner(): PlaybackHostApi? = owner?.get()

    /**
     * 结束会话:撤通知 + 释放锁与媒体会话。
     * **不 stopSelf、不释放引擎** —— 播放器要继续跨页面复用(P2),任务移除/服务销毁时才释放(见 onTaskRemoved)。
     */
    private fun stopPlaybackSession() {
        // 撤通知的唯一收尾点:留痕以便定位"通知自己消失"的路径
        LOG.i(TAG + " stopPlaybackSession (notification 1001 removed, playing=" + playing + ")")
        playing = false
        // 会话结束 ⇒ "本会话曾进不了前台"这件事随之失效,两个标记一起复位
        // (只复位一个会让 foregroundRetryLogged 永远留在 true:整个服务生命周期只打一次
        //  DENIED 日志 —— 而"留痕"正是这两个标记存在的理由,再被拒就看不见了)
        foregroundDenied = false
        foregroundRetryLogged = false
        releasePlaybackLocks()
        val session = mediaSession
        if (session != null) {
            session.setActive(false)
            session.release()
            mediaSession = null
        }
        stopForeground(true)
    }

    companion object {

        private const val TAG = "echo-p2"
        private const val CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_UPDATE = "com.github.tvbox.osc.playback.UPDATE"
        private const val ACTION_PLAY = "com.github.tvbox.osc.playback.PLAY"
        private const val ACTION_PAUSE = "com.github.tvbox.osc.playback.PAUSE"
        private const val ACTION_PREVIOUS = "com.github.tvbox.osc.playback.PREVIOUS"
        private const val ACTION_NEXT = "com.github.tvbox.osc.playback.NEXT"
        private const val ACTION_PLACEHOLDER = "com.github.tvbox.osc.playback.PLACEHOLDER"
        private const val ACTION_STOP = "com.github.tvbox.osc.playback.STOP"
        private const val ACTION_SEEK = "com.github.tvbox.osc.playback.SEEK"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_ARTWORK = "artwork"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_PLAYING = "playing"
        private const val EXTRA_SEEK = "seek"

        private var instance: PlaybackService? = null
        private var engine: PlaybackEngine? = null
        private var owner: WeakReference<PlaybackHostApi>? = null

        /** 内核预热任务(只捕获 application context,静态 Handler 不泄漏) */
        private val prewarmHandler = Handler(Looper.getMainLooper())

        /**
         * startForegroundService 已发出、服务尚未就绪(onCreate 未跑)。
         * ⚠️ 此窗口内绝不能 stopService:AOSP 竞态 —— create 已派发到进程,onStartCommand 可能
         * 不再交付,startForeground 永远不执行 → ForegroundServiceDidNotStartInTimeException 杀进程
         * (真机 2026-09-13:音乐起播失败重试期 start/stop 毫秒级抖动,连崩两次,vivo 超时窗 ~5s)。
         * 改为置 stopWhenStarted,让服务自己走「startForeground → stop」的合法时序。
         */
        @Volatile
        private var pendingStart: Boolean = false

        @Volatile
        private var stopWhenStarted: Boolean = false

        // ==================== 引擎入口(P2) ====================

        /**
         * 页面同步取用播放引擎(主线程调用;首次调用会顺带拉起宿主服务)。
         *
         * 同步返回是本设计的硬要求:页面构造期就要把 `scheduler`/`mVideoView` 指向引擎,
         * 不能"先本地建一套、服务就绪再替换"(在途取流/预载观察者会双投递)。
         */
        @JvmStatic
        fun engine(context: Context): PlaybackEngine {
            val app = context.applicationContext
            var current = engine
            if (current == null) {
                current = PlaybackEngine(app)
                engine = current
            }
            startHost(app, null)
            return current
        }

        /** 当前引擎(未创建时为 null) */
        @JvmStatic
        fun peek(): PlaybackEngine? = engine

        /**
         * 内核预热入口(开关关闭时不动):延迟 delayMs 后确保引擎与内核就绪(重复调用只保留最后一次)。
         * 不走 startHost —— 预热没有播放会话,宿主服务仍由真实播放按原路径拉起。
         */
        @JvmStatic
        fun prewarm(context: Context, delayMs: Long) {
            if (!KV.get(HawkConfig.KERNEL_PREWARM, false)) return
            val app = context.applicationContext
            prewarmHandler.removeCallbacksAndMessages(null)
            prewarmHandler.postDelayed({ ensurePrewarmed(app) }, Math.max(0L, delayMs))
        }

        /** 内核预热开关变更:开启 = 立即预热;关闭 = 交给引擎恢复空闲释放上界(不打断在用实例) */
        @JvmStatic
        fun onPrewarmPreferenceChanged(context: Context, enabled: Boolean) {
            val app = context.applicationContext
            prewarmHandler.removeCallbacksAndMessages(null)
            if (!enabled) {
                val current = engine
                if (current != null) current.onPrewarmPreferenceChanged(false)
                return
            }
            ensurePrewarmed(app)
        }

        private fun ensurePrewarmed(app: Context) {
            if (!KV.get(HawkConfig.KERNEL_PREWARM, false)) return
            try {
                var current = engine
                if (current == null) {
                    current = PlaybackEngine(app)
                    engine = current
                }
                // 走引擎的开关变更入口:cancelIdleRelease 必须执行 —— 否则预热完会被在途的 60s 空闲释放收走
                current.onPrewarmPreferenceChanged(true)
            } catch (th: Throwable) {
                // 预热失败不得影响启动与正常起播(起播链路会自行建内核)
                LOG.e(TAG + " prewarm failed: " + th.message)
            }
        }

        private fun startHost(app: Context, intent: Intent?) {
            if (instance != null) return
            try {
                if (intent == null) {
                    app.startService(Intent(app, PlaybackService::class.java))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (th: Throwable) {
                // 后台启动服务受限等异常不影响播放(引擎在本进程内已可用)
                LOG.e(TAG + " startService failed: " + th.message)
            }
        }

        // ==================== 媒体会话/通知入口(P3) ====================

        @JvmStatic
        fun isSupported(context: Context?): Boolean {
            if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            return !ScreenUtils.isTv(context)
        }

        /** 维护会话与通知(有音频轨就调用;影视/音乐一视同仁,见 PlaybackController.updateMusicSession) */
        @JvmStatic
        fun updateSession(
            context: Context,
            host: PlaybackHostApi,
            title: String?,
            subtitle: String?,
            artwork: String?,
            position: Long,
            duration: Long,
            playing: Boolean,
        ) {
            if (!isSupported(context)) return
            // 引擎已不在(空闲 TTL 自释放 / 任务移除后)却来了迟到的会话更新:再建就会得到一条**空通知**
            // 加上无人释放的 wake/wifi 锁(释放路径已跑完)。这种调用一律丢弃。
            if (engine == null) return
            owner = WeakReference(host)
            val intent = Intent(context, PlaybackService::class.java).setAction(ACTION_UPDATE)
            intent.putExtra(EXTRA_TITLE, title)
            intent.putExtra(EXTRA_SUBTITLE, subtitle)
            intent.putExtra(EXTRA_ARTWORK, artwork)
            intent.putExtra(EXTRA_POSITION, position)
            intent.putExtra(EXTRA_DURATION, duration)
            intent.putExtra(EXTRA_PLAYING, playing)
            val current = instance
            if (current != null) {
                pendingStart = false
                stopWhenStarted = false
                current.handleSessionIntent(intent)
            } else {
                // ⚠️ 这条是**后台受限启动**的入口(2026-09-19):服务不在时新建会话会走 startHost 的
                // startForegroundService 分支;若此刻 App 已在后台,系统会拒绝把服务提为前台
                // (Service.startForeground() not allowed due to mAllowStartForeground false),
                // 通知随之永远不出现。真机复现路径 = 本集自然播完撤会话后、在后台自动续播下一集。
                // 留痕以区分"服务已在前台只是没通知"与"这次压根没提到前台"。
                LOG.i(
                    TAG + " session update with no live service → startForegroundService"
                        + " (may be denied if app is in background)"
                )
                pendingStart = true
                stopWhenStarted = false
                startHost(context.applicationContext, intent)
            }
        }

        /**
         * 强制结束会话(**不看 host 归属**):直播接管、引擎释放等"非页面驱动"的收尾用。
         * 归属守卫([stopSession])是为"页面 B 不许停页面 A 的会话"而设,这些场景必须绕过它。
         */
        @JvmStatic
        fun forceStopSession(context: Context?) {
            owner = null
            val current = instance
            if (current != null) {
                pendingStart = false
                current.stopPlaybackSession()
            } else if (pendingStart) {
                stopWhenStarted = true
            }
        }

        /** 结束会话:撤通知 + 释放锁与会话资源(**不释放引擎**:播放器仍要跨页面复用) */
        @JvmStatic
        fun stopSession(context: Context, host: PlaybackHostApi?) {
            val current = owner?.get()
            if (host != null && current != null && current !== host) return
            // 归属守卫:owner 是弱引用,页面被回收后 current 为 null ⇒ 守卫会放行任何调用者,留痕以便定位
            LOG.i(
                TAG + " stopSession: ownerMatch=" + (host != null && current === host)
                    + " ownerAlive=" + (current != null) + " host=" + host
            )
            owner = null
            val service = instance
            if (service != null) {
                pendingStart = false
                service.stopPlaybackSession()
            } else if (pendingStart) {
                // FGS 在途:不能 stopService(见 pendingStart 注释),登记"起来就停"
                stopWhenStarted = true
            }
        }

        private fun releaseEngine() {
            val current = engine
            engine = null
            current?.release()
        }

        /**
         * 引擎**自己**释放后的回执(空闲 TTL 到期,见 `PlaybackEngine.IDLE_RELEASE_DELAY_MS`)。
         *
         * 与 [releaseEngine] 的区别:那条路径是服务主动释放(任务移除/服务销毁),静态引用已先清空;
         * 这条是引擎自下而上释放,服务必须把静态引用清掉,否则 `engine()` 会把一个已 released 的引擎继续发给新页面。
         * 引擎没了,服务也没有继续常驻的理由 —— 一并停掉(下次 `engine()` 会重新建引擎并拉起服务)。
         */
        @JvmStatic
        fun onEngineReleased(released: PlaybackEngine) {
            if (engine === released) engine = null
            owner = null
            LOG.i(TAG + " engine self-released (idle)")
            val current = instance
            if (current != null) {
                current.stopPlaybackSession()
                // ⚠️ **不 stopSelf**(2026-09-14 审查修复):stopSelf 到 onDestroy 之间有一段窗口,
                // 期间若用户打开详情页,`engine()` 会新建引擎 E2 而 `startHost` 因 `instance != null` 不拉服务;
                // 随后旧服务 onDestroy → `releaseEngine()` 会把静态 engine(此刻已是 **E2**)释放掉并置 null,
                // 页面拿到一个已释放的引擎 → 黑屏。服务本来就为托管引擎而常驻(P3 起会话结束也不 stopSelf),
                // 引擎可以重建,服务不必跟着销毁。
            }
        }
    }
}
