package com.uyatame.ucrt.library

import com.uyatame.ucrt.player.RandomInput

/**
 * 曲のファイルに埋め込まれたジャケット画像を、アプリで直接読む(FLAC・MP3・M4A)。
 * Android 標準の読み込み(MediaMetadataRetriever)は、古い Android では FLAC の画像を読めないことがあり、
 * また曲全体を調べるので遅い。ここではタグの部分だけを読む
 */
internal object ArtExtract {
    /** 画像のバイト列。画像が無い・この形式に対応していなければ null */
    fun picture(src: RandomInput, ext: String): ByteArray? = runCatching {
        when (ext) {
            "flac" -> flac(src)
            "mp3" -> id3(src)
            "m4a", "mp4", "alac" -> mp4(src)
            else -> null
        }
    }.getOrNull()

    private fun readAt(src: RandomInput, pos: Long, n: Int): ByteArray? {
        if (n < 0 || pos < 0 || pos + n > src.size) return null
        val b = ByteArray(n)
        var o = 0
        while (o < n) {
            val r = src.read(pos + o, b, o, n - o)
            if (r <= 0) return null
            o += r
        }
        return b
    }

    /** 埋め込み画像の大きさの上限(壊れた値で大きな配列を作らない) */
    private const val MAX_PICTURE = 32L shl 20

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    // ---- FLAC: PICTURE ブロック ----

    private fun flac(src: RandomInput): ByteArray? {
        var pos = 0L
        // 先頭に ID3v2 が付いていれば飛ばす
        val h = readAt(src, 0, 10) ?: return null
        if (h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) {
            pos = 10L + syncsafe(h, 6) + (if ((h[5].toInt() and 0x10) != 0) 10 else 0) // フッターがあればその分も
        }
        val m = readAt(src, pos, 4) ?: return null
        if (String(m, Charsets.ISO_8859_1) != "fLaC") return null
        pos += 4
        var first: ByteArray? = null
        while (true) {
            val bh = readAt(src, pos, 4) ?: break
            val last = (bh[0].toInt() and 0x80) != 0
            val type = bh[0].toInt() and 0x7F
            val len = ((bh[1].toInt() and 0xFF) shl 16) or ((bh[2].toInt() and 0xFF) shl 8) or (bh[3].toInt() and 0xFF)
            if (type == 6 && len in 32..(32 shl 20)) {
                val b = readAt(src, pos + 4, len) ?: break
                // 長さは壊れていることがあるので、Long で計算してブロックの中に収まるかを確かめる
                val picType = be32(b, 0).toInt()
                var p = 8 + be32(b, 4) // MIME
                if (p + 4 <= b.size) {
                    p += 4 + be32(b, p.toInt()) + 16 // 説明、幅・高さ・色深度・色数
                    if (p + 4 <= b.size) {
                        val dataLen = be32(b, p.toInt())
                        p += 4
                        if (dataLen > 0 && p + dataLen <= b.size) {
                            val img = b.copyOfRange(p.toInt(), (p + dataLen).toInt())
                            // 表紙(3)があればそれ、なければ最初の画像
                            if (picType == 3) return img
                            if (first == null) first = img
                        }
                    }
                }
            }
            pos += 4 + len
            if (last) break
        }
        return first
    }

    // ---- MP3: ID3v2 の APIC ----

    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun id3(src: RandomInput): ByteArray? {
        val h = readAt(src, 0, 10) ?: return null
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return null
        val size = syncsafe(h, 6)
        if (size <= 0 || size > (64 shl 20)) return null
        val tag = readAt(src, 0, 10 + size) ?: return null
        return com.uyatame.ucrt.tags.Id3Reader.parse(tag)?.picture
    }

    // ---- M4A / MP4: moov/udta/meta/ilst/covr/data ----

    private fun mp4(src: RandomInput): ByteArray? = box(src, 0, src.size, listOf("moov", "udta", "meta", "ilst", "covr", "data"), 0)

    private fun box(src: RandomInput, start: Long, end: Long, path: List<String>, depth: Int): ByteArray? {
        var pos = start
        while (pos + 8 <= end) {
            val h = readAt(src, pos, 16) ?: readAt(src, pos, 8) ?: return null
            var size = be32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var head = 8
            if (size == 1L && h.size >= 16) { size = (be32(h, 8) shl 32) or be32(h, 12); head = 16 }
            else if (size == 0L) size = end - pos
            if (size < head) return null
            // 外の箱をはみ出す大きさは、外の箱の終わりまでとみなす(大きすぎる値で位置が一周しないように)
            if (size > end - pos) size = end - pos
            if (type == path[depth]) {
                if (depth == path.lastIndex) {
                    // data: 型(4)・ロケール(4)のあとが画像
                    val n = size - head - 8
                    return if (n > 0 && n <= MAX_PICTURE) readAt(src, pos + head + 8, n.toInt()) else null
                }
                // meta は中身の前にバージョンとフラグ(4 バイト)がある(QuickTime 式の、無いものもある)
                var inner = pos + head
                if (type == "meta") {
                    val q = readAt(src, inner, 8)
                    if (q == null || String(q, 4, 4, Charsets.ISO_8859_1) != "hdlr") inner += 4
                }
                return box(src, inner, pos + size, path, depth + 1)
            }
            pos += size
        }
        return null
    }
}
