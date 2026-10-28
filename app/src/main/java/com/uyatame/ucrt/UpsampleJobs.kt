package com.uyatame.ucrt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.uyatame.ucrt.convert.ConvertCancelled
import com.uyatame.ucrt.convert.ConvertSpec
import com.uyatame.ucrt.convert.Converter
import com.uyatame.ucrt.convert.SeekOut
import com.uyatame.ucrt.convert.SourceTags
import java.io.FileOutputStream

/**
 * アップサンプルのタブの、変換する曲の並びと、順番に変換する処理(ConvertService の中で動く)。
 * 1 曲ごとに新しいファイルを書き出し先のフォルダに作り、失敗・中止した曲の書きかけは消す(ほかの曲は続ける)
 */
object UpsampleJobs {
    enum class State { WAITING, ANALYZING, CONVERTING, DONE, FAILED, CANCELLED }

    class Item(val uri: Uri, val name: String, val info: String) {
        var state by mutableStateOf(State.WAITING)
        var progress by mutableStateOf(0f)
        var message by mutableStateOf("")
        /** 変換にかかった時間(ミリ秒) */
        var tookMs by mutableStateOf(0L)
    }

    val items = mutableStateListOf<Item>()
    var running by mutableStateOf(false)
        private set
    /** 今変換している曲の番号(0 から) */
    var current by mutableStateOf(-1)
        private set
    @Volatile private var cancel = false
    @Volatile private var spec: ConvertSpec? = null
    @Volatile private var outTree: Uri? = null
    private val main = Handler(Looper.getMainLooper())
    var log: (String) -> Unit = {}
    /** 並びを変換し終えたとき(ライブラリを読み直す。書き出し先がライブラリのフォルダの中なら、新しい曲が出る) */
    var onFinished: () -> Unit = {}

    /** 表示名(拡張子つき) */
    fun nameOf(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')

    /** 曲を並びに足す(形式と音質を読んで表示する) */
    fun add(ctx: Context, uris: List<Uri>) {
        val app = ctx.applicationContext
        Thread({
            for (u in uris) {
                if (items.any { it.uri == u }) continue
                val name = nameOf(app, u)
                val info = runCatching {
                    val i = Converter(app).probe(u, name)
                    val ext = name.substringAfterLast('.', "").uppercase()
                    if (i.dsd) "$ext DSD${i.dsdRate / 44100} (${"%.1f".format(java.util.Locale.US, i.dsdRate / 1e6)} MHz) ${i.channels}ch"
                    else "$ext ${khz(i.rate)} / ${i.bits}bit ${i.channels}ch"
                }.getOrElse { S(R.string.up_cannot_read) }
                main.post { if (items.none { it.uri == u }) items.add(Item(u, name, info)) }
            }
        }, "upsample-add").start()
    }

    private fun khz(r: Int): String = if (r % 1000 == 0) "${r / 1000} kHz" else "%.1f kHz".format(java.util.Locale.US, r / 1000.0)

    fun remove(i: Item) { if (!running || i.state != State.CONVERTING && i.state != State.ANALYZING) items.remove(i) }

    fun clearFinished() { items.removeAll { it.state == State.DONE || it.state == State.FAILED || it.state == State.CANCELLED } }

    /** 変換を始める(終わっていない曲を、上から順に) */
    fun start(ctx: Context, s: ConvertSpec, tree: Uri) {
        if (running) return
        if (items.none { it.state != State.DONE }) return
        // DSRE は PCM にしか書き出さない
        spec = if (s.mode == 0 && s.format.dsd) s.copy(format = com.uyatame.ucrt.convert.OutFormat.FLAC) else s
        outTree = tree
        cancel = false
        running = true
        for (it in items) if (it.state != State.DONE) { it.state = State.WAITING; it.progress = 0f; it.message = "" }
        val i = Intent(ctx, ConvertService::class.java)
        val ok = runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }.isSuccess
        if (!ok) running = false
    }

    fun stop() { cancel = true }

    /** ConvertService のスレッドから: 並びを順に変換する */
    internal fun runAll(svc: ConvertService) {
        val s = spec ?: return
        val tree = outTree ?: return
        val conv = Converter(svc) { log(it) }
        // 状態の書き換えは画面のスレッドで後から行われるので、変換した曲はここで覚えておく(同じ曲を 2 度変換しないように)
        val tried = HashSet<Item>()
        try {
            while (!cancel) {
                val idx = items.indexOfFirst { it.state == State.WAITING && it !in tried }
                if (idx < 0) break
                val it = items[idx]
                tried.add(it)
                main.post { current = idx }
                convertOne(svc, conv, it, s, tree)
                svc.notifyProgress()
            }
        } finally {
            main.post {
                if (cancel) for (x in items) if (x.state == State.WAITING) x.state = State.CANCELLED
                running = false
                current = -1
                runCatching { onFinished() }
            }
        }
    }

