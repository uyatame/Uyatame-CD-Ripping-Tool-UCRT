package com.uyatame.ucrt.convert

/**
 * 1 チャンネル分のデルタシグマ変調(7 次。ファイルへの DSD 変換用)。直線補間で DSD の周波数まで上げながら 1bit にする。
 *
 * ノイズシェーピングの伝達関数 NTF は、零点を可聴帯域(20 kHz まで)に最適に散らし(ルジャンドル多項式の根)、
 * 極はバターワースの高域通過で、高域の利得が 1.4 になるよう選んだもの(再生用の 5 次より可聴帯域の雑音が 30 dB ほど低い。
 * DSD64 で 1 kHz・0 dB のとき約 133 dB。入力 ±0.6 まで発散しないことを確かめてある)。
 * 誤差を返すフィルター (NTF - 1) を転置直接形 II で計算する(状態は 7 つ)。
 *
 * 入力は ±0.5 を DSD の 0 dB(変調度 50%)として扱う(PCM のフルスケールを DSD の 0 dB にするので、呼び出し側で 0.5 倍して渡す)。
 * 出力は 1 バイトに 8 ビット、先頭のビットを最上位に入れる(1 = プラス)
 */
class DsdModulator(dsdRate: Int, private val f2: Int) {
    private val c: DoubleArray
    private val a: DoubleArray
    private var s1 = 0.0; private var s2 = 0.0; private var s3 = 0.0; private var s4 = 0.0
    private var s5 = 0.0; private var s6 = 0.0; private var s7 = 0.0
    private var prev = 0f
    private var acc = 0
    private var nb = 0
    /** 発散しかけて立て直した回数(ログ用) */
    var resets = 0
        private set

    init {
        val set = when {
            dsdRate >= 5_000_000 -> 2
            dsdRate % 48000 == 0 -> 1
            else -> 0
        }
        c = C[set]
        a = A[set]
    }

    /** src の n サンプル(DSD の周波数 ÷ f2 の周波数)を変調して dst の先頭から書く。書いたバイト数を返す */
    fun modulate(src: FloatArray, n: Int, dst: ByteArray): Int {
        val c1 = c[0]; val c2 = c[1]; val c3 = c[2]; val c4 = c[3]; val c5 = c[4]; val c6 = c[5]; val c7 = c[6]
        val a1 = a[0]; val a2 = a[1]; val a3 = a[2]; val a4 = a[3]; val a5 = a[4]; val a6 = a[5]; val a7 = a[6]
        var x1 = s1; var x2 = s2; var x3 = s3; var x4 = s4; var x5 = s5; var x6 = s6; var x7 = s7
        var p = prev
        var byte = acc
        var bits = nb
        var o = 0
        val inv = 1f / f2
        for (i in 0 until n) {
            var cur = src[i]
            if (cur > 0.55f) cur = 0.55f else if (cur < -0.55f) cur = -0.55f else if (cur.isNaN()) cur = 0f
            val step = (cur - p) * inv
            var v0 = p.toDouble()
            val st = step.toDouble()
            for (j in 0 until f2) {
                v0 += st
                val w = x1
                val v = v0 + w
                val bit = v >= 0.0
                if (v > 8.0 || v < -8.0) {
                    // 万一発散しかけたら状態を消して立て直す
                    x1 = 0.0; x2 = 0.0; x3 = 0.0; x4 = 0.0; x5 = 0.0; x6 = 0.0; x7 = 0.0
                    resets++
                } else {
                    val e = (if (bit) 1.0 else -1.0) - v
                    x1 = x2 + c1 * e - a1 * w
                    x2 = x3 + c2 * e - a2 * w
                    x3 = x4 + c3 * e - a3 * w
                    x4 = x5 + c4 * e - a4 * w
                    x5 = x6 + c5 * e - a5 * w
                    x6 = x7 + c6 * e - a6 * w
                    x7 = c7 * e - a7 * w
                }
                byte = (byte shl 1) or (if (bit) 1 else 0)
                if (++bits == 8) {
                    dst[o++] = byte.toByte()
                    byte = 0
                    bits = 0
                }
            }
            p = cur
        }
        prev = p
        s1 = x1; s2 = x2; s3 = x3; s4 = x4; s5 = x5; s6 = x6; s7 = x7
        acc = byte
        nb = bits
        return o
    }

    /** 残りのビット(1 バイトに満たない分)を、無音(交互の 0 と 1)で埋めて書く。書いたバイト数(0 か 1) */
    fun flush(dst: ByteArray): Int {
        if (nb == 0) return 0
        var b = acc
        var k = nb
        while (k < 8) { b = (b shl 1) or (k and 1); k++ }
        dst[0] = b.toByte()
        acc = 0; nb = 0
        return 1
    }

    private companion object {
        // NTF - 1 の分子(c)と、分母(a)。z^-1 から z^-7 の係数(上の説明の方法で求めた値)
        // [0] 2.8224 MHz(DSD64)、[1] 3.072 MHz(48 kHz 系の DSD64)、[2] 5.6448 MHz 以上(DSD128〜512)
        val C = arrayOf(
            doubleArrayOf(-0.6704422097597762, 3.801656237459536, -8.999005675581177, 11.381485919398202, -8.111020838127084, 3.0879375548406562, -0.4906122191672),
            doubleArrayOf(-0.6706926891134017, 3.8028236468237075, -9.001035409263306, 11.382949391270522, -8.111148556652736, 3.087587427905149, -0.4904850388704334),
            doubleArrayOf(-0.6716474231168732, 3.8072741950893736, -9.008773909235387, 11.388528241155043, -8.111634026382555, 3.0862518132922205, -0.4900001071896377),
        )
        val A = arrayOf(
            doubleArrayOf(-6.326355889453069, 17.182337143476257, -25.968983970526583, 23.58650372670956, -12.872972542808709, 3.908860544372189, -0.5093877808328),
            doubleArrayOf(-6.326604532266021, 17.183664515633758, -25.97194297082981, 23.590028988822596, -12.87533960580473, 3.9097097934742746, -0.5095149611295666),
            doubleArrayOf(-6.327552031634722, 17.188723259001716, -25.983221179269982, 23.603466847350326, -12.884363427708537, 3.912947641459375, -0.5099998928103623),
        )
    }
}
