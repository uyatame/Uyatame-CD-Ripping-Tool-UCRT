package com.uyatame.ucrt.legacy

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.RadioView
import com.uyatame.ucrt.legacy.ui.SliderView
import com.uyatame.ucrt.legacy.ui.SwitchView
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.addDivider
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.card
import com.uyatame.ucrt.legacy.ui.chip
import com.uyatame.ucrt.legacy.ui.chipRow
import com.uyatame.ucrt.legacy.ui.circle
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.icon
import com.uyatame.ucrt.legacy.ui.rounded
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import kotlin.math.roundToInt

// =====================================================================
// 設定の画面の部品(本家の Section・SwitchRow・IntSlider・Segmented・ChoiceList・CategoryRow)
// =====================================================================

/** 見出し付きのカード(中の部品は 12dp ずつ空ける) */
class Section(ctx: Context, title: String) {
    val view: LinearLayout = card(ctx)
    private var first = true

    init {
        view.setPadding(dp(16), dp(16), dp(16), dp(16))
        view.add(text(ctx, title, Type.TitleMedium, Pal.primary, lines = 2))
        first = false
    }

    fun add(v: View): View = view.add(v, t = 12)
}

/** スイッチの行 */
fun switchRow(ctx: Context, title: String, desc: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): View {
    val row = hbox(ctx)
    val col = vbox(ctx)
    col.add(text(ctx, title, Type.BodyLarge, Pal.onSurface, lines = 0))
    if (desc != null) col.add(text(ctx, desc, Type.BodySmall, Pal.onSurfaceVariant, lines = 0))
    row.add(col, w = 0, weight = 1f, r = 12)
    val sw = SwitchView(ctx, checked, null)
    sw.isEnabled = enabled
    row.add(sw, w = WRAP, h = WRAP)
    if (enabled) row.tap(8f) { sw.checked = !sw.checked; onChange(sw.checked) } else row.alpha = 0.45f
    return row
}

/** 整数のスライダー(本家の IntSlider。動かしている間は表示だけ、指を離したら保存) */
fun intSlider(ctx: Context, title: String, value: Int, range: IntRange, label: (Int) -> String, desc: String? = null, enabled: Boolean = true, onDone: (Int) -> Unit): View {
    val col = vbox(ctx)
    val top = hbox(ctx)
    top.add(text(ctx, title, Type.BodyLarge, Pal.onSurface, lines = 0), w = 0, weight = 1f)
    val v = text(ctx, label(value), Type.LabelLarge, Pal.primary)
    top.add(v, w = WRAP)
    col.add(top)
    if (desc != null) col.add(text(ctx, desc, Type.BodySmall, Pal.onSurfaceVariant, lines = 0))
    val span = (range.last - range.first).coerceAtLeast(1)
    fun at(f: Float) = (range.first + f * span).roundToInt().coerceIn(range.first, range.last)
    val s = SliderView(ctx, (value - range.first).toFloat() / span, { f -> v.text = label(at(f)) }, { f -> onDone(at(f)) })
    s.steps = if (span <= 100) span else 0
    s.isEnabled = enabled
    col.add(s)
    return col
}

/** 横に並んだ選択肢(本家の Segmented。SegmentedButton) */
fun segmented(ctx: Context, title: String?, options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit): View {
    val col = vbox(ctx)
    if (title != null) col.add(text(ctx, title, Type.BodyLarge, Pal.onSurface, lines = 0), b = 6)
    val row = hbox(ctx)
    options.forEachIndexed { i, o ->
        val sel = i == selected
        val b = hbox(ctx, Gravity.CENTER)
        b.minimumHeight = dp(40)
        b.setPadding(dp(8), 0, dp(8), 0)
        if (sel) b.add(icon(ctx, Icons.Check, Pal.onSecondaryContainer, 18), w = 18, h = 18, r = 6)
        b.add(text(ctx, o, Type.LabelLarge, if (sel) Pal.onSecondaryContainer else Pal.onSurface), w = WRAP)
        // 両端だけ丸い(本家の SegmentedButton と同じ形)
        val r = dp(20).toFloat()
        val shape = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (sel) Pal.secondaryContainer else 0)
            setStroke(dp(1), Pal.outline)
            cornerRadii = when {
                options.size == 1 -> floatArrayOf(r, r, r, r, r, r, r, r)
                i == 0 -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                i == options.lastIndex -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                else -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }
        }
        if (enabled) b.tap(0f, shape) { onSelect(i) } else { b.bgd(shape); b.alpha = 0.45f }
        row.add(b, w = 0, weight = 1f, l = if (i > 0) -1 else 0)
    }
    col.add(row)
    return col
}

