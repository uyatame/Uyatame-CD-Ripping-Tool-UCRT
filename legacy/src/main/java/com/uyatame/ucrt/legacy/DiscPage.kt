package com.uyatame.ucrt.legacy

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.data.AudioFormat
import com.uyatame.ucrt.legacy.ui.ArtView
import com.uyatame.ucrt.legacy.ui.Btn
import com.uyatame.ucrt.legacy.ui.CheckView
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.MATCH
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.ProgressBarView
import com.uyatame.ucrt.legacy.ui.RadioView
import com.uyatame.ucrt.legacy.ui.Sheet
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.addDivider
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.bold
import com.uyatame.ucrt.legacy.ui.button
import com.uyatame.ucrt.legacy.ui.card
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.enabled
import com.uyatame.ucrt.legacy.ui.field
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.iconButton
import com.uyatame.ucrt.legacy.ui.rounded
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import com.uyatame.ucrt.library.UNKNOWN_ALBUM
import com.uyatame.ucrt.library.UNKNOWN_ARTIST
import com.uyatame.ucrt.meta.ReleaseCandidate
import com.uyatame.ucrt.usb.TocTrack

/** CD の長さの表示(セクター数) */
fun fmtSectors(sectors: Int): String = "%d:%02d".format(sectors / 75 / 60, sectors / 75 % 60)

/**
 * ディスクのタブ(本家の CdScreen)。
 * ドライブ・ディスクの状態、アルバムの情報(ジャケット・取り込み・再生・音質・コピー・編集・候補)、曲の一覧
 */
class DiscPage(act: LegacyActivity) : Page(act) {
    private val app get() = LegacyApp.app
    private val p get() = app.prefs
    private val frame = FrameLayout(act)
    private val scroll = ScrollView(act)
    private val col = vbox(act)
    override val view: View get() = frame
    override val mask = Bus.DISC or Bus.RIP or Bus.PLAYER or Bus.SETTINGS

    /** 曲ごとの状態の表示(取り込み中に、そこだけ書き換える) */
    private class TrackViews(val status: TextView, val bar: ProgressBarView, val row: View, val title: TextView, val check: CheckView)
    private val trackViews = HashMap<Int, TrackViews>()
    private var chooser: Chooser? = null
    private var sig = ""

    init {
        scroll.isFillViewport = true
        col.setPadding(dp(16), 0, dp(16), dp(24))
        scroll.addView(col)
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        refresh(-1)
    }

    /** 作り直しがいるかどうかの目印(取り込みの進み具合だけなら作り直さない) */
    private fun signature(): String = listOf(
        Disc.connected, Disc.discBusy, Disc.discText, Disc.status, Disc.tracks.size, Disc.meta?.hashCode(), Disc.metaStatus,
        Disc.metaSearching, Disc.cover?.hashCode(), Disc.busy, MbUpload.busy, p.outputDir, SettingsPages.qualityLabel(),
        Disc.selected.entries.sortedBy { it.key }.joinToString { "${it.key}${it.value}" }, app.player.current?.uri, Disc.driveName,
    ).joinToString("|")

    override fun refresh(m: Int) {
        if (Disc.showChooser && chooser == null && Disc.audioTracks.isNotEmpty()) chooser = Chooser(act) { chooser = null }.also { it.show() }
        chooser?.refresh()
        val s = signature()
        if (s == sig && m != -1) { updateTracks(); return }
        sig = s
        val y = scroll.scrollY
        build()
        scroll.post { scroll.scrollTo(0, y) }
    }

    override fun dispose() {
        chooser?.dismiss()
        chooser = null
    }

    override fun scrollState(): IntArray = intArrayOf(scroll.scrollY)
    override fun restoreScroll(p: IntArray?) { val y = p?.firstOrNull() ?: return; scroll.post { scroll.scrollTo(0, y) } }

