package com.uyatame.ucrt.legacy

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.ui.ArtView
import com.uyatame.ucrt.legacy.ui.Btn
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.ProgressBarView
import com.uyatame.ucrt.legacy.ui.Sheet
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.bold
import com.uyatame.ucrt.legacy.ui.button
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.enabled
import com.uyatame.ucrt.legacy.ui.field
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.rounded
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import com.uyatame.ucrt.library.LibAlbum
import com.uyatame.ucrt.library.UNKNOWN_ALBUM
import com.uyatame.ucrt.library.UNKNOWN_ARTIST
import com.uyatame.ucrt.meta.MusicBrainz
import com.uyatame.ucrt.meta.ReleaseCandidate
import com.uyatame.ucrt.meta.SourceResult
import com.uyatame.ucrt.tags.TagEditor
import com.uyatame.ucrt.tags.TagExtra
import com.uyatame.ucrt.tags.TagValues
import com.uyatame.ucrt.tags.prepareCoverImage

/** 曲情報の候補の 1 行(本家の CandidateRow)。CD の画面とライブラリの両方で使う */
fun candidateRow(act: LegacyActivity, c: ReleaseCandidate, cdTracks: Int, enabled: Boolean, inUse: Boolean, onClick: () -> Unit): View {
    val col = vbox(act)
    col.setPadding(dp(16), dp(10), dp(16), dp(10))
    if (inUse) col.bgd(rounded(Pal.secondaryContainer, 12f))
    val over = hbox(act)
    over.add(text(act, "MusicBrainz", Type.LabelSmall, Pal.onSurfaceVariant), w = WRAP, r = 6)
    if (inUse) over.add(text(act, S(R.string.in_use), Type.LabelSmall, Pal.secondary).bold(), w = WRAP, r = 6)
    if (c.exact) over.add(text(act, S(R.string.disc_match), Type.LabelSmall, Pal.primary).bold(), w = WRAP)
    col.add(over)
    col.add(text(act, c.meta.title.ifBlank { "-" }, Type.BodyLarge, Pal.onSurface, lines = 2))
    col.add(text(act, c.meta.artist, Type.BodyMedium, Pal.onSurfaceVariant))
    col.add(text(act, c.summary, Type.BodySmall, Pal.onSurfaceVariant, lines = 2))
    if (c.trackCount != cdTracks && c.trackCount > 0) col.add(text(act, S(R.string.track_count_differs_cd_has, cdTracks), Type.LabelSmall, Pal.error))
    if (enabled) col.tap(12f) { onClick() } else col.alpha = 0.6f
    return col
}

/**
 * 取り込み済みのアルバムの曲情報を直す(本家の EditTagsPage・LookupSheet・saveAlbumTags)。
 * ファイルのタグを書き換えて、ライブラリを読み直す
 */
object Edit {
    private val app get() = LegacyApp.app
    private val main = Handler(Looper.getMainLooper())

    @Volatile var saving = false; private set

    /** 曲情報を変えるとアルバムのまとまり(キー)が変わるので、古いキーから新しいアルバムを探す */
    private var renamedFrom: String? = null
    private var renamedTo: Pair<String, String>? = null

    fun renamed(key: String): String? {
        if (key != renamedFrom) return null
        val (t, a) = renamedTo ?: return null
        val al = app.lib.albums.firstOrNull { it.title == t && it.artist == a } ?: app.lib.albums.firstOrNull { it.title == t }
        return al?.key
    }

    // ================= 書き込み =================

