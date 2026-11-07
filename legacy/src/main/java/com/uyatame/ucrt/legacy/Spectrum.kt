package com.uyatame.ucrt.legacy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.uyatame.ucrt.legacy.ui.add
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * スペクトラムアナライザー(本家の SpectrumTap・SpectrumView を、軽さ優先で写したもの)。
 *
 * 再生のスレッドが、音の加工のあとの音を左右それぞれ輪のバッファーに入れておき(VU メーターのため。スペクトラムは混ぜて使う)、
 * 画面は「今鳴っている位置」(出力の再生位置)までの音を取り出して周波数に分ける。
 * 設定でオンにして、再生画面を開いている間だけ動く(それ以外は何もしない)
 */
object Spectrum {
    /** 表示している画面があるか(ある間だけ写す) */
    @Volatile var enabled = false
    /** 表示しているスペクトラムの数(再生画面とロック画面の両方に出ていても、片方を閉じたとき止まらないように) */
    private var views = 0

    /** スペクトラムの表示が増えた・減った(メインのスレッドから) */
    fun viewAttached(on: Boolean) {
        views = (views + if (on) 1 else -1).coerceAtLeast(0)
        enabled = views > 0
    }

    // 写しておく長さ(約 6 秒分。44.1 kHz で)。鳴るまでの遅れ(DAR の x16 と USB DAC のリング)より長くないと、
    // 鳴る前に上書きされて表示が崩れる(本家と同じ不具合を防ぐ)
    private const val RING = 1 shl 18
    private const val MASK = RING - 1
    const val FFT_SIZE = 1024
    const val BANDS = 32

    private val ringL = FloatArray(RING)
    private val ringR = FloatArray(RING)
    private val lock = Any()
    private var owner: Any? = null
    private var headOf: (() -> Long)? = null
    /** 輪のバッファーに入れたサンプルの数と、その先頭の位置(音源のフレームで数える) */
    private var stored = 0L
    private var base = 0L
    private var rate = 44100

    /**
     * 出力に書く前に呼ぶ(再生のスレッドから)。buf はチャンネル交互の float、start はその先頭の位置(音源のフレーム)、
     * head は今鳴っている位置(同じく音源のフレーム)
     */
    fun submit(owner: Any, buf: FloatArray, frames: Int, channels: Int, sampleRate: Int, start: Long, head: () -> Long) {
        if (!enabled || frames <= 0 || channels <= 0) return
        synchronized(lock) {
            // 別の曲・シークのあと(位置が続いていない)は最初から。周波数の変換の端数で 2 フレームまではずれても続ける
            if (this.owner !== owner || sampleRate != rate || kotlin.math.abs(start - (base + stored)) > 2) {
                this.owner = owner
                headOf = head
                rate = sampleRate
                stored = 0
                base = start
            }
            val two = channels >= 2
            var k = 0
            for (f in 0 until frames) {
                val m = (stored and MASK.toLong()).toInt()
                ringL[m] = buf[k]
                ringR[m] = if (two) buf[k + 1] else buf[k]
                k += channels
                stored++
            }
        }
    }

    /** 出力をやめたとき */
    fun forget() {
        synchronized(lock) { owner = null; headOf = null; stored = 0 }
    }

    private var lastHead = -1L
    private var headMovedAt = 0L
    private val headLock = Any()

    /** 今鳴っている位置までの dst.size 個の音を入れる(左右を混ぜて)。音が無い・止まっていれば 0(入れた周波数を返す) */
    fun window(dst: FloatArray): Int = read(dst, null, dst.size)

    /** 今鳴っている位置までの n 個の音を、左右に分けて入れる(VU メーター用) */
    fun windowStereo(l: FloatArray, r: FloatArray, n: Int): Int = read(l, r, n)

