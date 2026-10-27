package com.uyatame.ucrt.legacy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.uyatame.ucrt.player.EqBand
import com.uyatame.ucrt.player.EqBands
import com.uyatame.ucrt.player.ParamEq
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.dpf
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

/** シンプルのイコライザーで動かせる幅(±dB。本家の SIMPLE_EQ_MAX) */
const val SIMPLE_EQ_MAX = 12f

private val LN_MIN = ln(ParamEq.MIN_FREQ.toDouble())
private val LN_MAX = ln(ParamEq.MAX_FREQ.toDouble())
fun freqToPos(f: Float): Float = ((ln(f.toDouble()) - LN_MIN) / (LN_MAX - LN_MIN)).toFloat()
fun posToFreq(p: Float): Float = exp(LN_MIN + (LN_MAX - LN_MIN) * p.coerceIn(0f, 1f)).toFloat()
private val Q_LN_MIN = ln(ParamEq.MIN_Q.toDouble())
private val Q_LN_MAX = ln(ParamEq.MAX_Q.toDouble())
fun qToPos(q: Float): Float = ((ln(q.toDouble()) - Q_LN_MIN) / (Q_LN_MAX - Q_LN_MIN)).toFloat()
fun posToQ(p: Float): Float = exp(Q_LN_MIN + (Q_LN_MAX - Q_LN_MIN) * p.coerceIn(0f, 1f)).toFloat()

fun freqLabel(f: Float): String = if (f >= 1000f) {
    val k = f / 1000f
    (if (k >= 10f) "%.1f".format(java.util.Locale.US, k).removeSuffix(".0") else "%.2f".format(java.util.Locale.US, k).trimEnd('0').trimEnd('.')) + " kHz"
} else "${f.roundToInt()} Hz"

fun dbLabel(v: Float): String {
    val r = (v * 10).roundToInt() / 10f
    return (if (r > 0) "+" else "") + (if (r % 1f == 0f) r.toInt().toString() else r.toString()) + " dB"
}

/**
 * シンプル(10 バンド)のイコライザーの絵(本家の GraphicEq)。帯域をタップして選び、上下になぞって調整する。
 * パラメトリック EQ と同じ周波数特性の曲線を重ねて描く
 */