    private fun build() {
        col.removeAllViews()
        trackViews.clear()
        // 見出し
        val head = hbox(act)
        head.setPadding(dp(4), dp(12), 0, dp(4))
        head.add(text(act, S(R.string.disc), Type.HeadlineMedium), w = 0, weight = 1f)
        head.add(iconButton(act, Icons.Refresh, S(R.string.reload_disc), Pal.onSurfaceVariant) { Disc.refreshDisc() }.enabled(Disc.connected && !Disc.busy && !Disc.discBusy), w = 48, h = 48)
        head.add(iconButton(act, Icons.Eject, S(R.string.eject), Pal.onSurfaceVariant) { Disc.eject() }.enabled(Disc.connected && !Disc.busy), w = 48, h = 48)
        col.add(head, b = 8)
        val audio = Disc.audioTracks
        when {
            !Disc.connected -> col.add(emptyState(act, Icons.Album, S(R.string.no_drive_connected), S(R.string.connect_external_drive_over_usb, Disc.status), S(R.string.search_again)) { Disc.scan() })
            audio.isEmpty() -> if (Disc.discBusy) {
                val c = vbox(act, Gravity.CENTER_HORIZONTAL)
                c.setPadding(0, dp(48), 0, dp(48))
                c.add(ProgressBarView(act, -1f, 4), w = 160, b = 16)
                c.add(text(act, S(R.string.reading_disc), Type.BodyLarge), w = WRAP)
                col.add(c)
            } else {
                val loading = Disc.discText == S(R.string.loading_disc)
                col.add(emptyState(
                    act, Icons.Album, Disc.discText.ifEmpty { S(R.string.insert_disc) },
                    if (loading) S(R.string.wait_few_seconds_while_disc) else S(R.string.audio_cds_are_read_automatically) + "\n" + S(R.string.if_nothing_appears_tap_reload),
                    S(R.string.reload),
                ) { Disc.refreshDisc() })
            }
            else -> {
                col.add(albumHeader(audio), b = 8)
                val th = hbox(act)
                th.setPadding(dp(4), dp(8), 0, 0)
                th.add(text(act, S(R.string.tracks_4), Type.TitleMedium), w = 0, weight = 1f)
                val all = audio.all { Disc.selected[it.number] == true }
                th.add(button(act, if (all) S(R.string.deselect_all) else S(R.string.select_all), Btn.Text) { Disc.selectAll(!all) }.enabled(!Disc.busy), w = WRAP)
                col.add(th)
                audio.forEach { t -> col.add(trackRow(t)) }
            }
        }
        if (Disc.connected) col.add(text(act, Disc.driveName, Type.BodySmall, Pal.onSurfaceVariant), t = 16, l = 4)
        updateTracks()
    }

    // ---------------- アルバムの情報 ----------------

