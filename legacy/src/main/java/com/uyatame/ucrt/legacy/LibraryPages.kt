package com.uyatame.ucrt.legacy

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.library.LibAlbum
import com.uyatame.ucrt.library.LibTrack
import com.uyatame.ucrt.library.Quality
import com.uyatame.ucrt.library.QualityClass
import com.uyatame.ucrt.legacy.ui.ArtView
import com.uyatame.ucrt.legacy.ui.Btn
import com.uyatame.ucrt.legacy.ui.CheckView
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.ListPage
import com.uyatame.ucrt.legacy.ui.MATCH
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.ProgressBarView
import com.uyatame.ucrt.legacy.ui.Row
import com.uyatame.ucrt.legacy.ui.StaticRow
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.bold
import com.uyatame.ucrt.legacy.ui.button
import com.uyatame.ucrt.legacy.ui.chip
import com.uyatame.ucrt.legacy.ui.chipRow
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.icon
import com.uyatame.ucrt.legacy.ui.iconButton
import com.uyatame.ucrt.legacy.ui.rounded
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox

// =====================================================================
// ライブラリ: トップ(カテゴリーのアイコン)から、カテゴリー → アルバム → 曲 とたどる(本家の LibraryScreen)。
// 行き先は act.libStack に積む(戻るボタンで 1 つ戻る)
// =====================================================================

object LibraryPages {
    private val app get() = LegacyApp.app

    /** ページごとの並べ替え・絞り込み(戻ってきたときも同じにする) */
    private val sortOf = HashMap<String, Int>()
    private val qfOf = HashMap<String, Int>()
    private val qdOf = HashMap<String, String>()

    fun make(act: LegacyActivity, route: String?): Page = when {
        route == null -> HomePage(act)
        route == "search" -> SearchPage(act)
        route.startsWith("cat:") -> category(act, route.removePrefix("cat:"))
        route.startsWith("album:") -> AlbumPage(act, route.removePrefix("album:"))
        route.startsWith("artist:") -> ArtistPage(act, route.removePrefix("artist:"))
        route.startsWith("genre:") -> {
            val g = route.removePrefix("genre:")
            GroupedPage(act, g.ifEmpty { S(R.string.unknown_genre) }) { app.lib.albums.flatMap { a -> a.tracks.filter { it.genre == g } } }
        }
        route.startsWith("year:") -> {
            val y = route.removePrefix("year:")
            AlbumsPage(act, y.ifEmpty { S(R.string.unknown_year) }, route, sortable = false) { app.lib.albums.filter { it.year == y } }
        }
        route.startsWith("folder:") -> {
            val f = route.removePrefix("folder:")
            AlbumsPage(act, f.substringAfterLast('/').ifEmpty { "/" }, route, sortable = false, subtitle = f) {
                app.lib.albums.filter { a -> a.tracks.any { Lib.trackFolder(it).let { p -> p == f || p.startsWith("$f/") } } }
                    .sortedBy { folderPath(it).lowercase() }
            }
        }
        route.startsWith("playlist:") -> PlaylistPage(act, route.removePrefix("playlist:"))
        route.startsWith("dir:") -> FolderBrowsePage(act, route.removePrefix("dir:"))
        else -> HomePage(act)
    }

    private fun category(act: LegacyActivity, id: String): Page = when (id) {
        "albums" -> AlbumsPage(act, tileLabel(id), "albums", sortable = true) { app.lib.albums }
        "recent" -> {
            val ymd = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
            AlbumsPage(act, tileLabel(id), "recent", sortable = false, subOf = { ymd.format(java.util.Date(it.added)) }) {
                app.lib.albums.sortedByDescending { it.added }
            }
        }
        "hires" -> AlbumsPage(act, tileLabel(id), "hires", sortable = true, initialQuality = 0) { app.lib.albums }
        "artists" -> ArtistsPage(act)
        "songs" -> SongsPage(act)
        "favorites" -> FavoritesPage(act)
        "check" -> CheckPage(act)
        "playlists" -> PlaylistsPage(act)
        "history" -> HistoryPage(act)
        "top" -> TopPage(act)
        "genres" -> GenresPage(act)
        "years" -> YearsPage(act)
        else -> FoldersPage(act)
    }

    fun folderPath(a: LibAlbum): String = Lib.trackFolder(a.first)
    fun folderName(a: LibAlbum): String = folderPath(a).substringAfterLast('/')

    // ---------------- カテゴリー ----------------

    val TILE_IDS = listOf("albums", "artists", "songs", "favorites", "check", "playlists", "recent", "history", "top", "hires", "genres", "years", "folders")

    fun tileIcon(id: String): String = when (id) {
        "albums" -> Icons.Album
        "artists" -> Icons.Person
        "songs" -> Icons.MusicNote
        "favorites" -> Icons.Favorite
        "check" -> Icons.Headphones
        "playlists" -> Icons.Queue
        "recent" -> Icons.NewAdded
        "history" -> Icons.History
        "top" -> Icons.Trending
        "hires" -> Icons.HiRes
        "genres" -> Icons.Genre
        "years" -> Icons.DateRange
        else -> Icons.Folder
    }

    fun tileLabel(id: String): String = when (id) {
        "albums" -> S(R.string.albums)
        "artists" -> S(R.string.artists)
        "songs" -> S(R.string.songs)
        "favorites" -> S(R.string.favorites)
        "check" -> S(R.string.sound_check)
        "playlists" -> S(R.string.playlists)
        "recent" -> S(R.string.recently_added)
        "history" -> S(R.string.recently_played)
        "top" -> S(R.string.most_played)
        "hires" -> S(R.string.by_quality)
        "genres" -> S(R.string.genres)
        "years" -> S(R.string.years)
        else -> S(R.string.folders)
    }

    fun tileOrder(setting: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        setting.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { e ->
            val id = e.removePrefix("-")
            if (id in TILE_IDS && out.none { it.first == id }) out.add(id to !e.startsWith("-"))
        }
        TILE_IDS.forEach { id -> if (out.none { it.first == id }) out.add(id to true) }
        return out
    }

    fun encodeTiles(list: List<Pair<String, Boolean>>): String = list.joinToString(",") { (if (it.second) "" else "-") + it.first }

    fun stripOn(setting: String, id: String): Boolean = setting.split(',').none { it.trim() == "-strip:$id" }

    /** 画面の幅(dp。横向きでは左のレールの分を除く) */
    fun widthDp(act: LegacyActivity): Int {
        val dm = act.resources.displayMetrics
        return (dm.widthPixels / dm.density).toInt() - (if (act.useRail) 80 else 0) - act.paneWidthDp
    }

    /** アルバムのタイルの列の数(本家の GridCells.Adaptive(150dp)) */
    fun albumCols(act: LegacyActivity): Int = ((widthDp(act) - 40 + 12) / (150 + 12)).coerceIn(1, TilesRow.MAX)

    /** アルバムを列ごとに分けた行 */
    fun tileRows(act: LegacyActivity, list: List<LibAlbum>, labels: ((LibAlbum) -> String)? = null, subOf: ((LibAlbum) -> String)? = null): List<Row> {
        val cols = albumCols(act)
        return list.chunked(cols).map { ch -> TilesRow(act, ch, cols, subOf, labels?.invoke(ch.first()).orEmpty()) }
    }

    // ---------------- 共通の枠 ----------------

