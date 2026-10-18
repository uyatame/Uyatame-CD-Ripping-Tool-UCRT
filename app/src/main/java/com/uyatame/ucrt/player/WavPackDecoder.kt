package com.uyatame.ucrt.player

import java.io.IOException

/**
 * WavPack(.wv)のデコーダー(アプリ内蔵)。
 *
 * WavPack 4.x / 5.x のブロック("wvpk")を読んで PCM にする。可逆圧縮のファイルは元の PCM とビット単位で同じ
 * (ffmpeg の wavpack デコーダーと同じ結果になることを確かめてある)。
 *
 * 対応: 1ch〜多チャンネル(1 フレームに複数のブロック)、8〜32bit の整数、32bit の float、
 * ジョイントステレオ、全部のデコリレーション(-1〜-3, 1〜8, 17, 18)、"extra" モード、
 * モノラルを 2ch にしたブロック(false stereo)、下位の 0 のビット(shift・int32 の情報)、
 * 32bit の整数・float で別に送られる下位のビット(WVX)。
 * ハイブリッド(非可逆)のファイルは .wv の分だけでデコードする(.wvc の補正は使わない。ffmpeg と同じ)。
 * DSD は非対応(例外)。
 *
 * float は 32bit の整数にして出す(-1.0〜1.0 を ±2^31 に。ffmpeg の float → s32 の変換と同じ丸め)。
 */
internal class WavPackDecoder(private val src: RandomInput) : PcmDecoder {
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

    /** CRC が合わなかったブロックの数(データは出す) */
    var crcErrors = 0
        private set

    /** 最初のブロックの位置 */
    private var firstBlock = 0L
    /** 最初のブロックのサンプル番号(普通は 0) */
    private var baseIndex = 0L
    /** 次に読むブロックの位置 */
    private var pos = 0L
    private var isFloat = false

    // ---- ブロックのヘッダー(32 バイト) ----
    private val hb = ByteArray(32)
    private var hCk = 0
    private var hIdx = 0L
    private var hTotal = 0L
    private var hSamples = 0
    private var hFlags = 0
    private var hCrc = 0

