package com.uyatame.ucrt.player.mp3

import com.uyatame.ucrt.player.mp3.Mp3Tables.LINBITS
import com.uyatame.ucrt.player.mp3.Mp3Tables.POW43
import com.uyatame.ucrt.player.mp3.Mp3Tables.TAB32
import com.uyatame.ucrt.player.mp3.Mp3Tables.TAB33
import com.uyatame.ucrt.player.mp3.Mp3Tables.TABINDEX
import com.uyatame.ucrt.player.mp3.Mp3Tables.TABS

/**
 * MPEG-1/2/2.5 Layer I・II・III(MP3)のデコーダー。Android の部品(MediaCodec)を使わないので、古い Android でも動く。
 * minimp3(https://github.com/lieff/minimp3、CC0。作者はパブリックドメインに提供)を Kotlin に写したもの。
 * 出力は float(-1〜1)、チャンネル交互
 */
class Mp3Decoder {
    /** 1 フレームの情報 */
    class FrameInfo {
        /** 読み進めたバイト数(フレームの前のゴミを含む)。0 なら、データが足りない */
        var frameBytes = 0
        var frameOffset = 0
        var channels = 0
        var hz = 0
        var layer = 0
        var bitrateKbps = 0
        /** このフレームの 1 チャンネルあたりのサンプル数(デコードできなかったときも) */
        var frameSamples = 0
    }

    // ---- デコーダーの状態(mp3dec_t) ----
    private val mdctOverlap = Array(2) { FloatArray(9 * 32) }
    private val qmfState = FloatArray(15 * 2 * 32)
    private var reserv = 0
    private var freeFormatBytes = 0
    private val header = ByteArray(4)
    private val reservBuf = ByteArray(511)

    // ---- 作業用(mp3dec_scratch_t) ----
    private val maindata = ByteArray(MAX_BITRESERVOIR_BYTES + MAX_L3_FRAME_PAYLOAD_BYTES + 8)
    private val grInfo = Array(4) { GrInfo() }
    /** grbuf[2][576] を 1 本に並べたもの(右チャンネルは +576) */
    private val grbuf = FloatArray(576 * 2)
    private val scf = FloatArray(40)
    /** syn[18 + 15][2*32] を 1 本に */
    private val syn = FloatArray((18 + 15) * 2 * 32)
    private val istPos = Array(2) { IntArray(39) }
    private val mainBs = Bs()

    /** ビットを読む(bs_t) */
    private class Bs {
        var buf: ByteArray = ByteArray(0)
        var base = 0
        var pos = 0
        var limit = 0
        fun init(b: ByteArray, off: Int, bytes: Int) { buf = b; base = off; pos = 0; limit = bytes * 8 }
        fun byteAt(i: Int): Int { val k = base + i; return if (k < buf.size) buf[k].toInt() and 0xFF else 0 }
        fun get(n: Int): Int {
            val s = pos and 7
            var shl = n + s
            var p = pos shr 3
            pos += n
            if (pos > limit) return 0
            var next = byteAt(p++) and (255 shr s)
            var cache = 0
            while (true) {
                shl -= 8
                if (shl <= 0) break
                cache = cache or (next shl shl)
                next = byteAt(p++)
            }
            return cache or (next ushr -shl)
        }
    }

    private class GrInfo {
        var sfbtab: IntArray = Mp3Tables.SCF_LONG
        var sfbOff = 0
        var part23Length = 0
        var bigValues = 0
        var scalefacCompress = 0
        var globalGain = 0
        var blockType = 0
        var mixedBlockFlag = 0
        var nLongSfb = 0
        var nShortSfb = 0
        val tableSelect = IntArray(3)
        val regionCount = IntArray(3)
        val subblockGain = IntArray(3)
        var preflag = 0
        var scalefacScale = 0
        var count1Table = 0
        var scfsi = 0
    }

    private class L12ScaleInfo {
        val scf = FloatArray(3 * 64)
        var totalBands = 0
        var stereoBands = 0
        val bitalloc = IntArray(64)
        val scfcod = IntArray(64)
    }

    private val sci = L12ScaleInfo()

    /** 曲の頭から(シークのあと)読み直すときに呼ぶ */
    fun reset() {
        header[0] = 0
    }

    /**
     * 1 フレームを読む。mp3 の off から bytes バイトのうち、先頭のフレームを探してデコードし、pcm に書く
     * (最大 1152 × 2 サンプル)。戻り値は 1 チャンネルあたりのサンプル数(0 ならデコードできなかった)。
     * info.frameBytes だけ読み進める(0 ならデータが足りない)
     */
    fun decodeFrame(mp3: ByteArray, off: Int, bytes: Int, pcm: FloatArray?, info: FrameInfo): Int {
        var i = 0
        var frameSize = 0
        var success = 1
        if (bytes > 4 && (header[0].toInt() and 0xFF) == 0xFF && hdrCompare(header, 0, mp3, off)) {
            frameSize = hdrFrameBytes(mp3, off, freeFormatBytes) + hdrPadding(mp3, off)
            if (frameSize != bytes && (frameSize + HDR_SIZE > bytes || !hdrCompare(mp3, off, mp3, off + frameSize))) frameSize = 0
        }
        if (frameSize == 0) {
            clearState()
            val found = IntArray(1)
            i = findFrame(mp3, off, bytes, found)
            frameSize = found[0]
            if (frameSize == 0 || i + frameSize > bytes) {
                info.frameBytes = i
                return 0
            }
        }
        val h = off + i
        System.arraycopy(mp3, h, header, 0, HDR_SIZE)
        info.frameBytes = i + frameSize
        info.frameOffset = i
        info.channels = if (isMono(mp3, h)) 1 else 2
        info.hz = hdrSampleRateHz(mp3, h)
        info.layer = 4 - layerOf(mp3, h)
        info.bitrateKbps = hdrBitrateKbps(mp3, h)
        info.frameSamples = hdrFrameSamples(mp3, h)
        if (pcm == null) return hdrFrameSamples(mp3, h)

        val bs = Bs()
        bs.init(mp3, h + HDR_SIZE, frameSize - HDR_SIZE)
        if ((mp3[h + 1].toInt() and 1) == 0) bs.get(16) // CRC
        var p = 0
        if (info.layer == 3) {
            val mainDataBegin = l3ReadSideInfo(bs, mp3, h)
            if (mainDataBegin < 0 || bs.pos > bs.limit) {
                reset()
                return 0
            }
            success = if (l3RestoreReservoir(bs, mainDataBegin)) 1 else 0
            if (success != 0) {
                val grs = if (isMpeg1(header, 0)) 2 else 1
                for (igr in 0 until grs) {
                    grbuf.fill(0f)
                    l3Decode(igr * info.channels, info.channels)
                    synthGranule(18, info.channels, pcm, p)
                    p += 576 * info.channels
                }
            }
            l3SaveReservoir()
        } else {
            l12ReadScaleInfo(mp3, h, bs, sci)
            grbuf.fill(0f)
            var k = 0
            for (igr in 0 until 3) {
                k += l12DequantizeGranule(k, bs, sci, info.layer or 1)
                if (k == 12) {
                    k = 0
                    l12ApplyScf384(sci, igr)
                    synthGranule(12, info.channels, pcm, p)
                    grbuf.fill(0f)
                    p += 384 * info.channels
                }
                if (bs.pos > bs.limit) {
                    reset()
                    return 0
                }
            }
        }
        return success * hdrFrameSamples(header, 0)
    }

