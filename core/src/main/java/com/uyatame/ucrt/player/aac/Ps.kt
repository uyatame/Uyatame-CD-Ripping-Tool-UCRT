package com.uyatame.ucrt.player.aac

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PS(パラメトリックステレオ。HE-AAC v2)。モノラルの SBR の QMF の値から、ステレオを作る。
 * ISO/IEC 14496-3 8.6.4 のとおりで、計算の順番と細かいところは FFmpeg(libavcodec/aacps*.c。LGPL 2.1 以降)の
 * 浮動小数点の版と同じにしてある(FFmpeg のデコーダーと同じ音になる)
 */
internal class Ps {
    // ---- ビットストリームの値 ----
    /** PS のデータを読んだことがあるか(無ければ、右は左と同じにする) */
    var started = false
        private set
    private var enableIid = false
    private var iidQuant = 0
    private var nrIidPar = 0
    private var nrIpdopdPar = 0
    private var enableIcc = false
    private var iccMode = 0
    private var nrIccPar = 0
    private var enableExt = false
    private var frameClass = 0
    private var numEnvOld = 0
    private var numEnv = 0
    private var enableIpdopd = false
    private val border = IntArray(MAX_ENV + 1)
    private val iidPar = Array(MAX_ENV) { IntArray(34) }
    private val iccPar = Array(MAX_ENV) { IntArray(34) }
    private val ipdPar = Array(MAX_ENV) { IntArray(34) }
    private val opdPar = Array(MAX_ENV) { IntArray(34) }
    private var is34 = false
    private var is34Old = false

    // ---- 状態 ----
    /** in_buf[5][44][2] */
    private val inBuf = FloatArray(5 * 44 * 2)
    /** delay[91][46][2] */
    private val delay = FloatArray(91 * DL * 2)
    /** ap_delay[50][3][37][2] */
    private val apDelay = FloatArray(50 * 3 * APL * 2)
    private val peakDecayNrg = FloatArray(34)
    private val powerSmooth = FloatArray(34)
    private val peakDecayDiffSmooth = FloatArray(34)
    /** H[2 実部・虚部][6 包絡][34] */
    private val h11 = Array(2) { Array(MAX_ENV + 1) { FloatArray(34) } }
    private val h12 = Array(2) { Array(MAX_ENV + 1) { FloatArray(34) } }
    private val h21 = Array(2) { Array(MAX_ENV + 1) { FloatArray(34) } }
    private val h22 = Array(2) { Array(MAX_ENV + 1) { FloatArray(34) } }
    /** Lbuf・Rbuf[91][32][2] */
    private val lBuf = FloatArray(91 * 64)
    private val rBuf = FloatArray(91 * 64)
    private val opdHist = IntArray(34)
    private val ipdHist = IntArray(34)

    // =====================================================================
    // ビットストリーム
    // =====================================================================

    private fun huff(b: BitReader, t: Int): Int = HUFF[t].decode(b) + PsTables.HUFF_OFFSET[t]

    /** 値を int8 として持つ(FFmpeg と同じく、はみ出した値は折り返す) */
    private fun s8(v: Int): Int = v.toByte().toInt()

    /** iid・icc・ipd・opd の値を 1 つの包絡ぶん読む。壊れていれば false */
    private fun readPar(b: BitReader, par: Array<IntArray>, num: Int, table: Int, e: Int, dt: Boolean, mask: Int, kind: Int): Boolean {
        if (dt) {
            val ePrev = maxOf(if (e != 0) e - 1 else numEnvOld - 1, 0)
            for (i in 0 until num) {
                var v = par[ePrev][i] + huff(b, table)
                if (mask != 0) v = v and mask
                par[e][i] = s8(v)
                if (!ok(par[e][i], kind)) return false
            }
        } else {
            var v = 0
            for (i in 0 until num) {
                v += huff(b, table)
                if (mask != 0) v = v and mask
                par[e][i] = s8(v)
                if (!ok(par[e][i], kind)) return false
            }
        }
        return true
    }

    private fun ok(v: Int, kind: Int): Boolean = when (kind) {
        K_IID -> Math.abs(v) <= 7 + 8 * iidQuant
        K_ICC -> v in 0..7
        else -> true
    }

    private fun readExtension(b: BitReader, id: Int): Int {
        val start = b.pos
        if (id != 0) return 0
        enableIpdopd = b.bit() == 1
        if (enableIpdopd) {
            for (e in 0 until numEnv) {
                var dt = b.bit() == 1
                readPar(b, ipdPar, nrIpdopdPar, if (dt) H_IPD_DT else H_IPD_DF, e, dt, 7, K_IPD)
                dt = b.bit() == 1
                readPar(b, opdPar, nrIpdopdPar, if (dt) H_OPD_DT else H_OPD_DF, e, dt, 7, K_IPD)
            }
        }
        b.skip(1)
        return b.pos - start
    }

    /** PS のデータ(bits ビットまで)を読む。読んだビットの数を返す */
    fun read(b: BitReader, bits: Int): Int {
        val start = b.pos
        val okRead = try {
            readData(b)
        } catch (e: AacException) {
            false
        }
        val consumed = b.pos - start
        if (okRead && consumed <= bits) return consumed
        // 壊れている: 読んだ値を捨てて、bits ビット飛ばす
        started = false
        for (e in 0 until MAX_ENV) { iidPar[e].fill(0); iccPar[e].fill(0); ipdPar[e].fill(0); opdPar[e].fill(0) }
        b.pos = start + bits
        return bits
    }

