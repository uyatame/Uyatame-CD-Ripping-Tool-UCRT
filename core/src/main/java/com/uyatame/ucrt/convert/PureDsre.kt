package com.uyatame.ucrt.convert

import com.uyatame.ucrt.player.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 純正 DSRE (Urabewe 原典) のパラメータ。C++ 版 dsre_core の Params と同じ意味・同じ既定値。
 * DsreParams からの対応付けは [fromDsre] (設計書「純正DSRE移植」2.3)。
 */
data class PureDsreParams(
    val harmonicIntensity: Double = 1.0,
    val enhancementStrength: Double = 0.7,
    val stereoWidth: Float = 1.15f,
    val dynamicEnhancement: Double = 1.12,
    val usePsycho: Boolean = true,
    /** 各バンドの倍音数の上限 (原典は無制限)。DsreParams.harmonics がここに入る */
    val harmonicsCap: Int = Int.MAX_VALUE,
) {
    companion object {
        /** DsreParams → 原典パラメータ。enhancement_strength と use_psycho は原典既定に固定 */
        fun fromDsre(p: DsreParams): PureDsreParams = PureDsreParams(
            harmonicIntensity = p.strength.toDouble() * 2.2,
            enhancementStrength = 0.7,
            stereoWidth = p.width,
            dynamicEnhancement = p.dynamic.toDouble(),
            usePsycho = true,
            harmonicsCap = p.harmonics,
        )
    }
}

/** 原典 sanitize_audio: NaN/Inf → 0、ピークが 1000 を超えるときだけ全体を割る */
internal fun sanitize(x: FloatArray, n: Int = x.size) {
    for (i in 0 until n) if (!x[i].isFinite()) x[i] = 0f
    var p = 0f
    for (i in 0 until n) p = max(p, abs(x[i]))
    if (p > 1000f) for (i in 0 until n) x[i] /= p
}

/** sanitize の範囲版 (x[off until off+n] だけを見る) */
internal fun sanitizeRange(x: FloatArray, off: Int, n: Int) {
    for (i in off until off + n) if (!x[i].isFinite()) x[i] = 0f
    var p = 0f
    for (i in off until off + n) p = max(p, abs(x[i]))
    if (p > 1000f) for (i in off until off + n) x[i] /= p
}

internal fun peakOf(x: FloatArray, n: Int = x.size): Float {
    var p = 0f
    for (i in 0 until n) p = max(p, abs(x[i]))
    return p
}

/** 帯域マスク (raised-cosine、遷移帯は帯域幅の 15%)。原典 band_mask の移植 */
private fun bandMask(freq: Double, lowHz: Double, highHz: Double, nyquist: Double): Double {
    val bw = max(20.0, (highHz - lowHz) * 0.15)
    val low1 = max(0.0, lowHz - bw)
    val low2 = lowHz
    val high1 = highHz
    val high2 = min(nyquist, highHz + bw)
    if (freq >= low1 && freq < low2) {
        val t = (freq - low1) / max(1e-12, low2 - low1)
        return 0.5 - 0.5 * cos(PI * t)
    }
    if (freq >= low2 && freq <= high1) return 1.0
    if (freq > high1 && freq <= high2) {
        val t = (freq - high1) / max(1e-12, high2 - high1)
        return 0.5 + 0.5 * cos(PI * t)
    }
    return 0.0
}

internal class BandDef(val low: Double, val high: Double, val gain: Double, val harmonics: Int, val strength: Double)

/** 原典の固定 7 バンド。Air の上限は min(20000, ナイキスト (整数) − 1000) */
internal fun bandDefs(rate: Int): List<BandDef> {
    val nyqI = rate / 2
    return listOf(
        BandDef(20.0, 80.0, 1.10, 3, 0.08),
        BandDef(80.0, 250.0, 1.20, 4, 0.14),
        BandDef(250.0, 800.0, 1.25, 5, 0.18),
        BandDef(800.0, 2500.0, 1.35, 6, 0.22),
        BandDef(2500.0, 8000.0, 1.45, 4, 0.25),
        BandDef(8000.0, 16000.0, 1.50, 3, 0.18),
        BandDef(16000.0, min(20000.0, (nyqI - 1000).toDouble()), 1.35, 2, 0.12),
    )
}

/**
 * マルチバンド・エキサイター (1 チャンネル分、全長)。FFT は呼び出し側で使い回す。
 * origin は x[0] の絶対サンプル位置 (発振器の位相を絶対位置で引くため。チャンク分割しても位相が連続する)。
 */