    /**
     * フレームが Xing / Info(LAME など)の情報のフレームか。そうなら [フレーム数, 先頭の無音(サンプル), 末尾の詰め物(サンプル)]。
     * フレーム数が書かれていなければ [-1, 0, 0](音ではないが、長さは分からない)。
     * 情報のフレームは音として出さない(minimp3_ex の mp3dec_check_vbrtag と同じ)
     */
    fun vbrTag(b: ByteArray, h: Int, frameSize: Int): IntArray? {
        if (frameSize < HDR_SIZE + 8 || h + frameSize > b.size) return null
        val bs = Bs()
        bs.init(b, h + HDR_SIZE, frameSize - HDR_SIZE)
        if ((b[h + 1].toInt() and 1) == 0) bs.get(16)
        val saved = header.copyOf()
        System.arraycopy(b, h, header, 0, HDR_SIZE)
        val side = l3ReadSideInfo(bs, b, h)
        System.arraycopy(saved, 0, header, 0, HDR_SIZE)
        if (side < 0) return null
        var off = HDR_SIZE + bs.pos / 8
        if (off > frameSize || frameSize - off < 8) return null
        val t = h + off
        val tag = String(b, t, 4, Charsets.ISO_8859_1)
        if (tag != "Xing" && tag != "Info") return null
        val flags = u(b, t + 7)
        if ((flags and 1) == 0) return intArrayOf(-1, 0, 0)
        off += 8
        if (frameSize - off < 4) return null
        val frames = (u(b, h + off) shl 24) or (u(b, h + off + 1) shl 16) or (u(b, h + off + 2) shl 8) or u(b, h + off + 3)
        off += 4
        if ((flags and 2) != 0) { if (frameSize - off < 4) return null; off += 4 }
        if ((flags and 4) != 0) { if (frameSize - off < 100) return null; off += 100 }
        if ((flags and 8) != 0) { if (frameSize - off < 4) return null; off += 4 }
        var delay = 0
        var padding = 0
        if (frameSize - off < 1) return null
        if (b[h + off].toInt() != 0) {
            // LAME・Lavc などの拡張: 先頭の無音と末尾の詰め物
            if (frameSize - off <= 35) return null
            off += 21
            val x = h + off
            delay = ((u(b, x) shl 4) or (u(b, x + 1) shr 4)) + (528 + 1)
            padding = (((u(b, x + 1) and 0xF) shl 8) or u(b, x + 2)) - (528 + 1)
        }
        return intArrayOf(frames, delay, padding)
    }

    private fun clearState() {
        mdctOverlap[0].fill(0f); mdctOverlap[1].fill(0f)
        qmfState.fill(0f)
        reserv = 0
        freeFormatBytes = 0
        header.fill(0)
        reservBuf.fill(0)
    }

    // ================= ヘッダー =================

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun isMono(b: ByteArray, h: Int) = (u(b, h + 3) and 0xC0) == 0xC0
    private fun isMsStereo(b: ByteArray, h: Int) = (u(b, h + 3) and 0xE0) == 0x60
    private fun isFreeFormat(b: ByteArray, h: Int) = (u(b, h + 2) and 0xF0) == 0
    private fun isMpeg1(b: ByteArray, h: Int) = (u(b, h + 1) and 0x8) != 0
    private fun notMpeg25(b: ByteArray, h: Int) = (u(b, h + 1) and 0x10) != 0
    private fun iStereo(b: ByteArray, h: Int) = (u(b, h + 3) and 0x10) != 0
    private fun msStereoFlag(b: ByteArray, h: Int) = (u(b, h + 3) and 0x20) != 0
    private fun stereoMode(b: ByteArray, h: Int) = (u(b, h + 3) shr 6) and 3
    private fun stereoModeExt(b: ByteArray, h: Int) = (u(b, h + 3) shr 4) and 3
    private fun layerOf(b: ByteArray, h: Int) = (u(b, h + 1) shr 1) and 3
    private fun bitrateIdx(b: ByteArray, h: Int) = u(b, h + 2) shr 4
    private fun sampleRateIdx(b: ByteArray, h: Int) = (u(b, h + 2) shr 2) and 3
    private fun mySampleRate(b: ByteArray, h: Int) = sampleRateIdx(b, h) + (((u(b, h + 1) shr 3) and 1) + ((u(b, h + 1) shr 4) and 1)) * 3
    private fun isFrame576(b: ByteArray, h: Int) = (u(b, h + 1) and 14) == 2
    private fun isLayer1(b: ByteArray, h: Int) = (u(b, h + 1) and 6) == 6

    private fun hdrValid(b: ByteArray, h: Int): Boolean {
        if (h + 4 > b.size) return false
        return u(b, h) == 0xFF &&
            ((u(b, h + 1) and 0xF0) == 0xF0 || (u(b, h + 1) and 0xFE) == 0xE2) &&
            layerOf(b, h) != 0 && bitrateIdx(b, h) != 15 && sampleRateIdx(b, h) != 3
    }

    private fun hdrCompare(b1: ByteArray, h1: Int, b2: ByteArray, h2: Int): Boolean =
        hdrValid(b2, h2) &&
            ((u(b1, h1 + 1) xor u(b2, h2 + 1)) and 0xFE) == 0 &&
            ((u(b1, h1 + 2) xor u(b2, h2 + 2)) and 0x0C) == 0 &&
            isFreeFormat(b1, h1) == isFreeFormat(b2, h2)

    private fun hdrBitrateKbps(b: ByteArray, h: Int): Int =
        2 * HALFRATE[(if (isMpeg1(b, h)) 1 else 0) * 45 + (layerOf(b, h) - 1) * 15 + bitrateIdx(b, h)]

    private fun hdrSampleRateHz(b: ByteArray, h: Int): Int =
        HZ[sampleRateIdx(b, h)] shr (if (isMpeg1(b, h)) 0 else 1) shr (if (notMpeg25(b, h)) 0 else 1)

    private fun hdrFrameSamples(b: ByteArray, h: Int): Int = if (isLayer1(b, h)) 384 else (1152 shr (if (isFrame576(b, h)) 1 else 0))

    private fun hdrFrameBytes(b: ByteArray, h: Int, freeFormatSize: Int): Int {
        var fb = hdrFrameSamples(b, h) * hdrBitrateKbps(b, h) * 125 / hdrSampleRateHz(b, h)
        if (isLayer1(b, h)) fb = fb and 3.inv()
        return if (fb != 0) fb else freeFormatSize
    }

    private fun hdrPadding(b: ByteArray, h: Int): Int =
        if ((u(b, h + 2) and 0x2) != 0) (if (isLayer1(b, h)) 4 else 1) else 0

    private fun matchFrame(b: ByteArray, h: Int, bytes: Int, frameBytes: Int): Boolean {
        var i = 0
        var nmatch = 0
        while (nmatch < MAX_FRAME_SYNC_MATCHES) {
            i += hdrFrameBytes(b, h + i, frameBytes) + hdrPadding(b, h + i)
            if (i + HDR_SIZE > bytes) return nmatch > 0
            if (!hdrCompare(b, h, b, h + i)) return false
            nmatch++
        }
        return true
    }

