package com.uyatame.ucrt

/**
 * イコライザーの数値入力の読み取り(Android の部品には頼らない。PC 上の単体テストで確かめられるように)。
 * 「1.5」「-3」「+2,5」(小数点のカンマも可)、周波数は「1.2k」「1k2」(= 1.2 kHz)、「Hz」「dB」「Q」などの単位も付けてよい。
 * 読めなければ null
 */
object EqNumber {
    fun parse(text: String, allowKilo: Boolean = false): Float? {
        var t = text.trim().lowercase().replace(" ", "").replace('，', ',').replace('．', '.').replace('−', '-').replace('ー', '-')
        // 全角の数字を半角に
        t = t.map { c -> if (c in '０'..'９') ('0' + (c - '０')) else c }.joinToString("")
        t = t.removeSuffix("db").removeSuffix("hz").removePrefix("q")
        t = t.replace(',', '.')
        if (t.isEmpty()) return null
        if (allowKilo && 'k' in t) {
            // 1.2k / 1k2(1k2 は 1.2k の書き方)
            val i = t.indexOf('k')
            val a = t.substring(0, i)
            val b = t.substring(i + 1)
            if (a.isEmpty() || !a.all { it.isDigit() || it == '.' }) return null
            if (b.isNotEmpty() && (!b.all { it.isDigit() } || '.' in a)) return null
            val v = (if (b.isEmpty()) a else "$a.$b").toFloatOrNull() ?: return null
            return (v * 1000f).takeIf { it.isFinite() }
        }
        return t.toFloatOrNull()?.takeIf { it.isFinite() }
    }
}
