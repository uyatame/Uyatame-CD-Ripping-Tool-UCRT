package com.uyatame.ucrt.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * 歌詞を探す。
 * 1) 曲と同じフォルダにある同じ名前の .lrc / .txt
 * 2) 曲に埋め込まれた歌詞(FLAC の LYRICS、MP3 / DSF の USLT)
 */
object LyricsLoader {
    fun load(ctx: Context, item: PlayItem): Lyrics? {
        val uri = item.uri ?: return null
        // CUE シートで分けた曲は、元のファイルの歌詞では時刻が合わないので出さない
        if (com.uyatame.ucrt.library.Cue.isCue(uri.toString())) return null
        runCatching { sidecar(ctx, uri, item.folderId) }.getOrNull()?.let { return it }
        return runCatching { LyricsCore.embedded(ctx, uri) }.getOrNull()
    }

    /** 曲に埋め込まれた歌詞だけ(歌詞を LRCLIB に登録するときに使う)。無い・読めなければ null */
    fun embeddedOnly(ctx: Context, uri: String): Lyrics? {
        return LyricsCore.embeddedOnly(ctx, uri)
    }

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun sidecar(ctx: Context, uri: Uri, folderId: String?): Lyrics? {
        if (folderId.isNullOrEmpty()) return null
        val name = displayName(ctx, uri) ?: return null
        val base = name.substringBeforeLast('.').lowercase()
        val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        var lrc: String? = null
        var txt: String? = null
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1)?.lowercase() ?: continue
                if (n == "$base.lrc") lrc = c.getString(0)
                else if (n == "$base.txt") txt = c.getString(0)
            }
        }
        val id = lrc ?: txt ?: return null
        val bytes = ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { it.readBytes() }
            ?: return null
        return LyricsCore.parse(LyricsCore.decode(bytes))
    }

    /** LRC(時刻付き)または普通の文章として読む */
    fun parse(text: String): Lyrics? = LyricsCore.parse(text)
}