class GraphicEqView(ctx: Context, var gains: FloatArray, private val onSelect: (Int) -> Unit, private val onChange: (FloatArray) -> Unit, private val onDone: () -> Unit) : View(ctx) {
    var sel = 5
        set(v) { field = v; invalidate() }
    var on = true
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val n = EqBands.FREQS.size

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(230))

    private fun yOf(db: Float): Float { val h = height.toFloat(); val pad = dpf(14); return h / 2 - db / SIMPLE_EQ_MAX * (h / 2 - pad) }
    private fun dbAt(y: Float): Float { val h = height.toFloat(); val pad = dpf(14); return (h / 2 - y) / (h / 2 - pad) * SIMPLE_EQ_MAX }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val colW = w / n
        val a = if (on) 1f else 0.4f
        p.style = Paint.Style.FILL
        p.color = Pal.containerHighest
        c.drawRoundRect(RectF(0f, 0f, w, h), dpf(12), dpf(12), p)
        p.color = Pal.alpha(Pal.primary, 0.10f * a)
        c.drawRect(sel * colW, 0f, (sel + 1) * colW, h, p)
        val dash = colW * 0.62f
        for (i in 0 until n) {
            val cx = (i + 0.5f) * colW
            for (db in -SIMPLE_EQ_MAX.toInt()..SIMPLE_EQ_MAX.toInt()) {
                val y = yOf(db.toFloat())
                p.color = Pal.alpha(Pal.onSurfaceVariant, (if (db == 0) 0.55f else 0.18f) * a)
                p.strokeWidth = if (db == 0) dpf(2) else dpf(1)
                c.drawLine(cx - dash / 2, y, cx + dash / 2, y, p)
            }
            val y = yOf(gains[i]); val y0 = yOf(0f)
            p.color = Pal.alpha(Pal.primary, 0.22f * a)
            c.drawRect(cx - dash / 2, minOf(y, y0), cx + dash / 2, maxOf(y, y0), p)
            p.color = Pal.alpha(Pal.primary, a)
            c.drawRoundRect(RectF(cx - dash / 2 - dpf(2), y - dpf(3), cx + dash / 2 + dpf(2), y + dpf(3)), dpf(3), dpf(3), p)
        }
        // 周波数特性の曲線(列の中心が各帯域の周波数。1 オクターブずつ)
        val bands = ParamEq.fromGains(gains)
        val steps = 120
        val f0 = EqBands.FREQS.first().toDouble()
        val curve = ParamEq.responseCurve(bands, DoubleArray(steps + 1) { k -> (f0 * Math.pow(2.0, (k.toFloat() / steps * w / colW - 0.5f).toDouble())).coerceIn(10.0, 22000.0) })
        path.reset()
        for (k in 0..steps) {
            val x = k.toFloat() / steps * w
            val y = yOf(curve[k].toFloat().coerceIn(-SIMPLE_EQ_MAX * 1.3f, SIMPLE_EQ_MAX * 1.3f))
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = dpf(2.5f)
        p.strokeCap = Paint.Cap.ROUND
        p.color = Pal.alpha(Pal.tertiary, 0.9f * a)
        c.drawPath(path, p)
    }

    private var downY = 0f
    private var moved = false
    private var col = 0

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!on) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                col = (e.x / (width.toFloat() / n)).toInt().coerceIn(0, n - 1)
                sel = col; onSelect(col)
                downY = e.y; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (moved || abs(e.y - downY) > dpf(6)) {
                    moved = true
                    val g = gains.copyOf()
                    g[col] = ((dbAt(e.y) * 2).roundToInt() / 2f).coerceIn(-SIMPLE_EQ_MAX, SIMPLE_EQ_MAX)
                    gains = g
                    invalidate()
                    onChange(g)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (moved) onDone()
            }
        }
        return true
    }
}

/**
 * パラメトリック EQ の周波数特性の曲線と、帯域の点(本家の EqCurve)。
 * 点をドラッグして周波数(左右)と利得(上下)を変える。何もないところをダブルタップすると帯域を足す
 */
