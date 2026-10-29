package com.uyatame.ucrt.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** ReplayGain の値の読み取り(形式ごとのタグ)と、掛ける倍率 */
class ReplayGainTest {
    private class Bytes(val b: ByteArray) : RandomInput {
        override val size: Long get() = b.size.toLong()
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int {
            if (pos >= this.b.size) return -1
            val n = minOf(len, this.b.size - pos.toInt())
            System.arraycopy(this.b, pos.toInt(), b, off, n)
            return n
        }
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun cat(vararg p: ByteArray) = ByteArrayOutputStream().apply { p.forEach { write(it) } }.toByteArray()

    @After fun reset() {
        ReplayGain.mode = 0
        ReplayGain.preampDb = 0f
        ReplayGain.preventClipping = true
    }

    @Test fun flacVorbisCommentAfterLargePicture() {
        val comments = listOf("TITLE=x", "REPLAYGAIN_TRACK_GAIN=-7.89 dB", "replaygain_track_peak=0.988525", "REPLAYGAIN_ALBUM_GAIN=-6.5 dB")
        val vc = cat(le32(3), "abc".toByteArray(), le32(comments.size), *comments.flatMap { listOf(le32(it.length), it.toByteArray()) }.toTypedArray())
        val pic = ByteArray(3 shl 20) // 画像の区画が先にあって大きくても読める
        val flac = cat(
            "fLaC".toByteArray(),
            byteArrayOf(0, 0, 0, 34), ByteArray(34),
            byteArrayOf(6, (pic.size shr 16).toByte(), (pic.size shr 8).toByte(), pic.size.toByte()), pic,
            byteArrayOf((0x80 or 4).toByte(), (vc.size shr 16).toByte(), (vc.size shr 8).toByte(), vc.size.toByte()), vc,
        )
        val r = ReplayGain.read(Bytes(flac), "flac")!!
        assertEquals(-7.89f, r.trackGain!!, 1e-4f)
        assertEquals(0.988525f, r.trackPeak!!, 1e-6f)
        assertEquals(-6.5f, r.albumGain!!, 1e-4f)
        assertNull(r.albumPeak)
    }

    private fun txxx(desc: String, value: String, utf16: Boolean): ByteArray {
        val body = if (utf16) {
            cat(byteArrayOf(1), byteArrayOf(0xFF.toByte(), 0xFE.toByte()), desc.toByteArray(Charsets.UTF_16LE), byteArrayOf(0, 0),
                byteArrayOf(0xFF.toByte(), 0xFE.toByte()), value.toByteArray(Charsets.UTF_16LE))
        } else cat(byteArrayOf(0), desc.toByteArray(), byteArrayOf(0), value.toByteArray())
        return cat("TXXX".toByteArray(), be32(body.size), byteArrayOf(0, 0), body)
    }

    private fun id3(frames: ByteArray): ByteArray {
        val n = frames.size
        return cat("ID3".toByteArray(), byteArrayOf(3, 0, 0),
            byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte()), frames)
    }

