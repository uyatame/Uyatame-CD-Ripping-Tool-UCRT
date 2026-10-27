package com.uyatame.ucrt.player.mp3

import com.uyatame.ucrt.player.RandomInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自前の MP3 デコーダー(層 I・II・III)が、正しい長さの、元の音(正弦波)に近い音を出すかを確かめる。
 *
 * テスト用のファイル(src/test/resources/audio)は ffmpeg で作った正弦波:
 * - sine44.mp3: 44.1 kHz ステレオ・1.2 秒(左 440 Hz・右 1000 Hz)、LAME 96 kbps。LAME の情報のフレームで前後の無音を除く
 * - sine22mono.mp3: 22.05 kHz モノラル・1.0 秒(300 Hz)、LAME の可変ビットレート
 * - sine48.mp2: 48 kHz ステレオ・0.8 秒(700 Hz)、層 II(情報のフレームが無いので、フレームの数 × 1152 サンプル)
 * サンプル数は ffmpeg でデコードしたときと同じ
 */
class Mp3PcmDecoderTest {
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

    /** 全部デコードする([チャンネル][サンプル]) */
    private fun decodeAll(d: Mp3PcmDecoder, frames: Int): Array<IntArray> {
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

    /** 周波数 hz の正弦波に合わせたときの、残り(雑音)に対する信号の比(dB)。前後の 10% は除く */
    private fun snr(x: IntArray, rate: Int, hz: Double): Double {
        val a = x.size / 10
        val b = x.size - a
        var ss = 0.0; var sc = 0.0; var cc = 0.0; var xs = 0.0; var xc = 0.0
        for (i in a until b) {
            val w = 2 * Math.PI * hz * i / rate
            val s = Math.sin(w); val c = Math.cos(w)
            ss += s * s; sc += s * c; cc += c * c; xs += x[i] * s; xc += x[i] * c
        }
        // 最小二乗で sin と cos の重みを求める
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

    private fun check(name: String, rate: Int, channels: Int, frames: Int, minSnr: Double, vararg hz: Double) {
        val bytes = fixture(name)
        val d = Mp3PcmDecoder(Bytes(bytes)).also { it.open() }
        assertEquals(rate, d.sampleRate)
        assertEquals(channels, d.channels)
        assertEquals("長さ", frames.toLong(), d.totalSamples)
        val full = decodeAll(d, frames)
        for (c in 0 until channels) {
            val v = snr(full[c], rate, hz[c])
            assertTrue("元の音に近い (ch$c: ${"%.1f".format(v)} dB)", v > minSnr)
        }
        // 途中から再生する: 移った先のブロックに、全体をデコードしたときと同じ値が出る
        for (target in longArrayOf(1, 1500, frames / 3L, frames / 2L, frames - 10L)) {
            val s = Mp3PcmDecoder(Bytes(bytes)).also { it.open() }
            s.seekSample(target)
            var found = false
            while (s.decodeNext()) {
                if (target < s.frameSample + s.blockSize) {
                    assertTrue("移った先のブロックに $target が含まれる", target >= s.frameSample)
                    val i = (target - s.frameSample).toInt()
                    for (c in 0 until channels) assertEquals("移動後の値 ($target, ch$c)", full[c][target.toInt()], s.samples[c][i])
                    found = true
                    break
                }
            }
            assertTrue("移動後にデコードできる ($target)", found)
        }
    }

    // 層 II(128 kbps)は層 III より雑音が多い(ffmpeg でデコードしても同じ)
    @Test fun layer3Stereo() = check("sine44.mp3", 44100, 2, 52920, 30.0, 440.0, 1000.0)
    @Test fun layer3MonoVbr() = check("sine22mono.mp3", 22050, 1, 22050, 30.0, 300.0)
    @Test fun layer2() = check("sine48.mp2", 48000, 2, 39168, 20.0, 700.0, 700.0)

    /** 壊れたデータ・空のデータで落ちない(音が無いことを例外で知らせる) */
    @Test fun garbage() {
        val r = java.util.Random(1)
        for (n in intArrayOf(0, 10, 5000)) {
            val b = ByteArray(n).also { r.nextBytes(it) }
            runCatching {
                val d = Mp3PcmDecoder(Bytes(b))
                d.open()
                var k = 0
                while (d.decodeNext() && k++ < 10000) Unit
            }.exceptionOrNull()?.let { assertTrue("$it", it is java.io.IOException) }
        }
    }
}
