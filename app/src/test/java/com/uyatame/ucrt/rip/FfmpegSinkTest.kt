package com.uyatame.ucrt.rip

import com.uyatame.ucrt.player.BuiltinCodecs
import com.uyatame.ucrt.player.RandomInput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * FFmpeg での変換(取り込みと同じ流し込み方)と、FLAC の先頭の付け替え。
 * PC に ffmpeg が入っていないときは飛ばす(アプリに入れる FFmpeg と同じ設定のエンコーダーを使う)
 */
class FfmpegSinkTest {
    private val exe: File? = System.getenv("PATH").orEmpty().split(':').map { File(it, "ffmpeg") }.firstOrNull { it.canExecute() }

    private class Bytes(val b: ByteArray) : RandomInput {
        override val size: Long get() = b.size.toLong()
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int {
            if (pos >= this.b.size) return -1
            val n = minOf(len, this.b.size - pos.toInt())
            System.arraycopy(this.b, pos.toInt(), b, off, n)
            return n
        }
    }

    /** CD と同じ形の音(16bit・ステレオ)。左右で違う音にする */
    private fun pcm(frames: Int): ByteArray {
        val out = ByteArray(frames * 4)
        var seed = 1234567
        for (i in 0 until frames) {
            seed = seed * 1103515245 + 12345
            val l = (8000 * Math.sin(i * 0.0627) + (seed shr 20) % 300).toInt()
            val r = (6000 * Math.sin(i * 0.031)).toInt()
            out[i * 4] = l.toByte(); out[i * 4 + 1] = (l shr 8).toByte()
            out[i * 4 + 2] = r.toByte(); out[i * 4 + 3] = (r shr 8).toByte()
        }
        return out
    }

    /** CD の読み取りと同じく、決まった大きさに区切って流し込む */
    private fun feed(sink: AudioSink, data: ByteArray) {
        var p = 0
        while (p < data.size) {
            val n = minOf(13 * 2352, data.size - p)
            sink.write(data, p, n)
            p += n
        }
        sink.finish()
    }

    @Test fun flacIsBitExactAndRetagged() {
        assumeTrue("ffmpeg が無い", exe != null)
        val frames = 44100 * 3 + 123
        val src = pcm(frames)
        val out = ByteArrayOutputStream()
        val tags = listOf("TITLE" to "曲名", "ARTIST" to "アーティスト")
        val tmp = File.createTempFile("ucrt-ff", ".flac")
        feed(FfmpegSink(exe!!, listOf("-c:a", "flac", "-compression_level", "5", "-f", "flac"), tmp) { f ->
            f.inputStream().use { FlacHeader.retag(it, out, tags, null) }
        }, src)
        assertTrue("一時ファイルは消す", !tmp.exists())
        val flac = out.toByteArray()
        // 先頭: fLaC → STREAMINFO → このアプリの曲の情報(最後の区画)
        assertEquals(0, flac[4].toInt())
        val viAt = 4 + 4 + 34
        assertEquals(0x84, flac[viAt].toInt() and 0xFF)
        assertTrue(String(flac, viAt + 4, 200, Charsets.UTF_8).contains("Uyatame CD Ripping Tool"))
        val input = Bytes(flac)
        val d = BuiltinCodecs.create(BuiltinCodecs.sniff(input)!!, input).also { it.open() }
        val got = ByteArrayOutputStream()
        while (d.decodeNext()) for (i in 0 until d.blockSize) for (c in 0 until 2) {
            val v = d.samples[c][i]
            got.write(v); got.write(v shr 8)
        }
        assertArrayEquals("元の音とビット単位で同じ", src, got.toByteArray())
    }

    @Test fun mp3HasXingAndNoTags() {
        assumeTrue("ffmpeg が無い", exe != null)
        val tmp = File.createTempFile("ucrt-ff", ".mp3")
        var bytes = ByteArray(0)
        feed(FfmpegSink(exe!!, listOf("-c:a", "libmp3lame", "-b:a", "320k", "-compression_level", "2",
            "-f", "mp3", "-id3v2_version", "0", "-write_id3v1", "0", "-write_xing", "1"), tmp) { f -> bytes = f.readBytes() }, pcm(44100 * 2))
        // ID3 は付けず(アプリで付ける)、最初のフレームに長さの情報(Info / Xing)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        val head = String(bytes, 0, 64, Charsets.ISO_8859_1)
        assertTrue(head, head.contains("Info") || head.contains("Xing"))
        assertTrue("320 kbps で約 80 KB", bytes.size in 70_000..90_000)
    }

    @Test fun failureIsReported() {
        assumeTrue("ffmpeg が無い", exe != null)
        val tmp = File.createTempFile("ucrt-ff", ".x")
        val r = runCatching { feed(FfmpegSink(exe!!, listOf("-c:a", "no_such_encoder"), tmp) {}, pcm(44100)) }
        assertTrue(r.exceptionOrNull()?.message.orEmpty(), r.exceptionOrNull()?.message.orEmpty().startsWith("FFmpeg:"))
    }
}
