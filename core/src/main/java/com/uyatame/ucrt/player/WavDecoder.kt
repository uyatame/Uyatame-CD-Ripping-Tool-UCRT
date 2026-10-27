package com.uyatame.ucrt.player

import java.io.IOException

/**
 * WAV(RIFF の PCM。整数 8〜32 ビット、小数 32・64 ビット、WAVE_FORMAT_EXTENSIBLE、RF64)を読む。
 * 小数のデータは 24 ビットの整数にして出す
 */
internal class WavDecoder(private val src: RandomInput) : PcmDecoder {
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
    private var dataEnd = 0L
    private var srcBits = 0
    private var isFloat = false
    private var frameBytes = 0
    private var pos = 0L
    private var buf = ByteArray(0)

    override fun open() {
        val h = read(0, 12) ?: throw IOException("WAV: too short")
        val riff = String(h, 0, 4, Charsets.ISO_8859_1)
        if ((riff != "RIFF" && riff != "RF64") || String(h, 8, 4, Charsets.ISO_8859_1) != "WAVE") throw IOException("WAV: not RIFF/WAVE")
        var p = 12L
        var rf64Data = -1L
        var fmt: ByteArray? = null
        while (p + 8 <= src.size) {
            val c = read(p, 8) ?: break
            val id = String(c, 0, 4, Charsets.ISO_8859_1)
            var size = le32(c, 4).toLong() and 0xFFFFFFFFL
            when (id) {
                "ds64" -> read(p + 8, 28)?.let { rf64Data = le64(it, 8) }
                "fmt " -> fmt = read(p + 8, minOf(size, 64L).toInt())
                "data" -> {
                    if (size == 0xFFFFFFFFL && rf64Data >= 0) size = rf64Data
                    dataStart = p + 8
                    dataEnd = minOf(src.size, dataStart + size)
                    break
                }
            }
            p += 8 + size + (size and 1)
        }
        val f = fmt ?: throw IOException("WAV: no fmt")
        if (dataEnd <= dataStart) throw IOException("WAV: no data")
        var tag = le16(f, 0)
        channels = le16(f, 2)
        sampleRate = le32(f, 4)
        srcBits = le16(f, 14)
        frameBytes = le16(f, 12)
        // WAVE_FORMAT_EXTENSIBLE: 本当の形式は後ろの GUID の頭
        if (tag == 0xFFFE && f.size >= 26) tag = le16(f, 24)
        isFloat = tag == 3
        if (tag != 1 && tag != 3) throw IOException("WAV: format $tag not supported")
        if (isFloat && srcBits != 32 && srcBits != 64) throw IOException("WAV: float $srcBits bit not supported")
        if (!isFloat && srcBits !in 8..32) throw IOException("WAV: $srcBits bit not supported")
        if (channels <= 0 || sampleRate <= 0 || frameBytes < channels * ((srcBits + 7) / 8)) throw IOException("WAV: bad header")
        bitsPerSample = if (isFloat) 24 else srcBits
        totalSamples = (dataEnd - dataStart) / frameBytes
        samples = Array(channels) { IntArray(BLOCK) }
        buf = ByteArray(BLOCK * frameBytes)
        pos = 0
    }

    override fun decodeNext(): Boolean {
        val left = totalSamples - pos
        if (left <= 0) return false
        val n = minOf(left, BLOCK.toLong()).toInt()
        var got = 0
        val want = n * frameBytes
        while (got < want) {
            val r = src.read(dataStart + pos * frameBytes + got, buf, got, want - got)
            if (r <= 0) break
            got += r
        }
        val frames = got / frameBytes
        if (frames == 0) return false
        val bytes = (srcBits + 7) / 8
        for (i in 0 until frames) {
            var o = i * frameBytes
            for (c in 0 until channels) {
                samples[c][i] = sample(o, bytes)
                o += bytes
            }
        }
        blockSize = frames
        frameSample = pos
        pos += frames
        return true
    }

    private fun sample(o: Int, bytes: Int): Int {
        val b = buf
        if (isFloat) {
            val v = if (bytes == 4) java.lang.Float.intBitsToFloat(le32(b, o)).toDouble() else java.lang.Double.longBitsToDouble(le64(b, o))
            val s = Math.round(v * 8388608.0)
            return s.coerceIn(-8388608L, 8388607L).toInt()
        }
        return when (bytes) {
            1 -> (b[o].toInt() and 0xFF) - 128
            2 -> ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8))
            3 -> ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() shl 16))
            else -> le32(b, o)
        } shr (bytes * 8 - srcBits).coerceAtLeast(0)
    }

    override fun seekSample(target: Long) {
        pos = target.coerceIn(0, totalSamples)
    }

    private fun read(at: Long, n: Int): ByteArray? {
        if (at < 0 || at + n > src.size) return null
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = src.read(at + off, b, off, n - off)
            if (r <= 0) return null
            off += r
        }
        return b
    }

    private companion object {
        const val BLOCK = 4096
        fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
        fun le64(b: ByteArray, o: Int) = (le32(b, o).toLong() and 0xFFFFFFFFL) or (le32(b, o + 4).toLong() shl 32)
    }
}
