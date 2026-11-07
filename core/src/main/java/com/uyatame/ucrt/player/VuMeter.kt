package com.uyatame.ucrt.player

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * VU メーターの針・バーの動き(いつものアプリと UCRT Legacy で同じものを使う)。
 * 左右の音(今鳴っている位置の直前の数十 ms)を受け取り、針の位置(VU)とバーの高さ(dBFS)を、決まった動き方で動かす
 */
class VuMeter {
    /** 針の動き: 0 = VU(なめらか。約 0.3 秒で振れる)、1 = ピーク(すばやく上がって、ゆっくり下がる) */
    var mode = 0
    /** 0 VU にする音の大きさ(dBFS。サイン波の最大値で -18 dBFS なら 18) */
    var refDb = 18f

    /** 針の位置(VU。0 が基準、-20〜+3 が目盛り) */
    val needle = FloatArray(2) { FLOOR_VU }
    /** バーの高さ(dBFS のピーク) */
    val peak = FloatArray(2) { FLOOR_DB }
    /** バーのピークホールド(dBFS) */
    val hold = FloatArray(2) { FLOOR_DB }

    private val a1 = FloatArray(2)
    private val a2 = FloatArray(2)
    private val pk = FloatArray(2) { FLOOR_DB }
    private val holdAt = LongArray(2)
    private var last = 0L

    /**
     * 1 フレーム分動かす。l・r は今鳴っている位置までの n 個の音(ok が false なら無音として下げる)。
     * いちばん大きく動いた量を返す(止まっていれば描き直さなくてよい)
     */
    fun step(l: FloatArray, r: FloatArray, n: Int, ok: Boolean, nowNs: Long): Float {
        val dt = if (last == 0L) 1f / 60 else ((nowNs - last).coerceIn(1_000_000L, 200_000_000L) / 1e9f)
        last = nowNs
        var moved = 0f
        for (ch in 0..1) {
            val src = if (ch == 0) l else r
            var sq = 0.0
            var mx = 0f
            if (ok && n > 0) {
                for (i in 0 until n) { val v = src[i]; sq += v * v; val a = abs(v); if (a > mx) mx = a }
            }
            val rms = if (ok && n > 0) sqrt(sq / n).toFloat() else 0f
            // VU: 2 段のなめらかさ(本物の VU 計のように、少し遅れて柔らかく振れる。約 0.3 秒で 99%)
            val k = 1f - exp(-dt / 0.065f)
            a1[ch] += (rms - a1[ch]) * k
            a2[ch] += (a1[ch] - a2[ch]) * k
            // ピーク(dBFS): 上がるときはすぐ、下がるときは 1 秒に 20 dB
            val pDb = toDb(mx)
            pk[ch] = if (pDb > pk[ch]) pDb else max(pDb, pk[ch] - 20f * dt)
            // 針の目標(サイン波で校正: 実効値 × √2 が最大値)
            val target = if (mode == 1) pk[ch] + refDb else toDb(a2[ch] * SQRT2) + refDb
            val t = target.coerceIn(FLOOR_VU, 6f)
            // 針の重さ(ピークのときも、少しだけ遅れて動く)
            val mk = if (mode == 1) 1f - exp(-dt / 0.025f) else 1f
            val d = (t - needle[ch]) * mk
            needle[ch] += d
            // バー(ピーク)とホールド(1.5 秒止めてから、1 秒に 15 dB 下げる)
            val before = peak[ch]
            peak[ch] = pk[ch]
            if (peak[ch] >= hold[ch]) { hold[ch] = peak[ch]; holdAt[ch] = nowNs }
            else if (nowNs - holdAt[ch] > 1_500_000_000L) hold[ch] = max(peak[ch], hold[ch] - 15f * dt)
            moved = max(moved, max(abs(d), abs(peak[ch] - before)))
            if (hold[ch] > FLOOR_DB + 0.1f && nowNs - holdAt[ch] > 1_500_000_000L) moved = max(moved, 0.01f)
        }
        return moved
    }

    companion object {
        const val FLOOR_VU = -24f
        const val FLOOR_DB = -72f
        private const val SQRT2 = 1.4142135f

        fun toDb(v: Float): Float = if (v <= 1e-6f) FLOOR_DB else (20.0 * log10(v.toDouble())).toFloat().coerceAtLeast(FLOOR_DB)
    }
}