    private fun findFrame(b: ByteArray, off: Int, bytes: Int, outFrameBytes: IntArray): Int {
        var i = 0
        while (i < bytes - HDR_SIZE) {
            val m = off + i
            if (hdrValid(b, m)) {
                var frameBytes = hdrFrameBytes(b, m, freeFormatBytes)
                var frameAndPadding = frameBytes + hdrPadding(b, m)
                var k = HDR_SIZE
                while (frameBytes == 0 && k < MAX_FREE_FORMAT_FRAME_SIZE && i + 2 * k < bytes - HDR_SIZE) {
                    if (hdrCompare(b, m, b, m + k)) {
                        val fb = k - hdrPadding(b, m)
                        val nextfb = fb + hdrPadding(b, m + k)
                        if (!(i + k + nextfb + HDR_SIZE > bytes || !hdrCompare(b, m, b, m + k + nextfb))) {
                            frameAndPadding = k
                            frameBytes = fb
                            freeFormatBytes = fb
                        }
                    }
                    k++
                }
                if ((frameBytes != 0 && i + frameAndPadding <= bytes && matchFrame(b, m, bytes - i, frameBytes)) ||
                    (i == 0 && frameAndPadding == bytes)
                ) {
                    outFrameBytes[0] = frameAndPadding
                    return i
                }
                freeFormatBytes = 0
            }
            i++
        }
        outFrameBytes[0] = 0
        return bytes
    }

    // ================= Layer I・II =================

    private fun l12SubbandAllocTable(b: ByteArray, h: Int, sci: L12ScaleInfo): IntArray {
        val mode = stereoMode(b, h)
        val stereoBands = if (mode == MODE_MONO) 0 else if (mode == MODE_JOINT_STEREO) (stereoModeExt(b, h) shl 2) + 4 else 32
        val alloc: IntArray
        var nbands: Int
        if (isLayer1(b, h)) {
            alloc = intArrayOf(76, 4, 32)
            nbands = 32
        } else if (!isMpeg1(b, h)) {
            alloc = intArrayOf(60, 4, 4, 44, 3, 7, 44, 2, 19)
            nbands = 30
        } else {
            val sampleRateIdx = sampleRateIdx(b, h)
            var kbps = hdrBitrateKbps(b, h) shr (if (mode != MODE_MONO) 1 else 0)
            if (kbps == 0) kbps = 192
            var a = intArrayOf(0, 4, 3, 16, 4, 8, 32, 3, 12, 40, 2, 7)
            nbands = 27
            if (kbps < 56) {
                a = intArrayOf(44, 4, 2, 44, 3, 10)
                nbands = if (sampleRateIdx == 2) 12 else 8
            } else if (kbps >= 96 && sampleRateIdx != 1) {
                nbands = 30
            }
            alloc = a
        }
        sci.totalBands = nbands
        sci.stereoBands = minOf(stereoBands, nbands)
        return alloc
    }

    private fun l12ReadScalefactors(bs: Bs, pba: IntArray, scfcod: IntArray, bands: Int, scf: FloatArray) {
        var o = 0
        for (i in 0 until bands) {
            var s = 0f
            val ba = pba[i]
            val mask = if (ba != 0) 4 + ((19 shr scfcod[i]) and 3) else 0
            var m = 4
            while (m != 0) {
                if ((mask and m) != 0) {
                    val bb = bs.get(6)
                    s = DEQ_L12[ba * 3 - 6 + bb % 3] * (1 shl 21 shr bb / 3).toFloat()
                }
                scf[o++] = s
                m = m shr 1
            }
        }
    }

    private fun l12ReadScaleInfo(b: ByteArray, h: Int, bs: Bs, sci: L12ScaleInfo) {
        val alloc = l12SubbandAllocTable(b, h, sci)
        var k = 0
        var baBits = 0
        var baCodeTab = 0
        var a = 0
        for (i in 0 until sci.totalBands) {
            if (i == k) {
                k += alloc[a + 2]
                baBits = alloc[a + 1]
                baCodeTab = alloc[a]
                a += 3
            }
            var ba = BITALLOC_CODE_TAB[baCodeTab + bs.get(baBits)]
            sci.bitalloc[2 * i] = ba
            if (i < sci.stereoBands) ba = BITALLOC_CODE_TAB[baCodeTab + bs.get(baBits)]
            sci.bitalloc[2 * i + 1] = if (sci.stereoBands != 0) ba else 0
        }
        for (i in 0 until 2 * sci.totalBands) {
            sci.scfcod[i] = if (sci.bitalloc[i] != 0) (if (isLayer1(b, h)) 2 else bs.get(2)) else 6
        }
        l12ReadScalefactors(bs, sci.bitalloc, sci.scfcod, sci.totalBands * 2, sci.scf)
        for (i in sci.stereoBands until sci.totalBands) sci.bitalloc[2 * i + 1] = 0
    }

    private fun l12DequantizeGranule(start: Int, bs: Bs, sci: L12ScaleInfo, groupSize: Int): Int {
        for (j in 0 until 4) {
            var dst = start + groupSize * j
            var choff = 576
            for (i in 0 until 2 * sci.totalBands) {
                val ba = sci.bitalloc[i]
                if (ba != 0) {
                    if (ba < 17) {
                        val half = (1 shl (ba - 1)) - 1
                        for (k in 0 until groupSize) grbuf[dst + k] = (bs.get(ba) - half).toFloat()
                    } else {
                        val mod = (2 shl (ba - 17)) + 1
                        var code = bs.get(mod + 2 - (mod shr 3)).toLong() and 0xFFFFFFFFL
                        for (k in 0 until groupSize) {
                            grbuf[dst + k] = ((code % mod).toInt() - mod / 2).toFloat()
                            code /= mod
                        }
                    }
                }
                dst += choff
                choff = 18 - choff
            }
        }
        return groupSize * 4
    }

    private fun l12ApplyScf384(sci: L12ScaleInfo, igr: Int) {
        System.arraycopy(grbuf, sci.stereoBands * 18, grbuf, 576 + sci.stereoBands * 18, (sci.totalBands - sci.stereoBands) * 18)
        var d = 0
        var s = igr
        for (i in 0 until sci.totalBands) {
            for (k in 0 until 12) {
                grbuf[d + k] *= sci.scf[s]
                grbuf[d + k + 576] *= sci.scf[s + 3]
            }
            d += 18
            s += 6
        }
    }

    // ================= Layer III =================

    private fun l3ReadSideInfo(bs: Bs, b: ByteArray, h: Int): Int {
        var scfsi = 0
        var mainDataBegin: Int
        var part23Sum = 0
        var srIdx = mySampleRate(b, h)
        if (srIdx != 0) srIdx--
        var grCount = if (isMono(b, h)) 1 else 2
        if (isMpeg1(b, h)) {
            grCount *= 2
            mainDataBegin = bs.get(9)
            scfsi = bs.get(7 + grCount)
        } else {
            mainDataBegin = bs.get(8 + grCount) shr grCount
        }
        var g = 0
        do {
            val gr = grInfo[g]
            if (isMono(b, h)) scfsi = scfsi shl 4
            gr.part23Length = bs.get(12)
            part23Sum += gr.part23Length
            gr.bigValues = bs.get(9)
            if (gr.bigValues > 288) return -1
            gr.globalGain = bs.get(8)
            gr.scalefacCompress = bs.get(if (isMpeg1(b, h)) 4 else 9)
            gr.sfbtab = Mp3Tables.SCF_LONG
            gr.sfbOff = srIdx * 23
            gr.nLongSfb = 22
            gr.nShortSfb = 0
            var tables: Int
            if (bs.get(1) != 0) {
                gr.blockType = bs.get(2)
                if (gr.blockType == 0) return -1
                gr.mixedBlockFlag = bs.get(1)
                gr.regionCount[0] = 7
                gr.regionCount[1] = 255
                if (gr.blockType == SHORT_BLOCK_TYPE) {
                    scfsi = scfsi and 0x0F0F
                    if (gr.mixedBlockFlag == 0) {
                        gr.regionCount[0] = 8
                        gr.sfbtab = Mp3Tables.SCF_SHORT
                        gr.sfbOff = srIdx * 40
                        gr.nLongSfb = 0
                        gr.nShortSfb = 39
                    } else {
                        gr.sfbtab = Mp3Tables.SCF_MIXED
                        gr.sfbOff = srIdx * 40
                        gr.nLongSfb = if (isMpeg1(b, h)) 8 else 6
                        gr.nShortSfb = 30
                    }
                }
                tables = bs.get(10)
                tables = tables shl 5
                gr.subblockGain[0] = bs.get(3)
                gr.subblockGain[1] = bs.get(3)
                gr.subblockGain[2] = bs.get(3)
            } else {
                gr.blockType = 0
                gr.mixedBlockFlag = 0
                tables = bs.get(15)
                gr.regionCount[0] = bs.get(4)
                gr.regionCount[1] = bs.get(3)
                gr.regionCount[2] = 255
            }
            gr.tableSelect[0] = (tables shr 10) and 0xFF
            gr.tableSelect[1] = (tables shr 5) and 31
            gr.tableSelect[2] = tables and 31
            gr.preflag = if (isMpeg1(b, h)) bs.get(1) else (if (gr.scalefacCompress >= 500) 1 else 0)
            gr.scalefacScale = bs.get(1)
            gr.count1Table = bs.get(1)
            gr.scfsi = (scfsi shr 12) and 15
            scfsi = scfsi shl 4
            g++
        } while (--grCount > 0)
        if (part23Sum + bs.pos > bs.limit + mainDataBegin * 8) return -1
        return mainDataBegin
    }

