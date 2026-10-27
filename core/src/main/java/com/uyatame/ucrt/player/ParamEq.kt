package com.uyatame.ucrt.player

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** イコライザーの帯域の種類 */
enum class EqType(val code: Char) {
    /** 山・谷(ピーキング) */
    Peak('P'),
    /** 低域をまとめて上げ下げ(ローシェルフ) */
    LowShelf('L'),
    /** 高域をまとめて上げ下げ(ハイシェルフ) */
    HighShelf('H'),
    /** 低域を切る(ハイパス) */
    LowCut('C'),
    /** 高域を切る(ローパス) */
    HighCut('X'),
    ;

    /** 利得(dB)を使う種類か(カットは使わない) */
    val hasGain: Boolean get() = this == Peak || this == LowShelf || this == HighShelf
}

/** イコライザーの 1 つの帯域 */
data class EqBand(val type: EqType, val freq: Float, val gain: Float, val q: Float)

/** 自由な帯域・自由なバンド数のイコライザー(パラメトリック EQ)の計算 */
object ParamEq {
    const val MIN_FREQ = 20f
    const val MAX_FREQ = 20000f
    const val MAX_DB = 15f
    const val MIN_Q = 0.3f
    const val MAX_Q = 12f
    const val MAX_BANDS = 16

    fun encode(bands: List<EqBand>): String = bands.joinToString(";") {
        String.format(Locale.US, "%c,%.1f,%.2f,%.3f", it.type.code, it.freq, it.gain, it.q)
    }

    fun decode(s: String?): List<EqBand> = s.orEmpty().split(';').mapNotNull { part ->
        val p = part.split(',')
        if (p.size != 4) return@mapNotNull null
        val type = EqType.entries.firstOrNull { it.code == p[0].firstOrNull() } ?: return@mapNotNull null
        val f = p[1].toFloatOrNull() ?: return@mapNotNull null
        val g = p[2].toFloatOrNull() ?: return@mapNotNull null
        val q = p[3].toFloatOrNull() ?: return@mapNotNull null
        EqBand(type, f.coerceIn(MIN_FREQ, MAX_FREQ), g.coerceIn(-MAX_DB, MAX_DB), q.coerceIn(MIN_Q, MAX_Q))
    }.take(MAX_BANDS)

    /** 以前の 10 バンドの設定(各帯域の dB)を、同じ形のパラメトリック EQ にする */
    fun fromGains(g: FloatArray): List<EqBand> =
        EqBands.FREQS.mapIndexed { i, f -> EqBand(EqType.Peak, f, g.getOrElse(i) { 0f }, 1.41f) }