/** 縦に並んだ選択肢(本家の ChoiceList。RadioButton) */
fun choiceList(ctx: Context, title: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
    val col = vbox(ctx)
    if (title != null) col.add(text(ctx, title, Type.BodyLarge, Pal.onSurface, lines = 0))
    options.forEachIndexed { i, o ->
        val r = hbox(ctx)
        r.add(RadioView(ctx, selected == i), w = 40, h = 40)
        r.add(text(ctx, o, Type.BodyMedium, Pal.onSurface, lines = 0), w = 0, weight = 1f)
        r.tap(8f) { onSelect(i) }
        col.add(r)
    }
    return col
}

/** 名前と説明の付いた選択肢(本家の DAR・DSD・音質のおすすめ) */
fun radioRows(ctx: Context, options: List<Pair<String, String>>, selected: Int, onSelect: (Int) -> Unit): View {
    val col = vbox(ctx)
    options.forEachIndexed { i, (t, d) ->
        val r = hbox(ctx)
        r.setPadding(0, dp(4), 0, dp(4))
        r.add(RadioView(ctx, selected == i), w = 40, h = 40)
        val c = vbox(ctx)
        c.add(text(ctx, t, Type.BodyLarge, Pal.onSurface, lines = 0))
        if (d.isNotEmpty()) c.add(text(ctx, d, Type.BodySmall, Pal.onSurfaceVariant, lines = 0))
        r.add(c, w = 0, weight = 1f)
        r.tap(12f) { onSelect(i) }
        col.add(r)
    }
    return col
}

/** 設定のカテゴリの一行(本家の CategoryRow) */
fun categoryRow(ctx: Context, d: String, title: String, summary: String, onClick: () -> Unit): View {
    val row = hbox(ctx)
    row.setPadding(dp(16), dp(12), dp(16), dp(12))
    val c = FrameLayout(ctx)
    c.bgd(circle(Pal.secondaryContainer))
    c.add(icon(ctx, d, Pal.onSecondaryContainer, 22), w = 22, h = 22, gravity = Gravity.CENTER)
    row.add(c, w = 40, h = 40, r = 16)
    val col = vbox(ctx)
    col.add(text(ctx, title, Type.BodyLarge))
    col.add(text(ctx, summary, Type.BodyMedium, Pal.onSurfaceVariant, lines = 2))
    row.add(col, w = 0, weight = 1f)
    row.add(icon(ctx, Icons.ArrowRight, Pal.onSurfaceVariant), w = 24, h = 24)
    row.tap(0f) { onClick() }
    return row
}

/** カードの中の小見出し */
fun subHeader(ctx: Context, s: String): TextView = text(ctx, s, Type.LabelLarge, Pal.primary).also { it.setPadding(0, dp(4), 0, 0) }

/** 設定のトップの、カテゴリーのまとまりの見出し */
fun groupLabel(ctx: Context, s: String): View = text(ctx, s, Type.TitleSmall, Pal.primary).also { it.setPadding(dp(8), dp(8), 0, 0) }

/** 説明の小さな文字 */
fun note(ctx: Context, s: String, color: Int = Pal.onSurfaceVariant): TextView = text(ctx, s, Type.BodySmall, color, lines = 0)

/** カードに行を区切り線で並べる */
fun cardOf(ctx: Context, rows: List<View>): View {
    val c = card(ctx)
    rows.forEachIndexed { i, r ->
        if (i > 0) c.addDivider()
        c.add(r)
    }
    return c
}

/** チップの並び(選んだ番号) */
fun chipChoice(ctx: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View =
    chipRow(ctx, labels.mapIndexed { i, l -> chip(ctx, l, selected == i) { onSelect(i) } })

/** 角丸の面(設定の今の状態などを出す) */
fun panel(ctx: Context, color: Int): LinearLayout = vbox(ctx).apply { bgd(rounded(color, 12f)) }
