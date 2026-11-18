package com.uyatame.ucrt.player

/**
 * 16 bit へ丸めるときのディザー(TPDF: 三角分布の小さな雑音を足してから丸める)。
 * 丸めの誤差が音に付きまとうひずみになるのを、音と関係のない、ごく小さな一様の雑音(約 -96 dBFS)に変える。
 * Android の部品には頼らない(PC 上の単体テストで確かめられるように)。
 * ちょうど 0 の値(無音)には雑音を足さない(曲間などの無音を、無音のまま送る)
 */
class Tpdf(seed: Long = System.nanoTime()) {
    private var s = if (seed == 0L) 0x2545F4914F6CDD1DL else seed

    /** -0.5〜0.5 の一様な値 */
    private fun uni(): Float {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        s = x
        return ((x ushr 40).toInt() / 16777216f) - 0.5f
    }

    /** -1〜1 LSB の三角分布(一様な値 2 つの和) */
    fun next(): Float = uni() + uni()
}

/**
 * -1〜1 の値(x × gain)を 16 bit の整数にする。dither があればディザーを掛けてから、なければそのまま最も近い値に丸める。
 * 範囲の外は端に収める
 */
fun quantize16(x: Float, gain: Float, dither: Tpdf?): Int {
    val v = x * gain
    if (v == 0f) return 0
    val y = v * 32768f + (dither?.next() ?: 0f)
    val r = Math.round(y)
    return if (r > 32767) 32767 else if (r < -32768) -32768 else r
}
