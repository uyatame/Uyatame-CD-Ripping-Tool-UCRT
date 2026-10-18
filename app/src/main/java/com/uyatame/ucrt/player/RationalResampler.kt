package com.uyatame.ucrt.player

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 整数倍でない周波数の変換(例: 176.4 kHz → 192 kHz は 160/147 倍)。多相 FIR、カイザー窓。
 *
 * DAR で 44.1 kHz の系統の音を、48 kHz の系統の周波数しか受け付けない出力先(イヤホンジャックなど)へ出すときに、
 * 整数倍のアップサンプリング(Upsampler)のあとに通す。前の段で音はすでに元の周波数の 0.46 倍までに絞られ、
 * その上の帯域は空いているので、遷移の幅を広く取れて短いフィルターで済む。
 *
 * 入力はチャンネル交互の float。出力の数は入力 1 フレームあたり up/down フレーム前後(端数は次に持ち越す)。
 */
class RationalResampler(
    inRate: Int,
    outRate: Int,
    private val channels: Int,
    /** 切る周波数(Hz。null なら入力と出力の低いほうのナイキスト周波数の 0.95 倍) */
    cutHz: Double? = null,
    /** 1 位相あたりのタップ数(遷移の幅を狭くするほど多く要る) */
    private val taps: Int = 24,
) {
    val up: Int
    val down: Int
    /** cr[phase][j]: 新しい順に並べた履歴に掛ける係数 */
    private val cr: Array<FloatArray>
    private val hist = Array(channels) { FloatArray(taps * 2) }
    private var pos = 0
    /** 次に出す出力の位相(0 ≤ phase < up なら、いまの入力で出せる) */
    private var phase = 0

    init {
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        // 仮想の周波数(入力の up 倍)で設計する。入力と出力の低いほうのナイキスト周波数の少し手前で切る
        // (通す帯域 passHz は十分下にあり、前の段の折り返しは入力の周波数 - passHz より上にしかない)
        val virt = inRate.toDouble() * up
        val cut = cutHz ?: (minOf(outRate, inRate) / 2.0 * 0.95)
        val fc = cut / virt
        val n = up * taps
        val mid = (n - 1) / 2.0
        val beta = 10.0
        val h = DoubleArray(n) { i ->
            val x = i - mid
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val r = 2.0 * i / (n - 1) - 1.0
            sinc * bessel0(beta * sqrt((1 - r * r).coerceAtLeast(0.0))) / bessel0(beta)
        }
        val sum = h.sum()
        // y[n] = Σ h[p + t·up] · x[j0 - t]  (p: 位相、j0: いちばん新しい入力)
        cr = Array(up) { p -> FloatArray(taps) { t -> (h[p + t * up] * up / sum).toFloat() } }
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

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

    /** frames フレーム入れたときに出る最大のフレーム数 */
    fun maxOut(frames: Int): Int = (frames.toLong() * up / down).toInt() + 2

    /** フィルターの遅れ(入力のフレーム数)。最後にこれだけ無音を入れると、残りの音が出てくる */
    val delayFrames: Int get() = taps / 2 + 1

    /** 変換して out に書き、書いたフレーム数を返す */
    fun process(input: FloatArray, frames: Int, out: FloatArray): Int {
        val ch = channels
        val t = taps
        var o = 0
        var i = 0
        var p = phase
        var ps = pos
        for (f in 0 until frames) {
            // 履歴は 2 重に持ち、折り返しなしで読めるようにする。ps + t がいちばん新しい
            for (c in 0 until ch) {
                val v = input[i + c]
                hist[c][ps] = v
                hist[c][ps + t] = v
            }
            i += ch
            val newest = ps + t
            while (p < up) {
                val cf = cr[p]
                for (c in 0 until ch) {
                    val hh = hist[c]
                    var acc = 0f
                    var k = newest
                    for (j in 0 until t) {
                        acc += cf[j] * hh[k]
                        k--
                    }
                    out[o + c] = acc
                }
                o += ch
                p += down
            }
            p -= up
            ps++
            if (ps == t) ps = 0
        }
        phase = p
        pos = ps
        return o / ch
    }
}
