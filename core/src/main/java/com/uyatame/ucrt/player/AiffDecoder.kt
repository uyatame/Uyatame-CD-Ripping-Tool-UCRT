package com.uyatame.ucrt.player

import java.io.IOException

/**
 * AIFF / AIFF-C(非圧縮)の読み込み。
 * Android の OS には AIFF のデコーダーが無いので、アプリで読む。
 *
 * 対応: AIFF(ビッグエンディアンの整数 PCM)、AIFF-C の NONE / twos(ビッグエンディアン)・sowt(リトルエンディアン)・
 * fl32 / FL32(32bit float。24bit の整数にして出す)、8〜32bit、1〜8ch
 */
internal class AiffDecoder(private val src: RandomInput) : PcmDecoder {
    override var sampleRate = 0
        private set
    override var channels = 0
        private set
    override var bitsPerSample = 0
        private set
    override var totalSamples = 0L
        private set
    override var samples: Array<IntArray> = emptyArray()
        private set
    override var blockSize = 0
        private set
    override var frameSample = 0L
        private set

    private var dataStart = 0L
    private var bytesPerSample = 0
    private var little = false
    private var float = false
    /** 次に読むサンプルの番号 */
    private var next = 0L
    private var buf = ByteArray(0)
    private val block = 4096

    private fun readAt(pos: Long, n: Int): ByteArray {
        val b = ByteArray(n)
        var o = 0
        while (o < n) {
            val r = src.read(pos + o, b, o, n - o)
            if (r <= 0) throw IOException("AIFF: unexpected end")
            o += r
        }
        return b
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun be16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    /** 80bit の拡張精度浮動小数点(周波数) */
    private fun ext80(b: ByteArray, o: Int): Double {
        val exp = be16(b, o) and 0x7FFF
        var mant = 0L
        for (i in 0 until 8) mant = (mant shl 8) or (b[o + 2 + i].toLong() and 0xFF)
        if (exp == 0 && mant == 0L) return 0.0
        // 仮数は 1.xxx の形(最上位ビットが整数部)
        val m = (mant ushr 11).toDouble() / (1L shl 52).toDouble()
        val v = m * Math.pow(2.0, (exp - 16383).toDouble())
        return if ((b[o].toInt() and 0x80) != 0) -v else v
    }

    override fun open() {
        val head = readAt(0, 12)
        val form = String(head, 0, 4, Charsets.ISO_8859_1)
        val type = String(head, 8, 4, Charsets.ISO_8859_1)
        if (form != "FORM" || (type != "AIFF" && type != "AIFC")) throw IOException("not an AIFF file")
        val end = minOf(src.size, 8 + be32(head, 4))
        var pos = 12L
        var frames = 0L
        var gotComm = false
        var gotData = false
        while (pos + 8 <= end) {
            val h = readAt(pos, 8)
            val id = String(h, 0, 4, Charsets.ISO_8859_1)
            val size = be32(h, 4)
            val body = pos + 8
            when (id) {
                "COMM" -> {
                    if (size < 18) throw IOException("AIFF: bad COMM")
                    val c = readAt(body, minOf(size, 64L).toInt())
                    channels = be16(c, 0)
                    frames = be32(c, 2)
                    bitsPerSample = be16(c, 6)
                    sampleRate = Math.round(ext80(c, 8)).toInt()
                    if (type == "AIFC" && c.size >= 22) {
                        when (String(c, 18, 4, Charsets.ISO_8859_1)) {
                            "NONE", "twos" -> {}
                            "sowt" -> little = true
                            "fl32", "FL32" -> { float = true; bitsPerSample = 32 }
                            else -> throw IOException("AIFF-C: unsupported compression")
                        }
                    }
                    gotComm = true
                }
                "SSND" -> {
                    val s = readAt(body, 8)
                    dataStart = body + 8 + be32(s, 0)
                    gotData = true
                }
            }
            pos = body + size + (size and 1)
        }
        if (!gotComm || !gotData || channels !in 1..8 || sampleRate <= 0 || bitsPerSample !in 1..32) {
            throw IOException("AIFF: missing or bad header")
        }
        bytesPerSample = (bitsPerSample + 7) / 8
        // 実際に入っている分を超えないように
        val avail = (src.size - dataStart) / (bytesPerSample.toLong() * channels)
        totalSamples = minOf(frames, avail).coerceAtLeast(0)
        // float は 24bit の整数にして出す
        if (float) bitsPerSample = 24
        next = 0
    }

    override fun decodeNext(): Boolean {
        val n = minOf(block.toLong(), totalSamples - next).toInt()
        if (n <= 0) return false
        val frameBytes = bytesPerSample * channels
        val len = n * frameBytes
        if (buf.size < len) buf = ByteArray(len)
        var o = 0
        while (o < len) {
            val r = src.read(dataStart + next * frameBytes + o, buf, o, len - o)
            if (r <= 0) break
            o += r
        }
        val got = o / frameBytes
        if (got <= 0) return false
        if (samples.size != channels || samples[0].size < block) samples = Array(channels) { IntArray(block) }
        val bps = bytesPerSample
        var p = 0
        for (i in 0 until got) {
            for (c in 0 until channels) {
                var v = 0
                if (little) {
                    for (k in bps - 1 downTo 0) v = (v shl 8) or (buf[p + k].toInt() and 0xFF)
                } else {
                    for (k in 0 until bps) v = (v shl 8) or (buf[p + k].toInt() and 0xFF)
                }
                p += bps
                samples[c][i] = when {
                    float -> {
                        val f = java.lang.Float.intBitsToFloat(v).coerceIn(-1f, 1f)
                        (f * 8388607f).toInt()
                    }
                    // 8bit も含めて符号付き。ビット数が 8 の倍数でないときは上に詰まっているので右へ戻す
                    else -> ((v shl (32 - bps * 8)) shr (32 - bps * 8)) shr (bps * 8 - bitsPerSample)
                }
            }
        }
        frameSample = next
        blockSize = got
        next += got
        return true
    }

    override fun seekSample(target: Long) {
        next = target.coerceIn(0L, totalSamples)
    }
}