    private fun albumHeader(audio: List<TocTrack>): View {
        val c = card(act, Pal.containerLow)
        c.setPadding(dp(16), dp(16), dp(16), dp(16))
        if (android.os.Build.VERSION.SDK_INT >= 21) com.uyatame.ucrt.legacy.compat.Api21.elevation(c, com.uyatame.ucrt.legacy.ui.dpf(1))
        val busy = Disc.busy
        val top = hbox(act, Gravity.TOP)
        val art = ArtView(act, 12f)
        art.setBitmap(Disc.cover)
        if (!busy) art.tap(12f) { pickCover() }
        top.add(art, w = 116, h = 116, r = 16)
        val info = vbox(act)
        val title = text(act, Disc.albumTitle, Type.TitleLarge, Pal.onSurface, lines = 2)
        if (!busy) title.tap(4f) { Disc.openChooser() }
        info.add(title, b = 2)
        info.add(text(act, Disc.albumArtist, Type.BodyLarge, Pal.onSurfaceVariant), b = 2)
        val year = Disc.meta?.date?.take(4).orEmpty()
        val total = audio.sumOf { it.endLba - it.startLba }
        info.add(text(act, listOf(year, S(R.string.tracks, audio.size), fmtSectors(total)).filter { it.isNotEmpty() }.joinToString(S(R.string.label_5)), Type.BodySmall, Pal.onSurfaceVariant), b = 2)
        if (Disc.metaSearching) info.add(ProgressBarView(act, -1f, 4), w = 120, t = 4, b = 2)
        if (Disc.metaStatus.isNotEmpty()) {
            val st = text(act, Disc.metaStatus, Type.LabelSmall, Pal.primary, lines = 2)
            if (!busy) st.tap(4f) { Disc.openChooser() }
            info.add(st, b = 2)
        }
        if (!Disc.metaSearching && !busy) {
            val h = text(act, S(R.string.tap_to_choose_different_track), Type.LabelSmall, Pal.onSurfaceVariant, lines = 2)
            h.tap(4f) { Disc.openChooser() }
            info.add(h)
        }
        top.add(info, w = 0, weight = 1f)
        c.add(top, b = 14)
        // 取り込み・再生
        val btns = hbox(act)
        btns.add(button(act, S(R.string.rip), Btn.Filled, Icons.Download) { requestRip() }.enabled(!busy && !Disc.discBusy), w = 0, weight = 1f, r = 8)
        btns.add(button(act, S(R.string.play), Btn.Tonal, Icons.PlayArrow) { Disc.playCd() }.enabled(!busy), w = 0, weight = 1f)
        c.add(btns, b = 14)
        // 音質・コピー・登録・編集・候補
        val row = hbox(act)
        row.add(assist(SettingsPages.qualityLabel(), Icons.MusicNote, !busy) { qualityDialog() }, w = WRAP, r = 8)
        row.add(assist(S(R.string.copy_cd), Icons.Album, !busy && !Disc.discBusy) { Burn.startCopy(act) }, w = WRAP)
        row.add(View(act), w = 0, weight = 1f, h = 1)
        if (Disc.meta != null && Disc.meta?.releaseId == null) {
            if (MbUpload.busy) row.add(ProgressBarView(act, -1f, 3), w = 24, r = 12)
            else row.add(iconButton(act, Icons.Upload, S(R.string.mb_upload), Pal.onSurfaceVariant) { MbUpload.disc(act) }.enabled(!busy && !Disc.metaSearching), w = 48, h = 48)
        }
        row.add(iconButton(act, Icons.Edit, S(R.string.edit_track_info), Pal.onSurfaceVariant) { editMeta() }.enabled(!busy), w = 48, h = 48)
        row.add(iconButton(act, Icons.Search, S(R.string.matches_and_search), Pal.onSurfaceVariant) { Disc.openChooser() }.enabled(!busy), w = 48, h = 48)
        c.add(row)
        if (p.outputDir == null) {
            val w = hbox(act)
            w.add(text(act, S(R.string.no_save_folder_set), Type.BodySmall, Pal.error, lines = 2), w = 0, weight = 1f)
            w.add(button(act, S(R.string.set_up), Btn.Text) { act.openSettings(1) }, w = WRAP)
            c.add(w, t = 8)
        }
        return c
    }

    /** 本家の AssistChip(枠つきの小さなボタン) */
    private fun assist(label: String, d: String, enabled: Boolean, onClick: () -> Unit): View {
        val b = hbox(act, Gravity.CENTER)
        b.bgd(rounded(0, 8f, Pal.outline, 1f))
        b.setPadding(dp(8), 0, dp(16), 0)
        b.minimumHeight = dp(32)
        b.add(com.uyatame.ucrt.legacy.ui.icon(act, d, Pal.primary, 18), w = 18, h = 18, r = 8)
        b.add(text(act, label, Type.LabelLarge, Pal.onSurface), w = WRAP)
        b.tap(8f) { onClick() }
        b.enabled(enabled)
        return b
    }

    private fun pickCover() {
        act.pickImage { u ->
            Thread({
                val b = act.readImage(u)
                act.runOnUiThread { if (b == null) act.snack(S(R.string.could_not_load_image)) else Disc.setCover(b) }
            }, "ucrt-cover-read").start()
        }
    }

    // ---------------- 曲 ----------------

