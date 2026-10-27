package com.uyatame.ucrt.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri

/** ジャケット画像の読み出し(曲に埋め込まれたもの → フォルダの画像)と、表示する大きさへの縮小。app と legacy で共通 */
object ArtSource {
    /** 画像のバイト列(曲に埋め込まれたもの → フォルダの画像)。無ければ null。読めなければ例外 */
    fun bytes(ctx: Context, trackUri0: String?, coverUri: String?): ByteArray? {
        var bytes: ByteArray? = null
        // CUE シートで分けた曲は、元のファイルの画像を使う
        val trackUri = trackUri0?.let { Cue.fileUri(it) }
        val name = trackUri?.let { Uri.decode(it) }.orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        if (trackUri != null && com.uyatame.ucrt.player.dsd.DsdFile.isDsd(name)) {
            bytes = com.uyatame.ucrt.player.dsd.DsdTags.picture(ctx, Uri.parse(trackUri))
        } else if (trackUri != null && ext == "wav") {
            bytes = com.uyatame.ucrt.tags.WavTags.picture(ctx, Uri.parse(trackUri))
        } else if (trackUri != null && com.uyatame.ucrt.player.BuiltinCodecs.kindOf(name).let { it == "aiff" || it == "wavpack" || it == "ape" }) {
            val kind = com.uyatame.ucrt.player.BuiltinCodecs.kindOf(name)!!
            bytes = com.uyatame.ucrt.player.BuiltinCodecs.openInput(ctx, Uri.parse(trackUri))?.use { com.uyatame.ucrt.tags.ExtraTags.read(kind, it)?.picture }
        } else if (trackUri != null) {
            // FLAC・MP3・M4A はアプリで直接読む(速く、古い Android でも読める)
            if (ext == "flac" || ext == "mp3" || ext == "m4a" || ext == "mp4") {
                bytes = runCatching {
                    com.uyatame.ucrt.player.BuiltinCodecs.openInput(ctx, Uri.parse(trackUri))?.use { ArtExtract.picture(it, ext) }
                }.getOrNull()
            }
            if (bytes == null) {
                val r = MediaMetadataRetriever()
                try {
                    // 「すべてのファイルへのアクセス」があれば、ファイルから直接読む方が速い
                    val uri = Uri.parse(trackUri)
                    val file = com.uyatame.ucrt.FileUris.fileOf(uri)
                    if (file != null) r.setDataSource(file.absolutePath) else r.setDataSource(ctx, uri)
                    bytes = r.embeddedPicture
                } catch (e: Exception) {
                    // 形式に対応していないだけのこともあるので、フォルダの画像を探しに行く
                } finally {
                    runCatching { r.release() }
                }
            }
        }
        if (bytes == null && coverUri != null) {
            bytes = ctx.contentResolver.openInputStream(Uri.parse(coverUri))?.use { it.readBytes() }
        }
        return bytes
    }

    /**
     * 画像を size(px)前後の大きさで読む。大きな画像は、まず 2 のべき乗で間引いて読み、それでも大きければ縮める。
     * 小さな画像(small。一覧用)は、色数を減らしてメモリーを半分にする
     */
    fun decode(b: ByteArray, size: Int, small: Boolean): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= size && o.outHeight / (s * 2) >= size) s *= 2
        val opt = BitmapFactory.Options().apply {
            inSampleSize = s
            if (small) inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeByteArray(b, 0, b.size, opt) ?: return null
        // 一覧用は、表示する大きさの 1.5 倍を超えていればさらに縮める。再生画面用も、上限(画面の大きさ)を超えていれば縮める
        val m = maxOf(bmp.width, bmp.height)
        if ((small && m > size * 3 / 2) || (!small && m > size)) {
            val k = size.toFloat() / m
            val out = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
            if (out !== bmp) bmp.recycle()
            return out
        }
        return bmp
    }
}
