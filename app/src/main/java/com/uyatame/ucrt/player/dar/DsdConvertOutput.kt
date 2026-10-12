package com.uyatame.ucrt.player.dar

import android.content.Context
import android.media.AudioAttributes
import com.uyatame.ucrt.T
import com.uyatame.ucrt.player.AudioEngine
import com.uyatame.ucrt.player.AudioOutput
import com.uyatame.ucrt.player.PcmOut
import com.uyatame.ucrt.player.dsd.DopSink
import com.uyatame.ucrt.player.dsd.DsdSink
import com.uyatame.ucrt.player.dsd.NativeDsdSink
import com.uyatame.ucrt.player.pcmBytes
import com.uyatame.ucrt.player.readI32
import com.uyatame.ucrt.player.usb.UsbDac
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DAR の DSD 変換: PCM を DSD に変換し、USB DAC へネイティブ DSD か DoP で送る。
 * 再生側からは普通の PCM の出力と同じに見える(位置は入力の周波数で数える)。
 */
internal class DsdConvertOutput private constructor(
    override val sampleRate: Int,
    private val srcChannels: Int,
    private val sink: DsdSink,
    private val conv: PcmToDsd,
    /** DSD の周波数 ÷ 入力の周波数 */
    private val ratio: Int,
) : PcmOut {
    @Volatile override var framesWritten = 0L
        private set
    override val failed: Boolean get() = sink.failed

    private var fbuf = FloatArray(0)
    private var out = Array(OUT_CH) { ByteArray(0) }

    override fun headFrames(): Long {
        val sh = sink.headFrames()
        // 出力が鳴らし終えていれば、書いた分はすべて再生済み(端数で終わりを待ち続けないように)
        if (sh >= sink.framesWritten) return framesWritten
        val bits = sh * sink.bytesPerFrame * 8
        return minOf(framesWritten, (bits / ratio).toLong())
    }

    override fun play() = sink.play()
    override fun pause() = sink.pause()
    override fun release() = sink.release()

    override fun write(src: ByteBuffer, srcEnc0: Int, stop: () -> Boolean) {
        src.order(ByteOrder.LITTLE_ENDIAN)
        var enc = srcEnc0
        if (pcmBytes(enc) == 0) enc = android.media.AudioFormat.ENCODING_PCM_16BIT
        val frames = src.remaining() / (pcmBytes(enc) * srcChannels)
        if (frames <= 0) return
        framesWritten += frames
        // DSD の出力先は 2ch で開くので、モノラルは左右に同じ音を入れる
        val samples = frames * OUT_CH
        if (fbuf.size < samples) fbuf = FloatArray(samples)
        var k = 0
        for (f in 0 until frames) {
            val l = readI32(src, enc) / 2147483648f
            val r = if (srcChannels > 1) readI32(src, enc) / 2147483648f else l
            // 3ch 以上は来ない(呼び出し側で除外)が、念のため残りは読み飛ばす
            for (c in 2 until srcChannels) readI32(src, enc)
            fbuf[k++] = l
            fbuf[k++] = r
        }
        val need = conv.maxOut(frames)
        if (out[0].size < need) out = Array(OUT_CH) { ByteArray(need) }
        // DAC 本体で音量を変えられないときは、ソフトウェアの音量を変換の前に掛ける
        val n = conv.process(fbuf, frames, UsbDac.softGain.toFloat(), out)
        if (n > 0) sink.write(out, n, stop)
    }

    companion object {
        private const val OUT_CH = 2

        /**
         * 変換して出せる DSD の出力先を開く。設定の周波数から順に、DAC が受け付けるものを探す。
         * 使えなければ null(呼び出し側は PCM のまま出力する)
         */
        fun open(ctx: Context, attrs: AudioAttributes, srcRate: Int, srcChannels: Int): DsdConvertOutput? {
            if (srcChannels !in 1..2) return null
            val family = when {
                srcRate % 44100 == 0 -> 44100
                srcRate % 48000 == 0 -> 48000
                else -> return null
            }
            val mults = when (AudioEngine.darDsd) {
                2 -> listOf(256, 128, 64)
                1 -> listOf(128, 64)
                else -> listOf(64)
            }
            for (m in mults) {
                val dsdRate = family * m
                if (dsdRate % srcRate != 0) continue
                val ratio = dsdRate / srcRate
                if (ratio < 2) continue
                val sink = openSink(ctx, attrs, dsdRate) ?: continue
                // ネイティブ DSD は 4 バイト、DoP は 2 バイト単位で送る
                val align = if (sink.label == "DoP") 2 else 4
                val conv = PcmToDsd(srcRate, OUT_CH, ratio, align)
                val how = if (sink.label == "DoP") "DoP" else T("ネイティブ DSD", "Native DSD")
                AudioEngine.report(
                    false,
                    T("DAR: DSD$m に変換", "DAR: converted to DSD$m") + " · ${AudioEngine.khz(srcRate)} → ${"%.4f".format(java.util.Locale.US, dsdRate / 1e6)} MHz ($how)",
                )
                AudioEngine.log("dar: pcm ${AudioEngine.khz(srcRate)} ${srcChannels}ch -> DSD$m ($dsdRate Hz, x$ratio) via $how")
                return DsdConvertOutput(srcRate, srcChannels, sink, conv, ratio)
            }
            return null
        }

        /** DSD の出力先(ネイティブ DSD → DoP の順)。DSD の再生方法の設定に従う */
        private fun openSink(ctx: Context, attrs: AudioAttributes, dsdRate: Int): DsdSink? {
            val mode = AudioEngine.dsdMode
            // 「常に PCM に変換」のときは DSD で送らない
            if (mode == 2) return null
            if (mode == 0) {
                val f = runCatching { UsbDac.prepareDsd(dsdRate, OUT_CH) }.getOrNull()
                if (f != null) return NativeDsdSink(f, OUT_CH, AudioEngine.dsdSwap)
            }
            if (dsdRate % 16 == 0) {
                val o = runCatching { AudioOutput(ctx, attrs, dsdRate / 16, OUT_CH, 24, exact = true) }.getOrNull()
                if (o != null && o.exactOk) return DopSink(o, OUT_CH)
                o?.release()
            }
            return null
        }
    }
}
