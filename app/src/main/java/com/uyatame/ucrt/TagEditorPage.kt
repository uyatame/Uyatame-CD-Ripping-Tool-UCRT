package com.uyatame.ucrt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.tags.TagExtra

/** 曲情報の編集画面の 1 曲分(入力中の値) */
class TrackForm(
    /** 何曲目か(画面に出す) */
    val number: String,
    title: String,
    artist: String,
    /** 曲の長さ(画面に出す。分からなければ空) */
    val length: String,
    /** 詳しい情報(作曲・作詞・コメント・歌詞)を書けるか(WAV・DSF などは書けない) */
    val detailSupported: Boolean,
    composer: String = "",
    lyricist: String = "",
    comment: String = "",
    lyrics: String = "",
    /** 何曲目か・何枚目か(入力中の値。空なら書き換えない。CD の画面では使わない) */
    trackNo: String = "",
    discNo: String = "",
) {
    var title by mutableStateOf(title)
    var trackNo by mutableStateOf(trackNo)
    var discNo by mutableStateOf(discNo)
    var artist by mutableStateOf(artist)
    var composer by mutableStateOf(composer)
    var lyricist by mutableStateOf(lyricist)
    var comment by mutableStateOf(comment)
    var lyrics by mutableStateOf(lyrics)
    /** 詳しい情報を開いているか */
    var open by mutableStateOf(false)

    /** 詳しい情報が何か入っているか(閉じているときの表示) */
    val hasDetail: Boolean get() = composer.isNotBlank() || lyricist.isNotBlank() || comment.isNotBlank() || lyrics.isNotBlank()

    fun extra(genre: String): TagExtra =
        if (!detailSupported) TagExtra() else TagExtra(genre.trim(), composer.trim(), lyricist.trim(), comment.trim(), lyrics.trim())
}

/**
 * 曲情報の編集画面の全体(入力中の値)。
 * numbers: トラック番号・曲数・ディスク番号・枚数も入力できるか(ライブラリ。CD は目次で決まるので、枚数と何枚目かだけ)
 */
class TagForm(
    album: String, albumArtist: String, date: String, genre: String, val tracks: List<TrackForm>,
    val numbers: Boolean = false,
    trackTotal: String = "",
    discNo: String = "",
    discTotal: String = "",
) {
    var album by mutableStateOf(album)
    var albumArtist by mutableStateOf(albumArtist)
    var date by mutableStateOf(date)
    var genre by mutableStateOf(genre)
    /** 曲数(全体) */
    var trackTotal by mutableStateOf(trackTotal)
    /** 何枚目か(CD の画面。ライブラリでは曲ごと) */
    var discNo by mutableStateOf(discNo)
    /** 枚数(全体) */
    var discTotal by mutableStateOf(discTotal)
}

/** 数字だけを残す(番号の欄) */
private fun digits(s: String, max: Int = 3) = s.filter { it.isDigit() }.take(max)

/**
 * 曲情報の編集画面(画面いっぱいに出す。ダイアログではない)。ライブラリと CD の画面で使う。
 * 上の帯に「戻る」と「保存」、その下にアルバムの情報、曲ごとの曲名・アーティストと、開くと詳しい情報(作曲・作詞・コメント・歌詞)
 */
