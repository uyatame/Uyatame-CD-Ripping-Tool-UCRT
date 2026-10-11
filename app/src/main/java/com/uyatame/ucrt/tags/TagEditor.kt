package com.uyatame.ucrt.tags

import com.uyatame.ucrt.T

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.uyatame.ucrt.meta.Cover
import com.uyatame.ucrt.rip.Id3Tags
import com.uyatame.ucrt.rip.Id3v2
import com.uyatame.ucrt.rip.Mp4Tagger
import com.uyatame.ucrt.rip.Mp4Tags
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class TagValues(
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

fun coverFrom(b: ByteArray): Cover {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(b, 0, b.size, o)
    val mime = if (o.outMimeType == "image/png") "image/png" else "image/jpeg"
    return Cover(b, mime, maxOf(o.outWidth, 0), maxOf(o.outHeight, 0))
}

/** 選んだ画像をジャケット用に整える(大きすぎる場合は1000pxのJPEGに縮小) */
fun prepareCoverImage(b: ByteArray): ByteArray {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(b, 0, b.size, o)
    if (o.outWidth <= 0) throw IOException(T("画像を読み込めません", "Cannot load the image"))
    if (b.size <= 1_500_000 && maxOf(o.outWidth, o.outHeight) <= 1500 &&
        (o.outMimeType == "image/jpeg" || o.outMimeType == "image/png")
    ) return b
    val bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: throw IOException(T("画像を読み込めません", "Cannot load the image"))
    val scale = 1000f / maxOf(bmp.width, bmp.height)
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
    } else bmp
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
    return out.toByteArray()
}

/** 取り込み済みファイル(FLAC / MP3 / M4A)の曲情報を書き換える */
object TagEditor {
    fun supported(uri: String): Boolean = extOf(uri) in setOf("flac", "mp3", "m4a", "dsf")

    private fun extOf(uri: String) = Uri.decode(uri).substringAfterLast('.', "").lowercase()

    /** 書き換えたら true、未対応形式なら false */
    fun edit(ctx: Context, uri: Uri, v: TagValues, newCover: ByteArray?): Boolean {
        val ext = extOf(uri.toString())
        if (ext == "dsf") {
            // DSF は大きいので、コピーせずにファイル末尾のタグだけを書き換える
            com.uyatame.ucrt.player.dsd.DsdTags.writeDsf(
                ctx, uri,
                com.uyatame.ucrt.rip.Id3Tags(v.title, v.artist, v.album, v.albumArtist, v.year, v.track, v.trackTotal, v.disc, v.discTotal),
                newCover?.let { coverFrom(it) },
            )
            return true
        }
        if (ext !in setOf("flac", "mp3", "m4a")) return false
        val inF = File(ctx.cacheDir, "tag_in.$ext")
        val outF = File(ctx.cacheDir, "tag_out.$ext")
        try {
            val ins = ctx.contentResolver.openInputStream(uri) ?: throw IOException(T("ファイルを開けません", "Cannot open the file"))
            ins.use { i -> inF.outputStream().use { i.copyTo(it) } }
            val cover = newCover?.let { coverFrom(it) }
            when (ext) {
                "flac" -> FlacTags.rewrite(inF, outF, v, cover)
                "mp3" -> Mp3Tags.rewrite(inF, outF, v, cover)
                else -> {
                    val c = cover ?: existingPicture(inF)?.let { coverFrom(it) }
                    Mp4Tagger.tag(
                        inF,
                        Mp4Tags(v.title, v.artist, v.album, v.albumArtist, v.year, v.track, v.trackTotal, v.disc, v.discTotal),
                        c,
                    )
                    inF.copyTo(outF, overwrite = true)
                }
            }
            val os = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException(T("ファイルに書き込めません", "Cannot write to the file"))
            os.use { o -> outF.inputStream().use { it.copyTo(o, 1 shl 16) } }
            return true
        } finally {
            inF.delete()
            outF.delete()
        }
    }

    private fun existingPicture(f: File): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}

