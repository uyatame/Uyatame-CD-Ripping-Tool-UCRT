package com.uyatame.ucrt.convert

import com.uyatame.ucrt.meta.Cover
import com.uyatame.ucrt.rip.BitWriter
import com.uyatame.ucrt.rip.FlacHeader
import com.uyatame.ucrt.rip.Id3Tags
import com.uyatame.ucrt.rip.Id3v2
import com.uyatame.ucrt.tags.TagExtra
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** 書き出すファイルに付ける曲の情報(元のファイルから写す) */
class OutTags(val tags: Id3Tags, val genre: String, val extra: TagExtra?, val cover: Cover?) {
    /** FLAC の VORBIS_COMMENT */
    fun vorbis(): List<Pair<String, String>> = buildList {
        fun add(k: String, v: String) { if (v.isNotBlank()) add(k to v) }
        add("TITLE", tags.title); add("ARTIST", tags.artist); add("ALBUM", tags.album)
        add("ALBUMARTIST", tags.albumArtist); add("DATE", tags.year); add("GENRE", genre)
        if (tags.track > 0) add("TRACKNUMBER", tags.track.toString())
        if (tags.trackTotal > 0) add("TRACKTOTAL", tags.trackTotal.toString())
        if (tags.disc > 0) add("DISCNUMBER", tags.disc.toString())
        if (tags.discTotal > 0) add("DISCTOTAL", tags.discTotal.toString())
        extra?.vorbisPairs()?.forEach { (k, v) -> if (k != "GENRE") add(k, v) }
    }

    /** ID3v2(DSF・DFF・WAV) */
    fun id3(): ByteArray = Id3v2.build(tags, cover, extraFrames = extra?.id3Frames() ?: ByteArray(0))
}

/** 書き出し先(位置を戻して先頭を書き直せるファイル) */
class SeekOut(private val ch: FileChannel) {
    private val buf = ByteBuffer.allocate(1 shl 18)
    var pos = 0L
        private set

    fun write(b: ByteArray, off: Int = 0, len: Int = b.size) {
        var o = off
        var n = len
        while (n > 0) {
            if (!buf.hasRemaining()) drain()
            val k = minOf(n, buf.remaining())
            buf.put(b, o, k)
            o += k; n -= k
        }
        pos += len
    }

    fun byte(v: Int) { if (!buf.hasRemaining()) drain(); buf.put(v.toByte()); pos++ }

    private fun drain() {
        buf.flip()
        while (buf.hasRemaining()) ch.write(buf)
        buf.clear()
    }

    fun flush() = drain()

    /** at の位置に b を書く(書いたところはもう flush 済みであること) */
    fun patch(at: Long, b: ByteArray) {
        drain()
        val bb = ByteBuffer.wrap(b)
        var p = at
        while (bb.hasRemaining()) p += ch.write(bb, p)
    }
}

/** PCM の書き出し(FLAC・WAV) */
interface PcmWriter {
    /** チャンネル交互の float(±1 がフルスケール)を frames フレーム書く */
    fun write(buf: FloatArray, frames: Int)
    fun finish()
}

/** DSD の書き出し(DSF・DFF)。data[c] の n バイト(先頭のビットを最上位に) */
interface DsdWriter {
    fun write(data: Array<ByteArray>, n: Int)
    fun finish()
}

private fun le(v: Long, n: Int) = ByteArray(n) { ((v ushr (8 * it)) and 0xFF).toByte() }
private fun be(v: Long, n: Int) = ByteArray(n) { ((v ushr (8 * (n - 1 - it))) and 0xFF).toByte() }

/**
 * WAV(16・24bit の整数、または 32bit の浮動小数点)。3 チャンネル以上や 16bit より細かいときは WAVE_FORMAT_EXTENSIBLE。
 * 長さは最後に書き直す(4 GB を超えるときは RF64 の ds64 を使う)
 */
class WavWriter(private val out: SeekOut, private val rate: Int, private val ch: Int, private val bits: Int, tags: OutTags?) : PcmWriter {
    private val float = bits == 32
    private val bps = bits / 8
    private val q = if (float) null else Quantizer(bits)
    private val meta: ByteArray
    private var bytes = ByteArray(0)
    private var dataLen = 0L
    private val dataSizeAt: Long
    private val headerLen: Int

