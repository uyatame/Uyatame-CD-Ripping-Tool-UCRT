package com.uyatame.ucrt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.uyatame.ucrt.data.qualityLabel
import com.uyatame.ucrt.player.AudioEngine
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.outputMode
import com.uyatame.ucrt.data.AudioFormat
import com.uyatame.ucrt.data.Keys
import com.uyatame.ucrt.data.MP3_RATES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: MainViewModel, s: AppSettings, page: Int, onPage: (Int) -> Unit) {
    val back = { onPage(0) }
    // 横向きでは、横に長くなりすぎないよう読みやすい幅にして中央に寄せる
    WideCenter {
    when (page) {
        1 -> SettingsPage(S(R.string.saving_ripping), back) { RipPage(vm, s) }
        2 -> SettingsPage(S(R.string.rip_quality), back) { QualityPage(vm, s) }
        3 -> SettingsPage(S(R.string.cd_drive), back) { DrivePage(vm, s) }
        4 -> SettingsPage(S(R.string.library), back) { LibraryFoldersPage(vm, s) }
        5 -> SettingsPage(S(R.string.display), back) { DisplayPage(vm, s) }
        9 -> SettingsPage(S(R.string.player_screen), back) { PlayerScreenPage(vm, s) }
        6 -> LogPage(vm, s, back)
        7 -> LicensePage(vm, s, back)
        8 -> SettingsPage(SoundPage.title(SoundPage.MAIN), back) { SoundMain(vm, s) { onPage(80 + it) } }
        in 81..89 -> SettingsPage(SoundPage.title(page - 80), { onPage(8) }) { SoundSubPage(vm, s, page - 80) }
        else -> MainSettings(vm, s, onPage)
    }
    }
}

fun folderLabel(uri: String?): String {
    if (uri == null) return S(R.string.not_set)
    // SDカードなど、保存場所ごとの名前を出す
    return com.uyatame.ucrt.library.StorageAccess.label(uri)
}

/** 各設定ページの共通の枠(見出し・戻るボタン・スクロール) */
@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** 設定のカテゴリ一行(アイコン・名前・今の設定の要約) */
@Composable
private fun CategoryRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
}

/** カード内の小見出し(詳細な設定の区切り) */
@Composable
private fun SubHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun MainSettings(vm: MainViewModel, s: AppSettings, onPage: (Int) -> Unit) {
    val ctx = LocalContext.current
    val folders = 1 + s.libraryFolders.size - (if (s.outputUri == null) 1 else 0)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(S(R.string.settings))
        // ---- CD の取り込み ----
        GroupLabel(S(R.string.settings_group_rip))
        Card(Modifier.fillMaxWidth()) {
            CategoryRow(
                AppIcons.Download, S(R.string.saving_ripping),
                folderLabel(s.outputUri) + S(R.string.auto_track_info) + if (s.autoMeta) "ON" else "OFF",
            ) { onPage(1) }
            HorizontalDivider()
            CategoryRow(AppIcons.MusicNote, S(R.string.rip_quality), s.qualityLabel()) { onPage(2) }
            HorizontalDivider()
            CategoryRow(
                AppIcons.Album, S(R.string.cd_drive),
                S(R.string.read_speed) + (if (s.speedX == 0) S(R.string.max) else "${s.speedX}x") +
                    S(R.string.retries_2, s.retries),
            ) { onPage(3) }
        }
        // ---- 音楽プレーヤー ----
        GroupLabel(S(R.string.settings_group_player))
        Card(Modifier.fillMaxWidth()) {
            CategoryRow(
                AppIcons.Library, S(R.string.library),
                S(R.string.folders_add_music_from_your, folders.coerceAtLeast(0)),
            ) { onPage(4) }
            HorizontalDivider()
            CategoryRow(
                AppIcons.Headphones, S(R.string.sound_settings),
                (if (s.sourceDirect) S(R.string.source_direct) else S(R.string.eq) + (if (s.eqEnabled) "ON" else "OFF") +
                    (if (s.darMode != 0) S(R.string.separator) + "DAR" else "")) +
                    (if (s.outputMode == 2) S(R.string.usb_dac_direct) else ""),
            ) { onPage(8) }
            HorizontalDivider()
            CategoryRow(
                Icons.Filled.PlayArrow, S(R.string.player_screen),
                S(R.string.ambient_mode) + " " + (if (s.ambientPlayer) "ON" else "OFF"),
            ) { onPage(9) }
        }
        // ---- 全般 ----
        GroupLabel(S(R.string.settings_group_general))
        Card(Modifier.fillMaxWidth()) {
            CategoryRow(
                AppIcons.Smartphone, S(R.string.display),
                listOf(S(R.string.auto), S(R.string.light), S(R.string.dark))[s.themeMode.coerceIn(0, 2)] +
                    (if (AppLanguage.supported) S(R.string.separator) + languageLabel(AppLanguage.current(ctx)) else ""),
            ) { onPage(5) }
            HorizontalDivider()
            CategoryRow(Icons.Filled.Build, S(R.string.log), S(R.string.activity_log_for_troubleshooting)) { onPage(6) }
            HorizontalDivider()
            CategoryRow(
                Icons.Filled.Info, S(R.string.about_this_app),
                S(R.string.version) + BuildConfigVersion.name(ctx) + S(R.string.licenses),
            ) { onPage(7) }
        }
        // ---- USB DAC の解放・アプリの終了 ----
        Card(Modifier.fillMaxWidth()) {
            if (vm.usbDacInUse) {
                CategoryRow(AppIcons.Usb, S(R.string.release_usb_dac), S(R.string.release_usb_dac_desc)) { vm.releaseUsbDac() }
                HorizontalDivider()
            }
            CategoryRow(Icons.AutoMirrored.Filled.ExitToApp, S(R.string.quit_app), S(R.string.quit_app_desc)) {
                vm.quitApp { (ctx as? android.app.Activity)?.finishAndRemoveTask() }
            }
        }
    }
}

