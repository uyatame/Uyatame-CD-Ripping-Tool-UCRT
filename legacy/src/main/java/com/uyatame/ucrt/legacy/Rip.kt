package com.uyatame.ucrt.legacy

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.TextView
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.ui.Btn
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.ProgressBarView
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.button
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.icon
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox

/**
 * 取り込み・コピーなどの進み具合の帯(本家の RipBar。ミニプレーヤーの上に出す)と、その通知。
 * 帯を押すとディスクのタブへ。「中止」は確かめてから止める
 */
object Rip {
    private class Holder(val title: TextView, val sub: TextView, val bar: ProgressBarView)

    /** 取り込み中でなければ null */
    fun bar(act: LegacyActivity): View? {
        if (!Disc.busy) return null
        val box = vbox(act)
        box.setBackgroundColor(Pal.primaryContainer)
        box.setPadding(dp(16), dp(6), dp(4), dp(6))
        val row = hbox(act)
        row.add(icon(act, Icons.Download, Pal.onPrimaryContainer, 20), w = 20, h = 20, r = 12)
        val col = vbox(act)
        val t = text(act, "", Type.TitleSmall, Pal.onPrimaryContainer)
        val s = text(act, "", Type.BodySmall, Pal.onPrimaryContainer)
        col.add(t); col.add(s)
        row.add(col, w = 0, weight = 1f)
        row.add(button(act, S(R.string.stop), Btn.Text) { confirmStop(act) }, w = WRAP)
        box.add(row)
        val pb = ProgressBarView(act, 0f, 4)
        pb.trackColor = Pal.alpha(Pal.onPrimaryContainer, 0.15f)
        box.add(pb, t = 4, r = 12)
        box.isClickable = true
        box.setOnClickListener { act.onTab(1) }
        box.tag = Holder(t, s, pb)
        update(act, box)
        return box
    }

    /** 進み具合だけを書き換える */
    fun update(ctx: Context, v: View?) {
        val h = v?.tag as? Holder ?: return
        val p = Disc.ripOverall
        val elapsed = System.currentTimeMillis() - Disc.ripStartMs
        val eta = if (p > 0.03f && Disc.ripStartMs > 0) ((elapsed / p) * (1 - p)).toLong() else -1L
        h.title.text = Disc.busyTitle()
        h.sub.text = "${(p * 100).toInt()}%" + if (eta >= 0) S(R.string.about_left, fmtMs(eta)) else ""
        h.bar.progress = p
    }

    private fun confirmStop(act: LegacyActivity) {
        val d = UDialog(act).title(S(R.string.stop_ripping)).message(S(R.string.track_being_ripped_wont_be))
        d.button(S(R.string.continue_label)) { d.dismiss() }
        d.button(S(R.string.stop_2)) { d.dismiss(); Disc.cancelRip() }
        d.show()
    }

    // ---- 通知(画面を閉じていても進み具合が分かるように) ----

    private const val ID = 2
    private const val CHANNEL = "rip"
    private var lastPct = -1
    private var shown = false

    /** 取り込みの知らせのたびに呼ぶ(LegacyApp から。画面を閉じていても) */
    fun notify(ctx: Context, on: Boolean) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!on) {
            if (shown) { nm.cancel(ID); shown = false; lastPct = -1 }
            return
        }
        val pct = (Disc.ripOverall * 100).toInt()
        if (pct == lastPct && shown) return
        lastPct = pct
        if (Build.VERSION.SDK_INT >= 26) com.uyatame.ucrt.legacy.compat.Api26.channel(nm, CHANNEL, S(R.string.ripping))
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(ctx, 4, Intent(ctx, LegacyActivity::class.java), flags or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) com.uyatame.ucrt.legacy.compat.Api26.builder(ctx, CHANNEL) else Notification.Builder(ctx)
        b.setContentTitle(if (Disc.busyKind == 0) S(R.string.ripping_cd, Disc.ripIndex + 1, Disc.ripCount) else Disc.busyTitle())
            .setContentText("$pct%")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .setProgress(100, pct, false)
        @Suppress("DEPRECATION")
        val n = if (Build.VERSION.SDK_INT >= 16) com.uyatame.ucrt.legacy.compat.Api16.build(b) else b.notification
        runCatching { nm.notify(ID, n) }
        shown = true
    }
}