    private fun readData(b: BitReader): Boolean {
        val header = b.bit() == 1
        if (header) {
            enableIid = b.bit() == 1
            if (enableIid) {
                val mode = b.bits(3)
                if (mode > 5) return false
                nrIidPar = NR_IIDICC[mode]
                iidQuant = if (mode > 2) 1 else 0
                nrIpdopdPar = NR_IPDOPD[mode]
            }
            enableIcc = b.bit() == 1
            if (enableIcc) {
                iccMode = b.bits(3)
                if (iccMode > 5) return false
                nrIccPar = NR_IIDICC[iccMode]
            }
            enableExt = b.bit() == 1
        }
        frameClass = b.bit()
        numEnvOld = numEnv
        numEnv = NUM_ENV[frameClass][b.bits(2)]
        border[0] = -1
        if (frameClass != 0) {
            for (e in 1..numEnv) {
                border[e] = b.bits(5)
                if (border[e] < border[e - 1]) return false
            }
        } else {
            for (e in 1..numEnv) border[e] = (e * 32 shr LOG2[numEnv]) - 1
        }
        if (enableIid) {
            for (e in 0 until numEnv) {
                val dt = b.bit() == 1
                if (!readPar(b, iidPar, nrIidPar, HUFF_IID[2 * (if (dt) 1 else 0) + iidQuant], e, dt, 0, K_IID)) return false
            }
        } else {
            for (e in 0 until MAX_ENV) iidPar[e].fill(0)
        }
        if (enableIcc) {
            for (e in 0 until numEnv) {
                val dt = b.bit() == 1
                if (!readPar(b, iccPar, nrIccPar, if (dt) H_ICC_DT else H_ICC_DF, e, dt, 0, K_ICC)) return false
            }
        } else {
            for (e in 0 until MAX_ENV) iccPar[e].fill(0)
        }
        if (enableExt) {
            var cnt = b.bits(4)
            if (cnt == 15) cnt += b.bits(8)
            cnt *= 8
            while (cnt > 7) {
                val id = b.bits(2)
                cnt -= 2 + readExtension(b, id)
            }
            if (cnt < 0) return false
            b.skip(cnt)
        }
        // 包絡が最後まで届いていなければ、仮の包絡を足す
        if (numEnv == 0 || border[numEnv] < 31) {
            val source = if (numEnv != 0) numEnv - 1 else numEnvOld - 1
            if (source >= 0 && source != numEnv) {
                if (enableIid) System.arraycopy(iidPar[source], 0, iidPar[numEnv], 0, 34)
                if (enableIcc) System.arraycopy(iccPar[source], 0, iccPar[numEnv], 0, 34)
                if (enableIpdopd) {
                    System.arraycopy(ipdPar[source], 0, ipdPar[numEnv], 0, 34)
                    System.arraycopy(opdPar[source], 0, opdPar[numEnv], 0, 34)
                }
            }
            if (enableIid) for (i in 0 until nrIidPar) if (Math.abs(iidPar[numEnv][i]) > 7 + 8 * iidQuant) return false
            if (enableIcc) for (i in 0 until nrIidPar) if (iccPar[numEnv][i] !in 0..7) return false
            numEnv++
            border[numEnv] = 31
        }
        is34Old = is34
        if (enableIid || enableIcc) is34 = (enableIid && nrIidPar == 34) || (enableIcc && nrIccPar == 34)
        if (!enableIpdopd) {
            for (e in 0 until MAX_ENV) { ipdPar[e].fill(0); opdPar[e].fill(0) }
        }
        if (header) started = true
        return true
    }

    // =====================================================================
    // 合成
    // =====================================================================

    /** hybrid_analysis(dsp): out[i * stride] = Σ filter × in(13 サンプル) */
    private fun hybridAnalysisDsp(out: FloatArray, outOff: Int, stride: Int, inp: FloatArray, inOff: Int, filter: FloatArray, n: Int) {
        for (j in 0 until 6) {
            val a = inOff + 2 * j
            val c = inOff + 2 * (12 - j)
            re0[j] = inp[a] + inp[c]
            re1[j] = inp[a + 1] - inp[c + 1]
            im0[j] = inp[a + 1] + inp[c + 1]
            im1[j] = inp[a] - inp[c]
        }
        for (i in 0 until n) {
            val fo = i * 16
            var sr = filter[fo + 12] * inp[inOff + 12]
            var si = filter[fo + 12] * inp[inOff + 13]
            for (j in 0 until 6) {
                sr += filter[fo + 2 * j] * re0[j] - filter[fo + 2 * j + 1] * re1[j]
                si += filter[fo + 2 * j] * im0[j] + filter[fo + 2 * j + 1] * im1[j]
            }
            out[outOff + i * stride * 2] = sr
            out[outOff + i * stride * 2 + 1] = si
        }
    }

    private val re0 = FloatArray(6)
    private val re1 = FloatArray(6)
    private val im0 = FloatArray(6)
    private val im1 = FloatArray(6)
    private val temp = FloatArray(16)

    /** 帯域 band の in_buf の位置(サンプル i から) */
    private fun ib(band: Int, i: Int) = (band * 44 + i) * 2

    /** out の [sub][slot] の位置 */
    private fun ob(sub: Int, slot: Int) = (sub * 32 + slot) * 2