    private fun l3ReadScalefactors(scf: IntArray, ist: IntArray, scfSize: IntArray, scfCount: IntArray, countOff: Int, bs: Bs, scfsi0: Int) {
        var scfsi = scfsi0
        var o = 0
        var i = 0
        while (i < 4 && scfCount[countOff + i] != 0) {
            val cnt = scfCount[countOff + i]
            if ((scfsi and 8) != 0) {
                for (k in 0 until cnt) scf[o + k] = ist[o + k]
            } else {
                val bits = scfSize[i]
                if (bits == 0) {
                    for (k in 0 until cnt) { scf[o + k] = 0; ist[o + k] = 0 }
                } else {
                    val maxScf = if (scfsi < 0) (1 shl bits) - 1 else -1
                    for (k in 0 until cnt) {
                        val s = bs.get(bits)
                        // ist_pos は uint8_t なので、-1 は 255 になる
                        ist[o + k] = if (s == maxScf) 255 else s
                        scf[o + k] = s
                    }
                }
            }
            o += cnt
            i++
            scfsi *= 2
        }
        scf[o] = 0; scf[o + 1] = 0; scf[o + 2] = 0
    }

    private fun ldexpQ2(y0: Float, exp0: Int): Float {
        var y = y0
        var expQ2 = exp0
        do {
            val e = minOf(30 * 4, expQ2)
            y *= EXPFRAC[e and 3] * (1 shl 30 shr (e shr 2)).toFloat()
            expQ2 -= e
        } while (expQ2 > 0)
        return y
    }

    private val iscf = IntArray(40 + 3)
    private val scfSize = IntArray(4)

    private fun l3DecodeScalefactors(ist: IntArray, bs: Bs, gr: GrInfo, ch: Int) {
        val hb = header
        var partOff = (if (gr.nShortSfb != 0) 1 else 0) + (if (gr.nLongSfb == 0) 1 else 0)
        partOff *= 28
        val scfShift = gr.scalefacScale + 1
        var scfsi = gr.scfsi
        if (isMpeg1(hb, 0)) {
            val part = SCFC_DECODE[gr.scalefacCompress]
            scfSize[0] = part shr 2; scfSize[1] = part shr 2
            scfSize[2] = part and 3; scfSize[3] = part and 3
        } else {
            val ist2 = if (iStereo(hb, 0) && ch != 0) 1 else 0
            var sfc = gr.scalefacCompress shr ist2
            var k = ist2 * 3 * 4
            var modprod: Int
            while (sfc >= 0) {
                modprod = 1
                for (i in 3 downTo 0) {
                    scfSize[i] = sfc / modprod % MOD[k + i]
                    modprod *= MOD[k + i]
                }
                sfc -= modprod
                k += 4
            }
            partOff += k
            scfsi = -16
        }
        iscf.fill(0)
        l3ReadScalefactors(iscf, ist, scfSize, SCF_PARTITIONS, partOff, bs, scfsi)
        if (gr.nShortSfb != 0) {
            val sh = 3 - scfShift
            var i = 0
            while (i < gr.nShortSfb) {
                iscf[gr.nLongSfb + i + 0] += gr.subblockGain[0] shl sh
                iscf[gr.nLongSfb + i + 1] += gr.subblockGain[1] shl sh
                iscf[gr.nLongSfb + i + 2] += gr.subblockGain[2] shl sh
                i += 3
            }
        } else if (gr.preflag != 0) {
            for (i in 0 until 10) iscf[11 + i] += PREAMP[i]
        }
        // iscf は uint8_t なので 8 ビットに収める
        for (i in iscf.indices) iscf[i] = iscf[i] and 0xFF
        val gainExp = gr.globalGain + BITS_DEQUANTIZER_OUT * 4 - 210 - (if (isMsStereo(hb, 0)) 2 else 0)
        val gain = ldexpQ2((1 shl (MAX_SCFI / 4)).toFloat(), MAX_SCFI - gainExp)
        for (i in 0 until gr.nLongSfb + gr.nShortSfb) scf[i] = ldexpQ2(gain, iscf[i] shl scfShift)
    }

    private fun pow43(x0: Int): Float {
        var x = x0
        var mult = 256
        if (x < 129) return POW43[16 + x]
        if (x < 1024) {
            mult = 16
            x = x shl 3
        }
        val sign = 2 * x and 64
        val frac = ((x and 63) - sign).toFloat() / ((x and 63.inv()) + sign)
        return POW43[16 + ((x + sign) shr 6)] * (1f + frac * ((4f / 3) + frac * (2f / 9))) * mult
    }