    fun saveAlbumTags(
        a: LibAlbum, album: String, albumArtist: String, year: String,
        titles: List<String>, artists: List<String>, cover: ByteArray?, extras: List<TagExtra?>? = null,
    ) {
        if (saving) return
        saving = true
        renamedFrom = a.key
        renamedTo = album.trim() to albumArtist.trim()
        Bus.emit(Bus.LIBRARY)
        Thread({
            var msg: String
            try {
                val trackTotal = maxOf(a.tracks.size, a.tracks.maxOf { it.track })
                val discTotal = a.tracks.maxOf { it.disc }.coerceAtLeast(1)
                var ok = 0; var skipped = 0; var failed = 0
                a.tracks.forEachIndexed { i, t ->
                    try {
                        val v = TagValues(
                            titles.getOrElse(i) { t.title }.trim(), artists.getOrElse(i) { t.artist }.trim(),
                            album.trim(), albumArtist.trim(), year.trim(), t.track, trackTotal, t.disc, discTotal,
                            extras?.getOrNull(i) ?: TagExtra(),
                        )
                        if (TagEditor.edit(app, Uri.parse(t.uri), v, cover)) ok++ else skipped++
                    } catch (e: Exception) {
                        failed++
                        AppLog.log(S(R.string.failed_to_save_tags, t.title, e.message))
                    }
                }
                Art.forget(a.key)
                a.tracks.forEach { Art.forget("t:" + it.uri) }
                msg = buildString {
                    append(S(R.string.saved_track_info_for_tracks, ok))
                    if (skipped > 0) append(S(R.string.skipped_tracks_in_formats_that, skipped))
                    if (failed > 0) append(S(R.string.tracks_failed, failed))
                }
            } catch (e: Exception) {
                msg = S(R.string.could_not_save, e.message)
            }
            main.post {
                saving = false
                LegacyActivity.current?.snack(msg)
                app.lib.refresh(false)
                Bus.emit(Bus.LIBRARY or Bus.ART)
            }
        }, "ucrt-tag-save").start()
    }

    // ================= 編集の画面 =================

    fun editAlbum(act: LegacyActivity, a: LibAlbum) {
        if (saving) return
        val form = TagForm(
            a.title, a.artist, a.first.year, a.first.genre,
            a.tracks.mapIndexed { i, t ->
                TrackForm(trackLabel(if (t.track > 0) t.track else i + 1), t.title, t.artist, lengthText(t.durationMs), TagExtra.supported(t.uri))
            },
        )
        var newCover: ByteArray? = null
        var extras: List<TagExtra?>? = null
        // ジャケットとその変更のボタン
        val coverRow = hbox(act)
        val art = ArtView(act, 8f)
        art.bind(Actions.albumArt(a), dp(72))
        coverRow.add(art, w = 72, h = 72, r = 12)
        lateinit var pickBtn: LinearLayout
        pickBtn = button(act, S(R.string.change_cover), Btn.Tonal) {
            act.pickImage { u ->
                Thread({
                    val b = act.readImage(u)?.let { runCatching { prepareCoverImage(it) }.getOrNull() }
                    main.post {
                        if (b == null) { act.snack(S(R.string.could_not_load_image)); return@post }
                        newCover = b
                        art.setBitmap(com.uyatame.ucrt.library.ArtSource.decode(b, dp(72), true))
                        (pickBtn.getChildAt(pickBtn.childCount - 1) as? android.widget.TextView)?.text = S(R.string.new_image_selected)
                    }
                }, "ucrt-cover-read").start()
            }
        }
        coverRow.add(pickBtn, w = WRAP)
        val unsupported = a.tracks.count { !TagEditor.supported(it.uri) }
        lateinit var ed: TagEditView
        ed = TagEditView(
            act, S(R.string.edit_track_info), form,
            onCancel = { act.closeOverlay(ed.view) },
            onSave = {
                val read = extras
                saveAlbumTags(
                    a, form.album, form.albumArtist, form.date,
                    form.tracks.map { it.title }, form.tracks.map { it.artist }, newCover,
                    form.tracks.mapIndexed { i, t -> if (read?.getOrNull(i) == null) null else t.extra(form.genre) },
                )
                act.closeOverlay(ed.view)
            },
            note = if (unsupported > 0) S(R.string.songs_cant_be_edited_because, unsupported) else null,
            cover = coverRow,
        )
        ed.loading = true
        act.openOverlay(ed.view) { act.closeOverlay(ed.view) }
        // 曲ごとの詳しい情報(今の値)を読んでから入力できるようにする。読めなかった曲は書き換えない
        Thread({
            val list = a.tracks.map { TagExtra.read(app, it.uri) }
            main.post {
                list.forEachIndexed { i, e ->
                    val f = form.tracks.getOrNull(i) ?: return@forEachIndexed
                    if (e == null) return@forEachIndexed
                    f.composer = e.composer.orEmpty(); f.lyricist = e.lyricist.orEmpty()
                    f.comment = e.comment.orEmpty(); f.lyrics = e.lyrics.orEmpty()
                }
                list.firstOrNull { it != null }?.genre?.takeIf { it.isNotBlank() }?.let { form.genre = it }
                extras = list
                ed.loading = false
                ed.reload()
            }
        }, "ucrt-tag-read").start()
    }