    private fun hybridAnalysis(out: FloatArray, l: FloatArray, len: Int) {
        for (i in 0 until 5) for (j in 0 until 38) {
            inBuf[ib(i, j + 6)] = l[j * 64 + i]
            inBuf[ib(i, j + 6) + 1] = l[(38 + j) * 64 + i]
        }
        if (is34) {
            for (i in 0 until len) hybridAnalysisDsp(out, ob(0, i), 32, inBuf, ib(0, i), F34_0_12, 12)
            for (i in 0 until len) hybridAnalysisDsp(out, ob(12, i), 32, inBuf, ib(1, i), F34_1_8, 8)
            for (i in 0 until len) hybridAnalysisDsp(out, ob(20, i), 32, inBuf, ib(2, i), F34_2_4, 4)
            for (i in 0 until len) hybridAnalysisDsp(out, ob(24, i), 32, inBuf, ib(3, i), F34_2_4, 4)
            for (i in 0 until len) hybridAnalysisDsp(out, ob(28, i), 32, inBuf, ib(4, i), F34_2_4, 4)
            ileave(out, 27, l, 5, len)
        } else {
            // hybrid6_cx
            for (i in 0 until len) {
                hybridAnalysisDsp(temp, 0, 1, inBuf, ib(0, i), F20_0_8, 8)
                out[ob(0, i)] = temp[12]; out[ob(0, i) + 1] = temp[13]
                out[ob(1, i)] = temp[14]; out[ob(1, i) + 1] = temp[15]
                out[ob(2, i)] = temp[0]; out[ob(2, i) + 1] = temp[1]
                out[ob(3, i)] = temp[2]; out[ob(3, i) + 1] = temp[3]
                out[ob(4, i)] = temp[4] + temp[10]; out[ob(4, i) + 1] = temp[5] + temp[11]
                out[ob(5, i)] = temp[6] + temp[8]; out[ob(5, i) + 1] = temp[7] + temp[9]
            }
            hybrid2Re(1, out, 6, len, true)
            hybrid2Re(2, out, 8, len, false)
            ileave(out, 7, l, 3, len)
        }
        // in_buf を進める
        for (i in 0 until 5) System.arraycopy(inBuf, ib(i, 32), inBuf, ib(i, 0), 12)
    }

    /** 1 つの帯域を、対称な実数のフィルターで 2 つに分ける */
    private fun hybrid2Re(band: Int, out: FloatArray, sub: Int, len: Int, reverse: Boolean) {
        val f = G1_Q2
        for (i in 0 until len) {
            val o = ib(band, i)
            val reIn = f[6] * inBuf[o + 12]
            val imIn = f[6] * inBuf[o + 13]
            var reOp = 0f
            var imOp = 0f
            var j = 0
            while (j < 6) {
                reOp += f[j + 1] * (inBuf[o + 2 * (j + 1)] + inBuf[o + 2 * (12 - j - 1)])
                imOp += f[j + 1] * (inBuf[o + 2 * (j + 1) + 1] + inBuf[o + 2 * (12 - j - 1) + 1])
                j += 2
            }
            val a = if (reverse) sub + 1 else sub
            val c = if (reverse) sub else sub + 1
            out[ob(a, i)] = reIn + reOp
            out[ob(a, i) + 1] = imIn + imOp
            out[ob(c, i)] = reIn - reOp
            out[ob(c, i) + 1] = imIn - imOp
        }
    }

    /** QMF の帯域 from〜63 を、そのまま out の sub + i に写す(FFmpeg の hybrid_analysis_ileave) */
    private fun ileave(out: FloatArray, sub: Int, l: FloatArray, from: Int, len: Int) {
        for (i in from until 64) for (j in 0 until len) {
            out[ob(sub + i, j)] = l[j * 64 + i]
            out[ob(sub + i, j) + 1] = l[(38 + j) * 64 + i]
        }
    }

    private fun hybridSynthesis(out: FloatArray, inp: FloatArray, len: Int) {
        if (is34) {
            for (n in 0 until len) {
                for (c in 0 until 5) { out[n * 64 + c] = 0f; out[(38 + n) * 64 + c] = 0f }
                for (i in 0 until 12) { out[n * 64] += inp[ob(i, n)]; out[(38 + n) * 64] += inp[ob(i, n) + 1] }
                for (i in 0 until 8) { out[n * 64 + 1] += inp[ob(12 + i, n)]; out[(38 + n) * 64 + 1] += inp[ob(12 + i, n) + 1] }
                for (i in 0 until 4) {
                    out[n * 64 + 2] += inp[ob(20 + i, n)]; out[(38 + n) * 64 + 2] += inp[ob(20 + i, n) + 1]
                    out[n * 64 + 3] += inp[ob(24 + i, n)]; out[(38 + n) * 64 + 3] += inp[ob(24 + i, n) + 1]
                    out[n * 64 + 4] += inp[ob(28 + i, n)]; out[(38 + n) * 64 + 4] += inp[ob(28 + i, n) + 1]
                }
            }
            deint(out, inp, 27, 5, len)
        } else {
            for (n in 0 until len) {
                out[n * 64] = inp[ob(0, n)] + inp[ob(1, n)] + inp[ob(2, n)] + inp[ob(3, n)] + inp[ob(4, n)] + inp[ob(5, n)]
                out[(38 + n) * 64] = inp[ob(0, n) + 1] + inp[ob(1, n) + 1] + inp[ob(2, n) + 1] +
                    inp[ob(3, n) + 1] + inp[ob(4, n) + 1] + inp[ob(5, n) + 1]
                out[n * 64 + 1] = inp[ob(6, n)] + inp[ob(7, n)]
                out[(38 + n) * 64 + 1] = inp[ob(6, n) + 1] + inp[ob(7, n) + 1]
                out[n * 64 + 2] = inp[ob(8, n)] + inp[ob(9, n)]
                out[(38 + n) * 64 + 2] = inp[ob(8, n) + 1] + inp[ob(9, n) + 1]
            }
            deint(out, inp, 7, 3, len)
        }
    }

    private fun deint(out: FloatArray, inp: FloatArray, sub: Int, from: Int, len: Int) {
        for (i in from until 64) for (n in 0 until len) {
            out[n * 64 + i] = inp[ob(sub + i, n)]
            out[(38 + n) * 64 + i] = inp[ob(sub + i, n) + 1]
        }
    }

    private val power = Array(34) { FloatArray(32) }
    private val transientGain = Array(34) { FloatArray(32) }

    private fun dl(k: Int, n: Int) = (k * DL + n) * 2
    private fun ap(k: Int, m: Int, n: Int) = ((k * 3 + m) * APL + n) * 2