    private fun readFully(p: Long, b: ByteArray, off: Int, len: Int): Int {
        var o = 0
        while (o < len) {
            val r = src.read(p + o, b, off + o, len - o)
            if (r <= 0) break
            o += r
        }
        return o
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

    /** p にブロックのヘッダーがあれば読んで true(libwavpack と同じ確かめ方) */
    private fun header(p: Long): Boolean {
        if (p < 0 || readFully(p, hb, 0, 32) < 32) return false
        val b = hb
        if (b[0] != 'w'.code.toByte() || b[1] != 'v'.code.toByte() || b[2] != 'p'.code.toByte() || b[3] != 'k'.code.toByte()) return false
        val ck = le32(b, 4)
        val ver = le16(b, 8)
        // 大きさは偶数で 24 以上 16MB 未満、版は 0x402〜0x410、1 ブロックのサンプル数は 0x30000 未満
        if ((ck and 1) != 0 || ck < 24 || ck >= 0x1000000 || ver < 0x402 || ver > 0x410) return false
        if ((b[22].toInt() and 0xFF) >= 3 || b[23].toInt() != 0) return false
        val ns = le32(b, 20)
        if (ns < 0 || ns >= MAX_BLOCK_SAMPLES) return false
        hCk = ck
        val tot = le32(b, 12).toLong() and 0xFFFFFFFFL
        val tu8 = (b[11].toInt() and 0xFF).toLong()
        // 40bit に広げた値(5.x)。下位 32bit がすべて 1 なら分からない
        hTotal = if (tot == 0xFFFFFFFFL) -1 else tot + (tu8 shl 32) - tu8
        hIdx = (le32(b, 16).toLong() and 0xFFFFFFFFL) + ((b[10].toLong() and 0xFF) shl 32)
        hSamples = ns
        hFlags = le32(b, 24)
        hCrc = le32(b, 28)
        return true
    }

    private val scan = ByteArray(1 shl 16)

    /** from 以降で最初のブロックの位置(ヘッダーも読む)。無ければ -1 */
    private fun findBlock(from: Long): Long {
        if (header(from)) return from
        var q = from
        while (q < src.size) {
            val n = readFully(q, scan, 0, scan.size)
            if (n < 4) return -1
            for (i in 0..n - 4) {
                if (scan[i] == 'w'.code.toByte() && scan[i + 1] == 'v'.code.toByte() &&
                    scan[i + 2] == 'p'.code.toByte() && scan[i + 3] == 'k'.code.toByte() && header(q + i)
                ) return q + i
            }
            q += n - 3
        }
        return -1
    }

    // ---- 先頭 ----

    /** ファイルの先頭を読む。WavPack でなければ例外 */
    override fun open() {
        var p = findBlock(0)
        if (p < 0) throw IOException("WavPack: no block")
        totalSamples = 0
        var total = hTotal
        // 音の無いブロック(ヘッダーの情報だけなど)を飛ばして、最初のフレームの頭へ
        while (hSamples == 0 || (hFlags and INITIAL) == 0) {
            if (total < 0) total = hTotal
            p = findBlock(p + 8 + hCk)
            if (p < 0) throw IOException("WavPack: no audio")
        }
        if (total < 0) total = hTotal
        if ((hFlags and DSD) != 0) throw IOException("WavPack: DSD not supported")
        firstBlock = p
        baseIndex = hIdx
        isFloat = (hFlags and FLOAT) != 0
        bitsPerSample = if (isFloat) 32 else ((hFlags and 3) + 1) * 8
        val srIdx = (hFlags ushr 23) and 15
        sampleRate = if (srIdx < 15) RATES[srIdx] else 0
        if (srIdx == 15) {
            // 表に無いサンプリング周波数は、ブロックの中の ID_SAMPLE_RATE に入っている
            val len = 8 + hCk
            val b = ByteArray(len)
            if (readFully(p, b, 0, len) == len) {
                eachMeta(b, len) { id, o, n -> if (id == ID_SAMPLE_RATE && n >= 3) sampleRate = le16(b, o) or ((b[o + 2].toInt() and 0xFF) shl 16) }
            }
        }
        if (sampleRate <= 0) throw IOException("WavPack: unknown sample rate")
        // チャンネル数: 最初のフレームのブロック(INITIAL〜FINAL)のチャンネルを足す
        var ch = 0
        var q = p
        while (true) {
            ch += if ((hFlags and MONO) != 0 && (hFlags and FALSE_STEREO) == 0) 1 else 2
            if ((hFlags and FINAL) != 0) break
            q += 8 + hCk
            if (!header(q)) break
        }
        if (ch > MAX_CHANNELS) throw IOException("WavPack: too many channels")
        channels = ch
        if (total < 0) {
            // 全体のサンプル数が書かれていない: 最後のブロックまでたどる
            q = p
            total = 0
            while (q >= 0 && header(q)) {
                if (hSamples > 0) total = maxOf(total, hIdx + hSamples)
                q += 8 + hCk
            }
            totalSamples = (total - baseIndex).coerceAtLeast(0)
        } else {
            totalSamples = total
        }
        pos = firstBlock
    }

    /** ブロックの中のメタデータ(サブブロック)を順に渡す: id(下位 6bit)、データの位置、バイト数 */
    private inline fun eachMeta(b: ByteArray, len: Int, f: (id: Int, off: Int, n: Int) -> Unit) {
        var o = 32
        while (o + 2 <= len) {
            val id = b[o].toInt() and 0xFF
            var words = b[o + 1].toInt() and 0xFF
            o += 2
            if ((id and ID_LARGE) != 0) {
                if (o + 2 > len) return
                words = words or ((b[o].toInt() and 0xFF) shl 8) or ((b[o + 1].toInt() and 0xFF) shl 16)
                o += 2
            }
            val size = words * 2
            val n = if ((id and ID_ODD) != 0) size - 1 else size
            if (n < 0 || o + size > len) return
            f(id and 0x3F, o, n)
            o += size
        }
    }

    // ---- フレーム ----

    private var blk = ByteArray(1 shl 16)
    /** 置き場の無いチャンネル(ヘッダーと合わないファイル)を捨てる所 */
    private var junk = IntArray(0)

    /** 次のフレーム(1〜複数のブロック)をデコードする。終わりなら false */
    override fun decodeNext(): Boolean {
        while (true) {
            val p = findBlock(pos)
            if (p < 0) return false
            if (hSamples == 0 || (hFlags and INITIAL) == 0) {
                // 音の無いブロック、またはフレームの途中のブロック: 飛ばす
                pos = p + 8 + hCk
                continue
            }
            if ((hFlags and DSD) != 0) throw IOException("WavPack: DSD not supported")
            val n = hSamples
            val idx = hIdx
            if (samples.size != channels || samples[0].size < n) {
                if (channels.toLong() * n > (1 shl 24)) throw IOException("WavPack: block too large")
                samples = Array(channels) { IntArray(n) }
            }
            var ch = 0
            var q = p
            while (true) {
                val len = 8 + hCk
                if (blk.size < len) blk = ByteArray(len)
                if (readFully(q, blk, 0, len) < len) return false // 途中で切れている
                val flags = hFlags
                val crc = hCrc
                val stereo = (flags and MONO) == 0 || (flags and FALSE_STEREO) != 0
                val l = outArray(ch, n)
                val r = if (stereo) outArray(ch + 1, n) else l
                decodeBlock(blk, len, flags, n, l, r, crc)
                ch += if (stereo) 2 else 1
                q += len
                if ((flags and FINAL) != 0) break
                // 同じフレームの次のブロック
                if (!header(q) || hIdx != idx || hSamples != n || (hFlags and INITIAL) != 0) break
            }
            // 足りないチャンネルは無音に
            for (c in ch until channels) java.util.Arrays.fill(samples[c], 0, n, 0)
            pos = q
            blockSize = n
            frameSample = idx - baseIndex
            return true
        }
    }

    private fun outArray(ch: Int, n: Int): IntArray {
        if (ch < channels) return samples[ch]
        if (junk.size < n) junk = IntArray(n)
        return junk
    }

    // ---- ブロックの状態 ----

    private var terms = 0
    private val tValue = IntArray(MAX_TERMS)
    private val tDelta = IntArray(MAX_TERMS)
    private val wA = IntArray(MAX_TERMS)
    private val wB = IntArray(MAX_TERMS)
    private val sA = Array(MAX_TERMS) { IntArray(8) }
    private val sB = Array(MAX_TERMS) { IntArray(8) }

    /** 中央値の目安: med[ch * 3 + 0..2] */
    private val med = IntArray(6)
    private val slow = IntArray(2)
    private val errLimit = IntArray(2)
    private val brAcc = IntArray(2)
    private val brDelta = IntArray(2)

    private var hybrid = false
    private var hybridBitrate = false
    private var stereoIn = false
    private var holdZero = false
    private var holdOne = false
    private var zeros = 0

    private var extraBits = 0
    private var andMask = 0
    private var orMask = 0
    private var shift = 0
    private var gotExtra = false
    private var crcExtraWant = 0
    private var maxClip = 0L
    private var minClip = 0L

    private var floatFlag = 0
    private var floatShift = 0
    private var floatMaxExp = 0

    private val bs = Bits()
    private val ebs = Bits()

    /** 1 ブロックをデコードして l(と r)に出す */
    private fun decodeBlock(b: ByteArray, len: Int, flags: Int, n: Int, l: IntArray, r: IntArray, crcWant: Int) {
        if ((flags and DSD) != 0) throw IOException("WavPack: DSD not supported")
        val bytes = (flags and 3) + 1
        val origBpp = bytes * 8
        val mono = (flags and MONO) != 0
        val stereo = !mono || (flags and FALSE_STEREO) != 0
        stereoIn = !mono && (flags and FALSE_STEREO) == 0
        hybrid = (flags and HYBRID) != 0
        hybridBitrate = (flags and HYBRID_BITRATE) != 0
        val flt = (flags and FLOAT) != 0
        // 出すときに左へずらす量(下位の 0 のビット + ファイル全体のビット数とのちがい)
        var postShift = ((flags ushr 13) and 31) + bitsPerSample - origBpp
        maxClip = (1L shl (origBpp - 1)) - 1
        minClip = -(1L shl (origBpp - 1))

        terms = 0
        for (i in 0 until MAX_TERMS) {
            wA[i] = 0; wB[i] = 0; tValue[i] = 0; tDelta[i] = 0
            java.util.Arrays.fill(sA[i], 0)
            java.util.Arrays.fill(sB[i], 0)
        }
        java.util.Arrays.fill(med, 0)
        java.util.Arrays.fill(slow, 0)
        java.util.Arrays.fill(errLimit, 0)
        java.util.Arrays.fill(brAcc, 0)
        java.util.Arrays.fill(brDelta, 0)
        extraBits = 0; andMask = 0; orMask = 0; shift = 0
        gotExtra = false
        floatFlag = 0; floatShift = 0; floatMaxExp = 0
        var gotBs = false

        eachMeta(b, len) { id, o, sz ->
            when (id) {
                ID_DECORR_TERMS -> {
                    if (sz > MAX_TERMS) throw IOException("WavPack: too many terms")
                    terms = sz
                    for (i in 0 until sz) {
                        val v = b[o + i].toInt() and 0xFF
                        val t = (v and 0x1F) - 5
                        // 1〜8, 17, 18 と(2ch のとき)-1〜-3 だけ
                        if (!(t in 1..8 || t == 17 || t == 18 || (stereoIn && t in -3..-1))) throw IOException("WavPack: bad term $t")
                        // 書かれている順の逆に使う
                        tValue[sz - 1 - i] = t
                        tDelta[sz - 1 - i] = v ushr 5
                    }
                }
                ID_DECORR_WEIGHTS -> {
                    val cnt = sz shr (if (stereoIn) 1 else 0)
                    if (cnt > terms) throw IOException("WavPack: bad weights")
                    var k = o
                    for (i in 0 until cnt) {
                        val j = terms - 1 - i
                        wA[j] = restoreWeight(b[k++].toInt())
                        if (stereoIn) wB[j] = restoreWeight(b[k++].toInt())
                    }
                }
                ID_DECORR_SAMPLES -> {
                    var k = o
                    val end = o + sz
                    var i = terms - 1
                    while (i >= 0 && k < end) {
                        val t = tValue[i]
                        // 壊れたブロックで、サブブロックの外(配列の外)を読まないように
                        val need = if (t > 8) (if (stereoIn) 8 else 4) else if (t < 0) 4 else t * (if (stereoIn) 4 else 2)
                        if (k + need > end) break
                        if (t > 8) {
                            sA[i][0] = exp2s(le16(b, k)); sA[i][1] = exp2s(le16(b, k + 2)); k += 4
                            if (stereoIn) { sB[i][0] = exp2s(le16(b, k)); sB[i][1] = exp2s(le16(b, k + 2)); k += 4 }
                        } else if (t < 0) {
                            sA[i][0] = exp2s(le16(b, k)); sB[i][0] = exp2s(le16(b, k + 2)); k += 4
                        } else {
                            for (j in 0 until t) {
                                sA[i][j] = exp2s(le16(b, k)); k += 2
                                if (stereoIn) { sB[i][j] = exp2s(le16(b, k)); k += 2 }
                            }
                        }
                        i--
                    }
                }
                ID_ENTROPY_VARS -> {
                    if (sz != 6 * (if (stereoIn) 2 else 1)) throw IOException("WavPack: bad entropy vars")
                    for (i in 0 until 3) med[i] = exp2s(le16(b, o + i * 2))
                    if (stereoIn) for (i in 0 until 3) med[3 + i] = exp2s(le16(b, o + 6 + i * 2))
                }
                ID_HYBRID_PROFILE -> {
                    var k = o
                    var left = sz
                    val nc = if (stereoIn) 2 else 1
                    if (sz < nc * (if (hybridBitrate) 4 else 2)) throw IOException("WavPack: bad hybrid profile")
                    if (hybridBitrate) for (i in 0 until nc) { slow[i] = exp2s(le16(b, k)); k += 2; left -= 2 }
                    for (i in 0 until nc) { brAcc[i] = le16(b, k) shl 16; k += 2; left -= 2 }
                    if (left > 0) for (i in 0 until nc) { if (k + 2 > o + sz) break; brDelta[i] = exp2s(le16(b, k)); k += 2 }
                }
                ID_INT32_INFO -> {
                    if (sz == 4) {
                        val sent = b[o].toInt() and 0xFF
                        val zs = b[o + 1].toInt() and 0xFF
                        val ones = b[o + 2].toInt() and 0xFF
                        val dups = b[o + 3].toInt() and 0xFF
                        if (sent > 30) throw IOException("WavPack: bad int32 info")
                        when {
                            sent != 0 -> extraBits = sent
                            zs != 0 -> shift = zs
                            ones != 0 -> { andMask = 1; orMask = 1; shift = ones }
                            dups != 0 -> { andMask = 1; shift = dups }
                        }
                        if (shift > 31) throw IOException("WavPack: bad int32 info")
                        // 32bit のハイブリッドは 24bit として切り詰める(元の WavPack と同じ)
                        if (hybrid && bytes == 4 && postShift < 8 && shift > 8) {
                            postShift += 8; shift -= 8; maxClip = maxClip shr 8; minClip = minClip shr 8
                        }
                    }
                }
                ID_FLOAT_INFO -> {
                    if (sz == 4) {
                        floatFlag = b[o].toInt() and 0xFF
                        floatShift = b[o + 1].toInt() and 0xFF
                        floatMaxExp = b[o + 2].toInt() and 0xFF
                        if (floatShift > 31) throw IOException("WavPack: bad float info")
                    }
                }
                ID_WV_BITSTREAM -> {
                    bs.init(b, o, o + sz)
                    gotBs = true
                }
                ID_WVX_BITSTREAM -> {
                    if (sz > 4) {
                        ebs.init(b, o, o + sz)
                        crcExtraWant = ebs.bits(32)
                        gotExtra = true
                    }
                }
                ID_DSD_BLOCK -> throw IOException("WavPack: DSD not supported")
            }
        }
        if (!gotBs) throw IOException("WavPack: no bitstream")
        if (flt && !isFloat) throw IOException("WavPack: float block in integer file")

        holdZero = false; holdOne = false; zeros = 0
        val crc: Int
        if (stereoIn) {
            crc = unpackStereo(n, l, r, (flags and JOINT) != 0, bytes > 2 || flt, flt, postShift)
        } else {
            crc = unpackMono(n, l, bytes > 2 || flt, flt, postShift)
            if (stereo && r !== l) System.arraycopy(l, 0, r, 0, n)
        }
        if (crc != crcWant || (gotExtra && crcExtra != crcExtraWant)) crcErrors++
    }

    private var crcExtra = 0

    /** 2ch のブロック。戻り値は CRC */
    private fun unpackStereo(n: Int, outL: IntArray, outR: IntArray, joint: Boolean, wide: Boolean, flt: Boolean, postShift: Int): Int {
        var crc = -1
        crcExtra = -1
        var p = 0
        for (s in 0 until n) {
            var l = getValue(0)
            var r = getValue(1)
            for (i in 0 until terms) {
                val t = tValue[i]
                val d = tDelta[i]
                if (t > 0) {
                    val a: Int
                    val bb: Int
                    val j: Int
                    val xa = sA[i]
                    val xb = sB[i]
                    if (t > 8) {
                        if ((t and 1) != 0) {
                            a = 2 * xa[0] - xa[1]
                            bb = 2 * xb[0] - xb[1]
                        } else {
                            a = (3 * xa[0] - xa[1]) shr 1
                            bb = (3 * xb[0] - xb[1]) shr 1
                        }
                        xa[1] = xa[0]
                        xb[1] = xb[0]
                        j = 0
                    } else {
                        a = xa[p]
                        bb = xb[p]
                        j = (p + t) and 7
                    }
                    val l2 = l + weight(wA[i], a, wide)
                    val r2 = r + weight(wB[i], bb, wide)
                    if (a != 0 && l != 0) wA[i] -= ((((l xor a) shr 30) and 2) - 1) * d
                    if (bb != 0 && r != 0) wB[i] -= ((((r xor bb) shr 30) and 2) - 1) * d
                    l = l2; r = r2
                    xa[j] = l
                    xb[j] = r
                } else if (t == -1) {
                    val l2 = l + weight(wA[i], sA[i][0], wide)
                    wA[i] = clipWeight(wA[i], d, sA[i][0], l)
                    l = l2
                    val r2 = r + weight(wB[i], l2, wide)
                    wB[i] = clipWeight(wB[i], d, l2, r)
                    r = r2
                    sA[i][0] = r
                } else {
                    val r2 = r + weight(wB[i], sB[i][0], wide)
                    wB[i] = clipWeight(wB[i], d, sB[i][0], r)
                    r = r2
                    var x = r2
                    if (t == -3) {
                        x = sA[i][0]
                        sA[i][0] = r
                    }
                    val l2 = l + weight(wA[i], x, wide)
                    wA[i] = clipWeight(wA[i], d, x, l)
                    l = l2
                    sB[i][0] = l
                }
            }
            p = (p + 1) and 7
            if (joint) {
                r -= l shr 1
                l += r
            }
            crc = (crc * 3 + l) * 3 + r
            if (flt) {
                outL[s] = floatValue(l)
                outR[s] = floatValue(r)
            } else {
                outL[s] = intValue(l, postShift)
                outR[s] = intValue(r, postShift)
            }
        }
        return crc
    }

    /** 1ch のブロック。戻り値は CRC */
    private fun unpackMono(n: Int, out: IntArray, wide: Boolean, flt: Boolean, postShift: Int): Int {
        var crc = -1
        crcExtra = -1
        var p = 0
        for (s in 0 until n) {
            var v = getValue(0)
            for (i in 0 until terms) {
                val t = tValue[i]
                val xa = sA[i]
                val a: Int
                val j: Int
                if (t > 8) {
                    a = if ((t and 1) != 0) 2 * xa[0] - xa[1] else (3 * xa[0] - xa[1]) shr 1
                    xa[1] = xa[0]
                    j = 0
                } else {
                    a = xa[p]
                    j = (p + t) and 7
                }
                val v2 = v + weight(wA[i], a, wide)
                if (a != 0 && v != 0) wA[i] -= ((((v xor a) shr 30) and 2) - 1) * tDelta[i]
                v = v2
                xa[j] = v
            }
            p = (p + 1) and 7
            crc = crc * 3 + v
            out[s] = if (flt) floatValue(v) else intValue(v, postShift)
        }
        return crc
    }

    /** 重みをかける(16bit 以下は 32bit の計算で。ffmpeg と同じ) */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun weight(w: Int, s: Int, wide: Boolean): Int =
        if (wide) ((w.toLong() * s + 512) shr 10).toInt() else (w * s + 512) shr 10

    @Suppress("NOTHING_TO_INLINE")
    private inline fun clipWeight(w: Int, d: Int, s: Int, v: Int): Int {
        if (s == 0 || v == 0) return w
        return if ((s xor v) < 0) maxOf(w - d, -1024) else minOf(w + d, 1024)
    }

    /** 整数のサンプルの仕上げ(下位のビットを戻す) */
    private fun intValue(v0: Int, postShift: Int): Int {
        var v = v0
        if (extraBits != 0) {
            v = v shl extraBits
            if (gotExtra) {
                v = v or ebs.bits(extraBits)
                crcExtra = crcExtra * 9 + (v and 0xFFFF) * 3 + (v ushr 16)
            }
        }
        val bit = (v and andMask) or orMask
        var x = ((v + bit) shl shift) - bit
        if (hybrid) x = x.toLong().coerceIn(minClip, maxClip).toInt()
        return if (postShift >= 0) x shl postShift else x shr -postShift
    }

    /** float のサンプルを組み立て、32bit の整数にする */
    private fun floatValue(s0: Int): Int {
        var s = s0
        var exp = floatMaxExp
        var sign: Int
        if (s != 0) {
            s = s shl floatShift
            sign = if (s < 0) 1 else 0
            if (s < 0) s = -s
            if ((s.toLong() and 0xFFFFFFFFL) >= 0x1000000L) {
                s = if (gotExtra && ebs.bit() != 0) ebs.bits(23) else 0
                exp = 255
            } else if (exp != 0) {
                var sh = 23 - (31 - Integer.numberOfLeadingZeros(s))
                exp = floatMaxExp
                if (exp <= sh) { exp--; sh = exp }
                exp -= sh
                if (sh != 0) {
                    s = s shl sh
                    if ((floatFlag and FLT_SHIFT_ONES) != 0 ||
                        (gotExtra && (floatFlag and FLT_SHIFT_SAME) != 0 && ebs.bit() != 0)
                    ) {
                        s = s or ((1 shl sh) - 1)
                    } else if (gotExtra && (floatFlag and FLT_SHIFT_SENT) != 0) {
                        s = s or ebs.bits(sh)
                    }
                }
            } else {
                exp = floatMaxExp
            }
            s = s and 0x7FFFFF
        } else {
            sign = 0
            exp = 0
            if (gotExtra && (floatFlag and FLT_ZERO_SENT) != 0) {
                if (ebs.bit() != 0) {
                    s = ebs.bits(23)
                    if (floatMaxExp >= 25) exp = ebs.bits(8)
                    sign = ebs.bit()
                } else if ((floatFlag and FLT_ZERO_SIGN) != 0) {
                    sign = ebs.bit()
                }
            }
        }
        crcExtra = crcExtra * 27 + s * 9 + exp * 3 + sign
        val f = java.lang.Float.intBitsToFloat((sign shl 31) or ((exp and 0xFF) shl 23) or s)
        // ±1.0 を ±2^31 に(偶数への丸め、範囲の外は切り詰め)
        val d = Math.rint((f * 2147483648f).toDouble())
        return when {
            d >= 2147483647.0 -> Int.MAX_VALUE
            d <= -2147483648.0 -> Int.MIN_VALUE
            else -> d.toInt()
        }
    }

    // ---- エントロピー符号(words.c の get_word と同じ) ----

    /** 次の残差を 1 つ読む(ch = 0 / 1) */
    private fun getValue(ch: Int): Int {
        val m = med
        // 中央値が小さいときは、0 の続く数が先に書かれている
        if ((m[0] == 0 || m[0] == 1) && (m[3] == 0 || m[3] == 1) && !holdZero && !holdOne) {
            if (zeros > 0) {
                zeros--
                if (zeros > 0) {
                    slow[ch] -= (slow[ch] + 0x80) shr 8
                    return 0
                }
            } else {
                var t = bs.ones33()
                if (t >= 2) {
                    if (t >= 32) throw IOException("WavPack: bad zero run")
                    t = bs.bits(t - 1) or (1 shl (t - 1))
                }
                zeros = t
                if (t > 0) {
                    java.util.Arrays.fill(m, 0)
                    slow[ch] -= (slow[ch] + 0x80) shr 8
                    return 0
                }
            }
        }

        var t: Int
        if (holdZero) {
            t = 0
            holdZero = false
        } else {
            t = bs.ones33()
            if (t == 16) {
                val t2 = bs.ones33()
                if (t2 < 2) {
                    t += t2
                } else {
                    if (t2 >= 32) throw IOException("WavPack: bad code")
                    t += bs.bits(t2 - 1) or (1 shl (t2 - 1))
                }
            }
            if (holdOne) {
                holdOne = (t and 1) != 0
                t = (t shr 1) + 1
            } else {
                holdOne = (t and 1) != 0
                t = t shr 1
            }
            holdZero = !holdOne
        }

        if (hybrid && ch == 0) updateErrorLimit()

        val o = ch * 3
        val base: Int
        val add: Int
        when (t) {
            0 -> {
                base = 0
                add = (m[o] shr 4)
                decMed(o, 0)
            }
            1 -> {
                base = (m[o] shr 4) + 1
                add = (m[o + 1] shr 4)
                incMed(o, 0); decMed(o + 1, 1)
            }
            2 -> {
                base = (m[o] shr 4) + 1 + (m[o + 1] shr 4) + 1
                add = (m[o + 2] shr 4)
                incMed(o, 0); incMed(o + 1, 1); decMed(o + 2, 2)
            }
            else -> {
                base = (m[o] shr 4) + 1 + (m[o + 1] shr 4) + 1 + ((m[o + 2] shr 4) + 1) * (t - 2)
                add = (m[o + 2] shr 4)
                incMed(o, 0); incMed(o + 1, 1); incMed(o + 2, 2)
            }
        }
        val ret: Int
        val lim = errLimit[ch]
        if (lim == 0) {
            ret = base + tail(add)
        } else {
            // ハイブリッド: 誤差の範囲に入るまで二分して決める
            var bse = base
            var ad = add
            var mid = (bse * 2 + ad + 1) ushr 1
            var steps = 0
            while (ad > lim) {
                // 正しいデータなら 32 回以内に終わる(壊れたデータで止まらなくならないように)
                if (++steps > 64) throw IOException("WavPack: bad hybrid data")
                if (bs.bit() != 0) {
                    ad -= mid - bse
                    bse = mid
                } else {
                    ad = mid - bse - 1
                }
                mid = (bse * 2 + ad + 1) ushr 1
            }
            ret = mid
        }
        val sign = bs.bit()
        if (hybridBitrate) slow[ch] += log2s(ret) - ((slow[ch] + 0x80) shr 8)
        return if (sign != 0) ret.inv() else ret
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun decMed(i: Int, n: Int) {
        val div = 128 shr n
        med[i] -= ((med[i] + div - 2) / div) * 2
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun incMed(i: Int, n: Int) {
        val div = 128 shr n
        med[i] += ((med[i] + div) / div) * 5
    }

    /** 0〜k の値を読む(k + 1 個の値を、短い符号と長い符号に分けて) */
    private fun tail(k: Int): Int {
        if (k < 1) return 0
        val p = 31 - Integer.numberOfLeadingZeros(k)
        val e = ((1L shl (p + 1)) - k - 1).toInt()
        var res = bs.bits(p)
        if (res >= e) res = (res shl 1) - e + bs.bit()
        return res
    }

    private fun updateErrorLimit() {
        val nc = if (stereoIn) 2 else 1
        var br0 = 0; var br1 = 0; var sl0 = 0; var sl1 = 0
        for (i in 0 until nc) {
            brAcc[i] += brDelta[i]
            val br = brAcc[i] ushr 16
            val sl = (slow[i] + 0x80) shr 8
            if (i == 0) { br0 = br; sl0 = sl } else { br1 = br; sl1 = sl }
        }
        if (stereoIn && hybridBitrate) {
            val balance = (sl1 - sl0 + br1 + 1) shr 1
            when {
                balance > br0 -> { br1 = br0 * 2; br0 = 0 }
                -balance > br0 -> { br0 *= 2; br1 = 0 }
                else -> { br1 = br0 + balance; br0 -= balance }
            }
        }
        for (i in 0 until nc) {
            val br = if (i == 0) br0 else br1
            val sl = if (i == 0) sl0 else sl1
            errLimit[i] = if (hybridBitrate) {
                if (sl - br > -0x100) exp2s(sl - br + 0x100) else 0
            } else {
                exp2s(br)
            }
        }
    }

    // ---- 途中から ----

    /**
     * target(サンプル番号)を含むフレームの手前に移る。
     * 次の decodeNext() からそのフレームが出る(frameSample で始まりが分かるので、手前の分は呼び出し側で捨てる)
     */
    override fun seekSample(target: Long) {
        pos = firstBlock
        if (target <= 0) return
        val want = target + baseIndex
        var lo = firstBlock
        var hi = src.size
        // 位置で二分探索: 中ほどから次のフレームの頭を探し、そのサンプル番号で狭める
        while (hi - lo > 512 * 1024) {
            val mid = lo + (hi - lo) / 2
            val p = findFrame(mid, hi)
            if (p < 0 || hIdx > want) hi = mid else lo = p
        }
        // lo からヘッダーだけをたどり、target を含むフレームの頭へ
        var p = lo
        while (true) {
            p = findBlock(p)
            if (p < 0) { pos = src.size; return }
            if (hSamples > 0 && (hFlags and INITIAL) != 0 && hIdx + hSamples > want) { pos = p; return }
            p += 8 + hCk
        }
    }

    /**
     * from 以降で最初のフレームの頭(INITIAL のブロック)を探す。無ければ -1。
     * 圧縮したデータの中に "wvpk" がたまたまある場合に備え、次のブロックが続くかも確かめる
     */
    private fun findFrame(from: Long, limit: Long): Long {
        var q = from
        while (q in 0 until limit) {
            val p = findBlock(q)
            if (p < 0 || p >= limit) return -1
            val idx = hIdx
            val flags = hFlags
            val ns = hSamples
            val end = p + 8 + hCk
            val ok = end <= src.size && (header(end) || findBlock(end) < 0)
            if (ok && ns > 0 && (flags and INITIAL) != 0) {
                // header() が書き換えたので読み直す
                header(p)
                if (hIdx == idx) return p
            }
            q = if (ok) end else p + 1
        }
        return -1
    }

    // ---- ビットの読み込み(下位のビットから) ----

    private class Bits {
        private var b = ByteArray(0)
        private var p = 0
        private var end = 0
        private var acc = 0L
        private var n = 0

        fun init(buf: ByteArray, start: Int, e: Int) {
            b = buf; p = start; end = e; acc = 0; n = 0
        }

        /** 57 ビット以上にする(終わりの先は 0) */
        private fun fill() {
            while (n <= 56) {
                val v = if (p < end) b[p].toInt() and 0xFF else 0
                p++
                acc = acc or (v.toLong() shl n)
                n += 8
            }
        }

        fun bit(): Int {
            if (n == 0) fill()
            val v = (acc and 1).toInt()
            acc = acc ushr 1
            n--
            return v
        }

        /** k ビット(0〜32) */
        fun bits(k: Int): Int {
            if (k == 0) return 0
            if (n < k) fill()
            val v = (acc and ((1L shl k) - 1)).toInt()
            acc = acc ushr k
            n -= k
            return v
        }

        /** 0 が出るまでの 1 の数(最大 33。33 のときは 0 を読まない) */
        fun ones33(): Int {
            if (n < 34) fill()
            val t = java.lang.Long.numberOfTrailingZeros(acc.inv())
            if (t >= 33) {
                acc = acc ushr 33
                n -= 33
                return 33
            }
            acc = acc ushr (t + 1)
            n -= t + 1
            return t
        }
    }

    companion object {
        private const val MONO = 4
        private const val HYBRID = 8
        private const val JOINT = 0x10
        private const val FLOAT = 0x80
        private const val HYBRID_BITRATE = 0x200
        private const val INITIAL = 0x800
        private const val FINAL = 0x1000
        private const val FALSE_STEREO = 0x40000000
        private const val DSD = Int.MIN_VALUE // 0x80000000

        private const val ID_LARGE = 0x80
        private const val ID_ODD = 0x40
        private const val ID_DECORR_TERMS = 2
        private const val ID_DECORR_WEIGHTS = 3
        private const val ID_DECORR_SAMPLES = 4
        private const val ID_ENTROPY_VARS = 5
        private const val ID_HYBRID_PROFILE = 6
        private const val ID_FLOAT_INFO = 8
        private const val ID_INT32_INFO = 9
        private const val ID_WV_BITSTREAM = 0xA
        private const val ID_WVX_BITSTREAM = 0xC
        private const val ID_DSD_BLOCK = 0xE
        private const val ID_SAMPLE_RATE = 0x27

        private const val FLT_SHIFT_ONES = 1
        private const val FLT_SHIFT_SAME = 2
        private const val FLT_SHIFT_SENT = 4
        private const val FLT_ZERO_SENT = 8
        private const val FLT_ZERO_SIGN = 0x10

        private const val MAX_TERMS = 16
        /** 1 ブロックのサンプル数の上限(libwavpack が作るのは 131072 まで。壊れたヘッダーで大きな配列を作らない) */
        private const val MAX_BLOCK_SAMPLES = 0x30000
        private const val MAX_CHANNELS = 4096

        private val RATES = intArrayOf(6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000, 192000)

        private fun hexTable(s: String) = IntArray(256) { Integer.parseInt(s.substring(it * 2, it * 2 + 2), 16) }

        /** 2^(i/256) - 1 を 256 倍したもの */
        private val EXP2 = hexTable(
            "00010102030304050606070808090a0b0b0c0d0e0e0f10101112131314151616171819191a1b1c1d1d1e1f202021222324242526272828292a2b2c2c2d2e2f" +
                "3030313233343535363738393a3a3b3c3d3e3f4041414243444546474848494a4b4c4d4e4f50515152535455565758595a5b5c5d5e5e5f60616263646566" +
                "6768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f8081828384858788898a8b8c8d8e8f9091929395969798999a9b9c9d9fa0a1a2a3a4a5a6a8" +
                "a9aaabacadafb0b1b2b3b4b6b7b8b9babcbdbebfc0c2c3c4c5c6c8c9cacbcdcecfd0d2d3d4d6d7d8d9dbdcdddee0e1e2e4e5e6e8e9eaecedeef0f1f2f4f5f6" +
                "f8f9fafcfdff"
        )

        /** log2(1 + i/256) を 256 倍したもの */
        private val LOG2 = hexTable(
            "000103040607090a0b0d0e10111214151618191a1c1d1e20212224252628292a2c2d2e2f31323334363738393b3c3d3e3f41424344454748494a4b4d4e4f" +
                "5051525455565758595a5c5d5e5f6061626364666768696a6b6c6d6e6f7071727475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f9091" +
                "92939495969798999a9b9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9a9aaabacadaeafb0b1b2b2b3b4b5b6b7b8b9b9babbbcbdbebfc0c0c1c2c3c4c5c6c6c7c8c9ca" +
                "cbcbcccdcecfd0d0d1d2d3d4d4d5d6d7d8d8d9dadbdcdcdddedfe0e0e1e2e3e4e4e5e6e7e7e8e9eaeaebecedeeeeeff0f1f1f2f3f4f4f5f6f7f7f8f9f9fafb" +
                "fcfcfdfeffff"
        )

        /** 重みを 8bit から戻す */
        private fun restoreWeight(v: Int): Int {
            var w = v.toByte().toInt() * 8
            if (w > 0) w += (w + 64) shr 7
            return w
        }

        /** 対数(16bit の符号付き)から値に */
        private fun exp2s(v0: Int): Int {
            var v = v0.toShort().toInt()
            val neg = v < 0
            if (neg) v = (-v).toShort().toInt()
            var res = EXP2[v and 0xFF] or 0x100
            v = v shr 8
            if (v < 0 || v > 31) return Int.MIN_VALUE
            res = if (v > 9) res shl (v - 9) else res ushr (9 - v)
            return if (neg) -res else res
        }

        /** 値(符号なし)の対数 */
        private fun log2s(v0: Int): Int {
            if (v0 == 0) return 0
            val v = v0 + (v0 ushr 9)
            val bits = 32 - Integer.numberOfLeadingZeros(v)
            return if (bits < 9) (bits shl 8) + LOG2[(v shl (9 - bits)) and 0xFF]
            else (bits shl 8) + LOG2[(v ushr (bits - 9)) and 0xFF]
        }
    }
}
