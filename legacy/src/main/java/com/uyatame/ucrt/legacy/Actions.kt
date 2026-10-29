package com.uyatame.ucrt.legacy

import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.library.Cue
import com.uyatame.ucrt.library.LibAlbum
import com.uyatame.ucrt.library.LibTrack
import com.uyatame.ucrt.library.Quality
import com.uyatame.ucrt.library.QualityClass
import com.uyatame.ucrt.legacy.ui.ArtView
import com.uyatame.ucrt.legacy.ui.Btn
import com.uyatame.ucrt.legacy.ui.CheckView
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.Sheet
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.addDivider
import com.uyatame.ucrt.legacy.ui.badge
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.button
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.field
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.icon
import com.uyatame.ucrt.legacy.ui.sheetAction
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import java.io.File

/** 時間(ミリ秒)を「4:05」「1:02:03」の形にする(本家の fmtMs) */
fun fmtMs(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** CD のセクター数を時間にする(本家の fmtTime) */
fun fmtTime(sectors: Int): String = "%d:%02d".format(sectors / 75 / 60, sectors / 75 % 60)

/** 頭文字(高速スクロールの見出し) */
fun initialOf(s: String): String {
    val c = s.trim().firstOrNull() ?: return "#"
    return when {
        c.isLetter() -> c.uppercaseChar().toString()
        c.isDigit() -> "#"
        else -> c.toString()
    }
}

/** 検索用に文字をそろえる(全角/半角、大文字/小文字、カタカナ/ひらがな) */
fun norm(s: String): String {
    val n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).lowercase()
    val sb = StringBuilder(n.length)
    for (c in n) sb.append(if (c in 'ァ'..'ヶ') (c - 0x60) else c)
    return sb.toString()
}

/** 曲の合計時間と曲数(例: 12曲 ・ 48:10) */
fun countText(tracks: List<LibTrack>): String = S(R.string.songs_6, tracks.size, fmtMs(tracks.sumOf { it.durationMs }))

/** 曲の「アーティストを表示」で開くアーティスト(アルバムアーティストがあればそちら) */
fun artistOf(t: LibTrack): String = t.albumArtist.ifEmpty { t.artist }

/**
 * 曲・アルバムの操作(本家の MainViewModel の、ライブラリと再生の部分)
 */
object Actions {
    private val app get() = LegacyApp.app

    fun artRef(ctx: Context, t: LibTrack, perTrack: Boolean = app.prefs.trackArt): ArtRef {
        val album = app.lib.albumKeyOf(t.uri)
        return if (perTrack) {
            ArtRef("t:" + t.uri, t.uri, t.coverUri, app.lib.albumByKey(album)?.first?.uri?.takeIf { it != t.uri })
        } else ArtRef(album ?: t.uri, t.uri, t.coverUri, null)
    }

    fun albumArt(a: LibAlbum) = ArtRef(a.key, a.first.uri, a.first.coverUri)

    /** source: どのリストか(設定の「最後まで再生したら次のリストへ」で、次のリストを探す) */
    fun playTracks(list: List<LibTrack>, start: Int, shuffle: Boolean = false, source: ListQueue.Src? = null) {
        if (list.isEmpty()) return
        ListQueue.source = source
        if (shuffle) app.player.play(list, (list.indices).random(), true) else app.player.play(list, start)
    }

    fun playAlbum(a: LibAlbum, start: Int, shuffle: Boolean = false) = playTracks(a.tracks, start, shuffle, ListQueue.Src(ListQueue.ALBUM, a.key))

    fun shuffleAll() = playTracks(app.lib.albums.flatMap { it.tracks }, 0, shuffle = true)

    fun playNext(act: LegacyActivity, list: List<LibTrack>) {
        if (list.isEmpty()) return
        val wasEmpty = app.player.current == null
        app.player.playNext(list)
        if (!wasEmpty) act.snack(S(R.string.playing_next_songs, list.size))
    }

    fun addToQueue(act: LegacyActivity, list: List<LibTrack>) {
        if (list.isEmpty()) return
        val wasEmpty = app.player.current == null
        app.player.addToQueue(list)
        if (!wasEmpty) act.snack(S(R.string.added_to_end_of_queue, list.size))
    }

