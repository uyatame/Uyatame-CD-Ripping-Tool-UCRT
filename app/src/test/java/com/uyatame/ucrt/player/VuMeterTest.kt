package com.uyatame.ucrt.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class VuMeterTest {
    private class FakeOut(override val sampleRate: Int) : PcmOut {
        override var framesWritten = 0L
        override val failed = false
        var head = 0L
        override fun headFrames() = head
        override fun play() {}
        override fun pause() {}
        override fun release() {}
        override fun write(src: ByteBuffer, srcEnc0: Int, stop: () -> Boolean) {}
    }

    /** 左だけ amp の正弦波、右は ampR */
    private fun stereo(rate: Int, frames: Int, ampL: Double, ampR: Double): ByteBuffer {
        val b = ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val s = sin(2 * PI * 1000.0 * i / rate)
            b.putFloat((ampL * s).toFloat()); b.putFloat((ampR * s).toFloat())
        }
        b.flip()
        return b
    }

    private fun sine(n: Int, amp: Double) = FloatArray(n) { (amp * sin(2 * PI * 1000.0 * it / 40000)).toFloat() }

    @After fun off() { SpectrumTap.enabled = false }

    @Test fun needleReadsZeroVuAtReference() {
        // -18 dBFS(最大値)の正弦波は、基準 18 で 0 VU
        val m = VuMeter()
        val a = sine(2048, 10.0.pow(-18 / 20.0))
        var t = 0L
        repeat(120) { t += 16_666_667L; m.step(a, a, 2048, true, t) }
        assertEquals(0f, m.needle[0], 0.3f)
        // 基準を 14 にすると、-4 VU
        m.refDb = 14f
        repeat(120) { t += 16_666_667L; m.step(a, a, 2048, true, t) }
        assertEquals(-4f, m.needle[0], 0.3f)
        // バーは最大値(-18 dBFS)
        assertEquals(-18f, m.peak[0], 0.2f)
    }

    @Test fun vuNeedleIsSmoothAndFallsInSilence() {
        val m = VuMeter()
        val a = sine(2048, 10.0.pow(-18 / 20.0))
        var t = 16_666_667L
        m.step(a, a, 2048, true, t)
        // 1 フレーム目では、まだ振り切っていない(なめらか)
        assertTrue(m.needle[0] < -6f)
        repeat(30) { t += 16_666_667L; m.step(a, a, 2048, true, t) }
        assertTrue("0.5 秒でほぼ 0 VU: ${m.needle[0]}", m.needle[0] > -1f)
        // 音が止まると下がる。ピークのホールドは少し残る
        repeat(60) { t += 16_666_667L; m.step(a, a, 0, false, t) }
        assertTrue(m.needle[0] < -15f)
        assertTrue(m.peak[0] < -30f)
    }

    @Test fun tapKeepsLeftAndRightApart() {
        SpectrumTap.enabled = true
        val out = FakeOut(44100)
        SpectrumTap.submit(out, stereo(44100, 8192, 0.1, 0.025), android.media.AudioFormat.ENCODING_PCM_FLOAT, 8192, 2, 44100, 0)
        out.head = 8192
        val l = FloatArray(2048); val r = FloatArray(2048)
        assertTrue(SpectrumTap.windowStereo(l, r, 2048, 0) > 0)
        val m = VuMeter()
        var t = 0L
        repeat(120) { t += 16_666_667L; m.step(l, r, 2048, true, t) }
        // 左は右より 12 dB 大きい
        assertEquals(12f, m.needle[0] - m.needle[1], 0.5f)
        // 混ぜたもの(スペクトラム)は、左右の平均
        val mono = FloatArray(2048)
        SpectrumTap.window(mono, 0)
        assertEquals((l[1000] + r[1000]) / 2, mono[1000], 1e-6f)
        SpectrumTap.forget(out)
    }
}
