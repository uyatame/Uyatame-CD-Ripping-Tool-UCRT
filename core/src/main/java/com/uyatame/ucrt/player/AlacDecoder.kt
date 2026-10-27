package com.uyatame.ucrt.player

import java.io.IOException

/**
 * ALAC(Apple Lossless)のデコーダー(アプリ内蔵)。MP4 / M4A の入れ物も自分で読む。
 *
 * 端末の OS のデコーダーに頼らずに ALAC を PCM にする。結果は元の PCM とビット単位で同じ(可逆圧縮なので)。
 * Apple が公開している ALAC のデコーダー(ag_dec.c・dp_dec.c・matrix_dec.c・ALACDecoder.cpp)と同じ計算をする。
 *
 * 対応: MP4 / M4A / MOV の中の ALAC、1〜8ch、16 / 20 / 24 / 32bit、moov が mdat の前でも後ろでも。
 * 非対応: 分割された MP4(moof)。音声のトラックが ALAC でなければ(AAC など)open() で例外(OS のデコーダーに任せる)。
 *
 * チャンネルの並びは WAV と同じ(L R C LFE Ls Rs …。ffmpeg と同じ)に直して出す。
 */
internal class AlacDecoder(private val src: RandomInput) : PcmDecoder {
    override var sampleRate = 0
        private set
    override var channels = 0
        private set
    override var bitsPerSample = 0
        private set
    /** 全体のサンプル数(1 チャンネルあたり。分からなければ 0) */
    override var totalSamples = 0L
        private set

    /** デコードしたフレーム: samples[ch][0 until blockSize]、最初のサンプルの番号 */
    override var samples: Array<IntArray> = emptyArray()
        private set
    override var blockSize = 0
        private set
    override var frameSample = 0L
        private set

    // ---- ALAC の設定(magic cookie = ALACSpecificConfig) ----
    private var frameLength = 0
    private var pb = 0
    private var mb = 0
    private var kb = 0

    // ---- パケット(MP4 のサンプル)の表 ----
    private var pkOffset = LongArray(0)
    private var pkSize = IntArray(0)
    /** パケットの最初のサンプルの番号(最後に全体の数を足して 1 つ多い) */
    private var pkStart = LongArray(1)
    private var nextPacket = 0

    // ---- MP4 の読み込み ----

    /** ファイルの先頭を読む。ALAC でなければ例外 */
    override fun open() {
        val t = try {
            Mp4Track.open(src, setOf("alac"))
        } catch (e: IOException) {
            throw if (e.message?.startsWith("MP4: audio track is") == true) IOException("MP4: audio track is not ALAC") else e
        }
        val stsd = t.stsd
        // ---- stsd: 'alac' の音声の項目と、その中の 'alac' の箱(ALACSpecificConfig) ----
        val entrySize = u32(stsd, 8).toInt().coerceAtMost(stsd.size - 8)
        val ver = if (entrySize >= 26) u16(stsd, 8 + 16) else 0
        val entryRate = if (entrySize >= 36) (u32(stsd, 8 + 32) ushr 16).toInt() else 0
        val entryEnd = 8 + entrySize
        // 項目の頭は 28 バイト(QuickTime の版 1 は +16、版 2 は +36)。その後ろの箱(wave の中のこともある)から
        // 大きさ 36 の 'alac' を探す
        var p = 8 + 8 + 28 + (if (ver == 1) 16 else if (ver == 2) 36 else 0)
        var cfg = -1
        while (p + 36 <= entryEnd) {
            if (u32(stsd, p) == 36L && i32(stsd, p + 4) == fourcc("alac")) { cfg = p + 12; break }
            p++
        }
        if (cfg < 0) throw IOException("ALAC: no magic cookie")
        frameLength = u32(stsd, cfg).toInt()
        bitsPerSample = stsd[cfg + 5].toInt() and 0xFF
        pb = stsd[cfg + 6].toInt() and 0xFF
        mb = stsd[cfg + 7].toInt() and 0xFF
        kb = stsd[cfg + 8].toInt() and 0xFF
        channels = stsd[cfg + 9].toInt() and 0xFF
        // cfg+10: maxRun(16)、+12: maxFrameBytes(32)、+16: avgBitRate(32)、+20: sampleRate(32)
        sampleRate = u32(stsd, cfg + 20).toInt().let { if (it > 0) it else entryRate }
        if (frameLength <= 0 || frameLength > (1 shl 20)) throw IOException("ALAC: bad frameLength $frameLength")
        if (channels !in 1..8) throw IOException("ALAC: $channels channels not supported")
        if (bitsPerSample !in intArrayOf(16, 20, 24, 32)) throw IOException("ALAC: $bitsPerSample bit not supported")
        if (sampleRate <= 0) throw IOException("ALAC: bad sample rate")

        // ---- パケットの始まり(サンプルの番号) ----
        val count = t.count
        val timescale = t.timescale
        val scale = timescale > 0 && timescale != sampleRate.toLong()
        fun toSample(x: Long): Long = if (scale) x * sampleRate / timescale else x
        val starts = LongArray(count + 1)
        val times = t.times
        if (times != null) {
            for (i in 0..count) starts[i] = toSample(times[i])
        } else {
            // stts が無ければ 1 パケット = frameLength とみなす
            for (i in 0..count) starts[i] = i.toLong() * frameLength
        }
        pkOffset = t.offsets
        pkSize = t.sizes
        pkStart = starts
        totalSamples = starts[count].let { if (it > 0) it else if (t.duration > 0) toSample(t.duration) else 0 }

        samples = Array(channels) { IntArray(frameLength) }
        predictor = Array(2) { IntArray(frameLength) }
        mix = Array(2) { IntArray(frameLength) }
        shiftBuf = Array(2) { IntArray(frameLength) }
        nextPacket = 0
    }

