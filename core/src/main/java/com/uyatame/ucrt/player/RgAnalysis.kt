package com.uyatame.ucrt.player

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * 音の大きさ(ラウドネス)を測る。EBU R128 / ITU-R BS.1770 の積分ラウドネス(LUFS)。
 * K 特性のフィルターを通した音の 400ms ごとの平均の強さ(100ms ずつずらす)を集め、
 * -70 LUFS と「全体 - 10 LU」で静かな部分を除いて平均する。ReplayGain 2.0 はこの値を -18 LUFS にそろえる
 */
class LoudnessMeter(rate: Int, private val channels: Int) {
    // K 特性: 高い音を持ち上げるフィルター(1 段目)と、低い音を切るフィルター(2 段目)。係数はどの周波数でも作れる式(libebur128 と同じ)
    private val b0: Double; private val b1: Double; private val b2: Double; private val a1: Double; private val a2: Double
    private val c1: Double; private val c2: Double
    private val z1 = DoubleArray(channels); private val z2 = DoubleArray(channels)
    private val y1 = DoubleArray(channels); private val y2 = DoubleArray(channels)

    init {
        val fs = rate.toDouble()
        // 1 段目
        val f0 = 1681.974450955533
        val g = 3.999843853973347
        val q = 0.7071752369554196
        val k = tan(PI * f0 / fs)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        b0 = (vh + vb * k / q + k * k) / a0
        b1 = 2.0 * (k * k - vh) / a0
        b2 = (vh - vb * k / q + k * k) / a0
        a1 = 2.0 * (k * k - 1.0) / a0
        a2 = (1.0 - k / q + k * k) / a0
        // 2 段目
        val hf0 = 38.13547087602444
        val hq = 0.5003270373238773
        val hk = tan(PI * hf0 / fs)
        val d = 1.0 + hk / hq + hk * hk
        c1 = 2.0 * (hk * hk - 1.0) / d
        c2 = (1.0 - hk / hq + hk * hk) / d
    }

    /** 1 チャンネルの重み(5.1ch などの後ろのチャンネルは 1.41、LFE は数えない) */
    private val weight = DoubleArray(channels) { c ->
        when {
            channels <= 2 -> 1.0
            c == 3 -> 0.0
            c >= 4 -> 1.41
            else -> 1.0
        }
    }

    private val subLen = (rate / 10).coerceAtLeast(1) // 100ms
    private var subPos = 0
    private var subSum = 0.0
    /** 100ms ごとの強さ(チャンネルを合わせた平均の 2 乗) */
    private val subs = ArrayList<Double>()
    /** 400ms の区切り(100ms ずつずらす)ごとの強さ */
    val blocks = ArrayList<Double>()
    /** いちばん大きい標本の値(1 = 0 dBFS) */
    var peak = 0f
        private set

    /** 音を足す(float・チャンネル交互) */
    fun feed(buf: FloatArray, frames: Int) {
        val ch = channels
        var p = 0
        for (f in 0 until frames) {
            var e = 0.0
            for (c in 0 until ch) {
                val s = buf[p + c]
                val a = abs(s)
                if (a > peak) peak = a
                // 1 段目(高い音を持ち上げる。直接形 II の転置)
                val x = s.toDouble()
                val u = b0 * x + z1[c]
                z1[c] = b1 * x - a1 * u + z2[c]
                z2[c] = b2 * x - a2 * u
                // 2 段目(低い音を切る。分子は 1, -2, 1)
                val v = u + y1[c]
                y1[c] = -2.0 * u - c1 * v + y2[c]
                y2[c] = u - c2 * v
                e += weight[c] * v * v
            }
            subSum += e
            p += ch
            if (++subPos == subLen) {
                subs.add(subSum / subLen)
                subSum = 0.0
                subPos = 0
                val n = subs.size
                if (n >= 4) blocks.add((subs[n - 1] + subs[n - 2] + subs[n - 3] + subs[n - 4]) / 4.0)
            }
        }
    }

    companion object {
        /** 区切りの強さの一覧から、積分ラウドネス(LUFS)。音がほとんど無ければ null */
        fun integrated(blocks: List<Double>): Double? {
            fun lufs(z: Double) = -0.691 + 10.0 * log10(z)
            val absGate = blocks.filter { it > 0.0 && lufs(it) > -70.0 }
            if (absGate.isEmpty()) return null
            val rel = lufs(absGate.average()) - 10.0
            val gated = absGate.filter { lufs(it) > rel }
            if (gated.isEmpty()) return null
            return lufs(gated.average())
        }

        /** ReplayGain 2.0 の基準(-18 LUFS)にそろえるための利得(dB) */
        fun gainDb(lufs: Double): Float = (-18.0 - lufs).toFloat()
    }
}

/**
 * 解析した ReplayGain の値(曲の URI ごと)。曲のファイルは書き換えず、アプリの中に保存する。
 * 曲に値が書かれていないときに使う
 */
object RgStore {
    private var file: File? = null
    private val map = HashMap<String, RgInfo>()
    @Volatile private var loaded = false

    /** 画面表示用: 解析済みの曲の数 */
    @Volatile var count = 0
        private set
    /** 解析済みの曲の数が変わったとき(メインのスレッドで呼ぶ) */
    @Volatile var onCount: ((Int) -> Unit)? = null

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.filesDir, "replaygain.json").also { file = it }
        runCatching {
            if (!f.exists()) return@runCatching
            val o = JSONObject(f.readText())
            o.keys().forEach { k ->
                val v = o.optJSONObject(k) ?: return@forEach
                fun num(n: String) = if (v.has(n)) v.optDouble(n).toFloat() else null
                map[k] = RgInfo(num("tg"), num("tp"), num("ag"), num("ap"))
            }
        }
        count = map.size
        val n = count
        android.os.Handler(android.os.Looper.getMainLooper()).post { onCount?.invoke(n) }
    }

    @Synchronized
    fun get(uri: String): RgInfo? = map[uri]

    @Synchronized
    fun has(uri: String): Boolean = map.containsKey(uri)

    @Synchronized
    fun putAll(values: Map<String, RgInfo>) {
        map.putAll(values)
        save()
    }

    @Synchronized
    fun clear() {
        map.clear()
        save()
    }

    private fun save() {
        val o = JSONObject()
        for ((k, v) in map) {
            val e = JSONObject()
            v.trackGain?.let { e.put("tg", it.toDouble()) }
            v.trackPeak?.let { e.put("tp", it.toDouble()) }
            v.albumGain?.let { e.put("ag", it.toDouble()) }
            v.albumPeak?.let { e.put("ap", it.toDouble()) }
            o.put(k, e)
        }
        val f = file ?: return
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
        }
        val n = map.size
        android.os.Handler(android.os.Looper.getMainLooper()).post { count = n; onCount?.invoke(n) }
    }
}