    /**
     * 双二次フィルターの係数(b0, b1, b2, a1, a2。a0 で割ったもの)。Audio EQ Cookbook(RBJ)の式。
     * 周波数がナイキストに近すぎるものや、効果の無いもの(利得 0 の山・谷など)は null
     */
    fun coefs(b: EqBand, fs: Double): DoubleArray? {
        val f = b.freq.toDouble()
        if (f <= 0 || f >= fs * 0.49) return null
        if (b.type.hasGain && b.gain == 0f) return null
        val w0 = 2 * PI * f / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * b.q.toDouble().coerceAtLeast(0.05))
        val a = 10.0.pow(b.gain / 40.0)
        val r = when (b.type) {
            EqType.Peak -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
            EqType.LowShelf -> {
                val s = 2 * sqrt(a) * alpha
                doubleArrayOf(
                    a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s),
                    (a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s,
                )
            }
            EqType.HighShelf -> {
                val s = 2 * sqrt(a) * alpha
                doubleArrayOf(
                    a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s),
                    (a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s,
                )
            }
            EqType.LowCut -> doubleArrayOf((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
            EqType.HighCut -> doubleArrayOf((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
        }
        val a0 = r[3]
        return doubleArrayOf(r[0] / a0, r[1] / a0, r[2] / a0, r[4] / a0, r[5] / a0)
    }

    /** 1 つのフィルターの、周波数 f での利得(dB) */
    private fun bandDb(c: DoubleArray, f: Double, fs: Double): Double {
        val w = 2 * PI * f / fs
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = c[0] + c[1] * c1 + c[2] * c2
        val ni = -(c[1] * s1 + c[2] * s2)
        val dr = 1 + c[3] * c1 + c[4] * c2
        val di = -(c[3] * s1 + c[4] * s2)
        val mag = hypot(nr, ni) / max(hypot(dr, di), 1e-12)
        return 20 * log10(max(mag, 1e-9))
    }

    /** すべての帯域を合わせた、周波数 f での利得(dB)。画面のグラフ用(48 kHz で計算) */
    fun responseDb(bands: List<EqBand>, f: Double, fs: Double = 48000.0): Double {
        var db = 0.0
        for (b in bands) coefs(b, fs)?.let { db += bandDb(it, f, fs) }
        return db
    }

    /**
     * 周波数 freqs[i] それぞれでの、すべての帯域を合わせた利得(dB)。responseDb を点ごとに呼ぶのと同じ値になるが、
     * 帯域の係数は 1 回だけ計算する(グラフは数百の点を描くので、点ごとに係数を作り直すと重い)
     */
    fun responseCurve(bands: List<EqBand>, freqs: DoubleArray, fs: Double = 48000.0): DoubleArray {
        val cs = bands.mapNotNull { coefs(it, fs) }
        return DoubleArray(freqs.size) { i ->
            var db = 0.0
            for (c in cs) db += bandDb(c, freqs[i], fs)
            db
        }
    }

    /** 20 Hz〜20 kHz で、いちばん持ち上がっているところの dB(音割れを防ぐ自動の音量の下げ幅に使う) */
    fun maxBoostDb(bands: List<EqBand>): Float {
        if (bands.isEmpty()) return 0f
        var m = 0.0
        for (db in responseCurve(bands, BOOST_FREQS)) m = max(m, db)
        return m.toFloat()
    }

    /** maxBoostDb で調べる周波数(20 Hz〜20 kHz を対数で 161 点) */
    private val BOOST_FREQS: DoubleArray by lazy {
        val n = 160
        val l0 = ln(MIN_FREQ.toDouble()); val l1 = ln(MAX_FREQ.toDouble())
        DoubleArray(n + 1) { i -> kotlin.math.exp(l0 + (l1 - l0) * i / n) }
    }
}

/** パラメトリック EQ の実際の処理(帯域ごとの双二次フィルターを順に通す。チャンネル交互の float) */
internal class ParamEqualizer(private val sampleRate: Int, private val channels: Int) {
    private var c = Array(0) { DoubleArray(5) }
    private var z1 = Array(channels) { DoubleArray(0) }
    private var z2 = Array(channels) { DoubleArray(0) }
    var version = -1

    fun update(bands: List<EqBand>) {
        val nc = bands.mapNotNull { ParamEq.coefs(it, sampleRate.toDouble()) }.toTypedArray()
        // 帯域の数が変わったときだけ状態を作り直す(数が同じなら、つながりを保ってプツッと鳴らないように)
        if (nc.size != c.size) {
            z1 = Array(channels) { DoubleArray(nc.size) }
            z2 = Array(channels) { DoubleArray(nc.size) }
        }
        c = nc
    }

    val active: Boolean get() = c.isNotEmpty()

    /** 1 チャンネル分を double で持っておく作業用の場所 */
    private var tmp = DoubleArray(0)

    /**
     * 帯域ごとに、まとめて計算する(係数と状態を手元の変数に置けるので速い)。
     * 1 サンプルずつ全帯域を通す計算と、同じ値を同じ順に計算するので、結果はまったく同じ
     */
    fun process(buf: FloatArray, samples: Int) {
        val n = c.size
        if (n == 0) return
        val frames = samples / channels
        if (tmp.size < frames) tmp = DoubleArray(frames)
        val t = tmp
        for (ch in 0 until channels) {
            for (f in 0 until frames) t[f] = buf[f * channels + ch].toDouble()
            val s1 = z1[ch]
            val s2 = z2[ch]
            for (k in 0 until n) {
                val k5 = c[k]
                val b0 = k5[0]; val b1 = k5[1]; val b2 = k5[2]; val a1 = k5[3]; val a2 = k5[4]
                var z1k = s1[k]
                var z2k = s2[k]
                for (f in 0 until frames) {
                    val x = t[f]
                    // 転置直接形 II
                    val y = b0 * x + z1k
                    z1k = b1 * x - a1 * y + z2k
                    z2k = b2 * x - a2 * y
                    t[f] = y
                }
                s1[k] = z1k
                s2[k] = z2k
            }
            for (f in 0 until frames) buf[f * channels + ch] = t[f].toFloat()
        }
    }
}
