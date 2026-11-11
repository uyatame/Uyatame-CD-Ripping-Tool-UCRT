package com.uyatame.ucrt.network

/**
 * ファイル名の自然な順(数字の連なりは、数の大きさで比べる。大文字・小文字は区別しない)。
 * 「2.flac」が「10.flac」より前に来る(文字の順だと「10.flac」が先になってしまう)。
 * 長い数字でも溢れないよう、数にせず、桁数 → 文字の順で比べる
 */
object NaturalOrder : Comparator<String> {
    private fun digit(c: Char) = c in '0'..'9'

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (digit(ca) && digit(cb)) {
                var ei = i
                while (ei < a.length && digit(a[ei])) ei++
                var ej = j
                while (ej < b.length && digit(b[ej])) ej++
                // 先頭の 0 を除いた数字(全部 0 なら 1 桁だけ残す)
                var si = i
                while (si < ei - 1 && a[si] == '0') si++
                var sj = j
                while (sj < ej - 1 && b[sj] == '0') sj++
                val la = ei - si
                val lb = ej - sj
                if (la != lb) return la - lb
                for (k in 0 until la) {
                    val d = a[si + k] - b[sj + k]
                    if (d != 0) return d
                }
                // 数として同じなら、先頭の 0 が少ないほうを先に(「1」は「01」より前)
                val za = si - i
                val zb = sj - j
                if (za != zb) return za - zb
                i = ei
                j = ej
            } else {
                val la = Character.toLowerCase(ca)
                val lb = Character.toLowerCase(cb)
                if (la != lb) return la - lb
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
