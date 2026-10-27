package com.uyatame.ucrt.legacy

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews
import com.uyatame.ucrt.R

/**
 * ホーム画面のウィジェット(本家の PlayerWidget と同じ。縦長と横長)。
 * ジャケット・曲名・アーティストと、前の曲・再生 / 一時停止・次の曲のボタン
 */
abstract class LegacyWidget : AppWidgetProvider() {
    abstract val layout: Int

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = views(ctx, layout, javaClass)
        for (id in ids) mgr.updateAppWidget(id, v)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE, ACTION_NEXT, ACTION_PREV -> {
                val p = LegacyApp.app.player
                if (p.current == null) openApp(ctx) else when (intent.action) {
                    ACTION_TOGGLE -> p.toggle()
                    ACTION_NEXT -> p.next()
                    ACTION_PREV -> p.prev()
                }
                return
            }
        }
        super.onReceive(ctx, intent)
    }

    class State(val title: String, val artist: String, val playing: Boolean, val art: Bitmap?)

    companion object {
        private const val ACTION_TOGGLE = "com.uyatame.ucrt.legacy.widget.TOGGLE"
        private const val ACTION_NEXT = "com.uyatame.ucrt.legacy.widget.NEXT"
        private const val ACTION_PREV = "com.uyatame.ucrt.legacy.widget.PREV"

        @Volatile private var state: State? = null
        private val flags get() = (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT

        private fun openApp(ctx: Context) {
            runCatching { ctx.startActivity(Intent(ctx, LegacyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
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
            fun action(a: String, code: Int): PendingIntent = PendingIntent.getBroadcast(ctx, code, Intent(ctx, cls).setAction(a), flags)
            val base = if (cls == LegacyWidgetWide::class.java) 20 else 10
            v.setOnClickPendingIntent(R.id.widget_prev, action(ACTION_PREV, base + 1))
            v.setOnClickPendingIntent(R.id.widget_play, action(ACTION_TOGGLE, base + 2))
            v.setOnClickPendingIntent(R.id.widget_next, action(ACTION_NEXT, base + 3))
            val open = PendingIntent.getActivity(ctx, base, Intent(ctx, LegacyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
            v.setOnClickPendingIntent(R.id.widget_art, open)
            v.setOnClickPendingIntent(R.id.widget_title, open)
            v.setOnClickPendingIntent(R.id.widget_artist, open)
            return v
        }

        private var last: Triple<String, Boolean, Bitmap?>? = null

        /** 曲・再生の状態が変わったら呼ぶ(null なら「再生していません」) */
        fun update(ctx: Context, s: State?) {
            val key = s?.let { Triple(it.title + "\u0000" + it.artist, it.playing, it.art) }
            if (key == last) return
            last = key
            state = s?.let {
                val a = it.art
                // ウィジェットに送る画像は小さくする(古い端末は送れる大きさが小さい)
                val small = if (a != null && maxOf(a.width, a.height) > 256) {
                    val k = 256f / maxOf(a.width, a.height)
                    Bitmap.createScaledBitmap(a, (a.width * k).toInt().coerceAtLeast(1), (a.height * k).toInt().coerceAtLeast(1), true)
                } else a
                State(it.title, it.artist, it.playing, small)
            }
            val mgr = AppWidgetManager.getInstance(ctx)
            for ((cls, layout) in listOf(LegacyWidgetTall::class.java to R.layout.widget_tall, LegacyWidgetWide::class.java to R.layout.widget_wide)) {
                val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, cls)) }.getOrNull() ?: continue
                if (ids.isEmpty()) continue
                val v = views(ctx, layout, cls)
                for (id in ids) runCatching { mgr.updateAppWidget(id, v) }
            }
        }
    }
}

class LegacyWidgetTall : LegacyWidget() {
    override val layout = R.layout.widget_tall
}

class LegacyWidgetWide : LegacyWidget() {
    override val layout = R.layout.widget_wide
}
