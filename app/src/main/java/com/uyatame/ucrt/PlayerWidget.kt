package com.uyatame.ucrt

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews

/**
 * ホーム画面のウィジェット(再生画面の小さな版)。縦長と横長の 2 種類。
 * ジャケット・曲名・アーティストと、前の曲・再生 / 一時停止・次の曲のボタン。
 * ジャケットや曲名をタップするとアプリを開く
 */
abstract class PlayerWidget : AppWidgetProvider() {
    abstract val layout: Int

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = views(ctx, layout, javaClass)
        for (id in ids) mgr.updateAppWidget(id, v)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val p = MediaBridge.player
        when (intent.action) {
            ACTION_TOGGLE, ACTION_NEXT, ACTION_PREV -> {
                if (p == null || p.current == null) {
                    // アプリが動いていない・何も再生していないときは、アプリを開く
                    openApp(ctx)
                } else {
                    when (intent.action) {
                        ACTION_TOGGLE -> p.toggle()
                        ACTION_NEXT -> p.next()
                        ACTION_PREV -> p.prev()
                    }
                }
                return
            }
        }
        super.onReceive(ctx, intent)
    }

    /** 再生中の曲の情報(ウィジェットに出すもの) */
    class State(val title: String, val artist: String, val playing: Boolean, val art: Bitmap?)

    companion object {
        private const val ACTION_TOGGLE = "com.uyatame.ucrt.widget.TOGGLE"
        private const val ACTION_NEXT = "com.uyatame.ucrt.widget.NEXT"
        private const val ACTION_PREV = "com.uyatame.ucrt.widget.PREV"

        @Volatile private var state: State? = null

        private fun openApp(ctx: Context) {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { ctx.startActivity(i) }
        }

        private fun views(ctx: Context, layout: Int, cls: Class<*>): RemoteViews {
            val v = RemoteViews(ctx.packageName, layout)
            val s = state
            if (s == null) {
                v.setTextViewText(R.id.widget_title, ctx.getString(R.string.widget_idle))
                v.setTextViewText(R.id.widget_artist, ctx.getString(R.string.widget_idle_sub))
                v.setImageViewResource(R.id.widget_art, R.drawable.ic_launcher_foreground)
                v.setImageViewResource(R.id.widget_play, android.R.drawable.ic_media_play)
            } else {
                v.setTextViewText(R.id.widget_title, s.title)
                v.setTextViewText(R.id.widget_artist, s.artist)
                if (s.art != null) v.setImageViewBitmap(R.id.widget_art, s.art) else v.setImageViewResource(R.id.widget_art, R.drawable.ic_launcher_foreground)
                v.setImageViewResource(R.id.widget_play, if (s.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            }
            fun action(a: String, code: Int): PendingIntent =
                PendingIntent.getBroadcast(ctx, code, Intent(ctx, cls).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val base = if (cls == PlayerWidgetWide::class.java) 20 else 10
            v.setOnClickPendingIntent(R.id.widget_prev, action(ACTION_PREV, base + 1))
            v.setOnClickPendingIntent(R.id.widget_play, action(ACTION_TOGGLE, base + 2))
            v.setOnClickPendingIntent(R.id.widget_next, action(ACTION_NEXT, base + 3))
            val open = PendingIntent.getActivity(
                ctx, base, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_art, open)
            v.setOnClickPendingIntent(R.id.widget_title, open)
            v.setOnClickPendingIntent(R.id.widget_artist, open)
            return v
        }

        private var last: Triple<String, Boolean, Bitmap?>? = null

        /** 再生中の曲が変わった・再生 / 一時停止が変わったら呼ぶ。null なら「再生していません」 */
        fun update(ctx: Context, s: State?) {
            val key = s?.let { Triple(it.title + "\u0000" + it.artist, it.playing, it.art) }
            if (key == last) return
            last = key
            // ウィジェットに送る画像は小さくする(大きすぎると送れない)
            state = s?.let {
                val a = it.art
                val small = if (a != null && maxOf(a.width, a.height) > 360) {
                    val k = 360f / maxOf(a.width, a.height)
                    Bitmap.createScaledBitmap(a, (a.width * k).toInt().coerceAtLeast(1), (a.height * k).toInt().coerceAtLeast(1), true)
                } else a
                State(it.title, it.artist, it.playing, small)
            }
            val mgr = AppWidgetManager.getInstance(ctx)
            for ((cls, layout) in listOf(PlayerWidgetTall::class.java to R.layout.widget_tall, PlayerWidgetWide::class.java to R.layout.widget_wide)) {
                val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, cls)) }.getOrNull() ?: continue
                if (ids.isEmpty()) continue
                val v = views(ctx, layout, cls)
                for (id in ids) runCatching { mgr.updateAppWidget(id, v) }
            }
        }
    }
}

/** 縦長のウィジェット(上にジャケット) */
class PlayerWidgetTall : PlayerWidget() {
    override val layout = R.layout.widget_tall
}

/** 横長のウィジェット(左にジャケット) */
class PlayerWidgetWide : PlayerWidget() {
    override val layout = R.layout.widget_wide
}
