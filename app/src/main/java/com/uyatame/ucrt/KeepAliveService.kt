package com.uyatame.ucrt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import com.uyatame.ucrt.player.PlayerController

/** 通知に出す内容 */
class NotifInfo(
    val media: Boolean,
    val title: String,
    val text: String,
    val playing: Boolean = false,
    val progress: Int = -1,
    val type: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
)

/** サービスと再生処理の橋渡し(通知ボタンの操作を再生処理に伝える) */
object MediaBridge {
    @Volatile var player: PlayerController? = null
    @Volatile var info: NotifInfo? = null
    @Volatile var running = false
}

/** 再生・取り込み中にアプリが止められないようにする前面サービス(通知を表示) */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> MediaBridge.player?.toggle()
            ACTION_NEXT -> MediaBridge.player?.next()
            ACTION_PREV -> MediaBridge.player?.prev()
        }
        val info = MediaBridge.info ?: NotifInfo(false, T("処理中", "Working"), "")
        val n = build(this, info)
        try {
            startForeground(ID, n, info.type)
        } catch (e: Exception) {
            runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        }
        MediaBridge.running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        MediaBridge.running = false
        super.onDestroy()
    }

    companion object {
        const val ID = 1
        const val ACTION_TOGGLE = "com.uyatame.ucrt.TOGGLE"
        const val ACTION_NEXT = "com.uyatame.ucrt.NEXT"
        const val ACTION_PREV = "com.uyatame.ucrt.PREV"

        private fun action(ctx: Context, act: String, req: Int): PendingIntent = PendingIntent.getService(
            ctx, req, Intent(ctx, KeepAliveService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** 通知チャンネルを作ったか(取り込み中は通知を何度も更新するので、作るのは最初の 1 回だけ) */
        @Volatile private var channelReady = false

        fun build(ctx: Context, info: NotifInfo): Notification {
            if (!channelReady) {
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel("work", T("再生・取り込み", "Playback & ripping"), NotificationManager.IMPORTANCE_LOW),
                )
                channelReady = true
            }
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val b = Notification.Builder(ctx, "work")
                .setContentTitle(info.title)
                .setContentText(info.text)
                .setSmallIcon(R.drawable.ic_stat_ucrt)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
            val p = MediaBridge.player
            if (info.media && p != null) {
                fun act(icon: Int, label: String, a: String, req: Int) =
                    Notification.Action.Builder(Icon.createWithResource(ctx, icon), label, action(ctx, a, req)).build()
                b.addAction(act(android.R.drawable.ic_media_previous, T("前の曲", "Previous"), ACTION_PREV, 1))
                b.addAction(
                    if (info.playing) act(android.R.drawable.ic_media_pause, T("一時停止", "Pause"), ACTION_TOGGLE, 2)
                    else act(android.R.drawable.ic_media_play, T("再生", "Play"), ACTION_TOGGLE, 2),
                )
                b.addAction(act(android.R.drawable.ic_media_next, T("次の曲", "Next"), ACTION_NEXT, 3))
                b.setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(p.session.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2),
                )
            } else if (info.progress >= 0) {
                b.setProgress(100, info.progress, false)
            }
            return b.build()
        }

        /** 通知を出す・更新する。サービスが動いていなければ起動する */
        fun show(ctx: Context, info: NotifInfo) {
            MediaBridge.info = info
            if (MediaBridge.running) {
                runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, info)) }
            } else {
                runCatching { ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java)) }
            }
        }

        fun hide(ctx: Context) {
            MediaBridge.info = null
            runCatching { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) }
            MediaBridge.running = false
        }
    }
}