    // ---- ビットの読み込み(パケット 1 つ分をまとめて読む) ----
    private var pk = ByteArray(0)
    /** 次に読むビットの位置 */
    private var bp = 0
    private var pkBits = 0

    /** bp から 32 ビット(足りない分は 0)を見る。位置は動かさない */
    private fun peek32(): Int {
        val i = bp ushr 3
        if (i + 5 > pk.size) throw IOException("ALAC: frame overrun")
        val v = ((pk[i].toLong() and 0xFF) shl 32) or ((pk[i + 1].toLong() and 0xFF) shl 24) or
            ((pk[i + 2].toLong() and 0xFF) shl 16) or ((pk[i + 3].toLong() and 0xFF) shl 8) or (pk[i + 4].toLong() and 0xFF)
        return (v ushr (8 - (bp and 7))).toInt()
    }

    /** n ビット(0〜32)を符号なしで読む */
    private fun bits(n: Int): Int {
        if (n == 0) return 0
        val v = peek32() ushr (32 - n)
        bp += n
        return v
    }

    // ---- フレーム ----

    private var predictor: Array<IntArray> = emptyArray()
    private var mix: Array<IntArray> = emptyArray()
    private var shiftBuf: Array<IntArray> = emptyArray()
    private val coefs = Array(2) { IntArray(32) }
    private val mode = IntArray(2)
    private val denShift = IntArray(2)
    private val pbFactor = IntArray(2)
    private val numCoefs = IntArray(2)

    /** 次のパケットをデコードする。終わりなら false */
    override fun decodeNext(): Boolean {
        val i = nextPacket
        if (i >= pkSize.size) return false
        val size = pkSize[i]
        if (size <= 0 || size > (16 shl 20)) throw IOException("ALAC: bad packet size $size")
        if (pk.size < size + 8) pk = ByteArray(size + 8)
        var off = 0
        while (off < size) {
            val r = src.read(pkOffset[i] + off, pk, off, size - off)
            if (r <= 0) return false // 途中で切れたファイル
            off += r
        }
        java.util.Arrays.fill(pk, size, size + 8, 0)
        pkBits = size * 8
        decodeFrame()
        frameSample = pkStart[i]
        nextPacket = i + 1
        return true
    }

