package com.uyatame.ucrt.convert

import com.uyatame.ucrt.player.RationalResampler
import com.uyatame.ucrt.player.Upsampler
import kotlin.math.PI
import kotlin.math.ceil

/**
 * ファイルの変換で使う周波数の変換(どの周波数からどの周波数へも)。入力・出力はチャンネル交互の float。
 *
 * 整数倍に上げるときは再生の DAR と同じ多相 FIR(Upsampler)、それ以外は有理数倍の多相 FIR(RationalResampler)を、
 * 可聴帯域(低いほうの周波数の 0.4535 倍。44.1 kHz なら 20 kHz)を平らに通し、ナイキスト周波数から約 100 dB 落とす長さで使う。
 * フィルターの遅れは最初に測っておき、出力の先頭から捨てる(元の音と時間がずれないように)。
 * 終わりには finish() で残りを出す
 */
class Resample(val inRate: Int, val outRate: Int, private val channels: Int, probe: Boolean = false) {
    private val up: Upsampler?
    private val rr: RationalResampler?
    private val factor: Int
    /** 先頭から捨てる出力のフレーム数(残り) */
    private var skip: Long
    /** 入れた入力のフレーム数と、出した出力のフレーム数 */
    private var inFrames = 0L
    private var outFrames = 0L
    private var tmp = FloatArray(0)

    init {
        if (outRate == inRate) {
            up = null; rr = null; factor = 1
        } else if (outRate % inRate == 0) {
            factor = outRate / inRate
            up = Upsampler(factor, channels); rr = null
        } else {
            factor = 0
            up = null
            val low = minOf(inRate, outRate).toDouble()
            val pass = 0.4535 * low
            val stop = 0.5 * low
            // カイザー窓(β 10、約 100 dB)の長さの目安: (A - 8) / (2.285 Δω)。Δω は入力の周波数で数える
            val dw = 2 * PI * (stop - pass) / inRate
            val taps = ceil(92.0 / (2.285 * dw)).toInt().coerceIn(32, 2048)
            rr = RationalResampler(inRate, outRate, channels, cutHz = (pass + stop) / 2, taps = taps)
        }
        skip = if (outRate == inRate || probe) 0L else measureDelay()
    }

    /** 同じ作りの変換(1 チャンネル)に 1 つのパルスを通して、出力に出てくるまでの遅れ(出力のフレーム数)を測る */
    private fun measureDelay(): Long {
        val probe = Resample(inRate, outRate, 1, probe = true)
        val n = 8192
        val input = FloatArray(n)
        input[0] = 1f
        val out = FloatArray(probe.maxOut(n))
        val m = probe.raw(input, n, out)
        var best = 0
        for (i in 0 until m) if (kotlin.math.abs(out[i]) > kotlin.math.abs(out[best])) best = i
        return best.toLong()
    }

    /** 入力 n フレームに対する出力の最大フレーム数 */
    fun maxOut(n: Int): Int = when {
        outRate == inRate -> n
        up != null -> n * factor
        else -> rr!!.maxOut(n)
    }

    private fun raw(input: FloatArray, n: Int, out: FloatArray): Int = when {
        outRate == inRate -> { System.arraycopy(input, 0, out, 0, n * channels); n }
        up != null -> { up.process(input, n, out); n * factor }
        else -> rr!!.process(input, n, out)
    }

    /** n フレームを変換して out に書く(先頭の遅れの分は捨てる)。書いたフレーム数 */
    fun process(input: FloatArray, n: Int, out: FloatArray): Int {
        inFrames += n
        val m = raw(input, n, out)
        return emit(out, m)
    }

    private fun emit(out: FloatArray, m: Int): Int {
        var m2 = m
        if (skip > 0) {
            val d = minOf(skip, m.toLong()).toInt()
            skip -= d
            m2 = m - d
            if (m2 > 0) System.arraycopy(out, d * channels, out, 0, m2 * channels)
        }
        // 入力の長さに合う数より多くは出さない(最後に入れる無音の分)
        val want = inFrames * outRate / inRate - outFrames
        if (m2 > want) m2 = want.coerceAtLeast(0).toInt()
        outFrames += m2
        return m2
    }

    /** 終わり: フィルターに残っている音を出す。out は maxOut(4096) 以上の大きさ。呼ぶたびに出し、0 を返したら終わり */
    fun finish(out: FloatArray): Int {
        if (outRate == inRate) return 0
        val want = inFrames * outRate / inRate - outFrames
        if (want <= 0) return 0
        val n = 4096
        if (tmp.size < n * channels) tmp = FloatArray(n * channels)
        java.util.Arrays.fill(tmp, 0, n * channels, 0f)
        val m = raw(tmp, n, out)
        return emit(out, m)
    }
}