    private fun l3Huffman(dst0: Int, bs: Bs, gr: GrInfo, layer3grLimit: Int) {
        val buf = bs.buf
        val base = bs.base
        fun byteAt(i: Int): Int { val k = base + i; return if (k < buf.size) buf[k].toInt() and 0xFF else 0 }
        var dst = dst0
        var one = 0f
        var ireg = 0
        var bigValCnt = gr.bigValues
        val sfbTab = gr.sfbtab
        var sfb = gr.sfbOff
        var scfI = 0
        var next = bs.pos / 8
        var cache = ((((byteAt(next) * 256) + byteAt(next + 1)) * 256 + byteAt(next + 2)) * 256 + byteAt(next + 3)) shl (bs.pos and 7)
        var sh = (bs.pos and 7) - 8
        next += 4
        fun peek(n: Int) = cache ushr (32 - n)
        while (bigValCnt > 0) {
            val tabNum = gr.tableSelect[ireg]
            var sfbCnt = gr.regionCount[ireg++]
            val cb = TABINDEX[tabNum]
            val linbits = LINBITS[tabNum]
            do {
                val np = sfbTab[sfb++] / 2
                var pairs = minOf(bigValCnt, np)
                one = scf[scfI++]
                do {
                    var w = 5
                    var leaf = TABS[cb + peek(w)]
                    while (leaf < 0) {
                        cache = cache shl w; sh += w
                        w = leaf and 7
                        leaf = TABS[cb + peek(w) - (leaf shr 3)]
                    }
                    val f = leaf shr 8
                    cache = cache shl f; sh += f
                    for (j in 0 until 2) {
                        var lsb = leaf and 0x0F
                        if (linbits != 0 && lsb == 15) {
                            lsb += peek(linbits)
                            cache = cache shl linbits; sh += linbits
                            while (sh >= 0) { cache = cache or (byteAt(next++) shl sh); sh -= 8 }
                            grbuf[dst] = one * pow43(lsb) * (if (cache < 0) -1 else 1)
                        } else {
                            grbuf[dst] = POW43[16 + lsb - 16 * (cache ushr 31)] * one
                        }
                        val fl = if (lsb != 0) 1 else 0
                        cache = cache shl fl; sh += fl
                        dst++
                        leaf = leaf shr 4
                    }
                    while (sh >= 0) { cache = cache or (byteAt(next++) shl sh); sh -= 8 }
                } while (--pairs > 0)
                bigValCnt -= np
            } while (bigValCnt > 0 && --sfbCnt >= 0)
        }
        var np = 1 - bigValCnt
        val cb1 = if (gr.count1Table != 0) TAB33 else TAB32
        while (true) {
            var leaf = cb1[peek(4)]
            if ((leaf and 8) == 0) leaf = cb1[(leaf shr 3) + ((cache shl 4) ushr (32 - (leaf and 3)))]
            val f = leaf and 7
            cache = cache shl f; sh += f
            if (next * 8 - 24 + sh > layer3grLimit) break
            // RELOAD_SCALEFACTOR
            if (--np == 0) { np = sfbTab[sfb++] / 2; if (np == 0) break; one = scf[scfI++] }
            if ((leaf and (128 shr 0)) != 0) { grbuf[dst + 0] = if (cache < 0) -one else one; cache = cache shl 1; sh += 1 }
            if ((leaf and (128 shr 1)) != 0) { grbuf[dst + 1] = if (cache < 0) -one else one; cache = cache shl 1; sh += 1 }
            if (--np == 0) { np = sfbTab[sfb++] / 2; if (np == 0) break; one = scf[scfI++] }
            if ((leaf and (128 shr 2)) != 0) { grbuf[dst + 2] = if (cache < 0) -one else one; cache = cache shl 1; sh += 1 }
            if ((leaf and (128 shr 3)) != 0) { grbuf[dst + 3] = if (cache < 0) -one else one; cache = cache shl 1; sh += 1 }
            while (sh >= 0) { cache = cache or (byteAt(next++) shl sh); sh -= 8 }
            dst += 4
        }
        bs.pos = layer3grLimit
    }

    private fun midsideStereo(left: Int, n: Int) {
        val right = left + 576
        for (i in 0 until n) {
            val a = grbuf[left + i]
            val b = grbuf[right + i]
            grbuf[left + i] = a + b
            grbuf[right + i] = a - b
        }
    }

    private fun intensityStereoBand(left: Int, n: Int, kl: Float, kr: Float) {
        for (i in 0 until n) {
            grbuf[left + i + 576] = grbuf[left + i] * kr
            grbuf[left + i] = grbuf[left + i] * kl
        }
    }

    private fun stereoTopBand(right0: Int, sfb: IntArray, sfbOff: Int, nbands: Int, maxBand: IntArray) {
        var right = right0
        maxBand[0] = -1; maxBand[1] = -1; maxBand[2] = -1
        for (i in 0 until nbands) {
            val w = sfb[sfbOff + i]
            var k = 0
            while (k < w) {
                if (grbuf[right + k] != 0f || grbuf[right + k + 1] != 0f) {
                    maxBand[i % 3] = i
                    break
                }
                k += 2
            }
            right += w
        }
    }

    private fun stereoProcess(left0: Int, ist: IntArray, sfb: IntArray, sfbOff: Int, maxBand: IntArray, mpeg2Sh: Int) {
        val hb = header
        var left = left0
        val maxPos = if (isMpeg1(hb, 0)) 7 else 64
        var i = 0
        while (sfb[sfbOff + i] != 0) {
            val ipos = ist[i]
            val w = sfb[sfbOff + i]
            if (i > maxBand[i % 3] && ipos < maxPos) {
                val s = if (msStereoFlag(hb, 0)) 1.41421356f else 1f
                var kl: Float
                var kr: Float
                if (isMpeg1(hb, 0)) {
                    kl = PAN[2 * ipos]
                    kr = PAN[2 * ipos + 1]
                } else {
                    kl = 1f
                    kr = ldexpQ2(1f, (ipos + 1) shr 1 shl mpeg2Sh)
                    if ((ipos and 1) != 0) {
                        kl = kr
                        kr = 1f
                    }
                }
                intensityStereoBand(left, w, kl * s, kr * s)
            } else if (msStereoFlag(hb, 0)) {
                midsideStereo(left, w)
            }
            left += w
            i++
        }
    }

    private val maxBand = IntArray(3)

    private fun intensityStereo(left: Int, ist: IntArray, grIdx: Int) {
        val gr = grInfo[grIdx]
        val nSfb = gr.nLongSfb + gr.nShortSfb
        val maxBlocks = if (gr.nShortSfb != 0) 3 else 1
        stereoTopBand(left + 576, gr.sfbtab, gr.sfbOff, nSfb, maxBand)
        if (gr.nLongSfb != 0) {
            val m = maxOf(maxOf(maxBand[0], maxBand[1]), maxBand[2])
            maxBand[0] = m; maxBand[1] = m; maxBand[2] = m
        }
        for (i in 0 until maxBlocks) {
            val defaultPos = if (isMpeg1(header, 0)) 3 else 0
            val itop = nSfb - maxBlocks + i
            val prev = itop - maxBlocks
            ist[itop] = if (maxBand[i] >= prev) defaultPos else ist[prev]
        }
        stereoProcess(left, ist, gr.sfbtab, gr.sfbOff, maxBand, grInfo[grIdx + 1].scalefacCompress and 1)
    }

    private fun reorder(grb: Int, sfb: IntArray, sfbOff0: Int) {
        var sfbOff = sfbOff0
        var src = grb
        var dst = 0
        while (true) {
            val len = sfb[sfbOff]
            if (len == 0) break
            for (i in 0 until len) {
                syn[dst++] = grbuf[src + 0 * len]
                syn[dst++] = grbuf[src + 1 * len]
                syn[dst++] = grbuf[src + 2 * len]
                src++
            }
            sfbOff += 3
            src += 2 * len
        }
        System.arraycopy(syn, 0, grbuf, grb, dst)
    }

    private fun antialias(grb0: Int, nbands0: Int) {
        var grb = grb0
        var nbands = nbands0
        while (nbands > 0) {
            for (i in 0 until 8) {
                val u = grbuf[grb + 18 + i]
                val d = grbuf[grb + 17 - i]
                grbuf[grb + 18 + i] = u * AA0[i] - d * AA1[i]
                grbuf[grb + 17 - i] = u * AA1[i] + d * AA0[i]
            }
            nbands--
            grb += 18
        }
    }

