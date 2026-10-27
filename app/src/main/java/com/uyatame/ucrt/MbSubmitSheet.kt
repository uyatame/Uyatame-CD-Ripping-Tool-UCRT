package com.uyatame.ucrt

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
    ModalBottomSheet(onDismissRequest = { vm.mbDraft = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(S(R.string.mb_upload), style = MaterialTheme.typography.titleLarge)
            Text(S(R.string.mb_sheet_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // ---- アルバム ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (coverImage != null) {
                    Image(
                        coverImage, null, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(S(R.string.album)) }, singleLine = true)
                    OutlinedTextField(artist, { artist = it }, Modifier.fillMaxWidth(), label = { Text(S(R.string.artist)) }, singleLine = true)
                }
            }
            ChipRow(S(R.string.mb_type), TYPES.map { it.label() }, TYPES.indexOfFirst { it.value == type }) { type = TYPES[it].value }
            ChipRow(S(R.string.mb_status), STATUSES.map { it.label() }, STATUSES.indexOfFirst { it.value == status }) { status = STATUSES[it].value }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    date, { date = it }, Modifier.weight(1f), label = { Text(S(R.string.mb_date)) }, singleLine = true,
                    placeholder = { Text("2024-03-05") },
                )
                OutlinedTextField(
                    country, { country = it.take(2) }, Modifier.width(110.dp), label = { Text(S(R.string.mb_country)) }, singleLine = true,
                )
            }
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

            // ---- 曲 ----
            HorizontalDivider()
            Text(S(R.string.mb_tracks), style = MaterialTheme.typography.titleMedium)
            var k = 0
            d.discs.forEach { disc ->
                if (d.discs.size > 1) {
                    Text("Disc ${disc.position}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                disc.tracks.forEachIndexed { n, t ->
                    val i = k++
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            titles[i], { titles[i] = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("${n + 1}. " + S(R.string.title) + if (t.lengthMs > 0) "（${fmtMs(t.lengthMs)}）" else "") },
                        )
                        OutlinedTextField(
                            artists[i], { artists[i] = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(S(R.string.mb_track_artist)) },
                            placeholder = { Text(artist) },
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

            // ---- 一緒に登録するもの ----
            HorizontalDivider()
            if (d.cover != null) SwitchRow(S(R.string.mb_with_cover), S(R.string.mb_with_cover_desc), withCover) { withCover = it }
            if (lyricsCount > 0) {
                SwitchRow(S(R.string.mb_with_lyrics, lyricsCount), S(R.string.mb_with_lyrics_desc), withLyrics) { withLyrics = it }
            }
            if (missing) {
                Text(S(R.string.mb_upload_fill_in), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton({ vm.mbDraft = null }, Modifier.weight(1f)) { Text(S(R.string.cancel)) }
                Button({ submit() }, Modifier.weight(2f), enabled = !missing) { Text(S(R.string.mb_open_submit)) }
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
