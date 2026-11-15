package com.uyatame.ucrt.aging

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// エージング(イヤホン・ヘッドホンの慣らし)で流す音を作る。Android の部品には頼らない(PC 上の単体テストで確かめられるように)。
// 音はどれも 2 チャンネル(左右交互)の float。左右は別々の乱数で作る(左右がまったく同じ音にならないように)

/** エージングの音源 */
enum class AgingSource(val id: String, val generated: Boolean) {
    /** UCRT のエージング音(ピンクノイズを土台に、スイープ・低音・高い帯域・休みを順に回す) */
    UCRT("ucrt", true),
    /** ライブラリから自動で選ぶ(周波数・ビット数の高い曲を優先) */
    LIBRARY_AUTO("library_auto", false),
    /** ライブラリから選ぶ(今の再生リスト・プレイリスト) */
    LIBRARY_PICK("library_pick", false),
    PINK("pink", true),
    WHITE("white", true),
    BROWN("brown", true),
    SWEEP("sweep", true),
    ;

    companion object {
        fun of(id: String): AgingSource = entries.firstOrNull { it.id == id } ?: UCRT
    }
}

/** 音を作るもの。fill は frames フレーム(左右交互で frames * 2 個)を書く */
interface AgingSignal {
    val rate: Int
    fun fill(out: FloatArray, frames: Int)
}

object AgingLevels {
    /** ノイズとスイープの実効値(-20 dBFS)。耳で合わせる音量より、上げすぎにならない控えめな値 */
    const val RMS = 0.1f
    /** 念のための上限(これを超える値は切る) */
    const val CLIP = 0.95f
    /** 超低域を切る周波数(直流や、振動板を無意味に大きく動かす成分を入れない) */
    const val HIGH_PASS_HZ = 8.0
}

/** 速い乱数(xorshift)。-1〜1 の一様な値を返す */
internal class Rng(seed: Long) {
    private var s = if (seed == 0L) 0x2545F4914F6CDD1DL else seed
    fun next(): Float {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        s = x
        // 上位 24 ビットで -1〜1
        return ((x ushr 40).toInt() / 8388608f) - 1f
    }
}

/** 1 次のハイパス(超低域・直流を切る) */
internal class HighPass(rate: Int, hz: Double) {
    private val r = exp(-2 * PI * hz / rate).toFloat()
    private var x1 = 0f
    private var y1 = 0f
    fun step(x: Float): Float {
        val y = x - x1 + r * y1
        x1 = x
        y1 = y
        return y
    }
}

private fun clip(v: Float) = if (v > AgingLevels.CLIP) AgingLevels.CLIP else if (v < -AgingLevels.CLIP) -AgingLevels.CLIP else v

/** ホワイトノイズ(どの周波数も同じ強さ。出力の周波数の半分まで) */
class WhiteNoise(override val rate: Int, seed: Long = 1) : AgingSignal {
    private val l = Rng(seed * 2 + 1)
    private val r = Rng(seed * 2 + 2)
    // 一様乱数の実効値は 1/√3
    private val k = AgingLevels.RMS * sqrt(3f)

    override fun fill(out: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            out[2 * i] = l.next() * k
            out[2 * i + 1] = r.next() * k
        }
    }
}

/**
 * ピンクノイズ(1 オクターブ上がるごとに -3 dB。どのオクターブも同じ強さに聞こえる)。
 * Voss-McCartney の方法: 更新の間隔が 2 倍ずつ違う乱数の列を足す。下はおよそ 10 Hz、上は出力の周波数の半分まで
 */
class PinkNoise(override val rate: Int, seed: Long = 3) : AgingSignal {
    private val rows: Int = (ln(rate / 10.0) / ln(2.0)).toInt().coerceIn(8, 20)
    private val ch = Array(2) { c -> Chan(Rng(seed * 7 + c + 1), rows, rate) }
    // 列(rows 個)と白い成分(1 個)の和の実効値は √((rows+1)/3)
    private val k = AgingLevels.RMS / sqrt((rows + 1) / 3f)

    private class Chan(val rng: Rng, n: Int, rate: Int) {
        val v = FloatArray(n) { rng.next() }
        var sum = v.sum()
        var count = 0L
        val hp = HighPass(rate, AgingLevels.HIGH_PASS_HZ)
    }

