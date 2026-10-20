package com.uyatame.ucrt.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * ほかの音楽アプリ(Spotify・Qobuz・Amazon Music・YouTube Music など)の再生を、このアプリから表示・操作する。
 *
 * Android の「メディアセッション」を読むので、ストリーミングサービスごとの対応は要らない。
 * ほかのアプリのメディアセッションを読むには「通知へのアクセス」の許可が要る(通知の中身は読まない)。
 * 音はそのアプリから、いつもどおり Android を通して出る(このアプリの USB DAC 直接出力は通らない)。
 */
object ExternalMedia {
    /** 再生中(または最後に再生していた)ほかのアプリの曲 */
    class NowPlaying(
        val packageName: String,
        val appName: String,
        val title: String,
        val artist: String,
        val album: String,
        val art: ImageBitmap?,
        val durationMs: Long,
        val playing: Boolean,
        /** 再生位置の基準(この時刻 at にこの位置 posMs。再生中は speed の速さで進む) */
        val posMs: Long,
        val at: Long,
        val speed: Float,
        val canSeek: Boolean,
        val canPrev: Boolean,
        val canNext: Boolean,
    ) {
        fun positionNow(): Long {
            if (!playing) return posMs
            val p = posMs + ((SystemClock.elapsedRealtime() - at) * speed).toLong()
            return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
        }
    }

    /** 表示する曲(ほかのアプリが何も再生していなければ null) */
    var now by mutableStateOf<NowPlaying?>(null)
        private set

    /** 「通知へのアクセス」が許可されているか */
    var permitted by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var enabled = false
    private var artKey: Any? = null
    private var artCache: ImageBitmap? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            update()
            // 一時停止したら、ほかに再生中のアプリがあればそちらを表示する
            if (state?.state != PlaybackState.STATE_PLAYING) refresh()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onSessionDestroyed() = refresh()
    }

    private fun component(ctx: Context) = ComponentName(ctx, MediaListenerService::class.java)

    /** 「通知へのアクセス」が許可されているか(許可されたサービスの一覧に、このアプリのものがあるか) */
    private fun hasPermission(ctx: Context): Boolean {
        val list = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        return list.split(':').any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
    }

    /**
     * 使うかどうかを設定に合わせる。画面に戻ってきたとき(許可を変えたかもしれない)にも呼ぶ。
     * メインスレッドから呼ぶ
     */
    fun setEnabled(ctx: Context, on: Boolean) {
        val c = ctx.applicationContext
        app = c
        permitted = hasPermission(c)
        val want = on && permitted
        if (want == enabled) {
            if (want) refresh()
            return
        }
        enabled = want
        if (want) {
            val m = c.getSystemService(MediaSessionManager::class.java) ?: return
            manager = m
            runCatching { m.addOnActiveSessionsChangedListener(sessionsListener, component(c), main) }
            refresh()
        } else {
            manager?.let { m -> runCatching { m.removeOnActiveSessionsChangedListener(sessionsListener) } }
            manager = null
            attach(null)
        }
    }

    /** 今あるメディアセッションを読み直す */
    fun refresh() {
        val c = app ?: return
        val m = manager ?: return
        if (!enabled) return
        val list = runCatching { m.getActiveSessions(component(c)) }.getOrElse {
            // 許可が取り消された
            permitted = false
            emptyList()
        }
        pick(list)
    }

    /** 表示するアプリを選ぶ: 再生中のものを優先し、なければいちばん最近のもの(自分のアプリは除く) */
    private fun pick(list: List<MediaController>) {
        val self = app?.packageName
        val others = list.filter { it.packageName != self }
        val chosen = others.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: others.firstOrNull { it.packageName == controller?.packageName }
            ?: others.firstOrNull { it.metadata != null }
        attach(chosen)
    }

    private fun attach(c: MediaController?) {
        val old = controller
        if (old != null && c != null && old.sessionToken == c.sessionToken) {
            update()
            return
        }
        old?.let { runCatching { it.unregisterCallback(callback) } }
        controller = c
        c?.let { runCatching { it.registerCallback(callback, main) } }
        update()
    }

    private fun update() {
        val c = controller
        val md = c?.metadata
        if (c == null || md == null) {
            now = null
            return
        }
        val st = c.playbackState
        val actions = st?.actions ?: 0L
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        now = NowPlaying(
            packageName = c.packageName,
            appName = appName(c.packageName),
            title = title,
            artist = artist,
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            art = art(md),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            playing = st?.state == PlaybackState.STATE_PLAYING,
            posMs = st?.position?.coerceAtLeast(0) ?: 0L,
            at = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            canPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
        )
    }

    /** ジャケット(同じ画像なら変換し直さない) */
    private fun art(md: MediaMetadata): ImageBitmap? {
        val b: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (b == null) {
            artKey = null; artCache = null
            return null
        }
        if (b !== artKey) {
            artKey = b
            artCache = runCatching { b.asImageBitmap() }.getOrNull()
        }
        return artCache
    }

    private fun appName(pkg: String): String {
        val pm = app?.packageManager ?: return pkg
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    // ---- 操作 ----

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun previous() { controller?.transportControls?.skipToPrevious() }

    fun next() { controller?.transportControls?.skipToNext() }

    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms.coerceAtLeast(0)) }

    /** 再生しているアプリを開く */
    fun openApp(ctx: Context) {
        val pkg = now?.packageName ?: return
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** 「通知へのアクセス」の設定画面を開く */
    fun openPermissionSettings(ctx: Context) {
        val detail = if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(ctx).flattenToString())
        } else null
        val list = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        for (i in listOfNotNull(detail, list)) {
            if (runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}

/**
 * 「通知へのアクセス」を許可してもらうためだけのサービス(ほかのアプリのメディアセッションを読むのに要る)。
 * 通知の中身は読まない
 */
class MediaListenerService : android.service.notification.NotificationListenerService()
