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

class ConvertTest {
    private class Bytes(val b: ByteArray) : RandomInput {
        override val size: Long get() = b.size.toLong()
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int {
            if (pos >= this.b.size) return -1
            val n = minOf(len.toLong(), this.b.size - pos).toInt()
            System.arraycopy(this.b, pos.toInt(), b, off, n)
            return n
        }
    }

    private fun write(block: (SeekOut) -> Unit): ByteArray {
        val f = File.createTempFile("conv", ".bin")
        try {
            RandomAccessFile(f, "rw").use { raf ->
                val out = SeekOut(raf.channel)
                block(out)
                out.flush()
            }
            return f.readBytes()
        } finally {
            f.delete()
        }
    }

    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun le64(b: ByteArray, o: Int) = (le32(b, o).toLong() and 0xFFFFFFFFL) or (le32(b, o + 4).toLong() shl 32)

    /** 24bit・96 kHz・ステレオの FLAC を書いて、アプリ内蔵のデコーダーで読み戻すと、ディザーの分だけの違いで戻る */
    @Test fun flac24RoundTrip() {
        val rate = 96000
        val n = 10000
        val buf = FloatArray(n * 2) { i ->
            val f = i / 2
            (if (i % 2 == 0) 0.7 * sin(2 * PI * 1000 * f / rate) else 0.3 * sin(2 * PI * 7000 * f / rate) + 0.0001 * (f % 7)).toFloat()
        }
        val bytes = write { out ->
            val w = FlacWriter(out, rate, 2, 24, null)
            w.write(buf, 3000); w.write(buf.copyOfRange(6000, n * 2), n - 3000)
            w.finish()
        }
        val d = BuiltinCodecs.create("flac", Bytes(bytes))
        d.open()
        assertEquals(rate, d.sampleRate)
        assertEquals(2, d.channels)
        assertEquals(24, d.bitsPerSample)
        assertEquals(n.toLong(), d.totalSamples)
        var got = 0
        var maxErr = 0.0
        while (d.decodeNext()) {
            for (i in 0 until d.blockSize) for (c in 0..1) {
                val v = d.samples[c][i] / 8388608.0
                maxErr = maxOf(maxErr, abs(v - buf[(got + i) * 2 + c]))
            }
            got += d.blockSize
        }
        assertEquals(n, got)
        // TPDF ディザー(±1 LSB)と丸めの分だけ
        assertTrue("err $maxErr", maxErr < 2.5 / 8388608)
    }

    @Test fun flac16MonoRoundTrip() {
        val n = 5000
        val buf = FloatArray(n) { (0.5 * sin(2 * PI * 440 * it / 44100.0)).toFloat() }
        val bytes = write { out -> FlacWriter(out, 44100, 1, 16, null).apply { write(buf, n); finish() } }
        val d = BuiltinCodecs.create("flac", Bytes(bytes))
        d.open()
        assertEquals(1, d.channels)
        var got = 0
        while (d.decodeNext()) {
            for (i in 0 until d.blockSize) assertTrue(abs(d.samples[0][i] / 32768.0 - buf[got + i]) < 2.5 / 32768)
            got += d.blockSize
        }
        assertEquals(n, got)
    }