    private fun dct3_9(y: FloatArray) {
        var s0 = y[0]; var s2 = y[2]; var s4 = y[4]; var s6 = y[6]; var s8 = y[8]
        var t0 = s0 + s6 * 0.5f
        s0 -= s6
        var t4 = (s4 + s2) * 0.93969262f
        var t2 = (s8 + s2) * 0.76604444f
        s6 = (s4 - s8) * 0.17364818f
        s4 += s8 - s2
        s2 = s0 - s4 * 0.5f
        y[4] = s4 + s0
        s8 = t0 - t2 + s6
        s0 = t0 - t4 + t2
        s4 = t0 + t4 - s6
        var s1 = y[1]; var s3 = y[3]; var s5 = y[5]; var s7 = y[7]
        s3 *= 0.86602540f
        t0 = (s5 + s1) * 0.98480775f
        t4 = (s5 - s7) * 0.34202014f
        t2 = (s1 + s7) * 0.64278761f
        s1 = (s1 - s5 - s7) * 0.86602540f
        s5 = t0 - s3 - t2
        s7 = t4 - s3 - t0
        s3 = t4 + s3 - t2
        y[0] = s4 - s7
        y[1] = s2 + s1
        y[2] = s0 - s3
        y[3] = s8 + s5
        y[5] = s8 - s5
        y[6] = s0 + s3
        y[7] = s2 - s1
        y[8] = s4 + s7
    }

    private val co = FloatArray(9)
    private val si = FloatArray(9)

    private fun imdct36(grb0: Int, overlap: FloatArray, ov0: Int, window: FloatArray, nbands: Int) {
        var grb = grb0
        var ov = ov0
        for (j in 0 until nbands) {
            co[0] = -grbuf[grb + 0]
            si[0] = grbuf[grb + 17]
            for (i in 0 until 4) {
                si[8 - 2 * i] = grbuf[grb + 4 * i + 1] - grbuf[grb + 4 * i + 2]
                co[1 + 2 * i] = grbuf[grb + 4 * i + 1] + grbuf[grb + 4 * i + 2]
                si[7 - 2 * i] = grbuf[grb + 4 * i + 4] - grbuf[grb + 4 * i + 3]
                co[2 + 2 * i] = -(grbuf[grb + 4 * i + 3] + grbuf[grb + 4 * i + 4])
            }
            dct3_9(co)
            dct3_9(si)
            si[1] = -si[1]; si[3] = -si[3]; si[5] = -si[5]; si[7] = -si[7]
            for (i in 0 until 9) {
                val ovl = overlap[ov + i]
                val sum = co[i] * TWID9[9 + i] + si[i] * TWID9[0 + i]
                overlap[ov + i] = co[i] * TWID9[0 + i] - si[i] * TWID9[9 + i]
                grbuf[grb + i] = ovl * window[0 + i] - sum * window[9 + i]
                grbuf[grb + 17 - i] = ovl * window[9 + i] + sum * window[0 + i]
            }
            grb += 18
            ov += 9
        }
    }

    private val co3 = FloatArray(3)
    private val si3 = FloatArray(3)

    private fun idct3(x0: Float, x1: Float, x2: Float, dst: FloatArray) {
        val m1 = x1 * 0.86602540f
        val a1 = x0 - x2 * 0.5f
        dst[1] = x0 + x2
        dst[0] = a1 + m1
        dst[2] = a1 - m1
    }

    /** x は tmp(18)の xo から、dst は (dstArr, dstOff)、overlap は (ovArr, ovOff) */
    private fun imdct12(x: FloatArray, xo: Int, dst: FloatArray, dOff: Int, ovArr: FloatArray, ovOff: Int) {
        idct3(-x[xo + 0], x[xo + 6] + x[xo + 3], x[xo + 12] + x[xo + 9], co3)
        idct3(x[xo + 15], x[xo + 12] - x[xo + 9], x[xo + 6] - x[xo + 3], si3)
        si3[1] = -si3[1]
        for (i in 0 until 3) {
            val ovl = ovArr[ovOff + i]
            val sum = co3[i] * TWID3[3 + i] + si3[i] * TWID3[0 + i]
            ovArr[ovOff + i] = co3[i] * TWID3[0 + i] - si3[i] * TWID3[3 + i]
            dst[dOff + i] = ovl * TWID3[2 - i] - sum * TWID3[5 - i]
            dst[dOff + 5 - i] = ovl * TWID3[5 - i] + sum * TWID3[2 - i]
        }
    }

    private val tmp18 = FloatArray(18)

    private fun imdctShort(grb0: Int, overlap: FloatArray, ov0: Int, nbands0: Int) {
        var grb = grb0
        var ov = ov0
        var nbands = nbands0
        while (nbands > 0) {
            System.arraycopy(grbuf, grb, tmp18, 0, 18)
            System.arraycopy(overlap, ov, grbuf, grb, 6)
            imdct12(tmp18, 0, grbuf, grb + 6, overlap, ov + 6)
            imdct12(tmp18, 1, grbuf, grb + 12, overlap, ov + 6)
            imdct12(tmp18, 2, overlap, ov, overlap, ov + 6)
            nbands--
            ov += 9
            grb += 18
        }
    }

    private fun changeSign(grb0: Int) {
        var grb = grb0 + 18
        var b = 0
        while (b < 32) {
            var i = 1
            while (i < 18) {
                grbuf[grb + i] = -grbuf[grb + i]
                i += 2
            }
            b += 2
            grb += 36
        }
    }

    private fun imdctGr(grb0: Int, overlap: FloatArray, blockType: Int, nLongBands: Int) {
        var grb = grb0
        var ov = 0
        if (nLongBands != 0) {
            imdct36(grb, overlap, ov, MDCT_WINDOW0, nLongBands)
            grb += 18 * nLongBands
            ov += 9 * nLongBands
        }
        if (blockType == SHORT_BLOCK_TYPE) imdctShort(grb, overlap, ov, 32 - nLongBands)
        else imdct36(grb, overlap, ov, if (blockType == STOP_BLOCK_TYPE) MDCT_WINDOW1 else MDCT_WINDOW0, 32 - nLongBands)
    }

    private fun l3SaveReservoir() {
        var pos = (mainBs.pos + 7) / 8
        var remains = mainBs.limit / 8 - pos
        if (remains > MAX_BITRESERVOIR_BYTES) {
            pos += remains - MAX_BITRESERVOIR_BYTES
            remains = MAX_BITRESERVOIR_BYTES
        }
        if (remains > 0) System.arraycopy(maindata, pos, reservBuf, 0, remains)
        reserv = remains
    }

    private fun l3RestoreReservoir(bs: Bs, mainDataBegin: Int): Boolean {
        val frameBytes = (bs.limit - bs.pos) / 8
        val bytesHave = minOf(reserv, mainDataBegin)
        System.arraycopy(reservBuf, maxOf(0, reserv - mainDataBegin), maindata, 0, minOf(reserv, mainDataBegin))
        val src = bs.base + bs.pos / 8
        val n = minOf(frameBytes, bs.buf.size - src).coerceAtLeast(0)
        System.arraycopy(bs.buf, src, maindata, bytesHave, n)
        // 前のフレームの残りが読まれないように、後ろを 0 にする
        java.util.Arrays.fill(maindata, bytesHave + n, minOf(maindata.size, bytesHave + n + 8), 0)
        mainBs.init(maindata, 0, bytesHave + frameBytes)
        return reserv >= mainDataBegin
    }

    private fun l3Decode(grBase: Int, nch: Int) {
        for (ch in 0 until nch) {
            val gr = grInfo[grBase + ch]
            val limit = mainBs.pos + gr.part23Length
            l3DecodeScalefactors(istPos[ch], mainBs, gr, ch)
            l3Huffman(576 * ch, mainBs, gr, limit)
        }
        if (iStereo(header, 0)) {
            intensityStereo(0, istPos[1], grBase)
        } else if (isMsStereo(header, 0)) {
            midsideStereo(0, 576)
        }
        for (ch in 0 until nch) {
            val gr = grInfo[grBase + ch]
            var aaBands = 31
            val nLongBands = (if (gr.mixedBlockFlag != 0) 2 else 0) shl (if (mySampleRate(header, 0) == 2) 1 else 0)
            if (gr.nShortSfb != 0) {
                aaBands = nLongBands - 1
                reorder(576 * ch + nLongBands * 18, gr.sfbtab, gr.sfbOff + gr.nLongSfb)
            }
            antialias(576 * ch, aaBands)
            imdctGr(576 * ch, mdctOverlap[ch], gr.blockType, nLongBands)
            changeSign(576 * ch)
        }
    }

