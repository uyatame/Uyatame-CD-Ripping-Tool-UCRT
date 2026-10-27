package com.uyatame.ucrt.legacy

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.ui.ArtView
import com.uyatame.ucrt.legacy.ui.Btn
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.Sheet
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.addDivider
import com.uyatame.ucrt.legacy.ui.button
import com.uyatame.ucrt.legacy.ui.chip
import com.uyatame.ucrt.legacy.ui.chipRow
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.enabled
import com.uyatame.ucrt.legacy.ui.field
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import com.uyatame.ucrt.library.ArtSource
import com.uyatame.ucrt.library.LibAlbum
import com.uyatame.ucrt.library.UNKNOWN_ALBUM
import com.uyatame.ucrt.library.UNKNOWN_ARTIST
import com.uyatame.ucrt.meta.AlbumMeta
import com.uyatame.ucrt.meta.Lrclib
import com.uyatame.ucrt.meta.MusicBrainz
import com.uyatame.ucrt.meta.describeNetError
import com.uyatame.ucrt.player.Lyrics
import com.uyatame.ucrt.player.LyricsCore
import java.io.File

/**
 * 曲情報を MusicBrainz に登録する(本家の uploadToMusicBrainz・uploadAlbumToMusicBrainz・MbSubmitSheet)。
 * すでに登録されていれば登録しない。詳しい情報を入れてから、登録の画面を曲情報を入れた状態で開く(送信は利用者)。
 * 埋め込まれた歌詞は LRCLIB にも登録できる
 */
object MbUpload {
    private val app get() = LegacyApp.app
    private val main = Handler(Looper.getMainLooper())

    @Volatile var busy = false; private set

    class TrackDraft(val title: String, val artist: String, val lengthMs: Long, val lyrics: Lyrics?)
    class DiscDraft(val position: Int, val toc: String?, val tracks: List<TrackDraft>)
    class Draft(val title: String, val artist: String, val date: String, val discs: List<DiscDraft>, val cover: ByteArray?, val note: String)

    private fun snack(s: String) = main.post { LegacyActivity.current?.snack(s) }

    private fun setBusy(b: Boolean) { busy = b; main.post { Bus.emit(Bus.DISC) } }

    /** すでにあったとき(本家の MbExistingDialog) */
    private fun existing(title: String, summary: String, releaseUrl: String?, attachUrl: String?, message: String) = main.post {
        val act = LegacyActivity.current ?: return@post
        val body = vbox(act)
        body.add(text(act, message, Type.BodyMedium, Pal.onSurfaceVariant, lines = 0), b = 8)
        body.add(text(act, title, Type.TitleMedium, Pal.onSurface, lines = 0), b = 4)
        if (summary.isNotBlank()) body.add(text(act, summary, Type.BodySmall, Pal.onSurfaceVariant, lines = 0), b = 4)
        if (releaseUrl != null) body.add(button(act, S(R.string.mb_open_release), Btn.Text) { open(act, releaseUrl) }, w = WRAP)
        val d = UDialog(act).title(S(R.string.mb_upload)).view(body)
        d.button(S(R.string.close)) { d.dismiss() }
        if (attachUrl != null) d.button(S(R.string.mb_add_disc_id)) { d.dismiss(); open(act, attachUrl) }
        d.show()
    }

