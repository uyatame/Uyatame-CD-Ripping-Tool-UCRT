package com.uyatame.ucrt.legacy.compat

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.uyatame.ucrt.legacy.LegacyApp

/** Android 5.0 から: ロック画面・ボタンの操作(MediaSession) */
object Api21Media {
    private var session: MediaSession? = null
    private var lastMeta = ""

    private fun session(ctx: Context): MediaSession {
        session?.let { return it }
        val s = MediaSession(ctx.applicationContext, "UCRT Legacy")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = LegacyApp.app.player.resume()
            override fun onPause() = LegacyApp.app.player.pause()
            override fun onSkipToNext() = LegacyApp.app.player.next()
            override fun onSkipToPrevious() = LegacyApp.app.player.prev()
            override fun onStop() = LegacyApp.app.player.pause()
            override fun onSeekTo(pos: Long) = LegacyApp.app.player.seek(pos)
        })
        @Suppress("DEPRECATION")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session = s
        return s
    }

    fun update(ctx: Context, title: String, artist: String, album: String, durMs: Long, posMs: Long, playing: Boolean, art: Bitmap?) {
        val s = session(ctx)
        val key = "$title/$artist/$album/$durMs/${art != null}"
        if (key != lastMeta) {
            lastMeta = key
            val m = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs)
            if (art != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            s.setMetadata(m.build())
        }
        val st = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO,
            )
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, posMs, if (playing) 1f else 0f)
            .build()
        s.setPlaybackState(st)
        if (!s.isActive) s.isActive = true
    }

    fun stop() {
        val s = session ?: return
        s.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build())
        s.isActive = false
    }
}