internal class PureExciter(private val rate: Int, private val fftSize: Int) {
    private val fft = Fft(fftSize)
    private val specRe = FloatArray(fftSize)
    private val specIm = FloatArray(fftSize)
    private val workRe = FloatArray(fftSize)
    private val workIm = FloatArray(fftSize)
    private val band = FloatArray(fftSize)
    private val content = FloatArray(fftSize)

    /** x (長さ fftSize) を処理して out へ (out は x のコピーから始める)。原典 multiband_exciter_ch の移植 */
    fun process(x: FloatArray, out: FloatArray, intensity: Double, harmonicsCap: Int, origin: Long) {
        val n = fftSize
        val nyqI = rate / 2
        val base = intensity.coerceIn(0.1, 1.5)
        x.copyInto(specRe)
        specIm.fill(0f)
        fft.run(specRe, specIm)
        x.copyInto(out)
        for (b in bandDefs(rate)) {
            if (!(b.low < nyqI && b.high < nyqI && b.high > b.low)) continue
            bandpass(b.low, b.high)
            sanitize(band)
            if (peakOf(band) < 1e-8f) continue
            val center = (b.low + b.high) / 2.0
            val harmonics = generateHarmonics(center, min(b.harmonics, harmonicsCap), b.strength * base, origin)
            var finite = true
            for (i in 0 until n) {
                val s = tanh((harmonics[i] * 1.35f).toDouble()).toFloat() * 0.82f
                val be = s * b.gain.toFloat()
                if (!be.isFinite()) { finite = false; break }
                harmonics[i] = be
            }
            if (!finite) continue
            for (i in 0 until n) out[i] += harmonics[i] * 0.22f
        }
        sanitize(out)
    }

    /** スペクトルに帯域マスクを掛けて時間領域に戻し、band へ置く。原典 bandpass_from_spec の移植 */
    private fun bandpass(lowHz: Double, highHz: Double) {
        val n = fftSize
        val nyq = rate / 2.0
        val low = max(1.0, lowHz)
        val high = min(highHz, nyq - 1.0)
        if (low >= high) { band.fill(0f); return }
        for (k in 0 until n) {
            val fk = if (k <= n / 2) k else n - k
            val freq = fk * rate.toDouble() / n
            val m = bandMask(freq, low, high, nyq).toFloat()
            workRe[k] = specRe[k] * m
            workIm[k] = specIm[k] * m
        }
        fft.runInverse(workRe, workIm)
        workRe.copyInto(band)
    }

    /**
     * 原典 generate_harmonics の移植。band の内容を バンド中心 × h の正弦波で乗算して倍音を作る。
     * 発振器は 4096 サンプル毎に絶対位相で再初期化する漸化式 (原典 C++ 版と同じ方式)。
     */
    private fun generateHarmonics(center: Double, numHarmonics: Int, strength: Double, origin: Long): FloatArray {
        sanitize(band)
        val enhanced = band.copyOf()
        val n = fftSize
        for (h in 2 until numHarmonics + 2) {
            val hf = center * h
            if (!(hf < rate / 2.0)) continue
            val inc = 2.0 * PI * hf / rate
            if (!inc.isFinite()) continue
            val scale = (strength / h).toFloat()
            val stepS = sin(inc)
            val stepC = cos(inc)
            var ok = true
            var s = sin(inc * origin.toDouble())
            var c = cos(inc * origin.toDouble())
            for (i in 0 until n) {
                val a = origin + i
                if (i > 0 && a % 4096L == 0L) { s = sin(inc * a.toDouble()); c = cos(inc * a.toDouble()) }
                val osc = s.toFloat()
                if (!osc.isFinite()) { ok = false; break }
                val v = band[i] * osc * scale
                if (!v.isFinite()) { ok = false; break }
                content[i] = v
                val ns = s * stepC + c * stepS
                c = c * stepC - s * stepS
                s = ns
            }
            if (!ok) {
                // 従来どおり直接 sin で作り直し、それでも駄目なら倍音ごと捨てる
                ok = true
                for (i in 0 until n) {
                    val osc = sin(inc * (origin + i).toDouble()).toFloat()
                    if (!osc.isFinite()) { ok = false; break }
                    val v = band[i] * osc * scale
                    if (!v.isFinite()) { ok = false; break }
                    content[i] = v
                }
                if (!ok) continue
            }
            for (i in 0 until n) enhanced[i] += content[i]
        }
        sanitize(enhanced)
        return enhanced
    }
}

