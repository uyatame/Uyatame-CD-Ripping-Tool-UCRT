package com.uyatame.ucrt.legacy.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.uyatame.ucrt.legacy.compat.Api21

// =====================================================================
// 画面の部品(本家の Material 3 の部品を、Android 4.0 からある View で作る)
// =====================================================================

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object Ui {
    var density = 1f
        private set
    /** 文字の 1sp のピクセル数(画面の縮小と、文字の大きさの設定(1.3 倍まで)を含む) */
    var spPx = 1f
        private set

    /**
     * 狭い画面(短い辺が 360dp 未満の、解像度の低い端末)では、画面全体を同じ割合で縮めて、
     * 360dp の幅を前提にした並びが崩れないようにする(0.75 倍まで)。文字の大きさの設定も 1.3 倍までにする
     */
    fun init(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        val shortDp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
        val scale = (shortDp / 360f).coerceIn(0.75f, 1f)
        density = dm.density * scale
        val font = (ctx.resources.configuration.fontScale).coerceIn(0.85f, 1.3f)
        spPx = dm.density * font * scale
    }

    /** sp → ピクセル */
    fun sp(v: Float): Float = v * spPx
}

fun dp(v: Number): Int = (v.toFloat() * Ui.density + 0.5f).toInt()
fun dpf(v: Number): Float = v.toFloat() * Ui.density

/** 背景を付ける(setBackground は Android 4.1 からなので、古い名前の方を使う) */
@Suppress("DEPRECATION")
fun View.bgd(d: Drawable?) = setBackgroundDrawable(d)

/** 角の丸い四角(塗り・枠) */
fun rounded(color: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Float = 1f): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp)
        if (stroke != 0) setStroke(dp(strokeDp).coerceAtLeast(1), stroke)
    }

/** 上の角だけ丸い四角(下から出す画面) */
fun roundedTop(color: Int, radiusDp: Float): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        val r = dpf(radiusDp)
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

fun circle(color: Int): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

/**
 * 押せる部品の背景(押すと少し暗く・明るくなる。Android 5.0 からは波紋)。base は普段の背景(null なら透明)
 */
fun pressBg(base: Drawable?, radiusDp: Float, overlay: Int = Pal.alpha(Pal.onSurface, 0.12f), oval: Boolean = false): Drawable {
    val mask = if (oval) circle(0xFF000000.toInt()) else rounded(0xFF000000.toInt(), radiusDp)
    if (Build.VERSION.SDK_INT >= 21) return Api21.ripple(overlay, base, mask)
    val pressed = if (oval) circle(overlay) else rounded(overlay, radiusDp)
    val on = if (base != null) LayerDrawable(arrayOf(base, pressed)) else pressed
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), on)
        addState(intArrayOf(android.R.attr.state_focused), on)
        addState(intArrayOf(), base ?: rounded(0, radiusDp))
    }
}

/** タップできるようにする(背景も押したときの見た目にする) */
fun View.tap(radiusDp: Float = 12f, base: Drawable? = null, oval: Boolean = false, onClick: () -> Unit): View {
    bgd(pressBg(base, radiusDp, oval = oval))
    isClickable = true
    setOnClickListener { onClick() }
    return this
}

fun View.longTap(onLong: () -> Unit): View {
    setOnLongClickListener { onLong(); true }
    return this
}

fun View.pad(l: Int, t: Int = l, r: Int = l, b: Int = t): View {
    setPadding(dp(l), dp(t), dp(r), dp(b))
    return this
}

/** LinearLayout に部品を足す(幅・高さ・比率・余白は dp) */
fun <T : View> ViewGroup.add(
    v: T, w: Int = MATCH, h: Int = WRAP, weight: Float = 0f,
    l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0, gravity: Int = -1,
): T {
    val ww = if (w > 0) dp(w) else w
    val hh = if (h > 0) dp(h) else h
    val lp: ViewGroup.MarginLayoutParams = when (this) {
        is LinearLayout -> LinearLayout.LayoutParams(ww, hh, weight).also { if (gravity >= 0) it.gravity = gravity }
        is FrameLayout -> FrameLayout.LayoutParams(ww, hh).also { if (gravity >= 0) it.gravity = gravity }
        else -> ViewGroup.MarginLayoutParams(ww, hh)
    }
    lp.setMargins(dp(l), dp(t), dp(r), dp(b))
    // 画面を作り直すとき、前の入れ物に入ったままの部品を入れ直すことがあるので、先に外す
    (v.parent as? ViewGroup)?.removeView(v)
    addView(v, lp)
    return v
}

fun vbox(ctx: Context, gravity: Int = Gravity.NO_GRAVITY): LinearLayout =
    LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; this.gravity = gravity }

fun hbox(ctx: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
    LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }

fun space(ctx: Context): View = View(ctx)

/** 文字 */
fun text(ctx: Context, s: CharSequence, type: Type = Type.BodyMedium, color: Int = Pal.onSurface, lines: Int = 1): TextView =
    TextView(ctx).apply {
        style(type, color)
        text = s
        if (lines > 0) {
            maxLines = lines
            if (lines == 1) setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
    }

fun TextView.style(type: Type, color: Int = currentTextColor) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(type.sp))
    typeface = if (type.medium) Type.mediumFace else android.graphics.Typeface.DEFAULT
    setTextColor(color)
    includeFontPadding = true
}

/** 1 行に収まらない文字を横に流す(再生画面の曲名など。狭い画面でも全部見えるように) */
fun TextView.marquee(): TextView {
    setSingleLine(true)
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.MARQUEE
    marqueeRepeatLimit = -1
    setHorizontallyScrolling(true)
    // 流すには「選ばれている」状態が要る(フォーカスは取らない)
    isSelected = true
    return this
}

