package com.uyatame.ucrt.convert

import android.content.Context
import android.net.Uri
import com.uyatame.ucrt.library.ArtExtract
import com.uyatame.ucrt.library.TagScan
import com.uyatame.ucrt.library.UNKNOWN_ALBUM
import com.uyatame.ucrt.library.UNKNOWN_ARTIST
import com.uyatame.ucrt.player.BuiltinCodecs
import com.uyatame.ucrt.player.dsd.DsdTags
import com.uyatame.ucrt.rip.Id3Tags
import com.uyatame.ucrt.tags.TagExtra
import com.uyatame.ucrt.tags.coverFrom

/** 変換する曲の曲情報とジャケットを読む(書き出すファイルに写す) */
object SourceTags {
    /** name は表示名(拡張子で形式を決める)。読めなければ null */
    fun read(ctx: Context, uri: Uri, name: String): OutTags? = runCatching {
        val ext = name.substringAfterLast('.', "").lowercase()
        val t = TagScan(ctx, { ctx.contentResolver.openInputStream(it) }, { null }).readTags(uri, name, "", null, 0L)
        val extra = runCatching { BuiltinCodecs.openInput(ctx, uri)?.use { TagExtra.readFrom(it, ext) } }.getOrNull()
        val pic = runCatching {
            if (ext == "dsf" || ext == "dff") DsdTags.picture(ctx, uri)
            else BuiltinCodecs.openInput(ctx, uri)?.use { ArtExtract.picture(it, ext) }
        }.getOrNull()
        val title = t.title.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
        val artist = t.artist.takeIf { it != UNKNOWN_ARTIST }.orEmpty()
        val album = t.album.takeIf { it != UNKNOWN_ALBUM }.orEmpty()
        OutTags(
            Id3Tags(title, artist, album, t.albumArtist, t.year, t.track, 0, if (t.disc > 0) t.disc else 0, 0),
            t.genre, extra?.takeIf { !it.isEmpty }, pic?.let { coverFrom(it) },
        )
    }.getOrNull()
}
