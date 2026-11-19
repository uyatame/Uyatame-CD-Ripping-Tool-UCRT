package com.uyatame.ucrt.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 出力先が受け付けない周波数を下げるとき(384 kHz → 48 kHz など)の変換。
 * 聞こえる帯域の音はそのまま通し、出力のナイキスト周波数より上の音は折り返して戻ってこないように削る
 */
class RationalResamplerDownTest {
    private fun rmsAfter(inRate: Int, outRate: Int, hz: Double): Double {
        val ratio = (inRate + outRate - 1) / outRate
        val r = RationalResampler(inRate, outRate, 2, taps = 24 * ratio.coerceIn(1, 16))
        val frames = inRate / 2
        val input = FloatArray(frames * 2) { i -> (0.5 * sin(2 * PI * hz * (i / 2) / inRate)).toFloat() }
        val out = FloatArray(r.maxOut(frames) * 2)
        val n = r.process(input, frames, out)
        assertTrue("n=$n", kotlin.math.abs(n - frames.toLong() * outRate / inRate) <= 2)
        // 始めのフィルターの遅れの分を除いて、左のチャンネルの実効値
        var s = 0.0
        var c = 0
        for (k in n / 4 until n) { val v = out[k * 2].toDouble(); s += v * v; c++ }
        return sqrt(s / c)
    }

    @Test fun passesAudibleBand() {
        val rms = rmsAfter(384000, 48000, 1000.0)
        // 振幅 0.5 の正弦波の実効値は 0.354
        assertEquals(0.354, rms, 0.02)
    }

    @Test fun removesWhatWouldAlias() {
        // 100 kHz は 48 kHz にすると 4 kHz に折り返す。十分に小さくなっていること(-40 dB 未満)
        val rms = rmsAfter(384000, 48000, 100000.0)
        assertTrue("rms=$rms", rms < 0.0035)
    }

    @Test fun keepsFamilyRatios() {
        assertEquals(0.354, rmsAfter(352800, 44100, 1000.0), 0.02)
        assertEquals(0.354, rmsAfter(384000, 192000, 1000.0), 0.02)
    }
}
