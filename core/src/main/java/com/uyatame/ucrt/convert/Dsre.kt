package com.uyatame.ucrt.convert

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

/**
 * DSRE の設定(プリセット 1 つ分)。
 * harmonics: 作る倍音の数(1〜24。出力の周波数が高いほど多く作れる)、strength: 足す量(0.10〜0.80)、
 * rate: 出力の周波数、width: ステレオの広がり(0.50〜1.50。1 で元のまま)、dynamic: 立ち上がりの強調(1.00〜1.50。1 で元のまま)
 */
data class DsreParams(
    val name: String = "",
    val harmonics: Int = 8,
    val strength: Float = 0.45f,
    val rate: Int = 96000,
    val width: Float = 1.0f,
    val dynamic: Float = 1.05f,
) {
    fun clamped() = copy(
        name = name.trim().take(80),
        harmonics = harmonics.coerceIn(1, 24),
        strength = strength.coerceIn(0.10f, 0.80f),
        rate = if (rate in RATES) rate else 96000,
        width = width.coerceIn(0.50f, 1.50f),
        dynamic = dynamic.coerceIn(1.00f, 1.50f),
    )

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("harmonics", harmonics).put("strength", strength.toDouble())
        .put("rate", rate).put("width", width.toDouble()).put("dynamic", dynamic.toDouble())

    companion object {
        val RATES = listOf(44100, 48000, 88200, 96000, 176400, 192000)

        /** 最初から入っているプリセット(名前は画面で付ける) */
        val BUILTIN = listOf(
            DsreParams("natural", 8, 0.40f, 96000, 1.00f, 1.05f),
            DsreParams("clear", 12, 0.55f, 96000, 1.05f, 1.10f),
            DsreParams("strong", 16, 0.70f, 192000, 1.10f, 1.20f),
            DsreParams("light", 4, 0.25f, 48000, 1.00f, 1.00f),
        )

        fun fromJson(o: JSONObject): DsreParams = DsreParams(
            o.getString("name"), o.getInt("harmonics"), o.getDouble("strength").toFloat(), o.getInt("rate"),
            o.getDouble("width").toFloat(), o.getDouble("dynamic").toFloat(),
        ).clamped()

        /** 書き出すファイルの中身(ucrt-dsre-presets の 2 版) */
        fun export(list: List<DsreParams>): String = JSONObject()
            .put("schema", "ucrt-dsre-presets").put("version", 2).put("exportedBy", "UCRT")
            .put("presets", JSONArray().apply { list.forEach { put(it.toJson()) } })
            .toString(2)

        /**
         * 読み込んだファイルからプリセットを取り出す(読めなかった数も返す)。
         * 1 版(以前の試作のもの。値が 0〜1 の割合)も、近い値に直して読む
         */
        fun import(raw: String): Pair<List<DsreParams>, Int> {
            val root = JSONObject(raw)
            require(root.optString("schema") == "ucrt-dsre-presets") { "unsupported file" }
            val ver = root.optInt("version", -1)
            require(ver == 1 || ver == 2) { "unsupported version $ver" }
            val arr = root.getJSONArray("presets")
            val ok = ArrayList<DsreParams>()
            var bad = 0
            for (i in 0 until arr.length()) {
                val p = runCatching {
                    val o = arr.getJSONObject(i)
                    val name = o.getString("name").trim()
                    require(name.isNotEmpty() && name.length <= 80)
                    if (ver == 2) fromJson(o) else DsreParams(
                        name,
                        (1 + o.getDouble("harmonic").coerceIn(0.0, 1.0) * 23).toInt(),
                        o.getDouble("strength").toFloat(),
                        o.getInt("rate"),
                        (0.5 + o.getDouble("width").coerceIn(0.0, 1.0)).toFloat(),
                        (1.0 + o.getDouble("dynamic").coerceIn(0.0, 2.0) * 0.25).toFloat(),
                    ).clamped()
                }.getOrNull()
                if (p == null) bad++ else ok.add(p)
            }
            return ok to bad
        }
    }
}

/**
 * DSRE(音の高域の補い。元の音の上の端の帯域から倍音を作り、元の音で欠けている高い帯域に足す)。出力の周波数 rate で動く。
 *
 * 1. 元の音が切れている周波数 cutHz(MP3 なら 16 kHz 前後。解析で求める)のすぐ下の帯域を取り出す
 * 2. その帯域の大きさで割って ±1 にそろえ、チェビシェフ多項式 T2〜Tn を重み 1/h で足す
 *    (正弦波なら、ちょうど 2〜n 倍の倍音だけができる)。取り出す帯域は、いちばん高い倍音が出力のナイキスト周波数を
 *    超えない幅にするので、折り返しの雑音は出ない
 * 3. 帯域の大きさを掛け戻し、cutHz より上だけを残して、strength の量だけ元の音に足す
 * 4. 立ち上がりの強調(速い包絡と遅い包絡の差)とステレオの広がり(M/S)
 * 入力・出力はチャンネル交互の float(その場で書き換える)。音量の上限は後ろのリミッターで抑える
 */
