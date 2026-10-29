package com.uyatame.ucrt.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import android.media.AudioFormat as AFormat

/**
 * 再生の音響設定(イコライザー・ビットパーフェクト)と、その時点の状態を持つ。
 * 設定は画面から変更され、再生中の出力にすぐ反映される。
 */
object AudioEngine {
    @Volatile var eqEnabled = false
        private set
    /** イコライザーの帯域(パラメトリック EQ) */
    @Volatile var bands: List<EqBand> = emptyList()
        private set
    /** 帯域を合わせた、いちばん持ち上がっているところの dB(自動で音量を下げる分) */
    @Volatile private var maxBoost = 0f
    @Volatile var preampDb = 0f
        private set
    @Volatile var autoPreamp = true
        private set
    /** イコライザー設定が変わるたびに増える(出力側が係数を作り直す合図) */
    @Volatile var version = 0
        private set

    /**
     * アプリの中の音量(倍率。通常の出力だけ。1 = そのまま)。設定の「細かい音量」をオンにすると、再生画面の音量で
     * 端末の音量より細かく(0.5 dB ごと)変えられる
     */
    @Volatile var appVolume = 1f

    /** 0〜200 の段階(200 = 0 dB、1 段 0.5 dB。0 は消音) → 倍率 */
    fun appVolumeOf(step: Int): Float = if (step <= 0) 0f else Math.pow(10.0, (step - 200) * 0.5 / 20).toFloat()

    /** 画面表示用: ビットパーフェクトで出力中か */
    var bitPerfectActive by mutableStateOf(false)
        private set
    /** 画面表示用: 出力の状態の説明 */
    var outputStatus by mutableStateOf("")
        private set
    /** 画面表示用: DAR が効いているときの中身(どう変換して、実際に何で送っているか)。効いていなければ "" */
    var darInfo by mutableStateOf("")
        private set
    /** 画面表示用: DAR が設定どおりにできなかったときの注意(赤字で出す)。なければ "" */
    var darWarn by mutableStateOf("")
        private set


    fun setEq(enabled: Boolean, b: List<EqBand>, preamp: Float, auto: Boolean) {
        if (b != bands) maxBoost = ParamEq.maxBoostDb(b)
        eqEnabled = enabled
        bands = b.toList()
        preampDb = preamp
        autoPreamp = auto
        version++
    }

    /** USB DAC を独自ドライバーで直接動かすか */
    @Volatile var wantUsbDirect = false
        private set

    /** 出力方式: 0 = 通常, 2 = ビットパーフェクト(USB DAC 直接出力) */
    fun setOutputMode(mode: Int) {
        wantUsbDirect = mode == 2
    }

    /** DSD の出力方法: 0 = 自動(ネイティブ → DoP → PCM 変換), 1 = DoP を使う(ネイティブを使わない), 2 = 常に PCM に変換 */
    @Volatile var dsdMode = 0
    /** ネイティブ DSD のバイト順を逆にする(DAC によって必要) */
    @Volatile var dsdSwap = false

    /** DAR: 0 = オフ, 1 = PCM アップサンプリング, 2 = DSD に変換(USB DAC 直接出力のときだけ使う) */
    @Volatile var darMode = 0
    /** DAR の PCM アップサンプリングの上限: 0 = DAC の最大, 1 = 2 倍, 2 = 4 倍, 3 = 8 倍, 4 = 16 倍(44.1 / 48 kHz に対して) */
    @Volatile var darPcm = 0
    /** DAR の DSD 変換の周波数: 0 = DSD64, 1 = DSD128, 2 = DSD256 */
    @Volatile var darDsd = 0
    /** この端末で間に合う DSD 変換の上限(DSD64 なら 64。0 なら制限なし。変換が追いつかなかったときに下げる) */
    @Volatile var darDsdLimit = 0
    /** DAR の PCM アップサンプリングを整数倍だけで行うか(通常の出力) */
    @Volatile var darInteger = true

    /** 通常の出力で、その周波数の AudioTrack を作れるか(出力先の id と周波数ごとに、試した結果を覚えておく) */
    internal val rateProbe = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** DAR の PCM アップサンプリングで目指す周波数の上限(DAR を使わないなら 0) */
    fun darMaxRate(srcRate: Int): Int {
        if (darMode != 1) return 0
        val mul = intArrayOf(0, 2, 4, 8, 16).getOrElse(darPcm) { 0 }
        if (mul == 0) return Int.MAX_VALUE
        val family = when {
            srcRate % 44100 == 0 -> 44100
            srcRate % 48000 == 0 -> 48000
            else -> srcRate
        }
        return family * mul
    }

    /** 選ばれている出力先(OutputDevices の key。空なら自動) */
    @Volatile var preferredKey = ""

    /** 画面表示用: 今の出力先 */
    var output by mutableStateOf<OutputDevice?>(null)
        private set

    internal fun setOutput(d: OutputDevice?) {
        main.post { output = d }
    }

    /** 音量キーなどで DAC の音量が変わったときに呼ばれる(設定に保存するため) */
    @Volatile var onUsbVolume: ((Int) -> Unit)? = null

    /** クリップを防ぐために自動で下げる分も含めた、全体の音量(倍率) */
    fun preampGain(): Float {
        val auto = if (autoPreamp) max(0f, maxBoost) else 0f
        return 10.0.pow((preampDb - auto) / 20.0).toFloat()
    }


    /** アプリのログへの出力先(MainViewModel が設定する) */
    @Volatile var logger: ((String) -> Unit)? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    internal fun log(msg: String) {
        val l = logger ?: return
        main.post { runCatching { l("[audio] $msg") } }
    }

