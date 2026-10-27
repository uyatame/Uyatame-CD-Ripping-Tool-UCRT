package com.uyatame.ucrt.legacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.uyatame.ucrt.library.ArtSource
import java.io.File
import java.util.concurrent.Executors

/** ジャケットの読み込みに使う情報(本家の artRef と同じ) */
class ArtRef(val key: String, val trackUri: String?, val coverUri: String?, val fallbackUri: String? = null)

/**
 * ジャケット画像の読み込み(本家の ArtLoader と同じ考え方)。
 * 表示する大きさに合わせて読み、一覧用の小さな画像は端末に保存して、次からは曲のファイルを開かずに読む
 */
object Art {
    private val BUCKETS = intArrayOf(128, 256, 512, 2048)
    private const val DISK_MAX = 512
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(4L shl 20, 64L shl 20).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.rowBytes * value.height
    }
    private val misses = HashSet<String>()
    /** 読み込みは 2 本のスレッドで(古い端末で再生を邪魔しないように) */
    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            r.run()
        }.apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var dir: File? = null
    @Volatile private var bigMax = 1024
    /** 画像が差し替わったら増える(表示中の画像を読み直す) */
    @Volatile var generation = 0
        private set

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "art").also { it.mkdirs() }
        val dm = ctx.resources.displayMetrics
        bigMax = maxOf(dm.widthPixels, dm.heightPixels).coerceIn(DISK_MAX * 2, 2048)
    }

    fun bucketOf(px: Int): Int = BUCKETS.firstOrNull { it >= px } ?: BUCKETS.last()

    fun peek(key: String?, px: Int = BUCKETS.last()): Bitmap? {
        if (key == null) return null
        val b = bucketOf(px)
        for (x in BUCKETS) if (x >= b) cache.get("$key@$x")?.let { return it }
        for (x in BUCKETS.reversed()) if (x < b) cache.get("$key@$x")?.let { return it }
        return null
    }

    /** 読み込んで done を呼ぶ(メインのスレッドで。無ければ null) */
    fun load(ctx: Context, r: ArtRef, px: Int, done: (Bitmap?) -> Unit) {
        val b = bucketOf(px)
        cache.get("${r.key}@$b")?.let { done(it); return }
        synchronized(misses) { if (r.key in misses) { done(null); return } }
        val app = ctx.applicationContext
        pool.execute {
            val bmp = runCatching { loadNow(app, r, b) }.getOrNull()
            main.post { done(bmp) }
        }
    }

    private fun diskFile(key: String, b: Int): File? {
        val d = dir ?: return null
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(d, "${h}_$b.jpg")
    }

    private fun loadNow(ctx: Context, r: ArtRef, b: Int): Bitmap? {
        cache.get("${r.key}@$b")?.let { return it }
        if (b <= DISK_MAX) {
            val f = diskFile(r.key, b)
            if (f != null && f.exists()) {
                val bmp = runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) }.getOrNull()
                if (bmp != null) { cache.put("${r.key}@$b", bmp); return bmp }
                f.delete()
            }
        }
        var failed = false
        val bytes = try {
            ArtSource.bytes(ctx, r.trackUri, null)
                ?: r.fallbackUri?.takeIf { it != r.trackUri }?.let { ArtSource.bytes(ctx, it, null) }
                ?: ArtSource.bytes(ctx, null, r.coverUri)
        } catch (e: Exception) {
            failed = true; null
        } catch (e: OutOfMemoryError) {
            failed = true; null
        }
        val target = if (b > DISK_MAX) minOf(b, bigMax) else b
        val bmp = bytes?.let { runCatching { ArtSource.decode(it, target, b <= DISK_MAX) }.getOrNull() }
        if (bmp == null) {
            if (!failed && bytes == null) synchronized(misses) { misses.add(r.key) }
            return null
        }
        if (b <= DISK_MAX) {
            runCatching {
                val f = diskFile(r.key, b) ?: return@runCatching
                val tmp = File(f.path + "." + Thread.currentThread().id + ".tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                if (!tmp.renameTo(f)) tmp.delete()
            }
        }
        cache.put("${r.key}@$b", bmp)
        return bmp
    }

    /** 元の画像のバイト列(縮めていないもの) */
    fun originalBytes(ctx: Context, trackUri: String?, coverUri: String?): ByteArray? =
        runCatching { ArtSource.bytes(ctx, trackUri, coverUri) }.getOrNull()

    /** 画像を忘れる(曲情報の編集でジャケットを変えたとき) */
    fun forget(key: String) {
        for (x in BUCKETS) cache.remove("$key@$x")
        for (x in BUCKETS) if (x <= DISK_MAX) diskFile(key, x)?.delete()
        synchronized(misses) { misses.remove(key) }
        generation++
        Bus.emit(Bus.ART)
    }

    fun clearMisses() {
        synchronized(misses) { misses.clear() }
        generation++
        Bus.emit(Bus.ART)
    }
}
