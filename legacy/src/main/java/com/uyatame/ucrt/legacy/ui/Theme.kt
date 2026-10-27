package com.uyatame.ucrt.legacy.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * 画面の色(本家の UCRT と同じ Material 3 の色の組み合わせ)。
 * テーマの色を選んでいなければ標準の紫、選んでいればその色から作る(本家の ThemeColors と同じ作り方)
 */
object Pal {
    var dark = false; private set
    var primary = 0; private set
    var onPrimary = 0; private set
    var primaryContainer = 0; private set
    var onPrimaryContainer = 0; private set
    var secondary = 0; private set
    var secondaryContainer = 0; private set
    var onSecondaryContainer = 0; private set
    var tertiary = 0; private set
    var tertiaryContainer = 0; private set
    var onTertiaryContainer = 0; private set
    var surface = 0; private set
    var onSurface = 0; private set
    var surfaceVariant = 0; private set
    var onSurfaceVariant = 0; private set
    var outline = 0; private set
    var outlineVariant = 0; private set
    var containerLow = 0; private set
    var container = 0; private set
    var containerHigh = 0; private set
    var containerHighest = 0; private set
    var inverseSurface = 0; private set
    var inverseOnSurface = 0; private set
    var error = 0; private set

    /** ハイレゾの金色(本家と同じ) */
    const val HIRES_GOLD = 0xFFE2B24A.toInt()
    const val HIRES_INK = 0xFF2E2200.toInt()

    /** themeMode: 0 = 端末に合わせる、1 = 明るい、2 = 暗い。seed: テーマの色(0 = 標準の紫)。text: 文字の色(0 = 標準) */
    fun apply(ctx: Context, themeMode: Int, seed: Int, text: Int) {
        val sys = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        dark = when (themeMode) { 1 -> false; 2 -> true; else -> sys }
        if (seed == 0) baseline() else fromSeed(seed)
        if (text != 0) {
            onSurface = text
            onSurfaceVariant = mix(text, surface, 0.3f)
        }
    }