    /** 見出しと一覧(選ぶモードの帯も重ねる) */
    abstract class ListBased(act: LegacyActivity, fast: Boolean = false) : Page(act) {
        val frame = FrameLayout(act)
        val col = vbox(act)
        val list = ListPage(act, fast)
        private var bar: View? = null
        override val view: View get() = frame

        init {
            frame.add(col, h = MATCH)
        }

        /** 見出しと一覧を並べる(top は見出し) */
        fun setup(top: View?) {
            col.removeAllViews()
            if (top != null) col.add(top)
            col.add(list, h = 0, weight = 1f)
        }

        fun updateSelectionBar() {
            bar?.let { frame.removeView(it) }
            bar = null
            if (act.selecting) {
                val b = Actions.selectionBar(act)
                frame.add(b, h = WRAP, gravity = Gravity.BOTTOM)
                bar = b
                list.padBottom(80)
            } else list.padBottom(24)
        }

        override fun scrollState(): IntArray? = list.position()
        override fun restoreScroll(p: IntArray?) = list.restore(p)
    }

    // ---------------- トップ ----------------

    private var countsFor: List<LibAlbum>? = null
    private var countsCache: Map<String, String> = emptyMap()

    private fun libCounts(albums: List<LibAlbum>): Map<String, String> {
        if (countsFor === albums) return countsCache
        val tracks = albums.sumOf { it.tracks.size }
        countsCache = mapOf(
            "albums" to "${albums.size}",
            "artists" to "${albums.map { it.artist }.distinct().size}",
            "songs" to "$tracks",
            "hires" to S(R.string.hi_res_2, albums.count { Quality.classOf(it) == QualityClass.HiRes }),
            "genres" to "${albums.flatMap { a -> a.tracks.map { it.genre } }.filter { it.isNotEmpty() }.distinct().size}",
            "years" to "${albums.map { it.year }.filter { it.isNotEmpty() }.distinct().size}",
            "folders" to "${albums.flatMap { a -> a.tracks.map { Lib.trackFolder(it) } }.distinct().size}",
        )
        countsFor = albums
        return countsCache
    }

    class HomePage(act: LegacyActivity) : ListBased(act) {
        init {
            setup(null)
            refresh(-1)
        }

        override val mask = Bus.LIBRARY or Bus.STORE or Bus.SETTINGS or Bus.SCAN or Bus.ART or Bus.PLAYER

        override fun refresh(m: Int) {
            if (m == Bus.PLAYER) return
            val lib = app.lib
            val s = app.prefs
            val albums = lib.albums
            val store = app.store
            val hasFolders = lib.folders().isNotEmpty()
            val rows = ArrayList<Row>()
            // 見出し
            val head = hbox(act)
            head.setPadding(dp(4), dp(12), 0, 0)
            head.add(text(act, S(R.string.library), Type.HeadlineMedium), w = 0, weight = 1f)
            head.add(iconButton(act, Icons.Edit, S(R.string.reorder_categories)) { tileEdit(act) }, w = 48, h = 48)
            val rf = iconButton(act, Icons.Refresh, S(R.string.rescan_library)) { lib.refresh(true) }
            if (lib.scanning || !hasFolders) rf.alpha = 0.38f
            rf.isEnabled = !lib.scanning && hasFolders
            head.add(rf, w = 48, h = 48)
            rows.add(StaticRow(pad(head)))
            if (lib.scanning && (lib.full || lib.newCount > 0 || albums.isEmpty())) {
                val c = vbox(act)
                c.add(ProgressBarView(act))
                c.add(text(act, if (lib.full) S(R.string.rescanning_library, lib.progress) else S(R.string.scanning_for_new_songs, lib.progress), Type.BodySmall), t = 4, b = 4)
                rows.add(StaticRow(pad(c)))
            }
            if (!Permissions.granted(act)) {
                rows.add(StaticRow(emptyState(act, Icons.Library, S(R.string.legacy_permission_needed), "", S(R.string.legacy_allow)) { Permissions.ask(act, force = true) }))
                list.setRows(rows); return
            }
            if (albums.isEmpty() && !lib.scanning) {
                val bad = lib.unreadable
                val body = S(R.string.ripped_cds_appear_here_you) + if (bad.isNotEmpty()) "\n\n" + S(R.string.legacy_load_failed, bad.joinToString("\n")) else ""
                rows.add(StaticRow(emptyState(act, Icons.Library, S(R.string.no_songs_yet), body, S(R.string.add_music_folder)) {
                    FolderPicker.pick(act, null) { addFolder(act, it) }
                }))
                list.setRows(rows); return
            }
            // 検索
            val search = hbox(act)
            search.setPadding(dp(18), dp(14), dp(18), dp(14))
            search.add(icon(act, Icons.Search, Pal.onSurfaceVariant), w = 24, h = 24, r = 12)
            search.add(text(act, S(R.string.search_songs_albums_and_artists), Type.BodyLarge, Pal.onSurfaceVariant), w = 0, weight = 1f)
            search.tap(28f, rounded(Pal.containerHigh, 28f)) { act.libPush("search") }
            rows.add(StaticRow(pad(search, top = 4)))
            // 全曲シャッフル
            val counts = libCounts(albums) + mapOf(
                "favorites" to "${store.favTracks.size + store.favAlbums.size}",
                "check" to "${store.checkTracks.size}",
                "playlists" to "${store.playlists.size}",
                "history" to "${store.history.size}",
            )
            val sh = hbox(act)
            sh.setPadding(0, dp(4), 0, dp(4))
            sh.add(text(act, S(R.string.albums_songs, albums.size, counts["songs"]), Type.LabelLarge, Pal.onSurfaceVariant), w = 0, weight = 1f, l = 4)
            sh.add(button(act, S(R.string.shuffle_all), Btn.Tonal, Icons.Shuffle) { Actions.shuffleAll() }, w = WRAP)
            rows.add(StaticRow(pad(sh)))
            // カテゴリー
            val tiles = tileOrder(s.libTiles).filter { it.second }.map { it.first }
            val cols = ((widthDp(act) - 32 + 8) / (96 + 8)).coerceAtLeast(3)
            for (ch in tiles.chunked(cols)) {
                val r = hbox(act, Gravity.TOP)
                for (i in 0 until cols) {
                    val id = ch.getOrNull(i)
                    if (id == null) { r.add(View(act), w = 0, h = 1, weight = 1f); continue }
                    r.add(categoryTile(act, tileIcon(id), tileLabel(id), counts[id].orEmpty()) { act.libPush("cat:$id") }, w = 0, weight = 1f, r = if (i < cols - 1) 8 else 0)
                }
                rows.add(StaticRow(pad(r, bottom = 8)))
            }
            // ジャケットの列
            if ("recent" in tiles && stripOn(s.libTiles, "recent")) {
                val recent = albums.sortedByDescending { it.added }.take(15)
                if (recent.isNotEmpty()) {
                    rows.add(StaticRow(pad(headingView(act, S(R.string.recently_added_2), S(R.string.all)) { act.libPush("cat:recent") })))
                    rows.add(StaticRow(strip(act, recent)))
                }
            }
            if ("history" in tiles && stripOn(s.libTiles, "history")) {
                val played = store.history.asSequence().mapNotNull { lib.albumKeyOf(it.uri) }.distinct().mapNotNull { lib.albumByKey(it) }.take(15).toList()
                if (played.isNotEmpty()) {
                    rows.add(StaticRow(pad(headingView(act, S(R.string.recently_played_2), S(R.string.history)) { act.libPush("cat:history") })))
                    rows.add(StaticRow(strip(act, played)))
                }
            }
            list.setRows(rows)
            updateSelectionBar()
        }

