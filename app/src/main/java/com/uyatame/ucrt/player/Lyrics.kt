package com.uyatame.ucrt.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.uyatame.ucrt.tags.Id3Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 歌詞 1 行(timeMs は時刻付きの歌詞のときだけ意味がある) */
class LyricLine(val timeMs: Long, val text: String)

/** 歌詞。synced = 時刻付き(LRC) */
class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

/**
 * 歌詞を探す。
 * 1) 曲と同じフォルダにある同じ名前の .lrc / .txt
 * 2) 曲に埋め込まれた歌詞(FLAC の LYRICS、MP3 / DSF の USLT)
 */
object LyricsLoader {
    fun load(ctx: Context, item: PlayItem): Lyrics? {
        val uri = item.uri ?: return null
        runCatching { sidecar(ctx, uri, item.folderId) }.getOrNull()?.let { return it }
        return runCatching { embedded(ctx, uri) }.getOrNull()
    }

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun sidecar(ctx: Context, uri: Uri, folderId: String?): Lyrics? {
        if (folderId.isNullOrEmpty()) return null
        val name = displayName(ctx, uri) ?: return null
        val base = name.substringBeforeLast('.').lowercase()
        val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        var lrc: String? = null
        var txt: String? = null
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1)?.lowercase() ?: continue
                if (n == "$base.lrc") lrc = c.getString(0)
                else if (n == "$base.txt") txt = c.getString(0)
            }
        }
        val id = lrc ?: txt ?: return null
        val bytes = ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { it.readBytes() }
            ?: return null
        return parse(decode(bytes))
    }

    /** UTF-8 で読めなければ Shift_JIS として読む */
    private fun decode(b: ByteArray): String {
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

    private fun embedded(ctx: Context, uri: Uri): Lyrics? {
        val text = when (ext(uri)) {
            "flac" -> flacLyrics(ctx, uri)
            "mp3" -> mp3Lyrics(ctx, uri)
            "dsf", "dff" -> com.uyatame.ucrt.player.dsd.DsdTags.read(ctx, uri)?.tags?.lyrics
            else -> null
        } ?: return null
        return parse(text)
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
