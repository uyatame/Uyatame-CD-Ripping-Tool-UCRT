package com.uyatame.ucrt.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uyatame.ucrt.T
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import android.media.AudioFormat as AFormat

/** イコライザーのプリセット(10バンド、dB) */
class EqPreset(val ja: String, val en: String, val gains: FloatArray)

object EqBands {
    val FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    const val MAX_DB = 12f

    val PRESETS = listOf(
        EqPreset("フラット", "Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("低音強調", "Bass boost", floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("高音強調", "Treble boost", floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
        EqPreset("ボーカル", "Vocal", floatArrayOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
        EqPreset("ロック", "Rock", floatArrayOf(5f, 4f, 2f, -1f, -2f, -1f, 2f, 4f, 5f, 5f)),
        EqPreset("ポップ", "Pop", floatArrayOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f)),
        EqPreset("ジャズ", "Jazz", floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
        EqPreset("クラシック", "Classical", floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)),
        EqPreset("小音量向け", "Loudness", floatArrayOf(5f, 4f, 1f, 0f, -1f, 0f, 0f, 1f, 4f, 5f)),
    )

    fun encode(g: FloatArray): String = g.joinToString(",") { "%.1f".format(java.util.Locale.US, it) }
    fun decode(s: String?): FloatArray {
        val v = s?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }.orEmpty()
        return FloatArray(FREQS.size) { i -> v.getOrElse(i) { 0f }.coerceIn(-MAX_DB, MAX_DB) }
    }
}

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

    /** 画面表示用: ビットパーフェクトで出力中か */
    var bitPerfectActive by mutableStateOf(false)
        private set
    /** 画面表示用: 出力の状態の説明 */
    var outputStatus by mutableStateOf("")
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

    internal fun report(active: Boolean, status: String) {
        main.post {
            bitPerfectActive = active
            outputStatus = status
        }
    }

    fun encName(enc: Int): String = when (enc) {
        AFormat.ENCODING_PCM_8BIT -> "8bit"
        AFormat.ENCODING_PCM_16BIT -> "16bit"
        AFormat.ENCODING_PCM_24BIT_PACKED -> "24bit"
        AFormat.ENCODING_PCM_32BIT -> "32bit"
        AFormat.ENCODING_PCM_FLOAT -> "float"
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
) : PcmOut {
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
    private val eq = ParamEqualizer(sampleRate, channels)
    private val toFloat = PcmToFloat()
    /** USB DAC 直接出力のときのイコライザー(DAC のチャンネル数で作る) */
    private var usbEq: ParamEqualizer? = null
    private var fbuf = FloatArray(0)
    private var fbuf2 = FloatArray(0)
    private var bbuf = ByteArray(0)
    private val frameTmp = IntArray(8)
    private var warned = -1
    private var routing: android.media.AudioRouting.OnRoutingChangedListener? = null
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
            val perfect = up == null && !softVol && !eqOn
            val title = when {
                perfect -> T("ビットパーフェクト", "Bit-perfect")
                eqOn && up == null -> T("USB DAC 直接出力(イコライザーを使用中)", "USB DAC direct (equalizer on)")
                up != null && darMax > 0 -> T("DAR: PCM アップサンプリング", "DAR: PCM upsampling")
                up != null -> T("USB DAC 直接出力(DAC が非対応の周波数のためアップサンプリング)", "USB DAC direct (upsampled: rate not supported by the DAC)")
                else -> T("USB DAC 直接出力(ソフトウェア音量を使用中。音量 100 でビットパーフェクト)", "USB DAC direct (software volume; set 100 for bit-perfect)")
            }
            AudioEngine.report(perfect, title + " · ${khz(sampleRate)}" + (if (up != null) " → ${khz(d.rate)}" else "") + " / ${d.bits} bit")
            AudioEngine.log("output: usb direct ${khz(d.rate)} ${d.channels}ch ${d.bits}bit" + (if (up != null) " (x$upFactor)" else ""))
            AudioEngine.setOutput(
                OutputDevice(
                    OutputDevices.KEY_USB_DIRECT, OutputKind.UsbDirect, usbDac.deviceName() ?: "USB DAC",
                    T("USB DAC · ビットパーフェクト", "USB DAC · Bit-perfect"), usbDac.maxSpec(),
                ),
            )
        } else {
            dac = null
            dacSession = -1
            up = null
            upFactor = 1
            pipe = null
            if (AudioEngine.wantUsbDirect && channels > 2) {
                AudioEngine.report(false, T("多チャンネル音源のため、通常の出力で再生しています", "Multichannel source; using normal output"))
                runCatching { usbDac.close(reattach = true) }
            }
            if (!AudioEngine.wantUsbDirect) AudioEngine.report(false, "")
            val stereo = if (channels == 1) AFormat.CHANNEL_OUT_MONO else AFormat.CHANNEL_OUT_STEREO
            val enc = AFormat.ENCODING_PCM_FLOAT
            // 2) 通常の出力
            val t = build(AFormat.Builder().setSampleRate(sampleRate).setChannelMask(stereo).setEncoding(enc).build(), enc)
            // 選ばれている出力先があれば、そこへ出す
            OutputDevices.find(ctx, AudioEngine.preferredKey)?.let { dev -> runCatching { t.setPreferredDevice(dev) } }
            // 実際に音が出ている先を画面に出す
            val rl = android.media.AudioRouting.OnRoutingChangedListener { r ->
                if (!released) r.routedDevice?.let { AudioEngine.setOutput(OutputDevices.describe(it)) }
            }
            routing = rl
            runCatching { t.addOnRoutingChangedListener(rl, android.os.Handler(android.os.Looper.getMainLooper())) }
            AudioEngine.setOutput(OutputDevices.find(ctx, AudioEngine.preferredKey)?.let { OutputDevices.describe(it) } ?: OutputDevices.defaultDevice(ctx))
            track = t
            outEnc = enc
            bitPerfect = false
            exactOk = false
            runCatching {
                val tf = t.format
                AudioEngine.log(
                    "track: ${tf.sampleRate} Hz ${AudioEngine.encName(tf.encoding)} mask=${tf.channelMask} idx=${tf.channelIndexMask}" +
                        " buf=${t.bufferSizeInFrames} state=${t.state}",
                )
            }
            AudioEngine.log(
                "output: ${khz(sampleRate)} ${channels}ch ${AudioEngine.encName(outEnc)}" +
                    " mixer",
            )
        }
        scheduleCheck()
    }

    private fun khz(hz: Int) = AudioEngine.khz(hz)

    private fun build(f: AFormat, enc: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sampleRate, f.channelMask.takeIf { it != 0 } ?: AFormat.CHANNEL_OUT_STEREO, enc)
            .coerceAtLeast(4096)
        val bytesPerFrame = channels * pcmBytes(enc).coerceAtLeast(2)
        // 約 1.5 秒分のバッファ(ほかの処理で読み込みが遅れても途切れないように)
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(f)
            .setBufferSizeInBytes(max(min * 2, sampleRate * bytesPerFrame * 3 / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private val usbDac get() = com.uyatame.ucrt.player.usb.UsbDac

    override fun play() {
        if (dac != null) usbDac.setPaused(dacSession, false) else runCatching { track?.play() }
    }

    override fun pause() {
        if (dac != null) usbDac.setPaused(dacSession, true) else runCatching { track?.pause() }
    }

    override fun release() {
        released = true
        pipe?.close()
        if (dac != null) {
            usbDac.end(dacSession)
            return
        }
        val t = track ?: return
        routing?.let { l -> runCatching { t.removeOnRoutingChangedListener(l) } }
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
            return wrapHi + raw
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
        // DoP のときは音量を一切掛けない(目印のバイトが壊れるため)
        val gain = if (exact) 1.0 else usbDac.softGain
        val slot = d.subslot
        val dch = d.channels
        val up = up
        // イコライザー(ソースダイレクトがオフで、イコライザーを使うとき)。DoP には掛けない
        val useEq = !exact && AudioEngine.eqEnabled
        if (up == null && !useEq) {
            ensureB(frames * d.frameBytes)
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
            if (e.version != AudioEngine.version) {
                e.update(AudioEngine.bands)
                e.version = AudioEngine.version
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
            for (k in 0 until samples) fbuf[k] = src.float
        } else {
            for (k in 0 until samples) fbuf[k] = (readI32(src, srcEnc) / 2147483648.0).toFloat()
        }
        if (AudioEngine.eqEnabled) {
            if (eq.version != AudioEngine.version) {
                eq.update(AudioEngine.bands)
                eq.version = AudioEngine.version
            }
            eq.process(fbuf, samples)
            val g = AudioEngine.preampGain()
            if (g != 1f) for (k in 0 until samples) fbuf[k] *= g
        }
        for (k in 0 until samples) {
            val v = fbuf[k]
            if (v > 1f) fbuf[k] = 1f else if (v < -1f) fbuf[k] = -1f else if (v.isNaN()) fbuf[k] = 0f
        }
        var off = 0
        while (off < samples && !stop() && !failed) {
            val w = t.write(fbuf, off, samples - off, AudioTrack.WRITE_BLOCKING)
            if (w < 0) {
                AudioEngine.log("write error $w")
                failed = true
                return
            }
            if (w == 0) Thread.sleep(20) else off += w
        }
        checkUnderrun(t)
    }

    private var lastUnderrun = 0
    private var underrunCheckAt = 0L

    /** 音の途切れ(バッファが空になった回数)を、増えたときだけログに出す */
    private fun checkUnderrun(t: AudioTrack) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now < underrunCheckAt) return
        underrunCheckAt = now + 2000
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
            val routed = runCatching { t?.routedDevice?.let { "${it.productName} (type ${it.type})" } }.getOrNull()
            AudioEngine.log(
                "after 3s: head=${headFrames()} written=$framesWritten" +
                    (if (t != null) " playState=${t.playState} routed=$routed" else " usb-direct streaming=${usbDac.streaming}") +
                    (if (bitPerfect) " bit-perfect" else ""),
            )
        }, 3000)
    }
}

