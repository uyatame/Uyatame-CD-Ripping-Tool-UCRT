package com.uyatame.ucrt.tags

import android.content.Context
import android.net.Uri
import com.uyatame.ucrt.meta.Cover
import com.uyatame.ucrt.player.dsd.RandomFile
import com.uyatame.ucrt.rip.Id3Tags
import com.uyatame.ucrt.rip.Id3v2
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * WAV の曲情報。RIFF の LIST/INFO チャンク(多くのソフトが読む)と、
 * "id3 " チャンク(ID3v2。日本語・ジャケット画像も入る)の両方を読み書きする。
 */
object WavTags {
    class Info(
        val rate: Int,
        val channels: Int,
        val bits: Int,
        val durationMs: Long,
        /** id3 を優先し、無い項目は INFO で補ったもの */
        val tags: Id3Data?,
    )

    private class Chunk(val id: String, val offset: Long, val size: Long)

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** RIFF のチャンクの一覧(WAV でなければ null) */
    private fun chunks(f: RandomFile): List<Chunk>? {
        val total = f.size
        val h = f.read(0, 12)
        if (h.size < 12 || String(h, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(h, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
        val out = ArrayList<Chunk>()
        var p = 12L
        while (p + 8 <= total && out.size < MAX_CHUNKS) {
            val c = f.read(p, 8)
            if (c.size < 8) break
            val id = String(c, 0, 4, Charsets.ISO_8859_1)
            var size = le32(c, 4)
            // 大きなファイルで data の長さが正しくないものがあるので、ファイルの終わりまでに収める
            if (p + 8 + size > total) size = total - p - 8
            out.add(Chunk(id, p + 8, size))
            p += 8 + size + (size and 1L)
        }
        return out
    }

    private const val MAX_CHUNKS = 4096

    /** WAV の形式と曲情報を読む。読めなければ null */
    fun read(ctx: Context, uri: Uri): Info? {
        val f = RandomFile.open(ctx, uri) ?: return null
        return f.use { readFrom(it) }
    }

    fun picture(ctx: Context, uri: Uri): ByteArray? = read(ctx, uri)?.tags?.picture

    private fun readFrom(f: RandomFile): Info? {
        val cs = chunks(f) ?: return null
        var rate = 0; var ch = 0; var bits = 0
        cs.firstOrNull { it.id == "fmt " && it.size >= 16 }?.let { c ->
            val b = f.read(c.offset, 16)
            if (b.size >= 16) {
                ch = le16(b, 2); rate = le32(b, 4).toInt(); bits = le16(b, 14)
            }
        }
        val data = cs.firstOrNull { it.id == "data" }
        val frameBytes = ch * ((bits + 7) / 8)
        val dur = if (data != null && rate > 0 && frameBytes > 0) data.size / frameBytes * 1000L / rate else 0L
        val id3 = cs.firstOrNull { (it.id == "id3 " || it.id == "ID3 ") && it.size in 10..(32L shl 20) }
            ?.let { runCatching { Id3Reader.parse(f.read(it.offset, it.size.toInt())) }.getOrNull() }
        val info = cs.firstOrNull { it.id == "LIST" && it.size in 4..(1L shl 20) }
            ?.let { c -> f.read(c.offset, c.size.toInt()) }
            ?.takeIf { it.size >= 4 && String(it, 0, 4, Charsets.ISO_8859_1) == "INFO" }
            ?.let { parseInfo(it) }
            .orEmpty()
        val tags = if (id3 == null && info.isEmpty()) null else {
            val trk = info["ITRK"] ?: info["IPRT"]
            Id3Data(
                title = id3?.title ?: info["INAM"],
                artist = id3?.artist ?: info["IART"],
                album = id3?.album ?: info["IPRD"],
                albumArtist = id3?.albumArtist,
                year = id3?.year ?: info["ICRD"]?.take(4),
                track = id3?.track ?: trk?.substringBefore('/')?.trim()?.toIntOrNull(),
                trackTotal = id3?.trackTotal,
                disc = id3?.disc,
                discTotal = id3?.discTotal,
                picture = id3?.picture,
                rawApic = id3?.rawApic.orEmpty(),
                genre = id3?.genre ?: info["IGNR"],
                lyrics = id3?.lyrics,
            )
        }
        return Info(rate, ch, bits, dur, tags)
    }

    private val sjis: Charset? = runCatching { Charset.forName("windows-31j") }.getOrNull()

    /** INFO の文字列(UTF-8 として読めなければ Shift_JIS、それも無理なら Latin-1) */
    private fun decode(b: ByteArray, off: Int, len: Int): String {
        var n = len
        while (n > 0 && b[off + n - 1].toInt() == 0) n--
        fun strict(cs: Charset): String? = runCatching {
            cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, off, n)).toString()
        }.getOrNull()
        return (strict(Charsets.UTF_8) ?: sjis?.let { strict(it) } ?: String(b, off, n, Charsets.ISO_8859_1)).trim()
    }

    private fun parseInfo(b: ByteArray): Map<String, String> {
        val m = HashMap<String, String>()
        var p = 4
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            val len = le32(b, p + 4).toInt()
            if (len < 0 || p + 8L + len > b.size) break
            val s = decode(b, p + 8, len)
            if (s.isNotEmpty() && id !in m) m[id] = s
            p += 8 + len + (len and 1)
        }
        return m
    }

