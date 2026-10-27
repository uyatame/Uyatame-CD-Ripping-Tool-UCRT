package com.uyatame.ucrt.player.vorbis

import com.uyatame.ucrt.player.RandomInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自前の Vorbis デコーダーが、正しい長さの、元の音(正弦波)に近い音を出すかを確かめる。
 *
 * テスト用のファイル(src/test/resources/audio)は ffmpeg(libvorbis)で作った正弦波:
 * - sine44.ogg: 44.1 kHz ステレオ・0.7 秒(左 440 Hz・右 1000 Hz)、品質 4
 * - sine22mono.ogg: 22.05 kHz モノラル・0.6 秒(300 Hz)、品質 2
 * - sine51.ogg: 48 kHz 5.1ch・0.4 秒。WAV の順番(L R C LFE 後L 後R)で 500・600・700・80・900・1100 Hz
 * どれも 1 ページに収まる短いファイルなので、最後のページの granule で末尾を切る(元の長さになる)。
 * (長いファイルでは、ffmpeg のデコーダーとも、出力が ±1 以内で一致することを確かめてある)
 */
class VorbisPcmDecoderTest {
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

    private fun decodeAll(d: VorbisPcmDecoder, frames: Int): Array<IntArray> {
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
        val d = VorbisPcmDecoder(Bytes(bytes)).also { it.open() }
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
            val s = VorbisPcmDecoder(Bytes(bytes)).also { it.open() }
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

    @Test fun stereo() = check("sine44.ogg", 44100, 30870, 30.0, 440.0, 1000.0)
    @Test fun mono() = check("sine22mono.ogg", 22050, 13230, 30.0, 300.0)
    @Test fun surround() = check("sine51.ogg", 48000, 19200, 20.0, 500.0, 600.0, 700.0, 80.0, 900.0, 1100.0)

    /** 壊れたデータで落ちない(読めないときは IOException) */
    @Test fun garbage() {
        val src = fixture("sine44.ogg")
        val r = java.util.Random(3)
        for (it in 0 until 200) {
            val b = src.copyOf()
            repeat(1 + r.nextInt(30)) { b[r.nextInt(b.size)] = r.nextInt(256).toByte() }
            runCatching {
                val d = VorbisPcmDecoder(Bytes(b))
                d.open()
                var k = 0
                while (d.decodeNext() && k++ < 10000) Unit
            }.exceptionOrNull()?.let { assertTrue("$it", it is java.io.IOException) }
        }
    }
}
