package com.uyatame.ucrt.usb

import com.uyatame.ucrt.R
import com.uyatame.ucrt.S

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScsiException(val senseKey: Int, val asc: Int, val ascq: Int, message: String) : Exception(message)

data class TocTrack(val number: Int, val startLba: Int, val endLba: Int, val isAudio: Boolean)

private fun cdb(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

private fun be32(d: ByteArray, o: Int): Long =
    ((d[o].toLong() and 0xFF) shl 24) or ((d[o + 1].toLong() and 0xFF) shl 16) or
        ((d[o + 2].toLong() and 0xFF) shl 8) or (d[o + 3].toLong() and 0xFF)

/** USB Bulk-Only Transport 上で SCSI/MMC コマンドを直接発行する光学ドライブ制御クラス */
class UsbScsiDrive private constructor(
    private val connection: UsbDeviceConnection,
    private val itf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    val name: String,
    /** USB 機器の名前(取り外されたのがこのドライブかを見分ける) */
    val deviceName: String,
) {
    private var tag = 0x100
    private var sectorType = 0x04
    var logger: ((String) -> Unit)? = null
    @Volatile var verbose = false

    companion object {
        /** Bulk-Only(protocol 0x50)のマスストレージIFを探す */
        fun findInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val f = device.getInterface(i)
                if (f.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && f.interfaceProtocol == 0x50) {
                    var hasIn = false
                    var hasOut = false
                    for (j in 0 until f.endpointCount) {
                        val ep = f.getEndpoint(j)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true else hasOut = true
                        }
                    }
                    if (hasIn && hasOut) return f
                }
            }
            return null
        }

        /** UAS(protocol 0x62)しか持たないか */
        fun isUasOnly(device: UsbDevice): Boolean {
            var uas = false
            for (i in 0 until device.interfaceCount) {
                val f = device.getInterface(i)
                if (f.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && f.interfaceProtocol == 0x62) uas = true
            }
            return uas && findInterface(device) == null
        }

        fun describe(dev: UsbDevice): String {
            val sb = StringBuilder("USB %04X:%04X %s".format(dev.vendorId, dev.productId, UsbCompat.productName(dev) ?: ""))
            for (i in 0 until dev.interfaceCount) {
                val f = dev.getInterface(i)
                sb.append("\n  IF#%d alt=%d class=%d sub=%d proto=0x%02X ep=%d".format(
                    f.id, UsbCompat.alternate(f), f.interfaceClass, f.interfaceSubclass, f.interfaceProtocol, f.endpointCount,
                ))
            }
            return sb.toString()
        }

        fun open(manager: UsbManager, device: UsbDevice): UsbScsiDrive {
            val itf = findInterface(device) ?: throw IllegalStateException(S(R.string.no_bulk_only_interface_found))
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                }
            }
            if (epIn == null || epOut == null) throw IllegalStateException(S(R.string.no_bulk_endpoints_found))
            val conn = manager.openDevice(device) ?: throw IllegalStateException(S(R.string.cannot_open_device))
            if (!conn.claimInterface(itf, true)) {
                conn.close()
                throw IllegalStateException(S(R.string.cannot_claim_interface))
            }
            UsbCompat.setInterface(conn, itf)
            val n = listOfNotNull(UsbCompat.manufacturerName(device), UsbCompat.productName(device))
                .joinToString(" ").ifBlank { device.deviceName }
            return UsbScsiDrive(conn, itf, epIn, epOut, n, device.deviceName)
        }

        fun profileName(p: Int): String = when (p) {
            0x00 -> S(R.string.no_media)
            0x08 -> "CD-ROM / CD-DA"
            0x09 -> "CD-R"
            0x0A -> "CD-RW"
            0x10 -> "DVD-ROM"
            0x11 -> "DVD-R"
            0x13, 0x14 -> "DVD-RW"
            0x1A -> "DVD+RW"
            0x1B -> "DVD+R"
            0x2B -> "DVD+R DL"
            0x40 -> "BD-ROM"
            0x41, 0x42 -> "BD-R"
            0x43 -> "BD-RE"
            else -> S(R.string.unknown_0x).format(p)
        }
    }

    /**
     * 閉じる。ほかのスレッドがコマンドの途中なら、それが終わるまで待つ
     * (転送の途中で閉じると、OS の中で解放済みの領域を使ってしまい、落ちることがある)
     */
    @Synchronized
    fun close() {
        runCatching { connection.releaseInterface(itf) }
        runCatching { connection.close() }
    }

    private fun clearHalt(ep: UsbEndpoint) {
        connection.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 2000)
    }

    /** BOT Reset Recovery: 通信が詰まったときの復旧 */
    private fun resetRecovery() {
        runCatching { connection.controlTransfer(0x21, 0xFF, 0, itf.id, null, 0, 2000) }
        runCatching { clearHalt(epIn) }
        runCatching { clearHalt(epOut) }
    }

    /**
     * データをドライブへ送るコマンド(WRITE・MODE SELECT・SEND CUE SHEET など)。
     * 失敗したら ScsiException(センスキー付き)
     */
    @Synchronized
    fun executeOut(cdb: ByteArray, out: ByteArray, off: Int = 0, len: Int = out.size, timeout: Int = 60000) {
        val cbw = ByteArray(31)
        val bb = ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN)
        val t = tag++
        bb.putInt(0x43425355)
        bb.putInt(t)
        bb.putInt(len)
        bb.put(0.toByte()) // ホスト → ドライブ
        bb.put(0.toByte())
        bb.put(cdb.size.toByte())
        bb.put(cdb)
        val op = "cmd=0x%02X".format(cdb[0].toInt() and 0xFF)
        if (connection.bulkTransfer(epOut, cbw, 31, timeout) != 31) {
            resetRecovery()
            throw ScsiException(-1, 0, 0, S(R.string.usb_send_failed_2))
        }
        var sent = 0
        var dataFail = false
        while (sent < len) {
            val n = minOf(16384, len - sent)
            val r = UsbCompat.bulk(connection, epOut, out, off + sent, n, timeout)
            if (r <= 0) {
                dataFail = true
                runCatching { clearHalt(epOut) }
                break
            }
            sent += r
        }
        val csw = ByteArray(13)
        var r = connection.bulkTransfer(epIn, csw, 13, timeout)
        if (r != 13) {
            runCatching { clearHalt(epIn) }
            r = connection.bulkTransfer(epIn, csw, 13, timeout)
            if (r != 13) {
                resetRecovery()
                logger?.invoke(S(R.string.csw_receive_failed, op))
                throw ScsiException(-1, 0, 0, S(R.string.usb_receive_failed_drive_may))
            }
        }
        if (ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN).int != 0x53425355) {
            resetRecovery()
            throw ScsiException(-1, 0, 0, S(R.string.invalid_usb_response))
        }
        val status = csw[12].toInt()
        if (status == 2) {
            resetRecovery()
            throw ScsiException(-1, 0, 0, S(R.string.usb_phase_error))
        }
        if (status != 0 || dataFail) {
            val s = try {
                execute(cdb(0x03, 0, 0, 0, 18, 0), 18, withSense = false).copyOf(18)
            } catch (e: Exception) {
                throw ScsiException(-1, 0, 0, S(R.string.scsi_command_failed))
            }
            val key = s[2].toInt() and 0x0F
            val asc = s[12].toInt() and 0xFF
            val ascq = s[13].toInt() and 0xFF
            val msg = S(R.string.scsi_error_key_asc).format(key, asc, ascq)
            logger?.invoke("$op: $msg")
            throw ScsiException(key, asc, ascq, msg)
        }
    }

    @Synchronized
    fun execute(cdb: ByteArray, inLen: Int, timeout: Int = 30000, withSense: Boolean = true): ByteArray {
        val data = ByteArray(inLen)
        val cbw = ByteArray(31)
        val bb = ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN)
        val t = tag++
        bb.putInt(0x43425355)
        bb.putInt(t)
        bb.putInt(inLen)
        bb.put(if (inLen > 0) 0x80.toByte() else 0.toByte())
        bb.put(0.toByte())
        bb.put(cdb.size.toByte())
        bb.put(cdb)
        val op = "cmd=0x%02X".format(cdb[0].toInt() and 0xFF)
        if (connection.bulkTransfer(epOut, cbw, 31, timeout) != 31) {
            resetRecovery()
            // 接続直後は1回目が失敗しやすいが自動で復旧するため、詳細ログのときだけ記録
            if (verbose) logger?.invoke(S(R.string.usb_send_failed, op))
            throw ScsiException(-1, 0, 0, S(R.string.usb_send_failed_2))
        }
        var got = 0
        var dataFail = false
        while (got < inLen) {
            val n = minOf(16384, inLen - got)
            val r = UsbCompat.bulk(connection, epIn, data, got, n, timeout)
            if (r < 0) {
                dataFail = true
                runCatching { clearHalt(epIn) }
                break
            }
            got += r
            if (r < n) break
        }
        val csw = ByteArray(13)
        var r = connection.bulkTransfer(epIn, csw, 13, timeout)
        if (r != 13) {
            runCatching { clearHalt(epIn) }
            r = connection.bulkTransfer(epIn, csw, 13, timeout)
            if (r != 13) {
                resetRecovery()
                logger?.invoke(S(R.string.csw_receive_failed, op))
                throw ScsiException(-1, 0, 0, S(R.string.usb_receive_failed_drive_may))
            }
        }
        if (ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN).int != 0x53425355) {
            resetRecovery()
            throw ScsiException(-1, 0, 0, S(R.string.invalid_usb_response))
        }
        val status = csw[12].toInt()
        if (status == 2) {
            resetRecovery()
            throw ScsiException(-1, 0, 0, S(R.string.usb_phase_error))
        }
        if (status != 0 || dataFail) {
            if (!withSense) throw ScsiException(-1, 0, 0, S(R.string.scsi_command_failed))
            val s = try {
                execute(cdb(0x03, 0, 0, 0, 18, 0), 18, withSense = false).copyOf(18)
            } catch (e: Exception) {
                throw ScsiException(-1, 0, 0, S(R.string.scsi_command_failed))
            }
            val key = s[2].toInt() and 0x0F
            val asc = s[12].toInt() and 0xFF
            val ascq = s[13].toInt() and 0xFF
            val msg = S(R.string.scsi_error_key_asc).format(key, asc, ascq)
            if (verbose) logger?.invoke("$op: $msg")
            throw ScsiException(key, asc, ascq, msg)
        }
        return if (got == inLen) data else data.copyOf(got)
    }

    fun inquiry(): String {
        val d = execute(cdb(0x12, 0, 0, 0, 36, 0), 36)
        if (d.size < 32) return name
        val type = d[0].toInt() and 0x1F
        return (String(d, 8, 8).trim() + " " + String(d, 16, 16).trim() + " (type=$type)").trim()
    }

    fun testUnitReady() { execute(cdb(0, 0, 0, 0, 0, 0), 0) }

    /** (総セクタ数, ブロックサイズ) */
    fun readCapacity(): Pair<Long, Int> {
        val d = execute(cdb(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), 8)
        return (be32(d, 0) + 1) to be32(d, 4).toInt()
    }

    fun currentProfile(): Int {
        val d = execute(cdb(0x46, 0x02, 0, 0, 0, 0, 0, 0, 8, 0), 8)
        if (d.size < 8) return 0
        return ((d[6].toInt() and 0xFF) shl 8) or (d[7].toInt() and 0xFF)
    }

    /** トラック一覧とリードアウトLBA。LBA形式で失敗したらMSF形式で再試行する。 */
    fun readToc(): Pair<List<TocTrack>, Int> {
        var msf = false
        val d = try {
            execute(cdb(0x43, 0, 0, 0, 0, 0, 1, 0x03, 0x24, 0), 804)
        } catch (e: ScsiException) {
            if (e.senseKey == -1) throw e
            msf = true
            execute(cdb(0x43, 0x02, 0, 0, 0, 0, 1, 0x03, 0x24, 0), 804)
        }
        if (d.size < 4) throw ScsiException(-1, 0, 0, S(R.string.toc_response_too_short))
        val len = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        val cnt = (len - 2) / 8
        val e = ArrayList<Triple<Int, Int, Int>>()
        for (i in 0 until cnt) {
            val o = 4 + i * 8
            if (o + 8 > d.size) break
            val lba = if (msf) {
                ((d[o + 5].toInt() and 0xFF) * 60 + (d[o + 6].toInt() and 0xFF)) * 75 +
                    (d[o + 7].toInt() and 0xFF) - 150
            } else be32(d, o + 4).toInt()
            e.add(Triple(d[o + 2].toInt() and 0xFF, d[o + 1].toInt() and 0x0F, lba))
        }
        val leadOut = e.firstOrNull { it.first == 0xAA }?.third ?: 0
        val real = e.filter { it.first in 1..99 }
        if (real.isEmpty()) throw ScsiException(-1, 0, 0, S(R.string.no_tracks_in_toc))
        val tracks = real.mapIndexed { i, t ->
            val audio = (t.second and 0x4) == 0
            val next = real.getOrNull(i + 1)
            var end = next?.third ?: leadOut
            if (audio && next != null && (next.second and 0x4) != 0) {
                end = (next.third - 11400).coerceAtLeast(t.third + 1)
            }
            TocTrack(t.first, t.third, end, audio)
        }
        return tracks to leadOut
    }

    /** speedKbps = 0 で最大速度 (1x = 176 kB/s) */
    fun setSpeed(speedKbps: Int) {
        val s = if (speedKbps <= 0) 0xFFFF else speedKbps.coerceAtMost(0xFFFF)
        execute(cdb(0xBB, 0, (s shr 8) and 0xFF, s and 0xFF, 0xFF, 0xFF, 0, 0, 0, 0, 0, 0), 0)
    }

    private fun readCdRaw(lba: Int, count: Int, c2: Boolean): ByteArray {
        val size = count * (if (c2) 2646 else 2352)
        return execute(
            cdb(
                0xBE, sectorType,
                (lba shr 24) and 0xFF, (lba shr 16) and 0xFF, (lba shr 8) and 0xFF, lba and 0xFF,
                (count shr 16) and 0xFF, (count shr 8) and 0xFF, count and 0xFF,
                if (c2) 0x12 else 0x10, 0, 0,
            ),
            size,
        )
    }

    fun readCd(lba: Int, count: Int, c2: Boolean): ByteArray {
        try {
            return readCdRaw(lba, count, c2)
        } catch (e: ScsiException) {
            // セクタータイプ指定(CD-DA)を受け付けないドライブ向け: タイプ指定なしで再試行
            if (e.senseKey == 5 && e.asc == 0x24 && sectorType != 0) {
                sectorType = 0
                logger?.invoke(S(R.string.read_cd_switched_to_no))
                return readCdRaw(lba, count, c2)
            }
            throw e
        }
    }

    fun read10(lba: Long, count: Int, block: Int): ByteArray = execute(
        cdb(
            0x28, 0,
            ((lba shr 24) and 0xFF).toInt(), ((lba shr 16) and 0xFF).toInt(),
            ((lba shr 8) and 0xFF).toInt(), (lba and 0xFF).toInt(),
            0, (count shr 8) and 0xFF, count and 0xFF, 0,
        ),
        count * block,
    )

    // ---- 書き込み(CD-R / CD-RW) ----

    /** ディスクの状態(READ DISC INFORMATION) */
    class DiscInfo(
        /** 0 = 空、1 = 追記できる、2 = 書き込み済み(閉じている) */
        val status: Int,
        /** 消去できる(CD-RW) */
        val erasable: Boolean,
        /** 書き込める最後の位置(リードアウトの始まりの LBA。分からなければ 0) */
        val lastLeadOut: Int,
    )

    fun readDiscInfo(): DiscInfo {
        val d = execute(cdb(0x51, 0, 0, 0, 0, 0, 0, 0, 34, 0), 34)
        if (d.size < 24) throw ScsiException(-1, 0, 0, S(R.string.disc_information_response_too_short))
        val status = d[2].toInt() and 0x03
        val erasable = (d[2].toInt() and 0x10) != 0
        // 20〜23: 書き込める最後のリードアウトの位置(MSF)
        val m = d[21].toInt() and 0xFF
        val s = d[22].toInt() and 0xFF
        val f = d[23].toInt() and 0xFF
        val lo = if (m == 0xFF && s == 0xFF && f == 0xFF) 0 else (m * 60 + s) * 75 + f - 150
        return DiscInfo(status, erasable, lo)
    }

    /** 書き込みの条件(モードページ 05h)を設定する */
    fun setWriteParameters(writeType: Int, testWrite: Boolean, multiSession: Boolean = false) {
        val sense = execute(cdb(0x5A, 0x08, 0x05, 0, 0, 0, 0, 0, 0xFF, 0), 0xFF)
        if (sense.size < 8 + 16) throw ScsiException(-1, 0, 0, S(R.string.cannot_read_write_parameters))
        val bdl = ((sense[6].toInt() and 0xFF) shl 8) or (sense[7].toInt() and 0xFF)
        val p = 8 + bdl
        // ページ 05h が返らない(書き込みに非対応の)ドライブでは、範囲の外を読まないように
        if (p + 2 > sense.size || (sense[p].toInt() and 0x3F) != 0x05) {
            throw ScsiException(-1, 0, 0, S(R.string.cannot_read_write_parameters))
        }
        val plen = (sense[p + 1].toInt() and 0xFF) + 2
        val page = sense.copyOfRange(p, minOf(sense.size, p + plen))
        if (page.size < 16) throw ScsiException(-1, 0, 0, S(R.string.cannot_read_write_parameters))
        page[0] = (page[0].toInt() and 0x3F).toByte() // PS は送り返さない
        // 2: BUFE(バッファーアンダーラン防止)・テスト書き込み・書き込み方式
        page[2] = ((0x40) or (if (testWrite) 0x10 else 0) or (writeType and 0x0F)).toByte()
        // 3: マルチセッション・トラックモード(0 = 2ch オーディオ、プリエンファシスなし)
        page[3] = ((if (multiSession) 0xC0 else 0) or 0x00).toByte()
        // 4: データブロックの形式(0 = 生の 2352 バイト)
        page[4] = 0
        page[8] = 0 // セッションの形式: CD-DA / CD-ROM
        page[14] = 0; page[15] = 150.toByte() // トラック間の無音(TAO): 2 秒
        val data = ByteArray(8) + page
        executeOut(cdb(0x55, 0x10, 0, 0, 0, 0, 0, (data.size shr 8) and 0xFF, data.size and 0xFF, 0), data)
    }

    /** 書き込みの速さ(kB/s。0 で最大)。読み込みは最大のまま */
    fun setWriteSpeed(writeKbps: Int) {
        val w = if (writeKbps <= 0) 0xFFFF else writeKbps.coerceAtMost(0xFFFF)
        execute(cdb(0xBB, 0, 0xFF, 0xFF, (w shr 8) and 0xFF, w and 0xFF, 0, 0, 0, 0, 0, 0), 0)
    }

    /** SAO の CUE シートを送る */
    fun sendCueSheet(cue: ByteArray) {
        executeOut(cdb(0x5D, 0, 0, 0, 0, 0, (cue.size shr 16) and 0xFF, (cue.size shr 8) and 0xFF, cue.size and 0xFF, 0), cue)
    }

    /** 次に書き込める位置(TAO。まだ書いていないトラックの READ TRACK INFORMATION) */
    fun nextWritableAddress(): Int {
        val d = execute(cdb(0x52, 0x01, 0, 0, 0, 0xFF, 0, 0, 36, 0), 36)
        if (d.size < 16) throw ScsiException(-1, 0, 0, S(R.string.track_information_response_too_short))
        val nwaValid = (d[7].toInt() and 0x01) != 0
        if (!nwaValid) throw ScsiException(-1, 0, 0, S(R.string.no_writable_address_found))
        return be32(d, 12).toInt()
    }

    /** 2352 バイトのセクターを count 個書き込む(lba は負でもよい: SAO の最初の無音) */
    fun write10(lba: Int, data: ByteArray, off: Int, count: Int) {
        executeOut(
            cdb(
                0x2A, 0,
                (lba shr 24) and 0xFF, (lba shr 16) and 0xFF, (lba shr 8) and 0xFF, lba and 0xFF,
                0, (count shr 8) and 0xFF, count and 0xFF, 0,
            ),
            data, off, count * 2352,
        )
    }

    /** ドライブのバッファーの中身をディスクに書き終える */
    fun synchronizeCache() {
        execute(cdb(0x35, 0x02, 0, 0, 0, 0, 0, 0, 0, 0), 0, timeout = 600_000)
    }

    /** TAO: トラック(func=1)・セッション(func=2)を閉じる */
    fun closeTrackSession(func: Int, track: Int = 0) {
        execute(cdb(0x5B, 0x01, func and 0x07, 0, (track shr 8) and 0xFF, track and 0xFF, 0, 0, 0, 0), 0, timeout = 600_000)
    }

    /** CD-RW の消去(minimal = 目次だけ消す速い消去) */
    fun blank(minimal: Boolean) {
        execute(cdb(0xA1, 0x10 or (if (minimal) 0x01 else 0x00), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0, timeout = 600_000)
    }

    fun eject() {
        runCatching { execute(cdb(0x1E, 0, 0, 0, 0, 0), 0) }
        execute(cdb(0x1B, 0, 0, 0, 0x02, 0), 0)
    }
}
