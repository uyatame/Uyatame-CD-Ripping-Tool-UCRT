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
        1 -> SettingsPage(T("保存と取り込み", "Saving & ripping"), back) { RipPage(vm, s) }
        2 -> SettingsPage(T("音質", "Quality"), back) { QualityPage(vm, s) }
        3 -> SettingsPage(T("CDドライブ", "CD drive"), back) { DrivePage(vm, s) }
        4 -> SettingsPage(T("ライブラリ", "Library"), back) { LibraryFoldersPage(vm, s) }
        5 -> SettingsPage(T("表示", "Display"), back) { DisplayPage(vm, s) }
        6 -> LogPage(vm, s, back)
        7 -> LicensePage(vm, s, back)
        8 -> SettingsPage(SoundPage.title(SoundPage.MAIN), back) { SoundMain(vm, s) { onPage(80 + it) } }
        in 81..89 -> SettingsPage(SoundPage.title(page - 80), { onPage(8) }) { SoundSubPage(vm, s, page - 80) }
        else -> MainSettings(vm, s, onPage)
    }
    }
}

fun folderLabel(uri: String?): String {
    if (uri == null) return T("未設定", "Not set")
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
        ScreenHeader(T("設定", "Settings"))
        Card(Modifier.fillMaxWidth()) {
            CategoryRow(
                AppIcons.Download, T("保存と取り込み", "Saving & ripping"),
                folderLabel(s.outputUri) + T(" ・ 曲情報の自動取得 ", " · Auto track info ") + if (s.autoMeta) "ON" else "OFF",
            ) { onPage(1) }
            HorizontalDivider()
            CategoryRow(AppIcons.MusicNote, T("音質", "Quality"), s.qualityLabel()) { onPage(2) }
            HorizontalDivider()
            CategoryRow(
                AppIcons.Album, T("CDドライブ", "CD drive"),
                T("読み取り速度 ", "Read speed ") + (if (s.speedX == 0) T("最大", "Max") else "${s.speedX}x") +
                    T(" ・ リトライ ${s.retries}回", " · ${s.retries} retries"),
            ) { onPage(3) }
            HorizontalDivider()
            CategoryRow(
                AppIcons.Library, T("ライブラリ", "Library"),
                T("${folders.coerceAtLeast(0)}個のフォルダ ・ 端末内の曲も追加できます", "${folders.coerceAtLeast(0)} folders · add music from your device"),
            ) { onPage(4) }
            HorizontalDivider()
            CategoryRow(
                AppIcons.Headphones, T("音質設定", "Sound settings"),
                (if (s.sourceDirect) T("ソースダイレクト", "Source direct") else T("EQ ", "EQ ") + (if (s.eqEnabled) "ON" else "OFF") +
                    (if (s.darMode != 0) " ・ DAR" else "")) +
                    (if (s.outputMode == 2) T(" ・ USB DAC直接出力", " · USB DAC direct") else ""),
            ) { onPage(8) }
            HorizontalDivider()
            CategoryRow(
                Icons.Filled.PlayArrow, T("表示", "Display"),
                listOf(T("自動", "Auto"), T("ライト", "Light"), T("ダーク", "Dark"))[s.themeMode.coerceIn(0, 2)] +
                    (if (AppLanguage.supported) " ・ " + languageLabel(AppLanguage.current(ctx)) else ""),
            ) { onPage(5) }
        }
        Card(Modifier.fillMaxWidth()) {
            CategoryRow(Icons.Filled.Build, T("ログ", "Log"), T("動作の記録（不具合の確認用）", "Activity log (for troubleshooting)")) { onPage(6) }
            HorizontalDivider()
            CategoryRow(
                Icons.Filled.Info, T("このアプリについて", "About this app"),
                T("バージョン ", "Version ") + BuildConfigVersion.name(ctx) + T(" ・ ライセンス", " · Licenses"),
            ) { onPage(7) }
        }
    }
}

private fun languageLabel(tag: String): String = when {
    tag.startsWith("ja") -> "日本語"
    tag.startsWith("en") -> "English"
    else -> T("端末の言語", "System language")
}

