package com.uyatame.ucrt.rip


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

    /** 自由形式(----)の項目。名前は com.apple.iTunes の下 */
    private fun freeform(name: String, v: String) = box(
        "----",
        box("mean", be32(0), "com.apple.iTunes".toByteArray(Charsets.UTF_8)),
        box("name", be32(0), name.toByteArray(Charsets.UTF_8)),
        box("data", be32(1), be32(0), v.toByteArray(Charsets.UTF_8)),
    )

    /** 自由形式(----)の項目の名前(大文字)。読めなければ空 */
    private fun freeformName(b: ByteArray, item: Box): String {
        for (c in boxes(b, item.off + 8, item.off + item.size)) {
            if (c.type == "name" && c.size > 12) return String(b, c.off + 12, c.size - 12, Charsets.UTF_8).uppercase()
        }
        return ""
    }

    /** extra: 詳しい情報(null の項目は書き換えない) */
    fun tag(file: File, t: Mp4Tags, cover: Cover?, extra: com.uyatame.ucrt.tags.TagExtra? = null) {
        val b = file.readBytes()
        val top = boxes(b, 0, b.size)
        // moov が無ければ書き込めない(黙って何もしないと、保存できたように見えてしまう)
        val moov = top.firstOrNull { it.type == "moov" } ?: throw java.io.IOException("MP4: no moov box")
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
        extra?.genre?.takeIf { it.isNotBlank() }?.let { items.add(text("\u00A9gen", it.trim())) }
        extra?.composer?.takeIf { it.isNotBlank() }?.let { items.add(text("\u00A9wrt", it.trim())) }
        extra?.comment?.takeIf { it.isNotBlank() }?.let { items.add(text("\u00A9cmt", it.trim())) }
        extra?.lyrics?.takeIf { it.isNotBlank() }?.let { items.add(text("\u00A9lyr", it.trim())) }
        extra?.lyricist?.takeIf { it.isNotBlank() }?.let { items.add(freeform("LYRICIST", it.trim())) }
        if (cover != null) {
            items.add(box("covr", box("data", be32(if (cover.mime == "image/png") 14 else 13), be32(0), cover.data)))
        }
        val hdlr = box(
            "hdlr", be32(0), be32(0), "mdir".toByteArray(Charsets.ISO_8859_1),
            "appl".toByteArray(Charsets.ISO_8859_1), ByteArray(8), byteArrayOf(0),
        )
        // 前からある曲情報のうち、ここで書かないもの(ジャンル・歌詞・ReplayGain など)は残す。
        // udta の meta 以外(チャプターなど)もそのまま残す
        val written = mutableSetOf("\u00A9nam", "\u00A9ART", "\u00A9alb", "aART", "\u00A9day", "trkn", "disk", "\u00A9too")
        if (cover != null) written.add("covr")
        if (extra?.genre != null) { written.add("\u00A9gen"); written.add("gnre") }
        if (extra?.composer != null) written.add("\u00A9wrt")
        if (extra?.comment != null) written.add("\u00A9cmt")
        if (extra?.lyrics != null) written.add("\u00A9lyr")
        val keepUdta = ArrayList<ByteArray>()
        boxes(b, moov.off + 8, moov.off + moov.size).firstOrNull { it.type == "udta" }?.let { u ->
            for (c in boxes(b, u.off + 8, u.off + u.size)) {
                if (c.type != "meta") { keepUdta.add(b.copyOfRange(c.off, c.off + c.size)); continue }
                // ISO 形式の meta は中身の前にバージョンとフラグ(4 バイト)がある。QuickTime 形式には無い
                val iso = c.size >= 12 && b[c.off + 8].toInt() == 0 && b[c.off + 9].toInt() == 0 && b[c.off + 10].toInt() == 0 && b[c.off + 11].toInt() == 0
                val inner = c.off + 8 + if (iso) 4 else 0
                boxes(b, inner, c.off + c.size).firstOrNull { it.type == "ilst" }?.let { il ->
                    for (item in boxes(b, il.off + 8, il.off + il.size)) {
                        if (item.type in written) continue
                        if (item.type == "----" && extra?.lyricist != null && freeformName(b, item) == "LYRICIST") continue
                        items.add(b.copyOfRange(item.off, item.off + item.size))
                    }
                }
            }
        }
        val ilst = box("ilst", *items.toTypedArray())
        val udta = box("udta", *(keepUdta + listOf(box("meta", be32(0), hdlr, ilst))).toTypedArray())

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