    // ---------------- 書き込み ----------------

    private fun le32b(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun chunk(id: String, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(body.size + 9)
        o.write(id.toByteArray(Charsets.ISO_8859_1))
        o.write(le32b(body.size.toLong()))
        o.write(body)
        if (body.size % 2 == 1) o.write(0)
        return o.toByteArray()
    }

    /** LIST/INFO の中で、書き換えるもの(古いものは残さない) */
    private val INFO_EDITED = setOf("INAM", "IART", "IPRD", "ICRD", "ITRK", "IPRT", "ISFT")

    /** 元の LIST/INFO の項目のうち、書き換えないもの(IGNR・ICMT など)を、そのままのバイト列で */
    private fun otherInfo(b: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var p = 4
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            val len = le32(b, p + 4).toInt()
            if (len < 0 || p + 8L + len > b.size) break
            if (id !in INFO_EDITED) out.add(id to b.copyOfRange(p + 8, p + 8 + len))
            p += 8 + len + (len and 1)
        }
        return out
    }

    /** LIST/INFO チャンク。extra は元のファイルから残す項目 */
    private fun infoChunk(t: Id3Tags, extra: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("INFO".toByteArray(Charsets.ISO_8859_1))
        fun item(id: String, v: String) {
            if (v.isBlank()) return
            o.write(chunk(id, v.trim().toByteArray(Charsets.UTF_8) + byteArrayOf(0)))
        }
        item("INAM", t.title)
        item("IART", t.artist)
        item("IPRD", t.album)
        item("ICRD", t.year.take(4))
        if (t.track > 0) item("ITRK", t.track.toString())
        item("ISFT", "Uyatame CD Ripping Tool")
        for ((id, v) in extra) o.write(chunk(id, v))
        return chunk("LIST", o.toByteArray())
    }

    /**
     * 曲情報のチャンク(LIST/INFO と id3)。WAV の fmt と data の間に入れる。
     * rawApic は書き換えのときに残す画像
     */
    fun metaChunks(t: Id3Tags, cover: Cover?, rawApic: List<ByteArray> = emptyList()): ByteArray =
        infoChunk(t) + chunk("id3 ", Id3v2.build(t, cover, rawApic))

    /**
     * 曲情報を書き換えた WAV を out に書く(音声データやほかのチャンクはそのまま)。
     * cover が null なら、元の画像を残す
     */
    fun rewrite(ctx: Context, uri: Uri, out: OutputStream, t: Id3Tags, cover: Cover?) {
        val f = RandomFile.open(ctx, uri) ?: throw IOException("cannot open")
        f.use { rf ->
            val cs = chunks(rf) ?: throw IOException("not a WAV file")
            // チャンクが多すぎて最後までたどれなかったら、書き換えない(残りを消してしまわないように)
            val last = cs.lastOrNull()
            if (last == null || last.offset + last.size + (last.size and 1L) + 8 <= rf.size) throw IOException("not a WAV file")
            // 元の id3 と INFO のうち、書き換えない項目(ジャンル・歌詞・コメントなど)は残す
            val oldId3 = cs.firstOrNull { (it.id == "id3 " || it.id == "ID3 ") && it.size in 10..(32L shl 20) }
                ?.let { rf.read(it.offset, it.size.toInt()) }
            val kept = Id3Keep.from(oldId3, cover == null)
            val oldInfo = cs.firstOrNull { it.id == "LIST" && it.size in 4..(1L shl 20) }
                ?.let { c -> rf.read(c.offset, c.size.toInt()) }
                ?.takeIf { it.size >= 4 && String(it, 0, 4, Charsets.ISO_8859_1) == "INFO" }
                ?.let { otherInfo(it) }
                .orEmpty()
            val meta = infoChunk(t, oldInfo) + chunk("id3 ", Id3Keep.merge(Id3v2.build(t, cover, kept.rawApic), kept.frames))
            // 曲情報のチャンクは入れ替え、ほかはそのまま残す
            val keep = cs.filter { c ->
                c.id != "id3 " && c.id != "ID3 " &&
                    !(c.id == "LIST" && rf.read(c.offset, 4).let { it.size == 4 && String(it, Charsets.ISO_8859_1) == "INFO" })
            }
            val fmtIdx = keep.indexOfFirst { it.id == "fmt " }
            if (fmtIdx < 0 || keep.none { it.id == "data" }) throw IOException("not a WAV file")
            var riff = 4L + meta.size
            keep.forEach { riff += 8 + it.size + (it.size and 1L) }
            out.write("RIFF".toByteArray(Charsets.ISO_8859_1))
            out.write(le32b(riff.coerceAtMost(0xFFFFFFFFL)))
            out.write("WAVE".toByteArray(Charsets.ISO_8859_1))
            val buf = ByteArray(1 shl 16)
            keep.forEachIndexed { i, c ->
                out.write(c.id.toByteArray(Charsets.ISO_8859_1))
                out.write(le32b(c.size))
                var p = c.offset
                var left = c.size
                while (left > 0) {
                    val n = minOf(left, buf.size.toLong()).toInt()
                    val b = rf.read(p, n)
                    if (b.isEmpty()) throw IOException("short read")
                    out.write(b)
                    p += b.size
                    left -= b.size
                }
                if (c.size % 2 == 1L) out.write(0)
                if (i == fmtIdx) out.write(meta)
            }
        }
    }
}