@Composable
private fun RipPage(vm: MainViewModel, s: AppSettings) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setOutputFolder(uri)
    }
    Section(T("保存先", "Save folder")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(folderLabel(s.outputUri), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            FilledTonalButton({ picker.launch(null) }) { Text(if (s.outputUri == null) T("選ぶ", "Choose") else T("変更", "Change")) }
        }
        SubHeader(T("フォルダとファイル名", "Folders & file names"))
        ChoiceList(
            T("フォルダ構成", "Folder structure"),
            listOf(T("アーティスト / アルバム / 曲", "Artist / Album / Track"), T("アルバム / 曲", "Album / Track"), T("フォルダを分けない", "No subfolders")),
            s.folderMode,
        ) { vm.set(Keys.folderMode, it) }
        ChoiceList(
            T("ファイル名", "File name"),
            listOf(T("01 曲名", "01 Title"), T("01 - アーティスト - 曲名", "01 - Artist - Title"), "Track01"),
            s.fileNameMode,
        ) { vm.set(Keys.fileNameMode, it) }
    }
    Section(T("CDを入れたとき", "When a CD is inserted")) {
        SwitchRow(
            T("曲情報を自動で取得", "Get track info automatically"),
            T("曲名・アーティスト・ジャケットをMusicBrainzから取得します", "Fetches titles, artists, and cover art from MusicBrainz"),
            s.autoMeta,
        ) { vm.set(Keys.autoMeta, it) }
        SwitchRow(T("ディスク画面を開く", "Open the Disc tab"), null, s.autoOpenDisc) { vm.set(Keys.autoOpenDisc, it) }
    }
    Section(T("取り込みのとき", "When ripping")) {
        SwitchRow(T("ジャケットを曲ファイルに埋め込む", "Embed cover art in files"), null, s.embedCover) { vm.set(Keys.embedCover, it) }
        SwitchRow(T("ジャケットをフォルダに保存（cover.jpg）", "Save cover art to folder (cover.jpg)"), null, s.saveCover) { vm.set(Keys.saveCover, it) }
        SwitchRow(T("取り込み後にCDを取り出す", "Eject CD after ripping"), null, s.ejectAfter) { vm.set(Keys.ejectAfter, it) }
        SwitchRow(T("取り込み中は画面を消さない", "Keep screen on while ripping"), null, s.keepScreenOn) { vm.set(Keys.keepScreenOn, it) }
    }
}