    @Test fun mp3Id3TxxxLatin1AndUtf16() {
        val tag = id3(cat(txxx("REPLAYGAIN_TRACK_GAIN", "+2.10 dB", false), txxx("REPLAYGAIN_ALBUM_GAIN", "-1.25 dB", true)))
        val r = ReplayGain.read(Bytes(cat(tag, ByteArray(4096))), "mp3")!!
        assertEquals(2.10f, r.trackGain!!, 1e-4f)
        assertEquals(-1.25f, r.albumGain!!, 1e-4f)
    }

    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val b = cat(*body)
        return cat(be32(8 + b.size), type.toByteArray(), b)
    }

    @Test fun m4aFreeformAtoms() {
        val ff = box("----",
            box("mean", ByteArray(4), "com.apple.iTunes".toByteArray()),
            box("name", ByteArray(4), "replaygain_track_gain".toByteArray()),
            box("data", be32(1), ByteArray(4), "-10.23 dB".toByteArray()))
        val moov = box("moov", box("trak", ByteArray(64)), box("udta", box("meta", ByteArray(4), box("ilst", ff))))
        val r = ReplayGain.read(Bytes(cat(box("ftyp", "M4A ".toByteArray()), moov, box("mdat", ByteArray(1000)))), "m4a")!!
        assertEquals(-10.23f, r.trackGain!!, 1e-4f)
    }

    @Test fun apev2ItemsAtEnd() {
        fun item(k: String, v: String) = cat(le32(v.length), le32(0), k.toByteArray(), byteArrayOf(0), v.toByteArray())
        val items = cat(item("REPLAYGAIN_TRACK_GAIN", "-3.00 dB"), item("REPLAYGAIN_TRACK_PEAK", "1.050000"))
        val footer = cat("APETAGEX".toByteArray(), le32(2000), le32(items.size + 32), le32(2), le32(0), ByteArray(8))
        val file = cat(ByteArray(3 shl 20), items, footer)
        val r = ReplayGain.read(Bytes(file), "wv")!!
        assertEquals(-3f, r.trackGain!!, 1e-4f)
        assertEquals(1.05f, r.trackPeak!!, 1e-6f)
    }

    @Test fun flacWithId3HeaderAndNoExtension() {
        val comments = listOf("REPLAYGAIN_TRACK_GAIN=-2.50 dB")
        val vc = cat(le32(0), le32(comments.size), *comments.flatMap { listOf(le32(it.length), it.toByteArray()) }.toTypedArray())
        val flac = cat(
            "fLaC".toByteArray(), byteArrayOf(0, 0, 0, 34), ByteArray(34),
            byteArrayOf((0x80 or 4).toByte(), (vc.size shr 16).toByte(), (vc.size shr 8).toByte(), vc.size.toByte()), vc,
        )
        // 先頭に ID3v2(中身は空)が付いていて、拡張子が分からない URI
        val r = ReplayGain.read(Bytes(cat(id3(ByteArray(20)), flac)), "")!!
        assertEquals(-2.5f, r.trackGain!!, 1e-4f)
    }

    @Test fun noTags() {
        val r = ReplayGain.read(Bytes(ByteArray(5000)), "wav")
        assertNull(r?.trackGain)
    }

    @Test fun linearGain() {
        val i = RgInfo(trackGain = -6f, trackPeak = 0.5f, albumGain = 6f, albumPeak = 0.9f)
        assertEquals(1f, ReplayGain.linear(i), 0f) // 使わない設定
        ReplayGain.mode = 1
        assertEquals(0.50119f, ReplayGain.linear(i), 1e-4f)
        ReplayGain.preampDb = 12f // +6 dB → 1.995 だが、ピーク 0.5 なので 2.0 まで(ちょうど収まる)
        assertEquals(1.99526f, ReplayGain.linear(i), 1e-4f)
        ReplayGain.preampDb = 0f
        ReplayGain.mode = 2 // アルバム: +6 dB だが、ピーク 0.9 で 1/0.9 に抑える
        assertEquals(1f / 0.9f, ReplayGain.linear(i), 1e-4f)
        ReplayGain.preventClipping = false
        assertEquals(1.99526f, ReplayGain.linear(i), 1e-4f)
        assertEquals(1f, ReplayGain.linear(RgInfo()), 0f) // 値が無い曲はそのまま
    }

    @Test fun opusR128Gain() {
        // Opus の R128_TRACK_GAIN は 1/256 dB 単位で、-23 LUFS が基準(ReplayGain の -18 LUFS に合わせて 5 dB 足す)
        val ogg = cat("OggS".toByteArray(), ByteArray(60), "OpusTags".toByteArray(), "R128_TRACK_GAIN=-2560".toByteArray(), ByteArray(4), "R128_ALBUM_GAIN=256".toByteArray())
        val r = ReplayGain.read(Bytes(ogg), "opus")!!
        assertEquals(-5f, r.trackGain!!, 1e-4f)
        assertEquals(6f, r.albumGain!!, 1e-4f)
    }

    @Test fun mp3ApeTagAtEnd() {
        // 初めの ID3v2 には値が無く、終わりの APEv2 タグに書いてある MP3(foobar2000 の設定によってはこうなる)
        val head = id3(txxx("comment", "x", false))
        val ape = cat("APETAGEX".toByteArray(), ByteArray(24), le32(5), le32(0), "REPLAYGAIN_TRACK_GAIN".toByteArray(), byteArrayOf(0), "-3.21 dB".toByteArray())
        val mp3 = cat(head, ByteArray((1 shl 20) + 1000), ape)
        val r = ReplayGain.read(Bytes(mp3), "mp3")!!
        assertEquals(-3.21f, r.trackGain!!, 1e-4f)
    }
}