    private fun decorrelation(out: FloatArray, s: FloatArray) {
        val bands = NR_BANDS[if (is34) 1 else 0]
        val kToI = if (is34) PsTables.K_TO_I_34 else PsTables.K_TO_I_20
        for (p in power) p.fill(0f)
        if (is34 != is34Old) {
            peakDecayNrg.fill(0f)
            powerSmooth.fill(0f)
            peakDecayDiffSmooth.fill(0f)
            delay.fill(0f)
            apDelay.fill(0f)
        }
        for (k in 0 until bands) {
            val pw = power[kToI[k]]
            for (n in 0 until 32) {
                val o = ob(k, n)
                pw[n] += s[o] * s[o] + s[o + 1] * s[o + 1]
            }
        }
        // 過渡の検出
        val parBands = NR_PAR_BANDS[if (is34) 1 else 0]
        for (i in 0 until parBands) for (n in 0 until 32) {
            val decayed = PEAK_DECAY * peakDecayNrg[i]
            peakDecayNrg[i] = maxOf(decayed, power[i][n])
            powerSmooth[i] += A_SMOOTH * (power[i][n] - powerSmooth[i])
            peakDecayDiffSmooth[i] += A_SMOOTH * (peakDecayNrg[i] - power[i][n] - peakDecayDiffSmooth[i])
            val denom = TRANSIENT_IMPACT * peakDecayDiffSmooth[i]
            transientGain[i][n] = if (denom > powerSmooth[i]) powerSmooth[i] / denom else 1.0f
        }
        val idx = if (is34) 1 else 0
        val allpass = NR_ALLPASS[idx]
        var k = 0
        while (k < allpass) {
            val b = kToI[k]
            val g = (1f - DECAY_SLOPE * (k - DECAY_CUTOFF[idx])).coerceIn(0f, 1f)
            shiftDelay(k, s)
            for (m in 0 until 3) System.arraycopy(apDelay, ap(k, m, 32), apDelay, ap(k, m, 0), 10)
            decorrelate(out, k, g, transientGain[b], idx)
            k++
        }
        while (k < SHORT_DELAY[idx]) {
            val tg = transientGain[kToI[k]]
            shiftDelay(k, s)
            for (n in 0 until 32) {
                val d = dl(k, n + 14 - 14)
                out[ob(k, n)] = delay[d] * tg[n]
                out[ob(k, n) + 1] = delay[d + 1] * tg[n]
            }
            k++
        }
        while (k < bands) {
            val tg = transientGain[kToI[k]]
            shiftDelay(k, s)
            for (n in 0 until 32) {
                val d = dl(k, n + 14 - 1)
                out[ob(k, n)] = delay[d] * tg[n]
                out[ob(k, n) + 1] = delay[d + 1] * tg[n]
            }
            k++
        }
    }

    private fun shiftDelay(k: Int, s: FloatArray) {
        System.arraycopy(delay, dl(k, 32), delay, dl(k, 0), 14 * 2)
        System.arraycopy(s, ob(k, 0), delay, dl(k, 14), 64)
    }

    private val ag = FloatArray(3)

    private fun decorrelate(out: FloatArray, k: Int, gDecay: Float, tg: FloatArray, idx: Int) {
        for (m in 0 until 3) ag[m] = AP_A[m] * gDecay
        val pf = PHI_FRACT[idx]
        val pr = pf[2 * k]
        val pi = pf[2 * k + 1]
        val qf = Q_FRACT[idx]
        for (n in 0 until 32) {
            val d = dl(k, 14 - 2 + n)
            var inRe = delay[d] * pr - delay[d + 1] * pi
            var inIm = delay[d] * pi + delay[d + 1] * pr
            for (m in 0 until 3) {
                val aRe = ag[m] * inRe
                val aIm = ag[m] * inIm
                val lo = ap(k, m, n + 2 - m)
                val ldRe = apDelay[lo]
                val ldIm = apDelay[lo + 1]
                val fdRe = qf[(k * 3 + m) * 2]
                val fdIm = qf[(k * 3 + m) * 2 + 1]
                val apdRe = inRe
                val apdIm = inIm
                inRe = ldRe * fdRe - ldIm * fdIm
                inRe -= aRe
                inIm = ldRe * fdIm + ldIm * fdRe
                inIm -= aIm
                val so = ap(k, m, n + 5)
                apDelay[so] = apdRe + ag[m] * inRe
                apDelay[so + 1] = apdIm + ag[m] * inIm
            }
            out[ob(k, n)] = tg[n] * inRe
            out[ob(k, n) + 1] = tg[n] * inIm
        }
    }

    // ---- 値の並べ替え(10・20・34 帯域) ----

    private fun mapIdx10To20(o: IntArray, p: IntArray, full: Boolean) {
        var b: Int
        if (full) b = 9 else { b = 4; o[10] = 0 }
        while (b >= 0) { o[2 * b + 1] = p[b]; o[2 * b] = p[b]; b-- }
    }

    private fun mapIdx34To20(o: IntArray, p: IntArray, full: Boolean) {
        o[0] = (2 * p[0] + p[1]) / 3
        o[1] = (p[1] + 2 * p[2]) / 3
        o[2] = (2 * p[3] + p[4]) / 3
        o[3] = (p[4] + 2 * p[5]) / 3
        o[4] = (p[6] + p[7]) / 2
        o[5] = (p[8] + p[9]) / 2
        o[6] = p[10]
        o[7] = p[11]
        o[8] = (p[12] + p[13]) / 2
        o[9] = (p[14] + p[15]) / 2
        o[10] = p[16]
        if (full) {
            o[11] = p[17]
            o[12] = p[18]
            o[13] = p[19]
            o[14] = (p[20] + p[21]) / 2
            o[15] = (p[22] + p[23]) / 2
            o[16] = (p[24] + p[25]) / 2
            o[17] = (p[26] + p[27]) / 2
            o[18] = (p[28] + p[29] + p[30] + p[31]) / 4
            o[19] = (p[32] + p[33]) / 2
        }
    }