/**
 * VU メーターを描く(android.graphics の Canvas に。いつものアプリは Compose の Canvas の中から呼ぶ)。
 * 針: 暖かい色の明かりの入った、昔ながらのアナログ VU 計(針の軸は窓の下に隠す)。
 * バー: L・R の横棒(LED の目盛り)。決めた音量を超えた所は赤
 */
class VuPainter(private val density: Float) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    /** 文字盤の色・映り込みのグラデーション(大きさが変わらない間は作り直さない。毎フレーム作ると、ゴミが出て重くなる) */
    private class Shaders(val key: FloatArray, val face: Shader, val shade: Shader, val glass: Shader)
    private val cache = arrayOfNulls<Shaders>(2)

    private fun shadersFor(i: Int, fx0: Float, fy0: Float, fw: Float, fh: Float): Shaders {
        val c = cache[i]
        if (c != null && c.key[0] == fx0 && c.key[1] == fy0 && c.key[2] == fw && c.key[3] == fh) return c
        val cx = fx0 + fw / 2
        val n = Shaders(
            floatArrayOf(fx0, fy0, fw, fh),
            RadialGradient(cx, fy0 + fh * 1.1f, fw * 0.75f,
                intArrayOf(0xFFFFF2CC.toInt(), 0xFFF8CF7E.toInt(), 0xFFE39B3D.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP),
            LinearGradient(0f, fy0, 0f, fy0 + fh * 0.22f, 0x55000000, 0x00000000, Shader.TileMode.CLAMP),
            LinearGradient(fx0, fy0, fx0 + fw * 0.6f, fy0 + fh, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP),
        )
        cache[i] = n
        return n
    }

    /** VU の値 → 目盛りの上の位置(0〜1)。本物の VU 計と同じく電圧に比例(左が詰まり、0 VU は右寄り) */
    private fun pos(v: Float): Float {
        val lo = 10.0.pow(-20 / 20.0)
        val hi = 10.0.pow(3 / 20.0)
        return ((10.0.pow(v.coerceIn(-22f, 4f) / 20.0) - lo) / (hi - lo)).toFloat()
    }

    /** 針の VU 計を左右 2 つ描く */
    fun drawNeedles(c: Canvas, w: Float, h: Float, vuL: Float, vuR: Float) {
        val gap = max(6f * density, w * 0.04f)
        // 窓の縦横は、いつも横 1 : 縦 0.56(横向きの画面などで幅が余っても、横に伸ばさない。2 つを真ん中に寄せる)
        val mw = min((w - gap) / 2, h / 0.56f)
        val mh = mw * 0.56f
        val top = (h - mh) / 2
        val left = (w - (mw * 2 + gap)) / 2
        for (ch in 0..1) {
            val x0 = left + ch * (mw + gap)
            drawOne(c, ch, x0, top, mw, mh, if (ch == 0) vuL else vuR, if (ch == 0) "L" else "R")
        }
    }

    private fun drawOne(c: Canvas, idx: Int, x0: Float, y0: Float, mw: Float, mh: Float, vu: Float, label: String) {
        // 枠(黒い縁)
        val bez = max(3f * density, mw * 0.025f)
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = 0xFF0E0E0F.toInt()
        r.set(x0, y0, x0 + mw, y0 + mh)
        c.drawRoundRect(r, 6f * density, 6f * density, p)
        // 文字盤(明かりの入った暖かい色)
        val fx0 = x0 + bez; val fy0 = y0 + bez; val fx1 = x0 + mw - bez; val fy1 = y0 + mh - bez
        val fw = fx1 - fx0; val fh = fy1 - fy0
        val cx = (fx0 + fx1) / 2
        c.save()
        r.set(fx0, fy0, fx1, fy1)
        // 角の丸い切り抜き(clipPath)は Android 4.2 以前のハードウェア描画で使えないので、四角で切る
        c.clipRect(r)
        val sh0 = shadersFor(idx, fx0, fy0, fw, fh)
        p.shader = sh0.face
        c.drawRect(r, p)
        p.shader = null

        // 針の軸は、窓の下に隠す
        val py = fy1 + fh * 0.38f
        val rad = py - (fy0 + fh * 0.36f)
        val half = asin((fw * 0.36f / rad).coerceIn(0.1f, 0.95f))
        fun ang(v: Float) = (-Math.PI / 2 - half + 2 * half * pos(v)).toFloat()
        fun px(a: Float, k: Float) = cx + cos(a) * rad * k
        fun py(a: Float, k: Float) = py + sin(a) * rad * k
        val ink = 0xFF1C1A17.toInt()
        val red = 0xFFC8231B.toInt()

        // 目盛りの弧(0 から右は赤い帯)
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, fh * 0.012f)
        p.color = ink
        r.set(cx - rad, py - rad, cx + rad, py + rad)
        val deg = (180.0 / Math.PI).toFloat()
        c.drawArc(r, ang(-20f) * deg, (ang(0f) - ang(-20f)) * deg, false, p)
        p.color = red
        p.strokeWidth = max(2f, fh * 0.045f)
        val rr = rad * 0.965f
        r.set(cx - rr, py - rr, cx + rr, py + rr)
        c.drawArc(r, ang(0f) * deg, (ang(3f) - ang(0f)) * deg, false, p)
        p.strokeWidth = max(1f, fh * 0.012f)
        r.set(cx - rad, py - rad, cx + rad, py + rad)
        c.drawArc(r, ang(0f) * deg, (ang(3f) - ang(0f)) * deg, false, p)

        // 目盛りと数字
        val major = floatArrayOf(-20f, -10f, -7f, -5f, -3f, -2f, -1f, 0f, 1f, 2f, 3f)
        val minor = floatArrayOf(-15f, -8.5f, -6f, -4f, -2.5f, -1.5f, -0.5f, 0.5f, 1.5f, 2.5f)
        txt.textSize = fh * 0.135f
        txt.isFakeBoldText = false
        for (v in major) {
            val a = ang(v)
            p.color = if (v > 0) red else ink
            p.strokeWidth = max(1f, fh * (if (v == 0f) 0.022f else 0.014f))
            c.drawLine(px(a, 1f), py(a, 1f), px(a, 1.1f), py(a, 1.1f), p)
            txt.color = p.color
            val tr = 1.1f + txt.textSize * 0.75f / rad
            c.drawText(abs(v).toInt().toString(), px(a, tr), py(a, tr) + txt.textSize * 0.35f, txt)
        }
        p.color = ink
        p.strokeWidth = max(0.8f, fh * 0.009f)
        for (v in minor) {
            val a = ang(v)
            p.color = if (v > 0) red else ink
            c.drawLine(px(a, 1f), py(a, 1f), px(a, 1.055f), py(a, 1.055f), p)
        }
        // 下の目盛り(%。小さいときは数字を出さない)
        p.color = ink
        p.strokeWidth = max(0.8f, fh * 0.008f)
        val pct = intArrayOf(0, 20, 40, 60, 80, 100)
        txt.textSize = fh * 0.075f
        txt.color = ink
        for (q in pct) {
            val v = (20.0 * log10(q / 100.0 * 1.0 + 1e-9)).toFloat() // 100% = 0 VU
            val a = if (q == 0) ang(-20f) else ang(v.coerceAtLeast(-20f))
            c.drawLine(px(a, 0.9f), py(a, 0.9f), px(a, 0.95f), py(a, 0.95f), p)
            if (txt.textSize >= 7f * density * 0.9f) c.drawText(q.toString(), px(a, 0.83f), py(a, 0.83f) + txt.textSize * 0.35f, txt)
        }
        // 「VU」と、左右
        txt.textSize = fh * 0.2f
        txt.isFakeBoldText = true
        txt.color = ink
        c.drawText("VU", cx, fy1 - fh * 0.1f, txt)
        txt.isFakeBoldText = false
        txt.textSize = fh * 0.09f
        txt.textAlign = Paint.Align.LEFT
        c.drawText(label, fx0 + fw * 0.05f, fy1 - fh * 0.08f, txt)
        txt.color = red
        txt.textAlign = Paint.Align.RIGHT
        c.drawText("+", fx1 - fw * 0.05f, fy1 - fh * 0.08f, txt)
        txt.textAlign = Paint.Align.CENTER

        // 針(影を少し付ける。軸は見せない)
        val a = ang(vu.coerceIn(-22f, 4.5f))
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = max(1.2f, fh * 0.018f)
        p.color = 0x33000000
        val sh = fh * 0.025f
        c.drawLine(px(a, 0.25f) + sh, py(a, 0.25f) + sh, px(a, 1.08f) + sh, py(a, 1.08f) + sh, p)
        p.color = 0xFF151311.toInt()
        c.drawLine(px(a, 0.25f), py(a, 0.25f), px(a, 1.08f), py(a, 1.08f), p)
        p.strokeCap = Paint.Cap.BUTT

        // ガラスの映り込みと、縁の内側の影
        p.style = Paint.Style.FILL
        p.shader = sh0.shade
        r.set(fx0, fy0, fx1, fy0 + fh * 0.22f)
        c.drawRect(r, p)
        p.shader = sh0.glass
        r.set(fx0, fy0, fx1, fy1)
        c.drawRect(r, p)
        p.shader = null
        c.restore()
    }

    /**
     * L・R の横棒。color はふだんの色、redFromDb(dBFS)を超えた所は赤。
     * hold が true ならピークホールドの印を出す。textColor は目盛りの数字の色
     */
    fun drawBars(
        c: Canvas, w: Float, h: Float, pL: Float, pR: Float, hL: Float, hR: Float,
        color: Int, textColor: Int, redFromDb: Float, hold: Boolean,
    ) {
        val lo = -48f
        val scaleH = if (h >= 34f * density) 11f * density else 0f
        val labW = 14f * density
        val gapY = 4f * density
        val rowH = ((h - scaleH - gapY) / 2).coerceAtLeast(2f)
        val bx = labW
        val bw = w - labW - 2f * density
        val segW = 5f * density
        val segs = max(12, (bw / segW).toInt())
        val sw = bw / segs
        val red = 0xFFFF3B30.toInt()
        txt.textSize = min(rowH * 0.9f, 11f * density)
        txt.color = textColor
        txt.isFakeBoldText = false
        fun xOf(db: Float) = bx + ((db.coerceIn(lo, 0f) - lo) / -lo) * bw
        for (ch in 0..1) {
            val y = ch * (rowH + gapY)
            val v = if (ch == 0) pL else pR
            txt.textAlign = Paint.Align.LEFT
            c.drawText(if (ch == 0) "L" else "R", 0f, y + rowH / 2 + txt.textSize * 0.35f, txt)
            val lit = ((v.coerceIn(lo, 0f) - lo) / -lo * segs).toInt()
            for (i in 0 until segs) {
                val db = lo + (i + 1) * (-lo) / segs
                val hot = db > -redFromDb
                val on = i < lit
                p.shader = null
                p.style = Paint.Style.FILL
                p.color = if (on) (if (hot) red else color) else ((if (hot) red else color) and 0x00FFFFFF) or 0x26000000
                r.set(bx + i * sw, y, bx + (i + 1) * sw - 1.5f * density, y + rowH)
                c.drawRoundRect(r, 1f * density, 1f * density, p)
            }
            if (hold) {
                val hv = if (ch == 0) hL else hR
                if (hv > lo) {
                    val i = (((hv.coerceIn(lo, 0f) - lo) / -lo * segs).toInt() - 1).coerceIn(0, segs - 1)
                    val db = lo + (i + 1) * (-lo) / segs
                    p.color = if (db > -redFromDb) red else 0xFFFFFFFF.toInt()
                    r.set(bx + i * sw, y - 1f * density, bx + (i + 1) * sw - 1.5f * density, y + rowH + 1f * density)
                    c.drawRoundRect(r, 1f * density, 1f * density, p)
                }
            }
        }
        if (scaleH > 0f) {
            txt.textSize = 9f * density
            txt.color = textColor
            val y = h - 2f * density
            for (m in intArrayOf(-48, -24, -12, -6, -3, 0)) {
                txt.textAlign = if (m == 0) Paint.Align.RIGHT else Paint.Align.CENTER
                txt.color = if (m > -redFromDb) red else textColor
                c.drawText(m.toString(), if (m == 0) w else xOf(m.toFloat()), y, txt)
            }
            txt.textAlign = Paint.Align.CENTER
        }
    }
}
