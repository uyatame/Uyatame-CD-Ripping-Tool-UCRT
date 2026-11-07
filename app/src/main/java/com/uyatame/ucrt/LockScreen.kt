package com.uyatame.ucrt

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * ロック画面(システムのロックの上に、再生画面を重ねて出す)。
 *
 * Android では、アプリがロック画面そのものを置き換えることはできない。そこで、画面が点いてシステムのロックが出たときに、
 * ロックの上に重なるアクティビティ(LockScreenActivity)を出し、再生画面を見せる。解除のスワイプでは、システムの
 * KeyguardManager.requestDismissKeyguard を呼ぶので、PIN・指紋などが設定されていれば、システムが認証を求める(アプリが代わりには認証しない)。
 *
 * 出し方は 2 つ(ほかの音楽アプリのロック画面と同じ考え方):
 * 1. 画面が消えたときに、先に出しておく(次に画面を点けたとき、すでにロック画面が前にある)。
 *    裏からアクティビティを開くことは Android 10 から制限されているので、「他のアプリの上に重ねて表示」の許可があるときだけ。
 * 2. 1 ができないときは、画面が点いたときに、フルスクリーン通知(アラーム・電話と同じ仕組み)で起こす。
 *    Android 14 からは、フルスクリーン通知を使うのに、設定での許可(特別なアプリのアクセス)が要る。
 *    端末によってはフルスクリーン通知でアクティビティを開かない(通知が出るだけ)ので、1 の許可を勧める。
 */
object LockScreen {
    private const val CHANNEL = "lockscreen"
    private const val NOTIF_ID = 7301
    /** 設定の「ロック画面を見てみる」から開いた印(ロックされていなくても閉じない) */
    const val EXTRA_PREVIEW = "preview"

    private var receiver: BroadcastReceiver? = null
    /** 最後にロック画面を出した時刻(SystemClock.elapsedRealtime)。短い間に何度も出さないため */
    @Volatile private var lastShownAt = 0L
    /** ロック画面が出ているか(LockScreenActivity が前に出ている間。裏に回ったら外れる) */
    @Volatile var showing = false

    /** 最後に画面が点いたとき、ロック画面を出したか・出さなかった理由(設定の画面に出す。原因を調べやすいように) */
    var lastStatus by androidx.compose.runtime.mutableStateOf("")
        private set

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun status(s: String) {
        lastStatus = s
        com.uyatame.ucrt.player.AudioEngine.log("lock screen: $s")
    }

    /** 画面が点いたときの合図を受け取り始める(MainViewModel が生きている間) */
    fun register(ctx: Context, vm: MainViewModel) {
        unregister(ctx)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_ON -> runCatching { onScreenOn(c, vm) }
                    Intent.ACTION_SCREEN_OFF -> runCatching { onScreenOff(c, vm) }
                }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    fun unregister(ctx: Context) {
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
    }