    private fun decodeFrame() {
        bp = 0
        var ch = 0
        var n = -1
        val offsets = CHANNEL_OFFSETS[channels - 1]
        while (true) {
            if (bp + 3 > pkBits) throw IOException("ALAC: no END element")
            when (val tag = bits(3)) {
                ID_SCE, ID_LFE, ID_CPE -> {
                    val nch = if (tag == ID_CPE) 2 else 1
                    if (ch + nch > channels || offsets[ch] + nch > channels) throw IOException("ALAC: too many channels in frame")
                    val ns = element(nch, offsets[ch])
                    if (n >= 0 && ns != n) throw IOException("ALAC: sample count mismatch")
                    n = ns
                    ch += nch
                }
                ID_DSE -> {
                    bits(4) // elementInstanceTag
                    val align = bits(1)
                    var count = bits(8)
                    if (count == 255) count += bits(8)
                    if (align != 0) bp = (bp + 7) and 7.inv()
                    bp += count * 8
                }
                ID_FIL -> {
                    var count = bits(4)
                    if (count == 15) count += bits(8) - 1
                    bp += count * 8
                }
                ID_END -> break
                else -> throw IOException("ALAC: unsupported element $tag")
            }
            if (bp > pkBits) throw IOException("ALAC: frame overrun")
            if (ch >= channels) break
        }
        if (ch < channels) throw IOException("ALAC: missing channels")
        blockSize = n
    }

    /** SCE / LFE(nch = 1)、CPE(nch = 2)を 1 つデコードし、samples[out] 〜 に書く。サンプルの数を返す */
    private fun element(nch: Int, out: Int): Int {
        bits(4) // elementInstanceTag
        bits(12) // 使われていない
        val hb = bits(4)
        val partial = hb ushr 3
        var bytesShifted = (hb ushr 1) and 3
        val escape = hb and 1
        if (bytesShifted == 3) throw IOException("ALAC: bad bytesShifted")
        var n = frameLength
        if (partial != 0) {
            n = bits(32)
            if (n < 0 || n > frameLength) throw IOException("ALAC: bad frame size $n")
        }
        var mixBits = 0
        var mixRes = 0
        if (escape == 0) {
            val chanBits = bitsPerSample - bytesShifted * 8 + (nch - 1)
            if (chanBits > 32) throw IOException("ALAC: chanBits $chanBits")
            mixBits = bits(8)
            mixRes = bits(8).toByte().toInt()
            for (c in 0 until nch) {
                var b = bits(8)
                mode[c] = b ushr 4
                denShift[c] = b and 15
                b = bits(8)
                pbFactor[c] = b ushr 5
                numCoefs[c] = b and 31
                for (j in 0 until numCoefs[c]) coefs[c][j] = bits(16).toShort().toInt()
            }
            // 下位のバイト(24 / 32bit)はここにまとめて置かれている。あとで読む
            val shiftPos = bp
            val shiftBits = bytesShifted * 8
            bp += shiftBits * n * nch
            for (c in 0 until nch) {
                agDecode(predictor[c], n, chanBits, (pb * pbFactor[c]) / 4)
                if (mode[c] != 0) unpc31(predictor[c], n, chanBits) // 1 次の予測を先にかける
                unpc(predictor[c], mix[c], n, coefs[c], numCoefs[c], chanBits, denShift[c])
            }
            if (shiftBits != 0) {
                val end = bp
                bp = shiftPos
                if (nch == 1) {
                    val s = shiftBuf[0]
                    for (i in 0 until n) s[i] = bits(shiftBits)
                } else {
                    val s0 = shiftBuf[0]
                    val s1 = shiftBuf[1]
                    for (i in 0 until n) { s0[i] = bits(shiftBits); s1[i] = bits(shiftBits) }
                }
                bp = end
            }
        } else {
            // 圧縮していないフレーム: bitsPerSample ビットのまま、チャンネルを交互に
            val bd = bitsPerSample
            val sh = 32 - bd
            for (i in 0 until n) for (c in 0 until nch) mix[c][i] = (bits(bd) shl sh) shr sh
            bytesShifted = 0
        }

        // ---- 出力: ステレオの戻し(unmix)と、下位のバイトを付ける ----
        val shift = bytesShifted * 8
        if (nch == 1) {
            val u = mix[0]
            val o = samples[out]
            if (shift == 0) System.arraycopy(u, 0, o, 0, n)
            else { val s = shiftBuf[0]; for (i in 0 until n) o[i] = (u[i] shl shift) or s[i] }
        } else {
            val u = mix[0]
            val v = mix[1]
            val l = samples[out]
            val r = samples[out + 1]
            if (mixRes != 0) {
                for (i in 0 until n) {
                    val a = u[i] + v[i] - ((mixRes * v[i]) shr mixBits)
                    l[i] = a
                    r[i] = a - v[i]
                }
            } else {
                System.arraycopy(u, 0, l, 0, n)
                System.arraycopy(v, 0, r, 0, n)
            }
            if (shift != 0) {
                val s0 = shiftBuf[0]
                val s1 = shiftBuf[1]
                for (i in 0 until n) {
                    l[i] = (l[i] shl shift) or s0[i]
                    r[i] = (r[i] shl shift) or s1[i]
                }
            }
        }
        return n
    }