    private fun mapIdx10To34(o: IntArray, p: IntArray, full: Boolean) {
        if (full) {
            for (i in 28..33) o[i] = p[9]
            for (i in 24..27) o[i] = p[8]
            for (i in 20..23) o[i] = p[7]
            o[19] = p[6]; o[18] = p[6]
            o[17] = p[5]; o[16] = p[5]
        } else {
            o[16] = 0
        }
        for (i in 12..15) o[i] = p[4]
        o[11] = p[3]; o[10] = p[3]
        for (i in 6..9) o[i] = p[2]
        for (i in 3..5) o[i] = p[1]
        for (i in 0..2) o[i] = p[0]
    }

    private fun mapIdx20To34(o: IntArray, p: IntArray, full: Boolean) {
        if (full) {
            o[33] = p[19]; o[32] = p[19]
            o[31] = p[18]; o[30] = p[18]; o[29] = p[18]; o[28] = p[18]
            o[27] = p[17]; o[26] = p[17]
            o[25] = p[16]; o[24] = p[16]
            o[23] = p[15]; o[22] = p[15]
            o[21] = p[14]; o[20] = p[14]
            o[19] = p[13]
            o[18] = p[12]
            o[17] = p[11]
        }
        o[16] = p[10]
        o[15] = p[9]; o[14] = p[9]
        o[13] = p[8]; o[12] = p[8]
        o[11] = p[7]
        o[10] = p[6]
        o[9] = p[5]; o[8] = p[5]
        o[7] = p[4]; o[6] = p[4]
        o[5] = p[3]
        o[4] = (p[2] + p[3]) / 2
        o[3] = p[2]
        o[2] = p[1]
        o[1] = (p[0] + p[1]) / 2
        o[0] = p[0]
    }

    private fun mapVal34To20(p: FloatArray) {
        p[0] = (2 * p[0] + p[1]) * 0.33333333f
        p[1] = (p[1] + 2 * p[2]) * 0.33333333f
        p[2] = (2 * p[3] + p[4]) * 0.33333333f
        p[3] = (p[4] + 2 * p[5]) * 0.33333333f
        p[4] = (p[6] + p[7]) * 0.5f
        p[5] = (p[8] + p[9]) * 0.5f
        p[6] = p[10]
        p[7] = p[11]
        p[8] = (p[12] + p[13]) * 0.5f
        p[9] = (p[14] + p[15]) * 0.5f
        p[10] = p[16]
        p[11] = p[17]
        p[12] = p[18]
        p[13] = p[19]
        p[14] = (p[20] + p[21]) * 0.5f
        p[15] = (p[22] + p[23]) * 0.5f
        p[16] = (p[24] + p[25]) * 0.5f
        p[17] = (p[26] + p[27]) * 0.5f
        p[18] = (p[28] + p[29] + p[30] + p[31]) * 0.25f
        p[19] = (p[32] + p[33]) * 0.5f
    }

    private fun mapVal20To34(p: FloatArray) {
        p[33] = p[19]; p[32] = p[19]
        p[31] = p[18]; p[30] = p[18]; p[29] = p[18]; p[28] = p[18]
        p[27] = p[17]; p[26] = p[17]
        p[25] = p[16]; p[24] = p[16]
        p[23] = p[15]; p[22] = p[15]
        p[21] = p[14]; p[20] = p[14]
        p[19] = p[13]
        p[18] = p[12]
        p[17] = p[11]
        p[16] = p[10]
        p[15] = p[9]; p[14] = p[9]
        p[13] = p[8]; p[12] = p[8]
        p[11] = p[7]
        p[10] = p[6]
        p[9] = p[5]; p[8] = p[5]
        p[7] = p[4]; p[6] = p[4]
        p[5] = p[3]
        p[4] = (p[2] + p[3]) * 0.5f
        p[3] = p[2]
        p[2] = p[1]
        p[1] = (p[0] + p[1]) * 0.5f
    }

    private val iidMappedBuf = Array(MAX_ENV) { IntArray(34) }
    private val iccMappedBuf = Array(MAX_ENV) { IntArray(34) }
    private val ipdMappedBuf = Array(MAX_ENV) { IntArray(34) }
    private val opdMappedBuf = Array(MAX_ENV) { IntArray(34) }

    private fun remap(buf: Array<IntArray>, par: Array<IntArray>, numPar: Int, full: Boolean, to34: Boolean): Array<IntArray> {
        if (to34) {
            when (numPar) {
                20, 11 -> { for (e in 0 until numEnv) mapIdx20To34(buf[e], par[e], full); return buf }
                10, 5 -> { for (e in 0 until numEnv) mapIdx10To34(buf[e], par[e], full); return buf }
            }
        } else {
            when (numPar) {
                34, 17 -> { for (e in 0 until numEnv) mapIdx34To20(buf[e], par[e], full); return buf }
                10, 5 -> { for (e in 0 until numEnv) mapIdx10To20(buf[e], par[e], full); return buf }
            }
        }
        return par
    }

    private val hh = FloatArray(8)
    private val hs = FloatArray(8)

