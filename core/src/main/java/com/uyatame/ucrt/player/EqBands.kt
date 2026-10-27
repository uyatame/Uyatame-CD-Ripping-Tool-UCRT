package com.uyatame.ucrt.player

import com.uyatame.ucrt.R
import com.uyatame.ucrt.S

/** イコライザーのプリセット(10バンド、dB)。label は表示名(表示言語に合わせて読む) */
class EqPreset(val label: () -> String, val gains: FloatArray)

object EqBands {
    val FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    const val MAX_DB = 12f

    val PRESETS = listOf(
        EqPreset({ S(R.string.flat) }, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset({ S(R.string.bass_boost) }, floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset({ S(R.string.treble_boost) }, floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
        EqPreset({ S(R.string.vocal) }, floatArrayOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
        EqPreset({ S(R.string.rock) }, floatArrayOf(5f, 4f, 2f, -1f, -2f, -1f, 2f, 4f, 5f, 5f)),
        EqPreset({ S(R.string.pop) }, floatArrayOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f)),
        EqPreset({ S(R.string.jazz) }, floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
        EqPreset({ S(R.string.classical) }, floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)),
        EqPreset({ S(R.string.loudness) }, floatArrayOf(5f, 4f, 1f, 0f, -1f, 0f, 0f, 1f, 4f, 5f)),
        // iTunes の「Perfect」として広まった設定(全体に持ち上げるので、音割れを防ぐ自動の音量調整と合わせて使う)
        EqPreset({ S(R.string.perfect) }, floatArrayOf(3f, 6f, 9f, 7f, 6f, 5f, 7f, 9f, 11f, 8f)),
    )

    fun encode(g: FloatArray): String = g.joinToString(",") { "%.1f".format(java.util.Locale.US, it) }
    fun decode(s: String?): FloatArray {
        val v = s?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }.orEmpty()
        return FloatArray(FREQS.size) { i -> v.getOrElse(i) { 0f }.coerceIn(-MAX_DB, MAX_DB) }
    }
}