    private fun trackRow(t: TocTrack): View {
        val row = hbox(act)
        row.setPadding(dp(4), dp(8), dp(12), dp(8))
        row.minimumHeight = dp(64)
        val check = CheckView(act, Disc.selected[t.number] == true) { Disc.toggle(t.number) }
        check.isEnabled = !Disc.busy
        row.add(check, w = 48, h = 48, r = 8)
        val c = vbox(act)
        val title = text(act, "${t.number}. ${Disc.trackTitle(t)}", Type.BodyLarge)
        c.add(title)
        c.add(text(act, Disc.trackArtist(t), Type.BodyMedium, Pal.onSurfaceVariant))
        val st = text(act, "", Type.LabelSmall, Pal.primary)
        c.add(st)
        val bar = ProgressBarView(act, 0f, 4)
        c.add(bar, t = 6)
        row.add(c, w = 0, weight = 1f)
        row.add(text(act, fmtSectors(t.endLba - t.startLba), Type.LabelLarge), w = WRAP, l = 8)
        row.tap(12f) { if (!Disc.busy) Disc.playCd(t) }
        trackViews[t.number] = TrackViews(st, bar, row, title, check)
        return row
    }

    /** 取り込みの進み具合と再生中の曲の印を書き換える */
    private fun updateTracks() {
        val cur = app.player.current?.uri
        for ((n, v) in trackViews) {
            val pr = Disc.progress[n] ?: 0f
            val s = Disc.trackStatus[n].orEmpty()
            val showBar = Disc.busy && pr > 0f && pr < 1f
            v.bar.visibility = if (showBar) View.VISIBLE else View.GONE
            v.bar.progress = pr
            v.status.visibility = if (!showBar && s.isNotEmpty()) View.VISIBLE else View.GONE
            v.status.text = s
            val playing = cur == "cd:$n"
            v.row.bgd(rounded(if (playing) Pal.secondaryContainer else 0, 12f))
            v.title.bold(playing)
        }
    }

    // ---------------- 取り込み ----------------

    private fun requestRip() {
        when {
            Disc.audioTracks.none { Disc.selected[it.number] == true } -> act.snack(S(R.string.select_tracks_to_rip))
            Disc.meta?.title == null || Disc.meta?.title == UNKNOWN_ALBUM -> {
                val d = UDialog(act).title(S(R.string.no_track_info)).message(S(R.string.tracks_will_be_named_track))
                d.button(S(R.string.choose_track_info)) { d.dismiss(); Disc.openChooser() }
                d.button(S(R.string.rip_anyway)) { d.dismiss(); ripWithFolder() }
                d.show()
            }
            else -> ripWithFolder()
        }
    }

    /** 保存先が未設定なら、その場でフォルダを選んでから */
    private fun ripWithFolder() {
        val dir = p.outputDir
        if (dir == null) FolderPicker.pick(act, null) { path -> p.set("outputDir", path); app.lib.refresh(false); ripConfirm(path) }
        else ripConfirm(dir)
    }

    private fun ripConfirm(dir: String) {
        Disc.prepareRip(act, dir)
        val plan = Disc.ripPlan ?: return
        var skip = true
        val body = vbox(act)
        body.add(text(act, S(R.string.tracks_2, plan.count, SettingsPages.qualityLabel()), Type.BodyMedium, Pal.onSurface, lines = 0), b = 8)
        body.add(text(act, S(R.string.save_to) + plan.dest, Type.BodySmall, Pal.onSurfaceVariant, lines = 0), b = 8)
        body.add(text(act, S(R.string.size_about) + String.format(java.util.Locale.US, "%.0f MB", plan.sizeBytes / 1048576.0) + S(R.string.make_sure_you_have_enough), Type.BodySmall, Pal.onSurfaceVariant, lines = 0), b = 8)
        if (plan.existing.isNotEmpty()) {
            body.add(text(act, S(R.string.files_with_same_name_already, plan.existing.size), Type.BodyMedium, Pal.error, lines = 0), b = 4)
            body.add(text(act, plan.existing.take(5).joinToString("\n") + if (plan.existing.size > 5) "\n…" else "", Type.BodySmall, Pal.onSurfaceVariant, lines = 0), b = 4)
            val radios = ArrayList<RadioView>()
            fun opt(label: String, v: Boolean) {
                val r = hbox(act)
                val rv = RadioView(act, skip == v)
                radios += rv
                r.add(rv, w = 48, h = 48)
                r.add(text(act, label, Type.BodyMedium, Pal.onSurface, lines = 0), w = 0, weight = 1f)
                r.tap(8f) { skip = v; radios.forEachIndexed { i, x -> x.checked = (i == 0) == v } }
                body.add(r)
            }
            opt(S(R.string.skip_existing_tracks), true)
            opt(S(R.string.overwrite_replaced_only_after_successful), false)
        }
        val d = UDialog(act).title(S(R.string.confirm_ripping)).view(body)
        d.button(S(R.string.cancel)) { Disc.ripPlan = null; d.dismiss() }
        d.button(S(R.string.rip)) { d.dismiss(); Disc.confirmRip(plan.existing.isNotEmpty() && skip) }
        d.setOnCancelListener { Disc.ripPlan = null }
        d.show()
    }

