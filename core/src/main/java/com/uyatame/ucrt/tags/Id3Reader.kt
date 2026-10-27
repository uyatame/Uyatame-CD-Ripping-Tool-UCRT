package com.uyatame.ucrt.tags

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/** ID3v2(2.2 / 2.3 / 2.4)から読み取った曲情報 */
class Id3Data(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val track: Int? = null,
    val trackTotal: Int? = null,
    val disc: Int? = null,
    val discTotal: Int? = null,
    val picture: ByteArray? = null,
    /** 書き換えのときに残す画像フレーム(APIC の中身) */
    val rawApic: List<ByteArray> = emptyList(),
    val genre: String? = null,
    /** 歌詞(USLT) */
    val lyrics: String? = null,
    /** 作曲(TCOM) */
    val composer: String? = null,
    /** 作詞(TEXT) */
    val lyricist: String? = null,
    /** コメント(COMM のうち、説明が空のもの) */
    val comment: String? = null,
)

/** DSF / DSDIFF に入っている ID3v2 タグを読む(Android 標準の機能では読めないため) */
object Id3Reader {
    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun be24(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)

    /** 同期化解除(0xFF 0x00 → 0xFF) */
    private fun unsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if ((b[i].toInt() and 0xFF) == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private val sjis: Charset? = runCatching { Charset.forName("windows-31j") }.getOrNull()

    private fun strictDecode(cs: Charset, b: ByteArray, off: Int, len: Int): String? {
        val d: CharsetDecoder = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { d.decode(ByteBuffer.wrap(b, off, len)).toString() }.getOrNull()
    }

    /** テキストを取り出す(文字コードの指定に従う。ISO-8859-1 指定で日本語が入っていれば Shift_JIS / UTF-8 も試す) */
    private fun text(body: ByteArray, off0: Int = 0): String? {
        if (body.size <= off0) return null
        val enc = body[off0].toInt()
        val off = off0 + 1
        val len = body.size - off
        if (len <= 0) return null
        val s = when (enc) {
            1 -> {
                var o = off; var be = false
                if (len >= 2) {
                    val b0 = body[o].toInt() and 0xFF; val b1 = body[o + 1].toInt() and 0xFF
                    if (b0 == 0xFE && b1 == 0xFF) { be = true; o += 2 } else if (b0 == 0xFF && b1 == 0xFE) o += 2
                }
                String(body, o, body.size - o, if (be) Charsets.UTF_16BE else Charsets.UTF_16LE)
            }
            2 -> String(body, off, len, Charsets.UTF_16BE)
            3 -> String(body, off, len, Charsets.UTF_8)
            else -> {
                val ascii = (off until body.size).all { body[it] >= 0 }
                if (ascii) String(body, off, len, Charsets.ISO_8859_1)
                else strictDecode(Charsets.UTF_8, body, off, len)
                    ?: sjis?.let { strictDecode(it, body, off, len) }
                    ?: String(body, off, len, Charsets.ISO_8859_1)
            }
        }
        // 2.4 は複数の値を NUL で区切る。最初の値だけ使う
        return s.split('\u0000').firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** USLT(歌詞)の本文: 文字コード(1) 言語(3) 説明(終端まで) 歌詞 */
    private fun lyricsOf(body: ByteArray): String? {
        if (body.size < 5) return null
        val enc = body[0].toInt()
        var p = 4
        if (enc == 1 || enc == 2) {
            while (p + 1 < body.size && !(body[p].toInt() == 0 && body[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < body.size && body[p].toInt() != 0) p++
            p++
        }
        if (p >= body.size) return null
        // 文字コードの 1 バイトを付け直して、通常のテキストとして読む
        val b = ByteArray(1 + body.size - p)
        b[0] = body[0]
        System.arraycopy(body, p, b, 1, body.size - p)
        return text(b)
    }

    /** COMM / USLT の説明(言語の後ろ)が空か(iTunNORM などの説明付きのコメントは、利用者のコメントではない) */
    private fun emptyDesc(body: ByteArray): Boolean {
        if (body.size < 5) return false
        return when (body[0].toInt()) {
            1 -> (body.size >= 6 && body[4].toInt() == 0 && body[5].toInt() == 0) ||
                (body.size >= 8 && body[6].toInt() == 0 && body[7].toInt() == 0)
            2 -> body.size >= 6 && body[4].toInt() == 0 && body[5].toInt() == 0
            else -> body[4].toInt() == 0
        }
    }

    private fun num(s: String?): Pair<Int?, Int?> {
        if (s == null) return null to null
        val a = s.substringBefore('/').trim().toIntOrNull()
        val b = if ('/' in s) s.substringAfter('/').trim().toIntOrNull() else null
        return a to b
    }

    /** APIC / PIC の本文から画像データを取り出す */
    private fun picture(body: ByteArray, v22: Boolean): ByteArray? {
        if (body.size < 4) return null
        val enc = body[0].toInt()
        var p = 1
        if (v22) p += 3 else {
            while (p < body.size && body[p].toInt() != 0) p++ // MIME
            p++
        }
        p++ // 画像の種類
        // 説明(文字コードにより終端が 1 または 2 バイト)
        if (enc == 1 || enc == 2) {
            while (p + 1 < body.size && !(body[p].toInt() == 0 && body[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < body.size && body[p].toInt() != 0) p++
            p++
        }
        return if (p < body.size) body.copyOfRange(p, body.size) else null
    }

    /** 2.4 のフレームを、大きさを syncsafe(または普通の 32bit)としてたどり、途中で壊れずに終わりまで行けるか */
    private fun walks(b: ByteArray, start: Int, syncsafe: Boolean): Boolean {
        var p = start
        while (p + 10 <= b.size) {
            if (b[p].toInt() == 0) return true // 詰め物
            for (k in 0 until 4) {
                val c = b[p + k].toInt().toChar()
                if (c !in 'A'..'Z' && c !in '0'..'9') return false
            }
            val fs = if (syncsafe) syncsafe(b, p + 4).toLong() else be32(b, p + 4).toLong() and 0xFFFFFFFFL
            if (syncsafe && (b[p + 4].toInt() or b[p + 5].toInt() or b[p + 6].toInt() or b[p + 7].toInt()) and 0x80 != 0) return false
            if (fs <= 0 || p + 10 + fs > b.size) return false
            p += 10 + fs.toInt()
        }
        return true
    }

    /** フレーム 1 つ。body は同期化・グループ識別・データ長の指示を戻したもの。status は 2.3 の形の状態フラグ */
    internal class Frame(val id: String, val body: ByteArray, val status: Int)

    internal class Frames(val version: Int, val list: List<Frame>)

    /** タグのフレームを並べる(圧縮・暗号化されたフレームは除く)。ID3v2 でなければ null */
    internal fun frames(tag: ByteArray): Frames? {
        if (tag.size < 10 || tag[0] != 'I'.code.toByte() || tag[1] != 'D'.code.toByte() || tag[2] != '3'.code.toByte()) return null
        val ver = tag[3].toInt()
        val flags = tag[5].toInt() and 0xFF
        val size = syncsafe(tag, 6)
        var b = tag.copyOfRange(10, minOf(tag.size, 10 + size))
        if ((flags and 0x80) != 0 && ver < 4) b = unsync(b)
        // 2.4 はタグ全体の同期化の印があれば、フレームごとに戻す
        val unsync24 = (flags and 0x80) != 0 && ver >= 4
        var p = 0
        if ((flags and 0x40) != 0 && ver >= 3 && b.size >= 4) {
            val ext = if (ver >= 4) syncsafe(b, 0).toLong() else (be32(b, 0).toLong() and 0xFFFFFFFFL) + 4
            if (ext > b.size) return null // 壊れている
            p = ext.toInt()
        }
        val v22 = ver == 2
        val hdr = if (v22) 6 else 10
        // 2.4 なのにフレームの大きさを syncsafe にしていないもの(古い iTunes など)がある。
        // syncsafe としてたどれず、普通の 32bit としてならたどれるなら、そちらで読む
        val plain24 = ver >= 4 && !walks(b, p, true) && walks(b, p, false)
        val out = ArrayList<Frame>()
        while (p + hdr <= b.size) {
            if (b[p].toInt() == 0) break
            val id = String(b, p, if (v22) 3 else 4, Charsets.ISO_8859_1)
            val fs = when {
                v22 -> be24(b, p + 3)
                ver >= 4 && !plain24 -> syncsafe(b, p + 4)
                else -> be32(b, p + 4)
            }
            if (fs <= 0 || p.toLong() + hdr + fs > b.size) break
            var body = b.copyOfRange(p + hdr, p + hdr + fs)
            var status = 0
            if (ver >= 4) {
                status = ((b[p + 8].toInt() and 0x70) shl 1)
                val ff = b[p + 9].toInt() and 0xFF
                // 圧縮・暗号化されたフレームは読まない
                if ((ff and 0x0C) != 0) { p += hdr + fs; continue }
                if ((ff and 0x40) != 0 && body.isNotEmpty()) body = body.copyOfRange(1, body.size) // グループ識別
                if ((ff and 0x02) != 0 || unsync24) body = unsync(body)
                if ((ff and 0x01) != 0 && body.size > 4) body = body.copyOfRange(4, body.size)
            } else if (ver == 3) {
                status = b[p + 8].toInt() and 0xE0
                val ff = b[p + 9].toInt() and 0xFF
                if ((ff and 0xC0) != 0) { p += hdr + fs; continue }
                if ((ff and 0x20) != 0 && body.isNotEmpty()) body = body.copyOfRange(1, body.size)
            }
            out.add(Frame(id, body, status))
            p += hdr + fs
        }
        return Frames(ver, out)
    }

    fun parse(tag: ByteArray): Id3Data? {
        val fr = frames(tag) ?: return null
        val v22 = fr.version == 2
        var title: String? = null; var artist: String? = null; var album: String? = null
        var albumArtist: String? = null; var year: String? = null
        var trk: String? = null; var pos: String? = null
        var pic: ByteArray? = null
        var genre: String? = null
        var lyrics: String? = null
        var composer: String? = null
        var lyricist: String? = null
        var comment: String? = null
        val apics = ArrayList<ByteArray>()
        for (f in fr.list) {
            val body = f.body
            when (f.id) {
                "TIT2", "TT2" -> title = text(body)
                "TPE1", "TP1" -> artist = text(body)
                "TALB", "TAL" -> album = text(body)
                "TPE2", "TP2" -> albumArtist = text(body)
                "TYER", "TYE", "TDRC" -> if (year == null) year = text(body)?.take(4)
                "TRCK", "TRK" -> trk = text(body)
                "TPOS", "TPA" -> pos = text(body)
                "TCON", "TCO" -> if (genre == null) genre = text(body)
                "USLT", "ULT" -> if (lyrics == null) lyrics = lyricsOf(body)
                "TCOM", "TCM" -> if (composer == null) composer = text(body)
                "TEXT", "TXT" -> if (lyricist == null) lyricist = text(body)
                "COMM", "COM" -> if (comment == null && emptyDesc(body)) comment = lyricsOf(body)
                "APIC", "PIC" -> {
                    if (pic == null) pic = picture(body, v22)
                    if (!v22) apics.add(body)
                }
            }
        }
        val (t, tt) = num(trk)
        val (d, dt) = num(pos)
        return Id3Data(
            title, artist, album, albumArtist, year, t, tt, d, dt, pic, apics, genre = genre, lyrics = lyrics,
            composer = composer, lyricist = lyricist, comment = comment,
        )
    }
}