    // ================= 合成フィルター =================

    private val t4 = Array(4) { FloatArray(8) }

    private fun dctII(grb: Int, n: Int) {
        for (k in 0 until n) {
            var y = grb + k
            for (i in 0 until 8) {
                val x0 = grbuf[y + i * 18]
                val x1 = grbuf[y + (15 - i) * 18]
                val x2 = grbuf[y + (16 + i) * 18]
                val x3 = grbuf[y + (31 - i) * 18]
                val t0 = x0 + x3
                val t1 = x1 + x2
                val t2 = (x1 - x2) * SEC[3 * i + 0]
                val t3 = (x0 - x3) * SEC[3 * i + 1]
                t4[0][i] = t0 + t1
                t4[1][i] = (t0 - t1) * SEC[3 * i + 2]
                t4[2][i] = t3 + t2
                t4[3][i] = (t3 - t2) * SEC[3 * i + 2]
            }
            for (r in 0 until 4) {
                val x = t4[r]
                var x0 = x[0]; var x1 = x[1]; var x2 = x[2]; var x3 = x[3]; var x4 = x[4]; var x5 = x[5]; var x6 = x[6]; var x7 = x[7]
                var xt = x0 - x7; x0 += x7
                x7 = x1 - x6; x1 += x6
                x6 = x2 - x5; x2 += x5
                x5 = x3 - x4; x3 += x4
                x4 = x0 - x3; x0 += x3
                x3 = x1 - x2; x1 += x2
                x[0] = x0 + x1
                x[4] = (x0 - x1) * 0.70710677f
                x5 = x5 + x6
                x6 = (x6 + x7) * 0.70710677f
                x7 = x7 + xt
                x3 = (x3 + x4) * 0.70710677f
                x5 -= x7 * 0.198912367f
                x7 += x5 * 0.382683432f
                x5 -= x7 * 0.198912367f
                x0 = xt - x6; xt += x6
                x[1] = (xt + x7) * 0.50979561f
                x[2] = (x4 + x3) * 0.54119611f
                x[3] = (x0 - x5) * 0.60134488f
                x[5] = (x0 + x5) * 0.89997619f
                x[6] = (x4 - x3) * 1.30656302f
                x[7] = (xt - x7) * 2.56291556f
            }
            for (i in 0 until 7) {
                grbuf[y + 0 * 18] = t4[0][i]
                grbuf[y + 1 * 18] = t4[2][i] + t4[3][i] + t4[3][i + 1]
                grbuf[y + 2 * 18] = t4[1][i] + t4[1][i + 1]
                grbuf[y + 3 * 18] = t4[2][i + 1] + t4[3][i] + t4[3][i + 1]
                y += 4 * 18
            }
            grbuf[y + 0 * 18] = t4[0][7]
            grbuf[y + 1 * 18] = t4[2][7] + t4[3][7]
            grbuf[y + 2 * 18] = t4[1][7]
            grbuf[y + 3 * 18] = t4[3][7]
        }
    }

    private fun scale(s: Float) = s * (1f / 32768f)

    private fun synthPair(pcm: FloatArray, p: Int, nch: Int, z: FloatArray, zo0: Int) {
        var zo = zo0
        var a = (z[zo + 14 * 64] - z[zo + 0]) * 29
        a += (z[zo + 1 * 64] + z[zo + 13 * 64]) * 213
        a += (z[zo + 12 * 64] - z[zo + 2 * 64]) * 459
        a += (z[zo + 3 * 64] + z[zo + 11 * 64]) * 2037
        a += (z[zo + 10 * 64] - z[zo + 4 * 64]) * 5153
        a += (z[zo + 5 * 64] + z[zo + 9 * 64]) * 6574
        a += (z[zo + 8 * 64] - z[zo + 6 * 64]) * 37489
        a += z[zo + 7 * 64] * 75038
        pcm[p] = scale(a)
        zo += 2
        a = z[zo + 14 * 64] * 104
        a += z[zo + 12 * 64] * 1567
        a += z[zo + 10 * 64] * 9727
        a += z[zo + 8 * 64] * 64019
        a += z[zo + 6 * 64] * -9975
        a += z[zo + 4 * 64] * -45
        a += z[zo + 2 * 64] * 146
        a += z[zo + 0 * 64] * -5
        pcm[p + 16 * nch] = scale(a)
    }

    private val sa = FloatArray(4)
    private val sb = FloatArray(4)

    /** xl は grbuf の位置、lins は syn の位置(zlin = lins + 15*64) */
    private fun synth(xl: Int, pcm: FloatArray, dstl: Int, nch: Int, lins: Int) {
        val xr = xl + 576 * (nch - 1)
        val dstr = dstl + (nch - 1)
        val z = syn
        val zlin = lins + 15 * 64
        var w = 0
        z[zlin + 4 * 15] = grbuf[xl + 18 * 16]
        z[zlin + 4 * 15 + 1] = grbuf[xr + 18 * 16]
        z[zlin + 4 * 15 + 2] = grbuf[xl + 0]
        z[zlin + 4 * 15 + 3] = grbuf[xr + 0]
        z[zlin + 4 * 31] = grbuf[xl + 1 + 18 * 16]
        z[zlin + 4 * 31 + 1] = grbuf[xr + 1 + 18 * 16]
        z[zlin + 4 * 31 + 2] = grbuf[xl + 1]
        z[zlin + 4 * 31 + 3] = grbuf[xr + 1]
        synthPair(pcm, dstr, nch, z, lins + 4 * 15 + 1)
        synthPair(pcm, dstr + 32 * nch, nch, z, lins + 4 * 15 + 64 + 1)
        synthPair(pcm, dstl, nch, z, lins + 4 * 15)
        synthPair(pcm, dstl + 32 * nch, nch, z, lins + 4 * 15 + 64)
        val win = Mp3Tables.WIN
        for (i in 14 downTo 0) {
            z[zlin + 4 * i] = grbuf[xl + 18 * (31 - i)]
            z[zlin + 4 * i + 1] = grbuf[xr + 18 * (31 - i)]
            z[zlin + 4 * i + 2] = grbuf[xl + 1 + 18 * (31 - i)]
            z[zlin + 4 * i + 3] = grbuf[xr + 1 + 18 * (31 - i)]
            z[zlin + 4 * (i + 16)] = grbuf[xl + 1 + 18 * (1 + i)]
            z[zlin + 4 * (i + 16) + 1] = grbuf[xr + 1 + 18 * (1 + i)]
            z[zlin + 4 * (i - 16) + 2] = grbuf[xl + 18 * (1 + i)]
            z[zlin + 4 * (i - 16) + 3] = grbuf[xr + 18 * (1 + i)]
            for (k in 0 until 8) {
                val w0 = win[w++]
                val w1 = win[w++]
                val vz = zlin + 4 * i - k * 64
                val vy = zlin + 4 * i - (15 - k) * 64
                when {
                    k == 0 -> for (j in 0 until 4) {
                        sb[j] = z[vz + j] * w1 + z[vy + j] * w0
                        sa[j] = z[vz + j] * w0 - z[vy + j] * w1
                    }
                    (k and 1) == 1 -> for (j in 0 until 4) { // S2
                        sb[j] += z[vz + j] * w1 + z[vy + j] * w0
                        sa[j] += z[vy + j] * w1 - z[vz + j] * w0
                    }
                    else -> for (j in 0 until 4) { // S1
                        sb[j] += z[vz + j] * w1 + z[vy + j] * w0
                        sa[j] += z[vz + j] * w0 - z[vy + j] * w1
                    }
                }
            }
            pcm[dstr + (15 - i) * nch] = scale(sa[1])
            pcm[dstr + (17 + i) * nch] = scale(sb[1])
            pcm[dstl + (15 - i) * nch] = scale(sa[0])
            pcm[dstl + (17 + i) * nch] = scale(sb[0])
            pcm[dstr + (47 - i) * nch] = scale(sa[3])
            pcm[dstr + (49 + i) * nch] = scale(sb[3])
            pcm[dstl + (47 - i) * nch] = scale(sa[2])
            pcm[dstl + (49 + i) * nch] = scale(sb[2])
        }
    }