    private fun convertOne(ctx: Context, conv: Converter, item: Item, s: ConvertSpec, tree: Uri) {
        val t0 = System.currentTimeMillis()
        set(item) { it.state = State.ANALYZING; it.progress = 0f }
        var outDoc: DocumentFile? = null
        try {
            val dir = DocumentFile.fromTreeUri(ctx, tree) ?: throw java.io.IOException(S(R.string.up_no_folder))
            val base = item.name.substringBeforeLast('.')
            val doc = dir.createFile(s.format.mime, "$base.${s.format.ext}") ?: throw java.io.IOException(S(R.string.up_cannot_create))
            outDoc = doc
            val tags = SourceTags.read(ctx, item.uri, item.name)
            val pfd = ctx.contentResolver.openFileDescriptor(doc.uri, "rw") ?: throw java.io.IOException(S(R.string.up_cannot_create))
            try {
                FileOutputStream(pfd.fileDescriptor).use { fos ->
                    val ch = fos.channel
                    ch.truncate(0)
                    var last = 0L
                    conv.convert(item.uri, item.name, s, SeekOut(ch), tags, { f, analyzing ->
                        val now = System.currentTimeMillis()
                        if (now - last >= 250 || f >= 1f) {
                            last = now
                            set(item) { it.progress = f; it.state = if (analyzing) State.ANALYZING else State.CONVERTING }
                        }
                    }) { cancel }
                    ch.force(true)
                }
            } finally {
                runCatching { pfd.close() }
            }
            val took = System.currentTimeMillis() - t0
            set(item) { it.state = State.DONE; it.progress = 1f; it.tookMs = took; it.message = doc.name.orEmpty() }
            log("upsample: done ${item.name} in ${took / 1000}s")
        } catch (e: ConvertCancelled) {
            runCatching { outDoc?.delete() }
            set(item) { it.state = State.CANCELLED; it.message = "" }
        } catch (e: Throwable) {
            runCatching { outDoc?.delete() }
            log("upsample: failed ${item.name}: ${e.javaClass.simpleName}: ${e.message}")
            set(item) { it.state = State.FAILED; it.message = e.message ?: e.javaClass.simpleName }
        }
    }

    private fun set(item: Item, f: (Item) -> Unit) = main.post { f(item) }

    /** 通知の文 */
    internal fun notifyText(): Pair<String, Int> {
        val done = items.count { it.state == State.DONE }
        val total = items.size
        val cur = items.getOrNull(current)
        val pct = ((cur?.progress ?: 0f) * 100).toInt()
        return S(R.string.up_notif_title, (done + 1).coerceAtMost(total), total) + (cur?.let { " · " + it.name } ?: "") to pct
    }
}

/** 変換中にアプリが止められないようにする前面サービス(通知に進み具合を出す) */
class ConvertService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            UpsampleJobs.stop()
            return START_NOT_STICKY
        }
        val n = build()
        val ok = runCatching {
            when {
                Build.VERSION.SDK_INT >= 35 -> startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                Build.VERSION.SDK_INT >= 29 -> startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(ID, n)
            }
        }.isSuccess
        if (!ok) {
            UpsampleJobs.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            wake = runCatching {
                androidx.core.content.ContextCompat.getSystemService(this, PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ucrt:convert")?.apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            }.getOrNull()
            main.post(tick)
            Thread({
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE) }
                try {
                    UpsampleJobs.runAll(this)
                } finally {
                    main.post { finishWork() }
                }
            }, "upsample").start()
        }
        return START_NOT_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            notifyProgress()
            main.postDelayed(this, 1000)
        }
    }

    internal fun notifyProgress() {
        runCatching { androidx.core.content.ContextCompat.getSystemService(this, NotificationManager::class.java)!!.notify(ID, build()) }
    }

    private fun finishWork() {
        main.removeCallbacks(tick)
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        @Suppress("DEPRECATION")
        runCatching { stopForeground(true) }
        stopSelf()
    }

    /** Android 15 から: 前面サービスで処理できる時間(1 日 6 時間)を使い切ったら止める */
    override fun onTimeout(startId: Int, fgsType: Int) {
        UpsampleJobs.stop()
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun build(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            androidx.core.content.ContextCompat.getSystemService(this, NotificationManager::class.java)!!.createNotificationChannel(
                NotificationChannel("convert", S(R.string.up_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ConvertService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (text, pct) = UpsampleJobs.notifyText()
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "convert") else Notification.Builder(this)
        @Suppress("DEPRECATION")
        val act = Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, S(R.string.stop), stop).build()
        return b.setContentTitle(S(R.string.up_converting))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_ucrt)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, false)
            .addAction(act)
            .build()
    }

    companion object {
        const val ID = 5
        const val ACTION_STOP = "com.uyatame.ucrt.CONVERT_STOP"
    }
}
