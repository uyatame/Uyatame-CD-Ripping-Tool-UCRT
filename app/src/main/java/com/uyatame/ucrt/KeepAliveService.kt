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
    /** アプリを終了する(通知の「終了」から。MainViewModel が設定する) */
    @Volatile var quit: (() -> Unit)? = null
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
            ACTION_QUIT -> {
                // アプリを終了する(USB DAC を Android に返してから)
                val q = MediaBridge.quit
                if (q != null) q() else {
                    runCatching { com.uyatame.ucrt.player.usb.UsbDac.close(reattach = true) }
                    stopSelf()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                return START_NOT_STICKY
            }
        }
        val info = MediaBridge.info ?: NotifInfo(false, S(R.string.working), "")
        val n = build(this, info)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(ID, n, info.type)
            } catch (e: Exception) {
                val ok = runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }.isSuccess
                if (!ok) {
                    // 前面サービスにできなかった(裏から起動できない状態など): 止めておく(そのままだとアプリごと止められる)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        } else {
            // Android 9 以前は種類を指定しない
            startForeground(ID, n)
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
        const val ACTION_QUIT = "com.uyatame.ucrt.QUIT"

        private fun action(ctx: Context, act: String, req: Int): PendingIntent = PendingIntent.getService(
            ctx, req, Intent(ctx, KeepAliveService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** 通知チャンネルを作ったか(取り込み中は通知を何度も更新するので、作るのは最初の 1 回だけ) */
        @Volatile private var channelReady = false

        fun build(ctx: Context, info: NotifInfo): Notification {
            // 通知チャンネルは Android 8.0 から
            if (!channelReady && android.os.Build.VERSION.SDK_INT >= 26) {
                androidx.core.content.ContextCompat.getSystemService(ctx, NotificationManager::class.java)!!.createNotificationChannel(
                    NotificationChannel("work", S(R.string.playback_and_ripping), NotificationManager.IMPORTANCE_LOW),
                )
                channelReady = true
            }
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val b = (if (android.os.Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, "work") else @Suppress("DEPRECATION") Notification.Builder(ctx))
                .setContentTitle(info.title)
                .setContentText(info.text)
                .setSmallIcon(R.drawable.ic_stat_ucrt)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
            val p = MediaBridge.player
            if (info.media && p != null) {
                // Icon は Android 6.0 から。5.x は番号でアイコンを渡す
                fun act(icon: Int, label: String, a: String, req: Int) =
                    (if (android.os.Build.VERSION.SDK_INT >= 23) Notification.Action.Builder(Icon.createWithResource(ctx, icon), label, action(ctx, a, req))
                    else @Suppress("DEPRECATION") Notification.Action.Builder(icon, label, action(ctx, a, req))).build()
                b.addAction(act(android.R.drawable.ic_media_previous, S(R.string.previous), ACTION_PREV, 1))
                b.addAction(
                    if (info.playing) act(android.R.drawable.ic_media_pause, S(R.string.pause), ACTION_TOGGLE, 2)
                    else act(android.R.drawable.ic_media_play, S(R.string.play), ACTION_TOGGLE, 2),
                )
                b.addAction(act(android.R.drawable.ic_media_next, S(R.string.next), ACTION_NEXT, 3))
                // 終了(USB DAC を Android に返してアプリを終える)。小さく畳んだ通知には出さない
                b.addAction(act(android.R.drawable.ic_menu_close_clear_cancel, S(R.string.quit_app), ACTION_QUIT, 4))
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
                runCatching { androidx.core.content.ContextCompat.getSystemService(ctx, NotificationManager::class.java)!!.notify(ID, build(ctx, info)) }
            } else {
                runCatching {
                    // 前面サービスとして始めるのは Android 8.0 から。それより前は普通に始める
                    val i = Intent(ctx, KeepAliveService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                }
            }
        }

        fun hide(ctx: Context) {
            MediaBridge.info = null
            runCatching { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) }
            MediaBridge.running = false
        }
    }
}