    // ================= インターネットから探す =================

    fun lookup(act: LegacyActivity, a: LibAlbum) {
        if (saving) return
        val n = a.tracks.size
        val sheet = Sheet(act, full = true)
        val body = sheet.body
        body.setPadding(dp(16), 0, dp(16), dp(32))
        val top = hbox(act)
        top.add(text(act, S(R.string.get_track_info), Type.TitleLarge), w = 0, weight = 1f)
        top.add(button(act, S(R.string.close), Btn.Text) { sheet.dismiss() }, w = WRAP)
        body.add(top)
        body.add(text(act, S(R.string.finds_matching_cds_by_song, n), Type.BodySmall, Pal.onSurfaceVariant, lines = 0))
        val albumF = field(act, a.title.takeIf { it != UNKNOWN_ALBUM }.orEmpty(), S(R.string.album))
        val artistF = field(act, a.artist.takeIf { it != UNKNOWN_ARTIST }.orEmpty(), S(R.string.artist))
        body.add(albumF, t = 8, b = 8)
        body.add(artistF, b = 8)
        val btns = hbox(act)
        val progress = ProgressBarView(act, -1f, 4)
        val message = text(act, "", Type.BodySmall, Pal.onSurface, lines = 0)
        val results = vbox(act)
        var busy = false
        var applying = false
        lateinit var searchBtn: View
        lateinit var lengthBtn: View
        fun state(msg: String?) {
            progress.visibility = if (busy || applying) View.VISIBLE else View.GONE
            searchBtn.enabled(!busy && (albumF.text.isNotBlank() || artistF.text.isNotBlank()))
            lengthBtn.enabled(!busy)
            if (msg != null) message.text = msg
            message.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
        }
        fun show(list: List<ReleaseCandidate>) {
            results.removeAllViews()
            list.forEach { c ->
                results.add(candidateRow(act, c, n, !applying, false) {
                    UDialog(act).title(S(R.string.overwrite_with_these_details))
                        .message("${c.meta.title}\n${c.meta.artist}\n\n" + S(R.string.this_rewrites_title_artist_album, n))
                        .apply {
                            button(S(R.string.cancel)) { dismiss() }
                            button(S(R.string.overwrite)) {
                                dismiss()
                                applying = true
                                state(null)
                                apply(a, c) { ok -> applying = false; if (ok) sheet.dismiss() else state(null) }
                            }
                        }.show()
                })
            }
        }
        fun run(work: () -> Pair<List<ReleaseCandidate>, String>) {
            if (busy) return
            busy = true
            state(S(R.string.searching))
            Thread({
                val r = runCatching { work() }.getOrElse { emptyList<ReleaseCandidate>() to S(R.string.search_failed_due_to_network) }
                main.post { busy = false; show(r.first); state(r.second) }
            }, "ucrt-lookup").start()
        }
        fun byLength() = run {
            MusicBrainz.version = SettingsPages.version(app)
            val out = ArrayList<SourceResult>()
            if (a.tracks.all { it.durationMs > 0 }) {
                val offs = ArrayList<Int>()
                var pos = 150
                for (t in a.tracks) { offs.add(pos); pos += (t.durationMs * 75 / 1000).toInt() }
                val toc = (listOf(1, n, pos) + offs).joinToString("+")
                out.add(MusicBrainz.lookup(MusicBrainz.DiscToc("-", toc, n)))
            }
            val known = a.title != UNKNOWN_ALBUM && a.title != "不明なアルバム" && a.title != "Unknown album"
            if (known) out.add(MusicBrainz.search(a.title, if (a.artist == UNKNOWN_ARTIST) "" else a.artist, n))
            out.filter { it.error != null }.forEach { AppLog.log("MusicBrainz: ${it.error}") }
            val seen = HashSet<String>()
            val list = out.flatMap { it.candidates }
                .filter { seen.add((it.meta.releaseId ?: it.meta.title) + "/" + it.meta.discNo) }
                .sortedWith(compareByDescending<ReleaseCandidate> { it.trackCount == n }.thenByDescending { it.loader == null })
            list to when {
                list.isNotEmpty() -> S(R.string.matches_matching_track_counts_first, list.size)
                out.isNotEmpty() && out.all { it.error != null } -> S(R.string.search_failed_due_to_network)
                else -> S(R.string.nothing_found_try_searching_by)
            }
        }
        fun byName() {
            val al = albumF.text.toString(); val ar = artistF.text.toString()
            if (al.isBlank() && ar.isBlank()) return
            act.hideKeyboard(albumF)
            run {
                MusicBrainz.version = SettingsPages.version(app)
                val r = MusicBrainz.search(al, ar, n)
                r.candidates to when {
                    r.error != null -> S(R.string.search_failed, r.error)
                    r.candidates.isEmpty() -> S(R.string.nothing_found_try_different_spelling_2)
                    else -> S(R.string.found_matching_track_counts_first_2, r.candidates.size)
                }
            }
        }
        searchBtn = button(act, S(R.string.search_by_name), Btn.Tonal, Icons.Search) { byName() }
        lengthBtn = button(act, S(R.string.match_by_length), Btn.Text) { byLength() }
        btns.add(searchBtn, w = WRAP, r = 8)
        btns.add(lengthBtn, w = WRAP)
        body.add(btns)
        body.add(progress, t = 8, b = 8)
        body.add(message, b = 4)
        body.add(results)
        albumF.setOnEditorActionListener { _, _, _ -> byName(); true }
        artistF.setOnEditorActionListener { _, _, _ -> byName(); true }
        val w = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { state(null) }
        }
        albumF.addTextChangedListener(w); artistF.addTextChangedListener(w)
        state("")
        sheet.show()
        byLength()
    }

    /** 選んだ候補の曲情報(とジャケット)を、取り込み済みのファイルに書き込む */
    private fun apply(a: LibAlbum, c: ReleaseCandidate, done: (Boolean) -> Unit) {
        Thread({
            val m = c.loader?.let { runCatching { it() }.getOrNull() } ?: c.meta
            if (m.tracks.isEmpty()) {
                main.post { LegacyActivity.current?.snack(S(R.string.could_not_load_track_list)); done(false) }
                return@Thread
            }
            val cover = m.releaseId?.let { id -> MusicBrainz.cover(id).data?.let { runCatching { prepareCoverImage(it) }.getOrNull() } }
            if (m.tracks.size != a.tracks.size) AppLog.log(S(R.string.track_counts_differ_files_match, a.tracks.size, m.tracks.size))
            val titles = a.tracks.mapIndexed { i, t -> m.tracks.getOrNull(i)?.title?.takeIf { it.isNotBlank() } ?: t.title }
            val artists = a.tracks.mapIndexed { i, t -> m.tracks.getOrNull(i)?.artist?.takeIf { it.isNotBlank() } ?: m.artist.ifBlank { t.artist } }
            main.post {
                saveAlbumTags(a, m.title, m.artist, m.date, titles, artists, cover)
                done(true)
            }
        }, "ucrt-lookup-apply").start()
    }
}
