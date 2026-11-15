package com.uyatame.ucrt.aging

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * エージングの画面のスペクトラム(10 Hz から、出力の周波数の半分まで)。Android の部品には頼らない。
 * 鳴らした音(左右を混ぜたもの)をためておき、FFT で周波数に分けて、対数の間隔の帯にまとめる。
 * 帯の値は「帯の中の強さの合計」(dB)。ピンクノイズはどの帯も同じ高さになる(よくある RTA の見え方)
 */
class AgingAnalyzer(val rate: Int, val bands: Int = 64) {
    /** FFT の長さ(およそ 12 Hz ごとに分けられる長さ。2 のべき乗) */
    val size: Int = run {
        var n = 1024
        while (n < rate / 12 && n < 32768) n *= 2
        n
    }
    val lowHz = 10.0
    val highHz = rate / 2.0
    private val ring = FloatArray(size)
    private var pos = 0
    private var filled = 0
    private val window = FloatArray(size) { i -> (0.5 - 0.5 * cos(2 * PI * i / (size - 1))).toFloat() }
    // 窓をかけたことで小さくなる分を戻す(強さの合計が正しくなるように)
    private val windowPower = window.fold(0.0) { a, w -> a + w * w }
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    /** 帯の境目(Hz)。bands + 1 個 */
    val edges = DoubleArray(bands + 1) { i -> lowHz * (highHz / lowHz).pow(i.toDouble() / bands) }

    /** 鳴らした音(左右交互)を入れる */
    @Synchronized fun push(stereo: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            ring[pos] = (stereo[2 * i] + stereo[2 * i + 1]) * 0.5f
            pos = (pos + 1) % size
        }
        filled = min(size, filled + frames)
    }

    private val fftLock = Any()

    /**
     * 帯ごとの強さ(dBFS。音が足りなければ null)。音を写すところだけロックし、FFT はロックの外で計算する
     * (音を入れるスレッドを待たせないように)
     */
    fun bandsDb(): FloatArray? {
        synchronized(fftLock) { return compute() }
    }

    private fun compute(): FloatArray? {
        synchronized(this) {
            if (filled < size) return null
            for (i in 0 until size) {
                re[i] = (ring[(pos + i) % size] * window[i]).toDouble()
                im[i] = 0.0
            }
        }
        fft(re, im)
        // 周波数ごとの強さ(片側。合計すると元の音の平均の強さ ≒ 実効値の 2 乗 になるように)
        val half = size / 2
        val p = DoubleArray(half + 1)
        for (k in 0..half) {
            val m = re[k] * re[k] + im[k] * im[k]
            p[k] = m * (if (k == 0 || k == half) 1.0 else 2.0) / (size * windowPower)
        }
        val binHz = rate.toDouble() / size
        val out = FloatArray(bands)
        for (b in 0 until bands) {
            val lo = edges[b]
            val hi = edges[b + 1]
            val k0 = (lo / binHz).let { kotlin.math.ceil(it).toInt() }
            val k1 = (hi / binHz).let { kotlin.math.floor(it).toInt() }
            val sum = if (k1 >= k0) {
                var s = 0.0
                for (k in max(k0, 1)..min(k1, half)) s += p[k]
                s
            } else {
                // 帯が FFT の 1 区切りより狭い(低い音): いちばん近い区切りの強さを、帯の幅の分だけとる
                val k = ((lo + hi) / 2 / binHz).toInt().coerceIn(1, half)
                p[k] * (hi - lo) / binHz
            }
            out[b] = (10 * log10(max(sum, 1e-14))).toFloat()
        }
        return out
    }

    /** 帯の中心の周波数 */
    fun centerHz(b: Int): Double = kotlin.math.sqrt(edges[b] * edges[b + 1])

    /** 周波数を、帯の並びの中の位置(0〜1)にする */
    fun positionOf(hz: Double): Float = (ln(hz / lowHz) / ln(highHz / lowHz)).toFloat().coerceIn(0f, 1f)

    companion object {
        /** 長さが 2 のべき乗の FFT(その場で書き換える) */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang)
                val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k + len / 2] = re[i + k] - ar
                        im[i + k + len / 2] = im[i + k] - ai
                        re[i + k] += ar
                        im[i + k] += ai
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