        private fun pad(v: View, top: Int = 0, bottom: Int = 0): View {
            val f = FrameLayout(act)
            f.setPadding(dp(16), dp(top), dp(16), dp(bottom))
            f.add(v, h = WRAP)
            return f
        }
    }

    /** カテゴリーのアイコン(タップで一覧へ。本家の CategoryTile) */
    private fun categoryTile(act: LegacyActivity, d: String, label: String, count: String, onClick: () -> Unit): View {
        val col = vbox(act, Gravity.CENTER_HORIZONTAL)
        col.setPadding(0, dp(10), 0, dp(10))
        val box = FrameLayout(act)
        box.bgd(rounded(Pal.primaryContainer, 18f))
        box.add(icon(act, d, Pal.onPrimaryContainer, 28), w = 28, h = 28, gravity = Gravity.CENTER)
        col.add(box, w = 56, h = 56, b = 6)
        val l = text(act, label, Type.LabelLarge)
        l.gravity = Gravity.CENTER
        col.add(l)
        val c = text(act, count.ifEmpty { " " }, Type.LabelSmall, Pal.onSurfaceVariant)
        c.gravity = Gravity.CENTER
        col.add(c)
        col.tap(16f) { onClick() }
        return col
    }

    /** 横に流れるアルバムの列(本家の AlbumStrip) */
    private fun strip(act: LegacyActivity, list: List<LibAlbum>): View {
        val sc = HorizontalScrollView(act)
        sc.isHorizontalScrollBarEnabled = false
        val row = hbox(act, Gravity.TOP)
        row.setPadding(dp(20), 0, dp(20), 0)
        list.forEachIndexed { i, a ->
            val t = TilesRow.tile(act)
            TilesRow.bindTile(act, t, a, null, dp(132))
            row.add(t, w = 132, r = if (i < list.lastIndex) 12 else 0)
        }
        sc.addView(row)
        return sc
    }

    /** トップに並べるカテゴリーの並べ替え・表示の切り替え(本家の TileEditDialog) */
    private fun tileEdit(act: LegacyActivity) {
        val s = app.prefs.libTiles
        val list = tileOrder(s).toMutableList()
        var recentStrip = stripOn(s, "recent")
        var playedStrip = stripOn(s, "history")
        val d = UDialog(act).title(S(R.string.reorder_categories_2))
        val box = vbox(act)
        d.view(box)
        fun build() {
            box.removeAllViews()
            list.forEachIndexed { i, (id, on) ->
                val r = hbox(act)
                r.add(CheckView(act, on) { list[i] = id to it; build() }, w = 40, h = 40)
                r.add(icon(act, tileIcon(id), Pal.onSurfaceVariant, 20), w = 20, h = 20, r = 10)
                r.add(text(act, tileLabel(id), Type.BodyMedium), w = 0, weight = 1f)
                val up = iconButton(act, Icons.ArrowUp, S(R.string.up)) { if (i > 0) { list.add(i - 1, list.removeAt(i)); build() } }
                if (i == 0) up.alpha = 0.38f
                r.add(up, w = 40, h = 40)
                val dn = iconButton(act, Icons.ArrowDown, S(R.string.down)) { if (i < list.lastIndex) { list.add(i + 1, list.removeAt(i)); build() } }
                if (i == list.lastIndex) dn.alpha = 0.38f
                r.add(dn, w = 40, h = 40)
                box.add(r)
            }
            box.add(text(act, S(R.string.show_as_artwork_rows), Type.TitleSmall, Pal.primary), t = 12)
            val recentTile = list.firstOrNull { it.first == "recent" }?.second == true
            val playedTile = list.firstOrNull { it.first == "history" }?.second == true
            val r1 = hbox(act)
            r1.add(CheckView(act, recentStrip && recentTile) { recentStrip = it }.also { it.isEnabled = recentTile }, w = 40, h = 40)
            r1.add(text(act, S(R.string.recently_added_2), Type.BodyMedium))
            box.add(r1)
            val r2 = hbox(act)
            r2.add(CheckView(act, playedStrip && playedTile) { playedStrip = it }.also { it.isEnabled = playedTile }, w = 40, h = 40)
            r2.add(text(act, S(R.string.recently_played_2), Type.BodyMedium))
            box.add(r2)
        }
        build()
        d.button(S(R.string.reset_2)) { app.prefs.set("libTiles", ""); d.dismiss() }
        d.button(S(R.string.cancel)) { d.dismiss() }
        d.button(S(R.string.save)) {
            val strips = (if (recentStrip) "" else ",-strip:recent") + (if (playedStrip) "" else ",-strip:history")
            app.prefs.set("libTiles", encodeTiles(list) + strips)
            d.dismiss()
        }
        d.show()
    }

    /** 音楽のフォルダをライブラリに追加する */
    fun addFolder(act: LegacyActivity, path: String) {
        app.prefs.set("libraryFolders", app.prefs.libraryFolders + path)
        app.lib.refresh(false)
    }

    // ---------------- アルバムの一覧 ----------------

