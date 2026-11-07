package com.uyatame.ucrt.tags

import com.uyatame.ucrt.player.RandomInput
import com.uyatame.ucrt.rip.FlacSink
import com.uyatame.ucrt.rip.Mp4Tagger
import com.uyatame.ucrt.rip.Mp4Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** 詳しい情報(ジャンル・作曲・作詞・コメント・歌詞)の書き換えと読み取り。書き換えない項目は残す */
class TagExtraTest {
    private class FileIn(f: File) : RandomInput {
        private val b = f.readBytes()
        override val size: Long get() = b.size.toLong()
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int {
            if (pos >= this.b.size) return -1
            val n = minOf(len, this.b.size - pos.toInt())
            System.arraycopy(this.b, pos.toInt(), b, off, n)
            return n
        }
    }

    private fun values(extra: TagExtra) = TagValues("曲", "歌手", "アルバム", "歌手", "2024", 1, 2, 1, 1, extra)

    @Test fun flacRewriteKeepsOthers() {
        val inF = File.createTempFile("ucrt-in", ".flac")
        val outF = File.createTempFile("ucrt-out", ".flac")
        val o = ByteArrayOutputStream()
        val sink = FlacSink(o, 4096, 5, listOf("TITLE" to "old", "GENRE" to "Rock", "COMPOSER" to "作曲者", "REPLAYGAIN_TRACK_GAIN" to "-3 dB"))
        sink.write(ByteArray(4096 * 4), 0, 4096 * 4)
        sink.finish()
        inF.writeBytes(o.toByteArray())
        // ジャンルと歌詞だけ書き換える(作曲は null なので残る)
        FlacTags.rewrite(inF, outF, values(TagExtra(genre = "Pop", lyrics = "[00:01.00] 一行目\n[00:02.00] 二行目")), null)
        val e = TagExtra.readFrom(FileIn(outF), "flac")!!
        assertEquals("Pop", e.genre)
        assertEquals("作曲者", e.composer)
        assertEquals("[00:01.00] 一行目\n[00:02.00] 二行目", e.lyrics)
        assertEquals("", e.lyricist)
        assertTrue(String(outF.readBytes(), Charsets.UTF_8).contains("REPLAYGAIN_TRACK_GAIN=-3 dB"))
        // 空にすると消える
        FlacTags.rewrite(outF, inF, values(TagExtra(genre = "")), null)
        assertEquals("", TagExtra.readFrom(FileIn(inF), "flac")!!.genre)
        inF.delete(); outF.delete()
    }

