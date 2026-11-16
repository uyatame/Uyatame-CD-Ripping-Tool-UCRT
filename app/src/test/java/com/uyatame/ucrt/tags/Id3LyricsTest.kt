package com.uyatame.ucrt.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * ID3v2 の歌詞の読み取り: MusicBee などの Windows のソフトが書く形(2.3・UTF-16・説明が空の USLT、TXXX の LYRICS、
 * 大きさ 0 のフレーム、BOM が最初の文字列にしか無い SYLT)を、組み立てて読む
 */
class Id3LyricsTest {
    private fun utf16(s: String, bom: Boolean = true): ByteArray {
        val o = ByteArrayOutputStream()
        if (bom) { o.write(0xFF); o.write(0xFE) }
        o.write(s.toByteArray(Charsets.UTF_16LE))
        return o.toByteArray()
    }

    private fun frame(id: String, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(id.toByteArray(Charsets.ISO_8859_1))
        val n = body.size
        // 2.3 は普通の 32bit
        o.write(n ushr 24); o.write(n ushr 16 and 0xFF); o.write(n ushr 8 and 0xFF); o.write(n and 0xFF)
        o.write(0); o.write(0)
        o.write(body)
        return o.toByteArray()
    }

    private fun tag(vararg frames: ByteArray, padding: Int = 64): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { body.write(it) }
        body.write(ByteArray(padding))
        val n = body.size()
        val o = ByteArrayOutputStream()
        o.write("ID3".toByteArray()); o.write(3); o.write(0); o.write(0)
        o.write(n ushr 21 and 0x7F); o.write(n ushr 14 and 0x7F); o.write(n ushr 7 and 0x7F); o.write(n and 0x7F)
        o.write(body.toByteArray())
        return o.toByteArray()
    }

    /** USLT: 文字コード 1、言語、説明(BOM + 終端)、歌詞(BOM 付き) */
    private fun uslt(text: String, lang: String = "eng", desc: String = ""): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(1)
        o.write(lang.toByteArray(Charsets.ISO_8859_1))
        o.write(utf16(desc)); o.write(0); o.write(0)
        o.write(utf16(text))
        return o.toByteArray()
    }

    private fun txxx(desc: String, value: String): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(1)
        o.write(utf16(desc)); o.write(0); o.write(0)
        o.write(utf16(value))
        return o.toByteArray()
    }

    private val lrc = "[00:01.00]一行目\r\n[00:05.50]二行目\r\n"

    @Test fun readsUtf16UsltWithEmptyDescription() {
        val t = tag(frame("TIT2", byteArrayOf(0) + "Song".toByteArray()), frame("USLT", uslt(lrc)))
        assertEquals(lrc.trim(), Id3Reader.parse(t)!!.lyrics)
    }

    @Test fun readsLyricsFromTxxx() {
        val t = tag(frame("TXXX", txxx("LYRICS", lrc)))
        assertEquals(lrc.trim(), Id3Reader.parse(t)!!.lyrics)
        val u = tag(frame("TXXX", txxx("UNSYNCEDLYRICS", "歌詞")))
        assertEquals("歌詞", Id3Reader.parse(u)!!.lyrics)
        // 歌詞ではない TXXX は読まない
        assertNull(Id3Reader.parse(tag(frame("TXXX", txxx("MOOD", "happy"))))!!.lyrics)
    }

    @Test fun zeroSizeFrameDoesNotHideLaterFrames() {
        val t = tag(frame("TXXX", ByteArray(0)), frame("USLT", uslt(lrc)))
        assertEquals(lrc.trim(), Id3Reader.parse(t)!!.lyrics)
    }

    @Test fun skipsBlankUsltAndPrefersTimedText() {
        val t = tag(frame("USLT", uslt("", lang = "XXX")), frame("USLT", uslt("時刻の無い歌詞")), frame("TXXX", txxx("LYRICS", lrc)))
        assertEquals(lrc.trim(), Id3Reader.parse(t)!!.lyrics)
    }

    /** SYLT: 時刻はミリ秒、BOM は最初の文字列だけ(以降はリトルエンディアンのまま) */
    private fun sylt(): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(1); o.write("eng".toByteArray()); o.write(2); o.write(1)
        o.write(utf16("")); o.write(0); o.write(0)
        fun entry(s: String, ms: Int, bom: Boolean) {
            o.write(utf16(s, bom)); o.write(0); o.write(0)
            o.write(ms ushr 24); o.write(ms ushr 16 and 0xFF); o.write(ms ushr 8 and 0xFF); o.write(ms and 0xFF)
        }
        entry("最初", 1000, true)
        entry("次の行", 5500, false)
        return o.toByteArray()
    }

    @Test fun syltDecodesStringsWithoutTheirOwnBom() {
        val l = Id3Reader.parse(tag(frame("SYLT", sylt())))!!.lyrics!!
        assertEquals("[00:01.00]最初\n[00:05.50]次の行", l.trim())
    }

    @Test fun timedTextLyricsWinOverSyltAndSyltOverPlainText() {
        // MusicBee で直した時刻付きの歌詞(USLT)を、古い SYLT より優先する
        assertEquals(lrc.trim(), Id3Reader.parse(tag(frame("SYLT", sylt()), frame("USLT", uslt(lrc))))!!.lyrics)
        // 時刻の無い歌詞しか無ければ、流れる歌詞(SYLT)を使う
        assertEquals(
            "[00:01.00]最初\n[00:05.50]次の行",
            Id3Reader.parse(tag(frame("SYLT", sylt()), frame("USLT", uslt("時刻の無い歌詞"))))!!.lyrics!!.trim(),
        )
    }
}