@Composable
fun TagEditorPage(
    title: String,
    form: TagForm,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    /** 曲の詳しい情報を読み込んでいる間 */
    loading: Boolean = false,
    /** 日付の欄の名前(ライブラリは「発売年」、CD は「発売日」) */
    dateLabel: String = S(R.string.year),
    /** 上の注意(書き換えられない曲があるときなど) */
    note: String? = null,
    /** ジャケットとその変更のボタン */
    cover: @Composable () -> Unit = {},
) {
    BackHandlerCompat(onCancel)
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S(R.string.cancel)) }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Button(onSave, enabled = !loading, modifier = Modifier.padding(end = 8.dp)) { Text(S(R.string.save)) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp + barInset()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "album") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cover()
                    if (note != null) {
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    // アルバムの情報(ここで入れた値は、全曲に書き込む)
                    Text(S(R.string.tag_album_common), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(form.album, { form.album = it }, Modifier.fillMaxWidth(), label = { Text(S(R.string.album)) }, singleLine = true)
                    OutlinedTextField(
                        form.albumArtist, { form.albumArtist = it }, Modifier.fillMaxWidth(),
                        label = { Text(S(R.string.album_artist)) }, singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(form.date, { form.date = it }, Modifier.weight(1f), label = { Text(dateLabel) }, singleLine = true)
                        OutlinedTextField(
                            form.genre, { form.genre = it }, Modifier.weight(1f), label = { Text(S(R.string.genre)) }, singleLine = true,
                            enabled = !loading,
                        )
                    }
                    // 曲数・何枚目か・枚数
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (form.numbers) NumberField(form.trackTotal, S(R.string.tag_track_total), Modifier.weight(1f)) { form.trackTotal = it }
                        else NumberField(form.discNo, S(R.string.tag_disc_no), Modifier.weight(1f)) { form.discNo = it }
                        NumberField(form.discTotal, S(R.string.tag_disc_total), Modifier.weight(1f)) { form.discTotal = it }
                    }
                }
            }
            // 曲ごとの欄を、全曲まとめて入れる(同じアーティスト・作曲者のアルバムなど)
            if (form.tracks.size > 1) item(key = "bulk") { BulkCard(form, loading) }
            item(key = "tracks-head") {
                Column {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(S(R.string.mb_tracks), style = MaterialTheme.typography.titleMedium)
                    Text(S(R.string.tag_detail_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(form.tracks, key = { i, _ -> "t$i" }) { _, t -> TrackFormCard(t, form.albumArtist, loading, form.numbers) }
        }
    }
}

@Composable
private fun TrackFormCard(t: TrackForm, albumArtist: String, loading: Boolean, numbers: Boolean) {
    UCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                t.number + if (t.length.isNotEmpty()) "（${t.length}）" else "",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
            // 何曲目か・何枚目か(ライブラリ)
            if (numbers) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(t.trackNo, S(R.string.tag_track_no), Modifier.weight(1f)) { t.trackNo = it }
                    NumberField(t.discNo, S(R.string.tag_disc_no), Modifier.weight(1f)) { t.discNo = it }
                }
            }
            OutlinedTextField(t.title, { t.title = it }, Modifier.fillMaxWidth(), label = { Text(S(R.string.title)) }, singleLine = true)
            OutlinedTextField(
                t.artist, { t.artist = it }, Modifier.fillMaxWidth(), label = { Text(S(R.string.artist)) }, singleLine = true,
                placeholder = { Text(albumArtist) },
            )
            // 詳しい情報(開いたときだけ出す。曲が多くても見通しよく)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = t.detailSupported && !loading) { t.open = !t.open }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        !t.detailSupported -> S(R.string.tag_detail_unsupported)
                        t.hasDetail && !t.open -> S(R.string.tag_detail) + "（" + detailSummary(t) + "）"
                        else -> S(R.string.tag_detail)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (t.detailSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (t.detailSupported) {
                    Icon(if (t.open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp))
                }
            }
            if (t.open && t.detailSupported) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(t.composer, { t.composer = it }, Modifier.weight(1f), label = { Text(S(R.string.composer)) }, singleLine = true)
                    OutlinedTextField(t.lyricist, { t.lyricist = it }, Modifier.weight(1f), label = { Text(S(R.string.lyricist)) }, singleLine = true)
                }
                OutlinedTextField(t.comment, { t.comment = it }, Modifier.fillMaxWidth(), label = { Text(S(R.string.comment)) }, singleLine = true)
                OutlinedTextField(
                    t.lyrics, { t.lyrics = it }, Modifier.fillMaxWidth(), label = { Text(S(R.string.lyrics)) },
                    minLines = 4, maxLines = 14,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (t.lyrics.trimStart().startsWith("[")) FontFamily.Monospace else FontFamily.Default),
                    supportingText = { Text(S(R.string.lyrics_hint)) },
                )
            }
        }
    }
}

