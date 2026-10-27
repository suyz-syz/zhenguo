package com.duanju.duanju_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 锁屏后台播放的前台服务。
 *
 * 以 mediaPlayback 类型前台服务保活进程并持有 WakeLock，配合 MediaSession
 * 在系统通知栏 / 锁屏上显示媒体控制条（播放/暂停/上一集/下一集）。
 *
 * 控制命令通过 MethodChannel("duanju/device") 转发到 Flutter 侧，由
 * PlayerScreen 实际执行播放/暂停/切集。
 */
class MediaPlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "zhenguojian_playback"
        private const val NOTIFICATION_ID = 2407
        private const val CHANNEL = "duanju/device"
        const val ACTION_START = "com.duanju.duanju_app.MEDIA_PLAYBACK_START"
        const val ACTION_STOP = "com.duanju.duanju_app.MEDIA_PLAYBACK_STOP"
        const val ACTION_PLAY = "com.duanju.duanju_app.MEDIA_PLAYBACK_PLAY"
        const val ACTION_PAUSE = "com.duanju.duanju_app.MEDIA_PLAYBACK_PAUSE"
        const val ACTION_PREV = "com.duanju.duanju_app.MEDIA_PLAYBACK_PREV"
        const val ACTION_NEXT = "com.duanju.duanju_app.MEDIA_PLAYBACK_NEXT"

        private const val ACTION_STATE =
            "com.duanju.duanju_app.MEDIA_PLAYBACK_STATE"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLAYING = "playing"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_DURATION = "duration"

        private const val WAKE_LOCK_TAG =
            "com.duanju.duanju_app:media_playback"

        var engine: FlutterEngine? = null
            private set

        @Volatile
        private var instance: MediaPlaybackService? = null

        /** 供 MainActivity 在 onCreate 时注入 FlutterEngine。 */
        fun attachEngine(flutterEngine: FlutterEngine?) {
            engine = flutterEngine
        }

        /** 启动前台服务（在前台上下文中调用）。 */
        fun start(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止前台服务。 */
        fun stop(context: Context) {
            val running = instance
            if (running != null) {
                running.stopSelf()
                return
            }
            runCatching {
                context.startService(
                    Intent(context, MediaPlaybackService::class.java).setAction(ACTION_STOP)
                )
            }
        }

        /** 更新通知栏显示状态。 */
        fun updateState(
            context: Context,
            title: String,
            playing: Boolean,
            position: Long,
            duration: Long,
        ) {
            val intent = Intent(context, MediaPlaybackService::class.java)
                .setAction(ACTION_STATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PLAYING, playing)
                .putExtra(EXTRA_POSITION, position)
                .putExtra(EXTRA_DURATION, duration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun notifyControl(channel: MethodChannel?, method: String) {
            if (channel == null) return
            runCatching {
                channel.invokeMethod(method, null)
            }
        }

        /** 处理 MediaSession 回调，转发到 Flutter。 */
        private fun forwardControl(method: String) {
            val engine = engine
            if (engine == null) return
            val channel = MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL)
            notifyControl(channel, method)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var title: String = "短剧"
    private var playing: Boolean = true
    private var position: Long = 0
    private var duration: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        mediaSession = MediaSession(this, "zhenguojian_playback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    playing = true
                    forwardControl("mediaControlPlay")
                }

                override fun onPause() {
                    playing = false
                    forwardControl("mediaControlPause")
                }

                override fun onSkipToNext() {
                    forwardControl("mediaControlNext")
                }

                override fun onSkipToPrevious() {
                    forwardControl("mediaControlPrevious")
                }
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY -> {
                playing = true
                forwardControl("mediaControlPlay")
            }
            ACTION_PAUSE -> {
                playing = false
                forwardControl("mediaControlPause")
            }
            ACTION_PREV -> forwardControl("mediaControlPrevious")
            ACTION_NEXT -> forwardControl("mediaControlNext")
            ACTION_STATE -> {
                intent.getStringExtra(EXTRA_TITLE)?.let { title = it }
                playing = intent.getBooleanExtra(EXTRA_PLAYING, playing)
                position = intent.getLongExtra(EXTRA_POSITION, 0)
                duration = intent.getLongExtra(EXTRA_DURATION, 0)
            }
        }

        acquireWakeLock()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        // 需要媒体会话才会在锁屏/通知栏显示控制条。
        mediaSession?.isActive = true
        return START_STICKY
    }

    private fun acquireWakeLock() {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (wakeLock == null) {
            wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG,
            ).apply { setReferenceCounted(false) }
        }
        // setReferenceCounted(false) 下重复 acquire 会刷新超时，保持后台解码唤醒。
        wakeLock?.acquire(8 * 60 * 60 * 1000L /* 8h */)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "锁屏后台播放",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "后台播放时显示当前剧目与播放控制"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val playPause = if (playing) {
            serviceAction(this, ACTION_PAUSE, "暂停", android.R.drawable.ic_media_pause)
        } else {
            serviceAction(this, ACTION_PLAY, "播放", android.R.drawable.ic_media_play)
        }
        val prev = serviceAction(this, ACTION_PREV, "上一集", android.R.drawable.ic_media_previous)
        val next = serviceAction(this, ACTION_NEXT, "下一集", android.R.drawable.ic_media_next)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(if (playing) "播放中" else "已暂停")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle().apply {
                    mediaSession?.sessionToken?.let { setMediaSession(it) }
                    setShowActionsInCompactView(0, 1, 2)
                }
            )
            .addAction(prev)
            .addAction(playPause)
            .addAction(next)
        return builder.build()
    }

    private fun serviceAction(
        context: Context,
        action: String,
        label: String,
        icon: Int,
    ): NotificationCompat.Action {
        val intent = Intent(context, MediaPlaybackService::class.java).setAction(action)
        val pending = PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(icon, label, pending).build()
    }

    override fun onDestroy() {
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        if (instance === this) instance = null
        super.onDestroy()
    }
}