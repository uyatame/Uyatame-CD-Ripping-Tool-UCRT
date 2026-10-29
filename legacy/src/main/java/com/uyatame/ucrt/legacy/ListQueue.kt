package com.uyatame.ucrt.legacy

import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.library.LibTrack

/**
 * 設定の「最後まで再生したら次のリストへ」(本家と同じ)。
 * 再生を始めたリスト(アルバム・アーティスト・ジャンル・年・プレイリスト・フォルダ)を覚えておき、最後まで再生したら、
 * ライブラリの画面の並び順で次のリストを続けて再生する(最後なら最初へ戻る。曲の無いリストは飛ばす)
 */
object ListQueue {
    /** どのリストか(kind はアルバムなどの種類、key はアルバムのキー・名前・プレイリストの ID・フォルダの場所) */
    class Src(val kind: String, val key: String)

    const val ALBUM = "album"
    const val ARTIST = "artist"
    const val GENRE = "genre"
    const val YEAR = "year"
    const val PLAYLIST = "playlist"
    const val FOLDER = "folder"

    /** 今再生しているリスト(null = 曲の一覧・検索などから再生した) */
    @Volatile var source: Src? = null

    private val app get() = LegacyApp.app

    fun artistTracks(name: String): List<LibTrack> {
        val albums = app.lib.albums
        val main = albums.filter { it.artist == name }.sortedByDescending { it.year }
        val guest = albums.filter { a -> a.artist != name && a.tracks.any { it.artist == name } }
        return main.flatMap { it.tracks } + guest.flatMap { a -> a.tracks.filter { it.artist == name } }
    }

    private fun genreTracks(g: String): List<LibTrack> {
        val lib = app.lib
        return lib.albums.flatMap { a -> a.tracks.filter { it.genre == g } }.groupBy { lib.albumKeyOf(it.uri) ?: it.album }.values.flatten()
    }

    /** フォルダの曲(フォルダのページと同じ並び。子フォルダも含む) */
    private fun folderTracks(path: String): List<LibTrack> {
        var n: LibraryPages.FolderNode? = LibraryPages.folderTree(app.lib.albums)
        for (p in path.split('/').filter { it.isNotEmpty() }) n = n?.children?.get(p)
        return n?.let { LibraryPages.folderTracks(it) }.orEmpty()
    }

    private fun <T> after(keys: List<T>, cur: T): List<T> {
        val i = keys.indexOf(cur)
        return if (i < 0) keys else keys.drop(i + 1) + keys.take(i)
    }

    /** 次のリスト(出どころ・名前・曲)。見つからなければ null */
    private fun next(src: Src): Triple<Src, String, List<LibTrack>>? {
        val albums = app.lib.albums
        when (src.kind) {
            ALBUM -> {
                val order = albums.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
                for (k in after(order.map { it.key }, src.key)) {
                    val a = order.firstOrNull { it.key == k } ?: continue
                    if (a.tracks.isNotEmpty()) return Triple(Src(ALBUM, a.key), a.title, a.tracks)
                }
            }
            ARTIST -> for (n in after(albums.map { it.artist }.distinct().sortedBy { it.lowercase() }, src.key)) {
                val t = artistTracks(n)
                if (t.isNotEmpty()) return Triple(Src(ARTIST, n), n, t)
            }
            GENRE -> for (g in after(albums.flatMap { it.tracks }.map { it.genre }.distinct().sortedWith(compareBy({ it.isEmpty() }, { it.lowercase() })), src.key)) {
                val t = genreTracks(g)
                if (t.isNotEmpty()) return Triple(Src(GENRE, g), g.ifEmpty { S(R.string.unknown_genre) }, t)
            }
            YEAR -> for (y in after(albums.map { it.year }.distinct().sortedWith(compareBy<String> { it.isEmpty() }.thenByDescending { it }), src.key)) {
                val t = albums.filter { it.year == y }.flatMap { it.tracks }
                if (t.isNotEmpty()) return Triple(Src(YEAR, y), y.ifEmpty { S(R.string.unknown_year) }, t)
            }
            PLAYLIST -> {
                val pls = app.store.playlists
                val map = app.lib.trackMap
                for (id in after(pls.map { it.id }, src.key)) {
                    val p = pls.firstOrNull { it.id == id } ?: continue
                    val t = p.uris.mapNotNull { map[it] }
                    if (t.isNotEmpty()) return Triple(Src(PLAYLIST, id), p.name, t)
                }
            }
            FOLDER -> {
                // 曲の入っているフォルダを名前順に並べ、今のフォルダ(とその中)より後ろの最初のフォルダ
                val cur = src.key
                val dirs = albums.asSequence().flatMap { it.tracks.asSequence() }.map { Lib.trackFolder(it) }.distinct()
                    .filter { it != cur && !it.startsWith("$cur/") }.sortedBy { it.lowercase() }.toList()
                val later = dirs.filter { it.lowercase() > cur.lowercase() }
                for (d in later + (dirs - later.toSet())) {
                    val t = folderTracks(d)
                    if (t.isNotEmpty()) return Triple(Src(FOLDER, d), d.substringAfterLast('/').ifEmpty { "/" }, t)
                }
            }
        }
        return null
    }

    /** 並びを最後まで再生し終えたとき(メインのスレッドで): 設定がオンなら、次のリストを続けて再生する(シャッフルはそのまま) */
    fun onQueueEnd() {
        val p = app.player
        if (!app.prefs.sp.getBoolean("autoNextList", false) || p.repeatMode != 0) return
        val src = source ?: return
        val (ns, name, tracks) = next(src) ?: return
        AppLog.log("auto next list: ${src.kind} -> ${ns.key}")
        Actions.playTracks(tracks, 0, p.shuffle, ns)
        LegacyActivity.current?.snack(S(R.string.auto_next_now, name))
    }
}
