package com.uyatame.ucrt.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** ラウドネスの測り方(EBU R128)。基準の信号で、決まった値になるかを確かめる */
class LoudnessMeterTest {
    private fun sine(rate: Int, ch: Int, freq: Double, amp: Double, seconds: Double): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n * ch) { i -> (amp * sin(2 * PI * freq * (i / ch) / rate)).toFloat() }
    }

    @Test fun stereoSine1kAtMinus20dBFSIsMinus20Lufs() {
        // EBU Tech 3341: 1 kHz の正弦波を両チャンネルに -23 dBFS で入れると -23 LUFS。-20 dBFS なら -20 LUFS
        for (rate in intArrayOf(44100, 48000, 96000)) {
            val m = LoudnessMeter(rate, 2)
            val buf = sine(rate, 2, 1000.0, 0.1, 10.0)
            // 小分けにして渡しても同じになるように
            var off = 0
            while (off < buf.size) {
                val frames = minOf(4096, (buf.size - off) / 2)
                m.feed(buf.copyOfRange(off, off + frames * 2), frames)
                off += frames * 2
            }
            val l = LoudnessMeter.integrated(m.blocks)!!
            assertEquals("rate $rate", -20.0, l, 0.1)
            assertEquals(2.0f, LoudnessMeter.gainDb(l), 0.1f)
            assertEquals(0.1f, m.peak, 1e-3f)
        }
    }

    @Test fun silenceHasNoLoudness() {
        val m = LoudnessMeter(48000, 2)
        m.feed(FloatArray(48000 * 2 * 3), 48000 * 3)
        assertNull(LoudnessMeter.integrated(m.blocks))
    }

    @Test fun quietPartsAreGatedOut() {
        // 大きな音の後ろに長い無音があっても、無音は数えない(-70 LUFS より下は除く)
        val rate = 48000
        val m = LoudnessMeter(rate, 2)
        val loud = sine(rate, 2, 1000.0, 0.1, 5.0)
        m.feed(loud, loud.size / 2)
        m.feed(FloatArray(rate * 2 * 20), rate * 20)
        assertEquals(-20.0, LoudnessMeter.integrated(m.blocks)!!, 0.15)
    }
}
