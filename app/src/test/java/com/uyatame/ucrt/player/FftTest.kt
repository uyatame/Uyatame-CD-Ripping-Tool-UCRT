package com.uyatame.ucrt.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Fft(順方向・逆方向)の検証。参照は素朴な DFT と既知の信号の解析解 */
class FftTest {

    /** 素朴な DFT(順方向、X[k] = Σ x[n] e^{-2πi kn/N})。小さい n の参照用 */
    private fun dft(re: FloatArray, im: FloatArray): Pair<FloatArray, FloatArray> {
        val n = re.size
        val or = FloatArray(n)
        val oi = FloatArray(n)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val a = -2 * PI * k * t / n
                val c = cos(a)
                val s = sin(a)
                sr += re[t] * c - im[t] * s
                si += re[t] * s + im[t] * c
            }
            or[k] = sr.toFloat()
            oi[k] = si.toFloat()
        }
        return or to oi
    }

    private fun maxErr(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in a.indices) m = maxOf(m, abs(a[i] - b[i]))
        return m
    }

    @Test
    fun forwardMatchesNaiveDft() {
        val n = 16
        val rnd = Random(42)
        val re = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        val im = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        val (er, ei) = dft(re.copyOf(), im.copyOf())
        Fft(n).run(re, im)
        assertTrue("re err=${maxErr(re, er)}", maxErr(re, er) < 1e-3f)
        assertTrue("im err=${maxErr(im, ei)}", maxErr(im, ei) < 1e-3f)
    }

    @Test
    fun impulseIsFlatAndDcIsSingleBin() {
        val n = 64
        val re = FloatArray(n)
        val im = FloatArray(n)
        re[0] = 1f
        Fft(n).run(re, im)
        for (k in 0 until n) {
            assertEquals(1f, re[k], 1e-4f)
            assertEquals(0f, im[k], 1e-4f)
        }
        re.fill(1f)
        im.fill(0f)
        Fft(n).run(re, im)
        assertEquals(n.toFloat(), re[0], 1e-3f)
        for (k in 1 until n) {
            assertEquals(0f, re[k], 1e-3f)
            assertEquals(0f, im[k], 1e-3f)
        }
    }

    @Test
    fun sinePeaksAtItsBin() {
        val n = 256
        val bin = 5
        val re = FloatArray(n) { sin(2 * PI * bin * it / n).toFloat() }
        val im = FloatArray(n)
        Fft(n).run(re, im)
        // sin は ±bin に虚数として立つ: X[bin] = -i n/2、X[n-bin] = +i n/2
        assertEquals(-n / 2f, im[bin], 1e-2f)
        assertEquals(n / 2f, im[n - bin], 1e-2f)
        assertEquals(0f, re[bin], 1e-2f)
        var others = 0f
        for (k in 0 until n) if (k != bin && k != n - bin) others = maxOf(others, abs(re[k]), abs(im[k]))
        assertTrue("leak=$others", others < 1e-2f)
    }

    @Test
    fun roundTripRestoresSignal() {
        val n = 1024
        val rnd = Random(7)
        val re0 = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        val im0 = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        val re = re0.copyOf()
        val im = im0.copyOf()
        val fft = Fft(n)
        fft.run(re, im)
        fft.runInverse(re, im)
        assertTrue("re err=${maxErr(re, re0)}", maxErr(re, re0) < 1e-3f)
        assertTrue("im err=${maxErr(im, im0)}", maxErr(im, im0) < 1e-3f)
    }

    /** 純正 DSRE の FFT サイズ (2^19) でも往復が float 精度で収まること */
    @Test
    fun roundTripAtDsreSize() {
        val n = 1 shl 19
        val rnd = Random(99)
        val re0 = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        val re = re0.copyOf()
        val im = FloatArray(n)
        val fft = Fft(n)
        fft.run(re, im)
        fft.runInverse(re, im)
        assertTrue("re err=${maxErr(re, re0)}", maxErr(re, re0) < 1e-4f)
        var imMax = 0f
        for (v in im) imMax = maxOf(imMax, abs(v))
        assertTrue("im max=$imMax", imMax < 1e-4f)
    }
}