private object FlacTags {
    fun rewrite(inF: File, outF: File, v: TagValues, cover: Cover?) {
        RandomAccessFile(inF, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.ISO_8859_1) != "fLaC") throw IOException(T("FLACファイルではありません", "Not a FLAC file"))
            val keep = ArrayList<Pair<Int, ByteArray>>()
            var last = false
            while (!last) {
                val h = raf.read()
                if (h < 0) throw IOException(T("FLACのヘッダーが不正です", "Invalid FLAC header"))
                last = (h and 0x80) != 0
                val type = h and 0x7F
                val len = (raf.read() shl 16) or (raf.read() shl 8) or raf.read()
                val body = ByteArray(len)
                raf.readFully(body)
                val drop = type == 1 || type == 4 || (type == 6 && cover != null)
                if (!drop) keep.add(type to body)
            }
            val audioStart = raf.filePointer
            val streamInfo = keep.firstOrNull { it.first == 0 } ?: throw IOException(T("STREAMINFOがありません", "STREAMINFO missing"))
            val blocks = ArrayList<Pair<Int, ByteArray>>()
            blocks.add(streamInfo)
            keep.filter { it.first != 0 }.forEach { blocks.add(it) }
            blocks.add(4 to vorbis(v))
            if (cover != null) blocks.add(6 to picture(cover))
            outF.outputStream().buffered(1 shl 16).use { o ->
                o.write(magic)
                blocks.forEachIndexed { i, (t, b) ->
                    o.write(t or (if (i == blocks.lastIndex) 0x80 else 0))
                    o.write((b.size shr 16) and 0xFF)
                    o.write((b.size shr 8) and 0xFF)
                    o.write(b.size and 0xFF)
                    o.write(b)
                }
                raf.seek(audioStart)
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = raf.read(buf)
                    if (n <= 0) break
                    o.write(buf, 0, n)
                }
            }
        }
    }

    private fun vorbis(v: TagValues): ByteArray {
        val tags = buildList {
            add("TITLE" to v.title)
            add("ARTIST" to v.artist)
            add("ALBUM" to v.album)
            add("ALBUMARTIST" to v.albumArtist)
            if (v.track > 0) add("TRACKNUMBER" to v.track.toString())
            if (v.trackTotal > 0) add("TRACKTOTAL" to v.trackTotal.toString())
            add("DISCNUMBER" to v.disc.toString())
            add("DISCTOTAL" to v.discTotal.toString())
            if (v.year.isNotBlank()) add("DATE" to v.year.trim())
        }.filter { it.second.isNotEmpty() }
        val b = ByteArrayOutputStream()
        fun le32(x: Int) {
            b.write(x and 0xFF); b.write((x shr 8) and 0xFF); b.write((x shr 16) and 0xFF); b.write((x shr 24) and 0xFF)
        }
        val vendor = "Uyatame CD Ripping Tool".toByteArray()
        le32(vendor.size); b.write(vendor); le32(tags.size)
        for ((k, value) in tags) {
            val s = "$k=$value".toByteArray(Charsets.UTF_8)
            le32(s.size); b.write(s)
        }
        return b.toByteArray()
    }

    private fun picture(c: Cover): ByteArray {
        val p = ByteArrayOutputStream()
        fun be32(x: Int) {
            p.write((x shr 24) and 0xFF); p.write((x shr 16) and 0xFF); p.write((x shr 8) and 0xFF); p.write(x and 0xFF)
        }
        val mime = c.mime.toByteArray()
        be32(3); be32(mime.size); p.write(mime); be32(0)
        be32(c.width); be32(c.height); be32(24); be32(0)
        be32(c.data.size); p.write(c.data)
        return p.toByteArray()
    }
}

private object Mp3Tags {
    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    fun rewrite(inF: File, outF: File, v: TagValues, cover: Cover?) {
        val b = inF.readBytes()
        var start = 0
        val oldApic = ArrayList<ByteArray>()
        if (b.size > 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) {
            val ver = b[3].toInt()
            val flags = b[5].toInt() and 0xFF
            val size = syncsafe(b, 6)
            start = (10 + size + (if ((flags and 0x10) != 0) 10 else 0)).coerceAtMost(b.size)
            var p = 10
            val end = (10 + size).coerceAtMost(b.size)
            if ((flags and 0x40) != 0 && p + 4 <= end) {
                p += if (ver >= 4) syncsafe(b, p) else be32(b, p) + 4
            }
            while (p + 10 <= end) {
                if (b[p].toInt() == 0) break
                val id = String(b, p, 4, Charsets.ISO_8859_1)
                val fs = if (ver >= 4) syncsafe(b, p + 4) else be32(b, p + 4)
                if (fs <= 0 || p + 10 + fs > end) break
                if (id == "APIC" && cover == null) {
                    val body = b.copyOfRange(p + 10, p + 10 + fs)
                    if (body.isNotEmpty() && body[0].toInt() == 3) body[0] = 0
                    oldApic.add(body)
                }
                p += 10 + fs
            }
        }
        val tag = Id3v2.build(
            Id3Tags(v.title, v.artist, v.album, v.albumArtist, v.year, v.track, v.trackTotal, v.disc, v.discTotal),
            cover, oldApic,
        )
        outF.outputStream().buffered(1 shl 16).use {
            it.write(tag)
            it.write(b, start, b.size - start)
        }
    }
}
