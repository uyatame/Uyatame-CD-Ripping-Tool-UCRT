package com.uyatame.ucrt.legacy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.Sheet
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.bold
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.dpf
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.rounded
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 音量(本家の VolumeSheet。USB DAC 直接出力のときは DAC の音量、それ以外は端末のメディア音量) */
object Volume {
    fun sheet(act: LegacyActivity) {
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val usb = UsbAudio.inUse && UsbAudio.hasVolume
        val max = if (usb) 100 else am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        fun read(): Int = if (usb) UsbAudio.volume else am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val s = Sheet(act)
        val col = vbox(act, Gravity.CENTER_HORIZONTAL)
        col.setPadding(0, 0, 0, dp(32))
        col.add(text(act, S(R.string.volume), Type.TitleLarge), w = WRAP)
        col.add(text(act, Output.name(act) + if (usb) S(R.string.dac_volume_2) else "", Type.BodySmall, Pal.onSurfaceVariant), w = WRAP, b = 16)
        lateinit var dial: Dial
        fun setVol(v: Int, save: Boolean) {
            val x = v.coerceIn(0, max)
            dial.value = x
            if (usb) UsbAudio.setVolume(x, save) else runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, x, 0) }
        }
        dial = Dial(act, read(), max) { v, done -> setVol(v, done) }
        col.add(dial, w = 240, h = 240, b = 8)
        val btns = hbox(act, Gravity.CENTER)
        val minus = text(act, "−", Type.HeadlineSmall)
        minus.gravity = Gravity.CENTER
        minus.tap(26f, rounded(0, 26f, Pal.outline, 1f)) { setVol(dial.value - 1, true) }
        btns.add(minus, w = 52, h = 52, r = 48)
        val plus = text(act, "+", Type.HeadlineSmall)
        plus.gravity = Gravity.CENTER
        plus.tap(26f, rounded(0, 26f, Pal.outline, 1f)) { setVol(dial.value + 1, true) }
        btns.add(plus, w = 52, h = 52)
        col.add(btns, w = WRAP)
        col.add(text(act, S(R.string.trace_around_dial_for_larger), Type.BodySmall, Pal.onSurfaceVariant, lines = 2), w = WRAP, t = 12)
        s.add(col)
        // 本体の音量ボタンで変えたときも表示を合わせる
        val h = Handler(Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                if (!s.isShowing) return
                if (!dial.touching) dial.value = read()
                h.postDelayed(this, 400)
            }
        }
        s.setOnShowListener { h.postDelayed(poll, 400) }
        s.show()
    }

    /** 大きなダイヤル。指でなぞった角度の分だけ変わる(急に最大にならないよう、触った場所へは飛ばない) */
    class Dial(ctx: Context, v: Int, private val max: Int, private val onChange: (Int, Boolean) -> Unit) : FrameLayout(ctx) {
        var value = v
            set(x) { field = x; num.text = "$x"; invalidate() }
        var touching = false
        private val num = text(ctx, "$v", Type.HeadlineMedium).bold()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val start = 135f
        private val sweep = 270f
        private var acc = 0f
        private var prev = 0f

        init {
            setWillNotDraw(false)
            num.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, com.uyatame.ucrt.legacy.ui.Ui.sp(56f))
            num.gravity = Gravity.CENTER
            val c = vbox(ctx, Gravity.CENTER)
            c.add(num, w = WRAP)
            c.add(text(ctx, "/ $max", Type.LabelLarge, Pal.onSurfaceVariant), w = WRAP)
            add(c, w = WRAP, h = WRAP, gravity = Gravity.CENTER)
        }

        override fun onDraw(cv: Canvas) {
            val stroke = dpf(18)
            val pad = dpf(16) + stroke / 2
            r.set(pad, pad, width - pad, height - pad)
            p.style = Paint.Style.STROKE
            p.strokeWidth = stroke
            p.strokeCap = Paint.Cap.ROUND
            p.color = Pal.surfaceVariant
            cv.drawArc(r, start, sweep, false, p)
            val f = value.toFloat() / max
            p.color = Pal.primary
            cv.drawArc(r, start, sweep * f, false, p)
            val ang = Math.toRadians((start + sweep * f).toDouble())
            val rad = r.width() / 2
            val cx = r.centerX() + rad * cos(ang).toFloat()
            val cy = r.centerY() + rad * sin(ang).toFloat()
            p.style = Paint.Style.FILL
            p.color = 0xFFFFFFFF.toInt()
            cv.drawCircle(cx, cy, stroke * 0.75f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dpf(3)
            p.color = Pal.primary
            cv.drawCircle(cx, cy, stroke * 0.75f, p)
        }

        private fun angleOf(x: Float, y: Float): Float = Math.toDegrees(atan2((y - height / 2f).toDouble(), (x - width / 2f).toDouble())).toFloat()

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touching = true; acc = value.toFloat(); prev = angleOf(e.x, e.y); parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    val a = angleOf(e.x, e.y)
                    var d = a - prev
                    if (d > 180f) d -= 360f
                    if (d < -180f) d += 360f
                    prev = a
                    acc = (acc + d / sweep * max).coerceIn(0f, max.toFloat())
                    val nv = acc.roundToInt()
                    if (nv != value) onChange(nv, false)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false; onChange(value, true) }
            }
            return true
        }
    }
}
