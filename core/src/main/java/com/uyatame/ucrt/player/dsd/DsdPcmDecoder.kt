package com.uyatame.ucrt.player.dsd

import com.uyatame.ucrt.player.PcmDecoder
import java.io.IOException

/**
 * DSD(DSF / DSDIFF)を PCM にして読む(DsdToPcm で 176.4 kHz / 192 kHz の PCM にする)。24 ビットの整数で出す。
 * DSD を DSD のまま出せない出力先(古い Android の AudioTrack など)で使う
 */
internal class DsdPcmDecoder(private val file: RandomFile) : PcmDecoder {
    override var sampleRate = 0
        private set
    override var channels = 0
        private set
    override val bitsPerSample = 24
    override var totalSamples = 0L
        private set
    override var samples: Array<IntArray> = emptyArray()
        private set
    override var blockSize = 0
        private set
    override var frameSample = 0L
        private set

    private lateinit var info: DsdInfo
    private lateinit var reader: DsdReader
    private lateinit var conv: DsdToPcm
    private var raw: Array<ByteArray> = emptyArray()
    private var pcm = FloatArray(0)
    /** 次に出す PCM のサンプルの番号 */
    private var outPos = 0L
    /** DSD の 1 バイトあたりの PCM のサンプル数の逆数(何バイトで 1 サンプルか) */
    private var bytesPerOut = 0

    override fun open() {
        info = DsdFile.parse(file)
        if (info.compressed) throw IOException("DSD: DST not supported")
        channels = info.channels
        conv = DsdToPcm(info.rate, channels)
        sampleRate = conv.outRate
        bytesPerOut = info.rate / 8 / sampleRate
        totalSamples = info.bytesPerChannel / bytesPerOut
        reader = DsdReader(file, info)
        raw = Array(channels) { ByteArray(CHUNK) }
        pcm = FloatArray(conv.maxOut(CHUNK) * channels)
        samples = Array(channels) { IntArray(conv.maxOut(CHUNK)) }
        outPos = 0
    }

    override fun decodeNext(): Boolean {
        while (true) {
            val n = reader.read(raw, CHUNK)
            if (n <= 0) return false
            val frames = conv.process(raw, n, pcm)
            if (frames == 0) continue
            for (i in 0 until frames) for (c in 0 until channels) {
                val v = Math.round(pcm[i * channels + c] * 8388608.0)
                samples[c][i] = v.coerceIn(-8388608L, 8388607L).toInt()
            }
            blockSize = frames
            frameSample = outPos
            outPos += frames
            return true
        }
    }

    override fun seekSample(target: Long) {
        // 変換のフィルターの分は、呼び出し側が先頭を少し捨てる(位置は DSD のバイトから決める)
        val byte = (target * bytesPerOut).coerceIn(0, info.bytesPerChannel)
        reader.seek(byte)
        outPos = byte / bytesPerOut
    }

    private companion object {
        /** 1 回に読む DSD のバイト数(1 チャンネルあたり) */
        const val CHUNK = 4096
    }
}
