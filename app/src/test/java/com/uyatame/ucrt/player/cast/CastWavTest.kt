package com.uyatame.ucrt.player.cast

import com.uyatame.ucrt.player.RandomInput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Chromecast に送る WAV(アプリの中でデコードしたもの)の中身と、途中から読むとき(Range)を確かめる */
class CastWavTest {
    private class FileInput(f: File) : RandomInput, AutoCloseable {
        private val r = RandomAccessFile(f, "r")
        override val size: Long = r.length()
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int { r.seek(pos); return r.read(b, off, len) }
        override fun close() = r.close()
    }

    private fun fixture(name: String) = File(javaClass.getResource("/audio/$name")!!.toURI())

    /** HTTP の応答から、頭(ヘッダー)と本文に分ける */
    private fun response(name: String, kind: String, range: String? = null, startMs: Long = 0, endMs: Long = 0): Pair<String, ByteArray> {
        val out = ByteArrayOutputStream()
        FileInput(fixture(name)).use { CastServer.writeWav(out, it, kind, startMs, endMs, range, head = false) }
        val all = out.toByteArray()
        val sep = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        return String(all, 0, sep, Charsets.ISO_8859_1) to all.copyOfRange(sep + 4, all.size)
    }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun wholeFileMatchesSource() {
        val (head, body) = response("stereo16.m4a", "alac")
        assertTrue(head, head.startsWith("HTTP/1.1 200"))
        assertEquals("RIFF", String(body, 0, 4))
        assertEquals("WAVE", String(body, 8, 4))
        // データは元の PCM(16 bit リトルエンディアン)と同じ
        assertEquals(44 + 13230 * 4, body.size)
        assertEquals("00d63a544b09ba7c4b6c0d4fe2b51150", md5(body.copyOfRange(44, body.size)))
        // 24 bit の FLAC は 24 bit の WAV になる
        val (_, b24) = response("stereo24.flac", "flac")
        assertEquals("679baa1536db0ab0afa6f2bce5ac114d", md5(b24.copyOfRange(44, b24.size)))
    }

    @Test
    fun rangesJoinToTheWholeFile() {
        val (_, full) = response("stereo16.wv", "wavpack")
        val parts = listOf("bytes=0-10", "bytes=11-12345", "bytes=12346-")
        val joined = ByteArrayOutputStream()
        for (r in parts) {
            val (head, body) = response("stereo16.wv", "wavpack", r)
            assertTrue(head, head.startsWith("HTTP/1.1 206"))
            joined.write(body)
        }
        assertArrayEquals(full, joined.toByteArray())
    }

    @Test
    fun cueSegment() {
        // 0.1 秒目から 0.2 秒目まで(4410 フレーム)
        val (_, body) = response("stereo16.aiff", "aiff", startMs = 100, endMs = 200)
        assertEquals(44 + 4410 * 4, body.size)
        val (_, full) = response("stereo16.aiff", "aiff")
        assertArrayEquals(full.copyOfRange(44 + 4410 * 4, 44 + 8820 * 4), body.copyOfRange(44, body.size))
    }
}
