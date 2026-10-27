package com.uyatame.ucrt.player.aac

import com.uyatame.ucrt.player.RandomInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自前の AAC デコーダー(AAC-LC)が、正しい長さの、元の音(正弦波)に近い音を出すかを確かめる。
 *
 * テスト用のファイル(src/test/resources/audio)は ffmpeg の AAC エンコーダーで作った正弦波:
 * - sine44.m4a: 44.1 kHz ステレオ・0.7 秒(左 440 Hz・右 1000 Hz)、192 kbps。編集リストで先頭の無音を除く
 * - sine22mono.aac: 22.05 kHz モノラル・0.6 秒(300 Hz)、64 kbps の ADTS。先頭の無音も含めて 14 フレーム(ffmpeg と同じ)
 * - sine51.m4a: 48 kHz 5.1ch・0.4 秒、640 kbps。WAV の順番(L R C LFE 後L 後R)で 500・600・700・80・900・1100 Hz
 * (ffmpeg のデコーダーとも、出力が ±1 以内で一致することを確かめてある。ffmpeg のエンコーダーは純音が苦手なので、
 * ビットレートを高めにしてある)
 */
class AacPcmDecoderTest {
    private class Bytes(val b: ByteArray) : RandomInput {
        override val size: Long get() = b.size.toLong()
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int {
            if (pos >= this.b.size) return -1
            val n = minOf(len, this.b.size - pos.toInt())
            System.arraycopy(this.b, pos.toInt(), b, off, n)
            return n
        }
    }

    private fun fixture(name: String): ByteArray =
        File(requireNotNull(javaClass.getResource("/audio/$name")) { "テスト用のファイルがありません: $name" }.toURI()).readBytes()

    private fun decodeAll(d: AacPcmDecoder, frames: Int): Array<IntArray> {
        val all = Array(d.channels) { IntArray(frames) }
        var pos = 0L
        while (d.decodeNext()) {
            assertEquals("ブロックの位置", pos, d.frameSample)
            for (c in 0 until d.channels) System.arraycopy(d.samples[c], 0, all[c], d.frameSample.toInt(), d.blockSize)
            pos += d.blockSize
        }
        assertEquals("サンプル数", frames.toLong(), pos)
        return all
    }

    /** 周波数 hz の正弦波に合わせたときの、残り(雑音)に対する信号の比(dB)。前後の 15% は除く */
    private fun snr(x: IntArray, rate: Int, hz: Double): Double {
        val a = x.size * 15 / 100
        val b = x.size - a
        var ss = 0.0; var sc = 0.0; var cc = 0.0; var xs = 0.0; var xc = 0.0
        for (i in a until b) {
            val w = 2 * Math.PI * hz * i / rate
            val s = Math.sin(w); val c = Math.cos(w)
            ss += s * s; sc += s * c; cc += c * c; xs += x[i] * s; xc += x[i] * c
        }
        val det = ss * cc - sc * sc
        val p = (xs * cc - xc * sc) / det
        val q = (xc * ss - xs * sc) / det
        var sig = 0.0; var err = 0.0
        for (i in a until b) {
            val w = 2 * Math.PI * hz * i / rate
            val y = p * Math.sin(w) + q * Math.cos(w)
            sig += y * y
            err += (x[i] - y) * (x[i] - y)
        }
        return 10 * Math.log10(sig / err)
    }

    private fun check(name: String, rate: Int, frames: Int, minSnr: Double, vararg hz: Double) {
        val bytes = fixture(name)
        val d = AacPcmDecoder(Bytes(bytes)).also { it.open() }
        assertEquals(rate, d.sampleRate)
        assertEquals(hz.size, d.channels)
        assertEquals("長さ", frames.toLong(), d.totalSamples)
        val full = decodeAll(d, frames)
        for (c in hz.indices) {
            val v = snr(full[c], rate, hz[c])
            assertTrue("元の音に近い (ch$c ${hz[c]} Hz: ${"%.1f".format(v)} dB)", v > minSnr)
        }
        // 途中から再生する: 移った先のブロックに、全体をデコードしたときと同じ値が出る
        for (target in longArrayOf(1, 1500, frames / 3L, frames / 2L, frames - 10L)) {
            val s = AacPcmDecoder(Bytes(bytes)).also { it.open() }
            s.seekSample(target)
            var found = false
            while (s.decodeNext()) {
                if (target < s.frameSample + s.blockSize) {
                    assertTrue("移った先のブロックに $target が含まれる", target >= s.frameSample)
                    val i = (target - s.frameSample).toInt()
                    for (c in hz.indices) assertEquals("移動後の値 ($target, ch$c)", full[c][target.toInt()], s.samples[c][i])
                    found = true
                    break
                }
            }
            assertTrue("移動後にデコードできる ($target)", found)
        }
    }

    @Test fun mp4Stereo() = check("sine44.m4a", 44100, 30870, 30.0, 440.0, 1000.0)
    @Test fun adtsMono() = check("sine22mono.aac", 22050, 14336, 30.0, 300.0)
    @Test fun mp4Surround() = check("sine51.m4a", 48000, 19200, 30.0, 500.0, 600.0, 700.0, 80.0, 900.0, 1100.0)

