package com.uyatame.ucrt.tags

import android.content.Context
import android.net.Uri
import com.uyatame.ucrt.player.BuiltinCodecs
import com.uyatame.ucrt.player.RandomInput
import java.io.ByteArrayOutputStream

/**
 * 曲の詳しい情報(ジャンル・作曲・作詞・コメント・歌詞)。
 * それぞれ null なら書き換えない(元のまま残す)。空の文字なら消す
 */
class TagExtra(
    val genre: String? = null,
    val composer: String? = null,
    val lyricist: String? = null,
    val comment: String? = null,
    val lyrics: String? = null,
) {
    val isEmpty: Boolean get() = genre == null && composer == null && lyricist == null && comment == null && lyrics == null

    /** FLAC(Vorbis コメント): 書き換えるので、古い値を消す項目 */
    internal fun vorbisKeys(): Set<String> = buildSet {
        if (genre != null) add("GENRE")
        if (composer != null) add("COMPOSER")
        if (lyricist != null) add("LYRICIST")
        if (comment != null) { add("COMMENT"); add("DESCRIPTION") }
        if (lyrics != null) { add("LYRICS"); add("UNSYNCEDLYRICS") }
    }

    /** FLAC(Vorbis コメント)に書く項目(空のものは書かない) */
    fun vorbisPairs(): List<Pair<String, String>> = listOfNotNull(
        genre?.let { "GENRE" to it },
        composer?.let { "COMPOSER" to it },
        lyricist?.let { "LYRICIST" to it },
        comment?.let { "COMMENT" to it },
        lyrics?.let { "LYRICS" to it },
    ).filter { it.second.isNotBlank() }.map { it.first to it.second.trim() }

    /** ID3v2(MP3 など): 書き換えるので、古いものを消すフレーム */
    internal fun id3Ids(): Set<String> = buildSet {
        if (genre != null) add("TCON")
        if (composer != null) add("TCOM")
        if (lyricist != null) add("TEXT")
        if (comment != null) add("COMM")
        if (lyrics != null) add("USLT")
    }

    /** ID3v2.3 のフレーム(UTF-16)。空のものは作らない */
    fun id3Frames(): ByteArray {
        val o = ByteArrayOutputStream()
        fun frame(id: String, body: ByteArray) {
            o.write(id.toByteArray(Charsets.ISO_8859_1))
            o.write(byteArrayOf((body.size shr 24).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()))
            o.write(byteArrayOf(0, 0))
            o.write(body)
        }
        fun utf16(s: String) = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)
        fun text(id: String, v: String?) {
            if (v.isNullOrBlank()) return
            frame(id, byteArrayOf(1) + utf16(v.trim()) + byteArrayOf(0, 0))
        }
        // COMM / USLT: 文字コード・言語・説明(空)・本文
        fun long(id: String, v: String?) {
            if (v.isNullOrBlank()) return
            frame(id, byteArrayOf(1) + "und".toByteArray(Charsets.ISO_8859_1) + utf16("") + byteArrayOf(0, 0) + utf16(v.trim()))
        }
        text("TCON", genre)
        text("TCOM", composer)
        text("TEXT", lyricist)
        long("COMM", comment)
        long("USLT", lyrics)
        return o.toByteArray()
    }

    companion object {
        /** 詳しい情報を書き換えられる形式(WAV・DSF などは、基本の曲情報だけ) */
        fun supported(uri: String): Boolean =
            '#' !in uri && Uri.decode(uri).substringAfterLast('.', "").lowercase() in setOf("flac", "mp3", "m4a")

        /** 曲の今の値を読む(対応していない形式・読めなければ null) */
        fun read(ctx: Context, uri: String): TagExtra? {
            if (!supported(uri)) return null
            val ext = Uri.decode(uri).substringAfterLast('.', "").lowercase()
            return runCatching { BuiltinCodecs.openInput(ctx, Uri.parse(uri))?.use { readFrom(it, ext) } }.getOrNull()
        }

        /** 形式(拡張子)ごとに読む */
        internal fun readFrom(src: RandomInput, ext: String): TagExtra? = when (ext) {
            "flac" -> flac(src)
            "mp3" -> mp3(src)
            "m4a", "mp4" -> mp4(src)
            else -> null
        }

        private fun bytes(src: RandomInput, pos: Long, n: Int): ByteArray? {
            if (n < 0 || pos + n > src.size) return null
            val b = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = src.read(pos + off, b, off, n - off)
                if (r <= 0) return null
                off += r
            }
            return b
        }

        private fun le32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        private fun be32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

        private fun flac(src: RandomInput): TagExtra? {
            var p = 0L
            var head = bytes(src, 0, 10) ?: return null
            if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                p = 10L + (((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F))
                head = bytes(src, p, 4) ?: return null
            }
            if (String(head, 0, 4, Charsets.ISO_8859_1) != "fLaC") return null
            p += 4
            while (true) {
                val h = bytes(src, p, 4) ?: return null
                val type = h[0].toInt() and 0x7F
                val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                if (type == 4) {
                    val b = bytes(src, p + 4, len) ?: return null
                    val map = HashMap<String, String>()
                    var q = 4 + le32(b, 0)
                    val count = le32(b, q)
                    q += 4
                    for (i in 0 until count) {
                        if (q + 4 > b.size) break
                        val n = le32(b, q)
                        q += 4
                        if (n < 0 || q + n > b.size) break
                        val s = String(b, q, n, Charsets.UTF_8)
                        q += n
                        val eq = s.indexOf('=')
                        if (eq > 0) map.putIfAbsentCompat(s.substring(0, eq).uppercase(), s.substring(eq + 1))
                    }
                    return TagExtra(
                        map["GENRE"].orEmpty(), map["COMPOSER"].orEmpty(), map["LYRICIST"].orEmpty(),
                        (map["COMMENT"] ?: map["DESCRIPTION"]).orEmpty(), (map["LYRICS"] ?: map["UNSYNCEDLYRICS"]).orEmpty(),
                    )
                }
                if ((h[0].toInt() and 0x80) != 0) return TagExtra("", "", "", "", "")
                p += 4 + len
            }
        }

        /** putIfAbsent は Android 7 からなので使わない */
        private fun HashMap<String, String>.putIfAbsentCompat(k: String, v: String) {
            if (!containsKey(k)) put(k, v)
        }

        private fun mp3(src: RandomInput): TagExtra? {
            val h = bytes(src, 0, 10) ?: return null
            if (!(h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte())) return TagExtra("", "", "", "", "")
            val size = ((h[6].toInt() and 0x7F) shl 21) or ((h[7].toInt() and 0x7F) shl 14) or ((h[8].toInt() and 0x7F) shl 7) or (h[9].toInt() and 0x7F)
            val tag = bytes(src, 0, 10 + size) ?: return null
            val d = Id3Reader.parse(tag) ?: return null
            return TagExtra(d.genre.orEmpty(), d.composer.orEmpty(), d.lyricist.orEmpty(), d.comment.orEmpty(), d.lyrics.orEmpty())
        }

        /** MP4: moov / udta / meta / ilst の項目(©gen ©wrt ©cmt ©lyr と、自由形式の LYRICIST) */
        private fun mp4(src: RandomInput): TagExtra? {
            fun find(start: Long, end: Long, type: String): Pair<Long, Long>? {
                var p = start
                while (p + 8 <= end) {
                    val b = bytes(src, p, 8) ?: return null
                    var size = be32(b, 0)
                    val t = String(b, 4, 4, Charsets.ISO_8859_1)
                    if (size == 0L) size = end - p
                    if (size < 8) return null
                    if (t == type) return p to (p + size)
                    p += size
                }
                return null
            }
            val moov = find(0, src.size, "moov") ?: return null
            val udta = find(moov.first + 8, moov.second, "udta") ?: return TagExtra("", "", "", "", "")
            val meta = find(udta.first + 8, udta.second, "meta") ?: return TagExtra("", "", "", "", "")
            val ilst = find(meta.first + 12, meta.second, "ilst") ?: find(meta.first + 8, meta.second, "ilst") ?: return TagExtra("", "", "", "", "")
            val b = bytes(src, ilst.first, (ilst.second - ilst.first).toInt()) ?: return null
            val map = HashMap<String, String>()
            var p = 8
            while (p + 8 <= b.size) {
                val size = be32(b, p).toInt()
                if (size < 8 || p + size > b.size) break
                val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
                // 中の箱(mean・name・data)
                var q = p + 8
                var name = type
                var value: String? = null
                while (q + 8 <= p + size) {
                    val s = be32(b, q).toInt()
                    if (s < 8 || q + s > p + size) break
                    when (String(b, q + 4, 4, Charsets.ISO_8859_1)) {
                        "name" -> if (s > 12) name = "----:" + String(b, q + 12, s - 12, Charsets.UTF_8).uppercase()
                        "data" -> if (s > 16) value = String(b, q + 16, s - 16, Charsets.UTF_8)
                    }
                    q += s
                }
                if (value != null && !map.containsKey(name)) map[name] = value
                p += size
            }
            return TagExtra(
                map["©gen"].orEmpty(), map["©wrt"].orEmpty(), map["----:LYRICIST"].orEmpty(),
                map["©cmt"].orEmpty(), map["©lyr"].orEmpty(),
            )
        }
    }
}
