package com.uyatame.ucrt.convert

import kotlin.math.max
import kotlin.math.min

/**
 * 純正 DSRE のチャンク分割ストリーミング版 (製品経路)。算法は全長版 ([PureDsre] / C++ 版 dsre_core)
 * と同じで、構造だけを「FFT 長で決まる定数メモリ」に作り替えたもの (設計書「純正DSRE移植」3.)。
 *
 * - 窓は FFT_SIZE (2^19) で、両側に MARGIN の重なりを持ち、中央の BODY だけを採用する (overlap-save 型)。
 * - 発振器の位相は絶対サンプル位置で引くため、窓をまたいでも連続する。
 * - 心理音響段は窓ごと filtfilt (マージンが整定を吸収する)。ダイナミクスは包絡の状態を持ち越す。
 * - 末尾の全体ピーク正規化はしない (後段の Limiter が天井を保証する。設計書 2.4)。
 *
 * 契約は [Limiter] と同じ: process はその場で書き換え、出力は [latency] フレーム遅れる
 * (先頭の latency フレームは無音なので呼び出し側で捨てる)。最後に [flush] で残りを受ける。
 * latency が MARGIN より大きいのは、出力が BODY 単位でしか確定しないため
 * (位置 q の出力には q + BODY + MARGIN までの入力が要る)。
 *
 * メモリは曲長に依存しない定数 (窓・スペクトル・履歴などの作業領域で約 40 MB)。
 * 書き込みの分割の仕方によらず出力はビット単位で同一になる。
 */
