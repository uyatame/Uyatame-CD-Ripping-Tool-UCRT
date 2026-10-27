package com.uyatame.ucrt.legacy.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

// 本家の Switch・Slider・Checkbox・RadioButton・LinearProgressIndicator を、自分で描いて作る
// (Android の版ごとに見た目が変わらないように)

/** スイッチ(本家の Switch。52×32dp) */
class SwitchView(ctx: Context, on: Boolean, private val onChange: ((Boolean) -> Unit)?) : View(ctx) {
    var checked = on
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        if (onChange != null) setOnClickListener { checked = !checked; onChange.invoke(checked) }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(52), dp(32))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(1f, 1f, w - 1f, h - 1f)
        p.style = Paint.Style.FILL
        p.color = if (checked) Pal.primary else Pal.containerHighest
        c.drawRoundRect(r, h / 2, h / 2, p)
        if (!checked) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = dpf(2)
            p.color = Pal.outline
            r.inset(dpf(1), dpf(1))
            c.drawRoundRect(r, h / 2, h / 2, p)
        }
        p.style = Paint.Style.FILL
        val rad = if (checked) dpf(12) else dpf(8)
        val cx = if (checked) w - h / 2 else h / 2
        p.color = if (checked) Pal.onPrimary else Pal.outline
        c.drawCircle(cx, h / 2, rad, p)
        alpha = if (isEnabled) 1f else 0.38f
    }
}

/** チェックボックス(本家の Checkbox。18dp の四角を 48dp の中に描く) */
class CheckView(ctx: Context, on: Boolean, private val onChange: ((Boolean) -> Unit)?) : View(ctx) {
    var checked = on
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        if (onChange != null) setOnClickListener { checked = !checked; onChange.invoke(checked) }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(40), dp(40))

    override fun onDraw(c: Canvas) {
        val s = dpf(18)
        val x = (width - s) / 2; val y = (height - s) / 2
        r.set(x, y, x + s, y + s)
        if (checked) {
            p.style = Paint.Style.FILL; p.color = Pal.primary
            c.drawRoundRect(r, dpf(2), dpf(2), p)
            p.style = Paint.Style.STROKE; p.color = Pal.onPrimary; p.strokeWidth = dpf(2)
            p.strokeCap = Paint.Cap.SQUARE
            c.drawLine(x + s * 0.22f, y + s * 0.52f, x + s * 0.42f, y + s * 0.72f, p)
            c.drawLine(x + s * 0.42f, y + s * 0.72f, x + s * 0.80f, y + s * 0.30f, p)
        } else {
            p.style = Paint.Style.STROKE; p.color = Pal.onSurfaceVariant; p.strokeWidth = dpf(2)
            r.inset(dpf(1), dpf(1))
            c.drawRoundRect(r, dpf(2), dpf(2), p)
        }
        alpha = if (isEnabled) 1f else 0.38f
    }
}

/** 丸い選択ボタン(本家の RadioButton) */
class RadioView(ctx: Context, on: Boolean) : View(ctx) {
    var checked = on
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(40), dp(40))

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = dpf(2)
        p.color = if (checked) Pal.primary else Pal.onSurfaceVariant
        c.drawCircle(cx, cy, dpf(9), p)
        if (checked) {
            p.style = Paint.Style.FILL
            c.drawCircle(cx, cy, dpf(5), p)
        }
    }
}

/**
 * スライダー(本家の Slider)。値は 0〜1。動かしている間は onChange、指を離したら onDone
 */
class SliderView(ctx: Context, value: Float, private val onChange: (Float) -> Unit, private val onDone: (Float) -> Unit) : View(ctx) {
    var value = value.coerceIn(0f, 1f)
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    /** 段階(0 なら連続) */
    var steps = 0
    var activeColor = Pal.primary
    var inactiveColor = Pal.secondaryContainer
    var thumbColor = Pal.primary
    var dragging = false
        private set
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(44))

    private val padX get() = dpf(10)

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val x0 = padX; val x1 = width - padX
        val x = x0 + (x1 - x0) * value
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = dpf(4)
        p.color = inactiveColor
        c.drawLine(x0, cy, x1, cy, p)
        p.color = activeColor
        c.drawLine(x0, cy, x, cy, p)
        p.style = Paint.Style.FILL
        p.color = thumbColor
        c.drawCircle(x, cy, dpf(if (dragging) 11 else 10), p)
        alpha = if (isEnabled) 1f else 0.38f
    }

    private fun at(ex: Float): Float {
        var v = ((ex - padX) / (width - 2 * padX)).coerceIn(0f, 1f)
        if (steps > 0) v = (v * steps).roundToInt() / steps.toFloat()
        return v
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                value = at(e.x); onChange(value)
            }
            MotionEvent.ACTION_MOVE -> { value = at(e.x); onChange(value) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onDone(value)
            }
        }
        return true
    }
}

/**
 * 進み具合の帯(本家の LinearProgressIndicator)。progress が負なら、終わりの分からない動く帯
 */
class ProgressBarView(ctx: Context, progress: Float = -1f, private val heightDp: Int = 4) : View(ctx) {
    var progress = progress
        set(v) { field = v; invalidate() }
    var trackColor = Pal.secondaryContainer
    var barColor = Pal.primary
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(heightDp))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = trackColor
        c.drawRect(0f, 0f, w, h, p)
        p.color = barColor
        if (progress >= 0f) {
            c.drawRect(0f, 0f, w * progress.coerceIn(0f, 1f), h, p)
        } else {
            val t = (SystemClock.uptimeMillis() % 1600) / 1600f
            val a = (t * 1.6f - 0.4f) * w
            c.drawRect(a.coerceAtLeast(0f), 0f, (a + w * 0.4f).coerceAtMost(w), h, p)
            if (visibility == VISIBLE && windowVisibility == VISIBLE) postInvalidateDelayed(32)
        }
    }
}
