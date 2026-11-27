package com.uyatame.ucrt.airplay

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DACP メタデータ (DMAP) の解釈テスト。 */
class DmapParserTest {

    private fun chunk(tag: String, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag.toByteArray(Charsets.US_ASCII))
        out.write((value.size ushr 24) and 0xFF)
        out.write((value.size ushr 16) and 0xFF)
        out.write((value.size ushr 8) and 0xFF)
        out.write(value.size and 0xFF)
        out.write(value)
        return out.toByteArray()
    }

    @Test
    fun parsesSongMetadata() {
        val data = chunk("minm", "タイトル曲".toByteArray(Charsets.UTF_8)) +
            chunk("asar", "Artist Name".toByteArray(Charsets.UTF_8)) +
            chunk("asal", "Album Name".toByteArray(Charsets.UTF_8)) +
            chunk("astn", byteArrayOf(0, 3))
        val tags = DmapParser.parse(data)
        assertEquals("タイトル曲", DmapParser.title(tags))
        assertEquals("Artist Name", DmapParser.artist(tags))
        assertEquals("Album Name", DmapParser.album(tags))
        assertTrue(tags["astn"] is ByteArray)
    }

    @Test
    fun unwrapsContainer() {
        val inner = chunk("minm", "Inner Song".toByteArray(Charsets.UTF_8)) +
            chunk("asar", "Inner Artist".toByteArray(Charsets.UTF_8))
        val tags = DmapParser.parse(chunk("mlit", inner))
        assertEquals("Inner Song", DmapParser.title(tags))
        assertEquals("Inner Artist", DmapParser.artist(tags))
    }

    @Test
    fun stopsAtBrokenChunk() {
        val good = chunk("minm", "Song".toByteArray(Charsets.UTF_8))
        val broken = byteArrayOf('a'.code.toByte(), 's'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), 0, 0, 1, 0) // 長さ 256 を主張するが中身が無い
        val tags = DmapParser.parse(good + broken)
        assertEquals("Song", DmapParser.title(tags))
        assertNull(DmapParser.artist(tags))
    }
}
