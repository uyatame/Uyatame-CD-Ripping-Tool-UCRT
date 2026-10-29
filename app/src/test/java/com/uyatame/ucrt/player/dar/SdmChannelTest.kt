package com.uyatame.ucrt.player.dar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** デルタシグマ変調: 1 サンプルあたりのビット数が整数でないとき(48 kHz 系の音源を 44.1 kHz 系の DSD にするとき)も、元の音になる */
class SdmChannelTest {
    @Test fun fractionalBitsFollowTheInput() {
        val f2 = 11_289_600.0 / 384_000.0 // 29.4
        val rate = 384_000
        val n = rate / 10
        // 1 kHz の正弦波(-6 dB)
        val x = FloatArray(n) { (0.5 * sin(2 * PI * 1000.0 * it / rate)).toFloat() }
        val sdm = SdmChannel(f2)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray((1000 * Math.ceil(f2) / 8 + 16).toInt())
        var i = 0
        while (i < n) {
            val k = minOf(1000, n - i)
            val w = sdm.modulate(x.copyOfRange(i, i + k), k, 1f, buf)
            out.write(buf, 0, w)
            i += k
        }
        val bytes = out.toByteArray()
        // できたビットの数は、サンプル数 × 29.4 になる(端数の 1 バイトまで)
        assertEquals(n * f2 / 8, bytes.size.toDouble(), 1.0)
        // ビットを ±1 にして、元の 1 サンプル分(29.4 ビット)ずつの窓より長い 256 ビットで平均すると、元の波形(×0.5)に近い
        val bits = DoubleArray(bytes.size * 8) { k -> if ((bytes[k / 8].toInt() shr (7 - k % 8)) and 1 == 1) 1.0 else -1.0 }
        val win = 256
        var err = 0.0
        var sig = 0.0
        var k = 10_000
        while (k + win < bits.size - 10_000) {
            var s = 0.0
            for (j in k until k + win) s += bits[j]
            val got = s / win
            // 窓の真ん中の時刻の、元の値(変調の前に 0.5 倍している)
            val t = (k + win / 2.0) / f2
            val want = 0.5 * 0.5 * sin(2 * PI * 1000.0 * t / rate)
            err += (got - want) * (got - want)
            sig += want * want
            k += win
        }
        assertTrue("error ratio ${err / sig}", err / sig < 0.01)
    }

    @Test fun integerBitsStillWork() {
        val sdm = SdmChannel(32.0)
        val x = FloatArray(1000) { 0.25f }
        val buf = ByteArray(1000 * 4 + 8)
        val w = sdm.modulate(x, x.size, 1f, buf)
        assertEquals(4000, w)
        // 直流 0.25 → 変調の前に 0.5 倍して 0.125 → 1 の割合は約 56%
        var ones = 0
        for (b in 0 until w) ones += Integer.bitCount(buf[b].toInt() and 0xFF)
        assertTrue(abs(ones.toDouble() / (w * 8) - 0.5625) < 0.01)
    }
}
