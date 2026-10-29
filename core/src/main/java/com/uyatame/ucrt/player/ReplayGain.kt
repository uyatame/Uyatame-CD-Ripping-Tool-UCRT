package com.uyatame.ucrt.player

import android.content.Context
import android.net.Uri
import kotlin.math.min
import kotlin.math.pow

/** 曲に書かれた ReplayGain の値(dB と、ピークの大きさ。1.0 = 0 dBFS)。書かれていない項目は null */
data class RgInfo(
    val trackGain: Float? = null,
    val trackPeak: Float? = null,
    val albumGain: Float? = null,
    val albumPeak: Float? = null,
) {
    val isEmpty: Boolean get() = trackGain == null && albumGain == null
}

/**
 * ReplayGain: 曲ごと(またはアルバムごと)の音の大きさの違いを、曲に書かれた値でそろえる。
 *
 * 読む場所:
 * - FLAC: Vorbis コメント(REPLAYGAIN_TRACK_GAIN など)
 * - MP3・AIFF・WAV・DSF: ID3v2 の TXXX
 * - M4A(AAC・ALAC): iTunes 形式の項目(----:com.apple.iTunes:replaygain_track_gain)
 * - WavPack・APE: APEv2 タグ(MP3 の終わりに付いた APEv2 タグも)
 * - Ogg Vorbis・Opus: Vorbis コメント。Opus の R128_TRACK_GAIN・R128_ALBUM_GAIN(foobar2000 などが書く)も読む
 * foobar2000 などほかのソフトで解析して書いた値も、そのまま使う(UCRT で解析し直さなくてよい)
 * FLAC 以外は、タグのある範囲を読んで項目の名前を探し、その後ろの数値を読む(形式ごとの細かな違いに強くするため)
 */
object ReplayGain {
    /** 0 = 使わない、1 = 曲ごと、2 = アルバムごと(アルバムの値が無ければ曲の値) */
    @Volatile var mode = 0
    /** 値に足す量(dB)。ReplayGain は小さめにそろえるので、少し上げたいときに使う */
    @Volatile var preampDb = 0f
    /** 上げたときに音が割れないよう、ピークの値で上げ幅を抑える */
    @Volatile var preventClipping = true

    private const val KEY_TG = "replaygain_track_gain"
    private const val KEY_TP = "replaygain_track_peak"
    private const val KEY_AG = "replaygain_album_gain"
    private const val KEY_AP = "replaygain_album_peak"
    /** Opus の値(1/256 dB 単位の整数。基準が EBU R128 の -23 LUFS なので、ReplayGain の -18 LUFS に合わせて 5 dB 足す) */
    private const val KEY_R128_T = "r128_track_gain"
    private const val KEY_R128_A = "r128_album_gain"

    private fun r128(v: Float?): Float? = v?.let { it / 256f + 5f }

    /** 掛ける倍率(1 = そのまま)。値が書かれていない曲・使わない設定では 1 */
    fun linear(i: RgInfo?): Float {
        if (mode == 0 || i == null) return 1f
        val album = mode == 2
        val g = (if (album) i.albumGain ?: i.trackGain else i.trackGain ?: i.albumGain) ?: return 1f
        val p = if (album) i.albumPeak ?: i.trackPeak else i.trackPeak ?: i.albumPeak
        var lin = 10.0.pow((g + preampDb) / 20.0).toFloat()
        if (preventClipping && p != null && p > 0f) lin = min(lin, 1f / p)
        return lin
    }

    /** 曲の ReplayGain の値を読む(CUE シートで分けた曲は元のファイルから)。読めなければ null */
    fun read(ctx: Context, uri: Uri): RgInfo? = runCatching {
        val u = Uri.parse(com.uyatame.ucrt.library.Cue.fileUri(uri.toString()))
        val name = Uri.decode(u.toString()).substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        BuiltinCodecs.openInput(ctx, u)?.use { read(it, ext) }
    }.getOrNull()?.takeIf { !it.isEmpty }

    /** 曲のタグに ReplayGain の値が書かれているか(書かれていれば、UCRT で解析しなくてよい) */
    fun hasTags(ctx: Context, uri: String): Boolean = read(ctx, Uri.parse(uri))?.trackGain != null

    /** 診断用: 最後に読んだ曲の形式と、見つかったタグの名前(値が無かったときに、ログで理由が分かるように) */
    @Volatile var lastNote = ""
        private set