    private fun qualityDialog() {
        val d = UDialog(act).title(S(R.string.rip_quality))
        val sc = ScrollView(act)
        sc.addView(SettingsPages.qualityOptions(act))
        d.view(sc)
        d.close(S(R.string.close))
        d.setOnDismissListener { sig = ""; refresh(0) }
        d.show()
    }

    // ---------------- 曲情報の編集 ----------------

    private fun editMeta() {
        val m = Disc.meta ?: return
        val audio = Disc.audioTracks
        // WAV には詳しい情報と歌詞を書けない
        val detail = p.format != AudioFormat.WAV
        val form = TagForm(
            m.title, m.artist, m.date, m.genre,
            m.tracks.mapIndexed { i, t ->
                val tt = audio.getOrNull(i)
                TrackForm(
                    trackLabel(tt?.number ?: (i + 1)), t.title, if (t.artist == m.artist) "" else t.artist,
                    tt?.let { fmtSectors(it.endLba - it.startLba) }.orEmpty(), detail,
                    t.composer, t.lyricist, t.comment, t.lyrics,
                )
            },
            // CD の画面では、何枚目かと枚数だけ(曲の番号は目次で決まる)
            discNo = m.discNo.toString(), discTotal = m.discTotal.toString(),
        )
        lateinit var ed: TagEditView
        ed = TagEditView(
            act, S(R.string.edit_track_info), form,
            onCancel = { act.closeOverlay(ed.view) },
            onSave = {
                val artist = form.albumArtist.trim()
                Disc.updateMeta(m.copy(
                    title = form.album.trim(), artist = artist, date = form.date.trim(), genre = form.genre.trim(),
                    discNo = form.discNo.toIntOrNull()?.coerceAtLeast(1) ?: m.discNo,
                    discTotal = form.discTotal.toIntOrNull()?.coerceAtLeast(1) ?: m.discTotal,
                    tracks = m.tracks.mapIndexed { i, tm ->
                        val f = form.tracks.getOrNull(i) ?: return@mapIndexed tm
                        tm.copy(
                            title = f.title.trim(), artist = f.artist.trim().ifEmpty { artist },
                            composer = f.composer.trim(), lyricist = f.lyricist.trim(), comment = f.comment.trim(), lyrics = f.lyrics.trim(),
                        )
                    },
                ))
                act.closeOverlay(ed.view)
            },
            dateLabel = S(R.string.release_date_e_g_2024),
            note = if (!detail) S(R.string.tag_detail_wav) else null,
        )
        act.openOverlay(ed.view) { act.closeOverlay(ed.view) }
    }

    // ---------------- 曲情報の候補(本家の MetaChooserDialog) ----------------

    private class Chooser(val act: LegacyActivity, val onGone: () -> Unit) {
        private val sheet = Sheet(act, full = true)
        private val matches = vbox(act)
        private val results = vbox(act)
        private val retry: View
        private val searchBtn: View
        private val searchProg = ProgressBarView(act, -1f, 4)
        private val searchMsg = text(act, "", Type.BodySmall, Pal.onSurface, lines = 0)
        private val albumF: android.widget.EditText
        private val artistF: android.widget.EditText
        private var sig = ""