class PureDsreStream(
    private val rate: Int,
    private val channels: Int,
    private val params: PureDsreParams = PureDsreParams(),
) {
    constructor(rate: Int, channels: Int, p: DsreParams, cutHz: Double) :
        this(rate, channels, PureDsreParams.fromDsre(p))

    companion object {
        const val FFT_SIZE = 1 shl 19
        const val MARGIN = 16384
        const val BODY = FFT_SIZE - 2 * MARGIN
    }

    /** 先頭の、遅れのために出す無音のフレーム数 (呼び出し側で捨てる) */
    val latency: Int = FFT_SIZE - MARGIN

    private val exciter = PureExciter(rate, FFT_SIZE)
    private val psycho = PurePsycho(rate)
    private val dynamics = Array(channels) { PureDynamics(rate, params.dynamicEnhancement) }

    // 入力履歴 (チャンネル毎)。hist[c][0] の絶対位置が histStart、全チャンネル共通の長さ histLen
    private var hist = Array(channels) { FloatArray(FFT_SIZE + MARGIN) }
    private var histLen = 0
    private var histStart = 0L
    private var totalFed = 0L

    // 窓の作業領域 (winPsy は使わず、心理段の出力先は winIn を再利用する)
    private val winIn = Array(channels) { FloatArray(FFT_SIZE) }
    private val winOut = Array(channels) { FloatArray(FFT_SIZE) }
    private val bodyBuf = Array(channels) { FloatArray(BODY) }
    private val rawBody = Array(channels) { FloatArray(BODY) }

    // 確定した出力 (チャンネル交互)。out[0] の絶対位置が outStart
    private var out = FloatArray(BODY * channels * 2)
    private var outLen = 0 // フレーム数
    private var outStart = 0L
    private var emitted = 0L // 次に出す絶対位置
    private var nextBody = 0L

    fun process(buf: FloatArray, frames: Int) {
        val inBefore = totalFed
        appendInput(buf, frames)
        totalFed += frames
        produce(final = false)
        val ch = channels
        for (f in 0 until frames) {
            val q = inBefore + f - latency
            val base = f * ch
            if (q < 0) {
                for (c in 0 until ch) buf[base + c] = 0f
            } else {
                val idx = (q - outStart).toInt() * ch
                for (c in 0 until ch) buf[base + c] = out[idx + c]
            }
        }
        emitted = max(emitted, inBefore + frames - latency)
        dropOutBefore(emitted)
    }

    /** 残りの出力を out (latency フレーム分の大きさ) へ書く。戻り値は書いたフレーム数 */
    fun flush(outBuf: FloatArray): Int {
        produce(final = true)
        val remain = (totalFed - emitted).toInt()
        if (remain <= 0) return 0
        val ch = channels
        val n = min(remain, outBuf.size / ch)
        val startIdx = (emitted - outStart).toInt() * ch
        for (i in 0 until n * ch) outBuf[i] = out[startIdx + i]
        emitted += n
        dropOutBefore(emitted)
        return n
    }

    private fun appendInput(buf: FloatArray, frames: Int) {
        val ch = channels
        val need = histLen + frames
        if (need > hist[0].size) {
            val newSize = max(need, hist[0].size * 2)
            for (c in 0 until ch) hist[c] = hist[c].copyOf(newSize)
        }
        for (c in 0 until ch) {
            val h = hist[c]
            for (f in 0 until frames) h[histLen + f] = buf[f * ch + c]
        }
        histLen += frames
    }

    /** 窓を確定できるだけ確定し、本体を out へ積む。final のときは末尾を 0 で埋めて最後まで作る */
    private fun produce(final: Boolean) {
        while (true) {
            val k = nextBody
            val bodyStart = k * BODY
            if (final) {
                if (bodyStart >= totalFed) return
            } else {
                // 窓 k の確定には (k+1)*BODY + MARGIN までの入力が要る
                if (totalFed < (k + 1) * BODY + MARGIN) return
            }
            produceWindow(k)
            nextBody++
            // 次の窓より前の履歴は捨てる (履歴が尽きている場合は尽きた分だけ)
            val newStart = nextBody * BODY - MARGIN
            val drop = min((newStart - histStart).toInt(), histLen)
            if (drop > 0) {
                for (c in 0 until channels) {
                    val h = hist[c]
                    h.copyInto(h, 0, drop, histLen)
                }
                histLen -= drop
                histStart += drop
            }
        }
    }

    private fun produceWindow(k: Long) {
        val w = k * BODY - MARGIN // 窓の先頭の絶対位置
        val ch = channels
        for (c in 0 until ch) {
            // 窓を集める (窓の index j ↔ 絶対位置 w+j。[0, totalFed) の外は 0)
            val win = winIn[c]
            val off = (w - histStart).toInt() // 窓先頭の履歴 index (最初の窓だけ負になる)
            val j0 = max(0, -off)
            val j1 = min(FFT_SIZE, histLen - off)
            win.fill(0f)
            if (j1 > j0) hist[c].copyInto(win, j0, off + j0, off + j1)
            // ブレンド用に原音の本体を取っておく
            win.copyInto(rawBody[c], 0, MARGIN, MARGIN + BODY)
            exciter.process(win, winOut[c], params.harmonicIntensity, params.harmonicsCap, w)
            val src = if (params.usePsycho) {
                psycho.process(winOut[c], winIn[c], params.enhancementStrength)
                winIn[c]
            } else winOut[c]
            src.copyInto(bodyBuf[c], 0, MARGIN, MARGIN + BODY)
            dynamics[c].process(bodyBuf[c], 0, BODY)
        }
        if (ch == 2) {
            val width = params.stereoWidth.coerceIn(1.0f, 1.8f)
            val l = bodyBuf[0]
            val r = bodyBuf[1]
            for (i in 0 until BODY) {
                val mid = (l[i] + r[i]) / 2.0f
                val side = (l[i] - r[i]) / 2.0f * width
                l[i] = mid + side
                r[i] = mid - side
            }
        }
        val blend = params.enhancementStrength.coerceIn(0.1, 0.8).toFloat()
        for (c in 0 until ch) {
            val oc = bodyBuf[c]
            val xc = rawBody[c]
            if (peakOf(oc, BODY) < 1e-10f) {
                xc.copyInto(oc)
            } else {
                for (i in 0 until BODY) oc[i] = xc[i] * (1f - blend) + oc[i] * blend
            }
            sanitize(oc, BODY)
        }
        // out へチャンネル交互で積む
        val need = outLen + BODY
        if (need * ch > out.size) out = out.copyOf(max(need * ch, out.size * 2))
        for (i in 0 until BODY) {
            val base = (outLen + i) * ch
            for (c in 0 until ch) out[base + c] = bodyBuf[c][i]
        }
        outLen += BODY
    }

    private fun dropOutBefore(pos: Long) {
        val drop = (pos - outStart).toInt()
        if (drop <= 0) return
        val d = min(drop, outLen)
        out.copyInto(out, 0, d * channels, outLen * channels)
        outLen -= d
        outStart += d
    }
}
