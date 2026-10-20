package com.uyatame.ucrt

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * 選んだ 1 色(テーマの色)から、画面全体の色の組み合わせを作る(ダイナミックカラーを使わないとき)。
 * Material 3 の作り方にならい、色相と鮮やかさはそのままに、明るさ(L*)だけを変えた色を役割ごとに使う
 */
object ThemeColors {
    /** 選べる色の見本(0 は標準の紫) */
    val presets = listOf(
        0, 0xFF3F51B5.toInt(), 0xFF1E88E5.toInt(), 0xFF00897B.toInt(), 0xFF43A047.toInt(),
        0xFFC0CA33.toInt(), 0xFFFFB300.toInt(), 0xFFF4511E.toInt(), 0xFFE53935.toInt(), 0xFFD81B60.toInt(), 0xFF8E24AA.toInt(),
        0xFF6D4C41.toInt(), 0xFF546E7A.toInt(),
    )

    /** 色相(0〜360)と鮮やかさ(0〜1)から見本の色を作る(明るさは中くらい) */
    fun fromHue(hue: Float, sat: Float): Int = toArgb(55.0, 90.0 * sat, Math.toRadians(hue.toDouble()))

    /** 色の色相(0〜360)と鮮やかさ(0〜1) */
    fun hueSat(argb: Int): Pair<Float, Float> {
        val (_, c, h) = lch(argb)
        val deg = Math.toDegrees(h).let { if (it < 0) it + 360 else it }
        return deg.toFloat() to (c / 90.0).coerceIn(0.0, 1.0).toFloat()
    }

    fun scheme(seed: Int, dark: Boolean): ColorScheme {
        val (_, c0, h) = lch(seed)
        // 役割ごとの鮮やかさ(主の色は元の色に近く、ほかは控えめに)
        val pc = c0.coerceAtLeast(30.0)
        val sc = (c0 / 3).coerceAtMost(16.0)
        val tc = (c0 / 2).coerceAtMost(24.0).coerceAtLeast(12.0)
        val th = h + Math.toRadians(60.0)
        val nc = (c0 / 12).coerceAtMost(4.0)
        val nvc = (c0 / 6).coerceAtMost(8.0)
        fun p(t: Int) = Color(toArgb(t.toDouble(), pc, h))
        fun s(t: Int) = Color(toArgb(t.toDouble(), sc, h))
        fun t(t: Int) = Color(toArgb(t.toDouble(), tc, th))
        fun n(t: Int) = Color(toArgb(t.toDouble(), nc, h))
        fun nv(t: Int) = Color(toArgb(t.toDouble(), nvc, h))
        return if (!dark) lightColorScheme(
            primary = p(40), onPrimary = p(100), primaryContainer = p(90), onPrimaryContainer = p(10), inversePrimary = p(80),
            secondary = s(40), onSecondary = s(100), secondaryContainer = s(90), onSecondaryContainer = s(10),
            tertiary = t(40), onTertiary = t(100), tertiaryContainer = t(90), onTertiaryContainer = t(10),
            background = n(98), onBackground = n(10), surface = n(98), onSurface = n(10),
            surfaceVariant = nv(90), onSurfaceVariant = nv(30), surfaceTint = p(40),
            inverseSurface = n(20), inverseOnSurface = n(95), outline = nv(50), outlineVariant = nv(80),
            surfaceBright = n(98), surfaceDim = n(87), surfaceContainerLowest = n(100), surfaceContainerLow = n(96),
            surfaceContainer = n(94), surfaceContainerHigh = n(92), surfaceContainerHighest = n(90),
        ) else darkColorScheme(
            primary = p(80), onPrimary = p(20), primaryContainer = p(30), onPrimaryContainer = p(90), inversePrimary = p(40),
            secondary = s(80), onSecondary = s(20), secondaryContainer = s(30), onSecondaryContainer = s(90),
            tertiary = t(80), onTertiary = t(20), tertiaryContainer = t(30), onTertiaryContainer = t(90),
            background = n(6), onBackground = n(90), surface = n(6), onSurface = n(90),
            surfaceVariant = nv(30), onSurfaceVariant = nv(80), surfaceTint = p(80),
            inverseSurface = n(90), inverseOnSurface = n(20), outline = nv(60), outlineVariant = nv(30),
            surfaceBright = n(24), surfaceDim = n(6), surfaceContainerLowest = n(4), surfaceContainerLow = n(10),
            surfaceContainer = n(12), surfaceContainerHigh = n(17), surfaceContainerHighest = n(22),
        )
    }

    // ---- 色の変換(sRGB ⇔ CIE L*a*b* の極座標 LCh) ----

    private fun lin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun gam(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055

    private fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
    private fun fi(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)

    private const val XN = 0.95047
    private const val ZN = 1.08883

    /** L*・彩度・色相(ラジアン) */
    private fun lch(argb: Int): Triple<Double, Double, Double> {
        val r = lin((argb shr 16) and 0xFF); val g = lin((argb shr 8) and 0xFF); val b = lin(argb and 0xFF)
        val x = 0.4124 * r + 0.3576 * g + 0.1805 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.0193 * r + 0.1192 * g + 0.9505 * b
        val fx = f(x / XN); val fy = f(y); val fz = f(z / ZN)
        val l = 116 * fy - 16
        val a = 500 * (fx - fy); val bb = 200 * (fy - fz)
        return Triple(l, hypot(a, bb), atan2(bb, a))
    }

    /** 明るさ l の色を作る。表せない鮮やかさのときは、表せるところまで下げる */
    private fun toArgb(l: Double, chroma: Double, hue: Double): Int {
        var lo = 0.0
        var hi = chroma
        var best = rgbOf(l, 0.0, hue)!!
        // 表せるいちばん鮮やかな色を二分探索で探す
        rgbOf(l, chroma, hue)?.let { return it }
        repeat(16) {
            val mid = (lo + hi) / 2
            val c = rgbOf(l, mid, hue)
            if (c != null) { best = c; lo = mid } else hi = mid
        }
        return best
    }

    private fun rgbOf(l: Double, c: Double, h: Double): Int? {
        val fy = (l + 16) / 116
        val fx = fy + c * cos(h) / 500
        val fz = fy - c * sin(h) / 200
        val x = XN * fi(fx); val y = fi(fy); val z = ZN * fi(fz)
        val r = 3.2406 * x - 1.5372 * y - 0.4986 * z
        val g = -0.9689 * x + 1.8758 * y + 0.0415 * z
        val b = 0.0557 * x - 0.2040 * y + 1.0570 * z
        val e = 1e-4
        if (r < -e || g < -e || b < -e || r > 1 + e || g > 1 + e || b > 1 + e) return null
        fun ch(v: Double) = (gam(v.coerceIn(0.0, 1.0)) * 255 + 0.5).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
