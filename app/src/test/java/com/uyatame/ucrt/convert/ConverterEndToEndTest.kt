package com.uyatame.ucrt.convert

import com.uyatame.ucrt.player.BuiltinCodecs
import com.uyatame.ucrt.player.RandomInput
import com.uyatame.ucrt.player.dsd.DsdToPcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** 変換の全体(内蔵のデコーダーで WAV を読み、編集・周波数の変換・書き出しまで) */
class ConverterEndToEndTest {
    private class Bytes(val b: ByteArray) : RandomInput {
        override val size: Long get() = b.size.toLong()
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int {
            if (pos >= this.b.size) return -1
            val n = minOf(len.toLong(), this.b.size - pos).toInt()
            System.arraycopy(this.b, pos.toInt(), b, off, n)
            return n
        }
    }

    /** 44.1 kHz・16bit・ステレオの WAV(前後 0.5 秒は無音、間は 1 kHz の -12 dB) */
    private fun wav(seconds: Double): ByteArray {
        val n = (44100 * seconds).toInt()
        val data = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val on = i >= 22050 && i < n - 22050
            val v = if (on) (0.25 * 32767 * sin(2 * PI * 1000 * i / 44100.0)).toInt().toShort() else 0
            data.putShort(v); data.putShort(v)
        }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + n * 4); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2); h.putInt(44100); h.putInt(176400); h.putShort(4); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(n * 4)
        return h.array() + data.array()
    }

    private fun run(spec: ConvertSpec, src: ByteArray): ByteArray {
        val conv = Converter(null, {}, { _, _ ->
            val d = com.uyatame.ucrt.player.WavDecoder(Bytes(src))
            d.open()
            com.uyatame.ucrt.player.Decoders.Opened(d, java.io.Closeable {})
        })
        val f = File.createTempFile("e2e", ".out")
        try {
            RandomAccessFile(f, "rw").use { raf ->
                conv.convert(null, "x.wav", spec, SeekOut(raf.channel), null, { _, _ -> }) { false }
            }
            return f.readBytes()
        } finally {
            f.delete()
        }
    }

    @Test fun wavToFlac96WithTrimAndNormalize() {
        val src = wav(2.0)
        val out = run(ConvertSpec(mode = 1, format = OutFormat.FLAC, pcmRate = 96000, bits = 24, normalize = true, normalizeDb = -1f, trimStart = true, trimEnd = true), src)
        val d = BuiltinCodecs.create("flac", Bytes(out))
        d.open()
        assertEquals(96000, d.sampleRate)
        // 1 秒(+ 前後 10 ms の余裕)
        val want = (44100 + 2 * 441).toLong() * 96000 / 44100
        // 無音とみなす境目(正弦波が 0 を横切るところ)の分だけ前後する
        assertTrue("len ${d.totalSamples} want $want", abs(d.totalSamples - want) <= 8)
        var peak = 0
        while (d.decodeNext()) for (i in 0 until d.blockSize) peak = maxOf(peak, abs(d.samples[0][i]))
        val db = 20 * log10(peak / 8388608.0)
        assertTrue("peak $db", db > -1.15 && db <= -0.99)
    }

    @Test fun wavToDsf64() {
        val src = wav(2.0)
        val out = run(ConvertSpec(mode = 1, format = OutFormat.DSF, dsdRate = 2822400, fadeIn = true, fadeInSec = 0.2f), src)
        assertEquals("DSD ", String(out, 0, 4))
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val samples = bb.getLong(28 + 36)
        assertEquals(88200L * 64, samples)
        // 中ほど(1 秒あたり)の左チャンネルを読み、PCM に戻して 1 kHz の大きさを見る
        val blocks = (out.size - 92) / 8192
        assertTrue(blocks > 10)
        val bytes = ByteArray(4096 * 8)
        val startBlock = (2822400 / 8) / 4096
        for (b in 0 until 8) for (j in 0 until 4096) bytes[b * 4096 + j] = DsfWriter.REV[out[92 + (startBlock + b) * 8192 + j].toInt() and 0xFF]
        val conv = DsdToPcm(2822400, 1)
        val pcm = FloatArray(conv.maxOut(bytes.size))
        val k = conv.process(arrayOf(bytes), bytes.size, pcm)
        val rate = conv.outRate
        var c = 0.0; var s = 0.0
        val st = 2000
        val len = k - st
        for (i in st until k) { c += pcm[i] * Math.cos(2 * PI * 1000 * i / rate); s += pcm[i] * Math.sin(2 * PI * 1000 * i / rate) }
        val amp = 2 * sqrt(c * c + s * s) / len
        // PCM の -12 dB(0.25)→ DSD の 0 dB の 0.25 倍 → 再生用の DSD → PCM では 0.125
        assertEquals(0.125, amp, 0.01)
    }

    @Test fun dsreFlac() {
        val src = wav(1.5)
        val out = run(ConvertSpec(mode = 0, format = OutFormat.FLAC, bits = 24, dsre = DsreParams("x", 8, 0.5f, 96000, 1f, 1.1f)), src)
        val d = BuiltinCodecs.create("flac", Bytes(out))
        d.open()
        assertEquals(96000, d.sampleRate)
        assertEquals(66150L * 96000 / 44100, d.totalSamples)
    }
}
