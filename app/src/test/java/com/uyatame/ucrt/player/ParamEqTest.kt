package com.uyatame.ucrt.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** イコライザーを帯域ごとにまとめて計算しても、1 サンプルずつ計算したときと結果がビット単位で同じになる */
class ParamEqTest {
    /** 以前の、1 サンプルずつ全帯域を通す計算 */
    private class Reference(val c: Array<DoubleArray>, ch: Int) {
        val s1 = Array(ch) { DoubleArray(c.size) }
        val s2 = Array(ch) { DoubleArray(c.size) }
        fun process(buf: FloatArray, samples: Int, channels: Int) {
            var i = 0
            while (i < samples) {
                for (chn in 0 until channels) {
                    var x = buf[i + chn].toDouble()
                    for (k in c.indices) {
                        val k5 = c[k]
                        val y = k5[0] * x + s1[chn][k]
                        s1[chn][k] = k5[1] * x - k5[3] * y + s2[chn][k]
                        s2[chn][k] = k5[2] * x - k5[4] * y
                        x = y
                    }
                    buf[i + chn] = x.toFloat()
                }
                i += channels
            }
        }
    }

    @Test fun sameAsSampleBySample() {
        val bands = listOf(
            EqBand(EqType.Peak, 62f, 6f, 1.41f), EqBand(EqType.LowShelf, 120f, -3f, 0.71f),
            EqBand(EqType.Peak, 1000f, -4.5f, 2f), EqBand(EqType.HighShelf, 8000f, 5f, 0.71f),
            EqBand(EqType.LowCut, 30f, 0f, 0.71f), EqBand(EqType.HighCut, 18000f, 0f, 0.71f),
        )
        val fs = 96000
        val coefs = bands.mapNotNull { ParamEq.coefs(it, fs.toDouble()) }.toTypedArray()
        val ch = 2
        val eq = ParamEqualizer(fs, ch)
        eq.update(bands)
        val ref = Reference(coefs, ch)
        val rnd = java.util.Random(7)
        // ブロックの大きさを変えながら何回か通し、状態の引き継ぎも同じになるかを見る
        for (block in intArrayOf(1, 7, 512, 4096, 33)) {
            val a = FloatArray(block * ch) { (rnd.nextGaussian() * 0.3).toFloat() }
            val b = a.copyOf()
            eq.process(a, a.size)
            ref.process(b, b.size, ch)
            for (i in a.indices) assertEquals("block $block sample $i", b[i].toRawBits(), a[i].toRawBits())
        }
    }

    @Test fun curveSameAsPointByPoint() {
        val bands = listOf(EqBand(EqType.Peak, 1000f, 4f, 1.41f), EqBand(EqType.HighShelf, 6000f, -3f, 0.71f), EqBand(EqType.LowCut, 40f, 0f, 0.71f))
        val freqs = DoubleArray(97) { 20.0 * Math.pow(1000.0, it / 96.0) }
        val c = ParamEq.responseCurve(bands, freqs)
        for (i in freqs.indices) assertEquals(ParamEq.responseDb(bands, freqs[i]).toRawBits(), c[i].toRawBits())
    }
}
