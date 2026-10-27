package com.uyatame.ucrt.rip

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.player.BuiltinCodecs
import com.uyatame.ucrt.player.RationalResampler
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.ceil

/**
 * 書き込む内容(BurnImage)を作る。
 * - CD のコピー: 元の CD の音楽トラックを、元の並びのまま 1 つのファイルに読み取る
 * - 曲から作る: ライブラリの曲を 44.1 kHz / 16bit / 2ch にして並べる(必要なら周波数を変換し、16bit にするときはディザーを掛ける)
 */
/** 書き込む曲(CUE シートで分けた曲は、元のファイルの中の範囲) */
class BurnSource(val uri: Uri, val startMs: Long = 0, val endMs: Long = 0)

object BurnPrep {
    private const val SECTOR = 2352
    /** 1 セクターのフレーム数 */
    private const val FRAMES = 588
    /** 1 曲の最短(CD の決まりで 4 秒) */
    private const val MIN_TRACK = 300

    /** 音楽 CD 1 枚の最長(80 分) */
    const val MAX_SECTORS = 80 * 60 * 75

    // ---- CD のコピー ----

    /**
     * CD の音楽トラック(先頭から続いている分)を読み取る。tracks は TOC の曲(データトラックは除いて渡す)
     */
    fun fromCd(
        drive: com.uyatame.ucrt.usb.UsbScsiDrive,
        tracks: List<com.uyatame.ucrt.usb.TocTrack>,
        leadOut: Int,
        rip: RipOptions,
        out: File,
        onProgress: (Float) -> Unit,
        check: () -> Unit,
    ): BurnImage {
        if (tracks.isEmpty()) throw IOException(S(R.string.no_audio_tracks))
        val first = tracks.first().startLba
        val end = tracks.last().endLba
        val starts = tracks.map { it.startLba - first }
        out.outputStream().buffered(1 shl 16).use { os ->
            val sink = object : AudioSink {
                override fun write(buf: ByteArray, off: Int, len: Int) = os.write(buf, off, len)
                override fun finish() {}
            }
            CdRipper(drive, rip).rip(first, end, leadOut, sink, onProgress, check)
        }
        return BurnImage(out, starts, List(starts.size) { 0 }, end - first)
    }

    // ---- 曲から作る ----

    /** 曲を 44.1 kHz / 16bit / 2ch にして並べる。gapSectors は 2 曲目からの曲間(無音)の長さ */
    /** どの形式でもアプリ内蔵のデコーダーを先に使う(legacy。OS のデコーダーが古い・無い端末のため) */
    @Volatile var builtinFirst = false

    fun fromFiles(
        ctx: Context,
        uris: List<BurnSource>,
        gapSectors: Int,
        out: File,
        log: (String) -> Unit,
        onProgress: (Float) -> Unit,
        check: () -> Unit,
    ): BurnImage {
        val starts = ArrayList<Int>()
        val gaps = ArrayList<Int>()
        var pos = 0
        out.outputStream().buffered(1 shl 16).use { os ->
            uris.forEachIndexed { i, uri ->
                check()
                val gap = if (i == 0) 0 else gapSectors
                if (gap > 0) { writeSilence(os, gap); pos += gap }
                starts.add(pos)
                gaps.add(gap)
                val frames = convert(ctx, uri.uri, uri.startMs, uri.endMs, os, log, { p -> onProgress((i + p) / uris.size) }, check)
                // セクターの区切りまで、また最短の長さまで無音で埋める
                var sectors = ((frames + FRAMES - 1) / FRAMES).toInt()
                val pad = (sectors.toLong() * FRAMES - frames).toInt()
                if (pad > 0) os.write(ByteArray(pad * 4))
                if (sectors < MIN_TRACK) { writeSilence(os, MIN_TRACK - sectors); sectors = MIN_TRACK }
                pos += sectors
                if (pos > MAX_SECTORS) throw IOException(S(R.string.too_long_for_one_cd))
            }
        }
        return BurnImage(out, starts, gaps, pos)
    }

    private fun writeSilence(os: OutputStream, sectors: Int) {
        val z = ByteArray(SECTOR)
        repeat(sectors) { os.write(z) }
    }