    /** ファイルの中身から形式を見分ける(拡張子が無い・違う URI でも読めるように)。見分けられなければ拡張子 */
    private fun sniff(src: RandomInput, ext: String): Pair<String, Long> {
        val h = readAt(src, 0, min(src.size, 12L).toInt()) ?: return ext to 0L
        fun at(o: Int, s: String) = h.size >= o + s.length && String(h, o, s.length, Charsets.ISO_8859_1) == s
        return when {
            at(0, "fLaC") -> "flac" to 0L
            at(4, "ftyp") -> "m4a" to 0L
            at(0, "DSD ") -> "dsf" to 0L
            at(0, "ID3") && h.size >= 10 -> {
                // 先頭に ID3v2 が付いた FLAC もある
                val size = ((h[6].toLong() and 0x7F) shl 21) or ((h[7].toLong() and 0x7F) shl 14) or
                    ((h[8].toLong() and 0x7F) shl 7) or (h[9].toLong() and 0x7F)
                val after = 10 + size
                val m = readAt(src, after, 4)
                if (m != null && String(m, Charsets.ISO_8859_1) == "fLaC") "flac" to after else "mp3" to 0L
            }
            else -> ext to 0L
        }
    }

    internal fun read(src: RandomInput, ext0: String): RgInfo? {
        val (ext, flacAt) = sniff(src, ext0)
        lastNote = ext.ifEmpty { "?" }
        if (ext == "flac") flacComments(src, flacAt)?.let { return it }
        val parts = ArrayList<ByteArray>()
        when (ext) {
            "mp3" -> id3v2AtStart(src)?.let { parts.add(it) }
            "m4a", "mp4", "alac" -> mp4Udta(src)?.let { parts.add(it) }
            "dsf" -> dsfMetadata(src)?.let { parts.add(it) }
        }
        val specific = parts.isNotEmpty()
        if (parts.isEmpty()) {
            // そのほかの形式(とうまく見つからなかったとき): ファイルの初めと終わりを調べる(タグはどちらかにある)
            val n = min(src.size, HEAD).toInt()
            readAt(src, 0, n)?.let { parts.add(it) }
            if (src.size > HEAD) {
                val t = min(src.size - HEAD, TAIL).toInt()
                readAt(src, src.size - t, t)?.let { parts.add(it) }
            }
        }
        var out = RgInfo()
        for (p in parts) out = merge(out, scan(p))
        // 形式ごとのタグに値が無ければ、ファイルの終わりも調べる(MP3 の終わりの APEv2 タグに書くソフトもある)
        if (specific && out.isEmpty && src.size > HEAD) {
            val t = min(src.size - HEAD, TAIL).toInt()
            readAt(src, src.size - t, t)?.let { out = merge(out, scan(it)) }
        }
        return out
    }

    private const val HEAD = 1L shl 20
    private const val TAIL = 1L shl 20
    private const val MAX_TAG = 32L shl 20

    private fun merge(a: RgInfo, b: RgInfo) = RgInfo(
        a.trackGain ?: b.trackGain, a.trackPeak ?: b.trackPeak, a.albumGain ?: b.albumGain, a.albumPeak ?: b.albumPeak,
    )

    /**
     * タグの中身から ReplayGain の項目を探す。項目の名前(大文字・小文字は区別しない)の後ろにある最初の数値を読む。
     * UTF-16 で書かれた ID3 の TXXX にも対応するため、0 のバイトを除いたものでも探す
     */
    internal fun scan(b: ByteArray): RgInfo {
        val a = scanAscii(b)
        if (a.trackGain != null && a.albumGain != null) return a
        val packed = ByteArray(b.size)
        var n = 0
        for (x in b) if (x.toInt() != 0) packed[n++] = x
        return merge(a, scanAscii(packed.copyOf(n)))
    }

    private fun scanAscii(b: ByteArray): RgInfo {
        val low = ByteArray(b.size)
        for (i in b.indices) {
            val c = b[i].toInt() and 0xFF
            low[i] = (if (c in 'A'.code..'Z'.code) c + 32 else c).toByte()
        }
        return RgInfo(
            valueAfter(low, KEY_TG) ?: r128(valueAfter(low, KEY_R128_T)), valueAfter(low, KEY_TP),
            valueAfter(low, KEY_AG) ?: r128(valueAfter(low, KEY_R128_A)), valueAfter(low, KEY_AP),
        )
    }

    private val NUMBER = Regex("[-+]?\\d+(?:\\.\\d+)?")

    private fun valueAfter(low: ByteArray, key: String): Float? {
        val k = key.toByteArray(Charsets.ISO_8859_1)
        var i = indexOf(low, k, 0)
        while (i >= 0) {
            val from = i + k.size
            val to = min(low.size, from + 48)
            // 数値の前にあるのは区切り(= や 0 のバイト)と、M4A の data 項目の頭(大きさ・種類)だけ
            val s = String(low, from, to - from, Charsets.ISO_8859_1)
            val m = NUMBER.find(s)
            // 名前のすぐ後ろが英字なら、別の項目(名前が長いもの)なので、次を探す
            val next = if (from < low.size) low[from].toInt() and 0xFF else ' '.code
            if (m != null && next !in 'a'.code..'z'.code && next != '_'.code) return m.value.toFloatOrNull()
            i = indexOf(low, k, i + 1)
        }
        return null
    }

