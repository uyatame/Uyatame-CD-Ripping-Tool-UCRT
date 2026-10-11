package com.uyatame.ucrt.player.dsd

import android.content.Context
import android.net.Uri
import com.uyatame.ucrt.meta.Cover
import com.uyatame.ucrt.rip.Id3Tags
import com.uyatame.ucrt.rip.Id3v2
import com.uyatame.ucrt.tags.Id3Data
import com.uyatame.ucrt.tags.Id3Reader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** DSD ファイルの曲情報の読み書き */
object DsdTags {
    class Result(val info: DsdInfo, val tags: Id3Data?)

    /** 曲情報と基本情報を読む。読めなければ null */
    fun read(ctx: Context, uri: Uri): Result? {
        val f = RandomFile.open(ctx, uri) ?: return null
        return f.use {
            val info = runCatching { DsdFile.parse(it) }.getOrNull() ?: return null
            var tags: Id3Data? = null
            if (info.id3Offset >= 0 && info.id3Length in 10..(64L shl 20)) {
                tags = runCatching { Id3Reader.parse(it.read(info.id3Offset, info.id3Length.toInt())) }.getOrNull()
            }
            if (tags == null && (info.diinTitle != null || info.diinArtist != null)) {
                tags = Id3Data(title = info.diinTitle, artist = info.diinArtist)
            }
            Result(info, tags)
        }
    }

    fun picture(ctx: Context, uri: Uri): ByteArray? = read(ctx, uri)?.tags?.picture

    /** タグを書き換えられる形式か(DSF のみ) */
    fun writable(name: String): Boolean = name.substringAfterLast('.', "").lowercase() == "dsf"

    /**
     * DSF のタグ(ファイル末尾の ID3v2)をその場で書き換える。音声データには触れない。
     */
    fun writeDsf(ctx: Context, uri: Uri, t: Id3Tags, cover: Cover?) {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("cannot open")
        pfd.use { p ->
            // 読み込み用と書き込み用のチャンネルを同じファイルから作る
            val rd = FileInputStream(p.fileDescriptor).channel
            rd.use { _ -> FileOutputStream(p.fileDescriptor).channel.use { ch ->
                val head = ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN)
                rd.read(head, 0)
                head.flip()
                if (head.limit() < 92 || String(head.array(), 0, 4, Charsets.ISO_8859_1) != "DSD ") throw IOException("not DSF")
                val meta = head.getLong(20)
                val fmtSize = head.getLong(32)
                val dataHead = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                rd.read(dataHead, 28 + fmtSize)
                dataHead.flip()
                val dataEnd = 28 + fmtSize + dataHead.getLong(4)
                val size = ch.size()
                // タグの位置が音声データより前・ファイルの外を指していたら信用しない(音声データを壊さないため)
                val tagPos = if (meta in dataEnd..size) meta else maxOf(dataEnd, size)
                // 新しい画像が無ければ、今の画像を残す
                var useCover = cover
                if (useCover == null && size > tagPos) {
                    val n = (size - tagPos).coerceAtMost(64L shl 20).toInt()
                    val old = ByteBuffer.allocate(n)
                    var pos = tagPos
                    while (old.hasRemaining()) {
                        val r = rd.read(old, pos)
                        if (r <= 0) break
                        pos += r
                    }
                    Id3Reader.parse(old.array())?.picture?.let { pic ->
                        useCover = runCatching { com.uyatame.ucrt.tags.coverFrom(pic) }.getOrNull()
                    }
                }
                val tag = Id3v2.build(t, useCover)
                ch.truncate(tagPos)
                ch.write(ByteBuffer.wrap(tag), tagPos)
                val total = tagPos + tag.size
                val upd = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                upd.putLong(total)
                upd.putLong(tagPos)
                upd.flip()
                ch.write(upd, 12)
            } }
        }
    }
}