    /** 曲を読み、44.1 kHz / 16bit / 2ch の PCM にして書く。書いたフレーム数を返す */
    private fun convert(
        ctx: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        os: OutputStream,
        log: (String) -> Unit,
        onProgress: (Float) -> Unit,
        check: () -> Unit,
    ): Long {
        val name = Uri.decode(uri.toString())
        if (com.uyatame.ucrt.player.dsd.DsdFile.isDsd(name)) {
            throw IOException(S(R.string.dsd_tracks_cannot_be_written))
        }
        val w = Writer(os, startMs, endMs)
        if (builtinFirst) {
            // アプリ内蔵のデコーダー(どの形式でも。古い Android の OS のデコーダーに頼らないように)
            val o = runCatching { com.uyatame.ucrt.player.Decoders.open(ctx, uri, name.substringAfterLast('/')) }.getOrNull()
            if (o != null) {
                o.use { opened ->
                    val d = opened.decoder
                    w.begin(d.sampleRate, d.channels, d.bitsPerSample)
                    log("burn: built-in ${d.sampleRate} Hz ${d.channels}ch ${d.bitsPerSample}bit")
                    val scale = 1f / (1L shl (d.bitsPerSample - 1)).toFloat()
                    var buf = FloatArray(0)
                    if (startMs > 0) d.seekSample(startMs * d.sampleRate / 1000)
                    while (d.decodeNext()) {
                        check()
                        val n = d.blockSize
                        val ch = d.channels
                        if (buf.size < n * ch) buf = FloatArray(n * ch)
                        var k = 0
                        for (f in 0 until n) for (c in 0 until ch) buf[k++] = d.samples[c][f] * scale
                        if (!w.write(buf, n, d.frameSample)) break
                        if (d.totalSamples > 0) onProgress(((d.frameSample + n).toFloat() / d.totalSamples).coerceAtMost(1f))
                    }
                }
                return w.finish()
            }
        }
        val kind = BuiltinCodecs.kindOf(name)
        if (kind != null) {
            val inp = BuiltinCodecs.openInput(ctx, uri)
            if (inp != null) {
                // 書き始めてから失敗したときは OS のデコーダーでやり直さない(同じ音が二重に書かれてしまう)
                val done = inp.use { i ->
                    runCatching {
                        val d = BuiltinCodecs.create(kind, i)
                        d.open()
                        w.begin(d.sampleRate, d.channels, d.bitsPerSample)
                        log("burn: $kind ${d.sampleRate} Hz ${d.channels}ch ${d.bitsPerSample}bit")
                        val scale = 1f / (1L shl (d.bitsPerSample - 1)).toFloat()
                        var buf = FloatArray(0)
                        if (startMs > 0) d.seekSample(startMs * d.sampleRate / 1000)
                        while (d.decodeNext()) {
                            check()
                            val n = d.blockSize
                            val ch = d.channels
                            if (buf.size < n * ch) buf = FloatArray(n * ch)
                            var k = 0
                            for (f in 0 until n) for (c in 0 until ch) buf[k++] = d.samples[c][f] * scale
                            if (!w.write(buf, n, d.frameSample)) break
                            if (d.totalSamples > 0) onProgress(((d.frameSample + n).toFloat() / d.totalSamples).coerceAtMost(1f))
                        }
                        true
                    }.getOrElse { e ->
                        if (w.finish() > 0 || e is java.util.concurrent.CancellationException) throw e
                        log("burn: built-in decoder failed (${e.message}), using OS decoder")
                        false
                    }
                }
                if (done) return w.finish()
            }
        }
        // OS のデコーダー(MediaExtractor)は Android 4.1 から(legacy の Android 4.0 では使えない)
        if (android.os.Build.VERSION.SDK_INT < 16) throw IOException(S(R.string.legacy_needs_os_decoder))
        return decodeWithCodec(ctx, uri, startMs, w, onProgress, check)
    }

