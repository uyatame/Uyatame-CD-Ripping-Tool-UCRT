package com.uyatame.ucrt.airplay

/**
 * DACP のメタデータ (SET_PARAMETER の application/x-dmap-tagged) の解釈。
 * 形式は「4 バイトのタグ + 4 バイトの長さ (ビッグエンディアン) + 値」の繰り返し。
 * 曲名は minm、アーティストは asar、アルバムは asal に入って届く。
 */
internal object DmapParser {

    /** 文字列として読むタグ。 */
    private val STRING_TAGS = setOf("minm", "asal", "asar", "asgn", "asdt", "ascm", "ascp", "aslc", "asrv")

    /** タグ → 値 (文字列タグは String、それ以外は ByteArray)。壊れた入力では途中までで止める。 */
    fun parse(data: ByteArray): Map<String, Any> {
        val out = parseChunks(data)
        // 曲情報が外側に無く、最初の塊が入れ物 (mlit など) なら、その中も見る
        if (title(out) == null && artist(out) == null && album(out) == null) {
            val first = out.values.firstOrNull() as? ByteArray
            if (first != null && first.size >= 8) {
                val inner = parseChunks(first)
                if (title(inner) != null || artist(inner) != null || album(inner) != null) {
                    return inner
                }
            }
        }
        return out
    }

    private fun parseChunks(data: ByteArray): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        var off = 0
        while (off + 8 <= data.size) {
            val tag = String(data, off, 4, Charsets.US_ASCII)
            val len = ((data[off + 4].toInt() and 0xFF) shl 24) or
                ((data[off + 5].toInt() and 0xFF) shl 16) or
                ((data[off + 6].toInt() and 0xFF) shl 8) or
                (data[off + 7].toInt() and 0xFF)
            if (len < 0 || off + 8 + len > data.size) break
            val value = data.copyOfRange(off + 8, off + 8 + len)
            out[tag] = if (tag in STRING_TAGS) String(value, Charsets.UTF_8) else value
            off += 8 + len
        }
        return out
    }

    /** 曲名 (minm)。無ければ null。 */
    fun title(tags: Map<String, Any>): String? = tags["minm"] as? String

    /** アーティスト (asar)。無ければ null。 */
    fun artist(tags: Map<String, Any>): String? = tags["asar"] as? String

    /** アルバム (asal)。無ければ null。 */
    fun album(tags: Map<String, Any>): String? = tags["asal"] as? String
}
