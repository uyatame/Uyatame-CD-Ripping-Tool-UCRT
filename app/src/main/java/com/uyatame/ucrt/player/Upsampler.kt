package com.uyatame.ucrt.player

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 整数倍のアップサンプラー(多相 FIR、カイザー窓)。
 * DAC が音源の周波数に対応していないときだけ使う(この場合はビットパーフェクトではない)。
 * 入力 1 フレームにつき、ちょうど factor フレームを出力する。
 */
class Upsampler(private val factor: Int, private val channels: Int) {
    private val taps = 64
    /** coef[phase][k] */
    private val coef: Array<FloatArray>
    private val hist = Array(channels) { FloatArray(taps * 2) }
    private var pos = 0

    init {
        val n = factor * taps
        val fc = 0.5 / factor * 0.92
        val beta = 9.0
        val mid = (n - 1) / 2.0
        val h = DoubleArray(n) { i ->
            val x = i - mid
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val r = 2.0 * i / (n - 1) - 1.0
            sinc * bessel0(beta * sqrt((1 - r * r).coerceAtLeast(0.0))) / bessel0(beta)
        }
        // 各位相の合計が 1 になるように(ゼロを挟んだ分の音量を戻す)
        val sum = h.sum()
        coef = Array(factor) { p -> FloatArray(taps) { k -> (h[p + k * factor] * factor / sum).toFloat() } }
    }

    private fun bessel0(x: Double): Double {
        var s = 1.0; var t = 1.0; var k = 1
        while (k < 50) {
            t *= (x / (2 * k)) * (x / (2 * k))
            s += t
            if (t < 1e-12 * s) break
            k++
        }
        return s
    }

    /** in: チャンネル交互の float(frames フレーム)。out に frames*factor フレームを書く */
    fun process(input: FloatArray, frames: Int, out: FloatArray) {
        var o = 0
        for (f in 0 until frames) {
            // 履歴は 2 重に持ち、折り返しなしで連続して読めるようにする
            for (c in 0 until channels) {
                val v = input[f * channels + c]
                val hh = hist[c]
                hh[pos] = v
                hh[pos + taps] = v
            }
            for (p in 0 until factor) {
                val cf = coef[p]
                for (c in 0 until channels) {
                    val hh = hist[c]
                    var acc = 0f
                    // hist[pos] が最新、k 番目に古いものは pos - k
                    var idx = pos + taps
                    for (k in 0 until taps) {
                        acc += cf[k] * hh[idx]
                        idx--
                    }
                    out[o++] = acc
                }
            }
            pos++
            if (pos == taps) pos = 0
        }
    }
}