class Dsre(private val rate: Int, private val channels: Int, p: DsreParams, cutHz: Double) {
    private val strength = p.strength.toDouble() * 1.4
    private val width = p.width.toDouble()
    private val dyn = (p.dynamic - 1.0).coerceIn(0.0, 0.5) * 2
    /** 作る倍音の最大の次数 */
    val order: Int
    private val active: Boolean
    private val bandHp: Array<Array<Biquad>>
    private val bandLp: Array<Array<Biquad>>
    private val outHp: Array<Array<Biquad>>
    private val outLp: Array<Array<Biquad>>
    private val env = DoubleArray(channels)
    private val wts: DoubleArray
    private val envAtk = exp(-1.0 / (rate * 0.0005))
    private val envRel = exp(-1.0 / (rate * 0.03))
    // 立ち上がりの強調の包絡(全チャンネル共通)
    private var fast = 0.0
    private var slow = 0.0
    private val fA = exp(-1.0 / (rate * 0.001)); private val fR = exp(-1.0 / (rate * 0.02))
    private val sA = exp(-1.0 / (rate * 0.02)); private val sR = exp(-1.0 / (rate * 0.2))
    private var dynGain = 1.0

    init {
        val nyq = rate / 2.0
        val cut = cutHz.coerceIn(4000.0, nyq * 0.95)
        // いちばん高い倍音 h × 帯域の上の端 が、ナイキスト周波数の 0.95 倍を超えないようにする
        val maxH = floor(nyq * 0.95 / (cut * 0.5)).toInt()
        order = minOf(p.harmonics + 1, maxH).coerceAtLeast(1)
        active = order >= 2 && cut < nyq * 0.9
        val hi = minOf(cut, nyq * 0.95 / order)
        val lo = hi / 2
        bandHp = Array(channels) { Biquad.highpass4(rate, lo) }
        bandLp = Array(channels) { Biquad.lowpass4(rate, hi) }
        outHp = Array(channels) { Biquad.highpass4(rate, cut) }
        outLp = Array(channels) { Biquad.lowpass4(rate, minOf(nyq * 0.92, cut * order * 1.05)) }
        // 重み: 1/h(高い倍音ほど弱く。自然な高域の減り方に近い)
        wts = DoubleArray(order + 1) { h -> if (h < 2) 0.0 else 1.0 / h.toDouble().pow(1.1) }
    }

    private fun run(f: Array<Biquad>, x: Double): Double {
        var v = x
        for (b in f) v = b.run(v)
        return v
    }

    fun process(buf: FloatArray, frames: Int) {
        val ch = channels
        for (f in 0 until frames) {
            val base = f * ch
            // ---- 倍音 ----
            if (active) {
                for (c in 0 until ch) {
                    val x = buf[base + c].toDouble()
                    val band = run(bandLp[c], run(bandHp[c], x))
                    val a = abs(band)
                    env[c] = if (a > env[c]) envAtk * env[c] + (1 - envAtk) * a else envRel * env[c] + (1 - envRel) * a
                    val e = env[c]
                    var add = 0.0
                    if (e > 1e-7) {
                        val u = (band / (e * 1.5)).coerceIn(-1.0, 1.0)
                        // T0 = 1, T1 = u, T(k+1) = 2u·T(k) - T(k-1)
                        var t0 = 1.0
                        var t1 = u
                        for (h in 2..order) {
                            val t2 = 2 * u * t1 - t0
                            add += wts[h] * t2
                            t0 = t1; t1 = t2
                        }
                        add *= e
                    }
                    val hf = run(outLp[c], run(outHp[c], add))
                    buf[base + c] = (x + strength * hf).toFloat()
                }
            }
            // ---- 立ち上がりの強調 ----
            if (dyn > 0) {
                var pk = 0.0
                for (c in 0 until ch) { val a = abs(buf[base + c].toDouble()); if (a > pk) pk = a }
                fast = if (pk > fast) fA * fast + (1 - fA) * pk else fR * fast + (1 - fR) * pk
                slow = if (pk > slow) sA * slow + (1 - sA) * pk else sR * slow + (1 - sR) * pk
                val t = if (slow > 1e-6) ((fast / slow) - 1).coerceIn(0.0, 1.0) else 0.0
                val g = 1 + dyn * 0.5 * t
                // 倍率は急に変えない(雑音にならないように)
                dynGain += (g - dynGain) * 0.02
                for (c in 0 until ch) buf[base + c] = (buf[base + c] * dynGain).toFloat()
            }
            // ---- ステレオの広がり ----
            if (ch == 2 && width != 1.0) {
                val l = buf[base].toDouble()
                val r = buf[base + 1].toDouble()
                val m = (l + r) * 0.5
                val s = (l - r) * 0.5 * width
                buf[base] = (m + s).toFloat()
                buf[base + 1] = (m - s).toFloat()
            }
        }
    }
}