/** ピーキング EQ の係数 (RBJ)。原典 design_peaking_eq の移植 */
private fun designPeakingEq(freq: Double, gainDb: Double, q: Double, sr: Int, b: DoubleArray, a: DoubleArray) {
    val bigA = 10.0.pow(gainDb / 40.0)
    val w0 = 2.0 * PI * freq / sr
    val alpha = sin(w0) / (2.0 * q)
    val cw = cos(w0)
    b[0] = 1 + alpha * bigA; b[1] = -2 * cw; b[2] = 1 - alpha * bigA
    a[0] = 1 + alpha / bigA; a[1] = -2 * cw; a[2] = 1 - alpha / bigA
}

/** IIR を in-place で掛ける (double)。原典 apply_iir_inplace の移植 */
private fun applyIir(bIn: DoubleArray, aIn: DoubleArray, x: DoubleArray, m: Int) {
    val b = DoubleArray(3) { bIn[it] / aIn[0] }
    val a = DoubleArray(3) { aIn[it] / aIn[0] }
    var y0 = 0.0; var y1 = 0.0
    var x0 = 0.0; var x1 = 0.0
    for (i in 0 until m) {
        val xi = x[i]
        var acc = b[0] * xi
        if (i - 1 >= 0) acc += b[1] * x1
        if (i - 2 >= 0) acc += b[2] * x0
        if (i - 1 >= 0) acc -= a[1] * y1
        if (i - 2 >= 0) acc -= a[2] * y0
        x[i] = acc
        x0 = x1; x1 = xi
        y0 = y1; y1 = acc
    }
}

/**
 * filtfilt (ゼロ位相)。結果は work に入り、先頭 pad 分がパディング。戻り値は pad。
 * 原典 filtfilt_to の移植 (パディングは端点の奇対称反射、pad は最大 9)。
 */
private fun filtfilt(b: DoubleArray, a: DoubleArray, xIn: FloatArray, n: Int, work: DoubleArray): Int {
    if (n < 9) {
        for (i in 0 until n) work[i] = xIn[i].toDouble()
        applyIir(b, a, work, n)
        return 0
    }
    val pad = min(n - 1, 9)
    for (i in 0 until pad) work[i] = 2.0 * xIn[0] - xIn[pad - i]
    for (i in 0 until n) work[pad + i] = xIn[i].toDouble()
    for (i in 0 until pad) work[pad + n + i] = 2.0 * xIn[n - 1] - xIn[n - 2 - i]
    val m = n + 2 * pad
    applyIir(b, a, work, m)
    work.reverse(0, m)
    applyIir(b, a, work, m)
    work.reverse(0, m)
    return pad
}

private fun DoubleArray.reverse(from: Int, to: Int) {
    var i = from
    var j = to - 1
    while (i < j) {
        val t = this[i]; this[i] = this[j]; this[j] = t
        i++; j--
    }
}

/**
 * 心理音響段 (1 チャンネル分)。src (エキサイター出力) を各バンドで filtfilt し、
 * dst (= src のコピーから始まる) へ 0.22 ずつブレンドする。原典 psychoacoustic_inplace の移植。
 * 作業領域は使い回す (チャンク版でも同じインスタンスで処理できる)。
 */
internal class PurePsycho(private val rate: Int) {
    private var work = DoubleArray(0)
    private val b = DoubleArray(3)
    private val a = DoubleArray(3)

    fun process(src: FloatArray, dst: FloatArray, strength: Double) {
        val n = src.size
        val scale = strength.coerceIn(0.3, 1.5)
        val bands = arrayOf(
            doubleArrayOf(1000.0, 1.0 * scale, 1.5),
            doubleArrayOf(2500.0, 1.8 * scale, 2.0),
            doubleArrayOf(4000.0, 2.0 * scale, 1.8),
            doubleArrayOf(6000.0, 1.5 * scale, 1.2),
            doubleArrayOf(10000.0, 1.0 * scale, 0.8),
        )
        if (dst !== src) src.copyInto(dst)
        if (work.size < n + 18) work = DoubleArray(n + 18)
        for ((freq, boost, q) in bands) {
            if (freq >= rate / 2) continue
            designPeakingEq(freq, boost, q, rate, b, a)
            val pad = filtfilt(b, a, src, n, work)
            // sanitize(filtered) 相当: 非有限 → 0、peak > 1000 のときだけ割る
            var peak = 0f
            for (i in 0 until n) {
                var f = work[pad + i].toFloat()
                if (!f.isFinite()) f = 0f
                peak = max(peak, abs(f))
            }
            val blend = 0.22f
            for (i in 0 until n) {
                var f = work[pad + i].toFloat()
                if (!f.isFinite()) f = 0f
                if (peak > 1000f) f /= peak
                dst[i] = dst[i] * (1f - blend) + f * blend
            }
        }
        sanitize(dst)
    }
}

/**
 * ダイナミクス段 (上向きエクスパンダ)。包絡は過去だけに依存するので状態を持ち越せる
 * (チャンク分割時は同じインスタンスを使い続ける)。原典 dynamic_range_inplace の移植。
 */
