package com.uyatame.ucrt.legacy.compat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Android 8.0 から(通知のチャンネル) */
object Api26 {
    fun channel(nm: NotificationManager, id: String, name: String) {
        if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW))
    }

    fun builder(ctx: Context, channel: String): Notification.Builder = Notification.Builder(ctx, channel)

    fun startForeground(ctx: Context, i: android.content.Intent) { ctx.startForegroundService(i) }
}
