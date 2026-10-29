package com.uyatame.ucrt.legacy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.data.AudioFormat
import com.uyatame.ucrt.data.MP3_RATES
import com.uyatame.ucrt.data.QUALITY_PRESETS
import com.uyatame.ucrt.data.QualityPreset
import com.uyatame.ucrt.legacy.ui.Btn
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.MATCH
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.RadioView
import com.uyatame.ucrt.legacy.ui.SliderView
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.addDivider
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.button
import com.uyatame.ucrt.legacy.ui.card
import com.uyatame.ucrt.legacy.ui.circle
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.icon
import com.uyatame.ucrt.legacy.ui.iconButton
import com.uyatame.ucrt.legacy.ui.rounded
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import kotlin.math.roundToInt

// =====================================================================
// 設定(本家の SettingsScreen)。page: 0 = トップ、1 = 保存と取り込み、2 = 取り込みの音質、3 = CD ドライブ、
// 4 = ライブラリ、5 = 表示、6 = ログ、7 = このアプリについて、8 = 音質設定(81〜89 はその詳細)、9 = 再生画面
// =====================================================================

object SettingsPages {
    private val app get() = LegacyApp.app
    private val p get() = app.prefs

    fun make(act: LegacyActivity, page: Int): Page = SettingsPage(act, page)

    /** 保存先の表示(フォルダの場所) */
    fun folderLabel(path: String?): String = path ?: S(R.string.not_set)

    fun qualityLabel(): String = when (p.format) {
        AudioFormat.FLAC -> S(R.string.flac_lossless)
        AudioFormat.AAC -> "AAC ${p.aacKbps}kbps"
        AudioFormat.MP3 -> "MP3 ${p.mp3Kbps}kbps"
        AudioFormat.WAV -> S(R.string.wav_uncompressed)
    }

    fun presetIndex(): Int = QUALITY_PRESETS.indexOfFirst { q ->
        q.format == p.format && when (p.format) {
            AudioFormat.FLAC -> q.flacLevel == p.flacLevel
            AudioFormat.AAC -> q.aacKbps == p.aacKbps
            AudioFormat.MP3 -> q.mp3Kbps == p.mp3Kbps
            AudioFormat.WAV -> true
        }
    }

    fun setPreset(q: QualityPreset) {
        p.sp.edit().putInt("format", q.format.ordinal).putInt("flacLevel", q.flacLevel).putInt("aacKbps", q.aacKbps).putInt("mp3Kbps", q.mp3Kbps).apply()
        Bus.emit(Bus.SETTINGS)
    }

    /** AAC の取り込みは Android 4.3 から(MP4 に書くため) */
    val aacSupported: Boolean get() = Build.VERSION.SDK_INT >= 18

    /** 取り込みの音質のおすすめ(AAC が使えない端末では AAC を除く) */
    fun presets(): List<QualityPreset> = QUALITY_PRESETS.filter { aacSupported || it.format != AudioFormat.AAC }

    /** 取り込みの音質のおすすめの一覧(本家の QualityOptions) */
    fun qualityOptions(act: LegacyActivity): View {
        val cur = presetIndex()
        val col = vbox(act)
        presets().forEach { q ->
            val i = QUALITY_PRESETS.indexOf(q)
            val r = hbox(act)
            r.setPadding(0, dp(4), 0, dp(4))
            r.add(RadioView(act, cur == i), w = 40, h = 40)
            val c = vbox(act)
            c.add(text(act, q.title, Type.BodyLarge, Pal.onSurface, lines = 0))
            c.add(text(act, q.desc, Type.BodySmall, Pal.onSurfaceVariant, lines = 0))
            r.add(c, w = 0, weight = 1f)
            r.tap(12f) { setPreset(q) }
            col.add(r)
        }
        if (cur < 0) col.add(text(act, S(R.string.custom_change_in_advanced_settings, qualityLabel()), Type.BodySmall, Pal.primary, lines = 0), l = 12, t = 4)
        return col
    }

    class SettingsPage(act: LegacyActivity, private val page: Int) : Page(act) {
        private val frame = FrameLayout(act)
        private val scroll = ScrollView(act)
        override val view: View get() = frame
        override val mask = Bus.SETTINGS or Bus.LIBRARY or Bus.SCAN or Bus.DISC or Bus.RIP

        init {
            refresh(-1)
        }