    // ---- 適応ゴロム符号(ag_dec.c の dyn_decomp) ----

    /**
     * 値を 1 つ読む(dyn_get / dyn_get_32bit)。先頭の 1 の数 pre が 9 以上なら、そのあと escBits ビットの生の値。
     * そうでなければ 0 を 1 つ飛ばして k ビットを v として読み、v >= 2 なら pre * m + v - 1、
     * v < 2 なら pre * m(このときは k - 1 ビットだけ進める)
     */
    private fun agGet(m: Int, k: Int, escBits: Int): Int {
        val pre = Integer.numberOfLeadingZeros(peek32().inv())
        if (pre >= MAX_PREFIX) {
            bp += MAX_PREFIX
            return bits(escBits)
        }
        bp += pre + 1
        if (k <= 1) return pre * m
        val v = peek32() ushr (32 - k)
        return if (v >= 2) {
            bp += k
            pre * m + v - 1
        } else {
            bp += k - 1
            pre * m
        }
    }

    private fun agDecode(out: IntArray, n: Int, maxBits: Int, pbv: Int) {
        var mb = this.mb // 平均の値(QB = 1 << 9 倍)
        val wb = (1 shl kb) - 1
        var zmode = 0
        var c = 0
        while (c < n) {
            var k = 31 - Integer.numberOfLeadingZeros((mb ushr QBSHIFT) + 3)
            if (k > kb) k = kb
            val v = agGet((1 shl k) - 1, k, maxBits)
            val nd = v + zmode
            out[c++] = (nd ushr 1) xor -(nd and 1) // 最下位のビットが符号
            mb = pbv * nd + mb - ((pbv * mb) ushr QBSHIFT)
            if (Integer.compareUnsigned(v, N_MAX_MEAN_CLAMP) > 0) mb = N_MAX_MEAN_CLAMP
            zmode = 0
            // 平均が小さいときは、0 が続く数が入っている
            if (Integer.compareUnsigned(mb shl MMULSHIFT, QB) < 0 && c < n) {
                zmode = 1
                val k2 = Integer.numberOfLeadingZeros(mb) - BITOFF + ((mb + MOFF) ushr MDENSHIFT)
                val run = agGet(((1 shl k2) - 1) and wb, k2, 16)
                if (run < 0 || run > n - c) throw IOException("ALAC: bad zero run")
                java.util.Arrays.fill(out, c, c + run, 0)
                c += run
                if (run >= 65535) zmode = 0
                mb = 0
            }
        }
    }

    // ---- 予測の戻し(dp_dec.c の unpc_block) ----

    /** 1 次の予測(numactive == 31)。その場で書き換える */
    private fun unpc31(x: IntArray, n: Int, chanBits: Int) {
        val cs = 32 - chanBits
        var prev = x[0]
        for (j in 1 until n) {
            prev = ((x[j] + prev) shl cs) shr cs
            x[j] = prev
        }
    }