    /**
     * 裏からアクティビティを開けるか。Android 9 までは制限が無い。10 からは、「他のアプリの上に重ねて表示」の許可があるか、
     * UCRT が既定のホームアプリなら開ける(ホームアプリは、裏から画面を開く制限の例外)
     */
    fun canStartFromBackground(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || HomeApps.isDefaultHome(ctx) ||
            runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    /** うまく出ないときの切り分け用(設定の「診断をコピー」。貼ってもらえば原因を絞れる) */
    fun diagnose(ctx: Context, vm: MainViewModel): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val s = vm.settings.value
        return buildString {
            appendLine("UCRT lock screen / ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            appendLine("mode=${if (s.lockScreen) "UCRT" else "system"} launch=${s.lockLaunch} whenPaused=${s.lockWhenPaused} always=${s.lockAlways}")
            appendLine("notifications=${notificationsAllowed(ctx)} fullScreenIntent=${fullScreenAllowed(ctx)} enabled=${runCatching { nm.areNotificationsEnabled() }.getOrNull()}")
            appendLine("overlay=${Build.VERSION.SDK_INT >= 23 && runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false)} home=${HomeApps.isDefaultHome(ctx)} canStart=${canStartFromBackground(ctx)}")
            appendLine("keyguardLocked=${km.isKeyguardLocked} secure=${km.isKeyguardSecure} showing=$showing")
            appendLine("playing=${vm.player.isPlaying} track=${vm.player.current != null}")
            appendLine("batteryOptimizationIgnored=${runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrNull()}")
            appendLine("last=$lastStatus")
        }
    }

    /** 「他のアプリの上に重ねて表示」の許可の画面を開く */
    fun openOverlaySettings(ctx: Context) {
        val i = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}"))
        if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** 画面が消えたとき: 許可があれば、ロック画面を先に出しておく(画面は点けない) */
    /** 最後に画面が消えた・点いた時刻(SystemClock.elapsedRealtime。ロック画面が出るまでの時間を記録に残すため) */
    @Volatile var screenOffAt = 0L
        private set
    @Volatile var screenOnAt = 0L
        private set

    private fun onScreenOff(ctx: Context, vm: MainViewModel) {
        screenOffAt = android.os.SystemClock.elapsedRealtime()
        val s = vm.settings.value
        if (!s.lockScreen) return
        if (vm.player.current == null) return status(S(R.string.lock_reason_no_track))
        if (!s.lockWhenPaused && !vm.player.isPlaying) return status(S(R.string.lock_reason_paused))
        if (showing) return
        // 出し方が「フルスクリーン通知だけ」なら、画面が点いたときに出す
        if (s.lockLaunch == 2) return
        if (!canStartFromBackground(ctx)) {
            com.uyatame.ucrt.player.AudioEngine.log("lock screen: screen off, no overlay permission (will try when the screen turns on)")
            return
        }
        val ok = runCatching {
            ctx.startActivity(Intent(ctx, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
        status(if (ok.isSuccess) S(R.string.lock_reason_prepared) else S(R.string.lock_reason_start_failed, ok.exceptionOrNull()?.javaClass?.simpleName ?: ""))
    }

    private fun onScreenOn(ctx: Context, vm: MainViewModel) {
        screenOnAt = android.os.SystemClock.elapsedRealtime()
        if (!vm.settings.value.lockScreen) return
        // 画面が消えたときに出しておいたものが、もう前にある
        if (showing) return status(S(R.string.lock_reason_prepared))
        // 画面が点いた直後は、システムのロックの状態がまだ変わりきっていないことがあるので、少し待ってから判断する
        main.postDelayed({ runCatching { decide(ctx, vm) } }, 350)
    }

    private fun decide(ctx: Context, vm: MainViewModel) {
        if (!vm.settings.value.lockScreen) return
        // 見せる曲が無いときは、システムのロック画面のままにする
        if (vm.player.current == null) return status(S(R.string.lock_reason_no_track))
        if (!vm.settings.value.lockWhenPaused && !vm.player.isPlaying) return status(S(R.string.lock_reason_paused))
        if (showing) return status(S(R.string.lock_reason_showing))
        val launch = vm.settings.value.lockLaunch
        // システムのロックが出ているときだけ(ロックが解けた状態で画面が点いたときは出さない)
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val locked = km.isKeyguardLocked
        // 「端末がロックされていなくても出す」がオフなら、ロックされているときだけ
        if (!locked && !vm.settings.value.lockAlways) return status(S(R.string.lock_reason_not_locked))
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastShownAt < 1500) return
        lastShownAt = now
        if (!notificationsAllowed(ctx)) return status(S(R.string.lock_reason_need_notifications))
        // 直接開く(「他のアプリの上に重ねて表示」の許可があるか、ホーム画面のとき。出し方が「直接だけ」なら、許可が無くても試す)
        // (自動: 開ける許可があるか、ロックされていない(全画面通知は、ロック中でないと画面を開かない)なら直接)
        if (launch == 1 || (launch == 0 && (canStartFromBackground(ctx) || !locked))) {
            val ok = runCatching { ctx.startActivity(Intent(ctx, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            status(if (ok.isSuccess) S(R.string.lock_reason_started) else S(R.string.lock_reason_start_failed, ok.exceptionOrNull()?.javaClass?.simpleName ?: ""))
            if (ok.isSuccess || launch == 1) return
        }
        if (!fullScreenAllowed(ctx)) {
            // フルスクリーン通知が使えなくても、直接の起動を試す(端末によっては通る)
            status(S(R.string.lock_reason_need_full_screen))
            runCatching { ctx.startActivity(Intent(ctx, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        post(ctx)
        status(S(R.string.lock_reason_posted))
        // 通知だけで出ない端末のために、直接の起動も試す(出ていれば、同じ画面が二重に出ないよう、singleInstance で 1 つにまとまる)
        main.postDelayed({
            if (!showing) {
                runCatching { ctx.startActivity(Intent(ctx, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                // 少し待っても出ていなければ、この端末では通知からも開けなかった(許可を勧める)
                main.postDelayed({ if (!showing) status(S(R.string.lock_reason_not_opened)) }, 1500)
            }
        }, 600)
    }

    /** ロック画面を、フルスクリーン通知で起こす */
    private fun post(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!canPost(ctx)) return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            // 音も振動も出さない(画面に出すためだけの通知。重要度は高くないと、フルスクリーンで出ない)
            val ch = NotificationChannel(CHANNEL, S(R.string.lock_screen_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(ch)
        }
        val open = Intent(ctx, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.ic_stat_ucrt)
            .setContentTitle(S(R.string.lock_screen_channel))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(false)
            .setAutoCancel(true)
            .setFullScreenIntent(pi, true)
        // 出し終えたら、通知は残さない(出ない場合に残り続けないよう、短い時間で消える)
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(5000)
        runCatching { nm.notify(NOTIF_ID, b.build()) }
    }

    /** 出し終えた(LockScreenActivity が出た)ので、通知を取り消す */
    fun cancelNotification(ctx: Context) {
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) }
    }

    /** 通知を出せるか(Android 13 からは通知の許可が要る) */
    fun notificationsAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        }.getOrDefault(true)
    }

    /** フルスクリーン通知を使えるか(Android 14 からは、設定での許可が要る。それより前は、いつも使える) */
    fun fullScreenAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
        }.getOrDefault(false)
    }

    /** ロック画面を出せる条件がそろっているか */
    fun canPost(ctx: Context): Boolean = notificationsAllowed(ctx) && fullScreenAllowed(ctx)

    /** フルスクリーン通知の許可の画面を開く(Android 14 から)。それより前は、通知の設定を開く */
    fun openPermissionSettings(ctx: Context) {
        val intents = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= 34) {
            intents += Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:${ctx.packageName}"))
        }
        intents += Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        for (i in intents) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    /** 設定の「いま見る」: ロックが解けた状態でも、ロック画面を出して見た目と動きを確かめられる(解除のスワイプは、すぐに閉じる) */
    fun preview(ctx: Context) {
        runCatching { ctx.startActivity(Intent(ctx, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_PREVIEW, true)) }
    }
}
