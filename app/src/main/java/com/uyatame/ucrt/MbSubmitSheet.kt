package com.uyatame.ucrt

import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.meta.MusicBrainz

/** 選べる値(MusicBrainz に送る値と、画面に出す名前) */
private class Choice(val value: String, val label: () -> String)

private val TYPES = listOf(
    Choice("album") { S(R.string.mb_type_album) },
    Choice("single") { S(R.string.mb_type_single) },
    Choice("ep") { "EP" },
    Choice("other") { S(R.string.mb_other) },
)
private val STATUSES = listOf(
    Choice("official") { S(R.string.mb_status_official) },
    Choice("promotion") { S(R.string.mb_status_promotion) },
    Choice("bootleg") { S(R.string.mb_status_bootleg) },
)
/** 言語と、それに合う文字(ISO 15924) */
private val LANGUAGES = listOf(
    Triple("jpn", "Jpan") { S(R.string.mb_lang_japanese) },
    Triple("eng", "Latn") { S(R.string.mb_lang_english) },
    Triple("zxx", "") { S(R.string.mb_lang_none) },
    Triple("", "") { S(R.string.mb_unset) },
)
private val PACKAGING = listOf(
    Choice("Jewel Case") { S(R.string.mb_pack_jewel) },
    Choice("Slim Jewel Case") { S(R.string.mb_pack_slim) },
    Choice("Digipak") { S(R.string.mb_pack_digipak) },
    Choice("Cardboard/Paper Sleeve") { S(R.string.mb_pack_paper) },
    Choice("Other") { S(R.string.mb_other) },
    Choice("") { S(R.string.mb_unset) },
)

/** 文字に日本語(ひらがな・カタカナ・漢字)があるか */
private fun hasJapanese(s: String) = s.any { it in '぀'..'ヿ' || it in '一'..'鿿' }

