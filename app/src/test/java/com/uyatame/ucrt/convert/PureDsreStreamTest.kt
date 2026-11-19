package com.uyatame.ucrt.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 純正 DSRE チャンク版 (製品経路) の検証。
 * - 書き込み分割不変性: 1000 フレーム刻みで process しても一括とビット単位で同一になること
 * - 全長版との一致: 窓 1 つ分より内側の領域で相対 RMS 差 −40 dB 以下 (設計書 5.)
 */
class PureDsreStreamTest {

    /** 決定的なテスト信号 (トーン + ノイズ、ステレオ) を作る */
    private fun signal(frames: Int, rate: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        val buf = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val t = f.toDouble() / rate
            val l = 0.25 * sin(2 * PI * 440 * t) + 0.12 * sin(2 * PI * 3000 * t) +
                0.05 * sin(2 * PI * 12000 * t) + (rnd.nextFloat() * 2 - 1) * 0.04f
            val r = 0.22 * sin(2 * PI * 554 * t + 1.0) + 0.10 * sin(2 * PI * 2500 * t) +
                (rnd.nextFloat() * 2 - 1) * 0.04f
            buf[f * 2] = l.toFloat()
            buf[f * 2 + 1] = r.toFloat()
        }
        return buf
    }

    /** Converter と同じ使い方 (latency を捨て、最後に flush) で全出力を集める */
    private fun runStream(input: FloatArray, frames: Int, rate: Int, chunk: Int): FloatArray {
        val st = PureDsreStream(rate, 2, PureDsreParams())
        val out = FloatArray(frames * 2)
        var written = 0
        var pos = 0
        var skip = st.latency
        while (pos < frames) {
            val n = minOf(chunk, frames - pos)
            val block = input.copyOfRange(pos * 2, (pos + n) * 2)
            st.process(block, n)
            var off = 0
            var cnt = n
            if (skip > 0) {
                val d = minOf(skip, cnt)
                skip -= d
                off = d
                cnt -= d
            }
            for (f in 0 until cnt) {
                out[(written + f) * 2] = block[(off + f) * 2]
                out[(written + f) * 2 + 1] = block[(off + f) * 2 + 1]
            }
            written += cnt
            pos += n
        }
        val tail = FloatArray(st.latency * 2)
        val k = st.flush(tail)
        for (f in 0 until k) {
            out[(written + f) * 2] = tail[f * 2]
            out[(written + f) * 2 + 1] = tail[f * 2 + 1]
        }
        written += k
        assertEquals("全フレームが出ること", frames, written)
        return out
    }

    @Test
    fun partitionInvariant() {
        val rate = 48000
        val frames = 700_000 // 2 の冪でない長さ (部分本体 + flush を踏む)
        val input = signal(frames, rate, 1234)
        val oneShot = runStream(input, frames, rate, frames)
        val chunked = runStream(input, frames, rate, 1000)
        var maxDiff = 0f
        for (i in input.indices) {
            val d = kotlin.math.abs(oneShot[i] - chunked[i])
            if (d > maxDiff) maxDiff = d
        }
        assertEquals("分割で出力が変わらないこと (maxDiff=$maxDiff)", 0f, maxDiff)
    }

    @Test
    fun matchesWholeInInterior() {
        val rate = 48000
        val frames = 1 shl 21 // 全長版が扱える 2 の冪。窓(FFT_SIZE = 2^19)の 4 つ分にして、両端の窓 1 つ分を除いた内側が残るようにする
        val input = signal(frames, rate, 77)
        val streamed = runStream(input, frames, rate, 65536)
        val wholeBuf = input.copyOf()
        PureDsre(rate, 2, PureDsreParams(), normalizePeak = false).process(wholeBuf, frames)
        val m = PureDsreStream.FFT_SIZE
        var num = 0.0
        var den = 0.0
        for (f in m until frames - m) {
            for (c in 0 until 2) {
                val d = (streamed[f * 2 + c] - wholeBuf[f * 2 + c]).toDouble()
                num += d * d
                den += wholeBuf[f * 2 + c].toDouble() * wholeBuf[f * 2 + c].toDouble()
            }
        }
        val db = 20 * log10(sqrt(num) / sqrt(den))
        assertTrue("interior rel RMS diff $db dB (gate -40 dB)", db <= -40.0)
    }
}