/** 太字 */
fun TextView.bold(on: Boolean = true): TextView {
    typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    return this
}

/** アイコン */
fun icon(ctx: Context, d: String, color: Int = Pal.onSurfaceVariant, sizeDp: Int = 24): ImageView =
    ImageView(ctx).apply {
        setImageDrawable(IconDrawable(d, color, dp(sizeDp)))
        scaleType = ImageView.ScaleType.CENTER
    }

fun ImageView.setIcon(d: String, color: Int, sizeDp: Int = 24) = setImageDrawable(IconDrawable(d, color, dp(sizeDp)))

/** アイコンだけのボタン(48dp。押すと丸く光る) */
fun iconButton(ctx: Context, d: String, desc: String?, color: Int = Pal.onSurfaceVariant, sizeDp: Int = 24, onClick: () -> Unit): ImageView =
    icon(ctx, d, color, sizeDp).apply {
        contentDescription = desc
        minimumWidth = dp(48); minimumHeight = dp(48)
        tap(oval = true) { onClick() }
    }

enum class Btn { Filled, Tonal, Outlined, Text, Elevated }

/** ボタン(本家の Button・FilledTonalButton・OutlinedButton・TextButton と同じ形) */
fun button(ctx: Context, label: String, style: Btn = Btn.Filled, iconD: String? = null, onClick: () -> Unit): LinearLayout {
    val (bg, fg) = when (style) {
        Btn.Filled -> Pal.primary to Pal.onPrimary
        Btn.Tonal -> Pal.secondaryContainer to Pal.onSecondaryContainer
        Btn.Elevated -> Pal.containerLow to Pal.primary
        Btn.Outlined, Btn.Text -> 0 to Pal.primary
    }
    val box = hbox(ctx, Gravity.CENTER)
    box.minimumHeight = dp(40)
    val padH = if (style == Btn.Text) 12 else if (iconD != null) 16 else 24
    box.setPadding(dp(padH), 0, dp(if (style == Btn.Text) 12 else 24), 0)
    if (iconD != null) {
        box.add(icon(ctx, iconD, fg, 18), w = 18, h = 18, r = 8)
    }
    box.add(text(ctx, label, Type.LabelLarge, fg), w = WRAP)
    val base = when (style) {
        Btn.Outlined -> rounded(0, 20f, Pal.outline, 1f)
        Btn.Text -> null
        else -> rounded(bg, 20f)
    }
    box.tap(20f, base) { onClick() }
    box.tag = style
    return box
}

/** ボタンを押せなくする(薄くする) */
fun View.enabled(on: Boolean): View {
    isEnabled = on
    isClickable = on
    alpha = if (on) 1f else 0.38f
    return this
}

/** 絞り込みのチップ(本家の FilterChip) */
fun chip(ctx: Context, label: String, selected: Boolean, onClick: () -> Unit): LinearLayout {
    val box = hbox(ctx, Gravity.CENTER)
    box.minimumHeight = dp(32)
    if (selected) {
        box.add(icon(ctx, Icons.Check, Pal.onSecondaryContainer, 18), w = 18, h = 18, r = 8)
        box.setPadding(dp(8), 0, dp(16), 0)
    } else box.setPadding(dp(16), 0, dp(16), 0)
    box.add(text(ctx, label, Type.LabelLarge, if (selected) Pal.onSecondaryContainer else Pal.onSurfaceVariant), w = WRAP)
    box.tap(8f, if (selected) rounded(Pal.secondaryContainer, 8f) else rounded(0, 8f, Pal.outlineVariant, 1f)) { onClick() }
    return box
}

/** 横に並べたチップ(はみ出したら横にスクロール) */
fun chipRow(ctx: Context, chips: List<View>): View {
    val sc = android.widget.HorizontalScrollView(ctx)
    sc.isHorizontalScrollBarEnabled = false
    val row = hbox(ctx)
    chips.forEachIndexed { i, c -> row.add(c, w = WRAP, h = WRAP, r = if (i < chips.lastIndex) 8 else 0) }
    sc.addView(row)
    return sc
}

/** カード(本家の Card。少し色の付いた角丸の面) */
fun card(ctx: Context, color: Int = Pal.containerHighest, radiusDp: Float = 12f): LinearLayout =
    vbox(ctx).apply { bgd(rounded(color, radiusDp)) }

fun divider(ctx: Context, color: Int = Pal.outlineVariant): View = View(ctx).apply { setBackgroundColor(color) }

/** 区切りの線を足す(1px) */
fun ViewGroup.addDivider(l: Int = 0, r: Int = 0) {
    val v = divider(context)
    val lp = LinearLayout.LayoutParams(MATCH, 1)
    lp.setMargins(dp(l), 0, dp(r), 0)
    addView(v, lp)
}

/** 品質の札(本家の QualityBadge。ハイレゾは金色で塗る) */
fun badge(ctx: Context, s: String, hiRes: Boolean, onDark: Boolean = false): TextView {
    val base = if (onDark) Pal.alpha(0xFFFFFFFF.toInt(), 0.75f) else Pal.onSurfaceVariant
    return TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(10f))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setSingleLine(true)
        setTextColor(if (hiRes) Pal.HIRES_INK else base)
        bgd(if (hiRes) rounded(Pal.HIRES_GOLD, 4f) else rounded(0, 4f, Pal.alpha(base, 0.6f), 0.8f))
        setPadding(dp(4), dp(1), dp(4), dp(1))
        includeFontPadding = false
        visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
    }
}
