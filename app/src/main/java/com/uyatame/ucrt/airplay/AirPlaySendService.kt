package com.uyatame.ucrt.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * AirPlay 送信中だけ生きるフォアグラウンドサービス。
 * アプリが裏に回ると Android の通信制限で UDP 送信が EPERM で
 * 弾かれるため、送信セッションの間はここで前面に居続ける。
 * 実送信は AirPlaySendBridge 側が行い、このサービスは生存アンカー。
 */
class AirPlaySendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val target = intent?.getStringExtra(EXTRA_TARGET).orEmpty()
        ensureChannel()
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val icon = applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.stat_sys_data_bluetooth
        val text = if (title.isNotEmpty() && target.isNotEmpty()) "$title → $target" else target
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notif = builder
            .setSmallIcon(icon)
            .setContentTitle("AirPlay送信中")
            .setContentText(text)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AirPlay送信", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "airplay_send"
        private const val NOTIF_ID = 0xA17
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TARGET = "target"

        fun start(ctx: Context, title: String, target: String) {
            val intent = Intent(ctx, AirPlaySendService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TARGET, target)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AirPlaySendService::class.java))
        }
    }
}
