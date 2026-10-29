package com.uyatame.ucrt.legacy.compat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build

/** Android 13 から: 受信口を作るときに、ほかのアプリから受け取るかを指定する(Android 14 からは指定しないと例外) */
object Api33 {
    /** 戻る操作を受け取る(画面が閉じるまで) */
    fun onBack(act: android.app.Activity, handler: () -> Unit) {
        act.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            android.window.OnBackInvokedCallback { handler() },
        )
    }

    fun register(ctx: Context, r: BroadcastReceiver, f: IntentFilter) {
        ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
    }
}

/** 受信口を作る(ほかのアプリからは受け取らない。システムの知らせと、自分の PendingIntent は受け取れる) */
object Receivers {
    fun register(ctx: Context, r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) Api33.register(ctx, r, f) else ctx.registerReceiver(r, f)
    }
}