/**
 * 全曲にまとめて入力するカード。曲ごとの欄(アーティスト・作曲・作詞・コメント・何枚目か)を、全曲に同じ値で入れる。
 * 「全曲に適用」を押したときだけ書き換えるので、押さなければ曲ごとの値はそのまま
 */
@Composable
private fun BulkCard(form: TagForm, loading: Boolean) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    UCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(S(R.string.tag_bulk), style = MaterialTheme.typography.titleMedium)
                    Text(S(R.string.tag_bulk_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(24.dp))
            }
            if (!open) return@Column
            var artist by remember { mutableStateOf(form.tracks.firstOrNull()?.artist.orEmpty()) }
            var composer by remember { mutableStateOf("") }
            var lyricist by remember { mutableStateOf("") }
            var comment by remember { mutableStateOf("") }
            var disc by remember { mutableStateOf("") }
            BulkRow(artist, { artist = it }, S(R.string.artist), placeholder = form.albumArtist) { form.tracks.forEach { t -> t.artist = artist } }
            // アルバムアーティストを、そのまま曲のアーティストにも入れる(よくある使い方なので、ボタン 1 つで)
            androidx.compose.material3.TextButton({ form.tracks.forEach { t -> t.artist = form.albumArtist } }, enabled = form.albumArtist.isNotBlank()) {
                Text(S(R.string.tag_bulk_artist_from_album))
            }
            BulkRow(composer, { composer = it }, S(R.string.composer), enabled = !loading) { form.tracks.filter { it.detailSupported }.forEach { t -> t.composer = composer } }
            BulkRow(lyricist, { lyricist = it }, S(R.string.lyricist), enabled = !loading) { form.tracks.filter { it.detailSupported }.forEach { t -> t.lyricist = lyricist } }
            BulkRow(comment, { comment = it }, S(R.string.comment), enabled = !loading) { form.tracks.filter { it.detailSupported }.forEach { t -> t.comment = comment } }
            if (form.numbers) {
                BulkRow(disc, { disc = digits(it) }, S(R.string.tag_disc_no), number = true) { form.tracks.forEach { t -> t.discNo = disc } }
                // 曲番号を、並んでいる順に 1 から振り直す
                androidx.compose.material3.TextButton({
                    form.tracks.forEachIndexed { i, t -> t.trackNo = (i + 1).toString() }
                    form.trackTotal = form.tracks.size.toString()
                }) { Text(S(R.string.tag_renumber)) }
            }
        }
    }
}

/** まとめて入力する 1 つの欄と、その「全曲に適用」ボタン */
@Composable
private fun BulkRow(
    value: String, onChange: (String) -> Unit, label: String, placeholder: String = "", enabled: Boolean = true, number: Boolean = false,
    apply: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value, onChange, Modifier.weight(1f), label = { Text(label) }, singleLine = true, enabled = enabled,
            placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
            keyboardOptions = if (number) androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            else androidx.compose.foundation.text.KeyboardOptions.Default,
        )
        androidx.compose.material3.FilledTonalButton(apply, enabled = enabled) { Text(S(R.string.tag_apply_all)) }
    }
}

/** 番号の入力欄(数字だけ) */
@Composable
private fun NumberField(value: String, label: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(digits(it)) }, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
    )
}

private fun detailSummary(t: TrackForm): String = buildList {
    if (t.composer.isNotBlank()) add(S(R.string.composer))
    if (t.lyricist.isNotBlank()) add(S(R.string.lyricist))
    if (t.comment.isNotBlank()) add(S(R.string.comment))
    if (t.lyrics.isNotBlank()) add(S(R.string.lyrics))
}.joinToString(S(R.string.separator).trim().ifEmpty { "・" })

/** 端末の戻る操作で閉じる(編集中に戻るとアプリが裏へ回ってしまわないように) */
@Composable
private fun BackHandlerCompat(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
}

/** 何曲目かの表示 */
fun trackLabel(n: Int): String = S(R.string.track_n, n)

/** 曲の長さの表示(ミリ秒) */
fun lengthText(ms: Long): String = if (ms <= 0) "" else "%d:%02d".format(ms / 60000, ms / 1000 % 60)
