package com.uyatame.ucrt.player.dar

import android.content.Context
import android.media.AudioAttributes
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.player.AudioEngine
import com.uyatame.ucrt.player.AudioOutput
import com.uyatame.ucrt.player.ParamEqualizer
import com.uyatame.ucrt.player.PcmOut
import com.uyatame.ucrt.player.dsd.DopSink
import com.uyatame.ucrt.player.dsd.DsdSink
import com.uyatame.ucrt.player.dsd.NativeDsdSink
import com.uyatame.ucrt.player.pcmBytes
import com.uyatame.ucrt.player.PcmToFloat
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
    /** DSD の周波数 */
    dsdRate: Int,
    align: Int,
    /** DSD64 なら 64 など(処理が追いつかないときに下げるため) */
    private val mult: Int,
) : PcmOut {
    @Volatile override var framesWritten = 0L
        private set
    override val failed: Boolean get() = sink.failed

    /** DSD の周波数 ÷ 入力の周波数(48 kHz 系の音源では整数にならない) */
    private val ratio = dsdRate.toDouble() / sampleRate

    @Volatile private var released = false
    /** 変調が追いつかない状態が続いた回数(約 2 秒ごとに数える) */
    private var overload = 0

    /** 変換(複数のコアで流れ作業)。出来た DSD は変換のスレッドから出力先へ送る */
    private val conv = PcmToDsd(
        sampleRate, dsdRate, align,
        out = { bytes, n -> sink.write(bytes, n) { released } },
        onLoad = { load -> checkLoad(load) },
    )

    private var fbuf = FloatArray(0)
    private val toFloat = PcmToFloat()
    /** イコライザー(ソースダイレクトがオフで、イコライザーを使うとき。DSD にする前に掛ける) */
    private val eq = ParamEqualizer(sampleRate, OUT_CH)

    override fun headFrames(): Long {
        val sh = sink.headFrames()
        // 出力が鳴らし終えていれば、書いた分はすべて再生済み(端数で終わりを待ち続けないように)
        if (sh >= sink.framesWritten) return framesWritten
        val bits = sh * sink.bytesPerFrame * 8
        return minOf(framesWritten, (bits / ratio).toLong())
    }

    override fun play() = sink.play()
    override fun pause() = sink.pause()
    override fun release() {
        released = true
        com.uyatame.ucrt.player.SpectrumTap.forget(this)
        conv.close()
        sink.release()
    }

    /**
     * 変調の忙しさを見て、この端末では追いつかない(音が途切れ続ける)と分かったら、
     * DSD の周波数を 1 段下げて出力を作り直す(途切れ続けるよりはよいので)
     */
    private fun checkLoad(load: Double) {
        if (load > 0.97) overload++ else overload = 0
        if (overload < 3 || mult <= 64 || released) return
        overload = 0
        val lower = mult / 2
        AudioEngine.darDsdLimit = lower
        AudioEngine.log("dar: DSD$mult conversion is too slow on this device (load %.2f), switching to DSD$lower".format(java.util.Locale.US, load))
        AudioEngine.report(
            false,
            S(R.string.dar_switched_to_dsd_this, lower, mult),
        )
        UsbDac.onChanged?.invoke()
    }

    override fun write(src: ByteBuffer, srcEnc0: Int, stop: () -> Boolean) {
        src.order(ByteOrder.LITTLE_ENDIAN)
        var enc = srcEnc0
        if (pcmBytes(enc) == 0) enc = android.media.AudioFormat.ENCODING_PCM_16BIT
        val frames = src.remaining() / (pcmBytes(enc) * srcChannels)
        if (frames <= 0) return
        // スペクトラムアナライザー(DSD に変換する前の音を写す)
        if (com.uyatame.ucrt.player.SpectrumTap.enabled) {
            com.uyatame.ucrt.player.SpectrumTap.submit(this, src, enc, frames, srcChannels, sampleRate, framesWritten)
        }
        framesWritten += frames
        // DSD の出力先は 2ch で開くので、モノラルは左右に同じ音を入れる
        val samples = frames * OUT_CH
        if (fbuf.size < samples) fbuf = FloatArray(samples)
        toFloat.read(src, enc, frames, srcChannels, OUT_CH, fbuf)
        // DAC 本体で音量を変えられないときは、ソフトウェアの音量を変換の前に掛ける
        var gain = UsbDac.softGain.toFloat()
        if (AudioEngine.eqEnabled) {
            // 番号を先に読む(読むあいだに設定が変わっても、古い帯域のまま新しい番号を覚えてしまわないように)
            val v = AudioEngine.version
            if (eq.version != v) {
                eq.update(AudioEngine.bands)
                eq.version = v
            }
            eq.process(fbuf, samples)
            gain *= AudioEngine.preampGain()
        }
        conv.submit(fbuf, frames, gain, stop)
    }

    companion object {
        private const val OUT_CH = 2

        /**
         * 変換して出せる DSD の出力先を開く。設定の周波数から順に、DAC が受け付けるものを探す。
         * 使えなければ null(呼び出し側は PCM のまま出力する)
         */
        fun open(ctx: Context, attrs: AudioAttributes, srcRate: Int, srcChannels: Int): DsdConvertOutput? {
            if (srcChannels !in 1..2) return null
            if (srcRate % 44100 != 0 && srcRate % 48000 != 0) return null
            // DSD は初めは 44.1 kHz 系(DSD64 = 2.8224 MHz など)にする。48 kHz 系の DSD(DSD64 = 3.072 MHz など)は
            // 対応していない DAC が多く、周波数の設定は受け付けてもノイズになるため(48 kHz の WAV・MPEG-H などで起きていた)。
            // 48 kHz 系の音源は、変換の途中の直線補間で 44.1 kHz 系に合わせる。
            // 設定でオンにしたときは、48 kHz 系の音源を 48 kHz 系の DSD のまま送る(対応している DAC 向け。整数倍で変換できる)
            val family = if (AudioEngine.darDsd48 && srcRate % 48000 == 0) 48000 else 44100
            val limit = AudioEngine.darDsdLimit
            val mults = when (AudioEngine.darDsd) {
                2 -> listOf(256, 128, 64)
                1 -> listOf(128, 64)
                else -> listOf(64)
            }.filter { limit <= 0 || it <= limit }.ifEmpty { listOf(64) }
            for (m in mults) {
                val dsdRate = family * m
                val ratio = dsdRate.toDouble() / srcRate
                if (ratio < 2) continue
                val sink = openSink(ctx, attrs, dsdRate) ?: continue
                // ネイティブ DSD は 4 バイト、DoP は 2 バイト単位で送る
                val align = if (sink.label == "DoP") 2 else 4
                val how = if (sink.label == "DoP") "DoP" else S(R.string.native_dsd)
                AudioEngine.report(
                    false,
                    S(R.string.dar_dsd_conversion, m) + " · ${AudioEngine.khz(srcRate)} → ${"%.4f".format(java.util.Locale.US, dsdRate / 1e6)} MHz ($how)",
                    "DAR · DSD$m ($how) · ${AudioEngine.khz(srcRate)} → ${"%.4f".format(java.util.Locale.US, dsdRate / 1e6)} MHz · " +
                        AudioEngine.bitrateText(dsdRate.toLong() * OUT_CH),
                )
                AudioEngine.log("dar: pcm ${AudioEngine.khz(srcRate)} ${srcChannels}ch -> DSD$m ($dsdRate Hz, x${"%.4g".format(java.util.Locale.US, ratio)}) via $how")
                return DsdConvertOutput(srcRate, srcChannels, sink, dsdRate, align, m)
            }
            return null
        }

        /** DSD の出力先(ネイティブ DSD → DoP の順)。DSD の再生方法の設定に従う */
        private fun openSink(ctx: Context, attrs: AudioAttributes, dsdRate: Int): DsdSink? {
            val mode = AudioEngine.dsdMode
            // 「常に PCM に変換」のときは DSD で送らない
            if (mode == 2) return null
            if (mode == 0) {
                val f = runCatching { UsbDac.prepareDsd(dsdRate, OUT_CH, softGain = true) }.getOrNull()
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
