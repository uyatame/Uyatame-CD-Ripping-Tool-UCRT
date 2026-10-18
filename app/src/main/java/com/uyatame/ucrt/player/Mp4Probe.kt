package com.uyatame.ucrt.player

/**
 * MP4 / M4A の中の音声の形式(サンプルエントリーの 4 文字: "mp4a" = AAC、"alac"、"mha1" / "mhm1" = MPEG-H 3D Audio など)を調べる。
 * 中身を読まずに箱(box)の見出しだけをたどるので速い
 */
internal object Mp4Probe {
    /** 最初の音声トラックのサンプルエントリー。MP4 でない・見つからなければ null */
    fun audioEntry(src: RandomInput): String? = runCatching { walk(src, 0, src.size, 0) }.getOrNull()

    /** MPEG-H 3D Audio(360 Reality Audio など)か */
    fun isMpegH(entry: String?): Boolean = entry == "mha1" || entry == "mhm1" || entry == "mha2" || entry == "mhm2"

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")

    private fun readAt(src: RandomInput, pos: Long, n: Int): ByteArray? {
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

    private fun walk(src: RandomInput, start: Long, end: Long, depth: Int): String? {
        // 入れ子は moov/trak/mdia/minf/stbl の 5 段まで(壊れたファイルで深く潜り続けないように)
        if (depth > 8) return null
        var pos = start
        var isSound = false
        while (pos + 8 <= end) {
            val h = readAt(src, pos, 16) ?: readAt(src, pos, 8) ?: return null
            var size = be32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var head = 8
            if (size == 1L && h.size >= 16) {
                size = (be32(h, 8) shl 32) or be32(h, 12)
                head = 16
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < head) return null
            // 外の箱をはみ出す大きさは、外の箱の終わりまでとみなす(大きすぎる値で位置が一周しないように)
            if (size > end - pos) size = end - pos
            when {
                // 音声のトラック(hdlr が 'soun')の minf だけを見る
                type == "minf" && !isSound -> {}
                type in CONTAINERS -> walk(src, pos + head, pos + size, depth + 1)?.let { return it }
                type == "hdlr" -> {
                    // 'soun' のトラックだけを見る
                    val b = readAt(src, pos + head, 12)
                    if (b != null) isSound = String(b, 8, 4, Charsets.ISO_8859_1) == "soun"
                }
                type == "stsd" -> {
                    val b = readAt(src, pos + head + 8, 8) ?: return null
                    return String(b, 4, 4, Charsets.ISO_8859_1)
                }
            }
            pos += size
        }
        return null
    }
}
