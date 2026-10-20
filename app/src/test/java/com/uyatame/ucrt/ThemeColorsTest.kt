package com.uyatame.ucrt

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** テーマの色から作る色の組み合わせを確かめる */
class ThemeColorsTest {
    private fun rgb(c: Color): Int =
        (Math.round(c.red * 255) shl 16) or (Math.round(c.green * 255) shl 8) or Math.round(c.blue * 255)

    /** 文字と背景の明るさの差(WCAG のコントラスト比) */
    private fun contrast(a: Color, b: Color): Double {
        fun lum(c: Color): Double {
            fun ch(v: Float): Double = if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun standardPurpleMatchesMaterialBaseline() {
        // Material 3 の標準の紫(#6750A4)を元にすると、ライトテーマの主の色はその色になる
        val s = ThemeColors.scheme(0xFF6750A4.toInt(), dark = false)
        val p = rgb(s.primary)
        val want = 0x6750A4
        for (shift in intArrayOf(16, 8, 0)) {
            assertTrue("primary = %06X".format(p), abs(((p shr shift) and 0xFF) - ((want shr shift) and 0xFF)) <= 2)
        }
    }

    @Test
    fun textIsReadableForEveryPreset() {
        for (seed in ThemeColors.presets.filter { it != 0 }) for (dark in listOf(false, true)) {
            val s = ThemeColors.scheme(seed, dark)
            val name = "%08X %s".format(seed, if (dark) "dark" else "light")
            assertTrue("$name: 背景の上の文字", contrast(s.onSurface, s.surface) >= 4.5)
            assertTrue("$name: 主の色の上の文字", contrast(s.onPrimary, s.primary) >= 4.5)
        }
    }

    @Test
    fun hueAndSaturationRoundTrip() {
        val c = ThemeColors.fromHue(120f, 1f)
        val (h, _) = ThemeColors.hueSat(c)
        assertEquals(120f, h, 1f)
    }
}
