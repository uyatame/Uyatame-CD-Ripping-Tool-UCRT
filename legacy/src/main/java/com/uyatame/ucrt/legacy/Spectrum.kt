package com.uyatame.ucrt.legacy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
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
 * 再生のスレッドが、音の加工のあとの音を左右を混ぜた 1 チャンネルにして輪のバッファーに入れておき、
 * 画面は「今鳴っている位置」(出力の再生位置)までの音を取り出して周波数に分ける。
 * 設定でオンにして、再生画面を開いている間だけ動く(それ以外は何もしない)
 */
object Spectrum {
    /** 表示している画面があるか(ある間だけ写す) */
    @Volatile var enabled = false

    private const val RING = 1 shl 16
    private const val MASK = RING - 1
    const val FFT_SIZE = 1024
    const val BANDS = 32

    private val ring = FloatArray(RING)
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
            val use = min(channels, 2)
            val inv = 1f / use
            var k = 0
            for (f in 0 until frames) {
                var s = 0f
                for (c in 0 until use) s += buf[k + c]
                k += channels
                ring[(stored and MASK.toLong()).toInt()] = s * inv
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

    /** 今鳴っている位置までの dst.size 個の音を入れる。音が無い・止まっていれば 0(入れた周波数を返す) */
    fun window(dst: FloatArray): Int {
        val h: () -> Long
        val r: Int
        synchronized(lock) { h = headOf ?: return 0; r = rate }
        val head = runCatching { h() }.getOrDefault(0L)
        // 再生位置が少しの間進まなければ(一時停止・停止)、音は鳴っていないので 0(バーがすっと下がる)
        val now = SystemClock.uptimeMillis()
        if (head != lastHead) { lastHead = head; headMovedAt = now } else if (now - headMovedAt > 150) return 0
        synchronized(lock) {
            if (stored == 0L) return 0
            val end = (head - base).coerceIn(0L, stored)
            val start = end - dst.size
            for (i in dst.indices) {
                val k = start + i
                dst[i] = if (k < 0 || k < stored - RING) 0f else ring[(k and MASK.toLong()).toInt()]
            }
            return r
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
        Spectrum.enabled = true
        running = true
        post(tick)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        Spectrum.enabled = false
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