    private fun stereoProcessing(l: FloatArray, r: FloatArray) {
        val kToI = if (is34) PsTables.K_TO_I_34 else PsTables.K_TO_I_20
        val lut = if (iccMode < 3) HA else HB
        if (numEnvOld != 0) {
            for (h in arrayOf(h11, h12, h21, h22)) for (c in 0..1) System.arraycopy(h[c][numEnvOld], 0, h[c][0], 0, 34)
        }
        val iidM: Array<IntArray>
        val iccM: Array<IntArray>
        var ipdM = ipdMappedBuf
        var opdM = opdMappedBuf
        if (is34) {
            iidM = remap(iidMappedBuf, iidPar, nrIidPar, true, true)
            iccM = remap(iccMappedBuf, iccPar, nrIccPar, true, true)
            if (enableIpdopd) {
                ipdM = remap(ipdMappedBuf, ipdPar, nrIpdopdPar, false, true)
                opdM = remap(opdMappedBuf, opdPar, nrIpdopdPar, false, true)
            }
            if (!is34Old) {
                for (h in arrayOf(h11, h12, h21, h22)) for (c in 0..1) mapVal20To34(h[c][0])
                ipdHist.fill(0); opdHist.fill(0)
            }
        } else {
            iidM = remap(iidMappedBuf, iidPar, nrIidPar, true, false)
            iccM = remap(iccMappedBuf, iccPar, nrIccPar, true, false)
            if (enableIpdopd) {
                ipdM = remap(ipdMappedBuf, ipdPar, nrIpdopdPar, false, false)
                opdM = remap(opdMappedBuf, opdPar, nrIpdopdPar, false, false)
            }
            if (is34Old) {
                for (h in arrayOf(h11, h12, h21, h22)) for (c in 0..1) mapVal34To20(h[c][0])
                ipdHist.fill(0); opdHist.fill(0)
            }
        }
        val idx = if (is34) 1 else 0
        for (e in 0 until numEnv) {
            for (b in 0 until NR_PAR_BANDS[idx]) {
                val li = iidM[e][b] + 7 + 23 * iidQuant
                val ic = iccM[e][b]
                val base = (li * 8 + ic) * 4
                var v11 = lut[base]
                var v12 = lut[base + 1]
                var v21 = lut[base + 2]
                var v22 = lut[base + 3]
                if (enableIpdopd && b < NR_IPDOPD[idx + 1]) {
                    val opdIdx = opdHist[b] * 8 + opdM[e][b]
                    val ipdIdx = ipdHist[b] * 8 + ipdM[e][b]
                    val opdRe = PD_RE[opdIdx]
                    val opdIm = PD_IM[opdIdx]
                    val ipdRe = PD_RE[ipdIdx]
                    val ipdIm = PD_IM[ipdIdx]
                    opdHist[b] = opdIdx and 0x3F
                    ipdHist[b] = ipdIdx and 0x3F
                    val adjRe = opdRe * ipdRe + opdIm * ipdIm
                    val adjIm = opdIm * ipdRe - opdRe * ipdIm
                    val v11i = v11 * opdIm
                    v11 *= opdRe
                    val v12i = v12 * adjIm
                    v12 *= adjRe
                    val v21i = v21 * opdIm
                    v21 *= opdRe
                    val v22i = v22 * adjIm
                    v22 *= adjRe
                    h11[1][e + 1][b] = v11i
                    h12[1][e + 1][b] = v12i
                    h21[1][e + 1][b] = v21i
                    h22[1][e + 1][b] = v22i
                }
                h11[0][e + 1][b] = v11
                h12[0][e + 1][b] = v12
                h21[0][e + 1][b] = v21
                h22[0][e + 1][b] = v22
            }
            for (k in 0 until NR_BANDS[idx]) {
                val start = border[e]
                val stop = border[e + 1]
                val width = 1f / (if (stop - start != 0) stop - start else 1)
                val b = kToI[k]
                hh[0] = h11[0][e][b]; hh[1] = h12[0][e][b]; hh[2] = h21[0][e][b]; hh[3] = h22[0][e][b]
                if (enableIpdopd) {
                    val neg = (is34 && k in 9..13) || (!is34 && k <= 1)
                    val sgn = if (neg) -1f else 1f
                    hh[4] = sgn * h11[1][e][b]; hh[5] = sgn * h12[1][e][b]; hh[6] = sgn * h21[1][e][b]; hh[7] = sgn * h22[1][e][b]
                }
                hs[0] = (h11[0][e + 1][b] - hh[0]) * width
                hs[1] = (h12[0][e + 1][b] - hh[1]) * width
                hs[2] = (h21[0][e + 1][b] - hh[2]) * width
                hs[3] = (h22[0][e + 1][b] - hh[3]) * width
                if (enableIpdopd) {
                    hs[4] = (h11[1][e + 1][b] - hh[4]) * width
                    hs[5] = (h12[1][e + 1][b] - hh[5]) * width
                    hs[6] = (h21[1][e + 1][b] - hh[6]) * width
                    hs[7] = (h22[1][e + 1][b] - hh[7]) * width
                }
                if (stop - start != 0) interpolate(l, r, k, 1 + start, stop - start)
            }
        }
    }

    private fun interpolate(l: FloatArray, r: FloatArray, k: Int, from: Int, len: Int) {
        if (!enableIpdopd) {
            var h0 = hh[0]; var h1 = hh[1]; var h2 = hh[2]; var h3 = hh[3]
            for (n in 0 until len) {
                val o = ob(k, from + n)
                val lr = l[o]; val li = l[o + 1]; val rr = r[o]; val ri = r[o + 1]
                h0 += hs[0]; h1 += hs[1]; h2 += hs[2]; h3 += hs[3]
                l[o] = h0 * lr + h2 * rr
                l[o + 1] = h0 * li + h2 * ri
                r[o] = h1 * lr + h3 * rr
                r[o + 1] = h1 * li + h3 * ri
            }
        } else {
            var h00 = hh[0]; var h01 = hh[1]; var h02 = hh[2]; var h03 = hh[3]
            var h10 = hh[4]; var h11v = hh[5]; var h12v = hh[6]; var h13 = hh[7]
            for (n in 0 until len) {
                val o = ob(k, from + n)
                val lr = l[o]; val li = l[o + 1]; val rr = r[o]; val ri = r[o + 1]
                h00 += hs[0]; h01 += hs[1]; h02 += hs[2]; h03 += hs[3]
                h10 += hs[4]; h11v += hs[5]; h12v += hs[6]; h13 += hs[7]
                l[o] = h00 * lr + h02 * rr - h10 * li - h12v * ri
                l[o + 1] = h00 * li + h02 * ri + h10 * lr + h12v * rr
                r[o] = h01 * lr + h03 * rr - h11v * li - h13 * ri
                r[o + 1] = h01 * li + h03 * ri + h11v * lr + h13 * rr
            }
        }
    }

