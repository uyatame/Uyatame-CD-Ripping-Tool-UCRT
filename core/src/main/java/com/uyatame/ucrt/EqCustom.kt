package com.uyatame.ucrt

import com.uyatame.ucrt.player.EqBand
import com.uyatame.ucrt.player.EqBands
import com.uyatame.ucrt.player.ParamEq
import kotlin.math.abs

// 保存したイコライザーと、今の設定のプリセットの名前(app と legacy で共通)

/**
 * 保存したイコライザー。pro なら data は PRO の帯域(ParamEq.encode)、そうでなければシンプルの 10 バンドの値(EqBands.encode)
 */
data class EqCustom(val name: String, val pro: Boolean, val data: String, val preamp: Float) {
    fun bands(): List<EqBand> = if (pro) ParamEq.decode(data) else ParamEq.fromGains(EqBands.decode(data))
    fun gains(): FloatArray = EqBands.decode(data)

    companion object {
        /** 1 行に 1 つ: 名前(URL エンコード)|s または p|値|プリアンプ */
        fun encode(list: List<EqCustom>): String = list.joinToString("\n") {
            java.net.URLEncoder.encode(it.name, "UTF-8") + "|" + (if (it.pro) "p" else "s") + "|" + it.data + "|" +
                "%.1f".format(java.util.Locale.US, it.preamp)
        }

        fun decode(s: String): List<EqCustom> = s.split('\n').mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 4) return@mapNotNull null
            val name = runCatching { java.net.URLDecoder.decode(p[0], "UTF-8") }.getOrNull() ?: return@mapNotNull null
            EqCustom(name, p[1] == "p", p[2], p[3].toFloatOrNull() ?: 0f)
        }
    }
}

fun sameGains(a: FloatArray, b: FloatArray): Boolean = a.size == b.size && a.indices.all { abs(a[it] - b[it]) < 0.05f }

fun sameBandList(a: List<EqBand>, b: List<EqBand>): Boolean =
    a.size == b.size && a.indices.all { i ->
        a[i].type == b[i].type && abs(a[i].freq - b[i].freq) < 0.5f && abs(a[i].gain - b[i].gain) < 0.05f && abs(a[i].q - b[i].q) < 0.01f
    }

/**
 * 今の設定が、あるプリセットと同じなら、その名前(どれとも違えば null)。
 * mode: 0 = シンプル、1 = PRO。simple: シンプルの 10 バンドの値。bands: PRO の帯域。custom: 保存したイコライザー(EqCustom.encode)
 */
fun eqPresetNameOf(mode: Int, simple: FloatArray, bands: List<EqBand>, custom: String): String? {
    if (mode == 0) {
        EqBands.PRESETS.firstOrNull { sameGains(it.gains, simple) }?.let { return it.label() }
        return EqCustom.decode(custom).firstOrNull { !it.pro && sameGains(it.gains(), simple) }?.name
    }
    EqBands.PRESETS.firstOrNull { sameBandList(ParamEq.fromGains(it.gains), bands) }?.let { return it.label() }
    return EqCustom.decode(custom).firstOrNull { sameBandList(it.bands(), bands) }?.name
}