    /** 周波数 hz の成分の振幅 */
    private fun amp(x: IntArray, rate: Int, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in x.indices) { val w = 2 * Math.PI * hz * i / rate; re += x[i] * Math.cos(w); im += x[i] * Math.sin(w) }
        return 2 * Math.hypot(re, im) / x.size
    }

    /** 周波数 hz より上のエネルギーの、全体に対する割合(dB) */
    private fun highShare(x: IntArray, rate: Int, hz: Double): Double {
        var y = x.map { it.toDouble() }.toDoubleArray()
        val cut = hz / rate
        // 2 次の IIR(バターワース)の高域通過
        val w0 = 2 * Math.PI * cut
        val alpha = Math.sin(w0) / Math.sqrt(2.0)
        val c = Math.cos(w0)
        val b0 = (1 + c) / 2; val b1 = -(1 + c); val b2 = (1 + c) / 2
        val a0 = 1 + alpha; val a1 = -2 * c; val a2 = 1 - alpha
        repeat(2) {
            val o = DoubleArray(y.size)
            for (i in y.indices) {
                o[i] = (b0 * y[i] + b1 * (if (i > 0) y[i - 1] else 0.0) + b2 * (if (i > 1) y[i - 2] else 0.0) -
                    a1 * (if (i > 0) o[i - 1] else 0.0) - a2 * (if (i > 1) o[i - 2] else 0.0)) / a0
            }
            y = o
        }
        val all = x.sumOf { it.toDouble() * it }
        val hi = y.sumOf { it * it }
        return 10 * Math.log10(hi / all)
    }

    /**
     * HE-AAC(SBR): 中の AAC は 22.05 kHz だが、44.1 kHz で出し、中の AAC には無い 11 kHz より上の音(SBR が作る)がある。
     * テスト用のファイル he_aac.m4a は、fdk-aac で作った 0.6 秒のステレオ(左 440 Hz + 14 kHz、右 1000 Hz + 15 kHz)、48 kbps。
     * ffmpeg のデコーダーとも、出力が ±6 以内で一致することを確かめてある
     */
    @Test fun heAac() {
        val d = AacPcmDecoder(Bytes(fixture("he_aac.m4a"))).also { it.open() }
        assertEquals(44100, d.sampleRate)
        assertEquals(2, d.channels)
        assertEquals(32768L, d.totalSamples)
        val x = decodeAll(d, 32768)
        val mid = x[0].copyOfRange(8192, 24576)
        assertTrue("左の 440 Hz", Math.abs(amp(mid, 44100, 440.0) / 32768 - 0.2) < 0.05)
        val hs = highShare(mid, 44100, 12000.0)
        assertTrue("SBR が作った高い音 (${"%.1f".format(hs)} dB)", hs > -25)
    }

    /**
     * HE-AAC v2(SBR + PS): モノラルで送ったものから、左右で違う音を作る。
     * テスト用のファイル he_aac_v2.aac は、fdk-aac で作った 0.6 秒のステレオ(左 440 Hz、右 1000 Hz)、24 kbps の ADTS。
     * ffmpeg のデコーダーとも、出力が ±1 以内で一致することを確かめてある
     */
    @Test fun heAacV2() {
        val d = AacPcmDecoder(Bytes(fixture("he_aac_v2.aac"))).also { it.open() }
        assertEquals(44100, d.sampleRate)
        assertEquals(2, d.channels)
        assertEquals(34816L, d.totalSamples)
        val x = decodeAll(d, 34816)
        val l = x[0].copyOfRange(10240, 26624)
        val r = x[1].copyOfRange(10240, 26624)
        assertTrue("左は 440 Hz が主", amp(l, 44100, 440.0) > 3 * amp(l, 44100, 1000.0))
        assertTrue("右は 1000 Hz が主", amp(r, 44100, 1000.0) > 3 * amp(r, 44100, 440.0))
    }

    /** AudioSpecificConfig: 明示された SBR(HE-AAC)と PS(HE-AAC v2)を読み分ける */
    @Test fun config() {
        // AAC-LC 44.1 kHz ステレオ
        AudioSpecificConfig.parse(byteArrayOf(0x12, 0x10)).let {
            assertEquals(2, it.objectType); assertEquals(44100, it.sampleRate); assertEquals(2, it.channels); assertEquals(null, it.sbr)
        }
        // HE-AAC(SBR): 中は 24 kHz ステレオ、SBR のあとは 48 kHz
        AudioSpecificConfig.parse(byteArrayOf(0x2B, 0x11, 0x88.toByte(), 0x00)).let {
            assertEquals(2, it.objectType); assertEquals(24000, it.sampleRate); assertEquals(true, it.sbr); assertEquals(48000, it.extSampleRate)
        }
        // HE-AAC v2(SBR + PS): 中は 24 kHz モノラル
        AudioSpecificConfig.parse(byteArrayOf(0xEB.toByte(), 0x09, 0x88.toByte(), 0x00)).let {
            assertEquals(true, it.sbr); assertEquals(true, it.ps); assertEquals(1, it.channels); assertEquals(48000, it.extSampleRate)
        }
        // 後ろに付ける形の SBR の知らせ(LC 22.05 kHz + 0x2b7 + SBR 44.1 kHz)
        AudioSpecificConfig.parse(byteArrayOf(0x13, 0x88.toByte(), 0x56, 0xE5.toByte(), 0xA0.toByte())).let {
            assertEquals(22050, it.sampleRate); assertEquals(true, it.sbr); assertEquals(44100, it.extSampleRate)
        }
    }

    /** 壊れたデータで落ちない(読めないときは IOException) */
    @Test fun garbage() {
        val src = fixture("sine44.m4a")
        val r = java.util.Random(3)
        for (it in 0 until 200) {
            val b = src.copyOf()
            repeat(1 + r.nextInt(30)) { b[r.nextInt(b.size)] = r.nextInt(256).toByte() }
            runCatching {
                val d = AacPcmDecoder(Bytes(b))
                d.open()
                var k = 0
                while (d.decodeNext() && k++ < 10000) Unit
            }.exceptionOrNull()?.let { assertTrue("$it", it is java.io.IOException) }
        }
    }
}