    private fun open(act: LegacyActivity, url: String) {
        runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // ================= CD から =================

    fun disc(act: LegacyActivity) {
        val m = Disc.meta ?: return
        val toc = MusicBrainz.toc(Disc.tracks, Disc.leadOut) ?: return
        if (busy) return
        val audio = Disc.audioTracks
        // 仮の名前のままのところがあれば、登録しない
        val placeholder = m.title.isBlank() || m.artist.isBlank() || m.title == UNKNOWN_ALBUM || m.artist == UNKNOWN_ARTIST ||
            m.tracks.size < toc.audioCount ||
            m.tracks.take(toc.audioCount).withIndex().any { (i, t) ->
                t.title.isBlank() || audio.getOrNull(i)?.let { t.title == S(R.string.track_2).format(it.number) } == true
            }
        if (placeholder) { act.snack(S(R.string.mb_upload_fill_in)); return }
        MusicBrainz.version = SettingsPages.version(app)
        val cover = Disc.coverData
        setBusy(true)
        Thread({
            try {
                // 1) このCDのディスクIDで見つかるなら、登録しない
                val byDisc = MusicBrainz.lookup(toc)
                if (byDisc.error != null) { snack(S(R.string.mb_upload_check_failed, byDisc.error)); return@Thread }
                val exact = byDisc.candidates.firstOrNull { it.exact }
                if (exact != null) {
                    existing(exact.meta.title, exact.summary, exact.meta.releaseId?.let { MusicBrainz.releaseUrl(it) }, null, S(R.string.mb_upload_already_desc))
                    AppLog.log(S(R.string.mb_upload_already, exact.meta.title))
                    return@Thread
                }
                // 2) 同じアルバムがあれば、ディスクIDの追加を勧める
                val same = byDisc.candidates.firstOrNull { it.trackCount == toc.audioCount } ?: MusicBrainz.findSame(m, toc.audioCount)
                if (same != null) {
                    existing(same.meta.title, same.summary, same.meta.releaseId?.let { MusicBrainz.releaseUrl(it) }, MusicBrainz.attachUrl(toc), S(R.string.mb_upload_similar_desc))
                    AppLog.log(S(R.string.mb_upload_similar, same.meta.title))
                    return@Thread
                }
                // 3) 詳しい情報を入力する画面へ
                val lengths = audio.take(toc.audioCount).map { (it.endLba - it.startLba).toLong() * 1000 / 75 }
                val draft = Draft(
                    m.title, m.artist, m.date,
                    listOf(DiscDraft(
                        if (m.discTotal > 1) m.discNo else 0, toc.tocParam.replace('+', ' '),
                        lengths.indices.map { i ->
                            val tm = m.tracks[i]
                            TrackDraft(tm.title, tm.artist, lengths[i], tm.lyrics.takeIf { it.isNotBlank() }?.let { LyricsCore.parse(it) })
                        },
                    )),
                    cover, MusicBrainz.EDIT_NOTE,
                )
                main.post { LegacyActivity.current?.let { sheet(it, draft) } }
            } catch (e: Exception) {
                snack(S(R.string.mb_upload_check_failed, describeNetError(e)))
            } finally {
                setBusy(false)
            }
        }, "ucrt-mb-check").start()
    }

    // ================= ライブラリから =================

    fun album(act: LegacyActivity, a: LibAlbum) {
        if (busy) return
        val discs = a.tracks.groupBy { it.disc }.toSortedMap()
        if (a.title.isBlank() || a.artist.isBlank() || a.title == UNKNOWN_ALBUM || a.artist == UNKNOWN_ARTIST || a.tracks.any { it.title.isBlank() }) {
            act.snack(S(R.string.mb_upload_fill_in)); return
        }
        MusicBrainz.version = SettingsPages.version(app)
        setBusy(true)
        act.snack(S(R.string.mb_upload_checking))
        Thread({
            try {
                val firstCount = discs.values.first().size
                val same = MusicBrainz.findSame(AlbumMeta(a.title, a.artist), firstCount)
                if (same != null) {
                    existing(same.meta.title, same.summary, same.meta.releaseId?.let { MusicBrainz.releaseUrl(it) }, null, S(R.string.mb_upload_library_exists))
                    AppLog.log(S(R.string.mb_upload_similar, same.meta.title))
                    return@Thread
                }
                val multi = discs.size > 1
                val draftDiscs = discs.entries.mapIndexed { n, (disc, list) ->
                    DiscDraft(
                        if (multi) (if (disc > 0) disc else n + 1) else 0, null,
                        list.sortedBy { it.track }.map { TrackDraft(it.title, it.artist, it.durationMs.coerceAtLeast(0), LyricsCore.embeddedOnly(app, it.uri)) },
                    )
                }
                val cover = runCatching { ArtSource.bytes(app, a.first.uri, a.first.coverUri) }.getOrNull()
                val draft = Draft(a.title, a.artist, a.year, draftDiscs, cover, MusicBrainz.EDIT_NOTE_LIBRARY)
                main.post { LegacyActivity.current?.let { sheet(it, draft) } }
            } catch (e: Exception) {
                snack(S(R.string.mb_upload_check_failed, describeNetError(e)))
            } finally {
                setBusy(false)
            }
        }, "ucrt-mb-check").start()
    }

    // ================= 詳しい情報を入れる画面(本家の MbSubmitSheet) =================

    private class Choice(val value: String, val label: () -> String)

    private val TYPES = listOf(
        Choice("album") { S(R.string.mb_type_album) }, Choice("single") { S(R.string.mb_type_single) },
        Choice("ep") { "EP" }, Choice("other") { S(R.string.mb_other) },
    )
    private val STATUSES = listOf(
        Choice("official") { S(R.string.mb_status_official) }, Choice("promotion") { S(R.string.mb_status_promotion) },
        Choice("bootleg") { S(R.string.mb_status_bootleg) },
    )
    private val LANGUAGES = listOf(
        Triple("jpn", "Jpan") { S(R.string.mb_lang_japanese) }, Triple("eng", "Latn") { S(R.string.mb_lang_english) },
        Triple("zxx", "") { S(R.string.mb_lang_none) }, Triple("", "") { S(R.string.mb_unset) },
    )
    private val PACKAGING = listOf(
        Choice("Jewel Case") { S(R.string.mb_pack_jewel) }, Choice("Slim Jewel Case") { S(R.string.mb_pack_slim) },
        Choice("Digipak") { S(R.string.mb_pack_digipak) }, Choice("Cardboard/Paper Sleeve") { S(R.string.mb_pack_paper) },
        Choice("Other") { S(R.string.mb_other) }, Choice("") { S(R.string.mb_unset) },
    )

    private fun hasJapanese(s: String) = s.any { it in '぀'..'ヿ' || it in '一'..'鿿' }

    private fun sheet(act: LegacyActivity, d: Draft) {
        val s = Sheet(act, full = true)
        val b = s.body
        b.setPadding(dp(20), 0, dp(20), dp(24))
        val allText = d.title + d.artist + d.discs.joinToString { disc -> disc.tracks.joinToString { it.title } }
        var type = if (d.discs.sumOf { it.tracks.size } <= 3) "single" else "album"
        var status = "official"
        var lang = if (hasJapanese(allText)) 0 else 1
        var packaging = "Jewel Case"
        val lyricsCount = d.discs.sumOf { disc -> disc.tracks.count { it.lyrics != null } }
        var withCover = d.cover != null
        var withLyrics = lyricsCount > 0

        b.add(text(act, S(R.string.mb_upload), Type.TitleLarge), b = 4)
        b.add(text(act, S(R.string.mb_sheet_desc), Type.BodySmall, Pal.onSurfaceVariant, lines = 0), b = 10)
        fun lab(label: String, v: View): View {
            val c = vbox(act)
            c.add(text(act, label, Type.LabelMedium, Pal.primary), l = 4, b = 4)
            c.add(v)
            return c
        }
        val top = hbox(act, Gravity.TOP)
        d.cover?.let { cb ->
            val art = ArtView(act, 8f)
            art.setBitmap(ArtSource.decode(cb, dp(72), true))
            top.add(art, w = 72, h = 72, r = 12)
        }
        val tc = vbox(act)
        val titleF = field(act, d.title, S(R.string.album))
        val artistF = field(act, d.artist, S(R.string.artist))
        tc.add(lab(S(R.string.album), titleF), b = 6)
        tc.add(lab(S(R.string.artist), artistF))
        top.add(tc, w = 0, weight = 1f)
        b.add(top, b = 10)
        fun chips(title: String, labels: List<String>, sel: Int, onSel: (Int) -> Unit) {
            val c = vbox(act)
            c.add(text(act, title, Type.LabelLarge, Pal.onSurfaceVariant), b = 2)
            val holder = vbox(act)
            fun build(cur: Int) {
                holder.removeAllViews()
                holder.add(chipRow(act, labels.mapIndexed { i, l -> chip(act, l, i == cur) { onSel(i); build(i) } }))
            }
            build(sel)
            c.add(holder)
            b.add(c, b = 10)
        }
        chips(S(R.string.mb_type), TYPES.map { it.label() }, TYPES.indexOfFirst { it.value == type }) { type = TYPES[it].value }
        chips(S(R.string.mb_status), STATUSES.map { it.label() }, STATUSES.indexOfFirst { it.value == status }) { status = STATUSES[it].value }
        val r1 = hbox(act, Gravity.TOP)
        val dateF = field(act, d.date, "2024-03-05")
        val countryF = field(act, java.util.Locale.getDefault().country.ifEmpty { "JP" }, S(R.string.mb_country))
        countryF.filters = arrayOf(android.text.InputFilter.LengthFilter(2))
        r1.add(lab(S(R.string.mb_date), dateF), w = 0, weight = 1f, r = 8)
        r1.add(lab(S(R.string.mb_country), countryF), w = 110)
        b.add(r1, b = 8)
        val r2 = hbox(act, Gravity.TOP)
        val labelF = field(act, "", S(R.string.mb_label))
        val catalogF = field(act, "", S(R.string.mb_catalog))
        r2.add(lab(S(R.string.mb_label), labelF), w = 0, weight = 1f, r = 8)
        r2.add(lab(S(R.string.mb_catalog), catalogF), w = 0, weight = 1f)
        b.add(r2, b = 8)
        val barcodeF = field(act, "", S(R.string.mb_barcode))
        barcodeF.inputType = InputType.TYPE_CLASS_NUMBER
        barcodeF.filters = arrayOf(android.text.InputFilter.LengthFilter(14))
        b.add(lab(S(R.string.mb_barcode), barcodeF), b = 10)
        chips(S(R.string.mb_language), LANGUAGES.map { it.third() }, lang) { lang = it }
        chips(S(R.string.mb_packaging), PACKAGING.map { it.label() }, PACKAGING.indexOfFirst { it.value == packaging }) { packaging = PACKAGING[it].value }
        val commentF = field(act, "", S(R.string.mb_comment))
        b.add(lab(S(R.string.mb_comment), commentF), b = 2)
        b.add(text(act, S(R.string.mb_comment_hint), Type.BodySmall, Pal.onSurfaceVariant, lines = 0), l = 4, b = 10)
        b.addDivider()
        b.add(text(act, S(R.string.mb_tracks), Type.TitleMedium), t = 10, b = 6)
        val titleFields = ArrayList<EditText>()
        val artistFields = ArrayList<EditText>()
        d.discs.forEach { disc ->
            if (d.discs.size > 1) b.add(text(act, "Disc ${disc.position}", Type.LabelLarge, Pal.primary), b = 4)
            disc.tracks.forEachIndexed { n, t ->
                val tf = field(act, t.title, S(R.string.title))
                val af = field(act, t.artist, d.artist)
                titleFields += tf; artistFields += af
                b.add(lab("${n + 1}. " + S(R.string.title) + if (t.lengthMs > 0) S(R.string.legacy_paren, fmtMs(t.lengthMs)) else "", tf), b = 4)
                b.add(lab(S(R.string.mb_track_artist), af), b = 4)
                if (t.lyrics != null) b.add(text(act, S(R.string.mb_has_lyrics) + if (t.lyrics.synced) S(R.string.mb_synced) else "", Type.LabelSmall, Pal.primary), l = 4)
                b.add(View(act), h = 8)
            }
        }
        b.addDivider()
        if (d.cover != null) b.add(switchRow(act, S(R.string.mb_with_cover), S(R.string.mb_with_cover_desc), withCover) { withCover = it }, t = 8)
        if (lyricsCount > 0) b.add(switchRow(act, S(R.string.mb_with_lyrics, lyricsCount), S(R.string.mb_with_lyrics_desc), withLyrics) { withLyrics = it }, t = 8)
        if (Build.VERSION.SDK_INT < WEB_MIN) b.add(text(act, S(R.string.legacy_mb_browser), Type.BodySmall, Pal.onSurfaceVariant, lines = 0), t = 8)
        val warn = text(act, S(R.string.mb_upload_fill_in), Type.BodySmall, Pal.error, lines = 0)
        b.add(warn, t = 8)
        val btns = hbox(act)
        btns.add(button(act, S(R.string.cancel), Btn.Text) { s.dismiss() }, w = 0, weight = 1f, r = 8)
        val go = button(act, S(R.string.mb_open_submit), Btn.Filled) {
            var k = 0
            val discs = d.discs.map { disc ->
                DiscDraft(disc.position, disc.toc, disc.tracks.map { t ->
                    val i = k++
                    TrackDraft(titleFields[i].text.toString().trim(), artistFields[i].text.toString().trim(), t.lengthMs, t.lyrics)
                })
            }
            val l = LANGUAGES[lang]
            val extra = MusicBrainz.ReleaseExtra(
                type = type, status = status, country = countryF.text.toString().trim(), label = labelF.text.toString().trim(),
                catalog = catalogF.text.toString().trim(), barcode = barcodeF.text.toString(), language = l.first, script = l.second,
                packaging = packaging, comment = commentF.text.toString().trim(),
            )
            val nd = Draft(titleF.text.toString().trim(), artistF.text.toString().trim(), dateF.text.toString().trim(), discs, d.cover, d.note)
            s.dismiss()
            submit(act, nd, extra, withCover, withLyrics)
        }
        btns.add(go, w = 0, weight = 2f)
        b.add(btns, t = 12)
        // 空の欄があれば送れない
        fun check() {
            val missing = titleF.text.isBlank() || artistF.text.isBlank() || titleFields.any { it.text.isBlank() }
            warn.visibility = if (missing) View.VISIBLE else View.GONE
            go.enabled(!missing)
        }
        val w = object : android.text.TextWatcher {
            override fun beforeTextChanged(x: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(x: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) = check()
        }
        (listOf(titleF, artistF) + titleFields).forEach { it.addTextChangedListener(w) }
        check()
        s.show()
    }

    /** アプリの中のブラウザで開ける Android(これより前は Let's Encrypt の証明書を信頼していないので、端末のブラウザで開く) */
    const val WEB_MIN = 25

    private fun submit(act: LegacyActivity, d: Draft, extra: MusicBrainz.ReleaseExtra, withCover: Boolean, withLyrics: Boolean) {
        val discs = d.discs.map { disc -> MusicBrainz.SeedDisc(disc.position, disc.tracks.map { MusicBrainz.SeedTrack(it.title, it.artist, it.lengthMs) }, disc.toc) }
        val html = MusicBrainz.seedHtml(d.title, d.artist, d.date, discs, d.note, extra)
        AppLog.log(S(R.string.mb_upload_opening))
        val cover = if (withCover) d.cover else null
        if (Build.VERSION.SDK_INT >= WEB_MIN) com.uyatame.ucrt.legacy.compat.Api25MbWebActivity.open(act, html, cover)
        else openInBrowser(act, html, cover)
        if (withLyrics) uploadLyrics(d)
    }

    /** 登録のページをファイルに書いて、端末のブラウザで開く(古い Android) */
    private fun openInBrowser(act: LegacyActivity, html: String, cover: ByteArray?) {
        try {
            @Suppress("DEPRECATION")
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "UCRT")
            dir.mkdirs()
            val f = File(dir, "musicbrainz.html")
            f.writeText(html)
            var coverPath: String? = null
            if (cover != null) {
                val c = File(dir, "musicbrainz-cover." + if (cover.size > 3 && cover[0] == 0x89.toByte()) "png" else "jpg")
                c.writeBytes(cover)
                coverPath = c.path
            }
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), "text/html")
            act.startActivity(i)
            if (coverPath != null) {
                val dlg = UDialog(act).title(S(R.string.mb_upload)).message(S(R.string.legacy_mb_cover_saved, coverPath))
                dlg.close(S(R.string.close))
                dlg.show()
            }
        } catch (e: Exception) {
            act.snack(S(R.string.legacy_mb_no_browser))
        }
    }

    // ================= 歌詞(LRCLIB) =================

    @Volatile private var lyricsUploading = false

    private fun uploadLyrics(d: Draft) {
        if (lyricsUploading) return
        val entries = d.discs.flatMap { it.tracks }.mapNotNull { t ->
            val l = t.lyrics ?: return@mapNotNull null
            if (t.lengthMs <= 0) return@mapNotNull null
            val (plain, synced) = Lrclib.fromLyrics(l)
            if (plain.isNullOrBlank() && synced.isNullOrBlank()) return@mapNotNull null
            Lrclib.Entry(t.title, t.artist.ifBlank { d.artist }, d.title, t.lengthMs / 1000.0, plain, synced)
        }
        if (entries.isEmpty()) return
        lyricsUploading = true
        Thread({
            var sent = 0; var skipped = 0; var failed = 0
            try {
                for ((i, e) in entries.withIndex()) {
                    snack(S(R.string.lyrics_uploading, i + 1, entries.size, e.track))
                    runCatching { if (Lrclib.hasLyrics(e)) false else { Lrclib.publish(e); true } }
                        .onSuccess { if (it) sent++ else skipped++ }
                        .onFailure { failed++; AppLog.log(S(R.string.lyrics_upload_failed, e.track, describeNetError(it))) }
                }
            } finally {
                lyricsUploading = false
                val msg = S(R.string.lyrics_upload_done, sent, skipped, failed)
                AppLog.log(msg)
                snack(msg)
            }
        }, "ucrt-lrclib").start()
    }
}