    private fun read(l: FloatArray, r: FloatArray?, n: Int): Int {
        val h: () -> Long
        val rt: Int
        synchronized(lock) { h = headOf ?: return 0; rt = rate }
        val head = runCatching { h() }.getOrDefault(0L)
        // 再生位置が少しの間進まなければ(一時停止・停止)、音は鳴っていないので 0(バーがすっと下がる)
        val now = SystemClock.uptimeMillis()
        synchronized(headLock) {
            if (head != lastHead) { lastHead = head; headMovedAt = now } else if (now - headMovedAt > 150) return 0
        }
        synchronized(lock) {
            if (stored == 0L) return 0
            val end = (head - base).coerceIn(0L, stored)
            val start = end - n
            for (i in 0 until n) {
                val k = start + i
                if (k < 0 || k < stored - RING) { l[i] = 0f; if (r != null) r[i] = 0f; continue }
                val m = (k and MASK.toLong()).toInt()
                if (r == null) l[i] = (ringL[m] + ringR[m]) * 0.5f else { l[i] = ringL[m]; r[i] = ringR[m] }
            }
            return rt
        }
    }
}

/**
 * 再生画面のスペクトラム(棒グラフ)。表示している間だけ、決まった間隔で計算して描く。
 * 止まって動きが無くなったら、描き直しをやめて間隔をあけて確かめるだけにする(電池と CPU のため)
 */
class SpectrumView(ctx: Context, private val color: Int, private val refreshMs: Int) : View(ctx) {
    private val n = Spectrum.FFT_SIZE
    private val buf = FloatArray(n)
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val win = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    private val target = FloatArray(Spectrum.BANDS)
    private val bands = FloatArray(Spectrum.BANDS)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@SpectrumView.color }
    private val r = RectF()
    private var last = 0L
    private var still = false
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val got = analyze()
            val moved = step()
            if (moved > 0.002f || got) invalidate()
            still = moved < 0.002f && !got
            postDelayed(this, if (still) 200L else refreshMs.toLong().coerceIn(16L, 200L))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Spectrum.viewAttached(true)
        running = true
        post(tick)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        Spectrum.viewAttached(false)
        super.onDetachedFromWindow()
    }

    /** 周波数に分ける。音が無ければ false(目標は 0) */
    private fun analyze(): Boolean {
        val rate = Spectrum.window(buf)
        if (rate <= 0) { target.fill(0f); return false }
        for (i in 0 until n) { re[i] = buf[i] * win[i]; im[i] = 0f }
        fft()
        val minHz = 40.0
        val maxHz = min(18000.0, rate * 0.5)
        for (b in target.indices) {
            val f0 = minHz * Math.pow(maxHz / minHz, b.toDouble() / target.size)
            val f1 = minHz * Math.pow(maxHz / minHz, (b + 1.0) / target.size)
            val k0 = max(1, (f0 * n / rate).toInt())
            val k1 = max(k0 + 1, min(n / 2, (f1 * n / rate).toInt()))
            var m = 0f
            for (k in k0 until k1) m = max(m, re[k] * re[k] + im[k] * im[k])
            val amp = (4.0 * sqrt(m.toDouble()) / n).coerceAtLeast(1e-9)
            target[b] = (((20.0 * log10(amp) + 72.0) / 66.0).toFloat().coerceIn(0f, 1f) - 0.06f).coerceAtLeast(0f) / 0.94f
        }
        return true
    }

    /** 表示を目標に近づける(上がるときは速く、下がるときはゆっくり)。いちばん大きく動いた量を返す */
    private fun step(): Float {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 1f / 30 else ((now - last).coerceIn(1L, 200L) / 1000f)
        last = now
        val up = 1f - exp(-40f * dt)
        val down = 1f - exp(-8f * dt)
        var moved = 0f
        for (i in bands.indices) {
            val d = (target[i] - bands[i]) * (if (target[i] > bands[i]) up else down)
            bands[i] += d
            moved = max(moved, kotlin.math.abs(d))
        }
        return moved
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val slot = w / bands.size
        val bw = slot * 0.62f
        val rad = bw * 0.3f
        for (i in bands.indices) {
            val bh = max(2f, h * bands[i])
            val x = i * slot + (slot - bw) / 2
            r.set(x, h - bh, x + bw, h)
            c.drawRoundRect(r, rad, rad, paint)
        }
    }

    private fun fft() {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
        }
        var len = 2
        while (len <= n) {
            val a = -2.0 * PI / len
            val wr0 = cos(a).toFloat(); val wi0 = sin(a).toFloat()
            val half = len / 2
            var b = 0
            while (b < n) {
                var wr = 1f; var wi = 0f
                for (k in 0 until half) {
                    val p = b + k; val q = p + half
                    val vr = re[q] * wr - im[q] * wi
                    val vi = re[q] * wi + im[q] * wr
                    re[q] = re[p] - vr; im[q] = im[p] - vi
                    re[p] += vr; im[p] += vi
                    val t = wr * wr0 - wi * wi0
                    wi = wr * wi0 + wi * wr0; wr = t
                }
                b += len
            }
            len = len shl 1
        }
    }
}