    /**
     * l(モノラル。SBR の X[2][38][64])から l・r を作る(どちらも上書き)。top は使う QMF の帯域の数
     */
    fun apply(l: FloatArray, r: FloatArray, topIn: Int) {
        val idx = if (is34) 1 else 0
        val top = topIn + NR_BANDS[idx] - 64
        if (top < NR_BANDS[idx]) java.util.Arrays.fill(delay, dl(maxOf(top, 0), 0), dl(NR_BANDS[idx], 0), 0f)
        if (top < NR_ALLPASS[idx]) java.util.Arrays.fill(apDelay, ap(maxOf(top, 0), 0, 0), ap(NR_ALLPASS[idx], 0, 0), 0f)
        hybridAnalysis(lBuf, l, 32)
        decorrelation(rBuf, lBuf)
        stereoProcessing(lBuf, rBuf)
        hybridSynthesis(l, lBuf, 32)
        hybridSynthesis(r, rBuf, 32)
    }

    companion object {
        private const val MAX_ENV = 5
        /** delay の 1 帯域の長さ(32 + 14) */
        private const val DL = 46
        /** ap_delay の 1 つの長さ(32 + 5) */
        private const val APL = 37

        private const val K_IID = 0
        private const val K_ICC = 1
        private const val K_IPD = 2

        private const val H_ICC_DF = 4
        private const val H_ICC_DT = 5
        private const val H_IPD_DF = 6
        private const val H_IPD_DT = 7
        private const val H_OPD_DF = 8
        private const val H_OPD_DT = 9
        /** iid の符号表: [df0, df1, dt0, dt1] */
        private val HUFF_IID = intArrayOf(2, 0, 3, 1)

        private val NUM_ENV = arrayOf(intArrayOf(0, 1, 2, 4), intArrayOf(1, 2, 3, 4))
        private val NR_IIDICC = intArrayOf(10, 20, 34, 10, 20, 34)
        /** 読むときの ipd/opd の数(モードごと)。後ろの 2 つは帯域の数(20 帯域: 11、34 帯域: 17)を兼ねる */
        private val NR_IPDOPD = intArrayOf(5, 11, 17, 5, 11, 17)
        private val LOG2 = intArrayOf(0, 0, 1, 1, 2, 2, 2, 2, 3)
        private val NR_PAR_BANDS = intArrayOf(20, 34)
        private val NR_BANDS = intArrayOf(71, 91)
        private val DECAY_CUTOFF = intArrayOf(10, 32)
        private val NR_ALLPASS = intArrayOf(30, 50)
        private val SHORT_DELAY = intArrayOf(42, 62)
        private const val DECAY_SLOPE = 0.05f
        private const val PEAK_DECAY = 0.76592833836465f
        private const val TRANSIENT_IMPACT = 1.5f
        private const val A_SMOOTH = 0.25f
        private val AP_A = floatArrayOf(0.65143905753106f, 0.56471812200776f, 0.48954165955695f)
        private val G1_Q2 = floatArrayOf(0.0f, 0.01899487526049f, 0.0f, -0.07293139167538f, 0.0f, 0.30596630545168f, 0.5f)

        private val HUFF = Array(10) { t ->
            val pairs = PsTables.HUFF[t]
            val count = pairs.size / 2
            val codes = IntArray(count)
            val lens = IntArray(count)
            var code = 0L
            for (i in 0 until count) {
                val sym = pairs[2 * i]
                val len = pairs[2 * i + 1]
                codes[sym] = (code ushr (32 - len)).toInt()
                lens[sym] = len
                code += 1L shl (32 - len)
            }
            Huffman(codes, lens)
        }

        // ---- FFmpeg の ps_tableinit と同じ計算で作る表 ----
        private val PD_RE = FloatArray(512)
        private val PD_IM = FloatArray(512)
        /** HA・HB[46][8][4] */
        private val HA = FloatArray(46 * 8 * 4)
        private val HB = FloatArray(46 * 8 * 4)
        /** フィルター [帯域][8][2] */
        private val F20_0_8 = FloatArray(8 * 16)
        private val F34_0_12 = FloatArray(12 * 16)
        private val F34_1_8 = FloatArray(8 * 16)
        private val F34_2_4 = FloatArray(4 * 16)
        /** [20 帯域・34 帯域][50][3][2] */
        private val Q_FRACT = arrayOf(FloatArray(50 * 6), FloatArray(50 * 6))
        /** [20 帯域・34 帯域][50][2] */
        private val PHI_FRACT = arrayOf(FloatArray(100), FloatArray(100))

        private fun makeFilters(f: FloatArray, proto: FloatArray, bands: Int) {
            for (q in 0 until bands) for (n in 0 until 7) {
                val theta = 2 * PI * (q + 0.5) * (n - 6) / bands
                f[q * 16 + 2 * n] = (proto[n] * cos(theta)).toFloat()
                f[q * 16 + 2 * n + 1] = (proto[n] * -sin(theta)).toFloat()
            }
        }

        init {
            val r2 = Math.sqrt(0.5).toFloat()
            val ipdSin = floatArrayOf(0f, r2, 1f, r2, 0f, -r2, -1f, -r2)
            val ipdCos = floatArrayOf(1f, r2, 0f, -r2, -1f, -r2, 0f, r2)
            for (p0 in 0 until 8) for (p1 in 0 until 8) for (p2 in 0 until 8) {
                val re = 0.25f * ipdCos[p0] + 0.5f * ipdCos[p1] + ipdCos[p2]
                val im = 0.25f * ipdSin[p0] + 0.5f * ipdSin[p1] + ipdSin[p2]
                val mag = (1 / hypot(im.toDouble(), re.toDouble())).toFloat()
                PD_RE[p0 * 64 + p1 * 8 + p2] = re * mag
                PD_IM[p0 * 64 + p1 * 8 + p2] = im * mag
            }
            val iidDequant = floatArrayOf(
                0.05623413251903f, 0.12589254117942f, 0.19952623149689f, 0.31622776601684f,
                0.44668359215096f, 0.63095734448019f, 0.79432823472428f, 1f,
                1.25892541179417f, 1.58489319246111f, 2.23872113856834f, 3.16227766016838f,
                5.01187233627272f, 7.94328234724282f, 17.7827941003892f,
                0.00316227766017f, 0.00562341325190f, 0.01f, 0.01778279410039f,
                0.03162277660168f, 0.05623413251903f, 0.07943282347243f, 0.11220184543020f,
                0.15848931924611f, 0.22387211385683f, 0.31622776601684f, 0.39810717055350f,
                0.50118723362727f, 0.63095734448019f, 0.79432823472428f, 1f,
                1.25892541179417f, 1.58489319246111f, 1.99526231496888f, 2.51188643150958f,
                3.16227766016838f, 4.46683592150963f, 6.30957344480193f, 8.91250938133745f,
                12.5892541179417f, 17.7827941003892f, 31.6227766016838f, 56.2341325190349f,
                100f, 177.827941003892f, 316.227766016837f,
            )
            val iccInvq = floatArrayOf(1f, 0.937f, 0.84118f, 0.60092f, 0.36764f, 0f, -0.589f, -1f)
            val acosIccInvq = floatArrayOf(0f, 0.35685527f, 0.57133466f, 0.92614472f, 1.1943263f, (PI / 2).toFloat(), 2.2006171f, PI.toFloat())
            val sqrt2 = Math.sqrt(2.0).toFloat()
            for (iid in 0 until 46) {
                val c = iidDequant[iid]
                val c1 = sqrt2 / sqrt(1.0f + c * c)
                val c2 = c * c1
                for (icc in 0 until 8) {
                    val o = (iid * 8 + icc) * 4
                    run {
                        val alpha = 0.5f * acosIccInvq[icc]
                        val beta = alpha * (c1 - c2) * r2
                        HA[o] = c2 * cos((beta + alpha).toDouble()).toFloat()
                        HA[o + 1] = c1 * cos((beta - alpha).toDouble()).toFloat()
                        HA[o + 2] = c2 * sin((beta + alpha).toDouble()).toFloat()
                        HA[o + 3] = c1 * sin((beta - alpha).toDouble()).toFloat()
                    }
                    run {
                        val rho = maxOf(iccInvq[icc], 0.05f)
                        var alpha = 0.5f * atan2((2.0f * c * rho).toDouble(), (c * c - 1.0f).toDouble()).toFloat()
                        var mu = c + 1.0f / c
                        mu = sqrt(1 + (4 * rho * rho - 4) / (mu * mu))
                        val gamma = atan(sqrt((1.0f - mu) / (1.0f + mu)).toDouble()).toFloat()
                        if (alpha < 0) alpha += (PI / 2).toFloat()
                        val ac = cos(alpha.toDouble()).toFloat()
                        val asn = sin(alpha.toDouble()).toFloat()
                        val gc = cos(gamma.toDouble()).toFloat()
                        val gs = sin(gamma.toDouble()).toFloat()
                        HB[o] = sqrt2 * ac * gc
                        HB[o + 1] = sqrt2 * asn * gc
                        HB[o + 2] = -sqrt2 * asn * gs
                        HB[o + 3] = sqrt2 * ac * gs
                    }
                }
            }
            val fCenter20 = intArrayOf(-3, -1, 1, 3, 5, 7, 10, 14, 18, 22)
            val fCenter34 = intArrayOf(
                2, 6, 10, 14, 18, 22, 26, 30, 34, -10, -6, -2, 51, 57, 15, 21,
                27, 33, 39, 45, 54, 66, 78, 42, 102, 66, 78, 90, 102, 114, 126, 90,
            )
            val links = floatArrayOf(0.43f, 0.75f, 0.347f)
            val gain = 0.39f
            for (k in 0 until 30) {
                val fc = if (k < fCenter20.size) fCenter20[k] * 0.125 else (k - 6.5f).toDouble()
                for (m in 0 until 3) {
                    val th = -PI * links[m] * fc
                    Q_FRACT[0][(k * 3 + m) * 2] = cos(th).toFloat()
                    Q_FRACT[0][(k * 3 + m) * 2 + 1] = sin(th).toFloat()
                }
                val th = -PI * gain * fc
                PHI_FRACT[0][2 * k] = cos(th).toFloat()
                PHI_FRACT[0][2 * k + 1] = sin(th).toFloat()
            }
            for (k in 0 until 50) {
                val fc = if (k < fCenter34.size) fCenter34[k] / 24.0 else (k - 26.5f).toDouble()
                for (m in 0 until 3) {
                    val th = -PI * links[m] * fc
                    Q_FRACT[1][(k * 3 + m) * 2] = cos(th).toFloat()
                    Q_FRACT[1][(k * 3 + m) * 2 + 1] = sin(th).toFloat()
                }
                val th = -PI * gain * fc
                PHI_FRACT[1][2 * k] = cos(th).toFloat()
                PHI_FRACT[1][2 * k + 1] = sin(th).toFloat()
            }
            makeFilters(F20_0_8, floatArrayOf(0.00746082949812f, 0.02270420949825f, 0.04546865930473f, 0.07266113929591f, 0.09885108575264f, 0.11793710567217f, 0.125f), 8)
            makeFilters(F34_0_12, floatArrayOf(0.04081179924692f, 0.03812810994926f, 0.05144908135699f, 0.06399831151592f, 0.07428313801106f, 0.08100347892914f, 0.08333333333333f), 12)
            makeFilters(F34_1_8, floatArrayOf(0.01565675600122f, 0.03752716391991f, 0.05417891378782f, 0.08417044116767f, 0.10307344158036f, 0.12222452249753f, 0.125f), 8)
            makeFilters(F34_2_4, floatArrayOf(-0.05908211155639f, -0.04871498374946f, 0.0f, 0.07778723915851f, 0.16486303567403f, 0.23279856662996f, 0.25f), 4)
        }
    }
}