    /** a と b を t の割合で混ぜる(t = 0 で a) */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt().coerceIn(0, 255)
        return Color.argb(255, ch(16), ch(8), ch(0))
    }

    /** 色に透明度を付ける(0〜1) */
    fun alpha(c: Int, a: Float): Int = (c and 0xFFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)

    private fun baseline() {
        if (!dark) {
            primary = 0xFF6750A4.toInt(); onPrimary = Color.WHITE
            primaryContainer = 0xFFEADDFF.toInt(); onPrimaryContainer = 0xFF21005D.toInt()
            secondary = 0xFF625B71.toInt(); secondaryContainer = 0xFFE8DEF8.toInt(); onSecondaryContainer = 0xFF1D192B.toInt()
            tertiary = 0xFF7D5260.toInt(); tertiaryContainer = 0xFFFFD8E4.toInt(); onTertiaryContainer = 0xFF31111D.toInt()
            surface = 0xFFFEF7FF.toInt(); onSurface = 0xFF1D1B20.toInt()
            surfaceVariant = 0xFFE7E0EC.toInt(); onSurfaceVariant = 0xFF49454F.toInt()
            outline = 0xFF79747E.toInt(); outlineVariant = 0xFFCAC4D0.toInt()
            containerLow = 0xFFF7F2FA.toInt(); container = 0xFFF3EDF7.toInt()
            containerHigh = 0xFFECE6F0.toInt(); containerHighest = 0xFFE6E0E9.toInt()
            inverseSurface = 0xFF322F35.toInt(); inverseOnSurface = 0xFFF5EFF7.toInt()
            error = 0xFFB3261E.toInt()
        } else {
            primary = 0xFFD0BCFF.toInt(); onPrimary = 0xFF381E72.toInt()
            primaryContainer = 0xFF4F378B.toInt(); onPrimaryContainer = 0xFFEADDFF.toInt()
            secondary = 0xFFCCC2DC.toInt(); secondaryContainer = 0xFF4A4458.toInt(); onSecondaryContainer = 0xFFE8DEF8.toInt()
            tertiary = 0xFFEFB8C8.toInt(); tertiaryContainer = 0xFF633B48.toInt(); onTertiaryContainer = 0xFFFFD8E4.toInt()
            surface = 0xFF141218.toInt(); onSurface = 0xFFE6E0E9.toInt()
            surfaceVariant = 0xFF49454F.toInt(); onSurfaceVariant = 0xFFCAC4D0.toInt()
            outline = 0xFF938F99.toInt(); outlineVariant = 0xFF49454F.toInt()
            containerLow = 0xFF1D1B20.toInt(); container = 0xFF211F26.toInt()
            containerHigh = 0xFF2B2930.toInt(); containerHighest = 0xFF36343B.toInt()
            inverseSurface = 0xFFE6E0E9.toInt(); inverseOnSurface = 0xFF322F35.toInt()
            error = 0xFFF2B8B5.toInt()
        }
    }

    private fun fromSeed(seed: Int) {
        val (_, c0, h) = lch(seed)
        val pc = c0.coerceAtLeast(30.0)
        val sc = (c0 / 3).coerceAtMost(16.0)
        val tc = (c0 / 2).coerceAtMost(24.0).coerceAtLeast(12.0)
        val th = h + Math.toRadians(60.0)
        val nc = (c0 / 12).coerceAtMost(4.0)
        val nvc = (c0 / 6).coerceAtMost(8.0)
        fun p(t: Int) = toArgb(t.toDouble(), pc, h)
        fun s(t: Int) = toArgb(t.toDouble(), sc, h)
        fun t(t: Int) = toArgb(t.toDouble(), tc, th)
        fun n(t: Int) = toArgb(t.toDouble(), nc, h)
        fun nv(t: Int) = toArgb(t.toDouble(), nvc, h)
        if (!dark) {
            primary = p(40); onPrimary = p(100); primaryContainer = p(90); onPrimaryContainer = p(10)
            secondary = s(40); secondaryContainer = s(90); onSecondaryContainer = s(10)
            tertiary = t(40); tertiaryContainer = t(90); onTertiaryContainer = t(10)
            surface = n(98); onSurface = n(10); surfaceVariant = nv(90); onSurfaceVariant = nv(30)
            outline = nv(50); outlineVariant = nv(80)
            containerLow = n(96); container = n(94); containerHigh = n(92); containerHighest = n(90)
            inverseSurface = n(20); inverseOnSurface = n(95)
            error = 0xFFB3261E.toInt()
        } else {
            primary = p(80); onPrimary = p(20); primaryContainer = p(30); onPrimaryContainer = p(90)
            secondary = s(80); secondaryContainer = s(30); onSecondaryContainer = s(90)
            tertiary = t(80); tertiaryContainer = t(30); onTertiaryContainer = t(90)
            surface = n(6); onSurface = n(90); surfaceVariant = nv(30); onSurfaceVariant = nv(80)
            outline = nv(60); outlineVariant = nv(30)
            containerLow = n(10); container = n(12); containerHigh = n(17); containerHighest = n(22)
            inverseSurface = n(90); inverseOnSurface = n(20)
            error = 0xFFF2B8B5.toInt()
        }
    }

    /** 選べるテーマの色の見本(0 は標準の紫。本家と同じ) */
    val presets = listOf(
        0, 0xFF3F51B5.toInt(), 0xFF1E88E5.toInt(), 0xFF00897B.toInt(), 0xFF43A047.toInt(),
        0xFFC0CA33.toInt(), 0xFFFFB300.toInt(), 0xFFF4511E.toInt(), 0xFFE53935.toInt(), 0xFFD81B60.toInt(), 0xFF8E24AA.toInt(),
        0xFF6D4C41.toInt(), 0xFF546E7A.toInt(),
    )

    // ---- 色の変換(sRGB ⇔ CIE L*a*b* の極座標 LCh。本家の ThemeColors と同じ) ----

    private fun lin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun gam(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
    private fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
    private fun fi(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)
    private const val XN = 0.95047
    private const val ZN = 1.08883

    private fun lch(argb: Int): Triple<Double, Double, Double> {
        val r = lin((argb shr 16) and 0xFF); val g = lin((argb shr 8) and 0xFF); val b = lin(argb and 0xFF)
        val x = 0.4124 * r + 0.3576 * g + 0.1805 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.0193 * r + 0.1192 * g + 0.9505 * b
        val fx = f(x / XN); val fy = f(y); val fz = f(z / ZN)
        return Triple(116 * fy - 16, hypot(500 * (fx - fy), 200 * (fy - fz)), atan2(200 * (fy - fz), 500 * (fx - fy)))
    }

    private fun toArgb(l: Double, chroma: Double, hue: Double): Int {
        rgbOf(l, chroma, hue)?.let { return it }
        var lo = 0.0
        var hi = chroma
        var best = rgbOf(l, 0.0, hue)!!
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

/** 文字の大きさと太さ(本家の Material 3 の文字の種類と同じ大きさ) */
enum class Type(val sp: Float, val medium: Boolean) {
    HeadlineMedium(28f, false), HeadlineSmall(24f, false),
    TitleLarge(22f, false), TitleMedium(16f, true), TitleSmall(14f, true),
    BodyLarge(16f, false), BodyMedium(14f, false), BodySmall(12f, false),
    LabelLarge(14f, true), LabelMedium(12f, true), LabelSmall(11f, true);

    companion object {
        /** 中くらいの太さの書体(Android 5.0 から。それより前は太字) */
        val mediumFace: Typeface by lazy {
            if (Build.VERSION.SDK_INT >= 21) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT_BOLD
        }
    }
}