    private fun indexOf(h: ByteArray, n: ByteArray, from: Int): Int {
        if (n.isEmpty()) return -1
        var i = from
        val last = h.size - n.size
        outer@ while (i <= last) {
            for (j in n.indices) if (h[i + j] != n[j]) { i++; continue@outer }
            return i
        }
        return -1
    }

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

    // ---- FLAC: メタデータの Vorbis コメントの区画を探して読む(画像の区画が前にあって大きくても読めるように) ----

    private fun flacComments(src: RandomInput, start: Long = 0L): RgInfo? {
        val head = readAt(src, start, 4) ?: return null
        if (String(head, Charsets.ISO_8859_1) != "fLaC") return null
        var pos = start + 4
        while (pos + 4 <= src.size) {
            val h = readAt(src, pos, 4) ?: return null
            val type = h[0].toInt() and 0x7F
            val last = (h[0].toInt() and 0x80) != 0
            val len = ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
            if (type == 4) {
                val body = readAt(src, pos + 4, len.toInt()) ?: return null
                return vorbis(body)
            }
            if (last) return null
            pos += 4 + len
        }
        return null
    }

    internal fun vorbis(b: ByteArray): RgInfo {
        val map = HashMap<String, String>()
        var p = 0
        fun u32(): Long = le32(b, p).also { p += 4 }
        if (b.size < 8) return RgInfo()
        val vendor = u32()
        p += vendor.toInt()
        if (p + 4 > b.size) return RgInfo()
        val count = u32()
        for (i in 0 until count) {
            if (p + 4 > b.size) break
            val n = u32().toInt()
            if (n < 0 || p + n > b.size) break
            val s = String(b, p, n, Charsets.UTF_8)
            p += n
            val eq = s.indexOf('=')
            if (eq > 0) {
                // putIfAbsent は Android 7 から(6 では使えない)
                val k = s.substring(0, eq).lowercase()
                if (!map.containsKey(k)) map[k] = s.substring(eq + 1)
            }
        }
        // 診断用: どんなタグが入っていたか(ReplayGain の値が無いときに、ログで分かるように)
        lastNote = "flac, vorbis comments: " + map.keys.take(16).joinToString(", ").ifEmpty { "(none)" }
        fun num(k: String) = map[k]?.let { NUMBER.find(it)?.value?.toFloatOrNull() }
        return RgInfo(num(KEY_TG) ?: r128(num(KEY_R128_T)), num(KEY_TP), num(KEY_AG) ?: r128(num(KEY_R128_A)), num(KEY_AP))
    }

    // ---- MP3: 初めの ID3v2 タグ全体(大きな画像が入っていても読めるように) ----

    private fun id3v2AtStart(src: RandomInput): ByteArray? {
        val h = readAt(src, 0, 10) ?: return null
        if (String(h, 0, 3, Charsets.ISO_8859_1) != "ID3") return null
        val size = ((h[6].toLong() and 0x7F) shl 21) or ((h[7].toLong() and 0x7F) shl 14) or
            ((h[8].toLong() and 0x7F) shl 7) or (h[9].toLong() and 0x7F)
        return readAt(src, 0, min(10 + size, min(src.size, MAX_TAG)).toInt())
    }

    // ---- M4A: moov の中の udta(曲情報の入れ物)を読む ----

    private fun mp4Udta(src: RandomInput): ByteArray? {
        val moov = findBox(src, 0, src.size, "moov") ?: return null
        val udta = findBox(src, moov.first, moov.second, "udta") ?: return null
        val len = udta.second - udta.first
        if (len <= 0 || len > MAX_TAG) return null
        return readAt(src, udta.first, len.toInt())
    }

    /** [start, end) の中の箱を順に見て、name の箱の中身の範囲を返す */
    private fun findBox(src: RandomInput, start: Long, end: Long, name: String): Pair<Long, Long>? {
        var pos = start
        while (pos + 8 <= end) {
            val h = readAt(src, pos, 16.coerceAtMost((end - pos).toInt())) ?: return null
            var size = be32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var head = 8
            if (size == 1L && h.size >= 16) {
                size = (be32(h, 8) shl 32) or be32(h, 12)
                head = 16
            } else if (size == 0L) size = end - pos
            if (size < head) return null
            if (type == name) return (pos + head) to minOf(end, pos + size)
            pos += size
        }
        return null
    }

    // ---- DSF: ファイルの終わりにある ID3v2(場所は先頭に書いてある) ----

    private fun dsfMetadata(src: RandomInput): ByteArray? {
        val h = readAt(src, 0, 28) ?: return null
        if (String(h, 0, 4, Charsets.ISO_8859_1) != "DSD ") return null
        val ptr = le32(h, 20) or (le32(h, 24) shl 32)
        if (ptr <= 0 || ptr >= src.size) return null
        return readAt(src, ptr, min(src.size - ptr, MAX_TAG).toInt())
    }
}
