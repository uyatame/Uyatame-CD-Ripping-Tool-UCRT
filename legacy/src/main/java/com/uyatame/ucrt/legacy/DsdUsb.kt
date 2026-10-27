package com.uyatame.ucrt.legacy

import android.content.Context
import android.net.Uri
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.usb.DacFormat
import com.uyatame.ucrt.legacy.usb.UsbDac
import com.uyatame.ucrt.player.dsd.DsdFile
import com.uyatame.ucrt.player.dsd.DsdInfo
import com.uyatame.ucrt.player.dsd.DsdReader
import com.uyatame.ucrt.player.dsd.RandomFile

/**
 * DSD の曲を、USB DAC に DSD のまま送る(本家の DsdStream の NativeDsdSink・DopSink)。
 * ネイティブ DSD → DoP の順に試す。どちらも使えなければ null(ふつうの再生で PCM にする)
 */
internal class DsdUsb private constructor(
    private val rf: RandomFile,
    private val info: DsdInfo,
    private val reader: DsdReader,
    val fmt: DacFormat,
    /** true = ネイティブ DSD、false = DoP */
    private val native: Boolean,
    private val swap: Boolean,
    /** 始めた位置(ミリ秒) */
    val startMs: Long,
) {
    private val ch = info.channels.coerceAtMost(2)
    private val chunk = 8192
    private val bufs = Array(info.channels) { ByteArray(chunk) }
    private var out = ByteArray(0)
    private var marker = false

    val durationMs: Long get() = info.durationMs
    val label: String get() = info.label() + " → USB DAC " + (if (native) S(R.string.native_dsd) else "DoP")

    /** 1 回分を送る。送ったフレーム数(終わりなら 0、止められたら -1) */
    fun step(session: Int, stop: () -> Boolean): Int {
        val n = reader.read(bufs, chunk)
        if (n <= 0) return 0
        return if (native) writeNative(session, n, stop) else writeDop(session, n, stop)
    }

    private fun writeNative(session: Int, n: Int, stop: () -> Boolean): Int {
        val slot = fmt.subslot
        val frames = n / slot
        if (frames <= 0) return 0
        val need = frames * slot * fmt.channels
        if (out.size < need) out = ByteArray(need)
        var o = 0
        for (f in 0 until frames) {
            val base = f * slot
            for (c in 0 until fmt.channels) {
                val src = bufs[if (c < ch) c else 0]
                if (swap) for (k in slot - 1 downTo 0) out[o++] = src[base + k]
                else for (k in 0 until slot) out[o++] = src[base + k]
            }
        }
        return if (UsbDac.write(session, out, o, stop)) frames else -1
    }

    private fun writeDop(session: Int, n: Int, stop: () -> Boolean): Int {
        val frames = n / 2
        if (frames <= 0) return 0
        val slot = fmt.subslot
        val need = frames * slot * fmt.channels
        if (out.size < need) out = ByteArray(need)
        val pad = slot - 3
        var o = 0
        for (f in 0 until frames) {
            val m: Byte = if (marker) 0xFA.toByte() else 0x05
            marker = !marker
            for (c in 0 until fmt.channels) {
                val src = bufs[if (c < ch) c else 0]
                // 24bit を上に詰める(下位の余りは 0): 下位 = 後のバイト、中位 = 先のバイト、上位 = 目印
                for (k in 0 until pad) out[o++] = 0
                out[o++] = src[f * 2 + 1]
                out[o++] = src[f * 2]
                out[o++] = m
            }
        }
        return if (UsbDac.write(session, out, o, stop)) frames else -1
    }

    fun close() { runCatching { rf.close() } }

    companion object {
        /** mode: 0 = 自動(ネイティブ DSD → DoP)、1 = DoP。使えなければ null */
        fun open(ctx: Context, uri: Uri, fromMs: Long, mode: Int, swap: Boolean): DsdUsb? {
            val rf = RandomFile.open(ctx, uri) ?: return null
            try {
                val info = DsdFile.parse(rf)
                if (info.compressed) { rf.close(); return null }
                val ch = info.channels.coerceAtMost(2)
                var fmt: DacFormat? = null
                var native = false
                if (mode == 0) {
                    fmt = runCatching { UsbDac.prepareDsd(info.rate, ch) }.getOrNull()
                    native = fmt != null
                }
                if (fmt == null && info.rate % 16 == 0) {
                    fmt = runCatching { UsbDac.prepare(info.rate / 16, ch, 24, exact = true) }.getOrNull()
                    if (fmt == null) AppLog.log("dsd: DoP not available at ${info.rate / 16} Hz")
                }
                if (fmt == null) { rf.close(); return null }
                val reader = DsdReader(rf, info)
                val start = (fromMs * info.rate / 8000L) and 7L.inv()
                reader.seek(start)
                val at = start * 8000L / info.rate
                AppLog.log("dsd: ${info.label()} ${info.channels}ch ${if (info.dff) "DFF" else "DSF"} -> ${if (native) "native DSD" else "DoP"}")
                if (!UsbDac.hwVolume) AppLog.log(S(R.string.dsd_plays_at_full_volume))
                return DsdUsb(rf, info, reader, fmt, native, swap, at)
            } catch (e: Exception) {
                rf.close()
                AppLog.log("dsd: ${e.message}")
                return null
            }
        }
    }
}