    fun toggleFav(act: LegacyActivity, uri: String) {
        val on = !app.store.isFav(uri)
        app.store.toggleFav(uri)
        act.snack(if (on) S(R.string.added_to_favorites) else S(R.string.removed_from_favorites))
    }

    fun toggleFavAlbum(act: LegacyActivity, key: String) {
        val on = !app.store.isFavAlbum(key)
        app.store.toggleFavAlbum(key)
        act.snack(if (on) S(R.string.album_added_to_favorites) else S(R.string.removed_from_favorites))
    }

    fun toggleCheck(act: LegacyActivity, uri: String) {
        val on = !app.store.isCheck(uri)
        app.store.setCheck(listOf(uri), on)
        act.snack(if (on) S(R.string.added_to_sound_check) else S(R.string.removed_from_sound_check))
    }

    fun setCheck(act: LegacyActivity, list: List<LibTrack>, on: Boolean) {
        app.store.setCheck(list.map { it.uri }, on)
        act.snack(if (on) S(R.string.added_songs_to_sound_check, list.size) else S(R.string.removed_from_sound_check))
    }

    fun addToPlaylist(act: LegacyActivity, id: String, uris: List<String>) {
        if (uris.isEmpty()) return
        app.store.addToPlaylist(id, uris)
        val name = app.store.playlists.firstOrNull { it.id == id }?.name.orEmpty()
        act.snack(S(R.string.added_songs_to, uris.size, name))
    }

    fun createPlaylist(act: LegacyActivity, name: String, uris: List<String>) {
        app.store.createPlaylist(name, uris)
        act.snack(S(R.string.created_playlist, name.trim()))
    }

    // ---------------- メニュー ----------------

    /** メニューの上に出す、曲・アルバムの見出し */
    private fun header(ctx: Context, art: ArtRef, title: String, sub: String, badgeText: String, hi: Boolean): View {
        val row = hbox(ctx)
        row.setPadding(dp(24), 0, dp(24), dp(12))
        val a = ArtView(ctx, 8f)
        a.enabledArt = app.prefs.listArt
        row.add(a, w = 56, h = 56, r = 14)
        a.bind(art, dp(56))
        val col = vbox(ctx)
        col.add(text(ctx, title, Type.TitleMedium, Pal.onSurface, lines = 2))
        col.add(text(ctx, sub, Type.BodySmall, Pal.onSurfaceVariant))
        col.add(badge(ctx, badgeText, hi), w = WRAP, t = 4)
        row.add(col, w = 0, weight = 1f)
        return row
    }

    /** 曲の操作メニュー(本家の TrackMenuSheet) */
    fun trackMenu(act: LegacyActivity, t: LibTrack, playlist: Pair<String, Int>? = null) {
        val s = Sheet(act)
        fun go(f: () -> Unit) { s.dismiss(); f() }
        s.add(header(act, artRef(act, t), t.title, t.artist + S(R.string.separator) + t.album, Quality.badge(t), Quality.classOf(t) == QualityClass.HiRes))
        s.body.addDivider()
        s.add(sheetAction(act, Icons.PlayNext, S(R.string.play_next)) { go { playNext(act, listOf(t)) } })
        s.add(sheetAction(act, Icons.Queue, S(R.string.add_to_queue)) { go { addToQueue(act, listOf(t)) } })
        val fav = app.store.isFav(t.uri)
        s.add(sheetAction(act, if (fav) Icons.Favorite else Icons.FavoriteBorder, S(if (fav) R.string.remove_from_favorites else R.string.add_to_favorites)) {
            go { toggleFav(act, t.uri) }
        })
        val check = app.store.isCheck(t.uri)
        s.add(sheetAction(act, Icons.Headphones, S(if (check) R.string.remove_from_sound_check else R.string.add_to_sound_check)) {
            go { toggleCheck(act, t.uri) }
        })
        s.add(sheetAction(act, Icons.PlaylistAdd, S(R.string.add_to_playlist)) { go { playlistPick(act, listOf(t.uri)) } })
        s.add(sheetAction(act, Icons.CheckCircle, S(R.string.select_multiple_add_to_playlist)) { go { act.startSelection(t.uri) } })
        if (playlist != null) {
            s.add(sheetAction(act, Icons.Delete, S(R.string.remove_from_this_playlist)) { go { app.store.removeFromPlaylist(playlist.first, playlist.second) } })
        }
        app.lib.albumKeyOf(t.uri)?.let { k ->
            s.add(sheetAction(act, Icons.Album, S(R.string.go_to_album)) { go { act.openRoute("album:$k") } })
        }
        s.add(sheetAction(act, Icons.Person, S(R.string.go_to_artist)) { go { act.openRoute("artist:" + artistOf(t)) } })
        s.add(sheetAction(act, Icons.Info, S(R.string.song_details)) { go { trackInfo(act, t) } })
        s.body.addDivider()
        s.add(sheetAction(act, Icons.Delete, S(R.string.delete_file)) { go { deleteDialog(act, listOf(t)) } })
        s.body.add(View(act), h = 16)
        s.show()
    }

