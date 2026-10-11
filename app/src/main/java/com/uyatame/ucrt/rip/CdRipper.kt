package com.uyatame.ucrt.rip

import com.uyatame.ucrt.T

import com.uyatame.ucrt.usb.ScsiException
import com.uyatame.ucrt.usb.UsbScsiDrive
import java.io.IOException

class RipOptions(
    val speedX: Int,
    val sectorsPerRead: Int,
    val retries: Int,
    val useC2: Boolean,
    val abortOnError: Boolean,
    val offsetSamples: Int,
)

class RipResult(val errorSectors: Int, val c2Disabled: Boolean)

/** READ CD(0xBE)でCD-DAをデジタル抽出する。リトライ・C2エラー検出・読み取りオフセット補正に対応。 */
class CdRipper(private val drive: UsbScsiDrive, private val opt: RipOptions) {
    private val sector = 2352
    private val c2Sector = 2646
    private var c2 = opt.useC2
    private var errors = 0

    private class Out(val data: ByteArray?, val clean: Boolean)

    fun rip(
        startLba: Int,
        endLba: Int,
        leadOut: Int,
        sink: AudioSink,
        onProgress: (Float) -> Unit,
        check: () -> Unit,
    ): RipResult {
        runCatching { drive.setSpeed(if (opt.speedX <= 0) 0 else opt.speedX * 176) }
        val offBytes = opt.offsetSamples.toLong() * 4
        val startByte = startLba.toLong() * sector + offBytes
        val endByte = endLba.toLong() * sector + offBytes
        val firstSector = Math.floorDiv(startByte, sector.toLong())
        val lastSector = Math.floorDiv(endByte + sector - 1, sector.toLong())
        val skipFirst = (startByte - firstSector * sector).toInt()
        val total = endByte - startByte
        if (total <= 0) return RipResult(0, false)

        var remaining = total
        var cur = firstSector
        var first = true
        while (cur < lastSector && remaining > 0) {
            check()
            val n = minOf(opt.sectorsPerRead.toLong(), lastSector - cur).toInt()
            val data = readSectors(cur, n, leadOut)
            val skip = if (first) skipFirst else 0
            first = false
            val len = minOf(remaining, (n * sector - skip).toLong()).toInt()
            sink.write(data, skip, len)
            remaining -= len
            cur += n
            onProgress(1f - remaining.toFloat() / total)
        }
        return RipResult(errors, opt.useC2 && !c2)
    }

    /** ディスク範囲外(リードイン/アウト)は無音で埋める */
    private fun readSectors(lba: Long, n: Int, leadOut: Int): ByteArray {
        val out = ByteArray(n * sector)
        val lo = maxOf(lba, 0L)
        val hi = minOf(lba + n, leadOut.toLong())
        if (hi <= lo) return out
        val cnt = (hi - lo).toInt()
        val d = readChunk(lo.toInt(), cnt)
        System.arraycopy(d, 0, out, ((lo - lba) * sector).toInt(), cnt * sector)
        return out
    }

    private fun readChunk(lba: Int, cnt: Int): ByteArray {
        val r = tryRead(lba, cnt)
        if (r.clean && r.data != null) return r.data
        if (cnt > 1) {
            val out = ByteArray(cnt * sector)
            for (i in 0 until cnt) System.arraycopy(readChunk(lba + i, 1), 0, out, i * sector, sector)
            return out
        }
        errors++
        if (opt.abortOnError) throw IOException(T("読み取りエラー LBA=$lba", "Read error at LBA $lba"))
        return r.data ?: ByteArray(sector)
    }

    private fun tryRead(lba: Int, cnt: Int): Out {
        var last: ByteArray? = null
        for (attempt in 0..opt.retries) {
            try {
                val raw = drive.readCd(lba, cnt, c2)
                val expect = cnt * (if (c2) c2Sector else sector)
                if (raw.size < expect) continue
                if (!c2) return Out(raw, true)
                val audio = ByteArray(cnt * sector)
                var bad = false
                for (i in 0 until cnt) {
                    System.arraycopy(raw, i * c2Sector, audio, i * sector, sector)
                    if (!bad) {
                        for (j in 0 until 294) {
                            if (raw[i * c2Sector + sector + j].toInt() != 0) { bad = true; break }
                        }
                    }
                }
                last = audio
                if (!bad) return Out(audio, true)
            } catch (e: ScsiException) {
                if (e.senseKey == -1) throw e
                if (c2 && e.senseKey == 5) { c2 = false; return tryRead(lba, cnt) }
            }
        }
        return Out(last, false)
    }
}