/** 設定のトップの、カテゴリーのまとまりの見出し */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
    )
}

private fun languageLabel(tag: String): String = when {
    tag.startsWith("ja") -> "日本語"
    tag.startsWith("en") -> "English"
    else -> S(R.string.system_language)
}

@Composable
private fun RipPage(vm: MainViewModel, s: AppSettings) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setOutputFolder(uri)
    }
    Section(S(R.string.save_folder)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(folderLabel(s.outputUri), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            FilledTonalButton({ picker.launch(null) }) { Text(if (s.outputUri == null) S(R.string.choose) else S(R.string.change)) }
        }
        SubHeader(S(R.string.folders_file_names))
        ChoiceList(
            S(R.string.folder_structure),
            listOf(S(R.string.artist_album_track), S(R.string.album_track), S(R.string.no_subfolders)),
            s.folderMode,
        ) { vm.set(Keys.folderMode, it) }
        ChoiceList(
            S(R.string.file_name),
            listOf(S(R.string.n01_title), S(R.string.n01_artist_title), "Track01"),
            s.fileNameMode,
        ) { vm.set(Keys.fileNameMode, it) }
    }
    Section(S(R.string.when_cd_is_inserted)) {
        SwitchRow(
            S(R.string.get_track_info_automatically),
            S(R.string.fetches_titles_artists_and_cover),
            s.autoMeta,
        ) { vm.set(Keys.autoMeta, it) }
        SwitchRow(S(R.string.open_disc_tab), null, s.autoOpenDisc) { vm.set(Keys.autoOpenDisc, it) }
    }
    Section(S(R.string.when_ripping)) {
        SwitchRow(S(R.string.embed_cover_art_in_files), null, s.embedCover) { vm.set(Keys.embedCover, it) }
        SwitchRow(S(R.string.save_cover_art_to_folder), null, s.saveCover) { vm.set(Keys.saveCover, it) }
        SwitchRow(S(R.string.eject_cd_after_ripping), null, s.ejectAfter) { vm.set(Keys.ejectAfter, it) }
        SwitchRow(S(R.string.keep_screen_on_while_ripping), null, s.keepScreenOn) { vm.set(Keys.keepScreenOn, it) }
    }
}

