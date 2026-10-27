package com.uyatame.ucrt.rip

import com.uyatame.ucrt.meta.Cover
import java.io.ByteArrayOutputStream

class Id3Tags(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val year: String,
    val track: Int,
    val trackTotal: Int,
    val disc: Int,
    val discTotal: Int,
)

/** ID3v2.3 タグ(UTF-16で日本語対応、ジャケットはAPIC)を作る */
object Id3v2 {
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** extraFrames: 後ろに足すフレーム(詳しい情報。TagExtra.id3Frames) */
    fun build(t: Id3Tags, cover: Cover?, rawApic: List<ByteArray> = emptyList(), extraFrames: ByteArray = ByteArray(0)): ByteArray {
        val frames = ByteArrayOutputStream()
        fun frame(id: String, body: ByteArray) {
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            frames.write(be32(body.size))
            frames.write(byteArrayOf(0, 0))
            frames.write(body)
        }
        fun text(id: String, v: String) {
            if (v.isEmpty()) return
            frame(
                id,
                byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + v.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0),
            )
        }
        text("TIT2", t.title)
        text("TPE1", t.artist)
        text("TALB", t.album)
        text("TPE2", t.albumArtist)
        text("TYER", t.year.take(4))
        text("TRCK", "${t.track}/${t.trackTotal}")
        text("TPOS", "${t.disc}/${t.discTotal}")
        if (cover != null) {
            frame(
                "APIC",
                byteArrayOf(0) + cover.mime.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + cover.data,
            )
        }
        rawApic.forEach { frame("APIC", it) }
        frames.write(extraFrames)
        val body = frames.toByteArray()
        val s = body.size
        val head = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((s shr 21) and 0x7F).toByte(), ((s shr 14) and 0x7F).toByte(),
            ((s shr 7) and 0x7F).toByte(), (s and 0x7F).toByte(),
        )
        return head + body
    }
}