/**
 * MusicBrainz に登録する前に、詳しい情報を入力・確認する画面。
 * ここで決めた内容を MusicBrainz の登録画面に入れて開くので、MusicBrainz の画面ではログインと送信だけで済む
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MbSubmitSheet(vm: MainViewModel) {
    val d = vm.mbDraft ?: return
    var title by remember(d) { mutableStateOf(d.title) }
    var artist by remember(d) { mutableStateOf(d.artist) }
    var date by remember(d) { mutableStateOf(d.date) }
    val allText = remember(d) { d.title + d.artist + d.discs.joinToString { disc -> disc.tracks.joinToString { it.title } } }
    var type by remember(d) { mutableStateOf(if (d.discs.sumOf { it.tracks.size } <= 3) "single" else "album") }
    var status by remember(d) { mutableStateOf("official") }
    var lang by remember(d) { mutableStateOf(if (hasJapanese(allText)) 0 else 1) }
    var country by remember(d) { mutableStateOf(java.util.Locale.getDefault().country.ifEmpty { "JP" }) }
    var label by remember(d) { mutableStateOf("") }
    var catalog by remember(d) { mutableStateOf("") }
    var barcode by remember(d) { mutableStateOf("") }
    var packaging by remember(d) { mutableStateOf("Jewel Case") }
    var comment by remember(d) { mutableStateOf("") }
    // 曲名・曲のアーティスト(全部のディスクを並べたもの)
    val titles = remember(d) { mutableStateListOf<String>().apply { d.discs.forEach { disc -> disc.tracks.forEach { add(it.title) } } } }
    val artists = remember(d) { mutableStateListOf<String>().apply { d.discs.forEach { disc -> disc.tracks.forEach { add(it.artist) } } } }
    val lyricsCount = remember(d) { d.discs.sumOf { disc -> disc.tracks.count { it.lyrics != null } } }
    var withCover by remember(d) { mutableStateOf(d.cover != null) }
    var withLyrics by remember(d) { mutableStateOf(lyricsCount > 0) }
    val coverImage = remember(d) {
        d.cover?.let { b -> runCatching { com.uyatame.ucrt.library.ArtLoader.decode(b, 256)?.asImageBitmap() }.getOrNull() }
    }

    fun submit() {
        var k = 0
        val discs = d.discs.map { disc ->
            MainViewModel.MbDiscDraft(
                disc.position, disc.toc,
                disc.tracks.map { t ->
                    val i = k++
                    MainViewModel.MbTrackDraft(titles[i].trim(), artists[i].trim(), t.lengthMs, t.lyrics)
                },
            )
        }
        val l = LANGUAGES[lang]
        val extra = MusicBrainz.ReleaseExtra(
            type = type, status = status, country = country.trim(), label = label.trim(), catalog = catalog.trim(),
            barcode = barcode, language = l.first, script = l.second, packaging = packaging, comment = comment.trim(),
        )
        vm.submitMbDraft(
            MainViewModel.MbDraft(title.trim(), artist.trim(), date.trim(), discs, d.cover, d.note, extra),
            withCover, withLyrics,
        )
    }

    val missing = title.isBlank() || artist.isBlank() || titles.any { it.isBlank() }
    // 3 つの手順に分ける: 1 アルバム(必須) → 2 曲 → 3 確認(ジャケット・歌詞と、このあと MusicBrainz の画面ですること)
    var step by remember(d) { mutableIntStateOf(0) }
    var more by remember(d) { mutableStateOf(false) }
    val stepNames = listOf(S(R.string.mb_step_album), S(R.string.mb_step_tracks), S(R.string.mb_step_confirm))
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { vm.mbDraft = null },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                // ---- 上の帯: 閉じる・題名・手順 ----
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.IconButton({ vm.mbDraft = null }) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, S(R.string.close))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(S(R.string.mb_upload), style = MaterialTheme.typography.titleLarge)
                        Text(
                            S(R.string.mb_step_n, step + 1, 3) + " · " + stepNames[step],
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                androidx.compose.material3.LinearProgressIndicator({ (step + 1) / 3f }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (step) {
                        0 -> {
                            Text(S(R.string.mb_sheet_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (coverImage != null) {
                                    Image(coverImage, null, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                    Spacer(Modifier.width(12.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(S(R.string.album)) }, singleLine = true, isError = title.isBlank())
                                    OutlinedTextField(artist, { artist = it }, Modifier.fillMaxWidth(), label = { Text(S(R.string.artist)) }, singleLine = true, isError = artist.isBlank())
                                }
                            }
                            ChipRow(S(R.string.mb_type), TYPES.map { it.label() }, TYPES.indexOfFirst { it.value == type }) { type = TYPES[it].value }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    date, { date = it }, Modifier.weight(1f), label = { Text(S(R.string.mb_date)) }, singleLine = true,
                                    placeholder = { Text("2024-03-05") },
                                )
                                OutlinedTextField(country, { country = it.take(2) }, Modifier.width(110.dp), label = { Text(S(R.string.mb_country)) }, singleLine = true)
                            }
                            // あまり使わない項目は、開いたときだけ
                            TextButton({ more = !more }) { Text(if (more) S(R.string.mb_less) else S(R.string.mb_more)) }
                            if (more) {
                                ChipRow(S(R.string.mb_status), STATUSES.map { it.label() }, STATUSES.indexOfFirst { it.value == status }) { status = STATUSES[it].value }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(label, { label = it }, Modifier.weight(1f), label = { Text(S(R.string.mb_label)) }, singleLine = true)
                                    OutlinedTextField(catalog, { catalog = it }, Modifier.weight(1f), label = { Text(S(R.string.mb_catalog)) }, singleLine = true)
                                }
                                OutlinedTextField(
                                    barcode, { barcode = it.filter { c -> c.isDigit() }.take(14) }, Modifier.fillMaxWidth(),
                                    label = { Text(S(R.string.mb_barcode)) }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                ChipRow(S(R.string.mb_language), LANGUAGES.map { it.third() }, lang) { lang = it }
                                ChipRow(S(R.string.mb_packaging), PACKAGING.map { it.label() }, PACKAGING.indexOfFirst { it.value == packaging }) {
                                    packaging = PACKAGING[it].value
                                }
                                OutlinedTextField(
                                    comment, { comment = it }, Modifier.fillMaxWidth(), label = { Text(S(R.string.mb_comment)) }, singleLine = true,
                                    supportingText = { Text(S(R.string.mb_comment_hint)) },
                                )
                            }
                        }
                        1 -> {
                            Text(S(R.string.mb_tracks_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            var k = 0
                            d.discs.forEach { disc ->
                                if (d.discs.size > 1) Text("Disc ${disc.position}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                disc.tracks.forEachIndexed { n, t ->
                                    val idx = k++
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedTextField(
                                            titles[idx], { titles[idx] = it }, Modifier.fillMaxWidth(), singleLine = true, isError = titles[idx].isBlank(),
                                            label = { Text("${n + 1}. " + S(R.string.title) + if (t.lengthMs > 0) "（${fmtMs(t.lengthMs)}）" else "") },
                                        )
                                        OutlinedTextField(
                                            artists[idx], { artists[idx] = it }, Modifier.fillMaxWidth(), singleLine = true,
                                            label = { Text(S(R.string.mb_track_artist)) }, placeholder = { Text(artist) },
                                        )
                                        if (t.lyrics != null) {
                                            Text(
                                                S(R.string.mb_has_lyrics) + if (t.lyrics.synced) S(R.string.mb_synced) else "",
                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            // ---- 確認 ----
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                artist + " · " + S(R.string.mb_n_tracks, titles.size) + (if (date.isNotBlank()) " · $date" else ""),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider()
                            if (d.cover != null) SwitchRow(S(R.string.mb_with_cover), S(R.string.mb_with_cover_desc), withCover) { withCover = it }
                            if (lyricsCount > 0) SwitchRow(S(R.string.mb_with_lyrics, lyricsCount), S(R.string.mb_with_lyrics_desc), withLyrics) { withLyrics = it }
                            HorizontalDivider()
                            Text(S(R.string.mb_next_steps_title), style = MaterialTheme.typography.titleSmall)
                            Text(S(R.string.mb_next_steps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (missing) Text(S(R.string.mb_upload_fill_in), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                // ---- 下の帯: 戻る・次へ・登録 ----
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ if (step == 0) vm.mbDraft = null else step-- }, Modifier.weight(1f)) {
                        Text(if (step == 0) S(R.string.cancel) else S(R.string.back))
                    }
                    val canNext = when (step) { 0 -> title.isNotBlank() && artist.isNotBlank(); 1 -> titles.none { it.isBlank() }; else -> !missing }
                    Button({ if (step < 2) step++ else submit() }, Modifier.weight(2f), enabled = canNext) {
                        Text(if (step < 2) S(R.string.next) else S(R.string.mb_open_submit))
                    }
                }
            }
        }
    }
}

/** 横に並べたチップから 1 つ選ぶ(多いときは横にスクロール) */
@Composable
private fun ChipRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, o -> FilterChip(selected == i, { onSelect(i) }, label = { Text(o) }) }
        }
    }
}
