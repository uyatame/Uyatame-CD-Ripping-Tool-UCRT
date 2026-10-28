package com.uyatame.ucrt.convert

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** 2 次の IIR フィルター(1 チャンネル。転置直接形 II) */
class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun run(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        /** 低域通過(q = 1/√2 でバターワース) */
        fun lowpass(rate: Int, hz: Double, q: Double = 0.7071067811865476): Biquad {
            val w = 2 * PI * (hz / rate).coerceIn(1e-5, 0.4999)
            val al = sin(w) / (2 * q)
            val cs = cos(w)
            val a0 = 1 + al
            return Biquad((1 - cs) / 2 / a0, (1 - cs) / a0, (1 - cs) / 2 / a0, -2 * cs / a0, (1 - al) / a0)
        }

        /** 高域通過 */
        fun highpass(rate: Int, hz: Double, q: Double = 0.7071067811865476): Biquad {
            val w = 2 * PI * (hz / rate).coerceIn(1e-5, 0.4999)
            val al = sin(w) / (2 * q)
            val cs = cos(w)
            val a0 = 1 + al
            return Biquad((1 + cs) / 2 / a0, -(1 + cs) / a0, (1 + cs) / 2 / a0, -2 * cs / a0, (1 - al) / a0)
        }

        /** 4 次のバターワース(2 次を 2 つ。q は 0.5412 と 1.3066) */
        fun lowpass4(rate: Int, hz: Double) = arrayOf(lowpass(rate, hz, 0.5411961), lowpass(rate, hz, 1.3065630))
        fun highpass4(rate: Int, hz: Double) = arrayOf(highpass(rate, hz, 0.5411961), highpass(rate, hz, 1.3065630))
    }
}

/**
 * ピークを抑えるリミッター(先読み 1.5 ms)。ceiling を超えそうなところだけ、なめらかに音量を下げる(超えなければ何もしない)。
 *
 * 要る倍率(ceiling ÷ ピーク)の、先読みの区間 + 1 での最小を取り、それを先読みの長さで平均した倍率を、遅らせた音に掛ける
 * (ピークの音が出るときには、必ずその倍率以下になっている)。戻すときは 80 ms でゆっくり戻す。
 * 入力・出力はチャンネル交互の float。先読みの分だけ遅れるので、先頭の latency フレームを捨て、最後に flush() で残りを出す
 */
class Limiter(rate: Int, private val channels: Int, private val ceiling: Float) {
    private val look = (rate * 0.0015).toInt().coerceAtLeast(1)
    private val delay = FloatArray(look * channels)
    private var dp = 0
    /** 要る倍率の最小(単調な列) */
    private val qSize = look + 2
    private val reqQ = FloatArray(qSize)
    private val idxQ = LongArray(qSize)
    private var qh = 0
    private var qt = 0
    private var n = 0L
    /** 最小の倍率の、直近 look 個の和(平均を取る) */
    private val box = FloatArray(look) { 1f }
    private var bp = 0
    private var boxSum = look.toDouble()
    private var gain = 1f
    private val release = exp(-1.0 / (rate * 0.08)).toFloat()
    /** 音量を下げたフレームの数(ログ用) */
    var limited = 0L
        private set

    /** 先頭の、遅れのために出す無音のフレーム数(呼び出し側で捨てる) */
    val latency: Int get() = look

    fun process(buf: FloatArray, frames: Int) {
        val ch = channels
        for (f in 0 until frames) {
            val base = f * ch
            var peak = 0f
            for (c in 0 until ch) { val a = abs(buf[base + c]); if (a > peak) peak = a }
            val req = if (peak > ceiling) ceiling / peak else 1f
            // 最小を求める列に入れる(後ろから、これより大きいものを捨てる)
            while (qt != qh) {
                val last = if (qt == 0) qSize - 1 else qt - 1
                if (reqQ[last] >= req) qt = last else break
            }
            reqQ[qt] = req; idxQ[qt] = n
            qt = if (qt + 1 == qSize) 0 else qt + 1
            while (idxQ[qh] < n - look) qh = if (qh + 1 == qSize) 0 else qh + 1
            n++
            val held = reqQ[qh]
            boxSum += held - box[bp]
            box[bp] = held
            bp = if (bp + 1 == look) 0 else bp + 1
            val avg = (boxSum / look).toFloat().coerceAtMost(1f)
            gain = if (avg < gain) avg else avg + (gain - avg) * release
            if (gain < 0.9999f) limited++
            for (c in 0 until ch) {
                val k = dp * ch + c
                val old = delay[k]
                delay[k] = buf[base + c]
                buf[base + c] = old * gain
            }
            dp = if (dp + 1 == look) 0 else dp + 1
        }
    }

    /** 最後: 遅れて残っている音を out の先頭に出す。出したフレーム数 */
    fun flush(out: FloatArray): Int {
        java.util.Arrays.fill(out, 0, look * channels, 0f)
        process(out, look)
        return look
    }
}

/**
 * TPDF ディザーを掛けて整数にする(16bit・24bit の出力)。乱数は速い xorshift
 */
class Quantizer(bits: Int) {
    private val scale = (1L shl (bits - 1)).toFloat()
    private val max = (1L shl (bits - 1)) - 1
    private val min = -(1L shl (bits - 1))
    private var seed = 0x2545F491L

    private fun rnd(): Float {
        var x = seed
        x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
        seed = x
        return ((x ushr 40).toInt() and 0xFFFFFF) / 16777216f
    }

    fun q(v: Float): Int {
        val d = rnd() - rnd()
        val r = Math.round(v * scale + d)
        return (if (r > max) max else if (r < min) min else r.toLong()).toInt()
    }
}

/** 二乗平均の平方根(dB 表示などに) */
internal fun rmsOf(x: FloatArray, n: Int): Double {
    var s = 0.0
    for (i in 0 until n) s += x[i].toDouble() * x[i]
    return sqrt(s / n.coerceAtLeast(1))
}
