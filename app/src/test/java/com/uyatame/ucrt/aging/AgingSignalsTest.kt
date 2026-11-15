package com.uyatame.ucrt.aging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** エージングの音: 音色ごとの傾き・強さ・直流・帯域・つなぎ目を、作った音を実際に分析して確かめる */
class AgingSignalsTest {
    private fun render(sig: AgingSignal, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val block = FloatArray(4096 * 2)
        var done = 0
        while (done < frames) {
            val m = minOf(4096, frames - done)
            sig.fill(block, m)
            System.arraycopy(block, 0, out, done * 2, m * 2)
            done += m
        }
        return out
    }

    private fun rmsDb(x: FloatArray, from: Int = 0): Double {
        var s = 0.0
        for (i in from until x.size) s += x[i] * x[i].toDouble()
        return 20 * log10(sqrt(s / (x.size - from)))
    }

    /** 分析器で何回も測って平均した、帯ごとの強さ(dB) */
    private fun averagedBands(sig: AgingSignal, times: Int = 40): Pair<AgingAnalyzer, DoubleArray> {
        val a = AgingAnalyzer(sig.rate)
        val buf = FloatArray(a.size * 2)
        // 最初は捨てる(フィルターが落ち着くまで)
        sig.fill(buf, a.size)
        val sum = DoubleArray(a.bands)
        repeat(times) {
            sig.fill(buf, a.size)
            a.push(buf, a.size)
            val b = a.bandsDb()!!
            for (i in sum.indices) sum[i] += Math.pow(10.0, b[i] / 10.0)
        }
        return a to DoubleArray(a.bands) { 10 * log10(sum[it] / times) }
    }

    /** 周波数の近い帯をいくつか平均した強さ(1 つの帯だけだとばらつくので) */
    private fun levelNear(a: AgingAnalyzer, db: DoubleArray, hz: Double): Double {
        val i = (0 until a.bands).minBy { abs(log10(a.centerHz(it) / hz)) }
        val r = (i - 2..i + 2).filter { it in 0 until a.bands }
        return 10 * log10(r.sumOf { Math.pow(10.0, db[it] / 10.0) } / r.size)
    }

    private fun slopePer4Octaves(sig: AgingSignal): Double {
        val (a, db) = averagedBands(sig)
        return levelNear(a, db, 4000.0) - levelNear(a, db, 250.0)
    }

    @Test fun pinkIsFlatPerBand() {
        // 帯ごとの合計で見ると、ピンクノイズは平ら(4 オクターブ離れても同じ高さ)
        assertEquals(0.0, slopePer4Octaves(PinkNoise(48000)), 1.5)
    }

    @Test fun whiteRisesThreeDbPerOctave() {
        assertEquals(12.0, slopePer4Octaves(WhiteNoise(48000)), 1.5)
    }

    @Test fun brownFallsThreeDbPerOctave() {
        // 強さ(1 Hz あたり)は -6 dB/oct、帯ごとの合計では -3 dB/oct
        assertEquals(-12.0, slopePer4Octaves(BrownNoise(48000)), 2.0)
    }

    @Test fun levelsAreAboutMinus20DbfsWithoutDcOrClipping() {
        for (sig in listOf(WhiteNoise(48000), PinkNoise(48000), BrownNoise(48000), Sweep(48000), UcrtAgingSignal(48000))) {
            val x = render(sig, 48000 * 20)
            val name = sig.javaClass.simpleName
            // UCRT は区間で強弱があるので広めに見る
            val tol = if (sig is UcrtAgingSignal) 4.0 else 1.0
            assertEquals(name, -20.0, rmsDb(x, 48000 * 2), tol)
            val mean = x.average()
            assertTrue("$name DC $mean", abs(mean) < 0.003)
            assertTrue("$name peak", x.all { abs(it) <= AgingLevels.CLIP })
        }
    }

    @Test fun hiResRatesCarrySoundAbove20k() {
        // 192 kHz では、可聴域より上(30〜80 kHz)にも音がある。ピンクは上でも大きく落ちない
        for (sig in listOf<AgingSignal>(WhiteNoise(192000), PinkNoise(192000))) {
            val (a, db) = averagedBands(sig, 8)
            val audible = levelNear(a, db, 2000.0)
            val ultra = levelNear(a, db, 50000.0)
            assertTrue("${sig.javaClass.simpleName} ${ultra - audible}", ultra - audible > -6.0)
        }
    }

    @Test fun sweepCoversRangeAndStaysContinuous() {
        val s = Sweep(192000, seconds = 2.0)
        assertEquals(20.0, s.hzAt(0), 0.01)
        assertEquals(80000.0, s.hzAt(192000L), 1.0)
        assertEquals(21600.0, Sweep(48000).highHz, 0.1)
        // 1 回り(上がって下がる)の中で、隣の値との差が、その周波数の正弦波として出せる差を超えない(途切れ・飛びがない)
        val x = render(s, 192000 * 2)
        val amp = AgingLevels.RMS * sqrt(2f)
        for (i in 1 until 192000 * 2) {
            val hz = s.hzAt(i.toLong())
            val maxStep = amp * 2 * PI * hz / 192000 * 1.05 + 1e-6
            assertTrue("jump at $i", abs(x[2 * i] - x[2 * (i - 1)]) <= maxStep)
        }
    }

    @Test fun ucrtSignalWrapsWithoutAJumpAndFollowsItsParts() {
        val u = UcrtAgingSignal(48000)
        val end = u.weights(599.999)
        val start = u.weights(0.0)
        for (i in 0..3) assertEquals("weight $i", end[i], start[i], 0.01f)
        assertEquals(UcrtAgingSignal.Part.PINK, u.partAt(10.0))
        assertEquals(UcrtAgingSignal.Part.SWEEP, u.partAt(250.0))
        assertEquals(UcrtAgingSignal.Part.BROWN, u.partAt(300.0))
        assertEquals(UcrtAgingSignal.Part.WIDE, u.partAt(400.0))
        assertEquals(UcrtAgingSignal.Part.QUIET, u.partAt(590.0))
        // 入れ替わりの途中でも、重みの合計が大きく落ち込まない(無音にならない)
        val mid = u.weights(240.0)
        assertTrue(mid.sum() > 0.4f)
    }

    @Test fun analyzerMeasuresASineAtItsLevel() {
        val rate = 48000
        val a = AgingAnalyzer(rate)
        val amp = 0.5f
        val x = FloatArray(a.size * 2) { i -> (amp * sin(2 * PI * 1000.0 * (i / 2) / rate)).toFloat() }
        a.push(x, a.size)
        val b = a.bandsDb()!!
        val peak = b.max()
        // 正弦波の強さ(実効値の 2 乗)= amp² / 2
        assertEquals(20 * log10(amp / sqrt(2.0)), peak.toDouble(), 1.0)
        val i = b.indexOfFirst { it == peak }
        assertTrue(1000.0 in a.edges[i] * 0.8..a.edges[i + 1] * 1.25)
    }
}