    /** アルバムの操作メニュー(本家の AlbumMenuSheet) */
    fun albumMenu(act: LegacyActivity, a: LibAlbum) {
        val s = Sheet(act)
        fun go(f: () -> Unit) { s.dismiss(); f() }
        s.add(header(act, albumArt(a), a.title, a.artist + S(R.string.songs_5, a.tracks.size), Quality.badge(a), Quality.classOf(a) == QualityClass.HiRes))
        s.body.addDivider()
        s.add(sheetAction(act, Icons.PlayArrow, S(R.string.play)) { go { playAlbum(a, 0) } })
        s.add(sheetAction(act, Icons.Shuffle, S(R.string.shuffle)) { go { playAlbum(a, 0, true) } })
        s.add(sheetAction(act, Icons.PlayNext, S(R.string.play_next)) { go { playNext(act, a.tracks) } })
        s.add(sheetAction(act, Icons.Queue, S(R.string.add_to_queue)) { go { addToQueue(act, a.tracks) } })
        val fav = app.store.isFavAlbum(a.key)
        s.add(sheetAction(act, if (fav) Icons.Favorite else Icons.FavoriteBorder, S(if (fav) R.string.remove_from_favorites else R.string.add_album_to_favorites)) {
            go { toggleFavAlbum(act, a.key) }
        })
        val allCheck = a.tracks.all { app.store.isCheck(it.uri) }
        s.add(sheetAction(act, Icons.Headphones, S(if (allCheck) R.string.remove_album_from_sound_check else R.string.add_album_to_sound_check)) {
            go { setCheck(act, a.tracks, !allCheck) }
        })
        s.add(sheetAction(act, Icons.PlaylistAdd, S(R.string.add_to_playlist)) { go { playlistPick(act, a.tracks.map { it.uri }) } })
        s.add(sheetAction(act, Icons.Person, S(R.string.go_to_artist)) { go { act.openRoute("artist:" + a.artist) } })
        if (act.libStack.lastOrNull() != "album:" + a.key) {
            s.add(sheetAction(act, Icons.Album, S(R.string.go_to_album)) { go { act.openRoute("album:" + a.key) } })
        }
        s.add(sheetAction(act, Icons.Album, S(R.string.burn_to_cd)) { go { Burn.request(act, a.title, a.tracks) } })
        s.add(sheetAction(act, Icons.Upload, S(R.string.mb_upload)) { go { MbUpload.album(act, a) } })
        s.body.addDivider()
        s.add(sheetAction(act, Icons.Delete, S(R.string.delete_album_files)) { go { deleteDialog(act, a.tracks) } })
        s.body.add(View(act), h = 16)
        s.show()
    }