    override fun fill(out: FloatArray, frames: Int) {
        for (c in 0..1) {
            val s = ch[c]
            for (i in 0 until frames) {
                s.count++
                // 何番目の列を新しくするか(数の下から続く 0 の数。2 回に 1 回は 0 番、4 回に 1 回は 1 番…)
                val row = java.lang.Long.numberOfTrailingZeros(s.count).coerceAtMost(rows - 1)
                val nv = s.rng.next()
                s.sum += nv - s.v[row]
                s.v[row] = nv
                out[2 * i + c] = clip(s.hp.step((s.sum + s.rng.next()) * k))
            }
        }
    }
}

/** ブラウンノイズ(1 オクターブ上がるごとに -6 dB。低い音が多い)。ホワイトノイズを、少しずつ漏らしながら足し合わせて作る */
class BrownNoise(override val rate: Int, seed: Long = 5) : AgingSignal {
    // この周波数より下は平らにする(下へいくらでも大きくならないように)
    private val a = exp(-2 * PI * CORNER_HZ / rate).toFloat()
    private val rng = arrayOf(Rng(seed * 11 + 1), Rng(seed * 11 + 2))
    private val y = FloatArray(2)
    private val hp = arrayOf(HighPass(rate, AgingLevels.HIGH_PASS_HZ), HighPass(rate, AgingLevels.HIGH_PASS_HZ))
    // 足し合わせた値の実効値は (1/√3) / √(1 - a²)。そのあと超低域を切ると、強さが CORNER / (CORNER + 切る周波数) 倍になるので、その分を戻す
    private val k = AgingLevels.RMS * sqrt(3f) * sqrt(1f - a * a) *
        sqrt(((CORNER_HZ + AgingLevels.HIGH_PASS_HZ) / CORNER_HZ).toFloat())

    private companion object {
        const val CORNER_HZ = 15.0
    }

    override fun fill(out: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            for (c in 0..1) {
                y[c] = a * y[c] + rng[c].next()
                out[2 * i + c] = clip(hp[c].step(y[c] * k))
            }
        }
    }
}

/**
 * スイープ(正弦波の高さを、低い音から高い音へ、また低い音へと滑らかに動かす。オクターブごとに同じ時間)。
 * 上は 80 kHz か、出力の周波数の 45% の低い方まで。位相はつなげるので、折り返しやつなぎ目で音が途切れない
 */
class Sweep(override val rate: Int, private val seconds: Double = 30.0) : AgingSignal {
    val lowHz = 20.0
    val highHz = minOf(80_000.0, rate * 0.45)
    private val amp = AgingLevels.RMS * sqrt(2f)
    private var phase = 0.0
    private var n = 0L
    private val period = (seconds * rate).toLong().coerceAtLeast(2)

    /** 今の周波数(画面の表示用) */
    @Volatile var currentHz = lowHz
        private set

    fun hzAt(frame: Long): Double {
        val p = (frame % period).toDouble() / period
        // 前半で上がり、後半で下がる
        val u = if (p < 0.5) p * 2 else (1 - p) * 2
        return lowHz * (highHz / lowHz).pow(u)
    }

    override fun fill(out: FloatArray, frames: Int) {
        var hz = lowHz
        for (i in 0 until frames) {
            hz = hzAt(n++)
            phase += 2 * PI * hz / rate
            if (phase > 2 * PI) phase -= 2 * PI
            val v = (sin(phase) * amp).toFloat()
            out[2 * i] = v
            out[2 * i + 1] = v
        }
        currentHz = hz
    }
}

/**
 * UCRT のエージング音。10 分を 1 回りとして、次の順に流す(つなぎ目は 1 秒かけて入れ替える)。
 * - 0:00〜4:00 ピンクノイズ(40 秒ごとに -6〜0 dB でゆっくり強弱をつける。音楽のような大小の動き)
 * - 4:00〜4:30 スイープ(低い音から可聴域の上まで)
 * - 4:30〜5:30 ブラウンノイズ(低い音を多めにして、振動板を大きめに動かす)
 * - 5:30〜9:00 ピンクノイズにホワイトノイズを足したもの(可聴域より上の帯域にも音を入れる)
 * - 9:00〜9:30 スイープ
 * - 9:30〜10:00 ほぼ無音(-40 dB のピンクノイズ。動かし続けずに一息つく)
 * どれも実効値は -20 dBFS 前後
 */
