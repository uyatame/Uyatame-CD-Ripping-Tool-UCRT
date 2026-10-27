package com.uyatame.ucrt.player.aac

import com.uyatame.ucrt.player.Mp4Track
import com.uyatame.ucrt.player.PcmDecoder
import com.uyatame.ucrt.player.RandomInput
import java.io.IOException

/**
 * AAC のファイル(M4A / MP4 と ADTS の .aac)を、自前の AAC デコーダーで 16 ビットの PCM にする。
 * MP4 は編集リスト(elst)か iTunes の情報(iTunSMPB)で、先頭の無音(エンコーダーの遅れ)と末尾の詰め物を除く
 */
internal class AacPcmDecoder(private val src: RandomInput) : PcmDecoder {
    override var sampleRate = 0
        private set
    override var channels = 0
        private set
    override val bitsPerSample = 16
    override var totalSamples = 0L
        private set
    override var samples: Array<IntArray> = emptyArray()
        private set
    override var blockSize = 0
        private set
    override var frameSample = 0L
        private set

    private lateinit var dec: AacDecoder
    /** パケット(MP4 のサンプル・ADTS のフレームの中身)の位置と大きさ */
    private var pkOffset = LongArray(0)
    private var pkSize = IntArray(0)
    private var next = 0
    /** 1 パケットの出力のサンプル数 */
    private var frameLen = 1024
    /** 先頭の除くサンプルの数 */
    private var delay = 0L
    /** 除いたあとの長さ(分からなければ -1。そのときは最後まで出す) */
    private var length = -1L
    /** 次のパケットの、除く前の最初のサンプルの番号 */
    private var raw = 0L
    private var pk = ByteArray(0)

    override fun open() {
        val head = ByteArray(12)
        val n = src.read(0, head, 0, head.size)
        if (n >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()) {
            openMp4()
        } else {
            openAdts()
        }
        channels = dec.channels
        samples = Array(channels) { IntArray(frameLen) }
        next = 0
        raw = 0
    }

    private fun openMp4() {
        val t = Mp4Track.open(src, setOf("mp4a"))
        val asc = esds(t.stsd) ?: throw IOException("AAC: no esds")
        val cfg = AudioSpecificConfig.parse(asc)
        if (cfg.frame960) throw AacException("AAC: 960-sample frames not supported")
        pkOffset = t.offsets
        pkSize = t.sizes
        start(cfg)
        val count = t.count.toLong()
        // 時間の単位 → 出力のサンプル
        val ts = t.timescale
        fun toOut(x: Long): Long = if (ts > 0 && ts != sampleRate.toLong()) x * sampleRate / ts else x
        val all = count * frameLen
        val gap = t.itunesGapless
        when {
            t.editStart > 0 || (t.editStart == 0L && t.editLength > 0) -> {
                delay = toOut(t.editStart)
                length = if (t.editLength > 0) toOut(t.editLength) else -1
            }
            gap != null && gap[2] > 0 -> {
                delay = gap[0]
                length = gap[2]
            }
        }
        if (length > all - delay || length < 0) length = -1
        totalSamples = if (length >= 0) length else maxOf(0, all - delay)
    }