        override fun refresh(m: Int) {
            val y = scroll.scrollY
            frame.removeAllViews()
            // スクロールの部品は作り直しても使い回すので、前の入れ物から外しておく
            (scroll.parent as? android.view.ViewGroup)?.removeView(scroll)
            val col = vbox(act)
            if (page == 0) {
                frame.add(scroll, h = MATCH)
                scroll.removeAllViews()
                scroll.addView(col)
                col.setPadding(dp(16), 0, dp(16), dp(24))
                main(act, col)
            } else {
                val outer = vbox(act)
                outer.add(header(act, pageTitle(page)) { goBack() }, h = WRAP)
                outer.add(scroll, h = 0, weight = 1f)
                frame.add(outer, h = MATCH)
                scroll.removeAllViews()
                scroll.addView(col)
                col.setPadding(dp(16), 0, dp(16), dp(32))
                fun add(v: View) = col.add(v, b = 12)
                when (page) {
                    1 -> ripPage(act).forEach { add(it) }
                    2 -> qualityPage(act).forEach { add(it) }
                    3 -> drivePage(act).forEach { add(it) }
                    4 -> libraryPage(act).forEach { add(it) }
                    5 -> displayPage(act).forEach { add(it) }
                    6 -> logPage(act).forEach { add(it) }
                    7 -> aboutPage(act).forEach { add(it) }
                    8 -> add(Sound.main(act) { act.openSettings(80 + it) })
                    9 -> playerPage(act).forEach { add(it) }
                    in 81..89 -> add(Sound.sub(act, page - 80))
                }
            }
            scroll.post { scroll.scrollTo(0, y) }
        }

        private fun goBack() {
            act.settingsPage = if (page in 81..89) 8 else 0
            act.show()
        }
    }

    private fun pageTitle(page: Int): String = when (page) {
        1 -> S(R.string.saving_ripping)
        2 -> S(R.string.rip_quality)
        3 -> S(R.string.cd_drive)
        4 -> S(R.string.library)
        5 -> S(R.string.display)
        6 -> S(R.string.log)
        7 -> S(R.string.about_this_app)
        8 -> Sound.title(Sound.MAIN)
        9 -> S(R.string.player_screen)
        in 81..89 -> Sound.title(page - 80)
        else -> S(R.string.settings)
    }

    /** 戻るボタン付きの見出し(本家の PageHeader) */
    private fun header(act: LegacyActivity, title: String, onBack: () -> Unit): View {
        val row = hbox(act)
        row.setPadding(dp(4), dp(4), dp(4), dp(4))
        row.add(iconButton(act, Icons.ArrowBack, S(R.string.back), Pal.onSurface) { onBack() }, w = 48, h = 48)
        row.add(text(act, title, Type.TitleLarge), w = 0, weight = 1f)
        return row
    }

    // ---------------- トップ ----------------

    private fun main(act: LegacyActivity, col: LinearLayout) {
        val h = text(act, S(R.string.settings), Type.HeadlineMedium)
        h.setPadding(dp(4), dp(12), 0, dp(4))
        col.add(h, b = 12)
        col.add(groupLabel(act, S(R.string.settings_group_rip)), b = 12)
        col.add(cardOf(act, listOf(
            categoryRow(act, Icons.Download, S(R.string.saving_ripping), folderLabel(p.outputDir) + S(R.string.auto_track_info) + if (p.autoMeta) "ON" else "OFF") { act.openSettings(1) },
            categoryRow(act, Icons.MusicNote, S(R.string.rip_quality), qualityLabel()) { act.openSettings(2) },
            categoryRow(act, Icons.Album, S(R.string.cd_drive), S(R.string.read_speed) + (if (p.speedX == 0) S(R.string.max) else "${p.speedX}x") + S(R.string.retries_2, p.retries)) { act.openSettings(3) },
        )), b = 12)
        col.add(groupLabel(act, S(R.string.settings_group_player)), b = 12)
        val folders = app.lib.folders().size
        col.add(cardOf(act, listOf(
            categoryRow(act, Icons.Library, S(R.string.library), S(R.string.folders_add_music_from_your, folders)) { act.openSettings(4) },
            categoryRow(act, Icons.Headphones, S(R.string.sound_settings),
                (if (p.sourceDirect) S(R.string.source_direct) else S(R.string.eq) + (if (p.eqEnabled) "ON" else "OFF") +
                    (if (p.sp.getInt("darMode", 0) == 1) S(R.string.separator) + "DAR" else "")) + (if (p.usbDirect) S(R.string.usb_dac_direct) else "")) { act.openSettings(8) },
            categoryRow(act, Icons.PlayArrow, S(R.string.player_screen), S(R.string.ambient_mode) + " " + (if (p.ambientPlayer) "ON" else "OFF")) { act.openSettings(9) },
        )), b = 12)
        col.add(groupLabel(act, S(R.string.settings_group_general)), b = 12)
        col.add(cardOf(act, listOf(
            categoryRow(act, Icons.Edit, S(R.string.tag_edit_files), S(R.string.tag_edit_files_desc)) { Edit.editFiles(act) },
            categoryRow(act, Icons.Smartphone, S(R.string.display), listOf(S(R.string.auto), S(R.string.light), S(R.string.dark))[p.themeMode.coerceIn(0, 2)]) { act.openSettings(5) },
            categoryRow(act, Icons.Build, S(R.string.log), S(R.string.activity_log_for_troubleshooting)) { act.openSettings(6) },
            categoryRow(act, Icons.Info, S(R.string.about_this_app), S(R.string.version) + version(act) + S(R.string.licenses)) { act.openSettings(7) },
        )), b = 12)
        val last = ArrayList<View>()
        if (com.uyatame.ucrt.legacy.usb.UsbDac.findDac() != null) last.add(categoryRow(act, Icons.Usb, S(R.string.disconnect_usb_dac), S(R.string.disconnect_usb_dac_desc)) { UsbAudio.disconnect() })
        last.add(categoryRow(act, Icons.ExitToApp, S(R.string.quit_app), S(R.string.quit_app_desc)) { quit(act) })
        col.add(cardOf(act, last))
    }

