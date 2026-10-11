package com.uyatame.ucrt.rip

import com.uyatame.ucrt.meta.Cover
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class BitWriter {
    private val buf = ByteArrayOutputStream(16384)
    private var acc = 0L
    private var n = 0

    fun write(value: Long, bits: Int) {
        if (bits == 0) return
        acc = (acc shl bits) or (value and ((1L shl bits) - 1))
        n += bits
        while (n >= 8) {
            buf.write(((acc shr (n - 8)) and 0xFF).toInt())
            n -= 8
        }
        acc = acc and ((1L shl n) - 1)
    }

    fun write(value: Int, bits: Int) = write(value.toLong(), bits)

    fun unary(q: Int) {
        var left = q
        while (left >= 32) { write(0L, 32); left -= 32 }
        write(1L, left + 1)
    }

    fun toByteArray(): ByteArray {
        if (n > 0) write(0L, 8 - n)
        return buf.toByteArray()
    }
}

/**
 * 外部ライブラリ不要の純Kotlin製FLACエンコーダ(16bit/44.1kHz/ステレオ、固定予測+Rice符号)。
 * level 0..8: 大きいほど予測次数・パーティション探索・ステレオ相関探索を増やして圧縮率を上げる。
 */
class FlacSink(
    private val out: OutputStream,
    private val totalSamples: Long,
    level: Int,
    tags: List<Pair<String, String>> = emptyList(),
    cover: Cover? = null,
) : AudioSink {
    private val bs = 4096
    private val lv = level.coerceIn(0, 8)
    private val maxOrder = intArrayOf(1, 2, 2, 3, 3, 4, 4, 4, 4)[lv]
    private val maxPart = intArrayOf(2, 3, 4, 4, 5, 5, 6, 6, 8)[lv]
    private val stereoSearch = lv >= 1
    private val left = IntArray(bs)
    private val right = IntArray(bs)
    private val mid = IntArray(bs)
    private val side = IntArray(bs)
    private val res = IntArray(bs)
    private var fill = 0
    private var frameNo = 0L

    private class Plan(val constant: Boolean, val order: Int, val partOrder: Int, val bits: Long)

    init { writeHeader(tags, cover) }

    private fun writeHeader(tags: List<Pair<String, String>>, cover: Cover?) {
        out.write(byteArrayOf(0x66, 0x4C, 0x61, 0x43))
        val bw = BitWriter()
        val blk = minOf(bs.toLong(), totalSamples).toInt().coerceAtLeast(16)
        bw.write(blk, 16); bw.write(blk, 16)
        bw.write(0, 24); bw.write(0, 24)
        bw.write(44100, 20); bw.write(1, 3); bw.write(15, 5)
        bw.write(totalSamples shr 32, 4)
        bw.write(totalSamples and 0xFFFFFFFFL, 32)
        repeat(4) { bw.write(0, 32) }
        val blocks = ArrayList<Pair<Int, ByteArray>>()
        blocks.add(0 to bw.toByteArray())

        val vc = ByteArrayOutputStream()
        fun le32(v: Int) {
            vc.write(v and 0xFF); vc.write((v shr 8) and 0xFF)
            vc.write((v shr 16) and 0xFF); vc.write((v shr 24) and 0xFF)
        }
        val vendor = "Uyatame CD Ripping Tool".toByteArray()
        le32(vendor.size); vc.write(vendor); le32(tags.size)
        for ((k, v) in tags) {
            val s = "$k=$v".toByteArray(Charsets.UTF_8)
            le32(s.size); vc.write(s)
        }
        blocks.add(4 to vc.toByteArray())

        if (cover != null) {
            val pc = ByteArrayOutputStream()
            fun be32(v: Int) {
                pc.write((v shr 24) and 0xFF); pc.write((v shr 16) and 0xFF)
                pc.write((v shr 8) and 0xFF); pc.write(v and 0xFF)
            }
            val mime = cover.mime.toByteArray()
            be32(3); be32(mime.size); pc.write(mime); be32(0)
            be32(cover.width); be32(cover.height); be32(24); be32(0)
            be32(cover.data.size); pc.write(cover.data)
            blocks.add(6 to pc.toByteArray())
        }

        blocks.forEachIndexed { i, (type, body) ->
            out.write(type or (if (i == blocks.lastIndex) 0x80 else 0))
            out.write((body.size shr 16) and 0xFF)
            out.write((body.size shr 8) and 0xFF)
            out.write(body.size and 0xFF)
            out.write(body)
        }
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len - (len % 4)
        while (i < end) {
            left[fill] = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)
            right[fill] = (buf[i + 3].toInt() shl 8) or (buf[i + 2].toInt() and 0xFF)
            fill++
            i += 4
            if (fill == bs) { encodeFrame(bs); fill = 0 }
        }
    }

    override fun finish() {
        if (fill > 0) { encodeFrame(fill); fill = 0 }
        out.flush()
    }

    private fun zz(r: Int): Int = (r shl 1) xor (r shr 31)

    private fun riceParam(sum: Long, size: Int): Int {
        var k = 0
        while (k < 14 && (size.toLong() shl (k + 1)) <= sum) k++
        return k
    }

    private fun residual(x: IntArray, n: Int, o: Int) {
        when (o) {
            0 -> for (i in 0 until n) res[i] = x[i]
            1 -> for (i in 1 until n) res[i] = x[i] - x[i - 1]
            2 -> for (i in 2 until n) res[i] = x[i] - 2 * x[i - 1] + x[i - 2]
            3 -> for (i in 3 until n) res[i] = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
            else -> for (i in 4 until n) res[i] = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
        }
    }

    private fun plan(x: IntArray, n: Int, bps: Int): Plan {
        var same = true
        for (i in 1 until n) if (x[i] != x[0]) { same = false; break }
        if (same) return Plan(true, 0, 0, 8L + bps)

        var best: Plan? = null
        val maxO = minOf(maxOrder, n - 1)
        for (o in 0..maxO) {
            residual(x, n, o)
            var pm = 0
            while (pm < maxPart && n % (1 shl (pm + 1)) == 0 && (n shr (pm + 1)) > o) pm++
            val sz = n shr pm
            var cur = LongArray(1 shl pm)
            for (i in cur.indices) {
                val s = if (i == 0) o else i * sz
                var t = 0L
                for (j in s until (i + 1) * sz) t += zz(res[j])
                cur[i] = t
            }
            var p = pm
            while (true) {
                var bits = 8L + o.toLong() * bps + 6
                val psz = n shr p
                for (i in cur.indices) {
                    val size = psz - (if (i == 0) o else 0)
                    val k = riceParam(cur[i], size)
                    bits += 4 + size.toLong() * (k + 1) + (cur[i] shr k)
                }
                if (best == null || bits < best.bits) best = Plan(false, o, p, bits)
                if (p == 0) break
                val nx = LongArray(cur.size / 2) { cur[2 * it] + cur[2 * it + 1] }
                cur = nx
                p--
            }
        }
        return best!!
    }

    private fun subframe(bw: BitWriter, x: IntArray, n: Int, bps: Int, p: Plan) {
        if (p.constant) {
            bw.write(0, 8)
            bw.write(x[0].toLong(), bps)
            return
        }
        bw.write(0, 1); bw.write(8 + p.order, 6); bw.write(0, 1)
        for (i in 0 until p.order) bw.write(x[i].toLong(), bps)
        residual(x, n, p.order)
        bw.write(0, 2)
        bw.write(p.partOrder, 4)
        var idx = p.order
        for (pi in 0 until (1 shl p.partOrder)) {
            val size = (n shr p.partOrder) - (if (pi == 0) p.order else 0)
            var sum = 0L
            for (j in 0 until size) sum += zz(res[idx + j])
            val k = riceParam(sum, size)
            bw.write(k, 4)
            val mask = (1 shl k) - 1
            for (j in 0 until size) {
                val u = zz(res[idx + j])
                bw.unary(u ushr k)
                if (k > 0) bw.write(u and mask, k)
            }
            idx += size
        }
    }

    private fun encodeFrame(n: Int) {
        val pl = plan(left, n, 16)
        val pr = plan(right, n, 16)
        var chan = 1
        var best = pl.bits + pr.bits
        var ps: Plan? = null
        var pm: Plan? = null
        if (stereoSearch) {
            for (i in 0 until n) {
                mid[i] = (left[i] + right[i]) shr 1
                side[i] = left[i] - right[i]
            }
            ps = plan(side, n, 17)
            pm = plan(mid, n, 16)
            if (pl.bits + ps.bits < best) { best = pl.bits + ps.bits; chan = 8 }
            if (ps.bits + pr.bits < best) { best = ps.bits + pr.bits; chan = 9 }
            if (pm.bits + ps.bits < best) { best = pm.bits + ps.bits; chan = 10 }
        }
        val bw = BitWriter()
        when (chan) {
            1 -> { subframe(bw, left, n, 16, pl); subframe(bw, right, n, 16, pr) }
            8 -> { subframe(bw, left, n, 16, pl); subframe(bw, side, n, 17, ps!!) }
            9 -> { subframe(bw, side, n, 17, ps!!); subframe(bw, right, n, 16, pr) }
            else -> { subframe(bw, mid, n, 16, pm!!); subframe(bw, side, n, 17, ps!!) }
        }
        val body = bw.toByteArray()

        val hdr = ByteArrayOutputStream()
        hdr.write(0xFF); hdr.write(0xF8)
        hdr.write(if (n == bs) 0xC9 else 0x79)
        hdr.write((chan shl 4) or 0x08)
        writeUtf8(hdr, frameNo)
        if (n != bs) { hdr.write((n - 1) shr 8); hdr.write((n - 1) and 0xFF) }
        val h = hdr.toByteArray()
        hdr.write(crc8(h))
        val full = hdr.toByteArray() + body
        out.write(full)
        val c = crc16(full)
        out.write(c shr 8); out.write(c and 0xFF)
        frameNo++
    }

    private fun writeUtf8(os: ByteArrayOutputStream, v: Long) {
        if (v < 0x80) { os.write(v.toInt()); return }
        val nb = when {
            v < 0x800 -> 2
            v < 0x10000 -> 3
            v < 0x200000 -> 4
            v < 0x4000000 -> 5
            else -> 6
        }
        val first = (0xFF00 shr nb) and 0xFF
        os.write(first or (v shr (6 * (nb - 1))).toInt())
        for (i in nb - 2 downTo 0) os.write(0x80 or ((v shr (6 * i)) and 0x3F).toInt())
    }

    private fun crc8(b: ByteArray): Int {
        var c = 0
        for (x in b) {
            c = c xor (x.toInt() and 0xFF)
            repeat(8) { c = if ((c and 0x80) != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        }
        return c
    }

    private val crcTab = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if ((c and 0x8000) != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
        c
    }

    private fun crc16(b: ByteArray): Int {
        var c = 0
        for (x in b) c = ((c shl 8) and 0xFFFF) xor crcTab[((c shr 8) xor (x.toInt() and 0xFF)) and 0xFF]
        return c
    }
}