@Composable
private fun QualityPage(vm: MainViewModel, s: AppSettings) {
    Section(T("かんたん設定", "Presets")) { QualityOptions(vm, s) }
    Section(T("詳細設定", "Custom")) {
        Segmented(T("形式", "Format"), AudioFormat.entries.map { it.label }, s.format.ordinal) { vm.set(Keys.format, it) }
        when (s.format) {
            AudioFormat.FLAC -> IntSlider(
                T("FLAC圧縮レベル", "FLAC compression level"), s.flacLevel, 0..8, { "$it" },
                T("音質はどのレベルでも変わりません。レベルが高いほどファイルは小さくなりますが、取り込みに時間がかかります。", "Sound quality is the same at every level. Higher levels make smaller files but take longer to rip."),
            ) { vm.set(Keys.flacLevel, it) }
            AudioFormat.AAC -> IntSlider(
                T("AACビットレート", "AAC bitrate"), s.aacKbps, 64..320, { "${snap32(it)} kbps" },
                T("高いほど音質が良くなり、ファイルも大きくなります。256 kbps以上ではほとんど聴き分けられません。", "Higher bitrates mean better quality and larger files. Above 256 kbps, differences are hard to hear."),
            ) { vm.set(Keys.aacKbps, snap32(it)) }
            AudioFormat.MP3 -> {
                IntSlider(
                    T("MP3ビットレート", "MP3 bitrate"), MP3_RATES.indexOf(s.mp3Kbps).coerceAtLeast(0), 0..MP3_RATES.lastIndex,
                    { "${MP3_RATES[it]} kbps" },
                    T("固定ビットレート（CBR）です。320 kbpsが最高音質です。", "Constant bitrate (CBR). 320 kbps gives the best quality."),
                ) { vm.set(Keys.mp3Kbps, MP3_RATES[it]) }
                Segmented(
                    T("エンコード品質", "Encoding quality"), listOf(T("速度優先", "Fast"), T("標準", "Standard"), T("高品質", "High")),
                    when { s.mp3Quality >= 5 -> 0; s.mp3Quality >= 3 -> 1; else -> 2 },
                ) { vm.set(Keys.mp3Quality, listOf(7, 5, 2)[it]) }
            }
            AudioFormat.WAV -> Text(
                T("WAVは無圧縮（16 bit / 44.1 kHz）で保存します。曲情報やジャケットは保存されません。", "WAV files are saved uncompressed (16-bit / 44.1 kHz). Track info and cover art are not saved."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DrivePage(vm: MainViewModel, s: AppSettings) {
    Section(T("読み取り", "Reading")) {
        IntSlider(
            T("読み取り速度", "Read speed"), s.speedX, 0..52, { if (it == 0) T("最大", "Max") else "${it}x" },
            T("遅くすると、傷のあるCDも正確に読めることがあります", "Slower speeds can help read scratched CDs accurately"),
        ) { vm.set(Keys.speedX, it) }
        IntSlider(
            T("リトライ回数", "Retries"), s.retries, 0..20, { T("${it}回", "$it") },
            T("読み取りに失敗したときに読み直す回数", "How many times to retry after a read error"),
        ) { vm.set(Keys.retries, it) }
        SwitchRow(
            T("エラー時に中断", "Stop on error"), T("オフにすると、読めない部分を無音で埋めて続行します", "When off, unreadable parts are filled with silence and ripping continues"),
            s.abortOnError,
        ) { vm.set(Keys.abortOnError, it) }
    }
    Section(T("上級者向け", "Advanced")) {
        IntSlider(
            T("読み取りオフセット補正", "Read offset correction"), s.offsetSamples, -2000..2000, { T("${it}サンプル", "$it samples") },
            T("ドライブごとの読み取り位置のずれを補正します。分からない場合は0にしてください。", "Corrects the drive's read position offset. Set to 0 if unsure."),
        ) { vm.set(Keys.offsetSamples, it) }
        IntSlider(
            T("一度に読むセクター数", "Sectors per read"), s.sectorsPerRead, 1..27, { "$it" },
            T("通常は変更する必要はありません", "You usually don't need to change this"),
        ) { vm.set(Keys.sectorsPerRead, it) }
        SwitchRow(
            T("C2エラー検出", "C2 error detection"),
            T("対応ドライブでのみ使えます。非対応のドライブでは自動で無効になります。", "Works only on supported drives. It turns off automatically on other drives."),
            s.useC2,
        ) { vm.set(Keys.useC2, it) }
    }
    Section(T("CDの再生", "CD playback")) {
        IntSlider(
            T("再生時の回転速度", "Drive speed during playback"), s.cdPlaySpeed, 1..8, { "${it}x" },
            T("低くするとドライブの動作音が静かになります", "Lower speeds make the drive quieter"),
        ) { vm.set(Keys.cdPlaySpeed, it) }
    }
    var confirmReset by remember { mutableStateOf(false) }
    OutlinedButton({ confirmReset = true }, Modifier.fillMaxWidth()) { Text(T("CDドライブの設定を初期値に戻す", "Reset CD drive settings")) }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(T("初期値に戻しますか？", "Reset to defaults?")) },
            text = { Text(T("読み取り速度・リトライ回数・オフセットなど、CDドライブの設定が初期値に戻ります", "Read speed, retries, offset, and other CD drive settings will be reset to their defaults")) },
            confirmButton = { TextButton({ confirmReset = false; vm.resetDriveSettings() }) { Text(T("戻す", "Reset")) } },
            dismissButton = { TextButton({ confirmReset = false }) { Text(T("キャンセル", "Cancel")) } },
        )
    }
}

@Composable
private fun LibraryFoldersPage(vm: MainViewModel, s: AppSettings) {
    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addLibraryFolder(uri)
    }
    Section(T("ライブラリに表示するフォルダ", "Folders shown in the library")) {
        Text(
            T(
                "取り込んだ曲の保存先に加えて、端末内のほかの音楽フォルダも追加できます。追加したフォルダの曲も再生でき、曲情報の取得や編集もできます。",
                "Along with your rip folder, you can add other music folders on your device. Songs in them can be played, and their track info can be fetched or edited.",
            ),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.outputUri != null) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(AppIcons.Download, null) },
                headlineContent = { Text(folderLabel(s.outputUri)) },
                supportingContent = { Text(T("取り込みの保存先", "Rip folder")) },
            )
        }
        var removing by remember { mutableStateOf<String?>(null) }
        s.libraryFolders.sorted().forEach { f ->
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(AppIcons.Library, null) },
                headlineContent = { Text(folderLabel(f)) },
                trailingContent = {
                    IconButton({ removing = f }) { Icon(Icons.Filled.Clear, T("ライブラリから外す", "Remove from library")) }
                },
            )
        }
        removing?.let { f ->
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text(T("ライブラリから外しますか？", "Remove from library?")) },
                text = {
                    Text(
                        folderLabel(f) + "\n\n" + T(
                            "このフォルダの曲はライブラリに表示されなくなります(ファイルは削除されません)",
                            "Songs in this folder will no longer appear in the library (the files are not deleted)",
                        ),
                    )
                },
                confirmButton = { TextButton({ removing = null; vm.removeLibraryFolder(f) }) { Text(T("外す", "Remove")) } },
                dismissButton = { TextButton({ removing = null }) { Text(T("キャンセル", "Cancel")) } },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ addPicker.launch(null) }) { Text(T("フォルダを追加", "Add folder")) }
            OutlinedButton({ vm.refreshLibrary(fresh = true) }, enabled = !vm.libScanning) { Text(T("読み込み直す", "Rescan")) }
        }
        Text(
            T("新しい曲はアプリを開いたときに自動で読み込まれます。曲情報が正しく表示されないときは、「読み込み直す」ですべての曲を読み直せます。",
                "New songs are added automatically when you open the app. If track info looks wrong, tap \"Rescan\" to re-read all songs."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vm.libScanning && (vm.libFull || vm.libNew > 0)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                if (vm.libFull) T("すべての曲を読み直しています ${vm.libProgress}", "Rescanning all songs ${vm.libProgress}")
                else T("新しい曲を読み込んでいます ${vm.libProgress}", "Loading new songs ${vm.libProgress}"),
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
    Section(T("すべてのファイルへのアクセス", "All files access")) {
        Text(
            T(
                "許可すると、フォルダや曲情報をファイルから直接読み込むため、ライブラリの読み込みが速くなります。許可しなくても、これまでどおり使えます。",
                "Allowing this lets the app read folders and track info directly from files, so the library loads faster. The app works fine without it.",
            ),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (granted) T("許可されています", "Allowed") else T("許可されていません", "Not allowed"),
            style = MaterialTheme.typography.bodyLarge,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton({
            val sa = com.uyatame.ucrt.library.StorageAccess
            runCatching { ctx.startActivity(sa.requestIntent(ctx)) }
                .onFailure { runCatching { ctx.startActivity(sa.fallbackIntent()) } }
        }) { Text(if (granted) T("設定を開く（許可を取り消す）", "Open settings to revoke") else T("許可する", "Allow")) }
    }
}

@Composable
private fun DisplayPage(vm: MainViewModel, s: AppSettings) {
    Section(T("見た目", "Appearance")) {
        Segmented(T("テーマ", "Theme"), listOf(T("自動", "Auto"), T("ライト", "Light"), T("ダーク", "Dark")), s.themeMode) { vm.set(Keys.themeMode, it) }
        // ダイナミックカラーは Android 12 から
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            SwitchRow(T("ダイナミックカラー", "Dynamic color"), T("壁紙の色に合わせます", "Matches your wallpaper colors"), s.dynamicColor) { vm.set(Keys.dynamicColor, it) }
        }
        if (!s.dynamicColor || android.os.Build.VERSION.SDK_INT < 31) {
            ThemeColorPicker(s.themeColor) { vm.set(Keys.themeColor, it) }
        }
        SwitchRow(
            T("一覧にジャケットを表示", "Show artwork in lists"),
            T("オフにすると、ライブラリや再生リストでジャケットを読み込まないため、動作が軽くなります（再生画面には表示されます）", "Turn off to skip loading artwork in the library and queue for smoother scrolling (the player still shows it)"),
            s.listArt,
        ) { vm.set(Keys.listArt, it) }
        Segmented(T("タブレットモード", "Tablet mode"), listOf(T("自動", "Auto"), T("オン", "On"), T("オフ", "Off")), s.tabletMode) { vm.set(Keys.tabletMode, it) }
        Text(
            T(
                "タブを画面の左側に並べ、広い画面では再生画面をライブラリの右に表示したままにします。「自動」では、タブレットなど画面の大きい端末で有効になります。",
                "Moves the tabs to the left side and, on wide screens, keeps the player open next to the library. Auto turns it on for tablets and other large screens.",
            ),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Section(T("再生画面", "Player")) {
        SwitchRow(
            T("アンビエントモード", "Ambient mode"),
            T("再生画面の背景をジャケットの色合いにします", "Tints the player background to match the album art"),
            s.ambientPlayer,
        ) { vm.set(Keys.ambientPlayer, it) }
        // ぼかしは Android 12 から(それより前の端末では、もともとぼかさずに表示している)
        if (s.ambientPlayer && android.os.Build.VERSION.SDK_INT >= 31) {
            SwitchRow(
                T("背景をぼかす", "Blur the background"),
                T("オフにすると、ジャケットをぼかさずに薄く背景に表示します", "Turn off to show the album art faintly behind the player without blur"),
                s.ambientBlur,
            ) { vm.set(Keys.ambientBlur, it) }
        }
    }
    if (AppLanguage.supported) {
        val ctx = LocalContext.current
        var lang by remember { mutableStateOf(AppLanguage.current(ctx)) }
        Section("言語 / Language") {
            val tags = listOf("", "ja", "en")
            val sel = tags.indexOfFirst { lang.startsWith(it) && it.isNotEmpty() }.let { if (it < 0) 0 else it }
            ChoiceList(
                T("表示言語", "Display language"),
                listOf(T("端末の設定に合わせる", "System default"), "日本語", "English"),
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
        PageHeader(T("ログ", "Log"), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SwitchRow(T("詳細ログ", "Verbose log"), T("SCSIコマンドのエラーも記録します（不具合調査用）", "Also records SCSI command errors (for troubleshooting)"), s.verboseLog) { vm.set(Keys.verboseLog, it) }
            FilledTonalButton({
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", vm.logText))
            }) { Text(T("ログをコピー", "Copy log")) }
            SelectionContainer {
                Text(
                    if (vm.logText.isEmpty()) T("記録はまだありません", "Nothing logged yet") else vm.logText,
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
        T(
            "このアプリはオープンソースソフトウェアで、GPL v3のもとで自由に使用・改変・再配布できます。改変版を配布する場合は、ソースコードも同じライセンスで公開してください。",
            "This app is open source under the GPL v3. You're free to use, modify, and redistribute it. If you distribute a modified version, you must release its source code under the same license.",
        ),
        "GPL-3.0.txt",
    ),
    LicenseInfo(
        T("jump3r（MP3エンコーダ）", "jump3r (MP3 encoder)"), "1.0.5",
        T("Java移植: Ken Händel / パッケージ化: Hanns Holger Rutz / 原作 LAME 3.98.4: The LAME Project", "Java port: Ken Händel / Packaging: Hanns Holger Rutz / Original LAME 3.98.4: The LAME Project"),
        T("GNU Lesser General Public License v2.1以降", "GNU Lesser General Public License v2.1 or later"), LGPL21,
        "https://codeberg.org/sciss/jump3r",
        T("本アプリはjump3rを改変せず、独立したライブラリ（de.sciss:jump3r:1.0.5）として使用しています。", "This app uses jump3r unmodified, as a separate library (de.sciss:jump3r:1.0.5). ") +
            T("ソースコードは上記のリポジトリとMaven Central（sources jar）から入手できます。", "The source code is available from the repository above and from Maven Central (sources jar). ") +
            T("LGPLに基づき、このライブラリを改変または差し替えたものと組み合わせて本アプリを使用できます。", "Under the LGPL, you may use this app with a modified or replacement version of the library. ") +
            T("本アプリのビルド環境（プロジェクト一式）を使えば、差し替えたライブラリで再ビルドできます。", "You can use this project's build setup to rebuild the app with a replacement library. ") +
            T("LGPL v2.1の全文を本アプリに同梱しています。", "The full text of the LGPL v2.1 is included in this app."),
        "LGPL-2.1.txt",
    ),
    LicenseInfo(
        "JNA (Java Native Access)", "5.14.0", "Copyright (c) 2007-2024 Timothy Wall and JNA contributors",
        T("Apache License 2.0（Apache-2.0 / LGPL-2.1のデュアルライセンスからApache-2.0を選択）", "Apache License 2.0 (chosen from the Apache-2.0 / LGPL-2.1 dual license)"),
        APACHE, "https://github.com/java-native-access/jna",
        T("USB DAC直接出力で、USBのアイソクロナス転送に使用しています。", "Used for USB isochronous transfers in USB DAC direct output."),
        "Apache-2.0.txt",
    ),
    LicenseInfo(
        T("Kotlin標準ライブラリ", "Kotlin standard library"), "2.1.0", "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors",
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
        T("MusicBrainz（曲情報データ）", "MusicBrainz (track data)"), "", "MetaBrainz Foundation and contributors",
        T("CC0 1.0（コアデータ）", "CC0 1.0 (core data)"), "https://creativecommons.org/publicdomain/zero/1.0/", "https://musicbrainz.org",
        T("曲名・アーティスト名などの情報をMusicBrainz Web Serviceから取得しています。", "Titles, artist names and similar information are fetched from the MusicBrainz Web Service."),
        "CC0-1.0.txt",
    ),
    LicenseInfo(
        T("Cover Art Archive（ジャケット画像）", "Cover Art Archive (cover art)"), "", T("画像の著作権は各権利者に帰属します", "Images are copyright of their respective owners"),
        T("画像ごとの権利者による", "Set by each image's rights holder"), "https://coverartarchive.org", "https://coverartarchive.org",
        T("取得したジャケット画像は、私的使用の範囲内でご利用ください。", "Downloaded cover art is for personal use only."),
    ),
    LicenseInfo(
        T("FLACエンコーダ / MP4・ID3タグ処理 / javax.sound互換クラス", "FLAC encoder / MP4 & ID3 tagging / javax.sound compatibility classes"), "", T("本アプリ独自の実装", "Original implementation in this app"),
        "—", "https://xiph.org/flac/", null,
        T("FLACはオープンでロイヤリティフリーな形式です。javax.sound.sampledの互換クラスは、", "FLAC is an open, royalty-free format. The javax.sound.sampled compatibility classes ") +
            T("AndroidでMP3エンコーダを動かすためにアプリ側で用意した最小限の実装です。", "are a minimal implementation that lets the MP3 encoder run on Android."),
    ),
    LicenseInfo(
        T("AACエンコーダ・各種デコーダ", "AAC encoder and decoders"), "", T("Android OSに内蔵", "Built into Android"),
        T("OS側で提供", "Provided by the OS"), "https://source.android.com/", null,
        T("AACの書き出しと曲の再生にはAndroid OS内蔵のコーデックを使用しており、本アプリには同梱していません。", "AAC encoding and playback use codecs built into Android; they are not bundled with this app."),
    ),
)

@Composable
private fun LicenseTextPage(title: String, asset: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val text by produceState<String?>(null, asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open("licenses/$asset").bufferedReader().use { it.readText() } }.getOrNull()
                ?: T("ライセンス全文が見つかりません（ビルド時に取得できなかった可能性があります）。", "License text not found (it may not have been downloaded at build time).")
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            SelectionContainer {
                Text(text ?: T("読み込み中…", "Loading…"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
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
        PageHeader(T("このアプリについて", "About this app"), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Uyatame CD Ripping Tool (UCRT)") {
                Text(T("バージョン ", "Version ") + BuildConfigVersion.name(ctx), style = MaterialTheme.typography.bodyMedium)
                Text(
                    T("外付けUSBドライブで音楽CDを取り込み・再生するアプリです。", "Rip and play audio CDs with an external USB drive."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Section(T("アップデート", "Updates")) {
                SwitchRow(
                    T("起動時にアップデートを確認", "Check for updates at startup"),
                    T("新しいバージョンが公開されていれば、ダウンロードページへのリンクを表示します", "Shows a link to the download page when a new version is available"),
                    s.updateCheck,
                ) { vm.set(Keys.updateCheck, it) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ vm.checkUpdate(manual = true) }, enabled = !vm.updateChecking) {
                        Text(if (vm.updateChecking) T("確認しています…", "Checking…") else T("今すぐ確認", "Check now"))
                    }
                    TextButton({ browse(com.uyatame.ucrt.meta.UpdateCheck.RELEASES_PAGE) }) { Text(T("リリースページ", "Releases page")) }
                }
            }
            Text(
                T("ライセンス情報", "Licenses"), style = MaterialTheme.typography.titleMedium,
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
                T("同梱しているライセンス全文", "Included license texts"), style = MaterialTheme.typography.titleMedium,
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
            confirmButton = { TextButton({ open = null }) { Text(T("閉じる", "Close")) } },
            dismissButton = {
                TextButton({
                    if (l.asset != null) { open = null; reading = l } else browse(l.licenseUrl)
                }) { Text(if (l.asset != null) T("ライセンス全文", "Full license text") else T("公式サイト", "Official site")) }
            },
            title = { Text(l.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (l.version.isNotEmpty()) Text(T("バージョン: ${l.version}", "Version: ${l.version}"), style = MaterialTheme.typography.bodyMedium)
                    Text(T("権利者: ${l.copyright}", "Copyright: ${l.copyright}"), style = MaterialTheme.typography.bodyMedium)
                    Text(T("ライセンス: ${l.license}", "License: ${l.license}"), style = MaterialTheme.typography.bodyMedium)
                    if (l.note.isNotEmpty()) Text(l.note, style = MaterialTheme.typography.bodySmall)
                    if (l.sourceUrl != null) {
                        TextButton({ browse(l.sourceUrl) }) { Text(T("ソースコード / 公式サイトを開く", "View source code / website")) }
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
    // 動かしている間は手元の値で見せ、指を離したら保存する
    val start = ThemeColors.hueSat(if (value == 0) std else value)
    var hue by remember(value) { mutableStateOf(start.first) }
    var sat by remember(value) { mutableStateOf(start.second) }
    // まだ動かしていなければ、選んでいる色そのものを見本に出す
    var moved by remember(value) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(T("テーマの色", "Theme color"), style = MaterialTheme.typography.bodyLarge)
        Text(
            T("ダイナミックカラーを使わないときの色です", "Used when dynamic color is off"),
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
                    if (c == 0) Text(T("標準", "Default"), color = Color.White, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(if (moved) ThemeColors.fromHue(hue, sat) else if (value == 0) std else value)))
            Spacer(Modifier.width(12.dp))
            Text(T("色相と鮮やかさで細かく調整", "Fine-tune hue and saturation"), style = MaterialTheme.typography.bodyMedium)
        }
        val save = { onChange(ThemeColors.fromHue(hue, sat)) }
        Slider(hue, { hue = it; moved = true }, valueRange = 0f..360f, onValueChangeFinished = save)
        Slider(sat, { sat = it; moved = true }, valueRange = 0.1f..1f, onValueChangeFinished = save)
    }
}