class EqCurveView(
    ctx: Context, var bands: MutableList<EqBand>, private val onSelect: (Int) -> Unit, private val onMove: (Int, Float, Float) -> Unit,
    private val onMoveEnd: () -> Unit, private val onAdd: (Float, Float) -> Unit,
) : View(ctx) {
    var sel = -1
        set(v) { field = v; invalidate() }
    var on = true
    var heightDp = 230
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val fill = Path()
    private val maxDb = ParamEq.MAX_DB

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(heightDp))

    private val pad get() = dpf(18)
    private fun xOf(f: Float) = freqToPos(f) * width
    private fun yOf(db: Float) = height / 2f - db / maxDb * (height / 2f - pad)
    private fun dbAt(y: Float) = (height / 2f - y) / (height / 2f - pad) * maxDb
    private fun handleY(b: EqBand) = yOf(if (b.type.hasGain) b.gain else ParamEq.responseDb(listOf(b), b.freq.toDouble()).toFloat())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val a = if (on) 1f else 0.4f
        p.style = Paint.Style.FILL
        p.color = Pal.surfaceVariant
        c.drawRoundRect(RectF(0f, 0f, w, h), dpf(12), dpf(12), p)
        p.textSize = dpf(10)
        for (f in listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)) {
            val x = xOf(f)
            p.color = Pal.alpha(Pal.outlineVariant, 0.6f)
            p.strokeWidth = 1f
            c.drawLine(x, 0f, x, h, p)
            if (f != 200f && f != 2000f) {
                p.color = Pal.onSurfaceVariant
                c.drawText(if (f >= 1000f) "${(f / 1000).toInt()}k" else "${f.toInt()}", x + 3, h - dpf(4), p)
            }
        }
        for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
            val y = yOf(db)
            p.color = if (db == 0f) Pal.outline else Pal.alpha(Pal.outlineVariant, 0.6f)
            p.strokeWidth = if (db == 0f) 2f else 1f
            c.drawLine(0f, y, w, y, p)
            p.color = Pal.onSurfaceVariant
            c.drawText((if (db > 0) "+" else "") + db.toInt(), 4f, y - dpf(3), p)
        }
        val list = bands.toList()
        val n = 160
        val curve = ParamEq.responseCurve(list, DoubleArray(n + 1) { posToFreq(it.toFloat() / n).toDouble() })
        path.reset(); fill.reset()
        for (i in 0..n) {
            val x = i.toFloat() / n * w
            val y = yOf(curve[i].toFloat().coerceIn(-maxDb * 1.2f, maxDb * 1.2f))
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, yOf(0f)); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(w, yOf(0f)); fill.close()
        p.style = Paint.Style.FILL
        p.color = Pal.alpha(Pal.primary, 0.15f * a)
        c.drawPath(fill, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dpf(3)
        p.color = Pal.alpha(Pal.primary, a)
        c.drawPath(path, p)
        val r = dpf(11)
        p.textSize = dpf(11)
        p.textAlign = Paint.Align.CENTER
        list.forEachIndexed { i, b ->
            val cx = xOf(b.freq)
            val cy = handleY(b).coerceIn(r, h - r)
            if (i == sel) {
                p.style = Paint.Style.FILL; p.color = Pal.alpha(Pal.primary, a)
                c.drawCircle(cx, cy, r, p)
                p.color = Pal.onPrimary
            } else {
                p.style = Paint.Style.FILL; p.color = Pal.alpha(Pal.surface, a)
                c.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = dpf(2); p.color = Pal.alpha(Pal.primary, a)
                c.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.FILL; p.color = Pal.primary
            }
            c.drawText("${i + 1}", cx, cy + dpf(4), p)
        }
        p.textAlign = Paint.Align.LEFT
    }

    private var hit = -1
    private var moved = false
    private var lastTap = 0L
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!on) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                hit = -1; moved = false
                var best = Float.MAX_VALUE
                bands.forEachIndexed { i, b ->
                    val d = hypot(xOf(b.freq) - e.x, handleY(b) - e.y)
                    if (d < dpf(28) && d < best) { best = d; hit = i }
                }
                if (hit >= 0) { sel = hit; onSelect(hit) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (hit >= 0) {
                    moved = true
                    onMove(hit, posToFreq(e.x / width), dbAt(e.y))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (hit >= 0) { if (moved) onMoveEnd() } else {
                    val now = e.eventTime
                    if (now - lastTap < 350 && hypot(e.x - lastX, e.y - lastY) < dpf(48)) {
                        onAdd(posToFreq(e.x / width), dbAt(e.y))
                        lastTap = 0
                    } else {
                        sel = -1; onSelect(-1)
                        lastTap = now; lastX = e.x; lastY = e.y
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}

/** 一覧の 1 行に添える、周波数特性の小さな絵(本家の CurveThumb) */
class CurveThumbView(ctx: Context, private val bands: List<EqBand>) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pts: FloatArray by lazy {
        val c = ParamEq.responseCurve(bands, DoubleArray(48) { k -> exp(ln(20.0) + (ln(20000.0) - ln(20.0)) * k / 47.0) })
        FloatArray(48) { c[it].toFloat() }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = Pal.containerHighest
        c.drawRoundRect(RectF(0f, 0f, w, h), dpf(8), dpf(8), p)
        fun yOf(db: Float) = h / 2 - db.coerceIn(-12f, 12f) / 12f * (h / 2 - dpf(3))
        p.color = Pal.outlineVariant
        p.strokeWidth = dpf(1)
        c.drawLine(0f, h / 2, w, h / 2, p)
        val path = Path(); val fill = Path()
        pts.forEachIndexed { k, db ->
            val x = k / (pts.size - 1f) * w
            val y = yOf(db)
            if (k == 0) { path.moveTo(x, y); fill.moveTo(x, h / 2); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(w, h / 2); fill.close()
        p.color = Pal.alpha(Pal.primary, 0.18f)
        c.drawPath(fill, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dpf(2)
        p.color = Pal.primary
        c.drawPath(path, p)
    }
}
