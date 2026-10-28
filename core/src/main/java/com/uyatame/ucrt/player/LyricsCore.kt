package com.uyatame.ucrt.player

import android.content.Context
import android.net.Uri
import com.uyatame.ucrt.tags.Id3Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 歌詞 1 行(timeMs は時刻付きの歌詞のときだけ意味がある) */
class LyricLine(val timeMs: Long, val text: String)

/** 歌詞。synced = 時刻付き(LRC) */
class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

/** 歌詞の読み方(LRC・文章の読み取りと、曲に埋め込まれた歌詞)。app と legacy で共通 */
object LyricsCore {
    /** 曲に埋め込まれた歌詞だけ(歌詞を LRCLIB に登録するときに使う)。無い・読めなければ null */
    fun embeddedOnly(ctx: Context, uri: String): Lyrics? {
        if (com.uyatame.ucrt.library.Cue.isCue(uri)) return null
        return runCatching { embedded(ctx, Uri.parse(uri)) }.getOrNull()
    }

    /** UTF-8 で読めなければ Shift_JIS として読む */
    fun decode(b: ByteArray): String {
        var off = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) off = 3
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        runCatching { return utf8.decode(ByteBuffer.wrap(b, off, b.size - off)).toString() }
        val sjis = runCatching { Charset.forName("windows-31j") }.getOrNull() ?: Charsets.UTF_8
        return String(b, off, b.size - off, sjis)
    }

    private fun ext(uri: Uri) = Uri.decode(uri.toString()).substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun embedded(ctx: Context, uri: Uri): Lyrics? {
        val text = when (ext(uri)) {
            "flac" -> flacLyrics(ctx, uri)
            "mp3" -> mp3Lyrics(ctx, uri)
            "dsf", "dff" -> com.uyatame.ucrt.player.dsd.DsdTags.read(ctx, uri)?.tags?.lyrics
            // M4A / ALAC / MP4(©lyr)
            "m4a", "mp4", "m4b", "alac" -> BuiltinCodecs.openInput(ctx, uri)?.use { com.uyatame.ucrt.tags.TagExtra.readFrom(it, "m4a")?.lyrics }
            // Ogg Vorbis / Opus(LYRICS・UNSYNCEDLYRICS)
            "ogg", "oga", "opus" -> BuiltinCodecs.openInput(ctx, uri)?.use { oggLyrics(it) }
            // WAV(id3 のチャンクの USLT / SYLT)
            "wav", "wave" -> BuiltinCodecs.openInput(ctx, uri)?.use { wavLyrics(it) }
            "aif", "aiff", "aifc", "wv", "ape" -> {
                val kind = BuiltinCodecs.kindOf(Uri.decode(uri.toString()))
                if (kind == null) null else BuiltinCodecs.openInput(ctx, uri)?.use { com.uyatame.ucrt.tags.ExtraTags.read(kind, it)?.lyrics }
            }
            else -> null
        } ?: return null
        return parse(text)
    }

    private fun at(src: RandomInput, pos: Long, n: Int): ByteArray? {
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

    /** Ogg の 2 つ目のパケット(コメント)を組み立てて、LYRICS を探す */
    internal fun oggLyrics(src: RandomInput): String? {
        var pos = 0L
        var packet = 0
        val cur = java.io.ByteArrayOutputStream()
        while (pos + 27 <= src.size && packet < 2) {
            val h = at(src, pos, 27) ?: return null
            if (h[0] != 'O'.code.toByte() || h[1] != 'g'.code.toByte()) return null
            val nseg = h[26].toInt() and 0xFF
            val lace = at(src, pos + 27, nseg) ?: return null
            var p = pos + 27 + nseg
            for (i in 0 until nseg) {
                val l = lace[i].toInt() and 0xFF
                if (packet == 1) {
                    if (cur.size() + l > (16 shl 20)) return null
                    cur.write(at(src, p, l) ?: return null)
                }
                p += l
                if (l < 255) {
                    packet++
                    if (packet == 2) break
                }
            }
            // 次のページの先頭へ
            pos = p
        }
        val b = cur.toByteArray()
        // "\u0003vorbis" か "OpusTags" のあと: ベンダー名、件数、"KEY=値" の並び(すべて長さ付き、リトルエンディアン)
        var o = when {
            b.size > 7 && b[0].toInt() == 3 && String(b, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> 7
            b.size > 8 && String(b, 0, 8, Charsets.ISO_8859_1) == "OpusTags" -> 8
            else -> return null
        }
        fun le(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
        if (o + 4 > b.size) return null
        o += 4 + le(o)
        if (o + 4 > b.size) return null
        val n = le(o); o += 4
        for (i in 0 until n) {
            if (o + 4 > b.size) break
            val len = le(o); o += 4
            if (len < 0 || o + len > b.size) break
            val kv = String(b, o, len, Charsets.UTF_8)
            o += len
            val key = kv.substringBefore('=').uppercase()
            if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "UNSYNCED LYRICS") return kv.substringAfter('=')
        }
        return null
    }

    /** WAV の id3 チャンクから歌詞を読む */
    private fun wavLyrics(src: RandomInput): String? {
        var p = 12L
        while (p + 8 <= src.size) {
            val h = at(src, p, 8) ?: return null
            val id = String(h, 0, 4, Charsets.ISO_8859_1)
            val len = ((h[4].toLong() and 0xFF) or ((h[5].toLong() and 0xFF) shl 8) or ((h[6].toLong() and 0xFF) shl 16) or ((h[7].toLong() and 0xFF) shl 24))
            if (id.equals("id3 ", true) && len in 10..(32L shl 20)) {
                return at(src, p + 8, len.toInt())?.let { Id3Reader.parse(it)?.lyrics }
            }
            p += 8 + len + (len and 1)
        }
        return null
    }

    private fun readFully(ins: java.io.InputStream, b: ByteArray): Int {
        var off = 0
        while (off < b.size) {
            val r = ins.read(b, off, b.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    /** FLAC の VORBIS_COMMENT から LYRICS / UNSYNCEDLYRICS を読む */
    private fun flacLyrics(ctx: Context, uri: Uri): String? {
        val raw = ctx.contentResolver.openInputStream(uri) ?: return null
        raw.use { r0 ->
            val ins = java.io.BufferedInputStream(r0, 1 shl 16)
            val head = ByteArray(4)
            if (readFully(ins, head) != 4 || String(head, Charsets.ISO_8859_1) != "fLaC") return null
            while (true) {
                val bh = ByteArray(4)
                if (readFully(ins, bh) != 4) return null
                val last = (bh[0].toInt() and 0x80) != 0
                val type = bh[0].toInt() and 0x7F
                val len = ((bh[1].toInt() and 0xFF) shl 16) or ((bh[2].toInt() and 0xFF) shl 8) or (bh[3].toInt() and 0xFF)
                if (type == 4) {
                    val b = ByteArray(len)
                    if (readFully(ins, b) != len) return null
                    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                    val vlen = bb.int
                    if (vlen < 0 || vlen > bb.remaining()) return null
                    bb.position(bb.position() + vlen)
                    val n = bb.int
                    var found: String? = null
                    for (k in 0 until n) {
                        if (bb.remaining() < 4) break
                        val l = bb.int
                        if (l < 0 || l > bb.remaining()) break
                        val arr = ByteArray(l)
                        bb.get(arr)
                        val c = String(arr, Charsets.UTF_8)
                        val eq = c.indexOf('=')
                        if (eq <= 0) continue
                        val key = c.substring(0, eq).uppercase()
                        if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "UNSYNCED LYRICS") {
                            val v = c.substring(eq + 1)
                            if (v.isNotBlank()) {
                                // 時刻付きの方を優先する
                                if (found == null || (!hasTime(found) && hasTime(v))) found = v
                            }
                        }
                    }
                    return found
                } else {
                    var left = len.toLong()
                    while (left > 0) {
                        val sk = ins.skip(left)
                        if (sk <= 0) return null
                        left -= sk
                    }
                }
                if (last) return null
            }
        }
        return null
    }

    /** MP3 の先頭の ID3v2 から USLT を読む */
    private fun mp3Lyrics(ctx: Context, uri: Uri): String? {
        val raw = ctx.contentResolver.openInputStream(uri) ?: return null
        raw.use { ins ->
            val h = ByteArray(10)
            if (readFully(ins, h) != 10 || h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return null
            val size = ((h[6].toInt() and 0x7F) shl 21) or ((h[7].toInt() and 0x7F) shl 14) or
                ((h[8].toInt() and 0x7F) shl 7) or (h[9].toInt() and 0x7F)
            if (size <= 0 || size > (32 shl 20)) return null
            val body = ByteArray(size)
            val n = readFully(ins, body)
            val tag = ByteArray(10 + n)
            System.arraycopy(h, 0, tag, 0, 10)
            System.arraycopy(body, 0, tag, 10, n)
            return Id3Reader.parse(tag)?.lyrics
        }
    }

    private val TIME = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val WORD_TIME = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val META = Regex("^\\[[A-Za-z]+:.*]$")
    private val OFFSET = Regex("^\\[offset:\\s*([+-]?\\d+)\\s*]$", RegexOption.IGNORE_CASE)

    private fun hasTime(s: String) = TIME.containsMatchIn(s)

    /** LRC(時刻付き)または普通の文章として読む */
    fun parse(text: String): Lyrics? {
        val rows = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var offset = 0L
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<String>()
        for (row0 in rows) {
            val row = row0.trim()
            val off = OFFSET.find(row)
            if (off != null) {
                offset = off.groupValues[1].toLongOrNull() ?: 0L
                continue
            }
            var p = 0
            val times = ArrayList<Long>()
            while (true) {
                val m = TIME.find(row, p) ?: break
                if (m.range.first != p) break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val f = m.groupValues[3]
                val ms = when (f.length) { 0 -> 0L; 1 -> f.toLong() * 100; 2 -> f.toLong() * 10; else -> f.take(3).toLong() }
                times.add(min * 60_000 + sec * 1000 + ms)
                p = m.range.last + 1
            }
            if (times.isNotEmpty()) {
                val t = row.substring(p).replace(WORD_TIME, "").trim()
                times.forEach { timed.add(LyricLine(it, t)) }
            } else if (!META.matches(row)) {
                plain.add(row0.trimEnd())
            }
        }
        if (timed.isNotEmpty()) {
            // LRC の offset は「正の値で歌詞を早く出す」
            val list = timed.map { LyricLine((it.timeMs - offset).coerceAtLeast(0L), it.text) }.sortedBy { it.timeMs }
            return Lyrics(list, true)
        }
        val lines = plain.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (lines.isEmpty()) return null
        return Lyrics(lines.map { LyricLine(0L, it) }, false)
    }
}