/**
 * 再生画面の VU メーター(本家の VuMeterView と同じ。描き方と動き方は core の VuMeter・VuPainter を使う)。
 * style: 0 = 針、1 = バー。タップで切り替える(onStyle)。表示している間だけ計算し、止まったら描き直さない
 */
class VuMeterView(
    ctx: Context, private var style: Int, mode: Int, refDb: Int, private val redDb: Int, private val hold: Boolean,
    private val color: Int, private val textColor: Int, private val refreshMs: Int, private val onStyle: (Int) -> Unit,
) : View(ctx) {
    private val meter = com.uyatame.ucrt.player.VuMeter().apply { this.mode = mode; this.refDb = refDb.toFloat() }
    private val painter = com.uyatame.ucrt.player.VuPainter(resources.displayMetrics.density)
    private val l = FloatArray(8192)
    private val r = FloatArray(8192)
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            // 直前の約 50 ms の音
            val rate = Spectrum.windowStereo(l, r, 8192)
            val ok = rate > 0
            val n = if (ok) (rate / 20).coerceIn(256, 8192) else 0
            if (ok && n < 8192) { System.arraycopy(l, 8192 - n, l, 0, n); System.arraycopy(r, 8192 - n, r, 0, n) }
            val moved = meter.step(l, r, n, ok, System.nanoTime())
            val still = !ok && moved < 0.01f
            if (!still) invalidate()
            postDelayed(this, if (still) 200L else refreshMs.toLong().coerceIn(16L, 200L))
        }
    }

    init {
        setOnClickListener { style = if (style == 0) 1 else 0; onStyle(style); invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Spectrum.viewAttached(true)
        running = true
        post(tick)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        Spectrum.viewAttached(false)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (style == 1) painter.drawBars(c, w, h, meter.peak[0], meter.peak[1], meter.hold[0], meter.hold[1], color, textColor, redDb.toFloat(), hold)
        else painter.drawNeedles(c, w, h, meter.needle[0], meter.needle[1])
    }
}

/**
 * 再生画面(とロック画面)に、スペクトラムと VU メーターを設定どおりに足す(本家と同じ: スペクトラム・VU メーター・両方)。
 * color はふだんの色、textColor は目盛りの数字の色
 */
fun addMeters(parent: android.widget.LinearLayout, ctx: Context, color: Int, textColor: Int, small: Boolean) {
    val sp = LegacyApp.app.prefs.sp
    val show = sp.getInt("meterShow", 0).coerceIn(0, 2)
    val ms = sp.getInt("legacySpectrumMs", 50)
    val base = if (small) 36 else 48
    val top = if (small) 4 else 10
    if (show != 1) parent.add(SpectrumView(ctx, color, ms), h = base, t = top, l = 6, r = 6)
    if (show != 0) {
        // 針は高さを 1.5 倍、バーは少し低く
        fun heightOf(style: Int) = if (style == 1) (base * 0.85f).toInt() else (base * 1.5f).toInt()
        lateinit var v: VuMeterView
        v = VuMeterView(
            ctx, sp.getInt("vuStyle", 0).coerceIn(0, 1), sp.getInt("vuMode", 0).coerceIn(0, 1), sp.getInt("vuRefDb", 18),
            sp.getInt("vuRedDb", 6), sp.getBoolean("vuHold", true), color, textColor, ms,
        ) { st ->
            // タップで切り替えたら覚えておく(画面は作り直さず、高さだけ合わせる)
            sp.edit().putInt("vuStyle", st).apply()
            v.layoutParams?.let { it.height = com.uyatame.ucrt.legacy.ui.dp(heightOf(st)); v.layoutParams = it }
        }
        parent.add(v, h = heightOf(sp.getInt("vuStyle", 0).coerceIn(0, 1)), t = if (show == 2) 6 else top, l = 6, r = 6)
    }
}
