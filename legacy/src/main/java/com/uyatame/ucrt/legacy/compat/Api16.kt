package com.uyatame.ucrt.legacy.compat

import android.app.Notification
import android.app.PendingIntent

/** Android 4.1 から(通知のボタンと build) */
object Api16 {
    @Suppress("DEPRECATION")
    fun action(b: Notification.Builder, title: CharSequence, pi: PendingIntent) { b.addAction(0, title, pi) }

    fun build(b: Notification.Builder): Notification = b.build()
}
