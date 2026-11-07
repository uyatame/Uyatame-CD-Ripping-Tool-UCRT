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

    /** 重要度の高い通知のチャンネル(フルスクリーン通知用。音も振動も出さない) */
    fun highChannel(nm: NotificationManager, id: String, name: String) {
        if (nm.getNotificationChannel(id) != null) return
        nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    /** PIN・パターン・パスワードなどのロックがあるか */
    fun secure(km: android.app.KeyguardManager): Boolean = km.isKeyguardSecure

    /** システムのロックの解除を求める(PIN・指紋などはシステムが求める)。done(解除できたか) */
    fun dismissKeyguard(a: android.app.Activity, km: android.app.KeyguardManager, done: (Boolean) -> Unit) {
        km.requestDismissKeyguard(a, object : android.app.KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { done(true) }
            override fun onDismissCancelled() { done(false) }
            override fun onDismissError() { done(false) }
        })
    }

    fun startForeground(ctx: Context, i: android.content.Intent) { ctx.startForegroundService(i) }
}
