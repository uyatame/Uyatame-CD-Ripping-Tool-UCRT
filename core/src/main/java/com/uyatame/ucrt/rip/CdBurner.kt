package com.uyatame.ucrt.rip

import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.usb.ScsiException
import com.uyatame.ucrt.usb.UsbScsiDrive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 書き込む内容(音楽 CD 1 枚分)。file は 2352 バイトのセクターを LBA 0 から順に並べた PCM(16bit / 44.1 kHz / 2ch、リトルエンディアン)。
 * tracks[i] は i+1 曲目の始まり(インデックス 01 の LBA)。gaps[i] はその前の曲間(インデックス 00 の長さ。セクター数)。
 * 1 曲目の前の 2 秒の無音はここに含めない(書き込むときに足す)
 */
class BurnImage(val file: File, val tracks: List<Int>, val gaps: List<Int>, val totalSectors: Int) {
    val seconds: Double get() = totalSectors / 75.0
}

class BurnOptions(
    /** 書き込みの速さ(倍速。0 で最大) */
    val speedX: Int,
    /** テスト書き込み(レーザーを弱くして、実際には焼かない) */
    val testWrite: Boolean,
)

/**
 * 音楽 CD の書き込み(CD-R / CD-RW)。
 *
 * まず SAO(セッションアットワンス: CUE シートで曲の位置を伝えて、リードインからリードアウトまで一度に書く)で書く。
 * 曲間の無音を自由にでき(曲間なしも可)、元の CD と同じ並びでコピーできる。
 * ドライブが SAO に対応していなければ TAO(トラックアットワンス: 1 曲ずつ書き、曲間に 2 秒の無音が入る)で書く
 */
class CdBurner(private val drive: UsbScsiDrive, private val opt: BurnOptions, private val log: (String) -> Unit) {
    private val sector = 2352
    /** 1 回に送るセクター数(約 60 KB) */
    private val chunk = 26

    /** ディスクの状態を確かめる。書き込めなければ理由の例外 */
    fun checkDisc(img: BurnImage): UsbScsiDrive.DiscInfo {
        val info = drive.readDiscInfo()
        if (info.status != 0) {
            throw IOException(
                if (info.erasable) S(R.string.this_cd_rw_is_not)
                else S(R.string.insert_blank_cd_r_this),
            )
        }
        // 1 曲目の前の 2 秒を含めて入るか
        if (info.lastLeadOut > 0 && img.totalSectors > info.lastLeadOut) {
            throw IOException(
                S(R.string.not_enough_space_on_disc).format(img.totalSectors / 75 / 60, img.totalSectors / 75 % 60, info.lastLeadOut / 75 / 60, info.lastLeadOut / 75 % 60),
            )
        }
        return info
    }

    /** 書き込む。onProgress は 0〜1、check は中止のときに例外を出す */
    fun burn(img: BurnImage, onProgress: (Float) -> Unit, check: () -> Unit) {
        runCatching { drive.setWriteSpeed(if (opt.speedX <= 0) 0 else opt.speedX * 176) }
            .onFailure { log("burn: set speed failed: ${it.message}") }
        val sao = try {
            drive.setWriteParameters(writeType = 2, testWrite = opt.testWrite)
            drive.sendCueSheet(cueSheet(img))
            true
        } catch (e: ScsiException) {
            if (e.senseKey == -1) throw e
            log("burn: SAO not accepted (${e.message}), using TAO")
            false
        }
        log("burn: ${if (sao) "SAO" else "TAO"} ${img.tracks.size} tracks ${img.totalSectors} sectors speed=${opt.speedX}x test=${opt.testWrite}")
        try {
            RandomAccessFile(img.file, "r").use { f ->
                if (sao) writeSao(img, f, onProgress, check) else writeTao(img, f, onProgress, check)
            }
        } catch (e: Throwable) {
            // 中止・失敗したときも、ドライブのバッファーを書き終えさせて書き込みの状態から戻す
            // (そのままだと、次のディスクの確認や書き込みのコマンドがはねられることがある)
            if (!(e is ScsiException && e.senseKey == -1)) {
                runCatching { syncCache(); waitReady(2 * 60_000L) }.onFailure { log("burn: flush after error: ${it.message}") }
            }
            throw e
        }
    }

    private fun bcd(v: Int) = ((v / 10) shl 4) or (v % 10)