    /** プレイリストを選んで追加(新しく作ることもできる。本家の PlaylistPickDialog) */
    fun playlistPick(act: LegacyActivity, uris: List<String>) {
        val d = UDialog(act).title(S(R.string.add_to_playlist_2))
        d.message(S(R.string.songs_will_be_added, uris.size))
        val box = vbox(act)
        d.view(box)
        fun creating() {
            box.removeAllViews()
            val f = field(act, "", S(R.string.new_playlist_name))
            box.add(f, t = 8)
            d.button(S(R.string.cancel)) { d.dismiss() }
            d.button(S(R.string.create_and_add)) {
                val n = f.text.toString()
                if (n.isNotBlank()) { createPlaylist(act, n, uris); d.dismiss() }
            }
        }
        if (app.store.playlists.isEmpty()) creating() else {
            val add = hbox(act)
            add.setPadding(0, dp(12), 0, dp(12))
            add.add(icon(act, Icons.Add, Pal.primary), w = 24, h = 24, r = 16)
            add.add(text(act, S(R.string.new_playlist), Type.BodyLarge, Pal.primary))
            add.tap(8f) { d.dismiss(); playlistNew(act, uris) }
            box.add(add, t = 8)
            for (p in app.store.playlists) {
                val row = hbox(act)
                row.setPadding(0, dp(12), 0, dp(12))
                row.add(icon(act, Icons.Queue, Pal.onSurfaceVariant), w = 24, h = 24, r = 16)
                row.add(text(act, p.name, Type.BodyLarge), w = 0, weight = 1f)
                row.add(text(act, S(R.string.label_2, p.uris.size), Type.LabelMedium, Pal.onSurfaceVariant), w = WRAP)
                row.tap(8f) { addToPlaylist(act, p.id, uris); d.dismiss() }
                box.add(row)
            }
            d.button(S(R.string.cancel)) { d.dismiss() }
        }
        d.show()
    }

    private fun playlistNew(act: LegacyActivity, uris: List<String>) {
        nameDialog(act, S(R.string.add_to_playlist_2), "", S(R.string.create_and_add)) { createPlaylist(act, it, uris) }
    }

    /** 名前を入れるダイアログ(本家の PlaylistNameDialog) */
    fun nameDialog(act: LegacyActivity, title: String, initial: String, ok: String = S(R.string.ok), onDone: (String) -> Unit) {
        val d = UDialog(act).title(title)
        val f = field(act, initial, S(R.string.name))
        d.view(f)
        d.button(S(R.string.cancel)) { d.dismiss() }
        d.button(ok) {
            val n = f.text.toString()
            if (n.isNotBlank()) { onDone(n); d.dismiss() }
        }
        d.show()
    }

    /** 曲の詳細情報(本家の TrackInfoDialog) */
    fun trackInfo(act: LegacyActivity, t: LibTrack) {
        val rows = ArrayList<Pair<String, String>>()
        rows.add(S(R.string.title_2) to t.title)
        rows.add(S(R.string.artist) to t.artist)
        rows.add(S(R.string.album_2) to t.album)
        if (t.albumArtist.isNotEmpty()) rows.add(S(R.string.album_artist) to t.albumArtist)
        if (t.track > 0) rows.add(S(R.string.track) to (if (t.disc > 1) "${t.disc}-${t.track}" else "${t.track}"))
        if (t.year.isNotEmpty()) rows.add(S(R.string.year) to t.year)
        if (t.genre.isNotEmpty()) rows.add(S(R.string.genre) to t.genre)
        rows.add(S(R.string.length) to fmtMs(t.durationMs))
        val det = Quality.detail(t)
        rows.add(S(R.string.format) to (Quality.codec(t) + if (det.isNotEmpty()) " $det" else ""))
        if (Quality.classOf(t) == QualityClass.HiRes) rows.add(S(R.string.quality) to S(R.string.hi_res))
        val f = Lib.fileOf(t.uri)
        if (f != null && f.length() > 0) rows.add(S(R.string.file_size) to String.format(java.util.Locale.US, "%.1f MB", f.length() / 1048576.0))
        if (f != null) rows.add(S(R.string.location) to f.path + if (Cue.isCue(t.uri)) " (CUE ${t.track})" else "")
        val d = UDialog(act).title(S(R.string.song_details))
        for ((k, v) in rows) {
            val col = vbox(act)
            col.setPadding(0, dp(6), 0, dp(6))
            col.add(text(act, k, Type.LabelMedium, Pal.primary))
            col.add(text(act, v, Type.BodyMedium, Pal.onSurface, lines = 0))
            d.view(col)
        }
        d.close(S(R.string.close))
        d.show()
    }

