package com.uyatame.ucrt.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** 16 bit へのディザー: 16 bit の値はそのまま、無音は無音、細かい値は平均で正しく、誤差は ±1.5 LSB 以内 */
class DitherTest {
    @Test fun sixteenBitValuesAndSilenceStayExactWithoutDither() {
        for (n in listOf(-32768, -1, 0, 1, 12345, 32767)) assertEquals(n, quantize16(n / 32768f, 1f, null))
        assertEquals(0, quantize16(0f, 1f, Tpdf(1)))
        assertEquals(32767, quantize16(2f, 1f, null))
        assertEquals(-32768, quantize16(-2f, 1f, Tpdf(1)))
    }

    @Test fun ditheredRoundingIsUnbiasedAndSmall() {
        val d = Tpdf(42)
        // 0.3 LSB の値: ディザーなしでは常に 0(誤差 -0.3 LSB)。ディザーありでは平均が 0.3 LSB になる
        val x = 0.3f / 32768f
        val n = 200_000
        var sum = 0.0
        var maxErr = 0f
        for (i in 0 until n) {
            val q = quantize16(x, 1f, d)
            sum += q
            maxErr = maxOf(maxErr, abs(q - 0.3f))
        }
        assertEquals(0, quantize16(x, 1f, null))
        assertEquals(0.3, sum / n, 0.01)
        assertTrue(maxErr <= 1.3f)
    }

    @Test fun keepsASineBelowOneLsb() {
        // 0.4 LSB の正弦波: ディザーなしでは消えてしまう(すべて 0)が、ディザーありでは平均すると形が残る
        val d = Tpdf(7)
        val period = 64
        val acc = DoubleArray(period)
        val reps = 4000
        for (r in 0 until reps) for (i in 0 until period) {
            acc[i] += quantize16((0.4 * sin(2 * PI * i / period)).toFloat() / 32768f, 1f, d).toDouble()
        }
        for (i in 0 until period) assertEquals(0.4 * sin(2 * PI * i / period), acc[i] / reps, 0.06)
        assertTrue((0 until period).all { quantize16((0.4 * sin(2 * PI * it / period)).toFloat() / 32768f, 1f, null) == 0 })
    }
}