    init {
        val ext = ch > 2 || bits > 16
        val fmt = ByteBuffer.allocate(if (ext) 40 else 16).order(ByteOrder.LITTLE_ENDIAN)
        fmt.putShort((if (ext) 0xFFFE else if (float) 3 else 1).toShort())
        fmt.putShort(ch.toShort()); fmt.putInt(rate); fmt.putInt(rate * ch * bps)
        fmt.putShort((ch * bps).toShort()); fmt.putShort(bits.toShort())
        if (ext) {
            fmt.putShort(22); fmt.putShort(bits.toShort())
            fmt.putInt(channelMask(ch))
            // サブフォーマット(PCM か IEEE float)の GUID
            fmt.putInt(if (float) 3 else 1)
            fmt.put(byteArrayOf(0x00, 0x00, 0x10, 0x00, 0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71))
        }
        meta = tags?.let { metaChunks(it) } ?: ByteArray(0)
        val h = ByteArrayOutputStream()
        h.write("RIFF".toByteArray()); h.write(le(0, 4)); h.write("WAVE".toByteArray())
        // RF64 にするときのための場所(JUNK。4 GB を超えたら ds64 に書き換える)
        h.write("JUNK".toByteArray()); h.write(le(28, 4)); h.write(ByteArray(28))
        h.write("fmt ".toByteArray()); h.write(le(fmt.capacity().toLong(), 4)); h.write(fmt.array())
        h.write(meta)
        h.write("data".toByteArray())
        dataSizeAt = h.size().toLong()
        h.write(le(0, 4))
        val head = h.toByteArray()
        headerLen = head.size
        out.write(head)
    }

    private fun metaChunks(t: OutTags): ByteArray {
        val id3 = t.id3()
        val o = ByteArrayOutputStream()
        o.write("id3 ".toByteArray()); o.write(le(id3.size.toLong(), 4)); o.write(id3)
        if (id3.size % 2 == 1) o.write(0)
        return o.toByteArray()
    }

    override fun write(buf: FloatArray, frames: Int) {
        val n = frames * ch
        if (bytes.size < n * bps) bytes = ByteArray(n * bps)
        val b = bytes
        var o = 0
        if (float) {
            for (i in 0 until n) {
                val v = java.lang.Float.floatToRawIntBits(buf[i])
                b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
                o += 4
            }
        } else {
            val qq = q!!
            for (i in 0 until n) {
                val v = qq.q(buf[i])
                b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
                if (bps == 3) b[o + 2] = (v shr 16).toByte()
                o += bps
            }
        }
        out.write(b, 0, o)
        dataLen += o
    }

    override fun finish() {
        if (dataLen % 2 == 1L) out.byte(0)
        out.flush()
        val riff = out.pos - 8
        if (riff <= 0xFFFFFFFFL) {
            out.patch(4, le(riff, 4))
            out.patch(dataSizeAt, le(dataLen, 4))
        } else {
            // RF64: 先頭を RF64 にし、JUNK を ds64(RIFF の大きさ・data の大きさ・サンプル数)にする
            out.patch(0, "RF64".toByteArray())
            out.patch(4, le(0xFFFFFFFFL, 4))
            out.patch(12, "ds64".toByteArray())
            out.patch(20, le(riff, 8) + le(dataLen, 8) + le(dataLen / (ch * bps), 8))
            out.patch(dataSizeAt, le(0xFFFFFFFFL, 4))
        }
    }

    companion object {
        fun channelMask(ch: Int): Int = when (ch) {
            1 -> 0x4
            2 -> 0x3
            3 -> 0x7
            4 -> 0x33
            5 -> 0x37
            6 -> 0x3F
            else -> 0
        }
    }
}

/**
 * FLAC(16・24bit、1〜8 チャンネル、655 kHz まで)。アプリ内蔵の FlacSink(CD の取り込み用)を、どの形式にも使えるようにしたもの。
 * 固定の予測(0〜4 次)と Rice 符号。ステレオは左右・中央と差の組み合わせから小さくなるものを選ぶ。
 * 長さ(サンプル数)は最後に STREAMINFO に書き直す
 */
class FlacWriter(private val out: SeekOut, private val rate: Int, private val ch: Int, private val bits: Int, tags: OutTags?) : PcmWriter {
    private val bs = 4096
    private val q = Quantizer(bits)
    private val x = Array(ch) { IntArray(bs) }
    private val mid = IntArray(bs)
    private val side = IntArray(bs)
    private val res = IntArray(bs)
    private var fill = 0
    private var frameNo = 0L
    private var total = 0L
    private var minFrame = Int.MAX_VALUE
    private var maxFrame = 0
    /** Rice のパラメーター: 16bit は 4 ビット(〜14)、それより細かいと大きな値が要るので 5 ビット(〜30) */
    private val rice2 = bits > 16
    private val streamInfoAt = 8L