    private fun openAdts() {
        // 先頭の ID3v2
        var p = 0L
        while (true) {
            val h = read(p, 10) ?: break
            if (!(h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte())) break
            p += 10 + ((h[6].toLong() and 0x7f) shl 21 or ((h[7].toLong() and 0x7f) shl 14) or ((h[8].toLong() and 0x7f) shl 7) or (h[9].toLong() and 0x7f))
            if ((h[5].toInt() and 16) != 0) p += 10
        }
        // 最初の見出しまで(ゴミが少しあってもよい)
        val first = read(p, minOf(65536L, src.size - p).toInt().coerceAtLeast(0)) ?: throw IOException("AAC: no data")
        var s = -1
        for (i in 0 until first.size - 7) {
            if (first[i] == 0xFF.toByte() && (first[i + 1].toInt() and 0xF6) == 0xF0) {
                val len = ((first[i + 3].toInt() and 3) shl 11) or ((first[i + 4].toInt() and 0xFF) shl 3) or ((first[i + 5].toInt() and 0xFF) ushr 5)
                // 次の見出しも続いているか(偶然の一致を除く)
                if (len >= 7 && (i + len + 2 > first.size || (first[i + len] == 0xFF.toByte() && (first[i + len + 1].toInt() and 0xF6) == 0xF0))) {
                    s = i
                    break
                }
            }
        }
        if (s < 0) throw IOException("AAC: no ADTS frame")
        p += s
        val h0 = read(p, 7)!!
        val profile = (h0[2].toInt() and 0xFF) ushr 6
        val sfi = ((h0[2].toInt() and 0xFF) ushr 2) and 15
        val chanCfg = ((h0[2].toInt() and 1) shl 2) or ((h0[3].toInt() and 0xFF) ushr 6)
        if (profile != 1) throw AacException("AAC: ADTS profile ${profile + 1} not supported")
        val cfg = AudioSpecificConfig.fromAdts(profile, sfi, chanCfg)
        // フレームの一覧(見出しだけをたどる)
        val offs = ArrayList<Long>()
        val sizes = ArrayList<Int>()
        val size = src.size
        val h = ByteArray(9)
        while (p + 7 <= size) {
            if (src.read(p, h, 0, 9.coerceAtMost((size - p).toInt())) < 7) break
            if (h[0] != 0xFF.toByte() || (h[1].toInt() and 0xF6) != 0xF0) break
            val protectionAbsent = h[1].toInt() and 1
            val len = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xFF) ushr 5)
            val blocks = (h[6].toInt() and 3) + 1
            val hl = if (protectionAbsent == 1) 7 else 9
            if (len < hl || p + len > size) break
            if (blocks != 1) throw AacException("AAC: ADTS with $blocks blocks not supported")
            offs.add(p + hl)
            sizes.add(len - hl)
            p += len
        }
        if (offs.isEmpty()) throw IOException("AAC: no ADTS frame")
        pkOffset = offs.toLongArray()
        pkSize = sizes.toIntArray()
        start(cfg)
        totalSamples = pkSize.size.toLong() * frameLen
    }

    /**
     * デコーダーを作る。SBR(HE-AAC)が書かれていなければ、中の周波数が 24 kHz 以下のときに最初のフレームを見て決める
     * (ffmpeg と同じ)。モノラルの HE-AAC は、PS が無いと書かれていなければステレオにする(ffmpeg と同じ)
     */
    private fun start(cfg: AudioSpecificConfig) {
        var sbr = cfg.sbr == true
        if (cfg.sbr == null && cfg.sampleRate <= 24000 && pkSize.isNotEmpty()) {
            val probe = AacDecoder(cfg)
            val p = readPacket(0)
            if (p != null) runCatching { probe.decodeFrame(p, 0, pkSize[0]) }
            sbr = probe.sbrSeen
        }
        val ps = sbr && cfg.channels == 1 && cfg.ps != false
        dec = AacDecoder(cfg, sbr, ps)
        frameLen = dec.frameLength
        sampleRate = if (sbr) cfg.sampleRate * 2 else cfg.sampleRate
    }

    /** i 番目のパケットを pk に読む。読めなければ null */
    private fun readPacket(i: Int): ByteArray? {
        val size = pkSize[i]
        if (size <= 0 || size > (1 shl 20)) return null
        if (pk.size < size) pk = ByteArray(size)
        var off = 0
        while (off < size) {
            val r = src.read(pkOffset[i] + off, pk, off, size - off)
            if (r <= 0) return null
            off += r
        }
        return pk
    }

    private fun read(pos: Long, n: Int): ByteArray? {
        if (pos < 0 || pos + n > src.size) return null
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = src.read(pos + off, b, off, n - off)
            if (r <= 0) return null
            off += r
        }
        return b
    }

    override fun decodeNext(): Boolean {
        while (true) {
            val i = next
            if (i >= pkSize.size) return false
            if (length >= 0 && raw - delay >= length) return false
            next++
            val size = pkSize[i]
            if (size <= 0 || size > (1 shl 20)) throw IOException("AAC: bad packet size $size")
            if (pk.size < size) pk = ByteArray(size)
            var off = 0
            while (off < size) {
                val r = src.read(pkOffset[i] + off, pk, off, size - off)
                if (r <= 0) return false // 途中で切れたファイル
                off += r
            }
            // 壊れたフレームは無音にして続ける(長さは保つ)
            try {
                dec.decodeFrame(pk, 0, size)
            } catch (e: AacException) {
            }
            val n = frameLen
            val s0 = raw - delay
            raw += n
            var first = 0
            var last = n
            if (s0 < 0) first = minOf(n.toLong(), -s0).toInt()
            if (length >= 0 && s0 + last > length) last = maxOf(first.toLong(), length - s0).toInt()
            if (last <= first) continue
            val out = dec.output
            val map = if (channels in 3..8) CHANNEL_OFFSETS[channels - 1] else null
            for (c in 0 until channels) {
                val o = out[c]
                val d = samples[map?.get(c) ?: c]
                for (k in first until last) {
                    val v = Math.round(o[k] * 32768f)
                    d[k - first] = if (v > 32767) 32767 else if (v < -32768) -32768 else v
                }
            }
            blockSize = last - first
            frameSample = s0 + first
            return true
        }
    }

    override fun seekSample(target: Long) {
        val want = target.coerceAtLeast(0) + delay
        var p = (want / frameLen).toInt().coerceIn(0, pkSize.size)
        // 前のパケットから(前のフレームとの重ね合わせのため。その分は呼び出し側で捨てる)。
        // SBR は時間の前後で差を取る値や QMF のつながりがあるので、2 つ前から
        repeat(if (dec.sbr) 2 else 1) { if (p > 0) p-- }
        dec.reset()
        next = p
        raw = p.toLong() * frameLen
    }

    companion object {
        /**
         * AAC のチャンネルの順番 → 出力(WAV の順番)の位置。
         * AAC の並び: 3ch = C L R、5.1ch = C L R Ls Rs LFE、7.1ch = C Lc Rc L R Ls Rs LFE(ALAC と同じ)
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

        /** サンプルエントリー(mp4a)の中の esds から AudioSpecificConfig を取り出す */
        fun esds(stsd: ByteArray): ByteArray? {
            val entrySize = Mp4Track.u32(stsd, 8).toInt().coerceAtMost(stsd.size - 8)
            val end = 8 + entrySize
            val ver = if (entrySize >= 26) Mp4Track.u16(stsd, 8 + 16) else 0
            // 項目の頭は 28 バイト(QuickTime の版 1 は +16、版 2 は +36)。その後ろの箱(wave の中のこともある)から探す
            var p = 8 + 8 + 28 + (if (ver == 1) 16 else if (ver == 2) 36 else 0)
            val esds = Mp4Track.fourcc("esds")
            while (p + 12 <= end) {
                val sz = Mp4Track.u32(stsd, p)
                if (Mp4Track.i32(stsd, p + 4) == esds && sz >= 12 && p + sz <= end) {
                    return descriptors(stsd, p + 12, (p + sz).toInt())
                }
                p++
            }
            return null
        }

        /** ES_Descriptor → DecoderConfigDescriptor → DecoderSpecificInfo */
        private fun descriptors(b: ByteArray, start: Int, end: Int): ByteArray? {
            var p = start
            fun len(): Int {
                var n = 0
                for (i in 0 until 4) {
                    if (p >= end) return -1
                    val c = b[p++].toInt() and 0xFF
                    n = (n shl 7) or (c and 0x7F)
                    if (c and 0x80 == 0) break
                }
                return n
            }
            while (p < end) {
                val tag = b[p++].toInt() and 0xFF
                val n = len()
                if (n < 0) return null
                when (tag) {
                    3 -> {
                        // ES_ID(2)とフラグ(1)。フラグによって後ろに足りないものがつく
                        if (p + 3 > end) return null
                        val flags = b[p + 2].toInt() and 0xFF
                        p += 3
                        if (flags and 0x80 != 0) p += 2
                        if (flags and 0x40 != 0) { if (p >= end) return null; p += 1 + (b[p].toInt() and 0xFF) }
                        if (flags and 0x20 != 0) p += 2
                    }
                    4 -> {
                        if (p + 13 > end) return null
                        val oti = b[p].toInt() and 0xFF
                        // 0x40: MPEG-4 の音声、0x66〜0x68: MPEG-2 の AAC
                        if (oti != 0x40 && oti !in 0x66..0x68) throw AacException("AAC: object type indication $oti not supported")
                        p += 13
                    }
                    5 -> {
                        if (p + n > end || n <= 0) return null
                        return b.copyOfRange(p, p + n)
                    }
                    else -> p += n
                }
            }
            return null
        }
    }
}