class UcrtAgingSignal(override val rate: Int, seed: Long = 9) : AgingSignal {
    enum class Part { PINK, SWEEP, BROWN, WIDE, QUIET }

    private val pink = PinkNoise(rate, seed)
    private val white = WhiteNoise(rate, seed + 1)
    private val brown = BrownNoise(rate, seed + 2)
    private val sweep = Sweep(rate)
    private val bp = FloatArray(BLOCK * 2)
    private val bw = FloatArray(BLOCK * 2)
    private val bb = FloatArray(BLOCK * 2)
    private val bs = FloatArray(BLOCK * 2)
    private var n = 0L

    /** 今の部分(画面の表示用) */
    @Volatile var part = Part.PINK
        private set

    /** 1 回りの中の秒(0〜600)での、各音の重み(ピンク・ホワイト・ブラウン・スイープ) */
    fun weights(t: Double): FloatArray {
        val w = FloatArray(4)
        val lfo = 10.0.pow((-3.0 + 3.0 * sin(2 * PI * t / 40.0)) / 20.0).toFloat()
        for (s in SEGMENTS) {
            // 1 回りの終わりと次の始まりもつなぐので、前後の回りの区間としても見る
            for (shift in doubleArrayOf(-CYCLE_S.toDouble(), 0.0, CYCLE_S.toDouble())) {
                val from = s.from + shift
                val to = s.to + shift
                // 区間の端は 1 秒かけて入れ替える(前後の区間と重ねて、音量のくぼみを小さくする)
                val f = when {
                    t < from - FADE / 2 || t >= to + FADE / 2 -> 0.0
                    t < from + FADE / 2 -> (t - (from - FADE / 2)) / FADE
                    t >= to - FADE / 2 -> ((to + FADE / 2) - t) / FADE
                    else -> 1.0
                }.toFloat()
                if (f <= 0f) continue
                val pinkGain = if (s.lfo) s.pink * lfo else s.pink
                w[0] += pinkGain * f; w[1] += s.white * f; w[2] += s.brown * f; w[3] += s.sweep * f
            }
        }
        return w
    }

    private class Segment(
        val from: Double, val to: Double, val pink: Float, val white: Float, val brown: Float, val sweep: Float, val lfo: Boolean = false,
    )

    fun partAt(t: Double): Part = when {
        t < 240 -> Part.PINK
        t < 270 -> Part.SWEEP
        t < 330 -> Part.BROWN
        t < 540 -> Part.WIDE
        t < 570 -> Part.SWEEP
        else -> Part.QUIET
    }

    override fun fill(out: FloatArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val m = minOf(BLOCK, frames - done)
            pink.fill(bp, m); white.fill(bw, m); brown.fill(bb, m); sweep.fill(bs, m)
            val t = (n % (CYCLE_S * rate)).toDouble() / rate
            val w = weights(t)
            part = partAt(t)
            for (i in 0 until m * 2) {
                out[done * 2 + i] = clip(bp[i] * w[0] + bw[i] * w[1] + bb[i] * w[2] + bs[i] * w[3])
            }
            n += m
            done += m
        }
    }

    companion object {
        const val CYCLE_S = 600L
        private const val FADE = 1.0
        private val SEGMENTS = listOf(
            Segment(0.0, 240.0, 1f, 0f, 0f, 0f, lfo = true),
            Segment(240.0, 270.0, 0f, 0f, 0f, 1f),
            Segment(270.0, 330.0, 0f, 0f, 1f, 0f),
            Segment(330.0, 540.0, 0.85f, 0.5f, 0f, 0f),
            Segment(540.0, 570.0, 0f, 0f, 0f, 1f),
            Segment(570.0, 600.0, 0.01f, 0f, 0f, 0f),
        )
        /** 重みを決め直す単位(フレーム。192 kHz で 1.3 ミリ秒) */
        private const val BLOCK = 256
    }
}

/** 音源から、音を作るものを用意する(ライブラリの音源は null) */
fun signalFor(source: AgingSource, rate: Int): AgingSignal? = when (source) {
    AgingSource.UCRT -> UcrtAgingSignal(rate)
    AgingSource.PINK -> PinkNoise(rate)
    AgingSource.WHITE -> WhiteNoise(rate)
    AgingSource.BROWN -> BrownNoise(rate)
    AgingSource.SWEEP -> Sweep(rate)
    AgingSource.LIBRARY_AUTO, AgingSource.LIBRARY_PICK -> null
}