    private class Plan(val constant: Boolean, val order: Int, val partOrder: Int, val bits: Long)

    init {
        val o = ByteArrayOutputStream()
        FlacHeader.write(o, streamInfo(0, 0, 0), tags?.vorbis().orEmpty(), tags?.cover)
        out.write(o.toByteArray())
    }

    private fun streamInfo(samples: Long, minF: Int, maxF: Int): ByteArray {
        val bw = BitWriter()
        bw.write(bs, 16); bw.write(bs, 16)
        bw.write(minF, 24); bw.write(maxF, 24)
        bw.write(rate, 20); bw.write(ch - 1, 3); bw.write(bits - 1, 5)
        bw.write(samples shr 32, 4)
        bw.write(samples and 0xFFFFFFFFL, 32)
        repeat(4) { bw.write(0, 32) }
        return bw.toByteArray()
    }

    override fun write(buf: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until ch) x[c][fill] = q.q(buf[i++])
            if (++fill == bs) { encodeFrame(bs); fill = 0 }
        }
    }

    override fun finish() {
        if (fill > 0) { encodeFrame(fill); fill = 0 }
        out.flush()
        out.patch(streamInfoAt, streamInfo(total, if (minFrame == Int.MAX_VALUE) 0 else minFrame, maxFrame))
    }

    private fun zz(r: Int): Int = (r shl 1) xor (r shr 31)

    private fun riceParam(sum: Long, size: Int): Int {
        val lim = if (rice2) 30 else 14
        var k = 0
        while (k < lim && (size.toLong() shl (k + 1)) <= sum) k++
        return k
    }

    private fun residual(v: IntArray, n: Int, o: Int) {
        when (o) {
            0 -> for (i in 0 until n) res[i] = v[i]
            1 -> for (i in 1 until n) res[i] = v[i] - v[i - 1]
            2 -> for (i in 2 until n) res[i] = v[i] - 2 * v[i - 1] + v[i - 2]
            3 -> for (i in 3 until n) res[i] = v[i] - 3 * v[i - 1] + 3 * v[i - 2] - v[i - 3]
            else -> for (i in 4 until n) res[i] = v[i] - 4 * v[i - 1] + 6 * v[i - 2] - 4 * v[i - 3] + v[i - 4]
        }
    }

    private fun plan(v: IntArray, n: Int, bps: Int): Plan {
        var same = true
        for (i in 1 until n) if (v[i] != v[0]) { same = false; break }
        if (same) return Plan(true, 0, 0, 8L + bps)
        var best: Plan? = null
        val pbits = if (rice2) 5 else 4
        for (o in 0..minOf(4, n - 1)) {
            residual(v, n, o)
            var pm = 0
            while (pm < 6 && n % (1 shl (pm + 1)) == 0 && (n shr (pm + 1)) > o) pm++
            val sz = n shr pm
            var cur = LongArray(1 shl pm) { i ->
                val s = if (i == 0) o else i * sz
                var t = 0L
                for (j in s until (i + 1) * sz) t += zz(res[j])
                t
            }
            var p = pm
            while (true) {
                var b = 8L + o.toLong() * bps + 6
                val psz = n shr p
                for (i in cur.indices) {
                    val size = psz - (if (i == 0) o else 0)
                    val k = riceParam(cur[i], size)
                    b += pbits + size.toLong() * (k + 1) + (cur[i] shr k)
                }
                if (best == null || b < best.bits) best = Plan(false, o, p, b)
                if (p == 0) break
                cur = LongArray(cur.size / 2) { cur[2 * it] + cur[2 * it + 1] }
                p--
            }
        }
        return best!!
    }

    private fun subframe(bw: BitWriter, v: IntArray, n: Int, bps: Int, p: Plan) {
        if (p.constant) {
            bw.write(0, 8)
            bw.write(v[0].toLong(), bps)
            return
        }
        bw.write(0, 1); bw.write(8 + p.order, 6); bw.write(0, 1)
        for (i in 0 until p.order) bw.write(v[i].toLong(), bps)
        residual(v, n, p.order)
        bw.write(if (rice2) 1 else 0, 2)
        bw.write(p.partOrder, 4)
        var idx = p.order
        for (pi in 0 until (1 shl p.partOrder)) {
            val size = (n shr p.partOrder) - (if (pi == 0) p.order else 0)
            var sum = 0L
            for (j in 0 until size) sum += zz(res[idx + j])
            val k = riceParam(sum, size)
            bw.write(k, if (rice2) 5 else 4)
            val mask = (1 shl k) - 1
            for (j in 0 until size) {
                val u = zz(res[idx + j])
                bw.unary(u ushr k)
                if (k > 0) bw.write((u and mask).toLong(), k)
            }
            idx += size
        }
    }

    private fun encodeFrame(n: Int) {
        val bw = BitWriter()
        var chan = ch - 1
        if (ch == 2) {
            val l = x[0]; val r = x[1]
            for (i in 0 until n) { mid[i] = (l[i] + r[i]) shr 1; side[i] = l[i] - r[i] }
            val pl = plan(l, n, bits); val pr = plan(r, n, bits)
            val ps = plan(side, n, bits + 1); val pm = plan(mid, n, bits)
            var best = pl.bits + pr.bits
            chan = 1
            if (pl.bits + ps.bits < best) { best = pl.bits + ps.bits; chan = 8 }
            if (ps.bits + pr.bits < best) { best = ps.bits + pr.bits; chan = 9 }
            if (pm.bits + ps.bits < best) { chan = 10 }
            when (chan) {
                1 -> { subframe(bw, l, n, bits, pl); subframe(bw, r, n, bits, pr) }
                8 -> { subframe(bw, l, n, bits, pl); subframe(bw, side, n, bits + 1, ps) }
                9 -> { subframe(bw, side, n, bits + 1, ps); subframe(bw, r, n, bits, pr) }
                else -> { subframe(bw, mid, n, bits, pm); subframe(bw, side, n, bits + 1, ps) }
            }
        } else {
            for (c in 0 until ch) subframe(bw, x[c], n, bits, plan(x[c], n, bits))
        }
        val body = bw.toByteArray()
        val hdr = ByteArrayOutputStream()
        hdr.write(0xFF); hdr.write(0xF8)
        // ブロックの大きさ(4096 か、末尾の 16 ビット)・周波数とビット数は STREAMINFO のもの
        hdr.write(if (n == bs) 0xC0 else 0x70)
        hdr.write(chan shl 4)
        writeUtf8(hdr, frameNo)
        if (n != bs) { hdr.write((n - 1) shr 8); hdr.write((n - 1) and 0xFF) }
        hdr.write(crc8(hdr.toByteArray()))
        val full = hdr.toByteArray() + body
        val c = crc16(full)
        out.write(full)
        out.byte(c shr 8); out.byte(c and 0xFF)
        val size = full.size + 2
        if (size < minFrame) minFrame = size
        if (size > maxFrame) maxFrame = size
        frameNo++
        total += n
    }

    private fun writeUtf8(os: ByteArrayOutputStream, v: Long) {
        if (v < 0x80) { os.write(v.toInt()); return }
        val nb = when { v < 0x800 -> 2; v < 0x10000 -> 3; v < 0x200000 -> 4; v < 0x4000000 -> 5; else -> 6 }
        os.write(((0xFF00 shr nb) and 0xFF) or (v shr (6 * (nb - 1))).toInt())
        for (i in nb - 2 downTo 0) os.write(0x80 or ((v shr (6 * i)) and 0x3F).toInt())
    }

    private fun crc8(b: ByteArray): Int {
        var c = 0
        for (v in b) {
            c = c xor (v.toInt() and 0xFF)
            repeat(8) { c = if ((c and 0x80) != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        }
        return c
    }

    private fun crc16(b: ByteArray): Int {
        var c = 0
        for (v in b) c = ((c shl 8) and 0xFFFF) xor CRC16[((c shr 8) xor (v.toInt() and 0xFF)) and 0xFF]
        return c
    }

    private companion object {
        val CRC16 = IntArray(256) { i ->
            var c = i shl 8
            repeat(8) { c = if ((c and 0x8000) != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
            c
        }
    }
}

/**
 * DSF(Sony の DSD の形式)。チャンネルごとに 4096 バイトずつ交互に並べ、各バイトは最下位のビットが先。
 * 曲の情報は末尾の ID3v2。長さは最後に書き直す
 */
class DsfWriter(private val out: SeekOut, private val rate: Int, private val ch: Int, private val tags: OutTags?) : DsdWriter {
    private val block = 4096
    private val bufs = Array(ch) { ByteArray(block) }
    private var fill = 0
    private var bytesPerCh = 0L

    init {
        val h = ByteBuffer.allocate(28 + 52 + 12).order(ByteOrder.LITTLE_ENDIAN)
        h.put("DSD ".toByteArray()); h.putLong(28); h.putLong(0); h.putLong(0)
        h.put("fmt ".toByteArray()); h.putLong(52); h.putInt(1); h.putInt(0)
        h.putInt(when (ch) { 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 4; 5 -> 6; 6 -> 7; else -> 2 })
        h.putInt(ch); h.putInt(rate); h.putInt(1); h.putLong(0); h.putInt(block); h.putInt(0)
        h.put("data".toByteArray()); h.putLong(12)
        out.write(h.array())
    }

    override fun write(data: Array<ByteArray>, n: Int) {
        var i = 0
        while (i < n) {
            val k = minOf(n - i, block - fill)
            for (c in 0 until ch) {
                val src = data[c]; val dst = bufs[c]
                for (j in 0 until k) dst[fill + j] = REV[src[i + j].toInt() and 0xFF]
            }
            fill += k; i += k
            if (fill == block) emit()
        }
        bytesPerCh += n
    }

    private fun emit() {
        for (c in 0 until ch) out.write(bufs[c], 0, block)
        fill = 0
    }

    override fun finish() {
        if (fill > 0) {
            // 最後のブロックの残りは無音の並び(DSD の 0x69 を、ビットを逆にしたもの)
            for (c in 0 until ch) java.util.Arrays.fill(bufs[c], fill, block, 0x96.toByte())
            emit()
        }
        val dataEnd = out.pos
        val id3 = tags?.id3()
        if (id3 != null) out.write(id3)
        out.flush()
        out.patch(12, le(out.pos, 8))
        out.patch(20, le(if (id3 != null) dataEnd else 0, 8))
        out.patch(28 + 36, le(bytesPerCh * 8, 8))
        out.patch(28 + 52 + 4, le(dataEnd - (28 + 52), 8))
    }

    companion object {
        /** ビットの順を逆にする表 */
        val REV = ByteArray(256) { Integer.reverse(it).ushr(24).toByte() }
    }
}

/**
 * DSDIFF(DFF)。バイトをチャンネルごとに交互に並べ、各バイトは最上位のビットが先(ビッグエンディアン)。
 * 曲の情報は末尾の "ID3 " チャンク(多くの機器・ソフトが読める)。大きさは最後に書き直す
 */
class DffWriter(private val out: SeekOut, private val rate: Int, private val ch: Int, private val tags: OutTags?) : DsdWriter {
    private var inter = ByteArray(0)
    private var dataLen = 0L
    private val dsdSizeAt: Long

    init {
        val ids = when (ch) {
            1 -> listOf("C   ")
            2 -> listOf("SLFT", "SRGT")
            5 -> listOf("MLFT", "MRGT", "C   ", "LS  ", "RS  ")
            6 -> listOf("MLFT", "MRGT", "C   ", "LFE ", "LS  ", "RS  ")
            else -> List(ch) { "C%03d".format(it) }
        }
        val prop = ByteArrayOutputStream()
        fun chunk(o: ByteArrayOutputStream, id: String, body: ByteArray) {
            o.write(id.toByteArray()); o.write(be(body.size.toLong(), 8)); o.write(body)
            if (body.size % 2 == 1) o.write(0)
        }
        prop.write("SND ".toByteArray())
        chunk(prop, "FS  ", be(rate.toLong(), 4))
        chunk(prop, "CHNL", be(ch.toLong(), 2) + ids.joinToString("").toByteArray())
        val name = "not compressed".toByteArray()
        chunk(prop, "CMPR", "DSD ".toByteArray() + byteArrayOf(name.size.toByte()) + name + (if ((name.size + 1) % 2 == 1) byteArrayOf(0) else ByteArray(0)))
        val head = ByteArrayOutputStream()
        head.write("FRM8".toByteArray()); head.write(be(0, 8)); head.write("DSD ".toByteArray())
        chunk(head, "FVER", be(0x01050000L, 4))
        chunk(head, "PROP", prop.toByteArray())
        head.write("DSD ".toByteArray())
        dsdSizeAt = head.size().toLong()
        head.write(be(0, 8))
        out.write(head.toByteArray())
    }

    override fun write(data: Array<ByteArray>, n: Int) {
        if (inter.size < n * ch) inter = ByteArray(n * ch)
        var o = 0
        for (i in 0 until n) for (c in 0 until ch) inter[o++] = data[c][i]
        out.write(inter, 0, o)
        dataLen += o
    }

    override fun finish() {
        if (dataLen % 2 == 1L) out.byte(0)
        val id3 = tags?.id3()
        if (id3 != null) {
            out.write("ID3 ".toByteArray()); out.write(be(id3.size.toLong(), 8)); out.write(id3)
            if (id3.size % 2 == 1) out.byte(0)
        }
        out.flush()
        out.patch(4, be(out.pos - 12, 8))
        out.patch(dsdSizeAt, be(dataLen, 8))
    }
}