    /**
     * アルバムの一覧(グリッド / リスト)。並べ替えと、音質での絞り込みができる(本家の AlbumCollectionPage)。
     * initialQuality: 最初に選んでおく音質(-1 = すべて、0 = ハイレゾ、1 = CD音質、2 = 圧縮音源)
     */
    class AlbumsPage(
        act: LegacyActivity, private val title: String, private val key: String, private val sortable: Boolean,
        private val subtitle: String? = null, initialQuality: Int = -1, private val subOf: ((LibAlbum) -> String)? = null,
        private val source: () -> List<LibAlbum>,
    ) : ListBased(act, fast = true) {
        private var sort: Int get() = sortOf[key] ?: 0; set(v) { sortOf[key] = v }
        private var qf: Int get() = qfOf[key] ?: initQ; set(v) { qfOf[key] = v }
        private var qd: String get() = qdOf[key] ?: ""; set(v) { qdOf[key] = v }
        private val initQ = initialQuality

        init { refresh(-1) }

        override fun refresh(m: Int) {
            if (m == Bus.PLAYER) return
            val albums = source()
            val listView = app.prefs.libraryList
            val inClass = if (qf < 0) albums else albums.filter { Quality.classOf(it).ordinal == qf }
            val details = if (qf < 0) emptyList() else {
                val counts = inClass.groupingBy { Quality.detail(it) }.eachCount()
                val order = inClass.groupBy { Quality.detail(it) }.mapValues { (_, l) -> l.minOf { Quality.detailOrder(it) } }
                counts.keys.sortedWith(
                    if (qf == QualityClass.HiRes.ordinal) compareBy({ order[it] }, { it }) else compareBy({ -counts.getValue(it) }, { it }),
                ).map { it to counts.getValue(it) }
            }
            val f = if (qd.isEmpty()) inClass else inClass.filter { Quality.detail(it) == qd }
            val shown = if (!sortable) f else when (sort) {
                1 -> f.sortedBy { it.title.lowercase() }
                2 -> f.sortedByDescending { it.added }
                3 -> f.sortedWith(compareByDescending<LibAlbum> { it.year }.thenBy { it.artist.lowercase() })
                else -> f.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            }
            val ym = java.text.SimpleDateFormat("yyyy/MM", java.util.Locale.getDefault())
            val labelOf: (LibAlbum) -> String = { a ->
                if (!sortable) initialOf(a.title) else when (sort) {
                    1 -> initialOf(a.title)
                    2 -> ym.format(java.util.Date(a.added))
                    3 -> a.year.ifEmpty { "?" }
                    else -> initialOf(a.artist)
                }
            }
            setup(pageTop(act, title, subtitle ?: S(R.string.albums_3, shown.size)) { acts ->
                acts.add(iconButton(act, if (listView) Icons.Grid else Icons.ListView, S(if (listView) R.string.grid_view else R.string.list_view)) {
                    app.prefs.set("libraryList", !listView)
                }, w = 48, h = 48)
            })
            val rows = ArrayList<Row>()
            if (shown.isEmpty() && qf < 0) {
                rows.add(StaticRow(emptyState(act, Icons.Album, S(R.string.no_albums), "")))
                list.setRows(rows); return
            }
            // 並べ替え・絞り込み・再生
            val ctl = vbox(act)
            ctl.setPadding(dp(16), 0, dp(16), dp(8))
            if (sortable) {
                ctl.add(chipRow(act, listOf(S(R.string.artist), S(R.string.title), S(R.string.date_added), S(R.string.year)).mapIndexed { i, l ->
                    chip(act, l, sort == i) { sort = i; refresh(-1) }
                }), b = 4)
            }
            ctl.add(chipRow(act, listOf(chip(act, S(R.string.all), qf < 0) { qf = -1; qd = ""; refresh(-1) }) +
                QualityClass.entries.map { q -> chip(act, Quality.label(q), qf == q.ordinal) { qf = q.ordinal; qd = ""; refresh(-1) } }), b = 4)
            if (details.size > 1) {
                ctl.add(chipRow(act, listOf(chip(act, S(R.string.all), qd.isEmpty()) { qd = ""; refresh(-1) }) +
                    details.map { (d, n) -> chip(act, "$d ($n)", qd == d) { qd = if (qd == d) "" else d; refresh(-1) } }), b = 4)
            }
            ctl.add(playShuffle(act, { Actions.playTracks(shown.flatMap { it.tracks }, 0) }, { Actions.playTracks(shown.flatMap { it.tracks }, 0, true) }), t = 4)
            rows.add(StaticRow(ctl, shown.firstOrNull()?.let(labelOf).orEmpty()))
            if (listView) {
                shown.forEach { a -> rows.add(AlbumRowItem(act, a, subOf?.invoke(a), labelOf(a)).also { }) }
            } else {
                rows.addAll(tileRows(act, shown, labelOf, subOf))
            }
            list.list.setPadding(if (listView) dp(12) else 0, 0, if (listView) dp(24) else 0, dp(24))
            list.setRows(rows)
            updateSelectionBar()
        }
    }

    // ---------------- アーティスト ----------------

