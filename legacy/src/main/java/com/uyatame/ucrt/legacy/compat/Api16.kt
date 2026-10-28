package com.uyatame.ucrt.legacy.compat

import android.app.Notification
import android.app.PendingIntent

/** Android 4.1 から(通知のボタンと build) */
object Api16 {
    @Suppress("DEPRECATION")
    fun action(b: Notification.Builder, title: CharSequence, pi: PendingIntent, icon: Int = 0) { b.addAction(icon, title, pi) }

    /** 大きく広げたときの表示を優先する(4.1〜4.4 で、ボタンが出るように) */
    fun priority(b: Notification.Builder) { b.setPriority(Notification.PRIORITY_HIGH) }

    fun build(b: Notification.Builder): Notification = b.build()
}