    private fun unpc(pc: IntArray, out: IntArray, n: Int, coefs: IntArray, numActive: Int, chanBits: Int, denShift: Int) {
        if (n <= 0) return
        val cs = 32 - chanBits
        out[0] = pc[0]
        if (numActive == 0) {
            System.arraycopy(pc, 1, out, 1, n - 1)
            return
        }
        if (numActive == 31) {
            var prev = out[0]
            for (j in 1 until n) {
                prev = ((pc[j] + prev) shl cs) shr cs
                out[j] = prev
            }
            return
        }
        val lim0 = minOf(numActive + 1, n)
        for (j in 1 until lim0) out[j] = ((pc[j] + out[j - 1]) shl cs) shr cs
        val lim = numActive + 1
        val denHalf = if (denShift == 0) 0 else 1 shl (denShift - 1)
        for (j in lim until n) {
            val top = out[j - lim]
            val base = j - 1 // pout[-k] = out[base - k]
            var sum = 0
            for (k in 0 until numActive) sum += coefs[k] * (out[base - k] - top)
            var del = pc[j]
            var del0 = del
            val sg = Integer.signum(del)
            del += top + ((sum + denHalf) shr denShift)
            out[j] = (del shl cs) shr cs
            // 係数を少しずつ動かす(適応)
            if (sg > 0) {
                var k = numActive - 1
                while (k >= 0) {
                    val dd = top - out[base - k]
                    val sgn = Integer.signum(dd)
                    coefs[k] = (coefs[k] - sgn).toShort().toInt()
                    del0 -= (numActive - k) * ((sgn * dd) shr denShift)
                    if (del0 <= 0) break
                    k--
                }
            } else if (sg < 0) {
                var k = numActive - 1
                while (k >= 0) {
                    val dd = top - out[base - k]
                    val sgn = Integer.signum(dd)
                    coefs[k] = (coefs[k] + sgn).toShort().toInt()
                    del0 -= (numActive - k) * ((-sgn * dd) shr denShift)
                    if (del0 >= 0) break
                    k--
                }
            }
        }
    }

    // ---- 途中から ----

    /**
     * target(サンプル番号)を含むパケットの手前に移る。
     * 次の decodeNext() からそのパケットが出る(frameSample で始まりが分かるので、手前の分は呼び出し側で捨てる)
     */
    override fun seekSample(target: Long) {
        val n = pkSize.size
        if (target <= 0 || n == 0) { nextPacket = 0; return }
        if (target >= pkStart[n]) { nextPacket = n; return }
        // pkStart[i] <= target となる最後の i
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pkStart[mid] <= target) lo = mid else hi = mid - 1
        }
        nextPacket = lo
    }

    companion object {
        private const val ID_SCE = 0
        private const val ID_CPE = 1
        private const val ID_LFE = 3
        private const val ID_DSE = 4
        private const val ID_FIL = 6
        private const val ID_END = 7

        private const val QBSHIFT = 9
        private const val QB = 1 shl QBSHIFT
        private const val MMULSHIFT = 2
        private const val MDENSHIFT = QBSHIFT - MMULSHIFT - 1
        private const val MOFF = 1 shl (MDENSHIFT - 2)
        private const val BITOFF = 24
        private const val MAX_PREFIX = 9
        private const val N_MAX_MEAN_CLAMP = 0xFFFF

        /**
         * ビットストリームのチャンネルの順番 → 出力(WAV の順番)の位置(ffmpeg の ff_alac_channel_layout_offsets と同じ)。
         * ALAC の並び: 3ch = C L R、5.1ch = C L R Ls Rs LFE など
         */
        private val CHANNEL_OFFSETS = arrayOf(
            intArrayOf(0),
            intArrayOf(0, 1),
            intArrayOf(2, 0, 1),
            intArrayOf(2, 0, 1, 3),
            intArrayOf(2, 0, 1, 3, 4),
            intArrayOf(2, 0, 1, 4, 5, 3),
            intArrayOf(2, 0, 1, 4, 5, 6, 3),
            intArrayOf(2, 6, 7, 0, 1, 4, 5, 3),
        )

        private fun fourcc(s: String): Int =
            (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code

        private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        private fun i32(b: ByteArray, o: Int): Int = (u16(b, o) shl 16) or u16(b, o + 2)
        private fun u32(b: ByteArray, o: Int): Long = i32(b, o).toLong() and 0xFFFFFFFFL
    }
}