@Composable
private fun QualityPage(vm: MainViewModel, s: AppSettings) {
    Section(S(R.string.presets)) { QualityOptions(vm, s) }
    Section(S(R.string.custom_2)) {
        Segmented(S(R.string.format), AudioFormat.entries.map { it.label }, s.format.ordinal) { vm.set(Keys.format, it) }
        when (s.format) {
            AudioFormat.FLAC -> IntSlider(
                S(R.string.flac_compression_level), s.flacLevel, 0..8, { "$it" },
                S(R.string.sound_quality_is_same_at),
            ) { vm.set(Keys.flacLevel, it) }
            AudioFormat.AAC -> IntSlider(
                S(R.string.aac_bitrate), s.aacKbps, 64..320, { "${snap32(it)} kbps" },
                S(R.string.higher_bitrates_mean_better_quality),
            ) { vm.set(Keys.aacKbps, snap32(it)) }
            AudioFormat.MP3 -> {
                IntSlider(
                    S(R.string.mp3_bitrate), MP3_RATES.indexOf(s.mp3Kbps).coerceAtLeast(0), 0..MP3_RATES.lastIndex,
                    { "${MP3_RATES[it]} kbps" },
                    S(R.string.constant_bitrate_cbr_320_kbps),
                ) { vm.set(Keys.mp3Kbps, MP3_RATES[it]) }
                Segmented(
                    S(R.string.encoding_quality), listOf(S(R.string.fast), S(R.string.standard), S(R.string.high)),
                    when { s.mp3Quality >= 5 -> 0; s.mp3Quality >= 3 -> 1; else -> 2 },
                ) { vm.set(Keys.mp3Quality, listOf(7, 5, 2)[it]) }
            }
            AudioFormat.WAV -> Text(
                S(R.string.wav_files_are_saved_uncompressed),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (s.format == AudioFormat.FLAC || s.format == AudioFormat.MP3) {
            SwitchRow(S(R.string.use_ffmpeg), S(R.string.use_ffmpeg_desc), s.useFfmpeg) { vm.set(Keys.useFfmpeg, it) }
        }
    }
}

@Composable
private fun DrivePage(vm: MainViewModel, s: AppSettings) {
    Section(S(R.string.reading)) {
        IntSlider(
            S(R.string.read_speed_2), s.speedX, 0..52, { if (it == 0) S(R.string.max) else "${it}x" },
            S(R.string.slower_speeds_can_help_read),
        ) { vm.set(Keys.speedX, it) }
        IntSlider(
            S(R.string.retries), s.retries, 0..20, { S(R.string.label_4, it) },
            S(R.string.how_many_times_to_retry),
        ) { vm.set(Keys.retries, it) }
        SwitchRow(
            S(R.string.stop_on_error), S(R.string.when_off_unreadable_parts_are),
            s.abortOnError,
        ) { vm.set(Keys.abortOnError, it) }
    }
    Section(S(R.string.advanced)) {
        IntSlider(
            S(R.string.read_offset_correction), s.offsetSamples, -2000..2000, { S(R.string.samples, it) },
            S(R.string.corrects_drives_read_position_offset),
        ) { vm.set(Keys.offsetSamples, it) }
        IntSlider(
            S(R.string.sectors_per_read), s.sectorsPerRead, 1..27, { "$it" },
            S(R.string.you_usually_dont_need_to),
        ) { vm.set(Keys.sectorsPerRead, it) }
        SwitchRow(
            S(R.string.c2_error_detection),
            S(R.string.works_only_on_supported_drives),
            s.useC2,
        ) { vm.set(Keys.useC2, it) }
    }
    Section(S(R.string.cd_playback)) {
        IntSlider(
            S(R.string.drive_speed_during_playback), s.cdPlaySpeed, 1..8, { "${it}x" },
            S(R.string.lower_speeds_make_drive_quieter),
        ) { vm.set(Keys.cdPlaySpeed, it) }
    }
    var confirmReset by remember { mutableStateOf(false) }
    OutlinedButton({ confirmReset = true }, Modifier.fillMaxWidth()) { Text(S(R.string.reset_cd_drive_settings)) }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(S(R.string.reset_to_defaults)) },
            text = { Text(S(R.string.read_speed_retries_offset_and)) },
            confirmButton = { TextButton({ confirmReset = false; vm.resetDriveSettings() }) { Text(S(R.string.reset_3)) } },
            dismissButton = { TextButton({ confirmReset = false }) { Text(S(R.string.cancel)) } },
        )
    }
}

