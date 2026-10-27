package com.uyatame.ucrt.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * DCT-IV: u[n] = scale × Σ x[k] cos(π/m (n + 1/2)(k + 1/2))(大きさ m。m/2 の複素 FFT で計算する)
 */
internal class Dct4(private val m: Int, scale: Double = 1.0) {
    private val h = m / 2
    private val preC = FloatArray(h)
    private val preS = FloatArray(h)
    private val postC = FloatArray(h)
    private val postS = FloatArray(h)
    private val fft = Fft(h)
    private val re = FloatArray(h)
    private val im = FloatArray(h)

    init {
        for (k in 0 until h) {
            val a = -PI * (4 * k + 1) / (4.0 * m)
            preC[k] = cos(a).toFloat()
            preS[k] = sin(a).toFloat()
            val b = -PI * k / m
            postC[k] = (cos(b) * scale).toFloat()
            postS[k] = (sin(b) * scale).toFloat()
        }
    }

    /** x[off until off + m] → u[uOff until uOff + m] */
    fun run(x: FloatArray, off: Int, u: FloatArray, uOff: Int) {
        for (k in 0 until h) {
            val xr = x[off + 2 * k]
            val xi = x[off + m - 1 - 2 * k]
            re[k] = xr * preC[k] - xi * preS[k]
            im[k] = xr * preS[k] + xi * preC[k]
        }
        fft.run(re, im)
        for (j in 0 until h) {
            u[uOff + 2 * j] = re[j] * postC[j] - im[j] * postS[j]
            u[uOff + m - 1 - 2 * j] = -(re[j] * postS[j] + im[j] * postC[j])
        }
    }
}

/**
 * 逆 MDCT(出力 n、入力 n/2)。規格の式 x[i] = 2/N Σ X[k] cos(2π/N (i + n0)(k + 1/2))、n0 = N/4 + 1/2。
 * 大きさ n/2 の DCT-IV を広げて作る。scale は全体に掛ける数(規格の 2/N の代わり。Vorbis は 1)
 */
internal class Imdct(private val n: Int, scale: Double = 2.0 / n) {
    private val m = n / 2
    private val dct = Dct4(m, scale)
    private val u = FloatArray(m)

    /** x[off until off+n/2] → y[0 until n] */
    fun run(x: FloatArray, off: Int, y: FloatArray) {
        dct.run(x, off, u, 0)
        // DCT-IV の結果を広げる
        val q = m / 2
        for (i in 0 until q) y[i] = u[q + i]
        for (i in q until 3 * q) y[i] = -u[3 * q - 1 - i]
        for (i in 3 * q until n) y[i] = -u[i - 3 * q]
    }
}

/** 大きさ 2^k の複素 FFT(順方向、その場で) */
internal class Fft(private val n: Int) {
    private val rev = IntArray(n)
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)

    init {
        var bits = 0
        while ((1 shl bits) < n) bits++
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
        for (i in 0 until n / 2) {
            cosT[i] = cos(-2 * PI * i / n).toFloat()
            sinT[i] = sin(-2 * PI * i / n).toFloat()
        }
    }

    fun run(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var t = 0
                for (k in i until i + half) {
                    val wr = cosT[t]
                    val wi = sinT[t]
                    val xr = re[k + half]
                    val xi = im[k + half]
                    val vr = xr * wr - xi * wi
                    val vi = xr * wi + xi * wr
                    re[k + half] = re[k] - vr
                    im[k + half] = im[k] - vi
                    re[k] += vr
                    im[k] += vi
                    t += step
                }
                i += len
            }
            len *= 2
        }
    }
}
