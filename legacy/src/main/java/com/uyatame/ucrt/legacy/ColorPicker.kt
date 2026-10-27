package com.uyatame.ucrt.legacy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.dpf
import com.uyatame.ucrt.legacy.ui.field
import com.uyatame.ucrt.legacy.ui.hbox

/** カラーピッカー(本家の ColorPickerDialog)。四角(横: 鮮やかさ、縦: 明るさ)と色相の帯で色を選び、#RRGGBB でも入力できる */
object ColorPicker {
    fun show(act: LegacyActivity, title: String, initial: Int, onReset: () -> Unit, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
        val d = UDialog(act).title(title)
        lateinit var sv: SvView
        lateinit var hue: HueView
        val preview = View(act)
        val hex = field(act, SettingsPages.hexOf(initial), S(R.string.color_code))
        var syncing = false
        fun color() = Color.HSVToColor(hsv)
        fun update(fromText: Boolean) {
            preview.bgd(android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color()) })
            sv.invalidate(); hue.invalidate()
            if (!fromText) { syncing = true; hex.setText(SettingsPages.hexOf(color())); syncing = false }
        }
        sv = SvView(act, hsv) { update(false) }
        hue = HueView(act, hsv) { update(false) }
        d.view(sv)
        d.body.add(hue, h = 28, t = 12)
        val r = hbox(act)
        r.add(preview, w = 40, h = 40, r = 12)
        r.add(hex, w = 0, weight = 1f)
        d.body.add(r, t = 12)
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val t = s?.toString()?.trim()?.removePrefix("#") ?: return
                if (t.length == 6) t.toIntOrNull(16)?.let { v -> Color.colorToHSV(v or 0xFF000000.toInt(), hsv); update(true) }
            }
        })
        update(false)
        d.button(S(R.string.default_label)) { onReset(); d.dismiss() }
        d.button(S(R.string.cancel)) { d.dismiss() }
        d.button(S(R.string.ok)) { onPick(color()); d.dismiss() }
        d.show()
    }

    private class SvView(ctx: Context, val hsv: FloatArray, val onChange: () -> Unit) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onMeasure(w: Int, h: Int) { val s = MeasureSpec.getSize(w); setMeasuredDimension(s, (s / 1.4f).toInt()) }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = RectF(0f, 0f, w, h)
            p.shader = LinearGradient(0f, 0f, w, 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, dpf(12), dpf(12), p)
            p.shader = LinearGradient(0f, 0f, 0f, h, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, dpf(12), dpf(12), p)
            p.shader = null
            val x = hsv[1] * w; val y = (1f - hsv[2]) * h
            p.style = Paint.Style.STROKE
            p.strokeWidth = dpf(3); p.color = Color.WHITE
            c.drawCircle(x, y, dpf(10), p)
            p.strokeWidth = dpf(1); p.color = 0x80000000.toInt()
            c.drawCircle(x, y, dpf(12), p)
            p.style = Paint.Style.FILL
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            hsv[1] = (e.x / width).coerceIn(0f, 1f)
            hsv[2] = 1f - (e.y / height).coerceIn(0f, 1f)
            onChange()
            return true
        }
    }

    private class HueView(ctx: Context, val hsv: FloatArray, val onChange: () -> Unit) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val cols = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
            p.shader = LinearGradient(0f, 0f, w, 0f, cols, null, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p)
            p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = dpf(3); p.color = Color.WHITE
            c.drawCircle(hsv[0] / 360f * w, h / 2, h / 2 - dpf(2), p)
            p.style = Paint.Style.FILL
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            hsv[0] = (e.x / width).coerceIn(0f, 1f) * 360f
            onChange()
            return true
        }
    }
}
