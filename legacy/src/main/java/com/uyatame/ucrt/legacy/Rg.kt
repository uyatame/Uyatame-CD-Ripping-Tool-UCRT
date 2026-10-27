package com.uyatame.ucrt.legacy

import android.net.Uri
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.library.Cue
import com.uyatame.ucrt.library.LibTrack
import com.uyatame.ucrt.player.Decoders
import com.uyatame.ucrt.player.LoudnessMeter
import com.uyatame.ucrt.player.RgInfo
import com.uyatame.ucrt.player.RgStore
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * ライブラリの曲の音の大きさを解析して、ReplayGain の値(曲ごと・アルバムごと)を求める(本家の analyzeReplayGain)。
 * 値はアプリの中に保存し、曲のファイルは書き換えない
 */
object Rg {
    private val app get() = LegacyApp.app
    @Volatile var running = false; private set
    @Volatile var progress = 0f; private set
    @Volatile var status = ""; private set
    @Volatile private var stop = false

    private class Result(val uri: String, val gain: Float?, val peak: Float, val blocks: List<Double>)

    fun start(act: LegacyActivity, all: Boolean) {
        if (running) return
        val targets = app.lib.albums.filter { a -> all || a.tracks.any { !RgStore.has(it.uri) } }
        val total = targets.sumOf { it.tracks.size }
        if (total == 0) { act.snack(S(R.string.rg_all_analyzed)); return }
        running = true
        progress = 0f
        stop = false
        val playing = app.player.isPlaying
        val n = if (playing) 1 else Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val prio = if (playing) android.os.Process.THREAD_PRIORITY_BACKGROUND else 5
        Bus.emit(Bus.SETTINGS)
        Thread({
            val done = AtomicInteger()
            val analyzed = AtomicInteger()
            val failed = AtomicInteger()
            val pool = Executors.newFixedThreadPool(n) { r -> Thread({ runCatching { android.os.Process.setThreadPriority(prio) }; r.run() }, "rg-analysis").apply { isDaemon = true } }
            try {
                val futures = ArrayList<Future<*>>()
                for (a in targets) {
                    val left = AtomicInteger(a.tracks.size)
                    val results = Collections.synchronizedList(ArrayList<Result>())
                    for (t in a.tracks) futures.add(pool.submit {
                        if (stop) return@submit
                        status = t.title + S(R.string.separator) + a.title
                        val r = analyze(t)
                        if (r == null) failed.incrementAndGet() else results.add(r)
                        progress = done.incrementAndGet().toFloat() / total
                        Bus.emit(Bus.SETTINGS)
                        if (left.decrementAndGet() == 0 && !stop) analyzed.addAndGet(saveAlbum(results))
                    })
                }
                for (f in futures) { try { f.get() } catch (e: Exception) { if (e is InterruptedException) break } }
            } finally {
                stop = true
                pool.shutdownNow()
                running = false
                status = ""
                val msg = S(R.string.rg_analysis_finished, analyzed.get(), failed.get())
                act.runOnUiThread { act.snack(msg) }
                Bus.emit(Bus.SETTINGS)
            }
        }, "rg-analysis-main").start()
    }

    fun cancel() { stop = true }

    fun clear() {
        Thread({ RgStore.clear(); Bus.emit(Bus.SETTINGS) }, "rg-clear").start()
    }

    private fun analyze(t: LibTrack): Result? {
        val file = Cue.fileUri(t.uri)
        if (com.uyatame.ucrt.player.dsd.DsdFile.isDsd(file)) return null
        val o = runCatching { Decoders.open(app, Uri.parse(file), Uri.decode(file).substringAfterLast('/')) }.getOrNull() ?: return null
        try {
            val d = o.decoder
            val rate = d.sampleRate
            val ch = d.channels
            val meter = LoudnessMeter(rate, ch)
            val scale = 1f / (1L shl (d.bitsPerSample - 1)).toFloat()
            val start = t.startMs * rate / 1000
            val end = if (t.endMs > 0) t.endMs * rate / 1000 else Long.MAX_VALUE
            if (start > 0) d.seekSample(start)
            var buf = FloatArray(0)
            while (!stop && d.decodeNext()) {
                if (d.frameSample >= end) break
                var first = 0
                if (d.frameSample < start) first = minOf(d.blockSize.toLong(), start - d.frameSample).toInt()
                var last = d.blockSize
                if (d.frameSample + last > end) last = (end - d.frameSample).toInt()
                val n = last - first
                if (n <= 0) continue
                if (buf.size < n * ch) buf = FloatArray(n * ch)
                val s = d.samples
                for (i in 0 until n) for (c in 0 until ch) buf[i * ch + c] = s[c][first + i] * scale
                meter.feed(buf, n)
            }
            if (stop) return null
            val l = LoudnessMeter.integrated(meter.blocks)
            return Result(t.uri, l?.let { LoudnessMeter.gainDb(it) }, meter.peak, meter.blocks)
        } catch (e: Exception) {
            return null
        } finally {
            o.close()
        }
    }

    private fun saveAlbum(tracks: List<Result>): Int {
        val list = synchronized(tracks) { tracks.toList() }
        val blocks = ArrayList<Double>()
        var albumPeak = 0f
        for (r in list) { blocks.addAll(r.blocks); if (r.peak > albumPeak) albumPeak = r.peak }
        val albumGain = LoudnessMeter.integrated(blocks)?.let { LoudnessMeter.gainDb(it) }
        val values = HashMap<String, RgInfo>()
        for (r in list) if (r.gain != null) values[r.uri] = RgInfo(r.gain, r.peak, albumGain, albumPeak)
        if (values.isNotEmpty()) RgStore.putAll(values)
        return values.size
    }
}