internal class PureDynamics(rate: Int, ratioIn: Double) {
    private val ratio = ratioIn.coerceIn(1.0, 1.5).toFloat()
    private val attackSamples = max(1, (5.0 * rate / 1000).toInt())
    private val releaseSamples = max(1, (50.0 * rate / 1000).toInt())
    private var current: Float? = null

    fun process(x: FloatArray, n: Int = x.size) = process(x, 0, n)

    /** x[off until off+n] を処理する (包絡の状態は呼び出しをまたいで持ち越す) */
    fun process(x: FloatArray, off: Int, n: Int) {
        if (n == 0) return
        val threshold = 0.08f
        var cur = current ?: abs(x[off])
        for (i in off until off + n) {
            val e = abs(x[i])
            cur = if (e > cur) cur + (e - cur) / attackSamples.toFloat()
            else cur - (cur - e) / releaseSamples.toFloat()
            var gain = 1.0f
            if (cur > threshold) gain = (cur / threshold).toDouble().pow((ratio - 1.0f).toDouble()).toFloat()
            gain = gain.coerceIn(1.0f, 1.8f)
            x[i] = x[i] * gain
        }
        current = cur
        sanitizeRange(x, off, n)
    }
}

/**
 * 純正 DSRE の全長版 (検証ハーネス形)。入力・出力はチャンネル交互の float (その場で書き換える)。
 *
 * 原典 (C++ 版 dsre_core) は曲全体を一度に処理する。ここも同じ全長処理で、frames は 2 の冪であること
 * (radix-2 FFT のため)。製品経路はチャンク分割のストリーミング版 (別途) で、こちらは
 * C++ 版との数値比較 (相対 RMS 差 −40 dB 以下) のための基準実装として置く。
 *
 * normalizePeak: 原典は最後にピーク > 0.95 なら全体を 0.95 へ正規化する。全長版は原典どおり
 * 行う (製品経路では後段の Limiter が天井を保証するので行わない — 設計書 2.4)。
 */
class PureDsre(
    private val rate: Int,
    private val channels: Int,
    private val params: PureDsreParams = PureDsreParams(),
    private val normalizePeak: Boolean = true,
) {
    constructor(rate: Int, channels: Int, p: DsreParams, cutHz: Double) :
        this(rate, channels, PureDsreParams.fromDsre(p))

    fun process(buf: FloatArray, frames: Int) {
        require(frames > 0 && (frames and (frames - 1)) == 0) {
            "PureDsre (全長版) は frames が 2 の冪である必要があります: $frames"
        }
        val ch = channels
        // 平面へほどく (原音はブレンド用に保持する)
        val orig = Array(ch) { c -> FloatArray(frames) { f -> buf[f * ch + c] } }
        val work = Array(ch) { FloatArray(frames) }
        val exciter = PureExciter(rate, frames)
        for (c in 0 until ch) exciter.process(orig[c], work[c], params.harmonicIntensity, params.harmonicsCap, 0L)
        if (params.usePsycho) {
            val psycho = PurePsycho(rate)
            for (c in 0 until ch) {
                val src = work[c].copyOf()
                psycho.process(src, work[c], params.enhancementStrength)
            }
        }
        val dynamics = Array(ch) { PureDynamics(rate, params.dynamicEnhancement) }
        for (c in 0 until ch) dynamics[c].process(work[c])
        if (ch == 2) {
            val w = params.stereoWidth.coerceIn(1.0f, 1.8f)
            val l = work[0]
            val r = work[1]
            for (i in 0 until frames) {
                val mid = (l[i] + r[i]) / 2.0f
                val side = (l[i] - r[i]) / 2.0f * w
                l[i] = mid + side
                r[i] = mid - side
            }
        }
        val blend = params.enhancementStrength.coerceIn(0.1, 0.8).toFloat()
        for (c in 0 until ch) {
            val oc = work[c]
            val xc = orig[c]
            if (peakOf(oc) < 1e-10f) {
                xc.copyInto(oc)
            } else {
                for (i in 0 until frames) oc[i] = xc[i] * (1f - blend) + oc[i] * blend
            }
            sanitize(oc)
        }
        if (normalizePeak) {
            var peak = 0f
            for (c in 0 until ch) peak = max(peak, peakOf(work[c]))
            if (peak > 0.95f) {
                val s = 0.95f / peak
                for (c in 0 until ch) for (i in 0 until frames) work[c][i] *= s
            }
        }
        for (c in 0 until ch) for (f in 0 until frames) buf[f * ch + c] = work[c][f]
    }
}