    class ArtistsPage(act: LegacyActivity) : ListBased(act, fast = true) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            if (m == Bus.PLAYER) return
            val artists = app.lib.albums.groupBy { it.artist }.map { (n, l) -> Triple(n, l, l.sumOf { it.tracks.size }) }.sortedBy { it.first.lowercase() }
            setup(pageTop(act, tileLabel("artists"), S(R.string.artists_2, artists.size)))
            list.list.setPadding(dp(12), 0, dp(24), dp(24))
            list.setRows(artists.map { (name, albums, n) ->
                val a = albums.first()
                GroupRowItem(name, S(R.string.albums_songs, albums.size, n), Lead.Art(Actions.albumArt(a), 24f), initialOf(name)) {
                    act.libPush("artist:$name")
                }
            })
        }
    }

    class ArtistPage(act: LegacyActivity, private val name: String) : ListBased(act) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            if (m == Bus.PLAYER) return
            val albums = app.lib.albums
            val main = albums.filter { it.artist == name }.sortedByDescending { it.year }
            val guest = albums.filter { a -> a.artist != name && a.tracks.any { it.artist == name } }
            val tracks = main.flatMap { it.tracks } + guest.flatMap { a -> a.tracks.filter { it.artist == name } }
            setup(pageTop(act, name))
            val rows = ArrayList<Row>()
            val head = hbox(act)
            head.setPadding(dp(16), 0, dp(16), dp(16))
            val lead = FrameLayout(act)
            GroupRowItem.leadView(act, Lead.Initial(name, 64), lead)
            head.add(lead, w = 64, h = 64, r = 16)
            val c = vbox(act)
            c.add(text(act, name, Type.HeadlineSmall, Pal.onSurface, lines = 2))
            c.add(text(act, S(R.string.albums_4, main.size + guest.size) + countText(tracks), Type.BodySmall, Pal.onSurfaceVariant))
            c.add(playShuffle(act, { Actions.playTracks(tracks, 0) }, { Actions.playTracks(tracks, 0, true) }), t = 8)
            head.add(c, w = 0, weight = 1f)
            rows.add(StaticRow(head))
            rows.addAll(tileRows(act, main, subOf = { it.year.ifEmpty { it.artist } }))
            if (guest.isNotEmpty()) {
                val h = FrameLayout(act); h.setPadding(dp(16), 0, dp(16), 0); h.add(headingView(act, S(R.string.appears_on), padStart = 0), h = WRAP)
                rows.add(StaticRow(h))
                rows.addAll(tileRows(act, guest))
            }
            list.setRows(rows)
        }
    }

    // ---------------- 曲 ----------------

    class SongsPage(act: LegacyActivity) : ListBased(act, fast = true) {
        private var sort: Int get() = sortOf["songs"] ?: 0; set(v) { sortOf["songs"] = v }
        init { refresh(-1) }

        override fun refresh(m: Int) {
            val t = app.lib.albums.flatMap { it.tracks }
            val all = when (sort) {
                1 -> t.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.disc }, { it.track }))
                2 -> t.sortedWith(compareBy({ it.album.lowercase() }, { it.disc }, { it.track }))
                3 -> t.sortedByDescending { it.modified }
                else -> t.sortedBy { it.title.lowercase() }
            }
            val ym = java.text.SimpleDateFormat("yyyy/MM", java.util.Locale.getDefault())
            val lab: (LibTrack) -> String = { x ->
                when (sort) {
                    1 -> initialOf(x.artist)
                    2 -> initialOf(x.album)
                    3 -> ym.format(java.util.Date(x.modified))
                    else -> initialOf(x.title)
                }
            }
            act.selectionPool = all
            setup(pageTop(act, tileLabel("songs"), countText(all)) { acts -> acts.add(selectButton(act), w = 48, h = 48) })
            val rows = ArrayList<Row>()
            val ctl = vbox(act)
            ctl.setPadding(dp(8), 0, 0, dp(8))
            ctl.add(chipRow(act, listOf(S(R.string.title_2), S(R.string.artist), S(R.string.album_2), S(R.string.date_added)).mapIndexed { i, l ->
                chip(act, l, sort == i) { sort = i; refresh(-1) }
            }), b = 4)
            ctl.add(playShuffle(act, { Actions.playTracks(all, 0) }, { Actions.playTracks(all, 0, true) }))
            rows.add(StaticRow(ctl, all.firstOrNull()?.let(lab).orEmpty()))
            all.forEachIndexed { i, x ->
                rows.add(TrackRowItem(act, x, { Actions.playTracks(all, i) }, sub = x.artist + S(R.string.separator) + x.album, label = lab(x)))
            }
            list.list.setPadding(dp(8), 0, dp(24), dp(24))
            list.setRows(rows)
            updateSelectionBar()
        }
    }

    /** 選ぶモードを始めるボタン */
    fun selectButton(act: LegacyActivity): View = iconButton(act, Icons.CheckCircle, S(R.string.select_songs)) { act.startSelection(null) }

    /** 曲の一覧(アルバムごとに見出しを付ける。本家の GroupedTracks) */
    class GroupedPage(act: LegacyActivity, private val title: String, private val source: () -> List<LibTrack>) : ListBased(act) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            val tracks = source()
            val lib = app.lib
            val groups = tracks.groupBy { lib.albumKeyOf(it.uri) ?: it.album }.toList()
            val flat = groups.flatMap { it.second }
            act.selectionPool = flat
            setup(pageTop(act, title, countText(tracks)) { acts -> acts.add(selectButton(act), w = 48, h = 48) })
            val rows = ArrayList<Row>()
            val ctl = FrameLayout(act)
            ctl.setPadding(dp(8), 0, 0, dp(8))
            ctl.add(playShuffle(act, { Actions.playTracks(flat, 0) }, { Actions.playTracks(flat, 0, true) }), w = WRAP)
            rows.add(StaticRow(ctl))
            for ((k, l) in groups) {
                val first = l.first()
                val h = hbox(act)
                h.setPadding(dp(8), dp(8), dp(8), dp(8))
                val art = ArtView(act, 6f)
                art.enabledArt = app.prefs.listArt
                h.add(art, w = 40, h = 40, r = 12)
                art.bind(ArtRef(lib.albumKeyOf(first.uri) ?: first.uri, first.uri, first.coverUri), dp(40))
                val c = vbox(act)
                c.add(text(act, first.album, Type.TitleSmall))
                c.add(text(act, artistOf(first), Type.BodySmall, Pal.onSurfaceVariant))
                h.add(c, w = 0, weight = 1f)
                h.tap(12f) { lib.albumKeyOf(first.uri)?.let { act.libPush("album:$it") } }
                rows.add(StaticRow(h))
                l.forEach { x -> rows.add(TrackRowItem(act, x, { Actions.playTracks(flat, flat.indexOf(x)) }, number = if (x.track > 0) "${x.track}" else "", showArt = false)) }
            }
            list.list.setPadding(dp(8), 0, dp(8), dp(24))
            list.setRows(rows)
            updateSelectionBar()
        }
    }

    class GenresPage(act: LegacyActivity) : ListBased(act) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            if (m == Bus.PLAYER) return
            val genres = app.lib.albums.flatMap { it.tracks }.groupBy { it.genre }.map { (g, l) -> Triple(g, l.size, l.map { it.album }.distinct().size) }
                .sortedWith(compareBy({ it.first.isEmpty() }, { it.first.lowercase() }))
            setup(pageTop(act, tileLabel("genres"), S(R.string.genres_2, genres.count { it.first.isNotEmpty() })))
            list.list.setPadding(dp(12), 0, dp(12), dp(24))
            list.setRows(genres.map { (g, n, al) ->
                GroupRowItem(g.ifEmpty { S(R.string.unknown_genre) }, S(R.string.albums_songs, al, n), Lead.Round(Icons.Genre)) { act.libPush("genre:$g") }
            })
        }
    }

    class YearsPage(act: LegacyActivity) : ListBased(act) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            if (m == Bus.PLAYER) return
            val years = app.lib.albums.groupBy { it.year }.map { (y, l) -> Triple(y, l.size, l.sumOf { it.tracks.size }) }
                .sortedWith(compareBy<Triple<String, Int, Int>> { it.first.isEmpty() }.thenByDescending { it.first })
            setup(pageTop(act, tileLabel("years")))
            list.list.setPadding(dp(12), 0, dp(12), dp(24))
            list.setRows(years.map { (y, n, t) ->
                GroupRowItem(y.ifEmpty { S(R.string.unknown_year) }, S(R.string.albums_songs, n, t), Lead.Round(Icons.DateRange)) { act.libPush("year:$y") }
            })
        }
    }

    // ---------------- フォルダ ----------------

    class FolderNode(val path: String, val name: String) {
        val children = java.util.TreeMap<String, FolderNode>(String.CASE_INSENSITIVE_ORDER)
        val albums = ArrayList<LibAlbum>()
        val albumKeys = HashSet<String>()
        val albumCount: Int get() = albumKeys.size
        var trackCount = 0
    }

    private var treeFor: List<LibAlbum>? = null
    private var treeCache: FolderNode? = null

    fun folderTree(albums: List<LibAlbum>): FolderNode {
        treeCache?.let { if (treeFor === albums) return it }
        val root = FolderNode("", "")
        for (a in albums) {
            for ((folder, tracks) in a.tracks.groupBy { Lib.trackFolder(it) }) {
                val parts = folder.split('/').filter { it.isNotEmpty() }
                var n = root
                n.albumKeys.add(a.key); n.trackCount += tracks.size
                for (p in parts) {
                    n = n.children.getOrPut(p) { FolderNode(if (n.path.isEmpty()) p else n.path + "/" + p, p) }
                    n.albumKeys.add(a.key); n.trackCount += tracks.size
                }
                if (n.albums.none { it.key == a.key }) n.albums.add(a)
            }
        }
        fun sortAlbums(n: FolderNode) {
            n.albums.sortBy { folderName(it).lowercase() }
            n.children.values.forEach { sortAlbums(it) }
        }
        sortAlbums(root)
        treeFor = albums
        treeCache = root
        return root
    }

    /** フォルダの中(エクスプローラーのように潜っていく。本家の FolderBrowsePage) */
    class FolderBrowsePage(act: LegacyActivity, private val path: String) : ListBased(act) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            if (m == Bus.PLAYER) return
            var node: FolderNode? = folderTree(app.lib.albums)
            for (p in path.split('/').filter { it.isNotEmpty() }) node = node?.children?.get(p)
            val parts = path.split('/').filter { it.isNotEmpty() }
            val top = vbox(act)
            top.add(pageTop(act, parts.lastOrNull() ?: "/", node?.let { S(R.string.albums_songs, it.albumCount, it.trackCount) }) { acts ->
                acts.add(iconButton(act, Icons.ListView, S(R.string.show_all_albums_in_this)) { act.libPush("folder:$path") }, w = 48, h = 48)
            })
            // 今いる場所(パンくず)。タップでその階層へ
            val crumbs = hbox(act)
            parts.forEachIndexed { i, p ->
                if (i > 0) crumbs.add(icon(act, Icons.ArrowRight, Pal.onSurfaceVariant, 16), w = 16, h = 16)
                val target = parts.take(i + 1).joinToString("/")
                val t = text(act, p, Type.LabelLarge, if (i == parts.lastIndex) Pal.onSurface else Pal.primary)
                t.setPadding(dp(6), dp(4), dp(6), dp(4))
                if (i < parts.lastIndex) t.tap(6f) { act.libReplaceTop("dir:$target") }
                crumbs.add(t, w = WRAP)
            }
            val sc = HorizontalScrollView(act)
            sc.isHorizontalScrollBarEnabled = false
            sc.addView(crumbs)
            top.add(sc, l = 12, r = 12, b = 2)
            setup(top)
            val n = node
            if (n == null) {
                list.setRows(listOf(StaticRow(emptyState(act, Icons.Folder, S(R.string.folder_not_found), ""))))
                return
            }
            val rows = ArrayList<Row>()
            n.children.values.forEach { c ->
                rows.add(GroupRowItem(c.name, S(R.string.albums_songs, c.albumCount, c.trackCount), Lead.Round(Icons.Folder)) { act.libPush("dir:" + c.path) })
            }
            n.albums.forEach { a -> rows.add(AlbumRowItem(act, a)) }
            list.list.setPadding(dp(12), 0, dp(12), dp(24))
            list.setRows(rows)
        }
    }

    /** 開いているフォルダ(フォルダ別のページ。戻ってきたときも同じように開いておく) */
    private val openFolders = HashSet<String>()
    private var openInit = false

    /** フォルダ別: フォルダの階層をたどって開いていく(本家の FoldersPage) */
    class FoldersPage(act: LegacyActivity) : ListBased(act) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            if (m == Bus.PLAYER) return
            val tree = folderTree(app.lib.albums)
            if (!openInit) {
                openInit = true
                if (tree.children.size == 1) openFolders.add(tree.children.values.first().path)
            }
            setup(pageTop(act, tileLabel("folders")))
            val rows = ArrayList<Row>()
            fun walk(n: FolderNode, depth: Int) {
                for (c in n.children.values) {
                    val open = c.path in openFolders
                    rows.add(FolderRow(act, c, depth, open) {
                        if (open) openFolders.remove(c.path) else openFolders.add(c.path)
                        refresh(-1)
                    })
                    if (open) walk(c, depth + 1)
                }
                for (a in n.albums) rows.add(AlbumRowItem(act, a, indentDp = depth * 20 + 20))
            }
            walk(tree, 0)
            list.list.setPadding(dp(8), 0, dp(12), dp(24))
            list.setRows(rows)
        }
    }

    private class FolderRow(val act: LegacyActivity, val f: FolderNode, val depth: Int, val open: Boolean, val onToggle: () -> Unit) : Row(FOLDER) {
        override fun create(ctx: android.content.Context): View = hbox(ctx)
        override fun bind(v: View) {
            val r = v as LinearLayout
            r.removeAllViews()
            r.setPadding(dp(depth * 20), dp(4), 0, dp(4))
            r.add(icon(act, if (open) Icons.ArrowDown else Icons.ArrowRight, Pal.onSurfaceVariant, 24).also {
                it.contentDescription = S(if (open) R.string.collapse else R.string.expand)
            }, w = 24, h = 24, r = 4)
            val lead = FrameLayout(act)
            GroupRowItem.leadView(act, Lead.Round(Icons.Folder, 40), lead)
            r.add(lead, w = 40, h = 40, r = 12)
            val c = vbox(act)
            c.add(text(act, f.name, Type.BodyLarge))
            c.add(text(act, S(R.string.albums_songs, f.albumCount, f.trackCount), Type.BodySmall, Pal.onSurfaceVariant))
            r.add(c, w = 0, weight = 1f)
            r.add(iconButton(act, Icons.Grid, S(R.string.open_folder)) { act.libPush("dir:" + f.path) }, w = 48, h = 48)
            r.tap(12f) { onToggle() }
        }
    }

    // ---------------- お気に入り・音質チェック・履歴・よく聴く曲 ----------------

    private var favTab = 0

    class FavoritesPage(act: LegacyActivity) : ListBased(act) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            val lib = app.lib
            val map = lib.trackMap
            val songs = app.store.favTracks.mapNotNull { map[it] }
            val albums = app.store.favAlbums.mapNotNull { lib.albumByKey(it) }
            val top = vbox(act)
            top.add(pageTop(act, tileLabel("favorites")))
            top.add(chipRow(act, listOf(
                chip(act, S(R.string.songs_2, songs.size), favTab == 0) { favTab = 0; refresh(-1) },
                chip(act, S(R.string.albums_2, albums.size), favTab == 1) { favTab = 1; refresh(-1) },
            )), l = 16, r = 16)
            setup(top)
            val rows = ArrayList<Row>()
            if (favTab == 0) {
                if (songs.isEmpty()) {
                    rows.add(StaticRow(emptyState(act, Icons.FavoriteBorder, S(R.string.no_favorite_songs_yet), S(R.string.add_songs_with_heart_button))))
                } else {
                    val c = FrameLayout(act); c.setPadding(dp(8), dp(8), 0, dp(8))
                    c.add(playShuffle(act, { Actions.playTracks(songs, 0) }, { Actions.playTracks(songs, 0, true) }), w = WRAP)
                    rows.add(StaticRow(c))
                    songs.forEachIndexed { i, t -> rows.add(TrackRowItem(act, t, { Actions.playTracks(songs, i) })) }
                }
                list.list.setPadding(dp(8), 0, dp(8), dp(24))
            } else {
                if (albums.isEmpty()) {
                    rows.add(StaticRow(emptyState(act, Icons.FavoriteBorder, S(R.string.no_favorite_albums_yet), S(R.string.add_albums_with_heart_button))))
                } else {
                    rows.add(StaticRow(View(act).apply { minimumHeight = dp(16) }))
                    rows.addAll(tileRows(act, albums))
                }
                list.list.setPadding(0, 0, 0, dp(24))
            }
            list.setRows(rows)
        }
    }

    class CheckPage(act: LegacyActivity) : ListBased(act) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            val map = app.lib.trackMap
            val songs = app.store.checkTracks.mapNotNull { map[it] }
            setup(pageTop(act, tileLabel("check"), S(R.string.sound_check_desc)) { acts ->
                if (songs.isNotEmpty()) acts.add(iconButton(act, Icons.Delete, S(R.string.clear_sound_check_title)) {
                    val d = UDialog(act).title(S(R.string.clear_sound_check_title)).message(S(R.string.clear_sound_check_body))
                    d.button(S(R.string.cancel)) { d.dismiss() }
                    d.button(S(R.string.clear)) { Actions.setCheck(act, songs, false); d.dismiss() }
                    d.show()
                }, w = 48, h = 48)
            })
            val rows = ArrayList<Row>()
            if (songs.isEmpty()) {
                rows.add(StaticRow(emptyState(act, Icons.Headphones, S(R.string.no_sound_check_songs_yet), S(R.string.add_sound_check_hint))))
            } else {
                val c = FrameLayout(act); c.setPadding(dp(8), dp(8), 0, dp(8))
                c.add(playShuffle(act, { Actions.playTracks(songs, 0) }, { Actions.playTracks(songs, 0, true) }), w = WRAP)
                rows.add(StaticRow(c))
                songs.forEachIndexed { i, t -> rows.add(TrackRowItem(act, t, { Actions.playTracks(songs, i) })) }
            }
            list.list.setPadding(dp(8), 0, dp(8), dp(24))
            list.setRows(rows)
        }
    }

    class HistoryPage(act: LegacyActivity) : ListBased(act) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            val map = app.lib.trackMap
            val items = app.store.history.mapNotNull { h -> map[h.uri]?.let { it to h.time } }
            val tracks = items.map { it.first }
            setup(pageTop(act, tileLabel("history"), S(R.string.songs_4, items.size)) { acts ->
                if (items.isNotEmpty()) acts.add(iconButton(act, Icons.Delete, S(R.string.clear_history)) {
                    val d = UDialog(act).title(S(R.string.clear_play_history)).message(S(R.string.this_resets_recently_played_and))
                    d.button(S(R.string.cancel)) { d.dismiss() }
                    d.button(S(R.string.clear)) { app.store.clearHistory(); d.dismiss() }
                    d.show()
                }, w = 48, h = 48)
            })
            if (items.isEmpty()) {
                list.setRows(listOf(StaticRow(emptyState(act, Icons.History, S(R.string.nothing_played_yet), ""))))
                return
            }
            val now = System.currentTimeMillis()
            list.list.setPadding(dp(8), 0, dp(8), dp(24))
            list.setRows(items.mapIndexed { i, (t, time) ->
                TrackRowItem(act, t, { Actions.playTracks(tracks, i) }, sub = t.artist,
                    trailing = android.text.format.DateUtils.getRelativeTimeSpanString(time, now, android.text.format.DateUtils.MINUTE_IN_MILLIS,
                        android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString())
            })
        }
    }

    class TopPage(act: LegacyActivity) : ListBased(act) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            val map = app.lib.trackMap
            val items = app.store.counts.entries.filter { it.value > 0 }.sortedByDescending { it.value }.mapNotNull { e -> map[e.key]?.let { it to e.value } }.take(100)
            val tracks = items.map { it.first }
            setup(pageTop(act, tileLabel("top")))
            if (items.isEmpty()) {
                list.setRows(listOf(StaticRow(emptyState(act, Icons.Trending, S(R.string.nothing_played_yet), ""))))
                return
            }
            val rows = ArrayList<Row>()
            val c = FrameLayout(act); c.setPadding(dp(8), 0, 0, dp(8))
            c.add(playShuffle(act, { Actions.playTracks(tracks, 0) }, { Actions.playTracks(tracks, 0, true) }), w = WRAP)
            rows.add(StaticRow(c))
            items.forEachIndexed { i, (t, n) -> rows.add(TrackRowItem(act, t, { Actions.playTracks(tracks, i) }, number = "${i + 1}", showArt = true, trailing = S(R.string.label, n))) }
            list.list.setPadding(dp(8), 0, dp(8), dp(24))
            list.setRows(rows)
        }
    }

    // ---------------- プレイリスト ----------------

    class PlaylistsPage(act: LegacyActivity) : ListBased(act) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            val map = app.lib.trackMap
            val create = { Actions.nameDialog(act, S(R.string.new_playlist), "") { Actions.createPlaylist(act, it, emptyList()) } }
            setup(pageTop(act, tileLabel("playlists")) { acts -> acts.add(iconButton(act, Icons.Add, S(R.string.new_playlist)) { create() }, w = 48, h = 48) })
            val pls = app.store.playlists
            if (pls.isEmpty()) {
                list.setRows(listOf(StaticRow(emptyState(act, Icons.Queue, S(R.string.no_playlists_yet), S(R.string.create_one_from_song_or), S(R.string.new_playlist)) { create() })))
                return
            }
            list.list.setPadding(dp(12), 0, dp(12), dp(24))
            list.setRows(pls.map { p ->
                val ts = p.uris.mapNotNull { map[it] }
                val first = ts.firstOrNull()
                val lead = if (first != null) Lead.Art(ArtRef(app.lib.albumKeyOf(first.uri) ?: first.uri, first.uri, first.coverUri), 8f) else Lead.Round(Icons.Queue)
                GroupRowItem(p.name, countText(ts), lead) { act.libPush("playlist:" + p.id) }
            })
        }
    }

    class PlaylistPage(act: LegacyActivity, private val id: String) : ListBased(act) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            val p = app.store.playlists.firstOrNull { it.id == id }
            if (p == null) { act.libPop(); return }
            val map = app.lib.trackMap
            val entries = p.uris.mapIndexedNotNull { i, u -> map[u]?.let { i to it } }
            val tracks = entries.map { it.second }
            setup(pageTop(act, p.name, countText(tracks)) { acts ->
                acts.add(iconButton(act, Icons.Edit, S(R.string.rename)) {
                    Actions.nameDialog(act, S(R.string.rename), p.name) { app.store.renamePlaylist(p.id, it) }
                }, w = 48, h = 48)
                acts.add(iconButton(act, Icons.Delete, S(R.string.delete)) {
                    val d = UDialog(act).title(S(R.string.delete_2, p.name)).message(S(R.string.song_files_wont_be_deleted))
                    d.button(S(R.string.cancel)) { d.dismiss() }
                    d.button(S(R.string.delete)) { d.dismiss(); app.store.deletePlaylist(p.id) }
                    d.show()
                }, w = 48, h = 48)
            })
            val rows = ArrayList<Row>()
            if (tracks.isEmpty()) {
                val t = text(act, S(R.string.use_add_to_playlist_in), Type.BodyMedium, Pal.onSurfaceVariant, lines = 0)
                t.setPadding(dp(16), dp(16), dp(16), dp(16))
                rows.add(StaticRow(t))
            } else {
                val c = FrameLayout(act); c.setPadding(dp(12), 0, 0, dp(8))
                c.add(playShuffle(act, { Actions.playTracks(tracks, 0) }, { Actions.playTracks(tracks, 0, true) }), w = WRAP)
                rows.add(StaticRow(c))
            }
            entries.forEachIndexed { i, (pos, t) ->
                rows.add(TrackRowItem(act, t, { Actions.playTracks(tracks, i) }, playlist = p.id to pos, handle = { h ->
                    Drag.attach(list.list, h, i + 1) { from, to ->
                        val now = app.store.playlists.firstOrNull { it.id == id } ?: return@attach
                        val m2 = app.lib.trackMap
                        val posList = now.uris.indices.filter { m2[now.uris[it]] != null }
                        val a = posList.getOrNull(from - 1)
                        val b = posList.getOrNull(to - 1)
                        if (a != null && b != null) app.store.movePlaylistItem(id, a, b)
                    }
                }))
            }
            list.list.setPadding(dp(4), 0, dp(8), dp(24))
            list.setRows(rows)
        }
    }

    // ---------------- 検索 ----------------

    private var lastQuery = ""

    class SearchPage(act: LegacyActivity) : ListBased(act) {
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        private var gen = 0
        private var index: Triple<List<Pair<String, String>>, List<Pair<LibAlbum, String>>, List<Pair<LibTrack, String>>>? = null
        private var indexFor: List<LibAlbum>? = null
        private val field = com.uyatame.ucrt.legacy.ui.field(act, lastQuery, S(R.string.songs_albums_artists))

        init {
            val top = hbox(act)
            top.setPadding(dp(4), dp(8), dp(12), 0)
            top.add(iconButton(act, Icons.ArrowBack, S(R.string.back), Pal.onSurface) { act.libPop() }, w = 48, h = 48)
            field.bgd(rounded(Pal.containerHigh, 28f))
            field.setPadding(dp(20), dp(12), dp(20), dp(12))
            top.add(field, w = 0, weight = 1f)
            setup(top)
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { lastQuery = s?.toString().orEmpty(); search() }
            })
            field.requestFocus()
            main.postDelayed({
                (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(field, 0)
            }, 200)
            search()
        }

        override val mask = Bus.LIBRARY or Bus.PLAYER

        override fun refresh(m: Int) { if (m and Bus.LIBRARY != 0) { index = null; search() } else list.adapter.notifyDataSetChanged() }

        override fun dispose() {
            (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(field.windowToken, 0)
        }

        private fun search() {
            val key = norm(lastQuery.trim())
            val my = ++gen
            if (key.isEmpty()) {
                val t = text(act, S(R.string.search_ignores_kana_and_character), Type.BodySmall, Pal.onSurfaceVariant, lines = 0)
                t.setPadding(dp(24), dp(24), dp(24), dp(24))
                list.setRows(listOf(StaticRow(t)))
                return
            }
            val albums = app.lib.albums
            Thread({
                if (indexFor !== albums || index == null) {
                    index = Triple(
                        albums.map { it.artist }.distinct().map { it to norm(it) },
                        albums.map { it to (norm(it.title) + "\u0000" + norm(it.artist)) },
                        albums.flatMap { it.tracks }.map { it to (norm(it.title) + "\u0000" + norm(it.artist)) },
                    )
                    indexFor = albums
                }
                val (ia, ial, isg) = index!!
                val artists = ia.filter { it.second.contains(key) }.take(10).map { it.first }
                val al = ial.filter { it.second.contains(key) }.take(30).map { it.first }
                val songs = isg.filter { it.second.contains(key) }.take(200).map { it.first }
                main.post { if (my == gen) show(artists, al, songs) }
            }, "ucrt-search").start()
        }

        private fun show(artists: List<String>, al: List<LibAlbum>, songs: List<LibTrack>) {
            if (artists.isEmpty() && al.isEmpty() && songs.isEmpty()) {
                list.setRows(listOf(StaticRow(emptyState(act, Icons.Search, S(R.string.no_results), ""))))
                return
            }
            val rows = ArrayList<Row>()
            if (artists.isNotEmpty()) {
                rows.add(StaticRow(headingView(act, S(R.string.artists), padStart = 8)))
                artists.forEach { n -> rows.add(GroupRowItem(n, "", Lead.Initial(n)) { act.libPush("artist:$n") }) }
            }
            if (al.isNotEmpty()) {
                rows.add(StaticRow(headingView(act, S(R.string.albums), padStart = 8)))
                al.forEach { a -> rows.add(AlbumRowItem(act, a)) }
            }
            if (songs.isNotEmpty()) {
                rows.add(StaticRow(headingView(act, S(R.string.songs_3), padStart = 8)))
                songs.forEachIndexed { i, t -> rows.add(TrackRowItem(act, t, { Actions.playTracks(songs, i) }, sub = t.artist + S(R.string.separator) + t.album)) }
            }
            list.list.setPadding(dp(8), 0, dp(8), dp(24))
            list.setRows(rows)
        }
    }

    // ---------------- アルバム ----------------

    class AlbumPage(act: LegacyActivity, private val key: String) : ListBased(act) {
        init { refresh(-1) }

        override fun refresh(m: Int) {
            val a = app.lib.albumByKey(key) ?: Edit.renamed(key)?.let { app.lib.albumByKey(it) }
            if (a == null) {
                setup(pageTop(act, ""))
                list.setRows(listOf(StaticRow(emptyState(act, Icons.Album, S(R.string.not_found), S(R.string.library_may_still_be_loading)))))
                return
            }
            act.selectionPool = a.tracks
            setup(null)
            val discs = a.tracks.groupBy { it.disc }.toList().sortedBy { it.first }
            val fav = app.store.isFavAlbum(a.key)
            val rows = ArrayList<Row>()
            // 上の帯
            val bar = hbox(act)
            bar.setPadding(dp(4), dp(4), dp(4), dp(4))
            bar.add(iconButton(act, Icons.ArrowBack, S(R.string.back), Pal.onSurface) { act.libPop() }, w = 48, h = 48)
            bar.add(View(act), w = 0, h = 1, weight = 1f)
            if (Edit.saving) {
                bar.add(text(act, S(R.string.saving), Type.LabelLarge), w = WRAP, r = 12)
            } else {
                bar.add(iconButton(act, Icons.Search, S(R.string.get_track_info)) { Edit.lookup(act, a) }, w = 48, h = 48)
                bar.add(iconButton(act, Icons.Edit, S(R.string.edit_track_info)) { Edit.editAlbum(act, a) }, w = 48, h = 48)
                bar.add(iconButton(act, Icons.MoreVert, S(R.string.more_options)) { Actions.albumMenu(act, a) }, w = 48, h = 48)
            }
            rows.add(StaticRow(bar))
            // ジャケットと曲の情報
            val head = vbox(act, Gravity.CENTER_HORIZONTAL)
            head.setPadding(dp(24), 0, dp(24), 0)
            val art = ArtView(act, 16f)
            val w = (LibraryPages.widthDp(act) * 0.66f).toInt()
            head.add(art, w = w, h = w)
            art.bind(Actions.albumArt(a), dp(w))
            val t = text(act, a.title, Type.HeadlineSmall, Pal.onSurface, lines = 0)
            t.gravity = Gravity.CENTER
            head.add(t, t = 20)
            val ar = text(act, a.artist, Type.TitleMedium, Pal.primary)
            ar.setPadding(dp(6), dp(2), dp(6), dp(2))
            ar.tap(8f) { act.libPush("artist:" + a.artist) }
            head.add(ar, w = WRAP)
            val line = hbox(act, Gravity.CENTER)
            line.add(com.uyatame.ucrt.legacy.ui.badge(act, Quality.badge(a), Quality.classOf(a) == QualityClass.HiRes), w = WRAP, r = 8)
            line.add(text(act, (if (a.year.isNotEmpty()) a.year + S(R.string.separator) else "") + countText(a.tracks), Type.BodySmall, Pal.onSurfaceVariant), w = WRAP)
            head.add(line, w = WRAP, t = 4)
            val ctl = hbox(act)
            ctl.add(playShuffle(act, { Actions.playAlbum(a, 0) }, { Actions.playAlbum(a, 0, true) }), w = WRAP, r = 4)
            ctl.add(iconButton(act, if (fav) Icons.Favorite else Icons.FavoriteBorder, S(if (fav) R.string.remove_from_favorites else R.string.add_to_favorites),
                if (fav) Pal.primary else Pal.onSurfaceVariant) { Actions.toggleFavAlbum(act, a.key) }, w = 48, h = 48)
            head.add(ctl, w = WRAP, t = 16, b = 16)
            rows.add(StaticRow(head))
            for ((disc, l) in discs) {
                if (discs.size > 1) {
                    val dt = text(act, "Disc $disc", Type.TitleSmall, Pal.primary).bold()
                    dt.setPadding(dp(24), dp(12), 0, dp(4))
                    rows.add(StaticRow(dt))
                }
                l.forEach { x ->
                    val i = a.tracks.indexOf(x)
                    rows.add(TrackRowItem(act, x, { Actions.playAlbum(a, i) }, number = if (x.track > 0) "${x.track}" else "${i + 1}", showArt = false,
                        sub = if (x.artist != a.artist) x.artist else ""))
                }
            }
            list.list.setPadding(dp(8), 0, dp(8), dp(24))
            list.setRows(rows)
            updateSelectionBar()
        }
    }
}

/**
 * 一覧の行をつまみでドラッグして並べ替える(本家の DragHandle)。
 * つまみを押したまま上下に動かすと、指の下の行と入れ替わる
 */
object Drag {
    /** ドラッグ中の行の位置(-1 = ドラッグしていない)。行の部品は使い回されるので、部品ではなくここで覚える */
    private var cur = -1

    fun attach(list: android.widget.ListView, handle: View, index: Int, onMove: (Int, Int) -> Unit) {
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { cur = index; list.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    if (cur >= 0) {
                        val loc = IntArray(2)
                        list.getLocationOnScreen(loc)
                        val y = (e.rawY - loc[1]).toInt()
                        val p = list.pointToPosition(dp(40), y)
                        if (p != android.widget.AdapterView.INVALID_POSITION && p != cur && p >= 1) {
                            onMove(cur, p)
                            cur = p
                        }
                    }
                }
                else -> { cur = -1; list.requestDisallowInterceptTouchEvent(false) }
            }
            true
        }
    }
}
