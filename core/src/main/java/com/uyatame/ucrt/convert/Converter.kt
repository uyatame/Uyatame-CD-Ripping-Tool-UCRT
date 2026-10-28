package com.uyatame.ucrt.convert

import android.content.Context
import android.net.Uri
import com.uyatame.ucrt.player.Decoders
import com.uyatame.ucrt.player.PcmDecoder
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** 書き出す形式 */
enum class OutFormat(val ext: String, val dsd: Boolean, val mime: String) {
    FLAC("flac", false, "audio/flac"),
    WAV("wav", false, "audio/wav"),
    DSF("dsf", true, "audio/x-dsf"),
    DFF("dff", true, "audio/x-dff"),
}

/**
 * 変換の設定(アップサンプルのタブの、ファイルごとに共通の設定)。
 * mode 0 = DSRE(高域を補ってアップサンプリング)、1 = DSD 変換(PCM ↔ DSD・DSD → DSD・PCM → PCM)
 */
data class ConvertSpec(
    val mode: Int = 1,
    val format: OutFormat = OutFormat.DSF,
    /** PCM の出力の周波数(0 = 元のまま。DSRE では dsre.rate を使う) */
    val pcmRate: Int = 0,
    /** PCM の出力のビット数(16・24。WAV は 32 = 浮動小数点も) */
    val bits: Int = 24,
    /** DSD の出力の周波数(2.8224 MHz × 1・2・4・8 か、3.072 MHz × 1・2・4・8) */
    val dsdRate: Int = 2822400,
    val dsre: DsreParams = DsreParams(),
    val normalize: Boolean = false,
    /** ノーマライズの目標(ピークの dBFS。-6〜0) */
    val normalizeDb: Float = -0.3f,
    val fadeIn: Boolean = false,
    val fadeInSec: Float = 2f,
    val fadeOut: Boolean = false,
    val fadeOutSec: Float = 3f,
    val trimStart: Boolean = false,
    val trimEnd: Boolean = false,
    /** 無音とみなす大きさ(dBFS) */
    val trimDb: Float = -60f,
) {
    val outRate: Int get() = if (mode == 0) dsre.rate else pcmRate
}

/** 変換の進み具合(0〜1)と、今していること */
fun interface ConvertProgress {
    fun update(fraction: Float, analyzing: Boolean)
}

/** 変換をやめた */
class ConvertCancelled : IOException("cancelled")

/**
 * 1 曲を変換する(どの形式からでも)。音はアプリ内蔵のデコーダーで読み、
 * 編集(無音の削除・ノーマライズ・フェード)→ 周波数の変換 → DSRE → リミッター → PCM の書き出し、または DSD の変調、の順に通す。
 *
 * 無音の削除・ノーマライズ・フェードアウト・DSRE を使うときは、先に一度読んで、大きさ・無音の位置・元の音の帯域を調べる。
 * DSD の曲は 176.4 / 192 kHz の PCM にして読み、+6 dB する(DSD の 0 dB を PCM のフルスケールに合わせる。
 * PCM から DSD にするときはその逆で、DSD → DSD の大きさは変わらない)
 */