    internal fun report(active: Boolean, status: String, dar: String = "", warn: String = "") {
        main.post {
            bitPerfectActive = active
            outputStatus = status
            darInfo = dar
            darWarn = warn
        }
    }

    /** ビットレートの表示(1 秒あたりのビット数から。10 Mbps 以上は Mbps で) */
    fun bitrateText(bitsPerSec: Long): String =
        if (bitsPerSec >= 10_000_000) "%.1f Mbps".format(java.util.Locale.US, bitsPerSec / 1e6)
        else "${bitsPerSec / 1000} kbps"

    fun encName(enc: Int): String = when (enc) {
        AFormat.ENCODING_PCM_8BIT -> "8bit"
        AFormat.ENCODING_PCM_16BIT -> "16bit"
        AFormat.ENCODING_PCM_24BIT_PACKED -> "24bit"
        AFormat.ENCODING_PCM_32BIT -> "32bit"
        AFormat.ENCODING_PCM_FLOAT -> "float"
        31 -> "DSD" // AudioFormat.ENCODING_DSD(新しい Android にだけある)
        else -> "enc$enc"
    }

    /** 出力をすべて手放す(再生終了時) */
    fun release(am: AudioManager, attrs: AudioAttributes) {
        closeUsb()
        report(false, "")
        setOutput(null)
    }

    /** 今の出力方式で使わないものだけを手放す */
    fun releaseUnused(am: AudioManager, attrs: AudioAttributes) {
        if (!wantUsbDirect) closeUsb()
    }

    private fun closeUsb() {
        val dac = com.uyatame.ucrt.player.usb.UsbDac
        if (dac.active) {
            // 閉じている間に開き直された場合は閉じない
            val gen = dac.openGen
            Thread({ dac.closeIfGen(gen) }, "usb-dac-close").start()
        }
    }

    fun khz(hz: Int) = when {
        hz <= 0 -> "? kHz"
        hz % 1000 == 0 -> "${hz / 1000} kHz"
        else -> "%.1f kHz".format(java.util.Locale.US, hz / 1000.0)
    }
}

/** PCM エンコーディングごとの 1 サンプルのバイト数(扱えないものは 0) */
fun pcmBytes(enc: Int): Int = when (enc) {
    AFormat.ENCODING_PCM_8BIT -> 1
    AFormat.ENCODING_PCM_16BIT -> 2
    AFormat.ENCODING_PCM_24BIT_PACKED -> 3
    AFormat.ENCODING_PCM_32BIT, AFormat.ENCODING_PCM_FLOAT -> 4
    else -> 0
}

/** PCM の出力先(AudioOutput と、DAR の DSD 変換の出力で共通) */
interface PcmOut {
    /** 入力の周波数(再生位置の計算に使う) */
    val sampleRate: Int
    /** 書き込んだフレーム数(入力の周波数で数える) */
    val framesWritten: Long
    /** 出力に書き込めなくなったか(作り直しが必要) */
    val failed: Boolean
    /** 再生し終えたフレーム数(入力の周波数で数える) */
    fun headFrames(): Long
    fun play()
    fun pause()
    fun release()
    /** src(srcEnc の音声)を書き込む。一時停止中は書き込めるまで待つ。stop() が true を返したら途中でやめる */
    fun write(src: ByteBuffer, srcEnc0: Int, stop: () -> Boolean)
}

/** 1 サンプルを 32bit 整数(上位詰め)で読む */
internal fun readI32(src: ByteBuffer, enc: Int): Int = when (enc) {
    AFormat.ENCODING_PCM_8BIT -> ((src.get().toInt() and 0xFF) - 128) shl 24
    AFormat.ENCODING_PCM_16BIT -> src.short.toInt() shl 16
    AFormat.ENCODING_PCM_24BIT_PACKED -> {
        val b0 = src.get().toInt() and 0xFF
        val b1 = src.get().toInt() and 0xFF
        val b2 = src.get().toInt()
        ((b2 shl 16) or (b1 shl 8) or b0) shl 8
    }
    AFormat.ENCODING_PCM_32BIT -> src.int
    else -> f2i(src.float)
}

/**
 * PCM を float にまとめて変換する(サンプルごとに形式を判定しないので速い)。
 * 結果はチャンネル交互で outCh チャンネル(入力に無いチャンネルは 1 チャンネル目を写す)。
 * 値は以前の 1 サンプルずつの変換(readI32 / 2^31)と同じになる
 */
internal class PcmToFloat {
    private var bytes = ByteArray(0)

