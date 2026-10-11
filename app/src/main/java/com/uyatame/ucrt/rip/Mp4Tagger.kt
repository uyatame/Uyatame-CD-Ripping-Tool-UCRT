package com.uyatame.ucrt.rip

import com.uyatame.ucrt.T

import com.uyatame.ucrt.meta.Cover
import java.io.ByteArrayOutputStream
import java.io.File

class Mp4Tags(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val year: String,
    val track: Int,
    val trackTotal: Int,
    val disc: Int,
    val discTotal: Int,
)

/** m4a(MP4)に iTunes形式の曲情報(ilst)とジャケットを書き込む */
object Mp4Tagger {
    private class Box(val type: String, val off: Int, val size: Int)

    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf")

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)

    private fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte(); b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte()
    }

    private fun put64(b: ByteArray, o: Int, v: Long) {
        put32(b, o, v ushr 32)
        put32(b, o + 4, v and 0xFFFFFFFFL)
    }

    private fun boxes(b: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (p + 8 <= end) {
            var size = u32(b, p)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) size = u64(b, p + 8) else if (size == 0L) size = (end - p).toLong()
            if (size < 8 || p + size > end) break
            out.add(Box(type, p, size.toInt()))
            p += size.toInt()
        }
        return out
    }

    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val len = 8 + parts.sumOf { it.size }
        val o = ByteArrayOutputStream(len)
        o.write(be32(len))
        o.write(type.toByteArray(Charsets.ISO_8859_1))
        parts.forEach { o.write(it) }
        return o.toByteArray()
    }

    private fun text(type: String, v: String) = box(type, box("data", be32(1), be32(0), v.toByteArray(Charsets.UTF_8)))

    private fun pair(type: String, n: Int, total: Int, trailing: Boolean): ByteArray {
        val p = byteArrayOf(0, 0, (n shr 8).toByte(), n.toByte(), (total shr 8).toByte(), total.toByte()) +
            (if (trailing) byteArrayOf(0, 0) else byteArrayOf())
        return box(type, box("data", be32(0), be32(0), p))
    }

    private fun patch(b: ByteArray, start: Int, end: Int, delta: Long) {
        for (x in boxes(b, start, end)) {
            when (x.type) {
                "stco" -> {
                    val n = u32(b, x.off + 12).toInt()
                    for (i in 0 until n) { val o = x.off + 16 + i * 4; put32(b, o, u32(b, o) + delta) }
                }
                "co64" -> {
                    val n = u32(b, x.off + 12).toInt()
                    for (i in 0 until n) { val o = x.off + 16 + i * 8; put64(b, o, u64(b, o) + delta) }
                }
                in containers -> patch(b, x.off + 8, x.off + x.size, delta)
            }
        }
    }

    fun tag(file: File, t: Mp4Tags, cover: Cover?) {
        val b = file.readBytes()
        val top = boxes(b, 0, b.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: return
        val mdat = top.firstOrNull { it.type == "mdat" }

        val items = ArrayList<ByteArray>()
        if (t.title.isNotEmpty()) items.add(text("\u00A9nam", t.title))
        if (t.artist.isNotEmpty()) items.add(text("\u00A9ART", t.artist))
        if (t.album.isNotEmpty()) items.add(text("\u00A9alb", t.album))
        if (t.albumArtist.isNotEmpty()) items.add(text("aART", t.albumArtist))
        if (t.year.isNotEmpty()) items.add(text("\u00A9day", t.year))
        items.add(pair("trkn", t.track, t.trackTotal, true))
        items.add(pair("disk", t.disc, t.discTotal, false))
        items.add(text("\u00A9too", "Uyatame CD Ripping Tool"))
        if (cover != null) {
            items.add(box("covr", box("data", be32(if (cover.mime == "image/png") 14 else 13), be32(0), cover.data)))
        }
        val hdlr = box(
            "hdlr", be32(0), be32(0), "mdir".toByteArray(Charsets.ISO_8859_1),
            "appl".toByteArray(Charsets.ISO_8859_1), ByteArray(8), byteArrayOf(0),
        )
        val ilst = box("ilst", *items.toTypedArray())
        val udta = box("udta", box("meta", be32(0), hdlr, ilst))

        val body = ByteArrayOutputStream()
        for (c in boxes(b, moov.off + 8, moov.off + moov.size)) {
            if (c.type != "udta") body.write(b, c.off, c.size)
        }
        body.write(udta)
        val newMoov = box("moov", body.toByteArray())
        val delta = newMoov.size - moov.size
        if (mdat != null && mdat.off > moov.off && delta != 0) patch(newMoov, 8, newMoov.size, delta.toLong())

        file.outputStream().buffered().use { o ->
            for (x in top) {
                if (x === moov) o.write(newMoov) else o.write(b, x.off, x.size)
            }
        }
    }
}