    /** OS のデコーダーで読む(MP3・AAC・WAV など) */
    private fun decodeWithCodec(ctx: Context, uri: Uri, startMs: Long, w: Writer, onProgress: (Float) -> Unit, check: () -> Unit): Long {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val ti = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException(S(R.string.no_audio_track_found))
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var started = false
            var buf = FloatArray(0)
            var at = -1L
            if (startMs > 0) ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            while (true) {
                check()
                if (!inEos) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(CodecCompat.input(c, i), 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o < 0) continue
                val of = c.outputFormat
                val rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else android.media.AudioFormat.ENCODING_PCM_16BIT
                if (!started) {
                    w.begin(rate, ch, if (enc == android.media.AudioFormat.ENCODING_PCM_16BIT) 16 else 24)
                    started = true
                }
                val bb = CodecCompat.output(c, o, info).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bb.position(info.offset)
                bb.limit(info.offset + info.size)
                val samples = if (enc == android.media.AudioFormat.ENCODING_PCM_FLOAT) info.size / 4 else info.size / 2
                if (buf.size < samples) buf = FloatArray(samples)
                if (enc == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                    for (k in 0 until samples) buf[k] = bb.float
                } else {
                    for (k in 0 until samples) buf[k] = bb.short / 32768f
                }
                c.releaseOutputBuffer(o, false)
                if (at < 0) at = info.presentationTimeUs * rate / 1_000_000
                if (!w.write(buf, samples / ch, at)) break
                at += samples / ch
                if (durUs > 0) onProgress((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
            return w.finish()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    /**
     * float の PCM を 44.1 kHz / 16bit / 2ch にして書く。
     * 元が 44.1 kHz / 16bit なら、値をそのまま 16bit に戻す(ディザーも掛けないので、元の CD の音とビット単位で同じ)
     */
    private class Writer(private val os: OutputStream, private val startMs: Long, private val endMs: Long) {
        private var startF = 0L
        private var endF = Long.MAX_VALUE
        private var ch = 2
        private var rs: RationalResampler? = null
        private var dither = false
        private var stereo = FloatArray(0)
        private var rsOut = FloatArray(0)
        private var bytes = ByteArray(0)
        private var frames = 0L
        private val rnd = java.util.Random(1)

        fun begin(rate: Int, channels: Int, bits: Int) {
            ch = channels.coerceAtLeast(1)
            startF = startMs * rate / 1000
            endF = if (endMs > 0) endMs * rate / 1000 else Long.MAX_VALUE
            rs = if (rate != 44100) {
                // 通す帯域は 20 kHz まで。遷移の幅(約 2 kHz)に足りるタップ数にする
                val cut = minOf(rate, 44100) / 2.0 - 1000.0
                val taps = ceil(92.0 * rate / (14.36 * 2000.0)).toInt().coerceIn(32, 640)
                RationalResampler(rate, 44100, 2, cut, taps)
            } else null
            dither = rs != null || bits > 16
        }

        /**
         * src: チャンネル交互の n フレーム。at はその最初のフレームの番号(元の周波数で)。
         * 範囲(CUE シートの曲)の外は捨てる。範囲の終わりまで来たら false
         */
        fun write(src0: FloatArray, n0: Int, at: Long): Boolean {
            val from = (startF - at).coerceIn(0L, n0.toLong()).toInt()
            val to = (endF - at).coerceIn(0L, n0.toLong()).toInt()
            if (to > from) put(src0, from, to - from)
            return at + n0 < endF
        }

        private var slice = FloatArray(0)

        private fun put(src0: FloatArray, off: Int, n: Int) {
            val src = if (off == 0) src0 else {
                if (slice.size < n * ch) slice = FloatArray(n * ch)
                System.arraycopy(src0, off * ch, slice, 0, n * ch)
                slice
            }
            if (stereo.size < n * 2) stereo = FloatArray(n * 2)
            // 2ch にする(モノラルは左右に同じ音、3ch 以上は左右にまとめる)
            when (ch) {
                1 -> for (i in 0 until n) { stereo[i * 2] = src[i]; stereo[i * 2 + 1] = src[i] }
                2 -> System.arraycopy(src, 0, stereo, 0, n * 2)
                else -> for (i in 0 until n) {
                    var l = src[i * ch]
                    var r = src[i * ch + 1]
                    if (ch > 2) { l += src[i * ch + 2] * 0.7071f; r += src[i * ch + 2] * 0.7071f }
                    for (c in 4 until ch) { if (c % 2 == 0) l += src[i * ch + c] * 0.7071f else r += src[i * ch + c] * 0.7071f }
                    stereo[i * 2] = l * 0.5f
                    stereo[i * 2 + 1] = r * 0.5f
                }
            }
            var buf = stereo
            var count = n
            rs?.let { r ->
                val need = r.maxOut(n) * 2
                if (rsOut.size < need) rsOut = FloatArray(need)
                count = r.process(stereo, n, rsOut)
                buf = rsOut
            }
            if (bytes.size < count * 4) bytes = ByteArray(count * 4)
            var o = 0
            for (k in 0 until count * 2) {
                var v = buf[k] * 32768f
                // 三角形の分布のディザー(±1 LSB)
                if (dither) v += rnd.nextFloat() - rnd.nextFloat()
                val s = Math.round(v).coerceIn(-32768, 32767)
                bytes[o] = s.toByte()
                bytes[o + 1] = (s shr 8).toByte()
                o += 2
            }
            os.write(bytes, 0, o)
            frames += count
        }

        /** 書き終える。周波数を変換したときは、フィルターに残っている最後の音も出す */
        fun finish(): Long {
            rs?.let { r ->
                val n = r.delayFrames
                put(FloatArray(n * ch), 0, n)
            }
            return frames
        }
    }
}
