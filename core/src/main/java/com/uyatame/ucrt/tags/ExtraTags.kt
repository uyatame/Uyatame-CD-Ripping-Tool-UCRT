package com.uyatame.ucrt.tags

import com.uyatame.ucrt.player.RandomInput

/**
 * Android 標準の機能では曲情報を読めない形式の曲情報を読む。
 * - AIFF: 「ID3 」チャンク(なければ NAME / AUTH チャンク)
 * - WavPack・APE: ファイルの終わりの APEv2 タグ
 */
internal object ExtraTags {
    fun read(kind: String, src: RandomInput): Id3Data? = runCatching {
        when (kind) {
            "aiff" -> aiff(src)
            "wavpack", "ape" -> apev2(src)
            else -> null
        }
    }.getOrNull()

    private fun readAt(src: RandomInput, pos: Long, n: Int): ByteArray? {
        if (pos < 0 || n < 0 || pos + n > src.size) return null
        val b = ByteArray(n)
        var o = 0
        while (o < n) {
            val r = src.read(pos + o, b, o, n - o)
            if (r <= 0) return null
            o += r
        }
        return b
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    // ---- AIFF ----

    private fun aiff(src: RandomInput): Id3Data? {
        val head = readAt(src, 0, 12) ?: return null
        if (String(head, 0, 4, Charsets.ISO_8859_1) != "FORM") return null
        val end = minOf(src.size, 8 + be32(head, 4))
        var pos = 12L
        var name: String? = null
        var auth: String? = null
        while (pos + 8 <= end) {
            val h = readAt(src, pos, 8) ?: break
            val id = String(h, 0, 4, Charsets.ISO_8859_1)
            val size = be32(h, 4)
            val body = pos + 8
            when (id) {
                "ID3 ", "id3 " -> {
                    if (size in 10..(64L shl 20)) {
                        readAt(src, body, size.toInt())?.let { Id3Reader.parse(it) }?.let { return it }
                    }
                }
                "NAME" -> if (size in 1..1024) name = readAt(src, body, size.toInt())?.let { text(it) }
                "AUTH" -> if (size in 1..1024) auth = readAt(src, body, size.toInt())?.let { text(it) }
            }
            pos = body + size + (size and 1)
        }
        if (name == null && auth == null) return null
        return Id3Data(title = name, artist = auth)
    }

    private fun text(b: ByteArray): String? {
        val n = b.indexOf(0).let { if (it < 0) b.size else it }
        return String(b, 0, n, Charsets.ISO_8859_1).trim().ifEmpty { null }
    }

    // ---- APEv2 ----

    private fun apev2(src: RandomInput): Id3Data? {
        // 終わりに ID3v1(128 バイト)が付いていることがある
        for (tail in longArrayOf(32, 32 + 128)) {
            val f = readAt(src, src.size - tail, 32) ?: continue
            if (String(f, 0, 8, Charsets.ISO_8859_1) != "APETAGEX") continue
            val tagSize = le32(f, 12) // 項目 + フッター(ヘッダーは含まない)
            val count = le32(f, 16).toInt()
            val footerPos = src.size - tail
            val itemsPos = footerPos + 32 - tagSize
            val len = (tagSize - 32).toInt()
            if (len <= 0 || len > (64 shl 20) || itemsPos < 0) return null
            val b = readAt(src, itemsPos, len) ?: return null
            return parseItems(b, count)
        }
        return null
    }

    private fun parseItems(b: ByteArray, count: Int): Id3Data {
        val m = HashMap<String, String>()
        var pic: ByteArray? = null
        var p = 0
        for (i in 0 until count) {
            if (p + 8 > b.size) break
            val vlen = le32(b, p).toInt()
            val flags = le32(b, p + 4).toInt()
            p += 8
            val k0 = p
            while (p < b.size && b[p] != 0.toByte()) p++
            if (p >= b.size) break
            val key = String(b, k0, p - k0, Charsets.ISO_8859_1).lowercase()
            p++
            if (vlen < 0 || p.toLong() + vlen > b.size) break
            val binary = ((flags shr 1) and 3) == 1
            if (binary) {
                if (key.startsWith("cover art") && (pic == null || key == "cover art (front)")) {
                    // 説明文(0 で終わる)のあとが画像
                    var q = p
                    while (q < p + vlen && b[q] != 0.toByte()) q++
                    if (q < p + vlen) pic = b.copyOfRange(q + 1, p + vlen)
                }
            } else {
                // 複数の値は 0 で区切られている。最初のものを使う
                m[key] = String(b, p, vlen, Charsets.UTF_8).substringBefore('\u0000').trim()
            }
            p += vlen
        }
        fun num(s: String?): Pair<Int?, Int?> {
            if (s == null) return null to null
            val a = s.split('/')
            return a[0].trim().toIntOrNull() to a.getOrNull(1)?.trim()?.toIntOrNull()
        }
        val (track, trackTotal) = num(m["track"])
        val (disc, discTotal) = num(m["disc"] ?: m["discnumber"])
        fun v(vararg keys: String) = keys.firstNotNullOfOrNull { m[it]?.ifEmpty { null } }
        return Id3Data(
            title = v("title"),
            artist = v("artist"),
            album = v("album"),
            albumArtist = v("album artist", "albumartist"),
            year = v("year", "date")?.take(4),
            track = track,
            trackTotal = trackTotal,
            disc = disc,
            discTotal = discTotal,
            picture = pic,
            genre = v("genre"),
            lyrics = v("lyrics", "unsyncedlyrics"),
        )
    }
}