    /** SAO の CUE シート(1 項目 8 バイト: CTL/ADR・曲番号・インデックス・データの形式・SCMS・分・秒・フレーム) */
    private fun cueSheet(img: BurnImage): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun entry(tno: Int, index: Int, form: Int, lba: Int) {
            val a = lba + 150
            out.write(0x01) // 2ch オーディオ、コピー不可・プリエンファシスなし / ADR 1
            out.write(tno)
            out.write(index)
            out.write(form)
            out.write(0)
            out.write(a / 75 / 60)
            out.write(a / 75 % 60)
            out.write(a % 75)
        }
        // リードイン(ドライブが作る)
        entry(0x00, 0x00, 0x01, -150)
        img.tracks.forEachIndexed { i, start ->
            val n = bcd(i + 1)
            // 1 曲目は LBA -150 からの 2 秒の無音がインデックス 00。2 曲目からは曲間があるときだけ
            if (i == 0) entry(n, 0x00, 0x00, -150)
            else if (img.gaps[i] > 0) entry(n, 0x00, 0x00, start - img.gaps[i])
            entry(n, 0x01, 0x00, start)
        }
        // リードアウト(ドライブが作る)
        entry(0xAA, 0x01, 0x01, img.totalSectors)
        return out.toByteArray()
    }

    private fun writeSao(img: BurnImage, f: RandomAccessFile, onProgress: (Float) -> Unit, check: () -> Unit) {
        val buf = ByteArray(chunk * sector)
        // 1 曲目の前の 2 秒の無音(LBA -150 から)
        var lba = -150
        while (lba < 0) {
            check()
            val n = minOf(chunk, -lba)
            java.util.Arrays.fill(buf, 0, n * sector, 0)
            writeRetry(lba, buf, n)
            lba += n
        }
        val total = img.totalSectors
        f.seek(0)
        while (lba < total) {
            check()
            val n = minOf(chunk, total - lba)
            readSectors(f, buf, n)
            writeRetry(lba, buf, n)
            lba += n
            onProgress(lba.toFloat() / total * 0.98f)
        }
        finish()
        onProgress(1f)
    }

    private fun writeTao(img: BurnImage, f: RandomAccessFile, onProgress: (Float) -> Unit, check: () -> Unit) {
        drive.setWriteParameters(writeType = 1, testWrite = opt.testWrite)
        val buf = ByteArray(chunk * sector)
        val total = img.totalSectors
        var done = 0
        img.tracks.forEachIndexed { i, start ->
            // TAO はドライブが曲間に 2 秒の無音を入れるので、こちらの曲間は書かない
            val end = if (i + 1 < img.tracks.size) img.tracks[i + 1] - img.gaps[i + 1] else total
            var lba = drive.nextWritableAddress()
            f.seek(start.toLong() * sector)
            var left = end - start
            while (left > 0) {
                check()
                val n = minOf(chunk, left)
                readSectors(f, buf, n)
                writeRetry(lba, buf, n)
                lba += n
                left -= n
                done += n
                onProgress(done.toFloat() / total * 0.98f)
            }
            syncCache()
            waitReady()
            runCatching { drive.closeTrackSession(1, i + 1) }.onFailure { log("burn: close track: ${it.message}") }
            waitReady()
        }
        runCatching { drive.closeTrackSession(2) }.onFailure { log("burn: close session: ${it.message}") }
        waitReady()
        onProgress(1f)
    }

    private fun readSectors(f: RandomAccessFile, buf: ByteArray, n: Int) {
        val len = n * sector
        var o = 0
        while (o < len) {
            val r = f.read(buf, o, len - o)
            if (r <= 0) break
            o += r
        }
        if (o < len) java.util.Arrays.fill(buf, o, len, 0)
    }

    /** 書き込む。ドライブのバッファーがいっぱい(準備中)なら少し待ってやり直す */
    private fun writeRetry(lba: Int, buf: ByteArray, n: Int) {
        var tries = 0
        while (true) {
            try {
                drive.write10(lba, buf, 0, n)
                return
            } catch (e: ScsiException) {
                // 2/04/08: 書き込み中(バッファーが空くのを待つ)
                if (e.senseKey == 2 && e.asc == 0x04 && tries++ < 400) {
                    Thread.sleep(25)
                    continue
                }
                throw IOException(S(R.string.write_failed_lba).format(lba, e.message), e)
            }
        }
    }

    /** バッファーの残りを書き終え、ドライブが落ち着くまで待つ(SAO はこのあとドライブがリードアウトを書く) */
    private fun finish() {
        syncCache()
        waitReady()
    }

    /** SYNCHRONIZE CACHE。ドライブがまだバッファーを書いている(2/04/xx)ならしばらく待ってやり直す */
    private fun syncCache() {
        var tries = 0
        while (true) {
            try {
                drive.synchronizeCache()
                return
            } catch (e: ScsiException) {
                if (e.senseKey == 2 && e.asc == 0x04 && tries++ < 120) { Thread.sleep(500); continue }
                throw e
            }
        }
    }

    private fun waitReady(maxMs: Long = 15 * 60_000L) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < maxMs) {
            try {
                drive.testUnitReady()
                return
            } catch (e: ScsiException) {
                if (e.senseKey == -1) throw e
                // 2/04/xx: 準備中(リードアウトの書き込みなど)
                if (e.senseKey == 2 && e.asc == 0x04) { Thread.sleep(1000); continue }
                // ディスク交換などの知らせは一度読めば消える
                if (e.senseKey == 6) { Thread.sleep(100); continue }
                return
            }
        }
    }
}