    @Test fun mp3RewriteKeepsItunesComments() {
        val inF = File.createTempFile("ucrt-in", ".mp3")
        val outF = File.createTempFile("ucrt-out", ".mp3")
        // 元のタグ: ジャンル・説明付きのコメント(iTunNORM)
        fun frame(id: String, body: ByteArray) = id.toByteArray() + byteArrayOf(0, 0, 0, body.size.toByte(), 0, 0) + body
        val itun = byteArrayOf(0) + "eng".toByteArray() + "iTunNORM".toByteArray() + byteArrayOf(0) + "0000".toByteArray()
        val frames = frame("TCON", byteArrayOf(0) + "Rock".toByteArray()) + frame("COMM", itun)
        val n = frames.size
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, (n shr 7).toByte(), (n and 0x7F).toByte()) + frames
        inF.writeBytes(tag + ByteArray(2000) { 0xFF.toByte() })
        Mp3Tags.rewrite(inF, outF, values(TagExtra(lyricist = "作詞者", comment = "メモ", lyrics = "歌詞")), null)
        val e = TagExtra.readFrom(FileIn(outF), "mp3")!!
        assertEquals("Rock", e.genre)
        assertEquals("作詞者", e.lyricist)
        assertEquals("メモ", e.comment)
        assertEquals("歌詞", e.lyrics)
        inF.delete(); outF.delete()
    }

    @Test fun mp4WritesAndReads() {
        fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        fun box(t: String, vararg p: ByteArray): ByteArray { val b = p.fold(ByteArray(0)) { a, x -> a + x }; return be32(8 + b.size) + t.toByteArray() + b }
        val f = File.createTempFile("ucrt", ".m4a")
        f.writeBytes(box("ftyp", "M4A ".toByteArray()) + box("moov", box("mvhd", ByteArray(100))) + box("mdat", ByteArray(64)))
        val t = Mp4Tags("曲", "歌手", "アルバム", "歌手", "2024", 1, 1, 1, 1)
        Mp4Tagger.tag(f, t, null, TagExtra(genre = "J-Pop", composer = "作曲者", lyricist = "作詞者", comment = "メモ", lyrics = "歌詞"))
        var e = TagExtra.readFrom(FileIn(f), "m4a")!!
        assertEquals("J-Pop", e.genre)
        assertEquals("作曲者", e.composer)
        assertEquals("作詞者", e.lyricist)
        assertEquals("メモ", e.comment)
        assertEquals("歌詞", e.lyrics)
        // 作詞だけ書き換え、ほかは残す
        Mp4Tagger.tag(f, t, null, TagExtra(lyricist = "別の人"))
        e = TagExtra.readFrom(FileIn(f), "m4a")!!
        assertEquals("別の人", e.lyricist)
        assertEquals("J-Pop", e.genre)
        assertEquals("歌詞", e.lyrics)
        f.delete()
    }

    private fun mp4Box(t: String, vararg p: ByteArray): ByteArray {
        fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        val b = p.fold(ByteArray(0)) { a, x -> a + x }
        return be32(8 + b.size) + t.toByteArray() + b
    }

    @Test fun mp4TagLeavesNoTempFileAndKeepsAudio() {
        val audio = ByteArray(64) { it.toByte() }
        val f = File.createTempFile("ucrt", ".m4a")
        f.writeBytes(mp4Box("ftyp", "M4A ".toByteArray()) + mp4Box("moov", mp4Box("mvhd", ByteArray(100))) + mp4Box("mdat", audio))
        Mp4Tagger.tag(f, Mp4Tags("曲", "歌手", "アルバム", "歌手", "2024", 1, 1, 1, 1), null)
        // 一時ファイルは残らず、音声のデータはそのまま入っている
        assertEquals(false, File(f.parentFile, f.name + ".tmp").exists())
        assertEquals(false, File(f.parentFile, f.name + ".bak").exists())
        val bytes = f.readBytes()
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("mdat")
        assertEquals(audio.toList(), bytes.copyOfRange(at + 4, at + 4 + 64).toList())
        f.delete()
    }

    @Test fun mp4WithoutMoovFailsAndKeepsTheFile() {
        val original = mp4Box("ftyp", "M4A ".toByteArray()) + mp4Box("mdat", ByteArray(64))
        val f = File.createTempFile("ucrt", ".m4a")
        f.writeBytes(original)
        val failed = runCatching { Mp4Tagger.tag(f, Mp4Tags("曲", "歌手", "アルバム", "歌手", "2024", 1, 1, 1, 1), null) }.isFailure
        assertEquals(true, failed)
        // 失敗しても、元のファイルはそのまま(途中で消えたり、空になったりしない)
        assertEquals(original.toList(), f.readBytes().toList())
        f.delete()
    }

    @Test fun mp4CorruptChunkOffsetCountDoesNotCrash() {
        fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        // stco が、実際より多いエントリ数を名乗っている(壊れた moov)。範囲外を読み書きせずに最後まで処理できる
        val stco = mp4Box("stco", be32(0), be32(1_000_000), be32(100))
        val f = File.createTempFile("ucrt", ".m4a")
        f.writeBytes(mp4Box("ftyp", "M4A ".toByteArray()) + mp4Box("moov", mp4Box("trak", mp4Box("mdia", mp4Box("minf", mp4Box("stbl", stco))))) + mp4Box("mdat", ByteArray(64)))
        Mp4Tagger.tag(f, Mp4Tags("曲", "歌手", "アルバム", "歌手", "2024", 1, 1, 1, 1), null)
        assertEquals(true, f.length() > 0)
        f.delete()
    }
}