    @Test fun wavHeader() {
        val bytes = write { out -> WavWriter(out, 192000, 2, 24, null).apply { write(FloatArray(200), 100); finish() } }
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(bytes.size - 8, le32(bytes, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        val fmt = 12 + 8 + 28
        assertEquals("fmt ", String(bytes, fmt, 4))
        assertEquals(0xFFFE, le32(bytes, fmt + 8) and 0xFFFF)
        assertEquals(192000, le32(bytes, fmt + 12))
        val data = bytes.size - 600 - 8
        assertEquals("data", String(bytes, data, 4))
        assertEquals(600, le32(bytes, data + 4))
    }

    /** 7 次の変調器で DSD64 にした 1bit の列を、そのまま FFT して可聴帯域(20 kHz まで)の SN 比を測る */
    @Test fun modulatorSnr() {
        val dsd = 2822400
        val r1 = 352800
        val f2 = dsd / r1
        val len = 1 shl 20
        val n = len / f2 + 64
        // FFT のちょうどの周波数(1 kHz 付近)にして、窓の漏れが測定を邪魔しないようにする
        val k0 = Math.round(1000.0 * len / dsd).toInt()
        val f = k0.toDouble() * dsd / len
        val x = FloatArray(n) { (0.5 * 0.5 * sin(2 * PI * f * it / r1)).toFloat() }
        val m = DsdModulator(dsd, f2)
        val bits = ByteArray(n * f2 / 8 + 2)
        val nb = m.modulate(x, n, bits)
        assertEquals(n * f2 / 8, nb)
        assertEquals(0, m.resets)
        val re = DoubleArray(len); val im = DoubleArray(len)
        for (i in 0 until len) {
            val b = (bits[i / 8].toInt() shr (7 - i % 8)) and 1
            re[i] = (if (b == 1) 1.0 else -1.0) * (0.5 - 0.5 * Math.cos(2 * PI * i / len))
        }
        fft(re, im)
        val p = DoubleArray(len / 2) { re[it] * re[it] + im[it] * im[it] }
        val sig = (k0 - 6..k0 + 6).sumOf { p[it] }
        val kmax = (20000.0 * len / dsd).toInt()
        val noise = (8..kmax).filter { it !in k0 - 6..k0 + 6 }.sumOf { p[it] }
        val snr = 10 * log10(sig / noise)
        // -6 dB(DSD の 0 dB の半分)の正弦波で 120 dB 以上(再生用の 5 次の変調器は 100 dB 前後)
        assertTrue("snr $snr", snr > 120)
        // 大きさ: ±1 の列で 0.25
        val wsum = len * 0.5
        assertEquals(0.25, 2 * sqrt(p[k0]) / wsum, 0.02)
    }

    /** 変換用の高音質の DSD → PCM は、DSD の高域の雑音を可聴帯域にほとんど折り返さない(再生用より 25 dB 以上よい) */
    @Test fun hqDsdToPcmSnr() {
        val dsd = 2822400
        val r1 = 352800
        val len = 16384
        val rate = 176400
        val k0 = Math.round(1000.0 * len / rate).toInt()
        val f = k0.toDouble() * rate / len
        val n = r1
        val x = FloatArray(n) { (0.5 * 0.5 * sin(2 * PI * f * it / r1)).toFloat() }
        val m = DsdModulator(dsd, dsd / r1)
        val bits = ByteArray(n * 8 / 8 + 2)
        val nb = m.modulate(x, n, bits)
        fun snr(hq: Boolean): Double {
            val conv = DsdToPcm(dsd, 1, hq)
            val pcm = FloatArray(conv.maxOut(nb))
            val k = conv.process(arrayOf(bits), nb, pcm)
            val st = k - len - 200
            val re = DoubleArray(len); val im = DoubleArray(len)
            for (i in 0 until len) re[i] = pcm[st + i] * (0.5 - 0.5 * Math.cos(2 * PI * i / len))
            fft(re, im)
            val p = DoubleArray(len / 2) { re[it] * re[it] + im[it] * im[it] }
            val sig = (k0 - 3..k0 + 3).sumOf { p[it] }
            val noise = (2..(20000 * len / rate)).filter { it !in k0 - 3..k0 + 3 }.sumOf { p[it] }
            return 10 * log10(sig / noise)
        }
        val lo = snr(false)
        val hi = snr(true)
        assertTrue("hq $hi / normal $lo", hi > 110 && hi > lo + 15)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            for (base in 0 until n step len) for (k in 0 until len / 2) {
                val a = -2 * PI * k / len
                val wr = Math.cos(a); val wi = Math.sin(a)
                val p = base + k; val q = p + len / 2
                val vr = re[q] * wr - im[q] * wi; val vi = re[q] * wi + im[q] * wr
                re[q] = re[p] - vr; im[q] = im[p] - vi; re[p] += vr; im[p] += vi
            }
            len = len shl 1
        }
    }

    @Test fun resampleKeepsTimingAndLength() {
        for ((a, b) in listOf(44100 to 48000, 48000 to 44100, 44100 to 352800, 96000 to 44100, 176400 to 352800)) {
            val n = a / 10
            val x = FloatArray(n * 2) { i -> if (i / 2 == n / 2) 1f else 0f }
            val r = Resample(a, b, 2)
            val out = FloatArray(r.maxOut(n) * 2 + 16)
            var got = r.process(x, n, out)
            val all = ArrayList<Float>()
            for (i in 0 until got) all.add(out[i * 2])
            val tail = FloatArray(r.maxOut(4096).coerceAtLeast(4096) * 2)
            while (true) {
                val k = r.finish(tail)
                if (k <= 0) break
                for (i in 0 until k) all.add(tail[i * 2])
                got += k
            }
            assertEquals("$a->$b length", n.toLong() * b / a, got.toLong())
            val peak = all.indices.maxByOrNull { abs(all[it]) }!!
            val want = (n / 2).toLong() * b / a
            assertTrue("$a->$b peak at $peak, want $want", abs(peak - want) <= 1)
        }
    }

    @Test fun limiterNeverExceedsCeiling() {
        val rate = 48000
        val n = 20000
        val x = FloatArray(n * 2) { i -> (1.6 * sin(2 * PI * 3000 * (i / 2) / rate) * (if (i / 2 in 5000..9000) 1.0 else 0.3)).toFloat() }
        val l = Limiter(rate, 2, 0.98f)
        val y = x.copyOf()
        l.process(y, n)
        for (i in l.latency * 2 until n * 2) assertTrue("${y[i]}", abs(y[i]) <= 0.9801f)
        // 小さい部分(超えない)はそのまま
        val k = 2000
        assertEquals(x[k * 2], y[(k + l.latency) * 2], 1e-6f)
    }

    @Test fun dsfHeader() {
        val data = arrayOf(ByteArray(5000) { 0x69 }, ByteArray(5000) { 0x0F })
        val bytes = write { out -> DsfWriter(out, 2822400, 2, null).apply { write(data, 5000); finish() } }
        assertEquals("DSD ", String(bytes, 0, 4))
        assertEquals(bytes.size.toLong(), le64(bytes, 12))
        assertEquals(2822400, le32(bytes, 28 + 28))
        assertEquals(5000L * 8, le64(bytes, 28 + 36))
        assertEquals(4096, le32(bytes, 28 + 44))
        val dataLen = le64(bytes, 84)
        assertEquals(12 + 2 * 8192L, dataLen)
        // 2 ブロック目の右チャンネルの先頭は、0x0F のビットを逆にした 0xF0
        val d0 = 92
        assertEquals(0x96.toByte(), bytes[d0])
        assertEquals(0xF0.toByte(), bytes[d0 + 4096])
        // 残りは無音で埋める
        assertEquals(0x96.toByte(), bytes[d0 + 8192 + 5000 - 4096])
    }

    @Test fun dffHeader() {
        val data = arrayOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val bytes = write { out -> DffWriter(out, 5644800, 2, null).apply { write(data, 3); finish() } }
        assertEquals("FRM8", String(bytes, 0, 4))
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(bytes.size - 12L, bb.getLong(4))
        val s = String(bytes, Charsets.ISO_8859_1)
        val fs = s.indexOf("FS  ")
        assertEquals(5644800, bb.getInt(fs + 12))
        val dsd = s.lastIndexOf("DSD ")
        assertEquals(6L, bb.getLong(dsd + 4))
        assertArrayEquals(byteArrayOf(1, 4, 2, 5, 3, 6), bytes.copyOfRange(dsd + 12, dsd + 18))
    }

    @Test fun dsreAddsOnlyAboveCutoff() {
        val rate = 96000
        val n = 48000
        // 15 kHz で切れた音(5〜15 kHz のいくつかの正弦波)
        val x = FloatArray(n * 2) { i ->
            val t = (i / 2).toDouble() / rate
            (0.1 * (sin(2 * PI * 6000 * t) + sin(2 * PI * 11000 * t) + sin(2 * PI * 14000 * t))).toFloat()
        }
        val y = x.copyOf()
        val d = Dsre(rate, 2, DsreParams(harmonics = 8, strength = 0.5f, rate = rate, width = 1f, dynamic = 1f), 15000.0)
        assertTrue(d.order >= 2)
        d.process(y, n)
        // 足したもの = y - x は 15 kHz より上だけ(下は 4 次の高域通過で十分小さい)
        fun power(sig: FloatArray, f: Double): Double {
            var c = 0.0; var s = 0.0
            for (i in n / 2 until n) { val t = i.toDouble() / rate; c += sig[i * 2] * Math.cos(2 * PI * f * t); s += sig[i * 2] * Math.sin(2 * PI * f * t) }
            return sqrt(c * c + s * s) / (n / 2)
        }
        val diff = FloatArray(n * 2) { y[it] - x[it] }
        // 2 倍音(22 kHz・28 kHz)ができている
        assertTrue(power(diff, 22000.0) > 1e-4 || power(diff, 28000.0) > 1e-4)
        // 元の帯域(6 kHz)はほとんど変わらない
        assertTrue(power(diff, 6000.0) < 0.05 * power(x, 6000.0))
    }

    @Test fun presetValuesAreClamped() {
        assertEquals(DsreParams("b", 24, 0.8f, 96000, 1.5f, 1.0f), DsreParams(" b ", 30, 2f, 12345, 9f, 0f).clamped())
        val ok = DsreParams("a", 5, 0.3f, 48000, 1.2f, 1.1f)
        assertEquals(ok, ok.clamped())
    }
}
