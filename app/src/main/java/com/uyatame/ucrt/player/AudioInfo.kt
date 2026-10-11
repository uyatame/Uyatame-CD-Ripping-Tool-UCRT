package com.uyatame.ucrt.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/** 再生中の曲の形式・音質(例: FLAC 44.1 kHz / 16 bit ・ 905 kbps) */
object AudioInfo {
    /**
     * 曲の形式の詳細。text は画面に出す 1 行の説明。
     * hiRes は「CD を超える音質」(48 kHz を超える、または 16 bit を超えるロスレス、または DSD)。
     */
    class Detail(
        val codec: String,
        val rate: Int,
        val bits: Int,
        val kbps: Long,
        val hiRes: Boolean,
        val text: String,
        val sizeBytes: Long,
    )

    fun describe(ctx: Context, item: PlayItem): String? = detail(ctx, item)?.text

    fun detail(ctx: Context, item: PlayItem): Detail? {
        if (item.cdTrack != null) return Detail("CD-DA", 44100, 16, 1411, false, "CD-DA 44.1 kHz / 16 bit · 1411 kbps", 0L)
        val uri = item.uri ?: return null
        if (com.uyatame.ucrt.player.dsd.DsdFile.isDsd(Uri.decode(uri.toString()))) {
            val d = com.uyatame.ucrt.player.dsd.DsdTags.read(ctx, uri)?.info
                ?: return Detail("DSD", 0, 1, 0, true, "DSD", size(ctx, uri) ?: 0L)
            val kbps = d.rate.toLong() * d.channels / 1000
            return Detail(
                "DSD", d.rate, 1, kbps, true,
                d.label() + " · " + (if (d.dff) "DFF" else "DSF") + " · $kbps kbps", size(ctx, uri) ?: 0L,
            )
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            var rate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
            var bits = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
            var kbps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { it / 1000 }
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val ext = Uri.decode(uri.toString()).substringAfterLast('.', "").lowercase()
            var name = formatName(mime, ext)
            if (name == "AAC" && (bits ?: 0) in 16..32) name = "ALAC"
            if (name == "FLAC" || name == "WAV") {
                header(ctx, uri, name)?.let { (sr, bps) ->
                    if (rate == null || rate <= 0) rate = sr
                    if (bits == null || bits <= 0) bits = bps
                }
            }
            val bytes = size(ctx, uri)
            if ((kbps == null || kbps <= 0) && dur > 0) {
                bytes?.let { kbps = it * 8 / dur }
            }
            val lossless = name == "FLAC" || name == "WAV" || name == "ALAC"
            val sb = StringBuilder(name)
            rate?.takeIf { it > 0 }?.let { sb.append(' ').append(khz(it)) }
            if (lossless) bits?.takeIf { it > 0 }?.let { sb.append(" / ").append(it).append(" bit") }
            kbps?.takeIf { it > 0 }?.let { sb.append(" · ").append(it).append(" kbps") }
            val rr = rate ?: 0
            val bb = if (lossless) bits ?: 0 else 0
            return Detail(name, rr, bb, kbps ?: 0L, lossless && (rr > 48000 || bb > 16), sb.toString(), bytes ?: 0L)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { r.release() }
        }
    }

    /** 音源のビット数(FLAC / WAV はファイルの中身から読む。分からなければ 16) */
    fun bitsOf(ctx: Context, uri: Uri): Int {
        val ext = Uri.decode(uri.toString()).substringAfterLast('.', "").lowercase()
        val name = when (ext) { "flac" -> "FLAC"; "wav" -> "WAV"; else -> return 16 }
        return header(ctx, uri, name)?.second?.takeIf { it in 8..32 } ?: 16
    }

    private fun formatName(mime: String, ext: String): String = when {
        mime.contains("flac") || ext == "flac" -> "FLAC"
        mime.contains("wav") || ext == "wav" -> "WAV"
        mime.contains("mpeg") || ext == "mp3" -> "MP3"
        mime.contains("alac") -> "ALAC"
        mime.contains("opus") || ext == "opus" -> "Opus"
        mime.contains("ogg") || mime.contains("vorbis") || ext == "ogg" -> "Ogg Vorbis"
        mime.contains("mp4") || mime.contains("aac") || ext == "m4a" || ext == "aac" -> "AAC"
        else -> ext.uppercase().ifEmpty { "Audio" }
    }

    private fun khz(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000.0)

    /** FLAC の STREAMINFO / WAV の fmt から(サンプリング周波数, ビット数)を読む */
    private fun header(ctx: Context, uri: Uri, name: String): Pair<Int, Int>? = runCatching {
        val b = ByteArray(64)
        val n = ctx.contentResolver.openInputStream(uri)?.use { it.read(b) } ?: return null
        if (name == "FLAC" && n >= 22 && String(b, 0, 4, Charsets.ISO_8859_1) == "fLaC") {
            fun u(i: Int) = b[i].toInt() and 0xFF
            val sr = (u(18) shl 12) or (u(19) shl 4) or (u(20) shr 4)
            val bps = (((u(20) and 1) shl 4) or (u(21) shr 4)) + 1
            sr to bps
        } else if (name == "WAV" && n >= 36 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF") {
            fun le(i: Int, c: Int): Int { var v = 0; for (k in c - 1 downTo 0) v = (v shl 8) or (b[i + k].toInt() and 0xFF); return v }
            le(24, 4) to le(34, 2)
        } else null
    }.getOrNull()

    private fun size(ctx: Context, uri: Uri): Long? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()
}
