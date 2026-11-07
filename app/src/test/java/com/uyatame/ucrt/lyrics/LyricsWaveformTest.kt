package com.uyatame.ucrt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 歌詞の編集の波形: 内蔵のデコーダー(FLAC)から、山の高さを作れるか */
class LyricsWaveformTest {
    private class Bytes(val b: ByteArray) : com.uyatame.ucrt.player.RandomInput {
        override val size: Long get() = b.size.toLong()
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int {
            if (pos >= this.b.size) return -1
            val n = minOf(len.toLong(), this.b.size - pos).toInt()
            System.arraycopy(this.b, pos.toInt(), b, off, n)
            return n
        }
    }

    @Test fun peaksFromFlac() {
        for (name in listOf("stereo16.flac", "stereo24.flac")) {
            val bytes = javaClass.classLoader!!.getResourceAsStream("audio/$name")!!.readBytes()
            val d = com.uyatame.ucrt.player.Decoders.create("flac", Bytes(bytes))
            d.open()
            var last = 0f
            val pk = com.uyatame.ucrt.LyricsIO.peaks(d, 200, progress = { last = it })!!
            assertEquals(200, pk.size)
            // どの山も 0〜1。音のある曲なので、ほとんどの区切りに山がある
            assertTrue(name, pk.all { it in 0f..1f })
            assertTrue(name, pk.count { it > 0.01f } > 150)
            assertEquals(name, 1f, last, 0.02f)
        }
    }

    @Test fun stopsWhenCancelled() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("audio/stereo16.flac")!!.readBytes()
        val d = com.uyatame.ucrt.player.Decoders.create("flac", Bytes(bytes))
        d.open()
        val pk = com.uyatame.ucrt.LyricsIO.peaks(d, 100, active = { false })!!
        assertTrue(pk.all { it == 0f })
    }
}