    fun read(src: ByteBuffer, enc: Int, frames: Int, ch: Int, outCh: Int, out: FloatArray) {
        val bps = pcmBytes(enc).takeIf { it > 0 } ?: 2
        val n = frames * ch * bps
        if (bytes.size < n) bytes = ByteArray(n)
        val b = bytes
        src.get(b, 0, n)
        val samples = frames * ch
        // まず入力のチャンネル数のまま、形式ごとの専用ループで変換する
        val dst = if (ch == outCh) out else {
            if (tmp.size < samples) tmp = FloatArray(samples)
            tmp
        }
        val s = 1f / 2147483648f
        var p = 0
        when (if (bps == 2 && enc != AFormat.ENCODING_PCM_16BIT) AFormat.ENCODING_PCM_16BIT else enc) {
            AFormat.ENCODING_PCM_24BIT_PACKED -> for (i in 0 until samples) {
                dst[i] = (((b[p + 2].toInt() shl 16) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p].toInt() and 0xFF)) shl 8) * s
                p += 3
            }
            AFormat.ENCODING_PCM_32BIT -> for (i in 0 until samples) {
                dst[i] = ((b[p + 3].toInt() shl 24) or ((b[p + 2].toInt() and 0xFF) shl 16) or
                    ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p].toInt() and 0xFF)) * s
                p += 4
            }
            AFormat.ENCODING_PCM_FLOAT -> for (i in 0 until samples) {
                val x = java.lang.Float.intBitsToFloat(
                    (b[p + 3].toInt() shl 24) or ((b[p + 2].toInt() and 0xFF) shl 16) or
                        ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p].toInt() and 0xFF),
                )
                dst[i] = if (x > 1f) 1f else if (x < -1f) -1f else if (x.isNaN()) 0f else x
                p += 4
            }
            AFormat.ENCODING_PCM_8BIT -> for (i in 0 until samples) {
                dst[i] = (((b[p].toInt() and 0xFF) - 128) shl 24) * s
                p += 1
            }
            else -> for (i in 0 until samples) {
                dst[i] = (((b[p + 1].toInt() shl 8) or (b[p].toInt() and 0xFF)) shl 16) * s
                p += 2
            }
        }
        if (ch == outCh) return
        // チャンネル数を合わせる(足りないチャンネルは 1 チャンネル目を写し、多い分は捨てる)
        var i = 0
        var o = 0
        for (f in 0 until frames) {
            for (c in 0 until outCh) out[o + c] = dst[i + if (c < ch) c else 0]
            i += ch
            o += outCh
        }
    }

    private var tmp = FloatArray(0)
}

/**
 * float(-1〜1)を、DAC に送るサンプル(slot バイト、リトルエンディアン、上位詰め)にまとめて書く。
 * 値は以前の 1 サンプルずつの変換(f2i → putSlot)と同じになる。書き終えた位置を返す
 */
internal fun floatToSlots(src: FloatArray, n: Int, gain: Float, slot: Int, dst: ByteArray, o0: Int): Int {
    var o = o0
    val k = 2147483648f
    when (slot) {
        2 -> for (i in 0 until n) {
            var x = src[i] * gain
            if (x > 0.9999999f) x = 0.9999999f else if (x < -1f) x = -1f
            val v = Math.round(x * k)
            dst[o] = (v shr 16).toByte(); dst[o + 1] = (v shr 24).toByte()
            o += 2
        }
        3 -> for (i in 0 until n) {
            var x = src[i] * gain
            if (x > 0.9999999f) x = 0.9999999f else if (x < -1f) x = -1f
            val v = Math.round(x * k)
            dst[o] = (v shr 8).toByte(); dst[o + 1] = (v shr 16).toByte(); dst[o + 2] = (v shr 24).toByte()
            o += 3
        }
        else -> for (i in 0 until n) {
            var x = src[i] * gain
            if (x > 0.9999999f) x = 0.9999999f else if (x < -1f) x = -1f
            val v = Math.round(x * k)
            dst[o] = v.toByte(); dst[o + 1] = (v shr 8).toByte(); dst[o + 2] = (v shr 16).toByte(); dst[o + 3] = (v shr 24).toByte()
            o += 4
        }
    }
    return o
}

internal fun f2i(f: Float): Int = (f.toDouble() * 2147483648.0).roundToLong().coerceIn(-2147483648L, 2147483647L).toInt()

/**
 * 音声の出力先。次のどれかで出力する。
 * - USB DAC 直接出力(独自ドライバー。Android の音声機能を通さない)
 * - Android のビットパーフェクト出力(Android 14 以降)
 * - 通常の出力(イコライザーを通して float で出力)
 * 入力は 8 / 16 / 24 / 32 bit 整数と float のどれでも受け付ける。
 */