    fun version(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""

    /** アプリを終了する(再生を止めて、通知も消す) */
    private fun quit(act: LegacyActivity) {
        app.writeResume()
        app.player.pause()
        app.store.flush()
        app.player.releaseOutput()
        runCatching { com.uyatame.ucrt.legacy.cast.CastManager.disconnect() }
        act.stopService(Intent(act, PlaybackService::class.java))
        act.finish()
        // 終了するときは、USB DAC の接続を解除する(本家と同じ。Android に返して USB でリセットする。数秒かかることがある)
        Thread({
            runCatching { UsbAudio.disconnect(sync = true) }
            android.os.Process.killProcess(android.os.Process.myPid())
        }, "usb-dac-exit").start()
    }

    // ---------------- 保存と取り込み ----------------

    private fun ripPage(act: LegacyActivity): List<View> {
        val s1 = Section(act, S(R.string.save_folder))
        val r = hbox(act)
        r.add(text(act, folderLabel(p.outputDir), Type.BodyLarge, Pal.onSurface, lines = 0), w = 0, weight = 1f)
        r.add(button(act, S(if (p.outputDir == null) R.string.choose else R.string.change), Btn.Tonal) {
            FolderPicker.pick(act, p.outputDir) { path -> p.set("outputDir", path); app.lib.refresh(false) }
        }, w = WRAP)
        s1.add(r)
        s1.add(subHeader(act, S(R.string.folders_file_names)))
        s1.add(choiceList(act, S(R.string.folder_structure), listOf(S(R.string.artist_album_track), S(R.string.album_track), S(R.string.no_subfolders)), p.folderMode) { p.set("folderMode", it) })
        s1.add(choiceList(act, S(R.string.file_name), listOf(S(R.string.n01_title), S(R.string.n01_artist_title), "Track01"), p.fileNameMode) { p.set("fileNameMode", it) })
        val s2 = Section(act, S(R.string.when_cd_is_inserted))
        s2.add(switchRow(act, S(R.string.get_track_info_automatically), S(R.string.fetches_titles_artists_and_cover), p.autoMeta) { p.set("autoMeta", it) })
        s2.add(switchRow(act, S(R.string.open_disc_tab), null, p.autoOpenDisc) { p.set("autoOpenDisc", it) })
        val s3 = Section(act, S(R.string.when_ripping))
        s3.add(switchRow(act, S(R.string.embed_cover_art_in_files), null, p.embedCover) { p.set("embedCover", it) })
        s3.add(switchRow(act, S(R.string.save_cover_art_to_folder), null, p.saveCover) { p.set("saveCover", it) })
        s3.add(switchRow(act, S(R.string.eject_cd_after_ripping), null, p.ejectAfter) { p.set("ejectAfter", it) })
        s3.add(switchRow(act, S(R.string.keep_screen_on_while_ripping), null, p.keepScreenOn) { p.set("keepScreenOn", it) })
        return listOf(s1.view, s2.view, s3.view)
    }

    // ---------------- 取り込みの音質 ----------------

    private fun snap32(v: Int): Int = (((v + 16) / 32) * 32).coerceIn(64, 320)

    private fun qualityPage(act: LegacyActivity): List<View> {
        val s1 = Section(act, S(R.string.presets))
        s1.add(qualityOptions(act))
        val s2 = Section(act, S(R.string.custom_2))
        val formats = AudioFormat.entries.filter { aacSupported || it != AudioFormat.AAC }
        s2.add(segmented(act, S(R.string.format), formats.map { it.label }, formats.indexOf(p.format)) { p.set("format", formats[it].ordinal) })
        when (p.format) {
            AudioFormat.FLAC -> s2.add(intSlider(act, S(R.string.flac_compression_level), p.flacLevel, 0..8, { "$it" }, S(R.string.sound_quality_is_same_at)) { p.set("flacLevel", it) })
            AudioFormat.AAC -> s2.add(intSlider(act, S(R.string.aac_bitrate), p.aacKbps, 64..320, { "${snap32(it)} kbps" }, S(R.string.higher_bitrates_mean_better_quality)) { p.set("aacKbps", snap32(it)) })
            AudioFormat.MP3 -> {
                s2.add(intSlider(act, S(R.string.mp3_bitrate), MP3_RATES.indexOf(p.mp3Kbps).coerceAtLeast(0), 0..MP3_RATES.lastIndex, { "${MP3_RATES[it]} kbps" }, S(R.string.constant_bitrate_cbr_320_kbps)) {
                    p.set("mp3Kbps", MP3_RATES[it])
                })
                s2.add(segmented(act, S(R.string.encoding_quality), listOf(S(R.string.fast), S(R.string.standard), S(R.string.high)),
                    when { p.mp3Quality >= 5 -> 0; p.mp3Quality >= 3 -> 1; else -> 2 }) { p.set("mp3Quality", listOf(7, 5, 2)[it]) })
            }
            AudioFormat.WAV -> s2.add(note(act, S(R.string.wav_files_are_saved_uncompressed)))
        }
        if (!aacSupported) s2.add(note(act, S(R.string.legacy_aac_needs_43)))
        return listOf(s1.view, s2.view)
    }

    // ---------------- CD ドライブ ----------------

    private fun drivePage(act: LegacyActivity): List<View> {
        val s1 = Section(act, S(R.string.reading))
        s1.add(intSlider(act, S(R.string.read_speed_2), p.speedX, 0..52, { if (it == 0) S(R.string.max) else "${it}x" }, S(R.string.slower_speeds_can_help_read)) { p.set("speedX", it) })
        s1.add(intSlider(act, S(R.string.retries), p.retries, 0..20, { S(R.string.label_4, it) }, S(R.string.how_many_times_to_retry)) { p.set("retries", it) })
        s1.add(switchRow(act, S(R.string.stop_on_error), S(R.string.when_off_unreadable_parts_are), p.abortOnError) { p.set("abortOnError", it) })
        val s2 = Section(act, S(R.string.advanced))
        s2.add(intSlider(act, S(R.string.read_offset_correction), p.offsetSamples, -2000..2000, { S(R.string.samples, it) }, S(R.string.corrects_drives_read_position_offset)) { p.set("offsetSamples", it) })
        s2.add(intSlider(act, S(R.string.sectors_per_read), p.sectorsPerRead, 1..27, { "$it" }, S(R.string.you_usually_dont_need_to)) { p.set("sectorsPerRead", it) })
        s2.add(switchRow(act, S(R.string.c2_error_detection), S(R.string.works_only_on_supported_drives), p.useC2) { p.set("useC2", it) })
        val s3 = Section(act, S(R.string.cd_playback))
        s3.add(intSlider(act, S(R.string.drive_speed_during_playback), p.cdPlaySpeed, 1..8, { "${it}x" }, S(R.string.lower_speeds_make_drive_quieter)) { p.set("cdPlaySpeed", it) })
        val reset = button(act, S(R.string.reset_cd_drive_settings), Btn.Outlined) {
            val d = UDialog(act).title(S(R.string.reset_to_defaults)).message(S(R.string.read_speed_retries_offset_and))
            d.button(S(R.string.cancel)) { d.dismiss() }
            d.button(S(R.string.reset_3)) {
                d.dismiss()
                p.sp.edit().remove("speedX").remove("retries").remove("abortOnError").remove("offsetSamples").remove("sectorsPerRead").remove("useC2").remove("cdPlaySpeed").apply()
                Bus.emit(Bus.SETTINGS)
                act.snack(S(R.string.cd_drive_settings_reset_to))
            }
            d.show()
        }
        return listOf(s1.view, s2.view, s3.view, reset)
    }

    // ---------------- ライブラリ ----------------

    private fun libraryPage(act: LegacyActivity): List<View> {
        val lib = app.lib
        val s = Section(act, S(R.string.folders_shown_in_library))
        s.add(note(act, S(R.string.along_with_your_rip_folder)))
        p.outputDir?.let { o ->
            val r = hbox(act)
            r.add(icon(act, Icons.Download, Pal.onSurfaceVariant), w = 24, h = 24, r = 16)
            val c = vbox(act)
            c.add(text(act, o, Type.BodyLarge, Pal.onSurface, lines = 0))
            c.add(text(act, S(R.string.rip_folder), Type.BodySmall, Pal.onSurfaceVariant))
            r.add(c, w = 0, weight = 1f)
            s.add(r)
        }
        p.libraryFolders.sorted().forEach { f ->
            val r = hbox(act)
            r.add(icon(act, Icons.Library, Pal.onSurfaceVariant), w = 24, h = 24, r = 16)
            r.add(text(act, f, Type.BodyLarge, Pal.onSurface, lines = 0), w = 0, weight = 1f)
            r.add(iconButton(act, Icons.Close, S(R.string.remove_from_library)) {
                val d = UDialog(act).title(S(R.string.remove_from_library_2)).message(f + "\n\n" + S(R.string.songs_in_this_folder_will))
                d.button(S(R.string.cancel)) { d.dismiss() }
                d.button(S(R.string.remove)) { d.dismiss(); p.set("libraryFolders", p.libraryFolders - f); lib.refresh(false) }
                d.show()
            }, w = 48, h = 48)
            s.add(r)
        }
        val btns = hbox(act)
        btns.add(button(act, S(R.string.add_folder), Btn.Tonal) { FolderPicker.pick(act, null) { LibraryPages.addFolder(act, it) } }, w = WRAP, r = 8)
        btns.add(button(act, S(R.string.rescan), Btn.Outlined) { lib.refresh(true) }.also { if (lib.scanning) it.alpha = 0.38f }, w = WRAP)
        s.add(btns)
        s.add(note(act, S(R.string.new_songs_are_added_automatically)))
        if (lib.scanning && (lib.full || lib.newCount > 0)) {
            s.add(com.uyatame.ucrt.legacy.ui.ProgressBarView(act))
            s.add(note(act, if (lib.full) S(R.string.rescanning_all_songs, lib.progress) else S(R.string.loading_new_songs, lib.progress), Pal.onSurface))
        }
        val out = arrayListOf<View>(s.view)
        // 「すべてのファイルへのアクセス」(Android 11 から。歌詞・ジャケットの画像・CUE シートを読むのに使う)
        if (Build.VERSION.SDK_INT >= 30) {
            val a = Section(act, S(R.string.all_files_access))
            a.add(note(act, S(R.string.allowing_this_lets_app_read)))
            val granted = com.uyatame.ucrt.legacy.compat.Api30.allFiles()
            a.add(text(act, S(if (granted) R.string.allowed else R.string.not_allowed), Type.BodyLarge, if (granted) Pal.primary else Pal.onSurfaceVariant))
            a.add(button(act, S(if (granted) R.string.open_settings_to_revoke else R.string.allow), Btn.Tonal) { com.uyatame.ucrt.legacy.compat.Api30.request(act) })
            out.add(a.view)
        }
        return out
    }

    // ---------------- 表示 ----------------

    private fun displayPage(act: LegacyActivity): List<View> {
        val s = Section(act, S(R.string.appearance))
        s.add(segmented(act, S(R.string.theme), listOf(S(R.string.auto), S(R.string.light), S(R.string.dark)), p.themeMode) { p.set("themeMode", it) })
        s.add(themeColors(act))
        s.add(textColor(act))
        s.add(switchRow(act, S(R.string.show_artwork_in_lists), S(R.string.turn_off_to_skip_loading), p.listArt) { p.set("listArt", it) })
        s.add(switchRow(act, S(R.string.show_artwork_per_song), S(R.string.when_a_song_has_its_own_artwork), p.trackArt) { p.set("trackArt", it) })
        if (Build.VERSION.SDK_INT >= 19) {
            s.add(switchRow(act, S(R.string.hide_navigation_bar), S(R.string.hides_navigation_bar_at_bottom), p.sp.getBoolean("hideNavBar", false)) { p.set("hideNavBar", it) })
        }
        s.add(switchRow(act, S(R.string.hide_status_bar), S(R.string.hide_status_bar_desc), p.sp.getBoolean("hideStatusBar", false)) { p.set("hideStatusBar", it) })
        s.add(segmented(act, S(R.string.tablet_mode), listOf(S(R.string.auto), S(R.string.on), S(R.string.off)), p.sp.getInt("tabletMode", 0)) { p.set("tabletMode", it) })
        s.add(note(act, S(R.string.moves_tabs_to_left_side)))
        return listOf(s.view)
    }

    /** テーマの色の見本(本家の ThemeColorPicker) */
    private fun themeColors(act: LegacyActivity): View {
        val col = vbox(act)
        col.add(text(act, S(R.string.theme_color), Type.BodyLarge))
        col.add(note(act, S(R.string.used_when_dynamic_color_is)))
        val row = hbox(act)
        val std = 0xFF6750A4.toInt()
        val value = p.themeColor
        for (c in Pal.presets) {
            val f = FrameLayout(act)
            val d = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (c == 0) std else c)
                if (c == value) setStroke(dp(3), Pal.onSurface)
            }
            f.bgd(d)
            if (c == 0) {
                val t = text(act, S(R.string.default_label), Type.LabelSmall, 0xFFFFFFFF.toInt())
                t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, com.uyatame.ucrt.legacy.ui.Ui.sp(9f))
                t.gravity = Gravity.CENTER
                f.add(t, h = MATCH)
            }
            f.isClickable = true
            f.setOnClickListener { p.set("themeColor", c) }
            row.add(f, w = 40, h = 40, r = 10)
        }
        val sc = android.widget.HorizontalScrollView(act)
        sc.isHorizontalScrollBarEnabled = false
        sc.addView(row)
        col.add(sc, t = 6, b = 6)
        col.add(colorRow(act, S(R.string.pick_any_color), if (value != 0 && value !in Pal.presets) hexOf(value) else S(R.string.pick_any_color_desc), if (value == 0) std else value) {
            ColorPicker.show(act, S(R.string.theme_color), if (value == 0) std else value, { p.set("themeColor", 0) }) { p.set("themeColor", it) }
        })
        return col
    }

    /** 文字の色(本家の TextColorPicker) */
    private fun textColor(act: LegacyActivity): View {
        val v = p.textColor
        return colorRow(act, S(R.string.text_color), if (v == 0) S(R.string.text_color_default) else hexOf(v), if (v == 0) Pal.onSurface else v) {
            ColorPicker.show(act, S(R.string.text_color), if (v == 0) Pal.onSurface else v, { p.set("textColor", 0) }) { p.set("textColor", it) }
        }
    }

    fun hexOf(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    private fun colorRow(act: LegacyActivity, title: String, desc: String, shown: Int, onClick: () -> Unit): View {
        val r = hbox(act)
        r.setPadding(0, dp(4), 0, dp(4))
        val c = vbox(act)
        c.add(text(act, title, Type.BodyLarge))
        c.add(note(act, desc))
        r.add(c, w = 0, weight = 1f)
        val sw = View(act)
        sw.bgd(android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(shown); setStroke(dp(1), Pal.outline) })
        r.add(sw, w = 32, h = 32)
        r.tap(8f) { onClick() }
        return r
    }

    // ---------------- 再生画面 ----------------

    private fun playerPage(act: LegacyActivity): List<View> {
        val s = Section(act, S(R.string.ambient_mode))
        s.add(switchRow(act, S(R.string.ambient_mode), S(R.string.tints_player_background_to_match), p.ambientPlayer) { p.set("ambientPlayer", it) })
        s.add(switchRow(act, S(R.string.fine_volume), S(R.string.fine_volume_desc), p.sp.getBoolean("fineVolume", false)) { p.set("fineVolume", it) })
        s.add(switchRow(act, S(R.string.auto_resume), S(R.string.auto_resume_desc), p.sp.getBoolean("autoResume", true)) { p.set("autoResume", it) })
        s.add(switchRow(act, S(R.string.auto_next_list), S(R.string.auto_next_list_desc), p.sp.getBoolean("autoNextList", false)) { p.set("autoNextList", it) })
        // 再生操作のボタンの並び(本家と同じ。上が左)
        val o = Section(act, S(R.string.control_order))
        o.add(note(act, S(R.string.control_order_desc)))
        val order = controlOrder(p.sp.getString("controlOrder", null))
        val names = mapOf('R' to S(R.string.repeat_all), 'P' to S(R.string.previous_track), 'X' to S(R.string.play), 'N' to S(R.string.next_track), 'S' to S(R.string.shuffle_2))
        val icons = mapOf('R' to Icons.Repeat, 'P' to Icons.SkipPrevious, 'X' to Icons.PlayArrow, 'N' to Icons.SkipNext, 'S' to Icons.Shuffle)
        fun swap(i: Int, j: Int): String { val c = order.toCharArray(); val t = c[i]; c[i] = c[j]; c[j] = t; return String(c) }
        order.forEachIndexed { i, c ->
            val row = com.uyatame.ucrt.legacy.ui.hbox(act, android.view.Gravity.CENTER_VERTICAL)
            row.add(com.uyatame.ucrt.legacy.ui.icon(act, icons[c]!!, Pal.primary), w = 24, h = 24, r = 12)
            row.add(text(act, names[c]!!, Type.BodyLarge, Pal.onSurface), w = 0, weight = 1f)
            row.add(iconButton(act, Icons.ArrowUp, S(R.string.move_up), if (i > 0) Pal.onSurface else Pal.outline) { if (i > 0) p.set("controlOrder", swap(i, i - 1)) }, w = 44, h = 44)
            row.add(iconButton(act, Icons.ArrowDown, S(R.string.move_down), if (i < 4) Pal.onSurface else Pal.outline) { if (i < 4) p.set("controlOrder", swap(i, i + 1)) }, w = 44, h = 44)
            o.add(row)
        }
        o.add(button(act, S(R.string.control_order_reset), Btn.Text) { p.set("controlOrder", "RPXNS") })
        // スペクトラムアナライザー(初めはオフ。オンにしても、再生画面を開いている間だけ計算する)
        val sp = Section(act, S(R.string.spectrum))
        sp.add(switchRow(act, S(R.string.spectrum), S(R.string.legacy_spectrum_desc), p.sp.getBoolean("spectrum", false)) { p.set("spectrum", it) })
        if (p.sp.getBoolean("spectrum", false)) {
            val opts = listOf(33, 50, 100)
            sp.add(segmented(act, S(R.string.spectrum_refresh), opts.map { "${1000 / it} fps" }, opts.indexOf(p.sp.getInt("legacySpectrumMs", 50)).coerceAtLeast(0)) {
                p.set("legacySpectrumMs", opts[it])
            })
        }
        return listOf(s.view, o.view, sp.view)
    }

    // ---------------- ログ ----------------

    private fun logPage(act: LegacyActivity): List<View> {
        val sw = switchRow(act, S(R.string.verbose_log), S(R.string.also_records_scsi_command_errors), p.verboseLog) { p.set("verboseLog", it) }
        val copy = button(act, S(R.string.copy_log), Btn.Tonal) {
            @Suppress("DEPRECATION")
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", AppLog.text))
        }
        val t = text(act, AppLog.text.ifEmpty { S(R.string.nothing_logged_yet) }, Type.BodySmall, Pal.onSurface, lines = 0)
        t.setTextIsSelectable(true)
        val w = FrameLayout(act); w.add(copy, w = WRAP, h = WRAP)
        return listOf(sw, w, t)
    }

    // ---------------- このアプリについて ----------------

    private class LicenseInfo(val name: String, val version: String, val copyright: String, val license: String, val licenseUrl: String, val sourceUrl: String?, val note: String, val asset: String? = null)

    private const val APACHE = "https://www.apache.org/licenses/LICENSE-2.0"
    private const val LGPL21 = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html"

    private val licenses: List<LicenseInfo> get() = listOf(
        LicenseInfo("Uyatame CD Ripping Tool", "", "Copyright (C) 2026 uyatame", "GNU General Public License v3.0", "https://www.gnu.org/licenses/gpl-3.0.html",
            com.uyatame.ucrt.meta.MusicBrainz.PROJECT_URL, S(R.string.this_app_is_open_source), "GPL-3.0.txt"),
        LicenseInfo(S(R.string.jump3r_mp3_encoder), "1.0.5", S(R.string.java_port_ken_h_ndel), S(R.string.gnu_lesser_general_public_license), LGPL21,
            "https://codeberg.org/sciss/jump3r", S(R.string.this_app_uses_jump3r_unmodified) + S(R.string.source_code_is_available_from) +
                S(R.string.under_lgpl_you_may_use) + S(R.string.you_can_use_this_projects) + S(R.string.full_text_of_lgpl_v2), "LGPL-2.1.txt"),
        LicenseInfo("FFmpeg", "8.1.3", "Copyright (c) 2000-2026 the FFmpeg developers", S(R.string.gnu_lesser_general_public_license), LGPL21,
            "https://github.com/FFmpeg/FFmpeg", S(R.string.ffmpeg_aac_tables_note) + S(R.string.full_text_of_lgpl_v2), "LGPL-2.1.txt"),
        LicenseInfo("minimp3", "", "lieff", "CC0 1.0 Universal", "https://creativecommons.org/publicdomain/zero/1.0/", "https://github.com/lieff/minimp3",
            S(R.string.minimp3_license_note), "CC0-1.0.txt"),
        LicenseInfo(S(R.string.kotlin_standard_library), "2.1.0", "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors", "Apache License 2.0", APACHE,
            "https://github.com/JetBrains/kotlin", "", "Apache-2.0.txt"),
        LicenseInfo(S(R.string.musicbrainz_track_data), "", "MetaBrainz Foundation and contributors", S(R.string.cc0_1_0_core_data),
            "https://creativecommons.org/publicdomain/zero/1.0/", "https://musicbrainz.org", S(R.string.titles_artist_names_and_similar), "CC0-1.0.txt"),
        LicenseInfo(S(R.string.cover_art_archive_cover_art), "", S(R.string.images_are_copyright_of_their), S(R.string.set_by_each_images_rights),
            "https://coverartarchive.org", "https://coverartarchive.org", S(R.string.downloaded_cover_art_is_for)),
        LicenseInfo(S(R.string.flac_encoder_mp4_id3_tagging), "", S(R.string.original_implementation_in_this_app), "—", "https://xiph.org/flac/", null,
            S(R.string.flac_is_open_royalty_free) + S(R.string.are_minimal_implementation_that_lets)),
    )

    private fun browse(act: LegacyActivity, url: String) {
        runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun aboutPage(act: LegacyActivity): List<View> {
        val out = ArrayList<View>()
        val s1 = Section(act, "Uyatame CD Ripping Tool (UCRT) Legacy")
        s1.add(text(act, S(R.string.version) + version(act), Type.BodyMedium))
        s1.add(note(act, S(R.string.rip_and_play_audio_cds)))
        out.add(s1.view)
        val s2 = Section(act, S(R.string.updates))
        s2.add(switchRow(act, S(R.string.check_for_updates_at_startup), S(R.string.shows_link_to_download_page), p.updateCheck) { p.set("updateCheck", it) })
        val r = hbox(act)
        r.add(button(act, S(if (Update.checking) R.string.checking else R.string.check_now), Btn.Tonal) { Update.check(act, true) }.also { if (Update.checking) it.alpha = 0.38f }, w = WRAP, r = 8)
        r.add(button(act, S(R.string.releases_page), Btn.Text) { browse(act, com.uyatame.ucrt.meta.UpdateCheck.RELEASES_PAGE) }, w = WRAP)
        s2.add(r)
        out.add(s2.view)
        out.add(text(act, S(R.string.licenses_2), Type.TitleMedium, Pal.primary).also { it.setPadding(dp(4), dp(4), 0, 0) })
        out.add(cardOf(act, licenses.map { l -> libRow(act, l.name, l.license) { licenseDialog(act, l) } }))
        out.add(text(act, S(R.string.included_license_texts), Type.TitleMedium, Pal.primary).also { it.setPadding(dp(4), dp(4), 0, 0) })
        out.add(cardOf(act, licenses.filter { it.asset != null }.distinctBy { it.asset }.map { l -> libRow(act, l.license, null) { licenseText(act, l.license, l.asset!!) } }))
        return out
    }

    private fun libRow(act: LegacyActivity, title: String, sub: String?, onClick: () -> Unit): View {
        val r = hbox(act)
        r.setPadding(dp(16), dp(12), dp(16), dp(12))
        val c = vbox(act)
        c.add(text(act, title, Type.BodyLarge, Pal.onSurface, lines = 0))
        if (sub != null) c.add(text(act, sub, Type.BodyMedium, Pal.onSurfaceVariant, lines = 0))
        r.add(c, w = 0, weight = 1f)
        r.add(icon(act, Icons.ArrowRight, Pal.onSurfaceVariant), w = 24, h = 24)
        r.tap(0f) { onClick() }
        return r
    }

    private fun licenseDialog(act: LegacyActivity, l: LicenseInfo) {
        val d = UDialog(act).title(l.name)
        if (l.version.isNotEmpty()) d.message(S(R.string.version_2, l.version), Pal.onSurface)
        d.message(S(R.string.copyright, l.copyright), Pal.onSurface)
        d.message(S(R.string.license, l.license), Pal.onSurface)
        if (l.note.isNotEmpty()) d.message(l.note)
        if (l.sourceUrl != null) d.view(button(act, S(R.string.view_source_code_website), Btn.Text) { browse(act, l.sourceUrl) })
        d.button(if (l.asset != null) S(R.string.full_license_text) else S(R.string.official_site)) {
            d.dismiss()
            if (l.asset != null) licenseText(act, l.license, l.asset) else browse(act, l.licenseUrl)
        }
        d.button(S(R.string.close)) { d.dismiss() }
        d.show()
    }

    private fun licenseText(act: LegacyActivity, title: String, asset: String) {
        val txt = runCatching { act.assets.open("licenses/$asset").bufferedReader().use { it.readText() } }.getOrNull() ?: S(R.string.license_text_not_found_it)
        val d = UDialog(act).title(title)
        val t = text(act, txt, Type.BodySmall, Pal.onSurface, lines = 0)
        t.typeface = Typeface.MONOSPACE
        t.setTextIsSelectable(true)
        d.view(t)
        d.close(S(R.string.close))
        d.show()
    }
}
