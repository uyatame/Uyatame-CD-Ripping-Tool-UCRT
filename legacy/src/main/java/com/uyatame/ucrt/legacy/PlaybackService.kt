package com.uyatame.ucrt.legacy

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.compat.Api16
import com.uyatame.ucrt.legacy.compat.Api26

/**
 * 再生中に、画面を閉じてもアプリが止められないようにする(フォアグラウンドのサービスと、再生中の通知)。
 * 通知から再生・一時停止・前後の曲を操作できる(Android 4.1 から)。イヤホンが抜けたら一時停止する
 */
class PlaybackService : Service() {
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            LegacyApp.app.player.pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        com.uyatame.ucrt.legacy.compat.Receivers.register(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = LegacyApp.app.player
        when (intent?.action) {
            ACTION_TOGGLE -> p.toggle()
            ACTION_NEXT -> p.next()
            ACTION_PREV -> p.prev()
        }
        val t = p.current
        if (t == null) {
            stopForegroundCompat(true)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification(t.title, t.artist, p.isPlaying)
        if (p.isPlaying) startForeground(ID, n) else {
            // 一時停止中は通知を残したまま、前面のサービスはやめる(消せるように)
            stopForegroundCompat(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, n)
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String, artist: String, playing: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) Api26.channel(nm, CHANNEL, S(R.string.legacy_notification_channel))
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, LegacyActivity::class.java).putExtra("openPlayer", true), flags or PendingIntent.FLAG_UPDATE_CURRENT)
        fun svc(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, PlaybackService::class.java).setAction(a), flags)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Api26.builder(this, CHANNEL) else Notification.Builder(this)
        b.setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_stat_ucrt)
            .setContentIntent(open)
            .setOngoing(playing)
        // ジャケット(通知の右の大きな絵)
        MediaControls.art?.let { b.setLargeIcon(it) }
        // 通知のボタン(Android 4.1 から)。アイコンは Android に入っているものを使う
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 16) {
            Api16.action(b, S(R.string.previous_track), svc(ACTION_PREV, 2), android.R.drawable.ic_media_previous)
            Api16.action(
                b, S(if (playing) R.string.pause else R.string.play), svc(ACTION_TOGGLE, 1),
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            )
            Api16.action(b, S(R.string.next_track), svc(ACTION_NEXT, 3), android.R.drawable.ic_media_next)
            if (Build.VERSION.SDK_INT < 21) Api16.priority(b)
        }
        // Android 5.0 からは、Android 標準のメディアの通知(ジャケットとアイコンのボタン。ロック画面にも出る)
        if (Build.VERSION.SDK_INT >= 21) com.uyatame.ucrt.legacy.compat.Api21Media.style(this, b)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 16) Api16.build(b) else b.notification
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat(remove: Boolean) {
        stopForeground(remove)
    }

    override fun onDestroy() {
        unregisterReceiver(noisy)
        super.onDestroy()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "playback"
        const val ACTION_TOGGLE = "com.uyatame.ucrt.legacy.TOGGLE"
        const val ACTION_NEXT = "com.uyatame.ucrt.legacy.NEXT"
        const val ACTION_PREV = "com.uyatame.ucrt.legacy.PREV"

        private var lastSig = ""

        /** 状態に合わせて通知を出し直す(曲が無くなったらサービスを止める) */
        fun update(ctx: Context, p: LegacyPlayer) {
            val cur = p.current
            // ジャケットを読み終えたら、出し直す
            val sig = "${cur?.id}/${p.isPlaying}/${MediaControls.art != null}"
            if (sig == lastSig) return
            lastSig = sig
            val i = Intent(ctx, PlaybackService::class.java)
            runCatching {
                if (p.isPlaying && Build.VERSION.SDK_INT >= 26) Api26.startForeground(ctx, i) else ctx.startService(i)
            }
        }
    }
}