@Composable
private fun LibraryFoldersPage(vm: MainViewModel, s: AppSettings) {
    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addLibraryFolder(uri)
    }
    Section(S(R.string.folders_shown_in_library)) {
        Text(
            S(R.string.along_with_your_rip_folder),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.outputUri != null) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(AppIcons.Download, null) },
                headlineContent = { Text(folderLabel(s.outputUri)) },
                supportingContent = { Text(S(R.string.rip_folder)) },
            )
        }
        var removing by remember { mutableStateOf<String?>(null) }
        s.libraryFolders.sorted().forEach { f ->
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(AppIcons.Library, null) },
                headlineContent = { Text(folderLabel(f)) },
                trailingContent = {
                    IconButton({ removing = f }) { Icon(Icons.Filled.Clear, S(R.string.remove_from_library)) }
                },
            )
        }
        removing?.let { f ->
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text(S(R.string.remove_from_library_2)) },
                text = {
                    Text(
                        folderLabel(f) + "\n\n" + S(R.string.songs_in_this_folder_will),
                    )
                },
                confirmButton = { TextButton({ removing = null; vm.removeLibraryFolder(f) }) { Text(S(R.string.remove)) } },
                dismissButton = { TextButton({ removing = null }) { Text(S(R.string.cancel)) } },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ addPicker.launch(null) }) { Text(S(R.string.add_folder)) }
            OutlinedButton({ vm.refreshLibrary(fresh = true) }, enabled = !vm.libScanning) { Text(S(R.string.rescan)) }
        }
        Text(
            S(R.string.new_songs_are_added_automatically),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vm.libScanning && (vm.libFull || vm.libNew > 0)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                if (vm.libFull) S(R.string.rescanning_all_songs, vm.libProgress)
                else S(R.string.loading_new_songs, vm.libProgress),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (com.uyatame.ucrt.library.StorageAccess.allFilesSupported) AllFilesSection(vm)
}

/** 「すべてのファイルへのアクセス」の許可(任意) */
@Composable
private fun AllFilesSection(vm: MainViewModel) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(com.uyatame.ucrt.library.StorageAccess.allFiles) }
    // 設定画面から戻ってきたときに反映する
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            val now = com.uyatame.ucrt.library.StorageAccess.allFiles
            if (now != granted) {
                granted = now
                if (now) vm.refreshLibrary(fresh = true)
            }
        }
    }
    Section(S(R.string.all_files_access)) {
        Text(
            S(R.string.allowing_this_lets_app_read),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (granted) S(R.string.allowed) else S(R.string.not_allowed),
            style = MaterialTheme.typography.bodyLarge,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton({
            val sa = com.uyatame.ucrt.library.StorageAccess
            runCatching { ctx.startActivity(sa.requestIntent(ctx)) }
                .onFailure { runCatching { ctx.startActivity(sa.fallbackIntent()) } }
        }) { Text(if (granted) S(R.string.open_settings_to_revoke) else S(R.string.allow)) }
    }
}

