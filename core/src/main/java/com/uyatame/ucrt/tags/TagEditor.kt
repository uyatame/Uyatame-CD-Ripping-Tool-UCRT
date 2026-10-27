package com.uyatame.ucrt.tags

import com.uyatame.ucrt.R
import com.uyatame.ucrt.S

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
    /** 詳しい情報(ジャンル・作曲・作詞・コメント・歌詞)。null の項目は書き換えない。FLAC・MP3・M4A だけ */
    val extra: TagExtra = TagExtra(),
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
    if (o.outWidth <= 0) throw IOException(S(R.string.cant_load_image))
    if (b.size <= 1_500_000 && maxOf(o.outWidth, o.outHeight) <= 1500 &&
        (o.outMimeType == "image/jpeg" || o.outMimeType == "image/png")
    ) return b
    val bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: throw IOException(S(R.string.cant_load_image))
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
    /** 曲情報を書き換えられるか(CUE シートで分けた曲は、元のファイルが共通なので書き換えない) */
    fun supported(uri: String): Boolean = '#' !in uri && extOf(uri) in setOf("flac", "mp3", "m4a", "dsf", "wav")

    private fun extOf(uri: String) = Uri.decode(uri).substringAfterLast('.', "").lowercase()

    /** 書き換えたら true、未対応形式なら false */
    fun edit(ctx: Context, uri: Uri, v: TagValues, newCover: ByteArray?): Boolean {
        if (!supported(uri.toString())) return false
        val ext = extOf(uri.toString())
        if (ext == "wav") {
            // WAV は大きいので、元のファイルから読みながら新しい内容を作り、書き戻す
            val outF = File(ctx.cacheDir, "tag_out.wav")
            try {
                outF.outputStream().buffered(1 shl 16).use { o ->
                    WavTags.rewrite(
                        ctx, uri, o,
                        Id3Tags(v.title, v.artist, v.album, v.albumArtist, v.year, v.track, v.trackTotal, v.disc, v.discTotal),
                        newCover?.let { coverFrom(it) },
                    )
                }
                writeBack(ctx, uri, outF, null)
                return true
            } finally {
                outF.delete()
            }
        }
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
            val ins = ctx.contentResolver.openInputStream(uri) ?: throw IOException(S(R.string.cant_open_file))
            ins.use { i -> inF.outputStream().use { i.copyTo(it) } }
            val cover = newCover?.let { coverFrom(it) }
            when (ext) {
                "flac" -> FlacTags.rewrite(inF, outF, v, cover)
                "mp3" -> Mp3Tags.rewrite(inF, outF, v, cover)
                else -> {
                    val c = cover ?: existingPicture(inF)?.let { coverFrom(it) }
                    // 元の内容(inF)は、書き戻しに失敗したときのために残しておく
                    inF.copyTo(outF, overwrite = true)
                    Mp4Tagger.tag(
                        outF,
                        Mp4Tags(v.title, v.artist, v.album, v.albumArtist, v.year, v.track, v.trackTotal, v.disc, v.discTotal),
                        c, v.extra,
                    )
                }
            }
            writeBack(ctx, uri, outF, inF)
            return true
        } finally {
            inF.delete()
            outF.delete()
        }
    }

    /**
     * out の内容で uri のファイルを置き換える。
     * ファイルとして書ける(「すべてのファイルへのアクセス」がある)ときは、隣に書いてから名前を変える
     * (途中で止まっても元のファイルは壊れない)。そうでなければ上書きし、途中で失敗したら backup に戻してみる
     */
    private fun writeBack(ctx: Context, uri: Uri, out: File, backup: File?) {
        val f = com.uyatame.ucrt.FileUris.fileOf(uri)
        if (f != null && f.isFile) {
            val tmp = File(f.parentFile, "." + f.name + ".ucrt-tmp")
            val ok = runCatching {
                java.io.FileOutputStream(tmp).use { o ->
                    out.inputStream().use { it.copyTo(o, 1 shl 16) }
                    o.fd.sync()
                }
                tmp.length() == out.length() && tmp.renameTo(f)
            }.getOrDefault(false)
            if (ok) return
            tmp.delete()
        }
        val os = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException(S(R.string.cant_write_to_file))
        try {
            os.use { o ->
                out.inputStream().use { it.copyTo(o, 1 shl 16) }
                o.flush()
                (o as? java.io.FileOutputStream)?.let { runCatching { it.fd.sync() } }
            }
        } catch (e: Exception) {
            // 書き込みの途中で失敗した: 元の内容に戻してみる
            if (backup != null) runCatching {
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { o -> backup.inputStream().use { it.copyTo(o, 1 shl 16) } }
            }
            throw e
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

internal object FlacTags {
    /** 書き換える項目(古いものは残さない)。ほかの項目(GENRE・LYRICS・REPLAYGAIN など)はそのまま残す */
    private val EDITED = setOf(
        "TITLE", "ARTIST", "ALBUM", "ALBUMARTIST", "ALBUM ARTIST", "TRACKNUMBER", "TRACKTOTAL", "TOTALTRACKS",
        "DISCNUMBER", "DISCTOTAL", "TOTALDISCS", "DATE", "YEAR",
    )

    fun rewrite(inF: File, outF: File, v: TagValues, cover: Cover?) {
        RandomAccessFile(inF, "r").use { raf ->
            val fileLen = raf.length()
            val magic = ByteArray(4)
            raf.readFully(magic)
            // 先頭に ID3v2 が付いているもの(ほかのソフトが付けたもの)は、そのまま残す
            var prefix = 0L
            if (magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte()) {
                val h = ByteArray(10)
                raf.seek(0)
                raf.readFully(h)
                val sz = ((h[6].toInt() and 0x7F) shl 21) or ((h[7].toInt() and 0x7F) shl 14) or
                    ((h[8].toInt() and 0x7F) shl 7) or (h[9].toInt() and 0x7F)
                prefix = 10L + sz + (if ((h[5].toInt() and 0x10) != 0) 10 else 0)
                if (prefix + 4 > fileLen) throw IOException(S(R.string.not_flac_file))
                raf.seek(prefix)
                raf.readFully(magic)
            }
            if (String(magic, Charsets.ISO_8859_1) != "fLaC") throw IOException(S(R.string.not_flac_file))
            val keep = ArrayList<Pair<Int, ByteArray>>()
            val oldComments = ArrayList<ByteArray>()
            var last = false
            while (!last) {
                val h = raf.read()
                val l0 = raf.read()
                val l1 = raf.read()
                val l2 = raf.read()
                if (h < 0 || l0 < 0 || l1 < 0 || l2 < 0) throw IOException(S(R.string.invalid_flac_header))
                last = (h and 0x80) != 0
                val type = h and 0x7F
                val len = (l0 shl 16) or (l1 shl 8) or l2
                if (raf.filePointer + len > fileLen) throw IOException(S(R.string.invalid_flac_header))
                val body = ByteArray(len)
                raf.readFully(body)
                if (type == 4) oldComments += otherComments(body, EDITED + v.extra.vorbisKeys())
                val drop = type == 1 || type == 4 || (type == 6 && cover != null)
                if (!drop) keep.add(type to body)
            }
            val audioStart = raf.filePointer
            val streamInfo = keep.firstOrNull { it.first == 0 } ?: throw IOException(S(R.string.streaminfo_block_is_missing))
            val blocks = ArrayList<Pair<Int, ByteArray>>()
            blocks.add(streamInfo)
            keep.filter { it.first != 0 }.forEach { blocks.add(it) }
            blocks.add(4 to vorbis(v, oldComments))
            if (cover != null) blocks.add(6 to picture(cover))
            // ブロックの長さは 24bit。収まらなければ書かない(壊れたファイルを作らない)
            if (blocks.any { it.second.size >= (1 shl 24) }) throw IOException(S(R.string.image_is_too_large))
            outF.outputStream().buffered(1 shl 16).use { o ->
                val buf = ByteArray(1 shl 16)
                // 先頭の ID3v2
                raf.seek(0)
                var left = prefix
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                    if (n <= 0) throw IOException(S(R.string.invalid_flac_header))
                    o.write(buf, 0, n)
                    left -= n
                }
                o.write(magic)
                blocks.forEachIndexed { i, (t, b) ->
                    o.write(t or (if (i == blocks.lastIndex) 0x80 else 0))
                    o.write((b.size shr 16) and 0xFF)
                    o.write((b.size shr 8) and 0xFF)
                    o.write(b.size and 0xFF)
                    o.write(b)
                }
                raf.seek(audioStart)
                while (true) {
                    val n = raf.read(buf)
                    if (n <= 0) break
                    o.write(buf, 0, n)
                }
            }
        }
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    /** 元の VORBIS_COMMENT のうち、書き換えない項目("KEY=value" のバイト列)。壊れていれば読めた所まで */
    private fun otherComments(b: ByteArray, edited: Set<String>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (b.size < 8) return out
        var p = 4 + le32(b, 0)
        if (p + 4 > b.size) return out
        val count = le32(b, p.toInt())
        p += 4
        var i = 0L
        while (i < count && p + 4 <= b.size) {
            val n = le32(b, p.toInt())
            p += 4
            if (p + n > b.size) break
            val e = b.copyOfRange(p.toInt(), (p + n).toInt())
            p += n
            i++
            val eq = e.indexOf('='.code.toByte())
            if (eq <= 0) continue
            val key = String(e, 0, eq, Charsets.ISO_8859_1).uppercase()
            if (key !in edited) out.add(e)
        }
        return out
    }

    private fun vorbis(v: TagValues, extra: List<ByteArray>): ByteArray {
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
            addAll(v.extra.vorbisPairs())
        }.filter { it.second.isNotEmpty() }
        val b = ByteArrayOutputStream()
        fun le32(x: Int) {
            b.write(x and 0xFF); b.write((x shr 8) and 0xFF); b.write((x shr 16) and 0xFF); b.write((x shr 24) and 0xFF)
        }
        val vendor = "Uyatame CD Ripping Tool".toByteArray()
        le32(vendor.size); b.write(vendor); le32(tags.size + extra.size)
        for ((k, value) in tags) {
            val s = "$k=$value".toByteArray(Charsets.UTF_8)
            le32(s.size); b.write(s)
        }
        for (e in extra) {
            le32(e.size); b.write(e)
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

internal object Mp3Tags {
    fun rewrite(inF: File, outF: File, v: TagValues, cover: Cover?) {
        RandomAccessFile(inF, "r").use { raf ->
            val fileLen = raf.length()
            // ファイル全体を読み込まない(大きな MP3 でもメモリが足りるように)。読むのは先頭のタグだけ
            var start = 0L
            var old: ByteArray? = null
            if (fileLen > 10) {
                val h = ByteArray(10)
                raf.readFully(h)
                if (h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) {
                    val size = ((h[6].toInt() and 0x7F) shl 21) or ((h[7].toInt() and 0x7F) shl 14) or
                        ((h[8].toInt() and 0x7F) shl 7) or (h[9].toInt() and 0x7F)
                    start = 10L + size + (if ((h[5].toInt() and 0x10) != 0) 10 else 0)
                    // タグの大きさが壊れていると、音声を消してしまうので書き換えない
                    if (start > fileLen) throw IOException(S(R.string.id3_tag_is_corrupted))
                    old = ByteArray(10 + size)
                    raf.seek(0)
                    raf.readFully(old)
                }
            }
            val kept = Id3Keep.from(old, cover == null, v.extra.id3Ids())
            val tag = Id3Keep.merge(
                Id3v2.build(
                    Id3Tags(v.title, v.artist, v.album, v.albumArtist, v.year, v.track, v.trackTotal, v.disc, v.discTotal),
                    cover, kept.rawApic,
                ),
                kept.frames + v.extra.id3Frames(),
            )
            outF.outputStream().buffered(1 shl 16).use { o ->
                o.write(tag)
                raf.seek(start)
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = raf.read(buf)
                    if (n <= 0) break
                    o.write(buf, 0, n)
                }
            }
        }
    }
}

/**
 * 曲情報を書き換えるときに、元の ID3v2 タグのうち書き換えない項目(ジャンル・歌詞・コメント・TXXX など)を、
 * 新しいタグ(ID3v2.3)に残すためのもの
 */
internal object Id3Keep {
    /** 書き換えで作り直すフレーム(古いものは残さない) */
    private val REWRITTEN = setOf("TIT2", "TPE1", "TALB", "TPE2", "TYER", "TDRC", "TRCK", "TPOS", "APIC")

    /** 2.2 の ID → 2.3 の ID(よく使われるもの) */
    private val V22 = mapOf(
        "TCO" to "TCON", "COM" to "COMM", "ULT" to "USLT", "TXX" to "TXXX", "TCM" to "TCOM", "TCR" to "TCOP",
        "TEN" to "TENC", "TBP" to "TBPM", "TPB" to "TPUB", "TP3" to "TPE3", "TP4" to "TPE4", "TT1" to "TIT1",
        "TT3" to "TIT3", "TLA" to "TLAN", "TOA" to "TOPE", "TOT" to "TOAL", "TSS" to "TSSE", "TKE" to "TKEY",
        "TMT" to "TMED", "TOL" to "TOLY", "TOR" to "TORY", "TRC" to "TSRC", "TCP" to "TCMP", "WXX" to "WXXX",
    )

    class Kept(val rawApic: List<ByteArray>, val frames: ByteArray)

    /** tag(ID3v2 全体。無ければ null)から残すもの。keepApic なら画像も残す */
    fun from(tag: ByteArray?, keepApic: Boolean, drop: Set<String> = emptySet()): Kept {
        val fr = tag?.let { runCatching { Id3Reader.frames(it) }.getOrNull() } ?: return Kept(emptyList(), ByteArray(0))
        val apics = ArrayList<ByteArray>()
        val out = ByteArrayOutputStream()
        for (f in fr.list) {
            if (f.body.isEmpty()) continue
            if (f.id == "APIC" || f.id == "PIC") {
                if (keepApic) apics.add(if (f.id == "PIC") apicOf22(f.body) else apic23(f.body))
                continue
            }
            val id = if (fr.version == 2) V22[f.id] ?: continue else f.id
            if (id in REWRITTEN || id in drop || id.length != 4 || !id.all { it in 'A'..'Z' || it in '0'..'9' }) continue
            // 「タグを変えたら捨てる」の印が付いたフレームは残さない
            if ((f.status and 0x80) != 0) continue
            val body = if (fr.version >= 4) to23(id, f.body) else f.body
            out.write(id.toByteArray(Charsets.ISO_8859_1))
            out.write(byteArrayOf((body.size shr 24).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()))
            out.write(byteArrayOf((f.status and 0x60).toByte(), 0))
            out.write(body)
        }
        return Kept(apics, out.toByteArray())
    }

    /** Id3v2.build が作ったタグの後ろに frames を足し、タグの大きさを直す */
    fun merge(tag: ByteArray, frames: ByteArray): ByteArray {
        if (frames.isEmpty()) return tag
        val s = tag.size - 10 + frames.size
        if (s >= (1 shl 28)) throw IOException(S(R.string.id3_tag_is_too_large))
        val out = tag.copyOf(tag.size + frames.size)
        System.arraycopy(frames, 0, out, tag.size, frames.size)
        out[6] = ((s shr 21) and 0x7F).toByte()
        out[7] = ((s shr 14) and 0x7F).toByte()
        out[8] = ((s shr 7) and 0x7F).toByte()
        out[9] = (s and 0x7F).toByte()
        return out
    }

    /** APIC の文字コード: 2.3 に無い UTF-8 / UTF-16BE(説明だけに使う)は、説明のバイトをそのまま Latin-1 とみなす(これまでと同じ) */
    private fun apic23(b: ByteArray): ByteArray {
        if (b[0].toInt() != 3) return b
        return b.copyOf().also { it[0] = 0 }
    }

    /** 2.2 の PIC(文字コード・形式 3 文字・種類・説明・画像)→ 2.3 の APIC */
    private fun apicOf22(b: ByteArray): ByteArray {
        if (b.size < 5) return b
        val fmt = String(b, 1, 3, Charsets.ISO_8859_1).trim().uppercase()
        val mime = when (fmt) { "PNG" -> "image/png"; "JPG", "JPEG" -> "image/jpeg"; else -> "image/" + fmt.lowercase() }
        val o = ByteArrayOutputStream()
        o.write(if (b[0].toInt() == 1) 1 else 0)
        o.write(mime.toByteArray(Charsets.ISO_8859_1))
        o.write(0)
        o.write(b, 4, b.size - 4)
        return o.toByteArray()
    }

    private fun utf16(s: String): ByteArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)

    /** off から、文字コード enc の文字列を終端まで読む。(文字列, 次の位置) */
    private fun str(b: ByteArray, off: Int, enc: Int): Pair<String, Int> {
        val cs = if (enc == 3) Charsets.UTF_8 else Charsets.UTF_16BE
        var p = off
        if (enc == 3) {
            while (p < b.size && b[p].toInt() != 0) p++
            return String(b, off, p - off, cs) to minOf(p + 1, b.size)
        }
        while (p + 1 < b.size && !(b[p].toInt() == 0 && b[p + 1].toInt() == 0)) p += 2
        return String(b, off, minOf(p, b.size) - off, cs) to minOf(p + 2, b.size)
    }

    /**
     * 2.4 のフレームの文字列を 2.3 で使える形にする(UTF-8 / UTF-16BE → BOM 付き UTF-16)。
     * 複数の値(NUL 区切り)は "/" でつなぐ。文字の入ったフレームでなければそのまま
     */
    private fun to23(id: String, b: ByteArray): ByteArray {
        val enc = b[0].toInt()
        if (enc != 2 && enc != 3) return b
        val cs = if (enc == 3) Charsets.UTF_8 else Charsets.UTF_16BE
        fun rest(off: Int): String = if (off >= b.size) "" else
            String(b, off, b.size - off, cs).split('\u0000').filter { it.isNotEmpty() }.joinToString("/")
        val o = ByteArrayOutputStream()
        o.write(1)
        when {
            id == "TXXX" -> {
                val (desc, p) = str(b, 1, enc)
                o.write(utf16(desc)); o.write(utf16(rest(p)))
            }
            id == "WXXX" -> {
                val (desc, p) = str(b, 1, enc)
                o.write(utf16(desc)); o.write(b, p, b.size - p) // URL は Latin-1 のまま
            }
            id == "COMM" || id == "USLT" -> {
                if (b.size < 4) return b
                o.write(b, 1, 3) // 言語
                val (desc, p) = str(b, 4, enc)
                o.write(utf16(desc)); o.write(utf16(rest(p)).let { it.copyOf(it.size - 2) }) // 本文は終端なし
            }
            id[0] == 'T' -> o.write(utf16(rest(1)).let { it.copyOf(it.size - 2) })
            else -> return b
        }
        return o.toByteArray()
    }
}
