package com.uyatame.ucrt.legacy

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.RemoteControlClient
import android.os.Build
import android.view.KeyEvent

/**
 * ロック画面・イヤホンや Bluetooth のボタンからの操作と、ホーム画面のウィジェットの更新(本家の MediaBridge)。
 * Android 4.x は RemoteControlClient、Android 5.0 からは MediaSession(compat/Api21Media)
 */
object MediaControls {
    private val app get() = LegacyApp.app
    private var rcc: RemoteControlClient? = null
    private var registered = false
    private var lastKey = ""
    private var art: Bitmap? = null
    private var artKey = ""

    private val receiver: ComponentName get() = ComponentName(app, MediaButtonReceiver::class.java)

    /** 再生の状態が変わったら呼ぶ(メインのスレッド) */
    fun update(ctx: Context) {
        val p = app.player
        val cur = p.current
        if (cur == null) {
            LegacyWidget.update(ctx, null)
            if (Build.VERSION.SDK_INT >= 21) com.uyatame.ucrt.legacy.compat.Api21Media.stop() else rcc?.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED)
            return
        }
        // ジャケット(読み終わったら、もう一度知らせる)
        val key = cur.uri
        if (key != artKey) {
            artKey = key
            art = null
            if (cur.uri.startsWith("cd:")) art = Disc.cover
            else Art.load(ctx, Actions.artRef(ctx, cur.track), 512) { b -> if (artKey == key) { art = b; lastKey = ""; update(ctx) } }
        }
        LegacyWidget.update(ctx, LegacyWidget.State(cur.title, cur.artist, p.isPlaying, art))
        val sig = "${cur.id}/${p.isPlaying}/${art != null}"
        if (Build.VERSION.SDK_INT >= 21) {
            com.uyatame.ucrt.legacy.compat.Api21Media.update(ctx, cur.title, cur.artist, cur.track.album, p.durationMs, p.positionMs, p.isPlaying, art)
            return
        }
        ensureRcc(ctx)
        val r = rcc ?: return
        r.setPlaybackState(if (p.isPlaying) RemoteControlClient.PLAYSTATE_PLAYING else RemoteControlClient.PLAYSTATE_PAUSED)
        if (sig == lastKey) return
        lastKey = sig
        val ed = r.editMetadata(true)
        ed.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, cur.title)
        ed.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, cur.artist)
        ed.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, cur.track.album)
        ed.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, p.durationMs)
        art?.let { ed.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, it.copy(it.config ?: Bitmap.Config.ARGB_8888, false)) }
        ed.apply()
    }

    @Suppress("DEPRECATION")
    private fun ensureRcc(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!registered) {
            am.registerMediaButtonEventReceiver(receiver)
            registered = true
        }
        if (rcc == null) {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver)
            val pi = PendingIntent.getBroadcast(ctx, 0, i, 0)
            val r = RemoteControlClient(pi)
            r.setTransportControlFlags(
                RemoteControlClient.FLAG_KEY_MEDIA_PLAY or RemoteControlClient.FLAG_KEY_MEDIA_PAUSE or
                    RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE or RemoteControlClient.FLAG_KEY_MEDIA_NEXT or
                    RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS or RemoteControlClient.FLAG_KEY_MEDIA_STOP,
            )
            am.registerRemoteControlClient(r)
            rcc = r
        }
    }

    /** ボタンのキーを再生の操作にする */
    fun key(code: Int): Boolean {
        val p = app.player
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> p.toggle()
            KeyEvent.KEYCODE_MEDIA_PLAY -> p.resume()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> p.pause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> p.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> p.prev()
            KeyEvent.KEYCODE_MEDIA_STOP -> p.pause()
            else -> return false
        }
        return true
    }
}

/** イヤホン・Bluetooth のボタン(Android 4.x) */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val e = i.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) MediaControls.key(e.keyCode)
    }
}
