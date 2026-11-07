package com.uyatame.ucrt.legacy

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S

/**
 * ロック画面(本家の LockScreen と同じ仕組み。LockDeck の考え方を取り込んだもの)。
 * システムのロックの上に、再生の画面(LockStageActivity)を重ねて出す。
 *
 * 出し方:
 * 1. 画面が消えたときに先に出しておく(次に画面を点けたとき、すでに前にある)。
 *    Android 9 までは制限が無いので、いつでもこれで出せる。10 からは、「他のアプリの上に重ねて表示」の許可があるか、
 *    UCRT Legacy がホーム画面のときだけ
 * 2. 1 ができないときは、画面が点いたときに、直接開くかフルスクリーン通知(出し方の設定)
 *
 * アプリ(プロセス)が動いている間ずっと、画面の点灯・消灯を受け取る(LegacyApp から登録)
 */
object LockGuard {
    private const val CHANNEL = "lockscreen"
    private const val NOTIF_ID = 7302
    private val main = Handler(Looper.getMainLooper())
    @Volatile var showing = false
    @Volatile private var lastShownAt = 0L
    /** 最後に画面が点いたとき・消えたときの結果(設定の画面に出す) */
    @Volatile var lastStatus = ""
        private set

    private val app get() = LegacyApp.app
    private val p get() = app.prefs

    /** UCRT の画面を使うか(false = 端末標準) */
    val enabled: Boolean get() = p.sp.getBoolean("lockScreen", false)
    /** 出し方: 0 = 自動、1 = 直接だけ、2 = フルスクリーン通知だけ */
    private val launch: Int get() = p.sp.getInt("lockLaunch", 0).coerceIn(0, 2)
    private val whenPaused: Boolean get() = p.sp.getBoolean("lockWhenPaused", true)

    private fun status(s: String) {
        lastStatus = s
        AppLog.log("lock screen: $s")
    }

    fun register(ctx: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching {
                    when (i.action) {
                        Intent.ACTION_SCREEN_OFF -> onScreenOff(c)
                        Intent.ACTION_SCREEN_ON -> main.postDelayed({ runCatching { onScreenOn(c) } }, 350)
                    }
                }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        com.uyatame.ucrt.legacy.compat.Receivers.register(ctx, r, f)
    }

    /** 裏から画面を開けるか(Android 9 までは制限が無い。10 からは、重ねて表示の許可か、ホーム画面のとき) */
    fun canStartFromBackground(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || AppsPage.isDefaultHome(ctx) ||
            (Build.VERSION.SDK_INT >= 23 && runCatching { com.uyatame.ucrt.legacy.compat.Api23.canDrawOverlays(ctx) }.getOrDefault(false))

    /** 見せる曲があるか(一時停止中も出すかの設定を含めて) */
    private fun hasTrack(): Boolean {
        val pl = app.player
        if (pl.current == null) { status(S(R.string.lock_reason_no_track)); return false }
        if (!whenPaused && !pl.isPlaying) { status(S(R.string.lock_reason_paused)); return false }
        return true
    }

    private fun open(ctx: Context): Boolean = runCatching {
        ctx.startActivity(Intent(ctx, LockStageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }.isSuccess

    private fun onScreenOff(ctx: Context) {
        if (!enabled || showing || !hasTrack()) return
        if (launch == 2) return
        if (!canStartFromBackground(ctx)) return
        status(if (open(ctx)) S(R.string.lock_reason_prepared) else S(R.string.lock_reason_start_failed, ""))
    }

    private fun onScreenOn(ctx: Context) {
        if (!enabled) return
        if (showing) return status(S(R.string.lock_reason_prepared))
        if (!hasTrack()) return
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val locked = km.inKeyguardRestrictedInputMode()
        // 「端末がロックされていなくても出す」がオフなら、ロックされているときだけ
        if (!locked && !p.sp.getBoolean("lockAlways", true)) return status(S(R.string.lock_reason_not_locked))
        val now = SystemClock.elapsedRealtime()
        if (now - lastShownAt < 1500) return
        lastShownAt = now
        if (launch == 1 || (launch == 0 && (canStartFromBackground(ctx) || !locked))) {
            val ok = open(ctx)
            status(if (ok) S(R.string.lock_reason_started) else S(R.string.lock_reason_start_failed, ""))
            if (ok || launch == 1) return
        }
        // フルスクリーン通知(アラーム・電話と同じ仕組み)。Android 14 からは許可が要る(無いと、ただの通知になり、押すまで開かない)
        if (!fullScreenAllowed(ctx)) {
            status(S(R.string.lock_reason_need_full_screen))
            // 許可が無くても、直接の起動を試す(端末によっては通る)
            open(ctx)
            return
        }
        postFullScreen(ctx)
        status(S(R.string.lock_reason_posted))
        // 通知だけで開かない端末のために、少ししたら直接の起動も試す(本家と同じ。出ていれば singleInstance で 1 つにまとまる)
        main.postDelayed({
            if (!showing) {
                open(ctx)
                main.postDelayed({ if (!showing) status(S(R.string.lock_reason_not_opened)) }, 1500)
            } else cancelNotification(ctx)
        }, 600)
    }

    /** フルスクリーン通知を使えるか(Android 14 からは設定での許可が要る。それより前は、いつも使える) */
    fun fullScreenAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return runCatching { com.uyatame.ucrt.legacy.compat.Api34.canUseFullScreen(nm) }.getOrDefault(false)
    }

    private fun postFullScreen(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val i = Intent(ctx, LockStageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(ctx, 3, i, flags)
        val b = if (Build.VERSION.SDK_INT >= 26) {
            com.uyatame.ucrt.legacy.compat.Api26.highChannel(nm, CHANNEL, S(R.string.lock_screen_channel))
            com.uyatame.ucrt.legacy.compat.Api26.builder(ctx, CHANNEL)
        } else @Suppress("DEPRECATION") Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.ic_stat_ucrt).setContentTitle(S(R.string.lock_screen_channel)).setAutoCancel(true).setFullScreenIntent(pi, true)
        // 種類を「アラーム」にする(Android 5.0 から。フルスクリーンで開いてもらいやすい。本家と同じ)
        if (Build.VERSION.SDK_INT >= 21) com.uyatame.ucrt.legacy.compat.Api21.alarmCategory(b)
        // 優先度と組み立ては Android 4.1 から(古い Android でクラスの検証に失敗しないよう、compat に分けてある)
        if (Build.VERSION.SDK_INT >= 16) com.uyatame.ucrt.legacy.compat.Api16.priority(b)
        @Suppress("DEPRECATION")
        runCatching { nm.notify(NOTIF_ID, if (Build.VERSION.SDK_INT >= 16) com.uyatame.ucrt.legacy.compat.Api16.build(b) else b.notification) }
    }

    fun cancelNotification(ctx: Context) {
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) }
    }

    /** 設定の「ロック画面を見てみる」 */
    fun preview(ctx: Context) { open(ctx) }
}