class Converter internal constructor(
    private val ctx: Context?,
    private val log: (String) -> Unit,
    /** 曲を開く(試験では差し替える) */
    private val opener: (Uri?, String) -> Decoders.Opened,
) {
    constructor(ctx: Context, log: (String) -> Unit = {}) : this(ctx, log, { u, n -> Decoders.open(ctx, u!!, n, hq = true) })

    /** 調べた結果 */
    class Analysis(val frames: Long, val peak: Float, val first: Long, val last: Long, val cutHz: Double)

    /** 元の曲の情報(画面の表示用) */
    class SourceInfo(val rate: Int, val bits: Int, val channels: Int, val dsd: Boolean, val dsdRate: Int, val frames: Long)

    fun probe(uri: Uri, name: String): SourceInfo {
        if (com.uyatame.ucrt.player.dsd.DsdFile.isDsd(name)) {
            val f = com.uyatame.ucrt.player.dsd.RandomFile.open(ctx!!, uri) ?: throw IOException("cannot open")
            f.use {
                val i = com.uyatame.ucrt.player.dsd.DsdFile.parse(it)
                return SourceInfo(0, 1, i.channels, true, i.rate, i.bytesPerChannel * 8)
            }
        }
        opener(uri, name).use { o ->
            val d = o.decoder
            return SourceInfo(d.sampleRate, d.bitsPerSample, d.channels, false, 0, d.totalSamples)
        }
    }

    private fun srcGain(name: String) = if (com.uyatame.ucrt.player.dsd.DsdFile.isDsd(name)) 2f else 1f

    /** 一度読んで調べる */
    private fun analyze(uri: Uri?, name: String, spec: ConvertSpec, total: Long, prog: ConvertProgress, cancel: () -> Boolean): Analysis {
        opener(uri, name).use { o ->
            val d = o.decoder
            val ch = d.channels
            val scale = srcGain(name) / (1L shl (d.bitsPerSample - 1)).toFloat()
            val thr = 10.0.pow(spec.trimDb / 20.0).toFloat()
            var peak = 0f
            var first = -1L
            var last = -1L
            var pos = 0L
            // 帯域を調べる(DSRE): 曲の中の 48 か所で 4096 点の FFT をし、平均する
            val wantSpec = spec.mode == 0
            val nfft = 4096
            val spec2 = DoubleArray(nfft / 2)
            var windows = 0
            val stride = if (total > 0) (total / 48).coerceAtLeast(nfft.toLong()) else d.sampleRate * 4L
            var nextWin = stride / 2
            val win = FloatArray(nfft)
            var wfill = -1
            while (d.decodeNext()) {
                if (cancel()) throw ConvertCancelled()
                val n = d.blockSize
                val s = d.samples
                for (i in 0 until n) {
                    var m = 0f
                    var mono = 0f
                    for (c in 0 until ch) {
                        val v = s[c][i] * scale
                        val a = if (v < 0) -v else v
                        if (a > m) m = a
                        mono += v
                    }
                    if (m > peak) peak = m
                    val g = pos + i
                    if (m > thr) {
                        if (first < 0) first = g
                        last = g
                    }
                    if (wantSpec) {
                        if (wfill < 0 && g >= nextWin) wfill = 0
                        if (wfill >= 0) {
                            win[wfill++] = mono / ch
                            if (wfill == nfft) {
                                addSpectrum(win, spec2)
                                windows++
                                wfill = -1
                                nextWin = g + stride
                            }
                        }
                    }
                }
                pos += n
                if (total > 0) prog.update((pos.toFloat() / total).coerceAtMost(1f), true)
            }
            val cut = if (wantSpec) cutoff(spec2, windows, d.sampleRate) else 0.0
            if (wantSpec) log("dsre: source bandwidth ${"%.1f".format(cut / 1000)} kHz (rate ${d.sampleRate})")
            return Analysis(pos, peak, first, last, cut)
        }
    }

    private fun addSpectrum(x: FloatArray, acc: DoubleArray) {
        val n = x.size
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until n) re[i] = x[i] * (0.5 - 0.5 * cos(2 * PI * i / (n - 1)))
        fft(re, im)
        for (k in acc.indices) acc[k] += re[k] * re[k] + im[k] * im[k]
    }

    /**
     * 元の音が切れている周波数。平均のスペクトル(200 Hz ほどでならす)の、上半分の帯域の最小(雑音の床)より
     * 15 dB 以上大きい、いちばん高い周波数
     */
    private fun cutoff(acc: DoubleArray, windows: Int, rate: Int): Double {
        val nyq = rate / 2.0
        if (windows == 0) return nyq * 0.95
        val n = acc.size
        val db = DoubleArray(n) { 10 * log10(acc[it] / windows + 1e-30) }
        val hz = nyq / n
        val half = (200 / hz).toInt().coerceAtLeast(1)
        val sm = DoubleArray(n) { k ->
            var s = 0.0; var c = 0
            for (j in (k - half).coerceAtLeast(0)..(k + half).coerceAtMost(n - 1)) { s += db[j]; c++ }
            s / c
        }
        var floor = Double.MAX_VALUE
        for (k in n / 2 until n - half) if (sm[k] < floor) floor = sm[k]
        val thr = floor + 15
        var top = n - 1 - half
        while (top > 0 && sm[top] < thr) top--
        return ((top + 1) * hz).coerceIn(4000.0, nyq * 0.98)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr0 = cos(ang); val wi0 = sin(ang)
            var base = 0
            while (base < n) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) {
                    val p = base + k; val q = p + len / 2
                    val vr = re[q] * wr - im[q] * wi
                    val vi = re[q] * wi + im[q] * wr
                    re[q] = re[p] - vr; im[q] = im[p] - vi
                    re[p] += vr; im[p] += vi
                    val t = wr * wr0 - wi * wi0
                    wi = wr * wi0 + wi * wr0; wr = t
                }
                base += len
            }
            len = len shl 1
        }
    }

    /**
     * 変換して out に書く。tags は書き出すファイルに付ける曲の情報。
     * cancel() が true になったら ConvertCancelled を投げる(書きかけのファイルは呼び出し側で消す)
     */
    fun convert(uri: Uri?, name: String, spec: ConvertSpec, out: SeekOut, tags: OutTags?, prog: ConvertProgress, cancel: () -> Boolean) {
        val pre = opener(uri, name).use { it.decoder.let { d -> Triple(d.sampleRate, d.channels, d.totalSamples) } }
        val (srcRate, ch, total0) = pre
        val needAnalysis = spec.normalize || spec.trimStart || spec.trimEnd || spec.fadeOut || spec.mode == 0
        val an = if (needAnalysis) analyze(uri, name, spec, total0, { f, _ -> prog.update(f * 0.25f, true) }, cancel) else null
        val total = an?.frames ?: total0
        val p0 = if (an != null) 0.25f else 0f
        // 残す範囲(無音の削除。前後に 10 ms の余裕を残す)
        val margin = srcRate / 100L
        var keepFrom = 0L
        var keepTo = if (total > 0) total else Long.MAX_VALUE
        if (an != null && an.first >= 0) {
            if (spec.trimStart) keepFrom = (an.first - margin).coerceAtLeast(0)
            if (spec.trimEnd) keepTo = (an.last + 1 + margin).coerceAtMost(total)
        }
        val keepLen = keepTo - keepFrom
        val normGain = if (spec.normalize && an != null && an.peak > 1e-6f) (10.0.pow(spec.normalizeDb / 20.0) / an.peak).toFloat() else 1f
        val fadeInN = if (spec.fadeIn) (spec.fadeInSec * srcRate).toLong().coerceAtLeast(1) else 0L
        val fadeOutN = if (spec.fadeOut && keepLen != Long.MAX_VALUE) (spec.fadeOutSec * srcRate).toLong().coerceAtLeast(1) else 0L

        val dsdOut = spec.format.dsd
        val family = if (spec.dsdRate % 48000 == 0) 48000 else 44100
        val rate = when {
            dsdOut -> family * 8
            spec.outRate > 0 -> spec.outRate
            else -> srcRate
        }
        val res = Resample(srcRate, rate, ch)
        val dsre = if (spec.mode == 0) Dsre(rate, ch, spec.dsre, an?.cutHz ?: (srcRate / 2.0 * 0.95)) else null
        val ceiling = when {
            dsdOut -> 1.0f
            spec.normalize -> minOf(0.999f, 10.0.pow(spec.normalizeDb / 20.0).toFloat())
            spec.bits == 32 && spec.format == OutFormat.WAV -> 1.0f
            else -> 0.999f
        }
        val lim = Limiter(rate, ch, ceiling)
        var limSkip = lim.latency.toLong()
        log(
            "convert: $name ${srcRate}Hz ${ch}ch -> " + (if (dsdOut) "${spec.format} ${spec.dsdRate}Hz" else "${spec.format} ${rate}Hz ${spec.bits}bit") +
                (if (dsre != null) " dsre order ${dsre.order}" else "") +
                (if (normGain != 1f) " gain ${"%.2f".format(20 * log10(normGain.toDouble()))}dB" else "") +
                (if (keepFrom > 0 || keepTo < total) " keep $keepFrom..$keepTo" else ""),
        )

        val pcmW: PcmWriter? = if (dsdOut) null else when (spec.format) {
            OutFormat.WAV -> WavWriter(out, rate, ch, spec.bits, tags)
            else -> FlacWriter(out, rate, ch, spec.bits.coerceAtMost(24), tags)
        }
        val dsdW: DsdWriter? = if (!dsdOut) null else if (spec.format == OutFormat.DFF) DffWriter(out, spec.dsdRate, ch, tags) else DsfWriter(out, spec.dsdRate, ch, tags)
        val f2 = spec.dsdRate / rate
        val mods = if (dsdOut) Array(ch) { DsdModulator(spec.dsdRate, f2) } else emptyArray()
        val pool: ExecutorService? = if (dsdOut && ch > 1) Executors.newFixedThreadPool(minOf(ch, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))) else null
        var chBuf = Array(if (dsdOut) ch else 0) { FloatArray(0) }
        var bits = Array(if (dsdOut) ch else 0) { ByteArray(0) }

        /** リミッターのあとの音を書き出す */
        fun emit(buf: FloatArray, framesIn: Int) {
            var frames = framesIn
            var off = 0
            if (limSkip > 0) {
                val d = minOf(limSkip, frames.toLong()).toInt()
                limSkip -= d; off = d; frames -= d
            }
            if (frames <= 0) return
            if (off > 0) System.arraycopy(buf, off * ch, buf, 0, frames * ch)
            if (pcmW != null) { pcmW.write(buf, frames); return }
            if (chBuf[0].size < frames) chBuf = Array(ch) { FloatArray(frames) }
            val need = frames * f2 / 8 + 2
            if (bits[0].size < need) bits = Array(ch) { ByteArray(need) }
            for (c in 0 until ch) {
                val dst = chBuf[c]
                var k = c
                // DSD の 0 dB(変調度 50%)を PCM のフルスケールに合わせる
                for (i in 0 until frames) { dst[i] = buf[k] * 0.5f; k += ch }
            }
            val counts = IntArray(ch)
            if (pool != null) {
                val fs = ArrayList<Future<Int>>(ch)
                for (c in 0 until ch) fs.add(pool.submit(Callable { mods[c].modulate(chBuf[c], frames, bits[c]) }))
                for (c in 0 until ch) counts[c] = fs[c].get()
            } else {
                for (c in 0 until ch) counts[c] = mods[c].modulate(chBuf[c], frames, bits[c])
            }
            dsdW!!.write(bits, counts[0])
        }

        var processed = 0L
        try {
            opener(uri, name).use { o ->
                val d: PcmDecoder = o.decoder
                val scale = srcGain(name) / (1L shl (d.bitsPerSample - 1)).toFloat()
                var inBuf = FloatArray(0)
                var outBuf = FloatArray(0)
                var pos = 0L
                while (pos < keepTo && d.decodeNext()) {
                    if (cancel()) throw ConvertCancelled()
                    val n = d.blockSize
                    val s = d.samples
                    if (inBuf.size < n * ch) inBuf = FloatArray(n * ch)
                    var m = 0
                    for (i in 0 until n) {
                        val g = pos + i
                        if (g < keepFrom || g >= keepTo) continue
                        val rel = g - keepFrom
                        var gain = normGain
                        if (fadeInN > 0 && rel < fadeInN) gain *= curve(rel.toDouble() / fadeInN)
                        if (fadeOutN > 0 && keepLen - rel <= fadeOutN) gain *= curve((keepLen - rel - 1).toDouble() / fadeOutN)
                        val b = m * ch
                        for (c in 0 until ch) inBuf[b + c] = s[c][i] * scale * gain
                        m++
                    }
                    pos += n
                    if (m > 0) {
                        val cap = res.maxOut(m) * ch
                        if (outBuf.size < cap) outBuf = FloatArray(cap)
                        val k = res.process(inBuf, m, outBuf)
                        if (k > 0) {
                            dsre?.process(outBuf, k)
                            lim.process(outBuf, k)
                            emit(outBuf, k)
                        }
                    }
                    processed = pos
                    if (total > 0) prog.update(p0 + (1 - p0) * (pos.toFloat() / total).coerceAtMost(1f), false)
                }
                // 残りを出す(周波数の変換のフィルター → リミッター)
                val cap = res.maxOut(4096).coerceAtLeast(4096) * ch
                if (outBuf.size < cap) outBuf = FloatArray(cap)
                while (true) {
                    val k = res.finish(outBuf)
                    if (k <= 0) break
                    dsre?.process(outBuf, k)
                    lim.process(outBuf, k)
                    emit(outBuf, k)
                }
                val tail = FloatArray(lim.latency * ch)
                val k = lim.flush(tail)
                emit(tail, k)
            }
            if (dsdOut) {
                // 最後の 1 バイトに満たないビットを埋める
                val last = Array(ch) { ByteArray(1) }
                var n = 0
                for (c in 0 until ch) n = maxOf(n, mods[c].flush(last[c]))
                if (n > 0) dsdW!!.write(last, 1)
                val resets = mods.sumOf { it.resets }
                if (resets > 0) log("convert: modulator reset $resets times")
            }
            if (lim.limited > 0) log("convert: limiter engaged for ${lim.limited * 1000 / rate} ms")
            pcmW?.finish()
            dsdW?.finish()
            prog.update(1f, false)
        } finally {
            pool?.shutdownNow()
        }
        if (processed == 0L) throw IOException("no audio")
    }

    /** フェードの形(なめらかな S 字。0〜1) */
    private fun curve(t: Double): Float = if (t >= 1) 1f else if (t <= 0) 0f else (0.5 - 0.5 * cos(PI * t)).toFloat()
}

