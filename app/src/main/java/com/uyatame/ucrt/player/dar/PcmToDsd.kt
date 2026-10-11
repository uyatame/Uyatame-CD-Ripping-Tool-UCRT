package com.uyatame.ucrt.player.dar

import com.uyatame.ucrt.player.Upsampler

/**
 * PCM を DSD(1bit)に変換する(DAR の DSD 変換)。
 *
 * 1. 多相 FIR で 352.8 / 384 kHz 付近まで整数倍にアップサンプリング
 * 2. 直線補間で DSD の周波数まで上げる
 * 3. 5 次のデルタシグマ変調で 1bit にする
 *
 * ratio は DSD の周波数 ÷ 音源の周波数(整数)。出力は 1 チャンネルごとのバイト列(先頭のビットが先、1 = プラス)。
 * DSD の出力先はまとまった単位でしか受け取れないので、align バイトの倍数になるまで残りを持ち越す。
 */
internal class PcmToDsd(srcRate: Int, private val channels: Int, private val ratio: Int, private val align: Int) {
    /** 1 段目(FIR)の倍率。2 段目(直線補間)は ratio / f1 */
    private val f1: Int
    private val f2: Int
    private val up: Upsampler?

    init {
        var f = 1
        for (c in 1..ratio) {
            if (ratio % c == 0 && srcRate.toLong() * c <= 384_000L) f = c
        }
        f1 = f
        f2 = ratio / f
        up = if (f1 > 1) Upsampler(f1, channels) else null
    }

    private var stage = FloatArray(0)
    /** 直線補間の 1 つ前の値 */
    private val prev = FloatArray(channels)

    // デルタシグマ変調器の状態(チャンネルごと)。e = 量子化誤差、w = 帰還フィルターの出力
    private val e1 = DoubleArray(channels); private val e2 = DoubleArray(channels); private val e3 = DoubleArray(channels)
    private val e4 = DoubleArray(channels); private val e5 = DoubleArray(channels)
    private val w1 = DoubleArray(channels); private val w2 = DoubleArray(channels); private val w3 = DoubleArray(channels)
    private val w4 = DoubleArray(channels); private val w5 = DoubleArray(channels)

    // 作りかけのバイト(チャンネルごと)
    private val acc = IntArray(channels)
    private var bits = 0

    /** align に満たず、まだ渡していないバイト */
    private val carry = Array(channels) { ByteArray(align) }
    private var carryLen = 0

    /** frames フレームを変換したときに出る最大のバイト数(1 チャンネルあたり) */
    fun maxOut(frames: Int): Int = (frames.toLong() * ratio / 8 + align + 1).toInt()

    /**
     * input: チャンネル交互の float(frames フレーム)。gain を掛けてから変換する。
     * out[c] にチャンネルごとの DSD バイトを書き、書いたバイト数(align の倍数)を返す。
     */
    fun process(input: FloatArray, frames: Int, gain: Float, out: Array<ByteArray>): Int {
        if (frames <= 0) return 0
        val src: FloatArray
        val n1: Int
        val u = up
        if (u != null) {
            n1 = frames * f1
            if (stage.size < n1 * channels) stage = FloatArray(n1 * channels)
            u.process(input, frames, stage)
            src = stage
        } else {
            n1 = frames
            src = input
        }
        // 前回の残りを先頭に置く
        var o = carryLen
        for (c in 0 until channels) System.arraycopy(carry[c], 0, out[c], 0, carryLen)
        val startBits = bits
        val startO = o
        for (c in 0 until channels) {
            o = modulate(c, src, n1, gain, out[c], startO, startBits)
        }
        // 全チャンネルで同じだけ進むので、最後のチャンネルの値を使う
        val total = o
        val emit = total / align * align
        carryLen = total - emit
        for (c in 0 until channels) System.arraycopy(out[c], emit, carry[c], 0, carryLen)
        return emit
    }

    /** 1 チャンネル分を変調して dst[o..] に書く。書き終えた位置を返す */
    private fun modulate(c: Int, src: FloatArray, n1: Int, gain: Float, dst: ByteArray, o0: Int, bits0: Int): Int {
        // ノイズシェーピング: NTF = (1 - z^-1)^5 / D(z)。D は高域の利得が 1.5 になるよう選んだ極
        val fa1 = -0.807717848861496; val fa2 = 2.9142085842917167; val fa3 = -3.9703926164888994
        val fa4 = 2.418792061036653; val fa5 = -0.5555555559879046
        val d1 = -4.192282151138504; val d2 = 7.085791415708283; val d3 = -6.029607383511101
        val d4 = 2.581207938963347; val d5 = -0.4444444440120954
        var x1 = e1[c]; var x2 = e2[c]; var x3 = e3[c]; var x4 = e4[c]; var x5 = e5[c]
        var y1 = w1[c]; var y2 = w2[c]; var y3 = w3[c]; var y4 = w4[c]; var y5 = w5[c]
        var a = acc[c]
        var nb = bits0
        var o = o0
        var p = prev[c]
        // DSD の 0 dB(変調度 50%)に合わせて半分にし、変調器が不安定にならないよう ±0.5 で抑える
        val g = gain * 0.5f
        val inv = 1f / f2
        for (i in 0 until n1) {
            var cur = src[i * channels + c] * g
            if (cur > 0.5f) cur = 0.5f else if (cur < -0.5f) cur = -0.5f else if (cur.isNaN()) cur = 0f
            val step = (cur - p) * inv
            var v0 = p
            for (j in 0 until f2) {
                v0 += step
                val w = fa1 * x1 + fa2 * x2 + fa3 * x3 + fa4 * x4 + fa5 * x5 -
                    (d1 * y1 + d2 * y2 + d3 * y3 + d4 * y4 + d5 * y5)
                val v = v0 + w
                val bit = v >= 0.0
                val e = (if (bit) 1.0 else -1.0) - v
                if (v > 8.0 || v < -8.0) {
                    // 万一発散しかけたら状態を消して立て直す(音が一瞬途切れるだけで済むように)
                    x1 = 0.0; x2 = 0.0; x3 = 0.0; x4 = 0.0; x5 = 0.0
                    y1 = 0.0; y2 = 0.0; y3 = 0.0; y4 = 0.0; y5 = 0.0
                } else {
                    x5 = x4; x4 = x3; x3 = x2; x2 = x1; x1 = e
                    y5 = y4; y4 = y3; y3 = y2; y2 = y1; y1 = w
                }
                a = (a shl 1) or (if (bit) 1 else 0)
                nb++
                if (nb == 8) {
                    dst[o++] = a.toByte()
                    a = 0
                    nb = 0
                }
            }
            p = cur
        }
        prev[c] = p
        e1[c] = x1; e2[c] = x2; e3[c] = x3; e4[c] = x4; e5[c] = x5
        w1[c] = y1; w2[c] = y2; w3[c] = y3; w4[c] = y4; w5[c] = y5
        acc[c] = a
        // ビットの位置は全チャンネルで同じ。最後のチャンネルのときに進める
        if (c == channels - 1) bits = nb
        return o
    }
}
