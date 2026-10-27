package com.uyatame.ucrt.player.aac

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * SBR(Spectral Band Replication。HE-AAC の高音を作る仕組み)。1 つの要素(SCE・CPE)ごとに 1 つ。
 * ISO/IEC 14496-3 4.6.18 のとおりで、計算の順番と細かいところは FFmpeg(libavcodec/aacsbr*.c・sbrdsp*.c。LGPL 2.1 以降)の
 * 浮動小数点の版と同じにしてある(FFmpeg のデコーダーと同じ音になる)。
 * 中の AAC の 1024 サンプルを、2 倍のサンプリング周波数の 2048 サンプルにする
 */
internal class Sbr(
    /** SBR のあとのサンプリング周波数(中の AAC の 2 倍) */
    private val sampleRate: Int,
    /** PS(パラメトリックステレオ)。モノラルの HE-AAC で使う。無ければ null */
    val ps: Ps?,
) {
    private class Data {
        var bsFrameClass = 0
        var bsAddHarmonicFlag = false
        var bsNumEnv = 0
        val bsFreqRes = IntArray(9)
        var bsNumNoise = 0
        val bsDfEnv = IntArray(9)
        val bsDfNoise = IntArray(2)
        val bsInvfMode = Array(2) { IntArray(5) }
        val bsAddHarmonic = IntArray(48)
        var bsAmpRes = 0

        val synth = FloatArray(SYNTH_BUF)
        var synthOffset = SYNTH_BUF - (1280 - 128)
        val analysis = FloatArray(1312)
        val eA = IntArray(2)
        val bwArray = FloatArray(5)
        /** QMF の値 W[2][32][32][2] */
        val w = FloatArray(2 * 32 * 32 * 2)
        var yPos = 0
        /** Y[2][38][64][2] */
        val y = FloatArray(2 * 38 * 64 * 2)
        val gTemp = Array(42) { FloatArray(48) }
        val qTemp = Array(42) { FloatArray(48) }
        val sIndexMapped = Array(9) { IntArray(48) }
        val envFacsQ = Array(9) { IntArray(48) }
        val envFacs = Array(9) { FloatArray(48) }
        val noiseFacsQ = Array(3) { IntArray(5) }
        val noiseFacs = Array(3) { FloatArray(5) }
        val tEnv = IntArray(9)
        var tEnvNumEnvOld = 0
        val tQ = IntArray(3)
        var fIndexNoise = 0
        var fIndexSine = 0
    }

    // ---- 見出し ----
    private var start = false
    private var readyForDequant = false
    private var reset = false
    private var idAac = -1
    /** spectrum parameters: start_freq, stop_freq, xover_band, freq_scale, alter_scale, noise_bands(最初は -1) */
    private val spec = IntArray(6) { -1 }
    private var bsAmpResHeader = 0
    private var bsLimiterBands = 0
    private var bsLimiterGains = 0
    private var bsInterpolFreq = 0
    private var bsSmoothingMode = 0
    private var bsCoupling = 0

    private val k = IntArray(5)
    private val kx = IntArray(2)
    private val m = IntArray(2)
    private var kxAndMPushed = false
    private var nMaster = 0
    private val data = arrayOf(Data(), Data())
    private val n = IntArray(2)
    private var nQ = 0
    private var nLim = 0
    private val fMaster = IntArray(49)
    private val fTableLow = IntArray(25)
    private val fTableHigh = IntArray(49)
    private val fTableNoise = IntArray(6)
    private val fTableLim = IntArray(30)
    private var numPatches = 0
    private val patchNumSubbands = IntArray(6)
    private val patchStartSubband = IntArray(6)

    /** X_low[32][40][2] */
    private val xLow = FloatArray(32 * 40 * 2)
    /** X_high[64][40][2] */
    private val xHigh = FloatArray(64 * 40 * 2)
    /** X[2 ch][2 実部・虚部][38][64] */
    val x = Array(2) { FloatArray(2 * 38 * 64) }
    private val alpha0 = FloatArray(64 * 2)
    private val alpha1 = FloatArray(64 * 2)
    private val eOrigMapped = Array(8) { FloatArray(48) }
    private val qMapped = Array(8) { FloatArray(48) }
    private val sMapped = Array(8) { IntArray(48) }
    private val eCurr = Array(8) { FloatArray(48) }
    private val qM = Array(8) { FloatArray(48) }
    private val sM = Array(8) { FloatArray(48) }
    private val gain = Array(8) { FloatArray(48) }
    private val z = FloatArray(5 * 64)
    private val mdctBuf = Array(2) { FloatArray(64) }

    init {
        kx[0] = kx[1]
        turnOff()
    }

    /** SBR を止めて、2 倍に広げるだけにする */
    private fun turnOff() {
        start = false
        readyForDequant = false
        kx[1] = 32
        m[1] = 0
        data[0].eA[1] = -1
        data[1].eA[1] = -1
        spec.fill(-1)
    }

    // =====================================================================
    // 周波数の表
    // =====================================================================

    private fun makeTableLim() {
        if (bsLimiterBands > 0) {
            val warped = floatArrayOf(1.32715174233856803909f, 1.18509277094158210129f, 1.11987160404675912501f)[bsLimiterBands - 1]
            val borders = IntArray(7)
            borders[0] = kx[1]
            for (i in 1..numPatches) borders[i] = borders[i - 1] + patchNumSubbands[i - 1]
            System.arraycopy(fTableLow, 0, fTableLim, 0, n[0] + 1)
            if (numPatches > 1) System.arraycopy(borders, 1, fTableLim, n[0] + 1, numPatches - 1)
            java.util.Arrays.sort(fTableLim, 0, numPatches + n[0])
            nLim = n[0] + numPatches - 1
            var inp = 1
            var out = 0
            while (out < nLim) {
                if (fTableLim[inp] >= fTableLim[out] * warped) {
                    fTableLim[++out] = fTableLim[inp++]
                } else if (fTableLim[inp] == fTableLim[out] || !inTable(borders, numPatches, fTableLim[inp])) {
                    inp++
                    nLim--
                } else if (!inTable(borders, numPatches, fTableLim[out])) {
                    fTableLim[out] = fTableLim[inp++]
                    nLim--
                } else {
                    fTableLim[++out] = fTableLim[inp++]
                }
            }
        } else {
            fTableLim[0] = fTableLow[0]
            fTableLim[1] = fTableLow[n[0]]
            nLim = 1
        }
    }

    private fun inTable(t: IntArray, last: Int, v: Int): Boolean {
        for (i in 0..last) if (t[i] == v) return true
        return false
    }

    private fun makeBands(bands: IntArray, off: Int, start: Int, stop: Int, num: Int) {
        val base = (stop.toFloat() / start).toDouble().pow(1.0 / num).toFloat()
        var prod = start.toFloat()
        var previous = start
        for (i in 0 until num - 1) {
            prod *= base
            val present = Math.rint(prod.toDouble()).toInt()
            bands[off + i] = present - previous
            previous = present
        }
        bands[off + num - 1] = stop - previous
    }

    private fun log2f(x: Float): Float = (ln(x.toDouble()) / ln(2.0)).toFloat()

    private fun makeMaster(): Boolean {
        val offIdx = when (sampleRate) {
            16000 -> 0
            22050 -> 1
            24000 -> 2
            32000 -> 3
            44100, 48000, 64000 -> 4
            88200, 96000, 128000, 176400, 192000 -> 5
            else -> return false
        }
        val temp = if (sampleRate < 32000) 3000 else if (sampleRate < 64000) 4000 else 5000
        val startMin = ((temp shl 7) + (sampleRate shr 1)) / sampleRate
        val stopMin = ((temp shl 8) + (sampleRate shr 1)) / sampleRate
        k[0] = startMin + SbrTables.OFFSET[offIdx][spec[S_START]]
        when {
            spec[S_STOP] < 14 -> {
                k[2] = stopMin
                val stopDk = IntArray(13)
                makeBands(stopDk, 0, stopMin, 64, 13)
                stopDk.sort()
                for (i in 0 until spec[S_STOP]) k[2] += stopDk[i]
            }
            spec[S_STOP] == 14 -> k[2] = 2 * k[0]
            spec[S_STOP] == 15 -> k[2] = 3 * k[0]
            else -> return false
        }
        k[2] = minOf(64, k[2])
        val maxQmf = if (sampleRate <= 32000) 48 else if (sampleRate == 44100) 35 else 32
        if (k[2] - k[0] > maxQmf) return false

        if (spec[S_FREQ_SCALE] == 0) {
            val dk = spec[S_ALTER] + 1
            nMaster = ((k[2] - k[0] + (dk and 2)) shr dk) shl 1
            if (!checkMaster()) return false
            for (i in 1..nMaster) fMaster[i] = dk
            val k2diff = k[2] - k[0] - nMaster * dk
            if (k2diff < 0) {
                fMaster[1]--
                if (k2diff < -1) fMaster[2]--
            } else if (k2diff != 0) {
                fMaster[nMaster]++
            }
            fMaster[0] = k[0]
            for (i in 1..nMaster) fMaster[i] += fMaster[i - 1]
        } else {
            val halfBands = 7 - spec[S_FREQ_SCALE]
            val twoRegions: Boolean
            if (49 * k[2] > 110 * k[0]) {
                twoRegions = true
                k[1] = 2 * k[0]
            } else {
                twoRegions = false
                k[1] = k[2]
            }
            val nb0 = Math.rint((halfBands * log2f(k[1] / k[0].toFloat())).toDouble()).toInt() * 2
            if (nb0 <= 0) return false
            val vk0 = IntArray(49)
            makeBands(vk0, 1, k[0], k[1], nb0)
            java.util.Arrays.sort(vk0, 1, 1 + nb0)
            val vdk0Max = vk0[nb0]
            vk0[0] = k[0]
            for (i in 1..nb0) {
                if (vk0[i] <= 0) return false
                vk0[i] += vk0[i - 1]
            }
            if (twoRegions) {
                val vk1 = IntArray(49)
                val invwarp = if (spec[S_ALTER] != 0) 0.76923076923076923077f else 1.0f
                val nb1 = Math.rint((halfBands * invwarp * log2f(k[2] / k[1].toFloat())).toDouble()).toInt() * 2
                if (nb1 <= 0) return false
                makeBands(vk1, 1, k[1], k[2], nb1)
                var vdk1Min = vk1[1]
                for (i in 2..nb1) vdk1Min = minOf(vdk1Min, vk1[i])
                if (vdk1Min < vdk0Max) {
                    java.util.Arrays.sort(vk1, 1, 1 + nb1)
                    val change = minOf(vdk0Max - vk1[1], (vk1[nb1] - vk1[1]) shr 1)
                    vk1[1] += change
                    vk1[nb1] -= change
                }
                java.util.Arrays.sort(vk1, 1, 1 + nb1)
                vk1[0] = k[1]
                for (i in 1..nb1) {
                    if (vk1[i] <= 0) return false
                    vk1[i] += vk1[i - 1]
                }
                nMaster = nb0 + nb1
                if (!checkMaster()) return false
                System.arraycopy(vk0, 0, fMaster, 0, nb0 + 1)
                System.arraycopy(vk1, 1, fMaster, nb0 + 1, nb1)
            } else {
                nMaster = nb0
                if (!checkMaster()) return false
                System.arraycopy(vk0, 0, fMaster, 0, nb0 + 1)
            }
        }
        return true
    }

    private fun checkMaster(): Boolean = nMaster > 0 && spec[S_XOVER] < nMaster && nMaster <= 48

    private fun calcPatches(): Boolean {
        var lastK = -1
        var lastMsb = -1
        var sb = 0
        var msb = k[0]
        var usb = kx[1]
        val goalSb = ((1000 shl 11) + (sampleRate shr 1)) / sampleRate
        numPatches = 0
        var kk: Int
        if (goalSb < kx[1] + m[1]) {
            kk = 0
            while (fMaster[kk] < goalSb) kk++
        } else {
            kk = nMaster
        }
        do {
            var odd = 0
            if (kk == lastK && msb == lastMsb) return false
            lastK = kk
            lastMsb = msb
            var i = kk
            while (i == kk || sb > (k[0] - 1 + msb - odd)) {
                if (i < 0) return false
                sb = fMaster[i]
                odd = (sb + k[0]) and 1
                i--
            }
            if (numPatches > 5) return false
            patchNumSubbands[numPatches] = maxOf(sb - usb, 0)
            patchStartSubband[numPatches] = k[0] - odd - patchNumSubbands[numPatches]
            if (patchNumSubbands[numPatches] > 0) {
                usb = sb
                msb = sb
                numPatches++
            } else {
                msb = kx[1]
            }
            if (fMaster[kk] - sb < 3) kk = nMaster
        } while (sb != kx[1] + m[1])
        if (numPatches > 1 && patchNumSubbands[numPatches - 1] < 3) numPatches--
        return true
    }

    private fun makeDerived(): Boolean {
        n[1] = nMaster - spec[S_XOVER]
        n[0] = (n[1] + 1) shr 1
        System.arraycopy(fMaster, spec[S_XOVER], fTableHigh, 0, n[1] + 1)
        m[1] = fTableHigh[n[1]] - fTableHigh[0]
        kx[1] = fTableHigh[0]
        if (kx[1] + m[1] > 64) return false
        if (kx[1] > 32) return false
        fTableLow[0] = fTableHigh[0]
        val t = n[1] and 1
        for (i in 1..n[0]) fTableLow[i] = fTableHigh[2 * i - t]
        nQ = maxOf(1, Math.rint((spec[S_NOISE] * log2f(k[2] / kx[1].toFloat())).toDouble()).toInt())
        if (nQ > 5) {
            nQ = 1
            return false
        }
        fTableNoise[0] = fTableLow[0]
        var temp = 0
        for (i in 1..nQ) {
            temp += (n[0] - temp) / (nQ + 1 - i)
            fTableNoise[i] = fTableLow[temp]
        }
        if (!calcPatches()) return false
        makeTableLim()
        data[0].fIndexNoise = 0
        data[1].fIndexNoise = 0
        return true
    }

    private fun doReset() {
        if (!(makeMaster() && makeDerived())) turnOff()
    }

    // =====================================================================
    // ビットストリーム
    // =====================================================================

    private fun readHeader(b: BitReader) {
        start = true
        readyForDequant = false
        val old = spec.copyOf()
        val oldLimiter = bsLimiterBands
        bsAmpResHeader = b.bit()
        spec[S_START] = b.bits(4)
        spec[S_STOP] = b.bits(4)
        spec[S_XOVER] = b.bits(3)
        b.skip(2)
        val extra1 = b.bit() == 1
        val extra2 = b.bit() == 1
        if (extra1) {
            spec[S_FREQ_SCALE] = b.bits(2)
            spec[S_ALTER] = b.bit()
            spec[S_NOISE] = b.bits(2)
        } else {
            spec[S_FREQ_SCALE] = 2
            spec[S_ALTER] = 1
            spec[S_NOISE] = 2
        }
        if (!old.contentEquals(spec)) reset = true
        if (extra2) {
            bsLimiterBands = b.bits(2)
            bsLimiterGains = b.bits(2)
            bsInterpolFreq = b.bit()
            bsSmoothingMode = b.bit()
        } else {
            bsLimiterBands = 2
            bsLimiterGains = 2
            bsInterpolFreq = 1
            bsSmoothingMode = 1
        }
        if (bsLimiterBands != oldLimiter && !reset) makeTableLim()
    }

    private fun readGrid(b: BitReader, d: Data): Boolean {
        var pointer = 0
        var absBordTrail = 16
        val oldNumEnv = d.bsNumEnv
        d.bsFreqRes[0] = d.bsFreqRes[d.bsNumEnv]
        d.bsAmpRes = bsAmpResHeader
        d.tEnvNumEnvOld = d.tEnv[oldNumEnv]
        val frameClass = b.bits(2)
        when (frameClass) {
            FIXFIX -> {
                val num = 1 shl b.bits(2)
                if (num > 5) return false
                d.bsNumEnv = num
                val numRelLead = num - 1
                if (num == 1) d.bsAmpRes = 0
                d.tEnv[0] = 0
                d.tEnv[num] = absBordTrail
                absBordTrail = (absBordTrail + (num shr 1)) / num
                for (i in 0 until numRelLead) d.tEnv[i + 1] = d.tEnv[i] + absBordTrail
                d.bsFreqRes[1] = b.bit()
                for (i in 1 until num) d.bsFreqRes[i + 1] = d.bsFreqRes[1]
            }
            FIXVAR -> {
                absBordTrail += b.bits(2)
                val numRelTrail = b.bits(2)
                d.bsNumEnv = numRelTrail + 1
                d.tEnv[0] = 0
                d.tEnv[d.bsNumEnv] = absBordTrail
                for (i in 0 until numRelTrail) d.tEnv[d.bsNumEnv - 1 - i] = d.tEnv[d.bsNumEnv - i] - 2 * b.bits(2) - 2
                pointer = b.bits(CEIL_LOG2[d.bsNumEnv])
                for (i in 0 until d.bsNumEnv) d.bsFreqRes[d.bsNumEnv - i] = b.bit()
            }
            VARFIX -> {
                d.tEnv[0] = b.bits(2)
                val numRelLead = b.bits(2)
                d.bsNumEnv = numRelLead + 1
                d.tEnv[d.bsNumEnv] = absBordTrail
                for (i in 0 until numRelLead) d.tEnv[i + 1] = d.tEnv[i] + 2 * b.bits(2) + 2
                pointer = b.bits(CEIL_LOG2[d.bsNumEnv])
                for (i in 0 until d.bsNumEnv) d.bsFreqRes[1 + i] = b.bit()
            }
            else -> {
                d.tEnv[0] = b.bits(2)
                absBordTrail += b.bits(2)
                val numRelLead = b.bits(2)
                val numRelTrail = b.bits(2)
                val num = numRelLead + numRelTrail + 1
                if (num > 5) return false
                d.bsNumEnv = num
                d.tEnv[num] = absBordTrail
                for (i in 0 until numRelLead) d.tEnv[i + 1] = d.tEnv[i] + 2 * b.bits(2) + 2
                for (i in 0 until numRelTrail) d.tEnv[num - 1 - i] = d.tEnv[num - i] - 2 * b.bits(2) - 2
                pointer = b.bits(CEIL_LOG2[num])
                for (i in 0 until num) d.bsFreqRes[1 + i] = b.bit()
            }
        }
        d.bsFrameClass = frameClass
        if (pointer > d.bsNumEnv + 1) return false
        for (i in 1..d.bsNumEnv) if (d.tEnv[i - 1] >= d.tEnv[i]) return false
        d.bsNumNoise = (if (d.bsNumEnv > 1) 1 else 0) + 1
        d.tQ[0] = d.tEnv[0]
        d.tQ[d.bsNumNoise] = d.tEnv[d.bsNumEnv]
        if (d.bsNumNoise > 1) {
            val idx = when {
                d.bsFrameClass == FIXFIX -> d.bsNumEnv shr 1
                d.bsFrameClass and 1 != 0 -> d.bsNumEnv - maxOf(pointer - 1, 1)
                pointer == 0 -> 1
                pointer == 1 -> d.bsNumEnv - 1
                else -> pointer - 1
            }
            d.tQ[1] = d.tEnv[idx]
        }
        d.eA[0] = if (d.eA[1] != oldNumEnv) -1 else 0
        d.eA[1] = -1
        if ((d.bsFrameClass and 1) != 0 && pointer != 0) {
            d.eA[1] = d.bsNumEnv + 1 - pointer
        } else if (d.bsFrameClass == 2 && pointer > 1) {
            d.eA[1] = pointer - 1
        }
        return true
    }

    private fun copyGrid(dst: Data, src: Data) {
        dst.bsFreqRes[0] = dst.bsFreqRes[dst.bsNumEnv]
        dst.tEnvNumEnvOld = dst.tEnv[dst.bsNumEnv]
        dst.eA[0] = if (dst.eA[1] != dst.bsNumEnv) -1 else 0
        System.arraycopy(src.bsFreqRes, 1, dst.bsFreqRes, 1, 8)
        System.arraycopy(src.tEnv, 0, dst.tEnv, 0, 9)
        System.arraycopy(src.tQ, 0, dst.tQ, 0, 3)
        dst.bsNumEnv = src.bsNumEnv
        dst.bsAmpRes = src.bsAmpRes
        dst.bsNumNoise = src.bsNumNoise
        dst.bsFrameClass = src.bsFrameClass
        dst.eA[1] = src.eA[1]
    }

    private fun readDtdf(b: BitReader, d: Data) {
        for (i in 0 until d.bsNumEnv) d.bsDfEnv[i] = b.bit()
        for (i in 0 until d.bsNumNoise) d.bsDfNoise[i] = b.bit()
    }

    private fun readInvf(b: BitReader, d: Data) {
        System.arraycopy(d.bsInvfMode[0], 0, d.bsInvfMode[1], 0, 5)
        for (i in 0 until nQ) d.bsInvfMode[0][i] = b.bits(2)
    }

    private fun readEnvelope(b: BitReader, d: Data, ch: Int): Boolean {
        val delta = if (ch == 1 && bsCoupling == 1) 2 else 1
        val odd = n[1] and 1
        val bits: Int
        val t: Int
        val f: Int
        if (bsCoupling != 0 && ch != 0) {
            if (d.bsAmpRes != 0) { bits = 5; t = 6; f = 7 } else { bits = 6; t = 2; f = 3 }
        } else {
            if (d.bsAmpRes != 0) { bits = 6; t = 4; f = 5 } else { bits = 7; t = 0; f = 1 }
        }
        for (i in 0 until d.bsNumEnv) {
            val res = d.bsFreqRes[i + 1]
            val cur = d.envFacsQ[i + 1]
            val prev = d.envFacsQ[i]
            if (d.bsDfEnv[i] != 0) {
                for (j in 0 until n[res]) {
                    val kk = when {
                        res == d.bsFreqRes[i] -> j
                        res != 0 -> (j + odd) shr 1
                        else -> if (j != 0) 2 * j - odd else 0
                    }
                    val v = prev[kk] + delta * huff(b, t)
                    if (v < 0 || v > 127) return false
                    cur[j] = v
                }
            } else {
                cur[0] = (delta * b.bits(bits)) and 0xFF
                for (j in 1 until n[res]) {
                    val v = cur[j - 1] + delta * huff(b, f)
                    if (v < 0 || v > 127) return false
                    cur[j] = v
                }
            }
        }
        System.arraycopy(d.envFacsQ[d.bsNumEnv], 0, d.envFacsQ[0], 0, 48)
        return true
    }

    private fun readNoise(b: BitReader, d: Data, ch: Int): Boolean {
        val delta = if (ch == 1 && bsCoupling == 1) 2 else 1
        val t: Int
        val f: Int
        if (bsCoupling != 0 && ch != 0) { t = 9; f = 7 } else { t = 8; f = 5 }
        for (i in 0 until d.bsNumNoise) {
            val cur = d.noiseFacsQ[i + 1]
            val prev = d.noiseFacsQ[i]
            if (d.bsDfNoise[i] != 0) {
                for (j in 0 until nQ) {
                    val v = prev[j] + delta * huff(b, t)
                    if (v < 0 || v > 30) return false
                    cur[j] = v
                }
            } else {
                cur[0] = (delta * b.bits(5)) and 0xFF
                for (j in 1 until nQ) {
                    val v = cur[j - 1] + delta * huff(b, f)
                    if (v < 0 || v > 30) return false
                    cur[j] = v
                }
            }
        }
        System.arraycopy(d.noiseFacsQ[d.bsNumNoise], 0, d.noiseFacsQ[0], 0, 5)
        return true
    }

    private fun huff(b: BitReader, t: Int): Int = HUFF[t].decode(b) + SbrTables.HUFF_OFFSET[t]

    private fun readHarmonic(b: BitReader, d: Data) {
        d.bsAddHarmonicFlag = b.bit() == 1
        if (d.bsAddHarmonicFlag) for (i in 0 until n[1]) d.bsAddHarmonic[i] = b.bit()
    }

    private fun readSce(b: BitReader): Boolean {
        if (b.bit() == 1) b.skip(4)
        val d = data[0]
        if (!readGrid(b, d)) return false
        readDtdf(b, d)
        readInvf(b, d)
        if (!readEnvelope(b, d, 0)) return false
        if (!readNoise(b, d, 0)) return false
        readHarmonic(b, d)
        return true
    }

    private fun readCpe(b: BitReader): Boolean {
        if (b.bit() == 1) b.skip(8)
        val d0 = data[0]
        val d1 = data[1]
        bsCoupling = b.bit()
        if (bsCoupling != 0) {
            if (!readGrid(b, d0)) return false
            copyGrid(d1, d0)
            readDtdf(b, d0)
            readDtdf(b, d1)
            readInvf(b, d0)
            System.arraycopy(d1.bsInvfMode[0], 0, d1.bsInvfMode[1], 0, 5)
            System.arraycopy(d0.bsInvfMode[0], 0, d1.bsInvfMode[0], 0, 5)
            if (!readEnvelope(b, d0, 0)) return false
            if (!readNoise(b, d0, 0)) return false
            if (!readEnvelope(b, d1, 1)) return false
            if (!readNoise(b, d1, 1)) return false
        } else {
            if (!readGrid(b, d0) || !readGrid(b, d1)) return false
            readDtdf(b, d0)
            readDtdf(b, d1)
            readInvf(b, d0)
            readInvf(b, d1)
            if (!readEnvelope(b, d0, 0)) return false
            if (!readEnvelope(b, d1, 1)) return false
            if (!readNoise(b, d0, 0)) return false
            if (!readNoise(b, d1, 1)) return false
        }
        readHarmonic(b, d0)
        readHarmonic(b, d1)
        return true
    }

    private fun readData(b: BitReader, id: Int) {
        idAac = id
        readyForDequant = true
        val ok = when (id) {
            ID_SCE -> readSce(b)
            ID_CPE -> readCpe(b)
            else -> false
        }
        if (!ok) {
            turnOff()
            return
        }
        if (b.bit() == 1) {
            var left = b.bits(4)
            if (left == 15) left += b.bits(8)
            left = left shl 3
            while (left > 7) {
                left -= 2
                val ext = b.bits(2)
                if (ext == EXTENSION_ID_PS && ps != null) {
                    left -= ps.read(b, left)
                } else {
                    b.skip(left)
                    left = 0
                }
            }
            if (left > 0) b.skip(left)
        }
    }

    /** FIL の中の SBR のデータ(拡張の種類の 4 ビットのあとから、bits ビット)を読む */
    fun decodeExtension(b: BitReader, bits: Int, crc: Boolean, id: Int) {
        val end = b.pos + bits
        reset = false
        try {
            if (crc) b.skip(10)
            kx[0] = kx[1]
            m[0] = m[1]
            kxAndMPushed = true
            if (b.bit() == 1) readHeader(b)
            if (reset) doReset()
            if (start) readData(b, id)
            if (b.pos > end) turnOff()
        } catch (e: AacException) {
            turnOff()
        }
    }

    // =====================================================================
    // 合成
    // =====================================================================

    private fun dequant(id: Int) {
        if (id == ID_CPE && bsCoupling != 0) {
            val d0 = data[0]
            val d1 = data[1]
            val pan = if (d0.bsAmpRes != 0) 12 else 24
            for (e in 1..d0.bsNumEnv) for (kk in 0 until n[d0.bsFreqRes[e]]) {
                var t1: Float
                val t2: Float
                if (d0.bsAmpRes != 0) {
                    t1 = exp2fi(d0.envFacsQ[e][kk] + 7)
                    t2 = exp2fi(pan - d1.envFacsQ[e][kk])
                } else {
                    t1 = (exp2fi((d0.envFacsQ[e][kk] shr 1) + 7) * EXP2_TAB[d0.envFacsQ[e][kk] and 1]).toFloat()
                    val p = pan - d1.envFacsQ[e][kk]
                    t2 = (exp2fi(p shr 1) * EXP2_TAB[p and 1]).toFloat()
                }
                if (t1 > 1e20f) t1 = 1f
                val fac = t1 / (1.0f + t2)
                d0.envFacs[e][kk] = fac
                d1.envFacs[e][kk] = fac * t2
            }
            for (e in 1..d0.bsNumNoise) for (kk in 0 until nQ) {
                val t1 = exp2fi(NOISE_FLOOR_OFFSET - d0.noiseFacsQ[e][kk] + 1)
                val t2 = exp2fi(12 - d1.noiseFacsQ[e][kk])
                val fac = t1 / (1.0f + t2)
                d0.noiseFacs[e][kk] = fac
                d1.noiseFacs[e][kk] = fac * t2
            }
        } else {
            for (ch in 0 until (if (id == ID_CPE) 2 else 1)) {
                val d = data[ch]
                for (e in 1..d.bsNumEnv) for (kk in 0 until n[d.bsFreqRes[e]]) {
                    var v = if (d.bsAmpRes != 0) {
                        exp2fi(d.envFacsQ[e][kk] + 6)
                    } else {
                        (exp2fi((d.envFacsQ[e][kk] shr 1) + 6) * EXP2_TAB[d.envFacsQ[e][kk] and 1]).toFloat()
                    }
                    if (v > 1e20f) v = 1f
                    d.envFacs[e][kk] = v
                }
                for (e in 1..d.bsNumNoise) for (kk in 0 until nQ) d.noiseFacs[e][kk] = exp2fi(NOISE_FLOOR_OFFSET - d.noiseFacsQ[e][kk])
            }
        }
    }

    // ---- QMF ----

    /** 分析(32 帯域): in(1024 サンプル)→ W[buf] */
    private fun analysis(inp: FloatArray, d: Data, buf: Int) {
        val xb = d.analysis
        System.arraycopy(xb, 1024, xb, 0, 320 - 32)
        System.arraycopy(inp, 0, xb, 288, 1024)
        val win = SbrTables.QMF_WINDOW_DS
        var xo = 0
        for (i in 0 until 32) {
            for (j in 0 until 320) z[j] = win[j] * xb[xo + 319 - j]
            for (j in 0 until 64) z[j] = z[j] + z[j + 64] + z[j + 128] + z[j + 192] + z[j + 256]
            // qmf_pre_shuffle
            z[64] = z[0]
            z[65] = z[1]
            var kk = 1
            while (kk < 31) {
                z[64 + 2 * kk] = -z[64 - kk]
                z[64 + 2 * kk + 1] = z[kk + 1]
                z[64 + 2 * kk + 2] = -z[63 - kk]
                z[64 + 2 * kk + 3] = z[kk + 2]
                kk += 2
            }
            z[64 + 2 * 31] = -z[64 - 31]
            z[64 + 2 * 31 + 1] = z[31 + 1]
            // FFmpeg では分析も逆 MDCT(半分)。倍率 −2 × 32768
            mdctInverse(z, 64, mdctTmp, DCT_ANA)
            System.arraycopy(mdctTmp, 0, z, 0, 64)
            // qmf_post_shuffle
            val wo = (buf * 32 + i) * 64
            kk = 0
            while (kk < 32) {
                d.w[wo + 2 * kk] = -z[63 - kk]
                d.w[wo + 2 * kk + 1] = z[kk]
                d.w[wo + 2 * kk + 2] = -z[62 - kk]
                d.w[wo + 2 * kk + 3] = z[kk + 1]
                kk += 2
            }
            xo += 32
        }
    }

    /** 合成(64 帯域): X → out(2048 サンプル) */
    private fun synthesis(out: FloatArray, xx: FloatArray, d: Data) {
        val win = SbrTables.QMF_WINDOW_US
        val v0 = d.synth
        var o = 0
        for (i in 0 until 32) {
            if (d.synthOffset < 128) {
                val saved = 1280 - 128
                System.arraycopy(v0, 0, v0, SYNTH_BUF - saved, saved)
                d.synthOffset = SYNTH_BUF - saved - 128
            } else {
                d.synthOffset -= 128
            }
            val v = d.synthOffset
            val re = i * 64
            val im = (38 + i) * 64
            // neg_odd_64(X[1][i])
            var kk = 1
            while (kk < 64) { xx[im + kk] = -xx[im + kk]; kk += 2 }
            mdctInverse(xx, re, mdctBuf[0], DCT_SYN)
            mdctInverse(xx, im, mdctBuf[1], DCT_SYN)
            val s0 = mdctBuf[1]
            val s1 = mdctBuf[0]
            for (j in 0 until 64) {
                v0[v + j] = s0[j] - s1[63 - j]
                v0[v + 127 - j] = s0[j] + s1[63 - j]
            }
            for (j in 0 until 64) {
                out[o + j] = v0[v + j] * win[j] +
                    v0[v + 192 + j] * win[64 + j] +
                    v0[v + 256 + j] * win[128 + j] +
                    v0[v + 448 + j] * win[192 + j] +
                    v0[v + 512 + j] * win[256 + j] +
                    v0[v + 704 + j] * win[320 + j] +
                    v0[v + 768 + j] * win[384 + j] +
                    v0[v + 960 + j] * win[448 + j] +
                    v0[v + 1024 + j] * win[512 + j] +
                    v0[v + 1216 + j] * win[576 + j]
            }
            o += 64
        }
    }

    // ---- 低い帯域・高い帯域の生成 ----

    private fun lfGen(d: Data, buf: Int) {
        java.util.Arrays.fill(xLow, 0f)
        for (kk in 0 until kx[1]) for (i in 8 until 40) {
            val s = ((buf * 32 + i - 8) * 32 + kk) * 2
            val t = (kk * 40 + i) * 2
            xLow[t] = d.w[s]
            xLow[t + 1] = d.w[s + 1]
        }
        val b2 = 1 - buf
        for (kk in 0 until kx[0]) for (i in 0 until 8) {
            val s = ((b2 * 32 + i + 32 - 8) * 32 + kk) * 2
            val t = (kk * 40 + i) * 2
            xLow[t] = d.w[s]
            xLow[t + 1] = d.w[s + 1]
        }
    }

    private val phi = FloatArray(12)

    /** phi[a][b][c] = phi[(a * 2 + b) * 2 + c] */
    private fun autocorrelate(kk: Int) {
        val x = xLow
        val o = kk * 80
        fun re(i: Int) = x[o + 2 * i]
        fun im(i: Int) = x[o + 2 * i + 1]
        var rs2 = re(0) * re(2) + im(0) * im(2)
        var is2 = re(0) * im(2) - im(0) * re(2)
        var rs1 = 0f
        var is1 = 0f
        var rs0 = 0f
        for (i in 1 until 38) {
            rs0 += re(i) * re(i) + im(i) * im(i)
            rs1 += re(i) * re(i + 1) + im(i) * im(i + 1)
            is1 += re(i) * im(i + 1) - im(i) * re(i + 1)
            rs2 += re(i) * re(i + 2) + im(i) * im(i + 2)
            is2 += re(i) * im(i + 2) - im(i) * re(i + 2)
        }
        phi[P(0, 1, 0)] = rs2
        phi[P(0, 1, 1)] = is2
        phi[P(2, 1, 0)] = rs0 + re(0) * re(0) + im(0) * im(0)
        phi[P(1, 0, 0)] = rs0 + re(38) * re(38) + im(38) * im(38)
        phi[P(1, 1, 0)] = rs1 + re(0) * re(1) + im(0) * im(1)
        phi[P(1, 1, 1)] = is1 + re(0) * im(1) - im(0) * re(1)
        phi[P(0, 0, 0)] = rs1 + re(38) * re(39) + im(38) * im(39)
        phi[P(0, 0, 1)] = is1 + re(38) * im(39) - im(38) * re(39)
    }

    @Suppress("FunctionName")
    private fun P(a: Int, b: Int, c: Int) = (a * 2 + b) * 2 + c

    private fun inverseFilter(k0: Int) {
        for (kk in 0 until k0) {
            autocorrelate(kk)
            val dk = phi[P(2, 1, 0)] * phi[P(1, 0, 0)] -
                (phi[P(1, 1, 0)] * phi[P(1, 1, 0)] + phi[P(1, 1, 1)] * phi[P(1, 1, 1)]) / 1.000001f
            if (dk == 0f) {
                alpha1[2 * kk] = 0f
                alpha1[2 * kk + 1] = 0f
            } else {
                val tr = phi[P(0, 0, 0)] * phi[P(1, 1, 0)] - phi[P(0, 0, 1)] * phi[P(1, 1, 1)] - phi[P(0, 1, 0)] * phi[P(1, 0, 0)]
                val ti = phi[P(0, 0, 0)] * phi[P(1, 1, 1)] + phi[P(0, 0, 1)] * phi[P(1, 1, 0)] - phi[P(0, 1, 1)] * phi[P(1, 0, 0)]
                alpha1[2 * kk] = tr / dk
                alpha1[2 * kk + 1] = ti / dk
            }
            if (phi[P(1, 0, 0)] == 0f) {
                alpha0[2 * kk] = 0f
                alpha0[2 * kk + 1] = 0f
            } else {
                val tr = phi[P(0, 0, 0)] + alpha1[2 * kk] * phi[P(1, 1, 0)] + alpha1[2 * kk + 1] * phi[P(1, 1, 1)]
                val ti = phi[P(0, 0, 1)] + alpha1[2 * kk + 1] * phi[P(1, 1, 0)] - alpha1[2 * kk] * phi[P(1, 1, 1)]
                alpha0[2 * kk] = -tr / phi[P(1, 0, 0)]
                alpha0[2 * kk + 1] = -ti / phi[P(1, 0, 0)]
            }
            val a1 = alpha1[2 * kk] * alpha1[2 * kk] + alpha1[2 * kk + 1] * alpha1[2 * kk + 1]
            val a0 = alpha0[2 * kk] * alpha0[2 * kk] + alpha0[2 * kk + 1] * alpha0[2 * kk + 1]
            if (a1 >= 16f || a0 >= 16f) {
                alpha1[2 * kk] = 0f; alpha1[2 * kk + 1] = 0f
                alpha0[2 * kk] = 0f; alpha0[2 * kk + 1] = 0f
            }
        }
    }

    private fun chirp(d: Data) {
        for (i in 0 until nQ) {
            var bw = if (d.bsInvfMode[0][i] + d.bsInvfMode[1][i] == 1) 0.6f else BW_TAB[d.bsInvfMode[0][i]]
            bw = if (bw < d.bwArray[i]) 0.75f * bw + 0.25f * d.bwArray[i] else 0.90625f * bw + 0.09375f * d.bwArray[i]
            d.bwArray[i] = if (bw < 0.015625f) 0f else bw
        }
    }

    private fun hfGen(d: Data): Boolean {
        var g = 0
        var kk = kx[1]
        for (j in 0 until numPatches) {
            for (xi in 0 until patchNumSubbands[j]) {
                val p = patchStartSubband[j] + xi
                while (g <= nQ && kk >= fTableNoise[g]) g++
                g--
                if (g < 0) return false
                val bw = d.bwArray[g]
                val a0r = alpha1[2 * p] * bw * bw
                val a0i = alpha1[2 * p + 1] * bw * bw
                val a1r = alpha0[2 * p] * bw
                val a1i = alpha0[2 * p + 1] * bw
                val start = 2 * d.tEnv[0]
                val end = 2 * d.tEnv[d.bsNumEnv]
                val hi = kk * 80 + ENV_OFF * 2
                val lo = p * 80 + ENV_OFF * 2
                for (i in start until end) {
                    val l0r = xLow[lo + 2 * (i - 2)]
                    val l0i = xLow[lo + 2 * (i - 2) + 1]
                    val l1r = xLow[lo + 2 * (i - 1)]
                    val l1i = xLow[lo + 2 * (i - 1) + 1]
                    xHigh[hi + 2 * i] = l0r * a0r - l0i * a0i + l1r * a1r - l1i * a1i + xLow[lo + 2 * i]
                    xHigh[hi + 2 * i + 1] = l0i * a0r + l0r * a0i + l1i * a1r + l1r * a1i + xLow[lo + 2 * i + 1]
                }
                kk++
            }
        }
        if (kk < m[1] + kx[1]) java.util.Arrays.fill(xHigh, kk * 80, (m[1] + kx[1]) * 80, 0f)
        return true
    }

    private fun mapping(d: Data): Boolean {
        val eA = d.eA
        for (e in 1 until 8) d.sIndexMapped[e].fill(0)
        for (e in 0 until d.bsNumEnv) {
            val ilim = n[d.bsFreqRes[e + 1]]
            val table = if (d.bsFreqRes[e + 1] != 0) fTableHigh else fTableLow
            if (kx[1] != table[0]) {
                turnOff()
                return false
            }
            for (i in 0 until ilim) for (mm in table[i] until table[i + 1]) eOrigMapped[e][mm - kx[1]] = d.envFacs[e + 1][i]
            val kk = if (d.bsNumNoise > 1 && d.tEnv[e] >= d.tQ[1]) 1 else 0
            for (i in 0 until nQ) for (mm in fTableNoise[i] until fTableNoise[i + 1]) qMapped[e][mm - kx[1]] = d.noiseFacs[kk + 1][i]
            for (i in 0 until n[1]) {
                if (d.bsAddHarmonicFlag) {
                    val mid = (fTableHigh[i] + fTableHigh[i + 1]) shr 1
                    d.sIndexMapped[e + 1][mid - kx[1]] = d.bsAddHarmonic[i] *
                        (if (e >= eA[1] || d.sIndexMapped[0][mid - kx[1]] == 1) 1 else 0)
                }
            }
            for (i in 0 until ilim) {
                var present = 0
                for (mm in table[i] until table[i + 1]) if (d.sIndexMapped[e + 1][mm - kx[1]] != 0) { present = 1; break }
                java.util.Arrays.fill(sMapped[e], table[i] - kx[1], table[i + 1] - kx[1], present)
            }
        }
        System.arraycopy(d.sIndexMapped[d.bsNumEnv], 0, d.sIndexMapped[0], 0, 48)
        return true
    }

    private fun sumSquare(off: Int, n: Int): Float {
        var s0 = 0f
        var s1 = 0f
        var i = 0
        while (i < n) {
            s0 += xHigh[off + 2 * i] * xHigh[off + 2 * i]
            s1 += xHigh[off + 2 * i + 1] * xHigh[off + 2 * i + 1]
            s0 += xHigh[off + 2 * i + 2] * xHigh[off + 2 * i + 2]
            s1 += xHigh[off + 2 * i + 3] * xHigh[off + 2 * i + 3]
            i += 2
        }
        return s0 + s1
    }

    private fun envEstimate(d: Data) {
        val kx1 = kx[1]
        if (bsInterpolFreq != 0) {
            for (e in 0 until d.bsNumEnv) {
                val recip = 0.5f / (d.tEnv[e + 1] - d.tEnv[e])
                val ilb = d.tEnv[e] * 2 + ENV_OFF
                val iub = d.tEnv[e + 1] * 2 + ENV_OFF
                if (ilb >= 40) return
                for (mm in 0 until m[1]) eCurr[e][mm] = sumSquare((mm + kx1) * 80 + ilb * 2, iub - ilb) * recip
            }
        } else {
            for (e in 0 until d.bsNumEnv) {
                val envSize = 2 * (d.tEnv[e + 1] - d.tEnv[e])
                val ilb = d.tEnv[e] * 2 + ENV_OFF
                val iub = d.tEnv[e + 1] * 2 + ENV_OFF
                val table = if (d.bsFreqRes[e + 1] != 0) fTableHigh else fTableLow
                if (ilb >= 40) return
                for (p in 0 until n[d.bsFreqRes[e + 1]]) {
                    var sum = 0f
                    val den = envSize * (table[p + 1] - table[p])
                    for (kk in table[p] until table[p + 1]) sum += sumSquare(kk * 80 + ilb * 2, iub - ilb)
                    sum /= den
                    for (kk in table[p] until table[p + 1]) eCurr[e][kk - kx1] = sum
                }
            }
        }
    }

    private fun gainCalc(d: Data) {
        val eA = d.eA
        for (e in 0 until d.bsNumEnv) {
            val delta = if (e == eA[1] || e == eA[0]) 0 else 1
            for (kk in 0 until nLim) {
                val lo = fTableLim[kk] - kx[1]
                val hi = fTableLim[kk + 1] - kx[1]
                for (mm in lo until hi) {
                    val temp = eOrigMapped[e][mm] / (1.0f + qMapped[e][mm])
                    qM[e][mm] = sqrt(temp * qMapped[e][mm])
                    sM[e][mm] = sqrt(temp * d.sIndexMapped[e + 1][mm])
                    gain[e][mm] = if (sMapped[e][mm] == 0) {
                        sqrt(eOrigMapped[e][mm] / ((1.0f + eCurr[e][mm]) * (1.0f + qMapped[e][mm] * delta)))
                    } else {
                        sqrt(eOrigMapped[e][mm] * qMapped[e][mm] / ((1.0f + eCurr[e][mm]) * (1.0f + qMapped[e][mm])))
                    }
                    gain[e][mm] += java.lang.Float.MIN_NORMAL
                }
                var s0 = 0f
                var s1 = 0f
                for (mm in lo until hi) {
                    s0 += eOrigMapped[e][mm]
                    s1 += eCurr[e][mm]
                }
                var gainMax = LIM_GAIN[bsLimiterGains] * sqrt((FLT_EPSILON + s0) / (FLT_EPSILON + s1))
                gainMax = minOf(100000f, gainMax)
                for (mm in lo until hi) {
                    val qMax = qM[e][mm] * gainMax / gain[e][mm]
                    qM[e][mm] = minOf(qM[e][mm], qMax)
                    gain[e][mm] = minOf(gain[e][mm], gainMax)
                }
                s0 = 0f
                s1 = 0f
                for (mm in lo until hi) {
                    s0 += eOrigMapped[e][mm]
                    s1 += eCurr[e][mm] * gain[e][mm] * gain[e][mm] + sM[e][mm] * sM[e][mm] +
                        (if (delta != 0 && sM[e][mm] == 0f) qM[e][mm] * qM[e][mm] else 0f)
                }
                var boost = sqrt((FLT_EPSILON + s0) / (FLT_EPSILON + s1))
                boost = minOf(1.584893192f, boost)
                for (mm in lo until hi) {
                    gain[e][mm] *= boost
                    qM[e][mm] *= boost
                    sM[e][mm] *= boost
                }
            }
        }
    }

    private val gFilt = FloatArray(48)
    private val qFilt = FloatArray(48)

    private fun hfAssemble(d: Data) {
        val y = d.y
        val yb = d.yPos * 38 * 64 * 2
        val eA = d.eA
        val hSL = if (bsSmoothingMode == 0) 4 else 0
        val kx1 = kx[1]
        val mMax = m[1]
        val gTemp = d.gTemp
        val qTemp = d.qTemp
        var indexNoise = d.fIndexNoise
        var indexSine = d.fIndexSine
        if (reset) {
            for (i in 0 until hSL) {
                System.arraycopy(gain[0], 0, gTemp[i + 2 * d.tEnv[0]], 0, mMax)
                System.arraycopy(qM[0], 0, qTemp[i + 2 * d.tEnv[0]], 0, mMax)
            }
        } else if (hSL != 0) {
            for (i in 0 until 4) {
                System.arraycopy(gTemp[i + 2 * d.tEnvNumEnvOld], 0, gTemp[i + 2 * d.tEnv[0]], 0, 48)
                System.arraycopy(qTemp[i + 2 * d.tEnvNumEnvOld], 0, qTemp[i + 2 * d.tEnv[0]], 0, 48)
            }
        }
        for (e in 0 until d.bsNumEnv) for (i in 2 * d.tEnv[e] until 2 * d.tEnv[e + 1]) {
            System.arraycopy(gain[e], 0, gTemp[hSL + i], 0, mMax)
            System.arraycopy(qM[e], 0, qTemp[hSL + i], 0, mMax)
        }
        val noise = SbrTables.NOISE
        for (e in 0 until d.bsNumEnv) {
            for (i in 2 * d.tEnv[e] until 2 * d.tEnv[e + 1]) {
                val gf: FloatArray
                val qf: FloatArray
                if (hSL != 0 && e != eA[0] && e != eA[1]) {
                    gf = gFilt
                    qf = qFilt
                    for (mm in 0 until mMax) {
                        val idx1 = i + hSL
                        var gs = 0f
                        var qs = 0f
                        for (j in 0..hSL) {
                            gs += gTemp[idx1 - j][mm] * H_SMOOTH[j]
                            qs += qTemp[idx1 - j][mm] * H_SMOOTH[j]
                        }
                        gf[mm] = gs
                        qf[mm] = qs
                    }
                } else {
                    gf = gTemp[i + hSL]
                    qf = qTemp[i]
                }
                // hf_g_filt
                val yo = yb + (i * 64 + kx1) * 2
                for (mm in 0 until mMax) {
                    val xo = (kx1 + mm) * 80 + (i + ENV_OFF) * 2
                    y[yo + 2 * mm] = xHigh[xo] * gf[mm]
                    y[yo + 2 * mm + 1] = xHigh[xo + 1] * gf[mm]
                }
                val sm = sM[e]
                if (e != eA[0] && e != eA[1]) {
                    // hf_apply_noise_0..3
                    var ps0: Float
                    var ps1: Float
                    when (indexSine) {
                        0 -> { ps0 = 1f; ps1 = 0f }
                        1 -> { ps0 = 0f; ps1 = (1 - 2 * (kx1 and 1)).toFloat() }
                        2 -> { ps0 = -1f; ps1 = 0f }
                        else -> { ps0 = 0f; ps1 = -(1 - 2 * (kx1 and 1)).toFloat() }
                    }
                    var nz = indexNoise
                    for (mm in 0 until mMax) {
                        var y0 = y[yo + 2 * mm]
                        var y1 = y[yo + 2 * mm + 1]
                        nz = (nz + 1) and 0x1ff
                        if (sm[mm] != 0f) {
                            y0 += sm[mm] * ps0
                            y1 += sm[mm] * ps1
                        } else {
                            y0 += qf[mm] * noise[2 * nz]
                            y1 += qf[mm] * noise[2 * nz + 1]
                        }
                        y[yo + 2 * mm] = y0
                        y[yo + 2 * mm + 1] = y1
                        ps1 = -ps1
                    }
                } else {
                    val idx = indexSine and 1
                    val a = 1 - ((indexSine + (kx1 and 1)) and 2)
                    val b = (a xor -idx) + idx
                    var mm = 0
                    while (mm + 1 < mMax) {
                        y[yo + 2 * mm + idx] += sm[mm] * a
                        y[yo + 2 * mm + 2 + idx] += sm[mm + 1] * b
                        mm += 2
                    }
                    if (mMax and 1 != 0) y[yo + 2 * mm + idx] += sm[mm] * a
                }
                indexNoise = (indexNoise + mMax) and 0x1ff
                indexSine = (indexSine + 1) and 3
            }
        }
        d.fIndexNoise = indexNoise
        d.fIndexSine = indexSine
    }

    private fun xGen(xx: FloatArray, d: Data) {
        val y0b = (1 - d.yPos) * 38 * 64 * 2
        val y1b = d.yPos * 38 * 64 * 2
        val y = d.y
        val iTemp = maxOf(2 * d.tEnvNumEnvOld - 32, 0)
        java.util.Arrays.fill(xx, 0f)
        val im = 38 * 64
        var kk = 0
        while (kk < kx[0]) {
            for (i in 0 until iTemp) {
                xx[i * 64 + kk] = xLow[(kk * 40 + i + ENV_OFF) * 2]
                xx[im + i * 64 + kk] = xLow[(kk * 40 + i + ENV_OFF) * 2 + 1]
            }
            kk++
        }
        while (kk < kx[0] + m[0]) {
            for (i in 0 until iTemp) {
                xx[i * 64 + kk] = y[y0b + ((i + 32) * 64 + kk) * 2]
                xx[im + i * 64 + kk] = y[y0b + ((i + 32) * 64 + kk) * 2 + 1]
            }
            kk++
        }
        kk = 0
        while (kk < kx[1]) {
            for (i in iTemp until 38) {
                xx[i * 64 + kk] = xLow[(kk * 40 + i + ENV_OFF) * 2]
                xx[im + i * 64 + kk] = xLow[(kk * 40 + i + ENV_OFF) * 2 + 1]
            }
            kk++
        }
        while (kk < kx[1] + m[1]) {
            for (i in iTemp until 32) {
                xx[i * 64 + kk] = y[y1b + (i * 64 + kk) * 2]
                xx[im + i * 64 + kk] = y[y1b + (i * 64 + kk) * 2 + 1]
            }
            kk++
        }
    }

    /**
     * 中の AAC の出力(l・r の先頭 1024 サンプル)に SBR を掛けて、2048 サンプルにする(l・r に上書き)。
     * id は要素の種類(ID_SCE・ID_CPE・ID_LFE)。PS があれば、モノラルの l から l・r の両方を作る
     */
    fun apply(id: Int, l: FloatArray, r: FloatArray?) {
        if (id != idAac) turnOff()
        if (start && !readyForDequant) turnOff()
        if (!kxAndMPushed) {
            kx[0] = kx[1]
            m[0] = m[1]
        } else {
            kxAndMPushed = false
        }
        if (start) {
            dequant(id)
            readyForDequant = false
        }
        var nch = if (id == ID_CPE) 2 else 1
        for (ch in 0 until nch) {
            val d = data[ch]
            analysis(if (ch == 1) r!! else l, d, d.yPos)
            lfGen(d, d.yPos)
            d.yPos = d.yPos xor 1
            if (start) {
                inverseFilter(k[0])
                chirp(d)
                if (d.bsNumEnv > 0 && hfGen(d) && mapping(d)) {
                    envEstimate(d)
                    gainCalc(d)
                    hfAssemble(d)
                }
            }
            xGen(x[ch], d)
        }
        if (ps != null && r != null) {
            if (ps.started) ps.apply(x[0], x[1], kx[1] + m[1]) else System.arraycopy(x[0], 0, x[1], 0, x[0].size)
            nch = 2
        }
        synthesis(l, x[0], data[0])
        if (nch == 2 && r != null) synthesis(r, x[1], data[1])
    }

    // ---- MDCT(FFmpeg の av_tx の逆 MDCT の定義と同じ。大きさ 64 の半分の出力) ----

    private val mdctTmp = FloatArray(64)
    private val dctOut = FloatArray(64)

    /**
     * inp[off until off+64] → out[0 until 64]。FFmpeg の av_tx の「半分の逆 MDCT」(大きさ 64)は、
     * 大きさ 64 の DCT-IV を逆の順に並べたものと同じ(out[k] = DCT-IV[63 − k])
     */
    private fun mdctInverse(inp: FloatArray, off: Int, out: FloatArray, dct: com.uyatame.ucrt.player.Dct4) {
        dct.run(inp, off, dctOut, 0)
        for (k in 0 until 64) out[k] = dctOut[63 - k]
    }

    companion object {
        const val ID_SCE = 0
        const val ID_CPE = 1
        const val ID_LFE = 3
        private const val SYNTH_BUF = (1280 - 128) * 2
        private const val ENV_OFF = 2
        private const val NOISE_FLOOR_OFFSET = 6
        private const val EXTENSION_ID_PS = 2
        private const val FIXFIX = 0
        private const val FIXVAR = 1
        private const val VARFIX = 2

        private const val S_START = 0
        private const val S_STOP = 1
        private const val S_XOVER = 2
        private const val S_FREQ_SCALE = 3
        private const val S_ALTER = 4
        private const val S_NOISE = 5

        private val CEIL_LOG2 = intArrayOf(0, 1, 2, 2, 3, 3)
        private val BW_TAB = floatArrayOf(0.0f, 0.75f, 0.9f, 0.98f)
        private val LIM_GAIN = floatArrayOf(0.70795f, 1.0f, 1.41254f, 10000000000f)
        private val H_SMOOTH = floatArrayOf(0.33333333333333f, 0.30150283239582f, 0.21816949906249f, 0.11516383427084f, 0.03183050093751f)
        private val EXP2_TAB = doubleArrayOf(1.0, Math.sqrt(2.0))
        private const val FLT_EPSILON = 1.1920929e-07f

        /** 2^x(x は整数)。FFmpeg の exp2fi と同じ */
        private fun exp2fi(x: Int): Float = when {
            x in -126..127 -> java.lang.Float.intBitsToFloat((x + 127) shl 23)
            x > 127 -> Float.POSITIVE_INFINITY
            x > -150 -> java.lang.Float.intBitsToFloat(1 shl (x + 149))
            else -> 0f
        }

        private val HUFF = Array(10) { t ->
            val pairs = SbrTables.HUFF[t]
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

        private val DCT_ANA = com.uyatame.ucrt.player.Dct4(64, -2.0 * 32768)
        private val DCT_SYN = com.uyatame.ucrt.player.Dct4(64, 1.0 / (64 * 32768))
    }
}