@Composable
private fun DisplayPage(vm: MainViewModel, s: AppSettings) {
    Section(S(R.string.appearance)) {
        Segmented(S(R.string.theme), listOf(S(R.string.auto), S(R.string.light), S(R.string.dark)), s.themeMode) { vm.set(Keys.themeMode, it) }
        // ダイナミックカラーは Android 12 から
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            SwitchRow(S(R.string.dynamic_color), S(R.string.matches_your_wallpaper_colors), s.dynamicColor) { vm.set(Keys.dynamicColor, it) }
        }
        if (!s.dynamicColor || android.os.Build.VERSION.SDK_INT < 31) {
            ThemeColorPicker(s.themeColor) { vm.set(Keys.themeColor, it) }
        }
        TextColorPicker(s.textColor) { vm.set(Keys.textColor, it) }
        SwitchRow(
            S(R.string.show_artwork_in_lists),
            S(R.string.turn_off_to_skip_loading),
            s.listArt,
        ) { vm.set(Keys.listArt, it) }
        SwitchRow(
            S(R.string.show_artwork_per_song),
            S(R.string.when_a_song_has_its_own_artwork),
            s.trackArt,
        ) { vm.set(Keys.trackArt, it) }
        SwitchRow(
            S(R.string.hide_navigation_bar),
            S(R.string.hides_navigation_bar_at_bottom),
            s.hideNavBar,
        ) { vm.set(Keys.hideNavBar, it) }
        Segmented(S(R.string.tablet_mode), listOf(S(R.string.auto), S(R.string.on), S(R.string.off)), s.tabletMode) { vm.set(Keys.tabletMode, it) }
        Text(
            S(R.string.moves_tabs_to_left_side),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    LanguageSection()
}

/** 再生画面の設定(アンビエントモード・ほかの音楽アプリ) */
@Composable
private fun PlayerScreenPage(vm: MainViewModel, s: AppSettings) {
    Section(S(R.string.ambient_mode)) {
        SwitchRow(
            S(R.string.ambient_mode),
            S(R.string.tints_player_background_to_match),
            s.ambientPlayer,
        ) { vm.set(Keys.ambientPlayer, it) }
        // ぼかしは Android 12 から(それより前の端末では、もともとぼかさずに表示している)
        if (s.ambientPlayer && android.os.Build.VERSION.SDK_INT >= 31) {
            SwitchRow(
                S(R.string.blur_background),
                S(R.string.turn_off_to_show_album),
                s.ambientBlur,
            ) { vm.set(Keys.ambientBlur, it) }
            if (s.ambientBlur) {
                IntSlider(
                    S(R.string.blur_strength), s.ambientBlurLevel, 1..10, { "$it" },
                    S(R.string.blur_strength_desc),
                ) { vm.set(Keys.ambientBlurLevel, it) }
            }
        }
    }
}

/** 表示言語(Android 13 から) */
@Composable
private fun LanguageSection() {
    if (AppLanguage.supported) {
        val ctx = LocalContext.current
        var lang by remember { mutableStateOf(AppLanguage.current(ctx)) }
        Section("言語 / Language") {
            val tags = listOf("", "ja", "en")
            val sel = tags.indexOfFirst { lang.startsWith(it) && it.isNotEmpty() }.let { if (it < 0) 0 else it }
            ChoiceList(
                S(R.string.display_language),
                listOf(S(R.string.system_default), "日本語", "English"),
                sel,
            ) { i ->
                lang = tags[i]
                AppLanguage.set(ctx, tags[i])
            }
        }
    }
}

private fun snap32(v: Int): Int = (((v + 16) / 32) * 32).coerceIn(64, 320)

@Composable
private fun LogPage(vm: MainViewModel, s: AppSettings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        PageHeader(S(R.string.log), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SwitchRow(S(R.string.verbose_log), S(R.string.also_records_scsi_command_errors), s.verboseLog) { vm.set(Keys.verboseLog, it) }
            FilledTonalButton({
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", vm.logText))
            }) { Text(S(R.string.copy_log)) }
            SelectionContainer {
                Text(
                    if (vm.logText.isEmpty()) S(R.string.nothing_logged_yet) else vm.logText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private class LicenseInfo(
    val name: String,
    val version: String,
    val copyright: String,
    val license: String,
    val licenseUrl: String,
    val sourceUrl: String?,
    val note: String,
    val asset: String? = null,
)

private const val APACHE = "https://www.apache.org/licenses/LICENSE-2.0"
private const val LGPL21 = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html"

private val LICENSES: List<LicenseInfo> get() = listOf(
    LicenseInfo(
        "Uyatame CD Ripping Tool", "", "Copyright (C) 2026 uyatame",
        "GNU General Public License v3.0", "https://www.gnu.org/licenses/gpl-3.0.html",
        com.uyatame.ucrt.meta.MusicBrainz.PROJECT_URL,
        S(R.string.this_app_is_open_source),
        "GPL-3.0.txt",
    ),
    LicenseInfo(
        S(R.string.jump3r_mp3_encoder), "1.0.5",
        S(R.string.java_port_ken_h_ndel),
        S(R.string.gnu_lesser_general_public_license), LGPL21,
        "https://codeberg.org/sciss/jump3r",
        S(R.string.this_app_uses_jump3r_unmodified) +
            S(R.string.source_code_is_available_from) +
            S(R.string.under_lgpl_you_may_use) +
            S(R.string.you_can_use_this_projects) +
            S(R.string.full_text_of_lgpl_v2),
        "LGPL-2.1.txt",
    ),
    LicenseInfo(
        "FFmpeg", "8.1.3", "Copyright (c) 2000-2026 the FFmpeg developers",
        S(R.string.gnu_lesser_general_public_license), LGPL21,
        "https://github.com/FFmpeg/FFmpeg",
        S(R.string.ffmpeg_license_note) + S(R.string.ffmpeg_aac_tables_note) + S(R.string.full_text_of_lgpl_v2),
        "LGPL-2.1.txt",
    ),
    LicenseInfo(
        "LAME", "3.100", "Copyright (c) 1999-2017 The LAME Project",
        S(R.string.gnu_lesser_general_public_license), LGPL21,
        "https://lame.sourceforge.io/",
        S(R.string.lame_license_note),
        "LGPL-2.1.txt",
    ),
    LicenseInfo(
        "minimp3", "", "lieff",
        "CC0 1.0 Universal", "https://creativecommons.org/publicdomain/zero/1.0/",
        "https://github.com/lieff/minimp3",
        S(R.string.minimp3_license_note),
        "CC0-1.0.txt",
    ),
    LicenseInfo(
        "JNA (Java Native Access)", "5.14.0", "Copyright (c) 2007-2024 Timothy Wall and JNA contributors",
        S(R.string.apache_license_2_0_chosen),
        APACHE, "https://github.com/java-native-access/jna",
        S(R.string.used_for_usb_isochronous_transfers),
        "Apache-2.0.txt",
    ),
    LicenseInfo(
        "Spotify App Remote SDK", "0.8.0", "Copyright (c) 2015 Spotify AB",
        "Apache License 2.0", APACHE, "https://github.com/spotify/android-sdk",
        S(R.string.spotify_sdk_license_note),
        "Apache-2.0.txt",
    ),
    LicenseInfo(
        "Gson", "2.10.1", "Copyright 2008 Google Inc.",
        "Apache License 2.0", APACHE, "https://github.com/google/gson", "", "Apache-2.0.txt",
    ),
    LicenseInfo(
        S(R.string.kotlin_standard_library), "2.1.0", "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors",
        "Apache License 2.0", APACHE, "https://github.com/JetBrains/kotlin", "", "Apache-2.0.txt",
    ),
    LicenseInfo(
        "kotlinx.coroutines", "1.9.0", "Copyright JetBrains s.r.o. and contributors",
        "Apache License 2.0", APACHE, "https://github.com/Kotlin/kotlinx.coroutines", "", "Apache-2.0.txt",
    ),
    LicenseInfo(
        "AndroidX(Core / Activity / Lifecycle / DataStore / DocumentFile)", "", "Copyright The Android Open Source Project",
        "Apache License 2.0", APACHE, "https://android.googlesource.com/platform/frameworks/support", "", "Apache-2.0.txt",
    ),
    LicenseInfo(
        "Jetpack Compose / Material 3 / Material Icons", "BOM 2025.04.01", "Copyright The Android Open Source Project",
        "Apache License 2.0", APACHE, "https://android.googlesource.com/platform/frameworks/support", "", "Apache-2.0.txt",
    ),
    LicenseInfo(
        S(R.string.musicbrainz_track_data), "", "MetaBrainz Foundation and contributors",
        S(R.string.cc0_1_0_core_data), "https://creativecommons.org/publicdomain/zero/1.0/", "https://musicbrainz.org",
        S(R.string.titles_artist_names_and_similar),
        "CC0-1.0.txt",
    ),
    LicenseInfo(
        S(R.string.cover_art_archive_cover_art), "", S(R.string.images_are_copyright_of_their),
        S(R.string.set_by_each_images_rights), "https://coverartarchive.org", "https://coverartarchive.org",
        S(R.string.downloaded_cover_art_is_for),
    ),
    LicenseInfo(
        S(R.string.flac_encoder_mp4_id3_tagging), "", S(R.string.original_implementation_in_this_app),
        "—", "https://xiph.org/flac/", null,
        S(R.string.flac_is_open_royalty_free) +
            S(R.string.are_minimal_implementation_that_lets),
    ),
    LicenseInfo(
        S(R.string.aac_encoder_and_decoders), "", S(R.string.built_into_android),
        S(R.string.provided_by_os), "https://source.android.com/", null,
        S(R.string.aac_encoding_and_playback_use),
    ),
)

@Composable
private fun LicenseTextPage(title: String, asset: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val text by produceState<String?>(null, asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open("licenses/$asset").bufferedReader().use { it.readText() } }.getOrNull()
                ?: S(R.string.license_text_not_found_it)
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            SelectionContainer {
                Text(text ?: S(R.string.loading_2), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun LicensePage(vm: MainViewModel, s: AppSettings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    var open by remember { mutableStateOf<LicenseInfo?>(null) }
    var reading by remember { mutableStateOf<LicenseInfo?>(null) }
    fun browse(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    val r = reading
    if (r != null && r.asset != null) {
        BackHandler { reading = null }
        LicenseTextPage(r.license, r.asset) { reading = null }
        return
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(S(R.string.about_this_app), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Uyatame CD Ripping Tool (UCRT)") {
                Text(S(R.string.version) + BuildConfigVersion.name(ctx), style = MaterialTheme.typography.bodyMedium)
                Text(
                    S(R.string.rip_and_play_audio_cds),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Section(S(R.string.updates)) {
                SwitchRow(
                    S(R.string.check_for_updates_at_startup),
                    S(R.string.shows_link_to_download_page),
                    s.updateCheck,
                ) { vm.set(Keys.updateCheck, it) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ vm.checkUpdate(manual = true) }, enabled = !vm.updateChecking) {
                        Text(if (vm.updateChecking) S(R.string.checking) else S(R.string.check_now))
                    }
                    TextButton({ browse(com.uyatame.ucrt.meta.UpdateCheck.RELEASES_PAGE) }) { Text(S(R.string.releases_page)) }
                }
            }
            Text(
                S(R.string.licenses_2), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            Card(Modifier.fillMaxWidth()) {
                LICENSES.forEachIndexed { i, l ->
                    if (i > 0) HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable { open = l },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(l.name) },
                        supportingContent = { Text(l.license) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    )
                }
            }
            Text(
                S(R.string.included_license_texts), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            Card(Modifier.fillMaxWidth()) {
                LICENSES.filter { it.asset != null }.distinctBy { it.asset }.forEachIndexed { i, l ->
                    if (i > 0) HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable { reading = l },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(l.license) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    )
                }
            }
        }
    }
    val l = open
    if (l != null) {
        AlertDialog(
            onDismissRequest = { open = null },
            confirmButton = { TextButton({ open = null }) { Text(S(R.string.close)) } },
            dismissButton = {
                TextButton({
                    if (l.asset != null) { open = null; reading = l } else browse(l.licenseUrl)
                }) { Text(if (l.asset != null) S(R.string.full_license_text) else S(R.string.official_site)) }
            },
            title = { Text(l.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (l.version.isNotEmpty()) Text(S(R.string.version_2, l.version), style = MaterialTheme.typography.bodyMedium)
                    Text(S(R.string.copyright, l.copyright), style = MaterialTheme.typography.bodyMedium)
                    Text(S(R.string.license, l.license), style = MaterialTheme.typography.bodyMedium)
                    if (l.note.isNotEmpty()) Text(l.note, style = MaterialTheme.typography.bodySmall)
                    if (l.sourceUrl != null) {
                        TextButton({ browse(l.sourceUrl) }) { Text(S(R.string.view_source_code_website)) }
                    }
                }
            },
        )
    }
}

/** テーマの色を選ぶ(見本から選ぶか、色相と鮮やかさを動かして決める) */
@Composable
private fun ThemeColorPicker(value: Int, onChange: (Int) -> Unit) {
    val std = 0xFF6750A4.toInt()
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        ColorPickerDialog(
            S(R.string.theme_color), if (value == 0) std else value, { picking = false }, onChange,
            onReset = { onChange(0) },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(S(R.string.theme_color), style = MaterialTheme.typography.bodyLarge)
        Text(
            S(R.string.used_when_dynamic_color_is),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (c in ThemeColors.presets) {
                val sel = c == value
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color(if (c == 0) std else c))
                        .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable { onChange(c) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (c == 0) Text(S(R.string.default_label), color = Color.White, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        // 見本にない色は、カラーピッカーで自由に選ぶ
        val custom = value != 0 && value !in ThemeColors.presets
        ColorSettingRow(
            S(R.string.pick_any_color), if (custom) hexOf(value) else S(R.string.pick_any_color_desc),
            Color(if (value == 0) std else value), false,
        ) { picking = true }
    }
}

/** 文字の色(カラーピッカーで選ぶ。背景と近くて読みにくいときは知らせる) */
@Composable
private fun TextColorPicker(value: Int, onChange: (Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    if (picking) {
        ColorPickerDialog(
            S(R.string.text_color), if (value == 0) scheme.onSurface.toArgb() else value, { picking = false }, onChange,
            onReset = { onChange(0) },
            preview = { c ->
                // 今の背景の上での見え方
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(scheme.surface)
                        .border(1.dp, scheme.outline, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                ) { Text(S(R.string.text_sample), color = Color(c), style = MaterialTheme.typography.titleMedium) }
            },
        )
    }
    ColorSettingRow(
        S(R.string.text_color),
        if (value == 0) S(R.string.text_color_default) else hexOf(value),
        if (value == 0) scheme.onSurface else Color(value), value == 0,
    ) { picking = true }
    if (value != 0 && contrast(Color(value), scheme.surface) < 3f) {
        Text(S(R.string.text_color_low_contrast), style = MaterialTheme.typography.bodySmall, color = scheme.error)
    }
}

/** 2 つの色の明るさの比(1〜21。4.5 以上なら本文として読みやすい) */
private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return maxOf(la, lb) / minOf(la, lb)
}

/** ほかの音楽アプリ(ストリーミングサービスなど)の再生の表示・操作と、「通知へのアクセス」の許可 */
@Composable
internal fun ExternalMediaSection(vm: MainViewModel, s: AppSettings) {
    val ctx = LocalContext.current
    val ext = com.uyatame.ucrt.player.ExternalMedia
    // 許可の画面から戻ってきたときの反映は、MainActivity.onResume が行う(ここで 1 秒ごとに確かめると、
    // 開いている間ずっと、ほかのアプリの再生の情報を読み直して画面を描き直すことになり重い)
    LaunchedEffect(s.externalMedia) { ext.setEnabled(ctx, s.externalMedia) }
    Section(S(R.string.other_music_apps)) {
        SwitchRow(S(R.string.show_other_apps_playback), S(R.string.other_apps_playback_desc), s.externalMedia) {
            vm.set(Keys.externalMedia, it)
        }
        if (s.externalMedia) {
            Text(S(R.string.notification_access), style = MaterialTheme.typography.bodyLarge)
            Text(
                S(R.string.notification_access_desc),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (ext.permitted) S(R.string.allowed) else S(R.string.not_allowed),
                style = MaterialTheme.typography.bodyLarge,
                color = if (ext.permitted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton({ ext.openPermissionSettings(ctx) }) {
                Text(if (ext.permitted) S(R.string.open_settings_to_revoke) else S(R.string.allow))
            }
            // Android 13 以降、GitHub などから入れたアプリは「制限付き設定」を許可するまで、通知へのアクセスをオンにできない
            if (!ext.permitted && android.os.Build.VERSION.SDK_INT >= 33) {
                Text(
                    S(R.string.restricted_settings_help),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton({
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.fromParts("package", ctx.packageName, null))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text(S(R.string.open_app_info)) }
            }
        }
        Text(
            S(R.string.streaming_trademark_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
