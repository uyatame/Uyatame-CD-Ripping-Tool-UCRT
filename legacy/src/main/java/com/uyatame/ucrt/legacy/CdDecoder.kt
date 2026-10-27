package com.uyatame.ucrt.legacy

import com.uyatame.ucrt.player.PcmDecoder
import com.uyatame.ucrt.usb.UsbScsiDrive

/**
 * CD の曲を、ほかの曲と同じデコーダーの形で読む(READ CD で 44.1 kHz / 16bit / 2ch のまま)。
 * 再生のエンジンが、ファイルの曲と同じようにシーク・ギャップレスできるように
 */
internal class CdDecoder(private val d: UsbScsiDrive, private val startLba: Int, private val endLba: Int, private val speedX: Int) : PcmDecoder {
    override val sampleRate = 44100
    override val channels = 2
    override val bitsPerSample = 16
    override val totalSamples: Long = (endLba - startLba).toLong() * 588
    override val samples = Array(2) { IntArray(588 * 27) }
    override var blockSize = 0; private set
    override var frameSample = 0L; private set
    private var lba = startLba
    @Volatile var stop = false

    override fun open() {
        runCatching { d.setSpeed(speedX * 176) }
    }

    private fun read(at: Int, n: Int): ByteArray {
        for (a in 0 until 2) {
            try {
                val r = d.readCd(at, n, false)
                if (r.size >= n * 2352) return r
            } catch (e: Exception) {
                if (stop) break
            }
        }
        return ByteArray(n * 2352)
    }

    override fun decodeNext(): Boolean {
        if (lba >= endLba || stop) return false
        val n = minOf(27, endLba - lba)
        val b = read(lba, n)
        val frames = n * 588
        val l = samples[0]; val r = samples[1]
        var o = 0
        for (i in 0 until frames) {
            l[i] = (b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)
            r[i] = (b[o + 2].toInt() and 0xFF) or (b[o + 3].toInt() shl 8)
            o += 4
        }
        frameSample = (lba - startLba).toLong() * 588
        blockSize = frames
        lba += n
        return true
    }

    override fun seekSample(target: Long) {
        lba = (startLba + target / 588).toInt().coerceIn(startLba, endLba)
        frameSample = (lba - startLba).toLong() * 588
    }
}
