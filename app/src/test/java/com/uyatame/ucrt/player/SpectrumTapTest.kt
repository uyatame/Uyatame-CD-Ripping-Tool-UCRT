package com.uyatame.ucrt.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class SpectrumTapTest {
    /** 再生位置を自由に決められる出力 */
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

    private fun sine(hz: Double, rate: Int, frames: Int, from: Long = 0): ByteBuffer {
        val b = ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (0.5 * sin(2 * PI * hz * (from + i) / rate)).toFloat()
            b.putFloat(v); b.putFloat(v)
        }
        b.flip()
        return b
    }

    @After fun off() { SpectrumTap.enabled = false }

    @Test fun peakBandFollowsFrequency() {
        SpectrumTap.enabled = true
        for (hz in listOf(100.0, 1000.0, 8000.0)) {
            val out = FakeOut(96000)
            val buf = sine(hz, 96000, 16384)
            SpectrumTap.submit(out, buf, android.media.AudioFormat.ENCODING_PCM_FLOAT, 16384, 2, 96000, 0)
            // 書き込んでも位置は動かさない
            assertEquals(0, buf.position())
            out.head = 16384
            val a = SpectrumAnalyzer()
            assertTrue(a.update(0))
            val peak = a.bands.indices.maxByOrNull { a.bands[it] }!!
            val center = 35.0 * Math.pow(20000.0 / 35.0, (peak + 0.5) / a.bands.size)
            assertTrue("$hz Hz -> band $peak ($center Hz)", center / hz in 0.8..1.25)
            // -6 dBFS の正弦波は、いちばん上の近く
            assertTrue(a.bands[peak] > 0.85f)
            SpectrumTap.forget(out)
        }
    }

    @Test fun onlyAudiblePartIsShown() {
        SpectrumTap.enabled = true
        val out = FakeOut(44100)
        SpectrumTap.submit(out, sine(1000.0, 44100, 8192), android.media.AudioFormat.ENCODING_PCM_FLOAT, 8192, 2, 44100, 0)
        val a = SpectrumAnalyzer()
        // まだ鳴っていない(再生位置が 0)ので、何も無い
        out.head = 0
        a.update(0)
        assertTrue(a.bands.all { it == 0f })
        SpectrumTap.forget(out)
    }
}