        init {
            val b = sheet.body
            b.setPadding(dp(16), 0, dp(16), dp(32))
            val top = hbox(act)
            top.add(text(act, S(R.string.choose_track_info), Type.TitleLarge), w = 0, weight = 1f)
            top.add(button(act, S(R.string.close), Btn.Text) { sheet.dismiss() }, w = WRAP)
            b.add(top)
            val mh = hbox(act)
            mh.add(text(act, S(R.string.matches_for_this_disc), Type.TitleSmall), w = 0, weight = 1f)
            retry = button(act, S(R.string.retry), Btn.Text) { Disc.fetchMeta(openChooserAlways = true) }
            mh.add(retry, w = WRAP)
            b.add(mh)
            b.add(matches)
            b.addDivider()
            b.add(text(act, S(R.string.search_by_name), Type.TitleSmall), t = 8, b = 8)
            val cur = Disc.meta
            albumF = field(act, cur?.title?.takeIf { it != UNKNOWN_ALBUM }.orEmpty(), S(R.string.album)) { search() }
            artistF = field(act, cur?.artist?.takeIf { it != UNKNOWN_ARTIST }.orEmpty(), S(R.string.artist)) { search() }
            b.add(albumF, b = 8)
            b.add(artistF, b = 8)
            val sr = hbox(act)
            searchBtn = button(act, S(R.string.search), Btn.Tonal, Icons.Search) { search() }
            sr.add(searchBtn, w = WRAP, r = 12)
            sr.add(searchProg, w = 0, weight = 1f)
            b.add(sr)
            b.add(searchMsg, t = 4)
            b.add(results)
            sheet.setOnDismissListener { Disc.closeChooser(); onGone() }
            refresh()
        }

        fun show() = sheet.show()
        fun dismiss() { sheet.setOnDismissListener(null); sheet.dismiss() }

        private fun search() {
            val al = albumF.text.toString(); val ar = artistF.text.toString()
            if (al.isBlank() && ar.isBlank()) return
            act.hideKeyboard(albumF)
            Disc.searchByName(al, ar)
        }

        private fun inUse(c: ReleaseCandidate): Boolean {
            val m = Disc.meta ?: return false
            return if (c.meta.releaseId != null) c.meta.releaseId == m.releaseId
            else m.releaseId == null && c.meta.title == m.title && c.meta.artist == m.artist
        }

        fun refresh() {
            if (!Disc.showChooser) { dismiss(); onGone(); return }
            val busy = Disc.applying
            val s = listOf(Disc.metaSearching, Disc.metaError, Disc.candidates.hashCode(), Disc.searchResults.hashCode(), Disc.searchBusy, Disc.searchMessage, busy, Disc.meta?.hashCode()).joinToString("|")
            if (s == sig) return
            sig = s
            val n = Disc.audioTracks.size
            retry.enabled(!Disc.metaSearching && !busy)
            matches.removeAllViews()
            when {
                Disc.metaSearching -> matches.add(ProgressBarView(act, -1f, 4), t = 8, b = 8)
                Disc.metaError.isNotEmpty() -> {
                    matches.add(text(act, S(R.string.couldnt_search_due_to_network), Type.BodyMedium, Pal.error, lines = 0))
                    matches.add(text(act, Disc.metaError, Type.BodySmall, Pal.onSurface, lines = 0), t = 4)
                }
                Disc.candidates.isEmpty() -> matches.add(text(act, S(R.string.no_matches_found_you_can), Type.BodySmall, Pal.onSurfaceVariant, lines = 0))
            }
            Disc.candidates.forEach { c -> matches.add(candidateRow(act, c, n, !busy, inUse(c)) { Disc.applyCandidate(c) }, b = 4) }
            searchBtn.enabled(!Disc.searchBusy && !busy)
            searchProg.visibility = if (Disc.searchBusy || busy) View.VISIBLE else View.INVISIBLE
            searchMsg.text = Disc.searchMessage
            searchMsg.visibility = if (Disc.searchMessage.isEmpty()) View.GONE else View.VISIBLE
            results.removeAllViews()
            Disc.searchResults.forEach { c -> results.add(candidateRow(act, c, n, !busy, inUse(c)) { Disc.applyCandidate(c) }, b = 4) }
        }
    }
}
