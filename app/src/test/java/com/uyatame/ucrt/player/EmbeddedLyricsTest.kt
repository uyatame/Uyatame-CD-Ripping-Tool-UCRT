package com.uyatame.ucrt.player

import com.uyatame.ucrt.tags.Id3Reader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class EmbeddedLyricsTest {
    private class Bytes(val b: ByteArray) : RandomInput {
        override val size: Long get() = b.size.toLong()
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int {
            if (pos >= this.b.size) return -1
            val n = minOf(len.toLong(), this.b.size - pos).toInt()
            System.arraycopy(this.b, pos.toInt(), b, off, n)
            return n
        }
    }

    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    /** 1 つのパケットを、決まった大きさのページに分けて入れる */
    private fun pages(packets: List<ByteArray>, pageMax: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (pk in packets) {
            var off = 0
            do {
                val segs = ArrayList<Int>()
                var take = 0
                while (segs.size < pageMax) {
                    val rest = pk.size - off - take
                    val l = minOf(255, rest)
                    segs.add(l); take += l
                    if (l < 255) break
                }
                val h = ByteArray(27)
                "OggS".toByteArray().copyInto(h)
                h[26] = segs.size.toByte()
                out.write(h)
                segs.forEach { out.write(it) }
                out.write(pk, off, take)
                off += take
            } while (segs.last() == 255)
        }
        return out.toByteArray()
    }

    @Test fun oggVorbisComment() {
        val id = "\u0001vorbis".toByteArray() + ByteArray(23)
        val c = ByteArrayOutputStream()
        c.write(3); c.write("vorbis".toByteArray())
        val vendor = "x".toByteArray(); c.write(le(vendor.size)); c.write(vendor)
        val items = listOf("TITLE=t", "LYRICS=" + "あ".repeat(400) + "\n[00:01.00]line")
        c.write(le(items.size))
        items.forEach { val b = it.toByteArray(); c.write(le(b.size)); c.write(b) }
        val ogg = pages(listOf(id, c.toByteArray()), 3)
        val got = LyricsCore.oggLyrics(Bytes(ogg))
        assertTrue(got!!.endsWith("[00:01.00]line"))
        assertEquals(400 + 15, got.length)
    }

    @Test fun syltBecomesLrc() {
        val body = ByteArrayOutputStream()
        body.write(0); body.write("eng".toByteArray()); body.write(2); body.write(1); body.write(0)
        body.write("Hello".toByteArray()); body.write(0); body.write(byteArrayOf(0, 0, 0x03, 0xE8.toByte()))
        body.write("World".toByteArray()); body.write(0); body.write(byteArrayOf(0, 0, 0xEA.toByte(), 0x60))
        val b = body.toByteArray()
        val frame = "SYLT".toByteArray() + byteArrayOf(0, 0, 0, b.size.toByte(), 0, 0) + b
        val n = frame.size
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, n.toByte()) + frame
        assertEquals("[00:01.00]Hello\n[01:00.00]World\n", Id3Reader.parse(tag)!!.lyrics)
    }
}