    private fun synthGranule(nbands: Int, nch: Int, pcm: FloatArray, p: Int) {
        for (i in 0 until nch) dctII(576 * i, nbands)
        System.arraycopy(qmfState, 0, syn, 0, 15 * 64)
        var i = 0
        while (i < nbands) {
            synth(i, pcm, p + 32 * nch * i, nch, i * 64)
            i += 2
        }
        if (nch == 1) {
            var k = 0
            while (k < 15 * 64) {
                qmfState[k] = syn[nbands * 64 + k]
                k += 2
            }
        } else {
            System.arraycopy(syn, nbands * 64, qmfState, 0, 15 * 64)
        }
    }

    companion object {
        const val MAX_SAMPLES_PER_FRAME = 1152 * 2
        private const val MAX_FREE_FORMAT_FRAME_SIZE = 2304
        private const val MAX_FRAME_SYNC_MATCHES = 10
        private const val MAX_L3_FRAME_PAYLOAD_BYTES = MAX_FREE_FORMAT_FRAME_SIZE
        private const val MAX_BITRESERVOIR_BYTES = 511
        private const val SHORT_BLOCK_TYPE = 2
        private const val STOP_BLOCK_TYPE = 3
        private const val MODE_MONO = 3
        private const val MODE_JOINT_STEREO = 1
        private const val HDR_SIZE = 4
        private const val BITS_DEQUANTIZER_OUT = -1
        private const val MAX_SCF = 255 + BITS_DEQUANTIZER_OUT * 4 - 210
        private const val MAX_SCFI = (MAX_SCF + 3) and 3.inv()

        private val HALFRATE = intArrayOf(
            0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 72, 80, 0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 72, 80,
            0, 16, 24, 28, 32, 40, 48, 56, 64, 72, 80, 88, 96, 112, 128,
            0, 16, 20, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 0, 16, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192,
            0, 16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224,
        )
        private val HZ = intArrayOf(44100, 48000, 32000)
        private val DEQ_L12: FloatArray = run {
            val div = intArrayOf(3, 7, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767, 65535, 3, 5, 9)
            val f = FloatArray(18 * 3)
            for (i in div.indices) {
                f[i * 3] = 9.53674316e-07f / div[i]
                f[i * 3 + 1] = 7.56931807e-07f / div[i]
                f[i * 3 + 2] = 6.00777173e-07f / div[i]
            }
            f
        }
        private val BITALLOC_CODE_TAB = intArrayOf(
            0, 17, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            0, 17, 18, 3, 19, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16,
            0, 17, 18, 3, 19, 4, 5, 16,
            0, 17, 18, 16,
            0, 17, 18, 19, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            0, 17, 18, 3, 19, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        )
        private val SCF_PARTITIONS = intArrayOf(
            6, 5, 5, 5, 6, 5, 5, 5, 6, 5, 7, 3, 11, 10, 0, 0, 7, 7, 7, 0, 6, 6, 6, 3, 8, 8, 5, 0,
            8, 9, 6, 12, 6, 9, 9, 9, 6, 9, 12, 6, 15, 18, 0, 0, 6, 15, 12, 0, 6, 12, 9, 6, 6, 18, 9, 0,
            9, 9, 6, 12, 9, 9, 9, 9, 9, 9, 12, 6, 18, 18, 0, 0, 12, 12, 12, 0, 12, 9, 9, 6, 15, 12, 9, 0,
        )
        private val SCFC_DECODE = intArrayOf(0, 1, 2, 3, 12, 5, 6, 7, 9, 10, 11, 13, 14, 15, 18, 19)
        private val MOD = intArrayOf(5, 5, 4, 4, 5, 5, 4, 1, 4, 3, 1, 1, 5, 6, 6, 1, 4, 4, 4, 1, 4, 3, 1, 1)
        private val PREAMP = intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 3, 2)
        private val EXPFRAC = floatArrayOf(9.31322575e-10f, 7.83145814e-10f, 6.58544508e-10f, 5.53767716e-10f)
        private val PAN = floatArrayOf(0f, 1f, 0.21132487f, 0.78867513f, 0.36602540f, 0.63397460f, 0.5f, 0.5f, 0.63397460f, 0.36602540f, 0.78867513f, 0.21132487f, 1f, 0f)
        private val AA0 = floatArrayOf(0.85749293f, 0.88174200f, 0.94962865f, 0.98331459f, 0.99551782f, 0.99916056f, 0.99989920f, 0.99999316f)
        private val AA1 = floatArrayOf(0.51449576f, 0.47173197f, 0.31337745f, 0.18191320f, 0.09457419f, 0.04096558f, 0.01419856f, 0.00369997f)
        private val TWID9 = floatArrayOf(
            0.73727734f, 0.79335334f, 0.84339145f, 0.88701083f, 0.92387953f, 0.95371695f, 0.97629601f, 0.99144486f, 0.99904822f,
            0.67559021f, 0.60876143f, 0.53729961f, 0.46174861f, 0.38268343f, 0.30070580f, 0.21643961f, 0.13052619f, 0.04361938f,
        )
        private val TWID3 = floatArrayOf(0.79335334f, 0.92387953f, 0.99144486f, 0.60876143f, 0.38268343f, 0.13052619f)
        private val MDCT_WINDOW0 = floatArrayOf(
            0.99904822f, 0.99144486f, 0.97629601f, 0.95371695f, 0.92387953f, 0.88701083f, 0.84339145f, 0.79335334f, 0.73727734f,
            0.04361938f, 0.13052619f, 0.21643961f, 0.30070580f, 0.38268343f, 0.46174861f, 0.53729961f, 0.60876143f, 0.67559021f,
        )
        private val MDCT_WINDOW1 = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 0.99144486f, 0.92387953f, 0.79335334f, 0f, 0f, 0f, 0f, 0f, 0f, 0.13052619f, 0.38268343f, 0.60876143f)
        private val SEC = floatArrayOf(
            10.19000816f, 0.50060302f, 0.50241929f, 3.40760851f, 0.50547093f, 0.52249861f, 2.05778098f, 0.51544732f, 0.56694406f,
            1.48416460f, 0.53104258f, 0.64682180f, 1.16943991f, 0.55310392f, 0.78815460f, 0.97256821f, 0.58293498f, 1.06067765f,
            0.83934963f, 0.62250412f, 1.72244716f, 0.74453628f, 0.67480832f, 5.10114861f,
        )
    }
}