class AudioOutput(
    ctx: Context,
    private val attrs: AudioAttributes,
    override val sampleRate: Int,
    val channels: Int,
    srcBits: Int,
    /** 一切加工せずに出力できる場合だけ使う(DoP 用)。できたかは exactOk で分かる */
    private val exact: Boolean = false,
    /** DAR の PCM アップサンプリングを使ってよいか(DSD を変換した音などには使わない) */
    dar: Boolean = false,
    /** 最初の曲の ReplayGain の値(ギャップレスで次の曲に移ったら rg を差し替える) */
    rg: RgInfo? = null,
) : PcmOut {
    /** 今書き込んでいる曲の ReplayGain の値。掛ける倍率は設定(ReplayGain.mode など)から毎回求める(設定の変更がすぐ効くように) */
    @Volatile var rg: RgInfo? = rg
    /** 加工せずに出力しているか(画面表示用) */
    val bitPerfect: Boolean
    /** exact を指定したとき、加工なしの出力を確保できたか */
    val exactOk: Boolean
    private val outEnc: Int
    private val track: AudioTrack?
    private val dac: com.uyatame.ucrt.player.usb.DacFormat?
    private val dacSession: Int
    private val up: Upsampler?
    private val upFactor: Int
    /** 複数のコアでアップサンプリングするときの流れ作業(使わなければ null) */
    private val pipe: UpsamplePipeline?
    /** DAR(通常の出力): 整数倍で上げたあと、出力先の周波数に合わせる変換(44.1 kHz の系統 → 48 kHz の系統。不要なら null) */
    private val frac: RationalResampler?
    /** 通常の出力の周波数(DAR で上げていれば上げたあと。USB DAC 直接出力では 0) */
    private val trackRate: Int
    private var fracBuf = FloatArray(0)
    /** 整数倍だけの設定なのに、出力先に合わせて整数倍でない変換をしているか */
    private var forcedFrac = false
    private val eq = ParamEqualizer(sampleRate, channels)
    private val toFloat = PcmToFloat()
    /** USB DAC 直接出力のときのイコライザー(DAC のチャンネル数で作る) */
    private var usbEq: ParamEqualizer? = null
    private var fbuf = FloatArray(0)
    private var fbuf2 = FloatArray(0)
    private var bbuf = ByteArray(0)
    private val frameTmp = IntArray(8)
    private var warned = -1
    /** 出力先が変わったときの知らせを受け取るもの(Android 7.0 から。型は AudioRouting.OnRoutingChangedListener) */
    private var routing: Any? = null
    /** 書き込んだフレーム数(再生の終わりを判定する) */
    @Volatile override var framesWritten = 0L
        private set
    /**
     * 通常の出力に書き込めなくなった(音声サーバーの再起動などで AudioTrack が使えなくなった)か。
     * 再生側はこれを見て出力を作り直す
     */
    @Volatile override var failed = false
        private set

    init {
        val usbDac = com.uyatame.ucrt.player.usb.UsbDac
        // 1) ビットパーフェクト(USB DAC 直接出力)
        val darMax = if (dar && !exact) AudioEngine.darMaxRate(sampleRate) else 0
        val d = if (AudioEngine.wantUsbDirect && channels in 1..2) {
            runCatching { usbDac.prepare(sampleRate, channels, srcBits, exact, darMax) }.getOrElse { e ->
                AudioEngine.log("usb dac error: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        } else null
        if (d != null) {
            dac = d
            dacSession = usbDac.begin()
            upFactor = (d.rate / sampleRate).coerceAtLeast(1)
            frac = null
            trackRate = 0
            up = if (upFactor > 1) Upsampler(upFactor, d.channels) else null
            // コアが 4 つ以上あれば、ステレオのアップサンプリングを段 × 左右ごとのスレッドで同時に計算する
            val cores = Runtime.getRuntime().availableProcessors()
            pipe = if (up != null && d.channels == 2 && cores >= 4) {
                val slot = d.subslot
                val session = dacSession
                var bytes = ByteArray(0)
                UpsamplePipeline(upFactor) { buf, n, g ->
                    if (bytes.size < n * 2 * slot) bytes = ByteArray(n * 2 * slot)
                    val len = floatToSlots(buf, n * 2, g, slot, bytes, 0)
                    usbDac.write(session, bytes, len) { released }
                }.also { AudioEngine.log("upsample: ${it.threadCount} threads on $cores cores") }
            } else null
            track = null
            outEnc = 0
            bitPerfect = up == null
            exactOk = up == null
            val softVol = !usbDac.hwVolume && usbDac.volume() < 100
            val eqOn = !exact && AudioEngine.eqEnabled
            val perfect = up == null && !softVol && !eqOn && ReplayGain.linear(rg) == 1f
            val title = when {
                perfect -> S(R.string.bit_perfect)
                eqOn && up == null -> S(R.string.usb_dac_direct_output_equalizer)
                up != null && darMax > 0 -> S(R.string.dar_pcm_upsampling)
                up != null -> S(R.string.usb_dac_direct_output_upsampled)
                else -> S(R.string.usb_dac_direct_output_software)
            }
            val darText = if (up != null && darMax > 0) {
                "DAR · PCM ×$upFactor · ${khz(sampleRate)} → ${khz(d.rate)} / ${d.bits} bit · " +
                    AudioEngine.bitrateText(d.rate.toLong() * d.bits * d.channels)
            } else ""
            AudioEngine.report(
                perfect, title + " · ${khz(sampleRate)}" + (if (up != null) " → ${khz(d.rate)}" else "") + " / ${d.bits} bit", darText,
                usbDac.capNotice(d.rate),
            )
            AudioEngine.log(
                "output: usb direct ${khz(d.rate)} ${d.channels}ch ${d.bits}bit" + (if (up != null) " (x$upFactor)" else "") +
                    " vol=${usbDac.volume()}" + if (usbDac.hwVolume) "(usb)" else "(soft)",
            )
            // 音量を変えられない DAC で、音量が 0 のままだと PCM は無音になる(DSD は音量を掛けないので、DSD では気づけない)
            if (!exact && !usbDac.hwVolume && usbDac.volume() <= 0) AudioEngine.log("usb dac: volume is 0, PCM will be silent")
            AudioEngine.setOutput(
                OutputDevice(
                    OutputDevices.KEY_USB_DIRECT, OutputKind.UsbDirect, usbDac.deviceName() ?: "USB DAC",
                    S(R.string.usb_dac_bit_perfect), usbDac.maxSpec(),
                ),
            )
        } else {
            dac = null
            dacSession = -1
            // DAR の PCM アップサンプリング: 出力先(イヤホンジャックなど)が受け付ける周波数まで、アプリの中で上げてから渡す
            val target = OutputDevices.route(ctx, AudioEngine.preferredKey)
            val outRate = if (dar && !exact && channels in 1..2) normalDarRate(target) else sampleRate
            // 整数倍なら 1 段(Upsampler)で。そうでなければ 2 のべき乗倍で近くまで上げてから、残りを RationalResampler で合わせる
            val nf = if (outRate % sampleRate == 0) outRate / sampleRate
            else Integer.highestOneBit((outRate / sampleRate).coerceAtLeast(1))
            upFactor = nf
            trackRate = outRate
            up = if (nf > 1) Upsampler(nf, channels) else null
            frac = if (outRate != sampleRate * nf) RationalResampler(sampleRate * nf, outRate, channels) else null
            val cores = Runtime.getRuntime().availableProcessors()
            pipe = if (up != null && channels == 2 && cores >= 4) {
                UpsamplePipeline(nf) { buf, n, _ -> writeFrac(buf, n) }
                    .also { AudioEngine.log("upsample: ${it.threadCount} threads on $cores cores") }
            } else null
            if (AudioEngine.wantUsbDirect && channels > 2) {
                AudioEngine.report(false, S(R.string.multichannel_source_using_standard_output))
                runCatching { usbDac.close(reattach = true) }
            }
            if (outRate != sampleRate) {
                val how = if (frac != null) "×%.3f".format(java.util.Locale.US, outRate.toDouble() / sampleRate) else "×$nf"
                AudioEngine.report(
                    false,
                    S(R.string.dar_pcm_upsampling) + " · ${khz(sampleRate)} → ${khz(outRate)}",
                    "DAR · PCM $how · ${khz(sampleRate)} → ${khz(outRate)} / 32 bit float · " +
                        AudioEngine.bitrateText(outRate.toLong() * 32 * channels),
                    if (forcedFrac) {
                        S(R.string.this_output_doesnt_accept_integer, khz(sampleRate), khz(outRate))
                    } else "",
                )
                AudioEngine.log(
                    "dar: normal output x$nf ${khz(sampleRate)} -> ${khz(sampleRate * nf)}" +
                        (frac?.let { " -> ${khz(outRate)} (${it.up}/${it.down})" } ?: "") + " (${target?.name}, type ${target?.type})",
                )
            } else if (!AudioEngine.wantUsbDirect) AudioEngine.report(false, "")
            val stereo = if (channels == 1) AFormat.CHANNEL_OUT_MONO else AFormat.CHANNEL_OUT_STEREO
            val enc = AFormat.ENCODING_PCM_FLOAT
            // 2) 通常の出力
            val t = try {
                build(AFormat.Builder().setSampleRate(outRate).setChannelMask(stereo).setEncoding(enc).build(), enc, outRate)
            } catch (e: Exception) {
                // 作れなかった出力の流れ作業のスレッドを止める(残ると動き続ける)
                pipe?.close()
                // Android 11 以前の通常の出力は 192 kHz までしか扱えない
                if (outRate > 192000 && android.os.Build.VERSION.SDK_INT < 31) {
                    AudioEngine.report(
                        false,
                        S(R.string.this_android_version_cant_play, khz(outRate)),
                    )
                }
                throw e
            }
            // 選ばれている出力先があれば、そこへ出す
            OutputDevices.applyPreferred(ctx, AudioEngine.preferredKey, t)
            // 実際に音が出ている先を画面に出す
            // (出力先が変わったときの知らせは Android 7.0 から)
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                routing = OutputDevices.onRouted(t) { d -> if (!released) AudioEngine.setOutput(d) }
            }
            AudioEngine.setOutput(OutputDevices.selected(ctx, AudioEngine.preferredKey) ?: OutputDevices.defaultDevice(ctx))
            track = t
            outEnc = enc
            bitPerfect = false
            exactOk = false
            OutputDevices.trackText(t)?.let { AudioEngine.log("track: $it") }
            AudioEngine.log(
                "output: ${khz(trackRate)} ${channels}ch ${AudioEngine.encName(outEnc)}" +
                    " mixer",
            )
        }
        scheduleCheck()
    }

    private fun khz(hz: Int) = AudioEngine.khz(hz)

    /**
     * 通常の出力で DAR の PCM アップサンプリングをするときの、出力の周波数。
     * 出力先が受け付ける周波数のうち、設定の上限(DAR の倍率)以下のいちばん高いものにする。
     * 元の周波数の整数倍でなくてもよい(44.1 kHz → 192 kHz など)が、そのときは 2 倍以上に限る。
     * 上げられない(出力先が高い周波数を受け付けない・分からない)ときは元の周波数(上げても Android がまた下げるだけなので)
     */
    private fun normalDarRate(dev: OutputRoute?): Int {
        if (dev != null && AudioEngine.darMode == 2) {
            // DSD への変換は、いまは USB DAC だけ。出力先が DSD を受け付けるかを調べられるよう、形式を残す
            AudioEngine.log("dar: DSD to ${dev.name} (type ${dev.type}) is not supported; ${dev.formats}")
        }
        val max = AudioEngine.darMaxRate(sampleRate)
        if (max <= 0 || dev == null) return sampleRate
        val rates = dev.rates
        AudioEngine.log("dar: output ${dev.name} (type ${dev.type}) ${dev.formats}")
        // Android 11 以前の通常の出力は 192 kHz まで
        val sdkCap = if (android.os.Build.VERSION.SDK_INT >= 31) 768000L else 192000L
        // 倍率の上限は、44.1 kHz の系統から 48 kHz の系統へ移るときは、その分(160/147)だけ広げる(例: ×4 なら 176.4 → 192 kHz まで)
        val maxL = max.toLong()
        // Android 12 以降でも、端末によっては 192 kHz を超える周波数を受け付けないので、実際に受け付けるかも確かめる
        // (Android 7 以前は、出力先の周波数の 2 倍までしか受け付けないなど、一覧どおりには使えない。96 kHz を超えるものは試しに作って確かめる)
        val ok = rates.filter {
            it > sampleRate && it <= sdkCap && runCatching { AFormat.Builder().setSampleRate(it) }.isSuccess &&
                (it <= 96000 || trackAccepts(dev, it))
        }
        val integer = ok.filter { it % sampleRate == 0 && it <= maxL }.maxOrNull()
        val other = ok.filter { it % sampleRate != 0 && it >= sampleRate * 2L && it <= maxL * 160 / 147 }.maxOrNull()
        forcedFrac = false
        return when {
            // 整数倍だけ: 整数倍で上げられればそれ。できない出力先なら、仕方なく出力先の周波数に合わせる(画面に赤字で出す)
            AudioEngine.darInteger -> integer ?: other?.also { forcedFrac = true } ?: sampleRate
            // そうでなければ、出力先が受け付けるいちばん高い周波数
            else -> listOfNotNull(integer, other).maxOrNull() ?: sampleRate
        }
    }

    /** 通常の出力で、その周波数の AudioTrack を本当に作れるか(作ってすぐ捨てる。結果は覚えておく) */
    private fun trackAccepts(dev: OutputRoute, rate: Int): Boolean =
        AudioEngine.rateProbe.getOrPut("${dev.id}:$rate") {
            val ok = runCatching {
                val mask = AFormat.CHANNEL_OUT_STEREO
                val enc = AFormat.ENCODING_PCM_FLOAT
                val min = AudioTrack.getMinBufferSize(rate, mask, enc)
                if (min <= 0) return@runCatching false
                val t = newAudioTrack(attrs, AFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(enc).build(), min)
                val inited = t.state == AudioTrack.STATE_INITIALIZED
                t.release()
                inited
            }.getOrDefault(false)
            if (!ok) AudioEngine.log("dar: ${khz(rate)} is not accepted by the standard output (${dev.name}, type ${dev.type})")
            ok
        }

    /** DAR(通常の出力): 整数倍で上げた音を、必要なら出力先の周波数に合わせてから書き込む */
    private fun writeFrac(buf: FloatArray, frames: Int, stop: () -> Boolean = { false }) {
        val f = frac
        if (f == null) {
            writeTrack(buf, frames * channels, stop)
            return
        }
        val need = f.maxOut(frames) * channels
        if (fracBuf.size < need) fracBuf = FloatArray(need)
        val n = f.process(buf, frames, fracBuf)
        writeTrack(fracBuf, n * channels, stop)
    }

    private fun build(f: AFormat, enc: Int, rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, f.channelMask.takeIf { it != 0 } ?: AFormat.CHANNEL_OUT_STEREO, enc)
            .coerceAtLeast(4096)
        val bytesPerFrame = channels * pcmBytes(enc).coerceAtLeast(2)
        // 約 1.5 秒分のバッファ(ほかの処理で読み込みが遅れても途切れないように)。
        // 古い Android では大きなバッファを作れないことがある(192 kHz の float で約 2.3 MB)ので、そのときは小さくして作り直す
        val sizes = listOf(max(min * 2, rate * bytesPerFrame * 3 / 2), max(min * 2, rate * bytesPerFrame / 2), min * 2).distinct()
        var last: Exception? = null
        for ((i, size) in sizes.withIndex()) {
            try {
                val t = newAudioTrack(attrs, f, size)
                if (i > 0) AudioEngine.log("track: created with a smaller buffer ($size bytes)")
                return t
            } catch (e: Exception) {
                last = e
                AudioEngine.log("track: cannot create with $size bytes (${e.javaClass.simpleName}: ${e.message})")
            }
        }
        throw last ?: IllegalStateException("cannot create AudioTrack")
    }

    private val usbDac get() = com.uyatame.ucrt.player.usb.UsbDac

    /** 一時停止中か(通常の出力で、書き込めるまで待つ間隔に使う) */
    @Volatile private var paused = true

    override fun play() {
        paused = false
        if (dac != null) usbDac.setPaused(dacSession, false) else runCatching { track?.play() }
    }

    override fun pause() {
        paused = true
        if (dac != null) usbDac.setPaused(dacSession, true) else runCatching { track?.pause() }
    }

    override fun release() {
        released = true
        SpectrumTap.forget(this)
        pipe?.close()
        if (dac != null) {
            usbDac.end(dacSession)
            return
        }
        val t = track ?: return
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            routing?.let { OutputDevices.removeRouted(t, it) }
        }
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.release() }
    }

    /** 再生し終えたフレーム数(音源の周波数で数える) */
    override fun headFrames(): Long {
        if (dac != null) {
            // 転送が止まってしまった(DAC が外れたなど)ときは、書いた分を再生済みとみなして先へ進める
            if (!usbDac.streaming) return framesWritten
            return usbDac.played(dacSession) / upFactor
        }
        // 書き込めなくなった出力は進まないので、書いた分を再生済みとみなす(終わりを待ち続けないように)
        if (failed) return framesWritten
        // 再生位置は 32bit で一周する(ギャップレスで長くつなぐと届く)ので、一周した分を足す
        synchronized(this) {
            val raw = runCatching { (track?.playbackHeadPosition ?: 0).toLong() and 0xFFFFFFFFL }
                .getOrElse { return wrapHi + lastRaw }
            if (raw < lastRaw && lastRaw - raw > 0x80000000L) wrapHi += 0x100000000L
            lastRaw = raw
            // DAR で上げているときは、出力の周波数で数えた位置を音源の周波数に戻す
            val pos = wrapHi + raw
            return if (trackRate == sampleRate || trackRate <= 0) pos else pos * sampleRate / trackRate
        }
    }

    private var lastRaw = 0L
    private var wrapHi = 0L

    /** 32bit 整数(上位詰め)を DAC の 1 サンプル分のバイトに書く */
    private fun putSlot(v: Int, slot: Int, o: Int) {
        when (slot) {
            2 -> { bbuf[o] = (v shr 16).toByte(); bbuf[o + 1] = (v shr 24).toByte() }
            3 -> { bbuf[o] = (v shr 8).toByte(); bbuf[o + 1] = (v shr 16).toByte(); bbuf[o + 2] = (v shr 24).toByte() }
            else -> { bbuf[o] = v.toByte(); bbuf[o + 1] = (v shr 8).toByte(); bbuf[o + 2] = (v shr 16).toByte(); bbuf[o + 3] = (v shr 24).toByte() }
        }
    }

    /** USB DAC 直接出力への書き込み */
    private fun writeUsb(d: com.uyatame.ucrt.player.usb.DacFormat, src: ByteBuffer, srcEnc: Int, frames: Int, stop: () -> Boolean) {
        // DoP のときは音量を一切掛けない(目印のバイトが壊れるため)。ReplayGain も音量と一緒に掛ける
        val gain = if (exact) 1.0 else usbDac.softGain * ReplayGain.linear(rg)
        val slot = d.subslot
        val dch = d.channels
        val up = up
        // イコライザー(ソースダイレクトがオフで、イコライザーを使うとき)。DoP には掛けない
        val useEq = !exact && AudioEngine.eqEnabled
        if (up == null && !useEq) {
            ensureB(frames * d.frameBytes)
            // 音量を掛けず、チャンネル数と 1 サンプルのバイト数が DAC と同じなら、バイト列はそのまま同じになるので、まとめて写す
            // (下の 1 サンプルずつの変換と結果は同じ。ビットパーフェクトで最もよく通る道を軽くする)
            if (gain == 1.0 && channels == dch && srcEnc != AFormat.ENCODING_PCM_FLOAT && srcEnc != AFormat.ENCODING_PCM_8BIT &&
                pcmBytes(srcEnc) == slot
            ) {
                val n = frames * d.frameBytes
                src.get(bbuf, 0, n)
                pushUsb(n, stop)
                return
            }
            var o = 0
            for (f in 0 until frames) {
                for (c in 0 until channels) frameTmp[c] = readI32(src, srcEnc)
                for (c in 0 until dch) {
                    var v = frameTmp[if (c < channels) c else 0]
                    if (gain != 1.0) v = (v * gain).toLong().coerceIn(-2147483648L, 2147483647L).toInt()
                    putSlot(v, slot, o)
                    o += slot
                }
            }
            pushUsb(o, stop)
            return
        }
        // イコライザー・アップサンプリング: float で計算してから DAC の形式に戻す
        val inSamples = frames * dch
        if (fbuf.size < inSamples) fbuf = FloatArray(inSamples)
        toFloat.read(src, srcEnc, frames, channels, dch, fbuf)
        var g = gain.toFloat()
        if (useEq) {
            val e = usbEq ?: ParamEqualizer(sampleRate, dch).also { usbEq = it }
            // 版を先に読む(帯域より後に読むと、変更の途中で古い帯域のまま新しい版を覚えてしまう)
            val v = AudioEngine.version
            if (e.version != v) {
                e.update(AudioEngine.bands)
                e.version = v
            }
            e.process(fbuf, inSamples)
            g *= AudioEngine.preampGain()
        }
        val p = pipe
        if (p != null) {
            // 流れ作業に渡す(DAC へ送るのは流れ作業の出力のスレッド)
            p.submit(fbuf, frames, g, stop)
            return
        }
        val outBuf: FloatArray
        val outSamples: Int
        if (up != null) {
            outSamples = inSamples * upFactor
            if (fbuf2.size < outSamples) fbuf2 = FloatArray(outSamples)
            up.process(fbuf, frames, fbuf2)
            outBuf = fbuf2
        } else {
            outSamples = inSamples
            outBuf = fbuf
        }
        ensureB(outSamples * slot)
        val o = floatToSlots(outBuf, outSamples, g, slot, bbuf, 0)
        pushUsb(o, stop)
    }

    /** DAC へ送る。送れなかった(転送が止まった)ときは、曲が勝手に進まないよう止められるまで待つ */
    private fun pushUsb(n: Int, stop: () -> Boolean) {
        if (usbDac.write(dacSession, bbuf, n, stop)) return
        while (!stop()) Thread.sleep(50)
    }

    /**
     * 音声を書き込む。src は srcEnc の音声。
     * 一時停止中は書き込めるまで待つ。stop() が true を返したら途中でやめる。
     */
    override fun write(src: ByteBuffer, srcEnc0: Int, stop: () -> Boolean) {
        src.order(ByteOrder.LITTLE_ENDIAN)
        var srcEnc = srcEnc0
        var srcBytes = pcmBytes(srcEnc)
        if (srcBytes == 0) {
            if (warned != srcEnc) { AudioEngine.log("unknown PCM encoding $srcEnc, treating as 16bit"); warned = srcEnc }
            srcEnc = AFormat.ENCODING_PCM_16BIT
            srcBytes = 2
        }
        val frameBytes = srcBytes * channels
        val frames = src.remaining() / frameBytes
        val samples = frames * channels
        if (samples <= 0) return
        // スペクトラムアナライザー(表示している間だけ。音には触れない)
        // (DoP は DSD のデータなので、DSD の再生の側で写す)
        if (SpectrumTap.enabled && !exact) SpectrumTap.submit(this, src, srcEnc, frames, channels, sampleRate, framesWritten)
        framesWritten += frames
        val d = dac
        if (d != null) {
            writeUsb(d, src, srcEnc, frames, stop)
            return
        }
        val t = track ?: return
        // 通常の出力: float に変換し、イコライザーと音量を通す
        if (fbuf.size < samples) fbuf = FloatArray(samples)
        if (srcEnc == AFormat.ENCODING_PCM_FLOAT) {
            // まとめて読む(1 サンプルずつ読むより速い)
            src.asFloatBuffer().get(fbuf, 0, samples)
            src.position(src.position() + samples * 4)
        } else {
            // 形式ごとの専用ループでまとめて変換する(値は readI32 / 2^31 と同じ)
            toFloat.read(src, srcEnc, frames, channels, channels, fbuf)
        }
        if (AudioEngine.eqEnabled) {
            // 版を先に読む(帯域より後に読むと、変更の途中で古い帯域のまま新しい版を覚えてしまう)
            val v = AudioEngine.version
            if (eq.version != v) {
                eq.update(AudioEngine.bands)
                eq.version = v
            }
            eq.process(fbuf, samples)
            val g = AudioEngine.preampGain()
            if (g != 1f) for (k in 0 until samples) fbuf[k] *= g
        }
        // ReplayGain と、アプリの細かい音量(設定の「細かい音量」。端末の音量の段階の間を細かく合わせる)
        val rgGain = ReplayGain.linear(rg) * AudioEngine.appVolume
        if (rgGain != 1f) for (k in 0 until samples) fbuf[k] *= rgGain
        // 3ch 以上(5.1ch など)は、通常の出力ではステレオにまとめて出す
        if (channels > 2) {
            if (mixBuf.size < frames * 2) mixBuf = FloatArray(frames * 2)
            downmix(fbuf, frames, channels, mixBuf)
            writeTrack(mixBuf, frames * 2, stop)
            checkUnderrun(t)
            return
        }
        val p = pipe
        if (p != null) {
            // DAR: 流れ作業でアップサンプリング(出力への書き込みは流れ作業の出力のスレッド)
            p.submit(fbuf, frames, 1f, stop)
            return
        }
        val u = up
        if (u != null) {
            val n = samples * upFactor
            if (fbuf2.size < n) fbuf2 = FloatArray(n)
            u.process(fbuf, frames, fbuf2)
            writeFrac(fbuf2, frames * upFactor, stop)
        } else {
            writeTrack(fbuf, samples, stop)
        }
        checkUnderrun(t)
    }

    private var mixBuf = FloatArray(0)
    private var mixL: FloatArray? = null
    private var mixR: FloatArray? = null
    private var mixN = 0f
    private var mixNr = 0f

    /**
     * 多チャンネルをステレオにまとめる(Android の並び: 左前・右前・中央・LFE・左後・右後・左横・右横…)。
     * 中央とサラウンドは -3 dB で左右に足し、LFE は使わない。まとめたあとに大きくなりすぎないよう、左右それぞれの係数の合計で割る
     */
    private fun downmix(src: FloatArray, frames: Int, ch: Int, dst: FloatArray) {
        // 係数はチャンネル数だけで決まるので、最初の 1 回だけ作る
        val wl = mixL ?: FloatArray(ch).also { mixL = it }
        val wr = mixR ?: FloatArray(ch).also { mixR = it }
        if (mixN == 0f) {
            val k = 0.70710678f
            for (c in 0 until ch) {
                when (c) {
                    0 -> wl[c] = 1f
                    1 -> wr[c] = 1f
                    2 -> { wl[c] = k; wr[c] = k }
                    3 -> {}
                    else -> if (c % 2 == 0) wl[c] = k else wr[c] = k
                }
            }
            mixN = 1f / wl.sum()
            mixNr = 1f / wr.sum()
        }
        val nl = mixN
        val nr = mixNr
        var i = 0
        var o = 0
        for (f in 0 until frames) {
            var l = 0f
            var r = 0f
            for (c in 0 until ch) {
                val v = src[i + c]
                l += v * wl[c]
                r += v * wr[c]
            }
            dst[o] = l * nl
            dst[o + 1] = r * nr
            i += ch
            o += 2
        }
    }

    /** 通常の出力へ書き込む(音割れしないよう ±1 に収める) */
    private fun writeTrack(buf: FloatArray, samples: Int, stop: () -> Boolean = { false }) {
        val t = track ?: return
        for (k in 0 until samples) {
            val v = buf[k]
            if (v > 1f) buf[k] = 1f else if (v < -1f) buf[k] = -1f else if (v.isNaN()) buf[k] = 0f
        }
        var off = 0
        while (off < samples && !stop() && !failed && !released) {
            // 待たずに書ける分だけ書く。WRITE_BLOCKING だと、一時停止中にバッファが埋まっていると
            // 再開するまで戻らず、その間に止められても(曲の切り替え・シーク)スレッドが終われず、
            // AudioTrack とデコーダーを手放せないまま残ってしまう
            val w = t.write(buf, off, samples - off, AudioTrack.WRITE_NON_BLOCKING)
            if (w < 0) {
                AudioEngine.log("write error $w")
                failed = true
                return
            }
            if (w == 0) Thread.sleep(if (paused) 50 else 20) else off += w
        }
    }

    private var lastUnderrun = 0
    private var underrunCheckAt = 0L

    /** 音の途切れ(バッファが空になった回数)を、増えたときだけログに出す */
    private fun checkUnderrun(t: AudioTrack) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now < underrunCheckAt) return
        underrunCheckAt = now + 2000
        if (android.os.Build.VERSION.SDK_INT < 24) return
        val u = runCatching { t.underrunCount }.getOrDefault(0)
        if (u > lastUnderrun) {
            AudioEngine.log("underrun: +${u - lastUnderrun} (total $u)")
            lastUnderrun = u
        }
    }

    private fun ensureB(n: Int) { if (bbuf.size < n) bbuf = ByteArray(n) }

    @Volatile private var released = false

    /** 出力を開いてから 3 秒後の状態をログに出す(音が出ないときの調査用) */
    private fun scheduleCheck() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (released) return@postDelayed
            val t = track
            val routed = t?.let { OutputDevices.routedText(it) }
            AudioEngine.log(
                "after 3s: head=${headFrames()} written=$framesWritten" +
                    (if (t != null) " playState=${t.playState} routed=$routed" else " usb-direct streaming=${usbDac.streaming}") +
                    (if (bitPerfect) " bit-perfect" else ""),
            )
        }, 3000)
    }
}