    /** 曲のファイルを削除する前の確認(本家の DeleteConfirmDialog) */
    fun deleteDialog(act: LegacyActivity, list: List<LibTrack>) {
        if (list.isEmpty()) return
        val d = UDialog(act).icon(Icons.Delete, Pal.error).title(S(R.string.delete_song_files, list.size))
        d.message(S(R.string.files_will_be_deleted_from), Pal.error)
        d.view(text(act, list.take(6).joinToString("\n") { it.title } + if (list.size > 6) "\n…" else "", Type.BodySmall, Pal.onSurfaceVariant, lines = 0).also {
            it.setPadding(0, dp(8), 0, dp(8))
        })
        var agree = false
        lateinit var del: android.widget.TextView
        val cb = CheckView(act, false) { agree = it; del.alpha = if (it) 1f else 0.38f; del.isEnabled = it }
        val row = hbox(act)
        row.add(cb, w = 40, h = 40)
        row.add(text(act, S(R.string.i_understand_this_cant_be), Type.BodyMedium, Pal.onSurface, lines = 2), w = 0, weight = 1f)
        row.tap(8f) { cb.performClick() }
        d.view(row)
        d.button(S(R.string.cancel)) { d.dismiss() }
        del = d.button(S(R.string.delete), Pal.error, enabled = false) { if (agree) { d.dismiss(); deleteTracks(act, list) } }
        d.show()
    }

    fun deleteTracks(act: LegacyActivity, list: List<LibTrack>) {
        val chosen = list.map { it.uri }.toHashSet()
        val files = LinkedHashSet<String>()
        var kept = 0
        val all = app.lib.trackMap.values
        list.filter { !Cue.isCue(it.uri) }.forEach { files.add(it.uri) }
        list.filter { Cue.isCue(it.uri) }.groupBy { Cue.fileUri(it.uri) }.forEach { (file, parts) ->
            val sib = all.filter { Cue.isCue(it.uri) && Cue.fileUri(it.uri) == file }
            if (sib.all { it.uri in chosen }) {
                files.add(file)
                parts.mapNotNull { it.sheet }.distinct().forEach { files.add(it) }
            } else kept += parts.size
        }
        val q = app.player.queue
        for (i in q.indices.reversed()) if (q[i].uri in chosen) app.player.removeAt(i)
        var ok = 0
        var failed = 0
        for (u in files) {
            val f = Uri.parse(u).path?.let { File(it) }
            if (f != null && f.delete()) ok++ else failed++
        }
        act.snack(buildString {
            append(S(R.string.deleted_files, ok))
            if (failed > 0) append(S(R.string.could_not_be_deleted, failed))
            if (kept > 0) append(S(R.string.tracks_in_cue_sheet_cant))
        })
        app.lib.removeTracks(chosen + files)
    }

    /** 曲を選ぶモードの帯(本家の SelectionBar) */
    fun selectionBar(act: LegacyActivity): View {
        val bar = hbox(act)
        bar.bgd(com.uyatame.ucrt.legacy.ui.rounded(Pal.secondaryContainer, 20f))
        bar.setPadding(dp(4), dp(4), dp(8), dp(4))
        bar.add(com.uyatame.ucrt.legacy.ui.iconButton(act, Icons.Close, S(R.string.cancel), Pal.onSecondaryContainer) { act.endSelection() }, w = 48, h = 48)
        bar.add(text(act, S(R.string.songs_selected, act.selected.size), Type.TitleMedium, Pal.onSecondaryContainer), w = 0, weight = 1f)
        act.selectionPool?.let { pool ->
            val allPicked = pool.isNotEmpty() && pool.all { it.uri in act.selected }
            bar.add(button(act, S(if (allPicked) R.string.deselect_all else R.string.select_all), Btn.Text) {
                if (allPicked) pool.forEach { act.selected.remove(it.uri) } else pool.forEach { act.selected.add(it.uri) }
                Bus.emit(Bus.STORE)
            }, w = WRAP)
        }
        val add = button(act, S(R.string.add_to_playlist), Btn.Tonal, Icons.PlaylistAdd) {
            if (act.selected.isNotEmpty()) {
                val list = act.selected.toList()
                act.endSelection()
                playlistPick(act, list)
            }
        }
        bar.add(add, w = WRAP)
        if (act.selected.isEmpty()) add.alpha = 0.38f
        val wrap = LinearLayout(act)
        wrap.setPadding(dp(12), dp(8), dp(12), dp(8))
        wrap.gravity = Gravity.CENTER
        wrap.add(bar)
        return wrap
    }
}