/** 設定の保存用(JSON の文字列) */
fun ConvertSpec.encode(): String = org.json.JSONObject()
    .put("mode", mode).put("format", format.name).put("pcmRate", pcmRate).put("bits", bits).put("dsdRate", dsdRate)
    .put("dsre", dsre.toJson())
    .put("normalize", normalize).put("normalizeDb", normalizeDb.toDouble())
    .put("fadeIn", fadeIn).put("fadeInSec", fadeInSec.toDouble()).put("fadeOut", fadeOut).put("fadeOutSec", fadeOutSec.toDouble())
    .put("trimStart", trimStart).put("trimEnd", trimEnd).put("trimDb", trimDb.toDouble())
    .toString()

/** 保存した設定を読む(読めない項目は初期値) */
fun decodeConvertSpec(raw: String?): ConvertSpec {
    val d = ConvertSpec()
    if (raw.isNullOrBlank()) return d
    return runCatching {
        val o = org.json.JSONObject(raw)
        ConvertSpec(
            mode = o.optInt("mode", d.mode).coerceIn(0, 1),
            format = runCatching { OutFormat.valueOf(o.optString("format")) }.getOrDefault(d.format),
            pcmRate = o.optInt("pcmRate", d.pcmRate),
            bits = o.optInt("bits", d.bits).let { if (it in setOf(16, 24, 32)) it else 24 },
            dsdRate = o.optInt("dsdRate", d.dsdRate).let { if (it in DSD_RATES) it else d.dsdRate },
            dsre = o.optJSONObject("dsre")?.let { j -> runCatching { DsreParams.fromJson(j.put("name", j.optString("name", ""))) }.getOrNull() } ?: d.dsre,
            normalize = o.optBoolean("normalize", d.normalize),
            normalizeDb = o.optDouble("normalizeDb", d.normalizeDb.toDouble()).toFloat().coerceIn(-12f, 0f),
            fadeIn = o.optBoolean("fadeIn", d.fadeIn),
            fadeInSec = o.optDouble("fadeInSec", d.fadeInSec.toDouble()).toFloat().coerceIn(0.1f, 30f),
            fadeOut = o.optBoolean("fadeOut", d.fadeOut),
            fadeOutSec = o.optDouble("fadeOutSec", d.fadeOutSec.toDouble()).toFloat().coerceIn(0.1f, 30f),
            trimStart = o.optBoolean("trimStart", d.trimStart),
            trimEnd = o.optBoolean("trimEnd", d.trimEnd),
            trimDb = o.optDouble("trimDb", d.trimDb.toDouble()).toFloat().coerceIn(-90f, -30f),
        )
    }.getOrDefault(d)
}

/** 選べる DSD の周波数(44.1 kHz 系と 48 kHz 系の DSD64〜512) */
val DSD_RATES = listOf(2822400, 5644800, 11289600, 22579200, 3072000, 6144000, 12288000, 24576000)

/** 選べる PCM の周波数(0 = 元のまま) */
val PCM_RATES = listOf(0, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)
