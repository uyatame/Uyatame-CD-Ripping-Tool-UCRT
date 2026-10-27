@file:OptIn(ExperimentalMaterial3Api::class)

package com.uyatame.ucrt

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.data.AppSettings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.input.ImeAction
import com.uyatame.ucrt.library.UNKNOWN_ALBUM
import com.uyatame.ucrt.library.UNKNOWN_ARTIST
import com.uyatame.ucrt.meta.ReleaseCandidate
import com.uyatame.ucrt.data.qualityLabel

@Composable
fun CdScreen(vm: MainViewModel, s: AppSettings, onOpenSettings: () -> Unit) {
    var showEdit by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var confirmUnknown by remember { mutableStateOf(false) }
    val audio = vm.audioTracks
    // 保存先が未設定なら、その場でフォルダを選んで取り込みを始める
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            vm.setOutputFolder(uri)
            vm.prepareRip(uri.toString())
        }
    }
    val requestRip: () -> Unit = {
        when {
            vm.audioTracks.none { vm.selected[it.number] == true } -> vm.snack = S(R.string.select_tracks_to_rip)
            vm.meta?.title == null || vm.meta?.title == UNKNOWN_ALBUM -> confirmUnknown = true
            s.outputUri == null -> folderPicker.launch(null)
            else -> vm.prepareRip()
        }
    }

    // 曲情報の編集は画面いっぱいに出す(ダイアログだと狭くて入力しにくいため)
    if (showEdit && vm.meta != null) {
        EditMetaPage(vm, s) { showEdit = false }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader(S(R.string.disc)) {
                IconButton({ vm.refreshDisc() }, enabled = vm.connected && !vm.busy && !vm.discBusy) {
                    Icon(Icons.Filled.Refresh, S(R.string.reload_disc))
                }
                IconButton({ vm.eject() }, enabled = vm.connected && !vm.busy) { Icon(AppIcons.Eject, S(R.string.eject)) }
            }
        }
        when {
            !vm.connected -> item {
                EmptyState(
                    AppIcons.Album, S(R.string.no_drive_connected),
                    S(R.string.connect_external_drive_over_usb, vm.status),
                    S(R.string.search_again),
                ) { vm.scan() }
            }
            audio.isEmpty() -> item {
                if (vm.discBusy) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(Modifier.width(160.dp))
                        Spacer(Modifier.size(16.dp))
                        Text(S(R.string.reading_disc), style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    val loading = vm.discText == S(R.string.loading_disc)
                    EmptyState(
                        AppIcons.Album,
                        vm.discText.ifEmpty { S(R.string.insert_disc) },
                        if (loading) S(R.string.wait_few_seconds_while_disc)
                        else S(R.string.audio_cds_are_read_automatically) +
                            "\n" + S(R.string.if_nothing_appears_tap_reload),
                        S(R.string.reload),
                    ) { vm.refreshDisc() }
                }
            }
            else -> {
                item { AlbumHeader(vm, s, { showEdit = true }, { showQuality = true }, onOpenSettings, requestRip) }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(S(R.string.tracks_4), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        val all = audio.all { vm.selected[it.number] == true }
                        TextButton({ vm.selectAll(!all) }, enabled = !vm.busy) { Text(if (all) S(R.string.deselect_all) else S(R.string.select_all)) }
                    }
                }
                items(audio, key = { it.number }) { t ->
                    val playing = vm.player.isCd && vm.player.current?.cdTrack?.number == t.number
                    val st = vm.trackStatus[t.number].orEmpty()
                    val pr = vm.progress[t.number] ?: 0f
                    ListItem(
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !vm.busy) { vm.playCd(t) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (playing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        ),
                        leadingContent = {
                            Checkbox(vm.selected[t.number] == true, { vm.toggle(t.number) }, enabled = !vm.busy)
                        },
                        headlineContent = {
                            Text(
                                "${t.number}. ${vm.trackTitle(t)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (playing) FontWeight.Bold else null,
                            )
                        },
                        supportingContent = {
                            Column {
                                Text(vm.trackArtist(t), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (vm.busy && pr > 0f && pr < 1f) {
                                    LinearProgressIndicator({ pr }, Modifier.fillMaxWidth().padding(top = 6.dp))
                                } else if (st.isNotEmpty()) {
                                    Text(st, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                        trailingContent = { Text(fmtTime(t.endLba - t.startLba), style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
        }
        if (vm.connected) {
            item {
                Text(
                    vm.driveName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, start = 4.dp),
                )
            }
        }
    }

    if (vm.showChooser) MetaChooserDialog(vm)
    if (confirmUnknown) {
        AlertDialog(
            onDismissRequest = { confirmUnknown = false },
            title = { Text(S(R.string.no_track_info)) },
            text = {
                Text(
                    S(R.string.tracks_will_be_named_track),
                )
            },
            confirmButton = {
                TextButton({
                    confirmUnknown = false
                    if (s.outputUri == null) folderPicker.launch(null) else vm.prepareRip()
                }) { Text(S(R.string.rip_anyway)) }
            },
            dismissButton = {
                TextButton({ confirmUnknown = false; vm.openChooser() }) { Text(S(R.string.choose_track_info)) }
            },
        )
    }
    vm.ripPlan?.let { RipConfirmDialog(vm, s, it) }
    if (showQuality) {
        AlertDialog(
            onDismissRequest = { showQuality = false },
            confirmButton = { TextButton({ showQuality = false }) { Text(S(R.string.close)) } },
            title = { Text(S(R.string.rip_quality)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { QualityOptions(vm, s) } },
        )
    }
}

@Composable
private fun AlbumHeader(
    vm: MainViewModel,
    s: AppSettings,
    onEdit: () -> Unit,
    onQuality: () -> Unit,
    onOpenSettings: () -> Unit,
    onRip: () -> Unit,
) {
    val audio = vm.audioTracks
    val totalSec = audio.sumOf { it.endLba - it.startLba }
    val year = vm.meta?.date?.take(4).orEmpty()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
                    if (u != null) vm.pickCdCover(u)
                }
                CoverBox(
                    vm.cdCover,
                    Modifier.size(116.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !vm.busy) {
                        pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    12.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        vm.albumTitle, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = !vm.busy) { vm.openChooser() },
                    )
                    Text(
                        vm.albumArtist, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        listOf(year, S(R.string.tracks, audio.size), fmtTime(totalSec)).filter { it.isNotEmpty() }.joinToString(S(R.string.label_5)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (vm.metaSearching) LinearProgressIndicator(Modifier.width(120.dp).padding(top = 4.dp))
                    if (vm.metaStatus.isNotEmpty()) {
                        Text(
                            vm.metaStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(enabled = !vm.busy) { vm.openChooser() },
                        )
                    }
                    if (!vm.metaSearching && !vm.busy) {
                        Text(
                            S(R.string.tap_to_choose_different_track),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { vm.openChooser() },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onRip, Modifier.weight(1f), enabled = !vm.busy && !vm.discBusy) {
                    Icon(AppIcons.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(S(R.string.rip))
                }
                FilledTonalButton({ vm.playCd() }, Modifier.weight(1f), enabled = !vm.busy) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(S(R.string.play))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = onQuality,
                    enabled = !vm.busy,
                    label = { Text(s.qualityLabel()) },
                    leadingIcon = { Icon(AppIcons.MusicNote, null, Modifier.size(18.dp)) },
                )
                Spacer(Modifier.width(8.dp))
                // 空の CD-R に、この CD と同じ内容を書く
                AssistChip(
                    onClick = { vm.startCopy() },
                    enabled = !vm.busy && !vm.discBusy,
                    label = { Text(S(R.string.copy_cd)) },
                    leadingIcon = { Icon(AppIcons.Album, null, Modifier.size(18.dp)) },
                )
                Spacer(Modifier.weight(1f))
                // 自分で入力した曲情報(MusicBrainz から取ったものでないとき)は、MusicBrainz に登録できる
                if (vm.meta != null && vm.meta?.releaseId == null) {
                    if (vm.mbUploadBusy) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton({ vm.uploadToMusicBrainz() }, enabled = !vm.busy && !vm.metaSearching) {
                            Icon(AppIcons.Upload, S(R.string.mb_upload))
                        }
                    }
                }
                IconButton(onEdit, enabled = !vm.busy) { Icon(Icons.Filled.Edit, S(R.string.edit_track_info)) }
                IconButton({ vm.openChooser() }, enabled = !vm.busy) { Icon(Icons.Filled.Search, S(R.string.matches_and_search)) }
            }
            if (s.outputUri == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        S(R.string.no_save_folder_set), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f),
                    )
                    TextButton(onOpenSettings) { Text(S(R.string.set_up)) }
                }
            }
        }
    }
}

@Composable
internal fun CandidateRow(c: ReleaseCandidate, cdTracks: Int, enabled: Boolean, inUse: Boolean, onClick: () -> Unit) {
    val countOk = c.trackCount == cdTracks
    ListItem(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (inUse) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        overlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("MusicBrainz", style = MaterialTheme.typography.labelSmall)
                if (inUse) {
                    Text(
                        S(R.string.in_use), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold,
                    )
                }
                if (c.exact) {
                    Text(
                        S(R.string.disc_match), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        headlineContent = { Text(c.meta.title.ifBlank { "-" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(c.meta.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.summary, style = MaterialTheme.typography.bodySmall)
                if (!countOk && c.trackCount > 0) {
                    Text(
                        S(R.string.track_count_differs_cd_has, cdTracks),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

@Composable
private fun MetaChooserDialog(vm: MainViewModel) {
    val cdTracks = vm.audioTracks.size
    val cur = vm.meta
    var album by remember { mutableStateOf(cur?.title?.takeIf { it != UNKNOWN_ALBUM }.orEmpty()) }
    var artist by remember { mutableStateOf(cur?.artist?.takeIf { it != UNKNOWN_ARTIST }.orEmpty()) }
    val busy = vm.applyingCandidate
    fun inUse(c: ReleaseCandidate): Boolean {
        val m = vm.meta ?: return false
        return if (c.meta.releaseId != null) c.meta.releaseId == m.releaseId
        else m.releaseId == null && c.meta.title == m.title && c.meta.artist == m.artist
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val doSearch = { if (album.isNotBlank() || artist.isNotBlank()) vm.searchByName(album, artist) }
    ModalBottomSheet(onDismissRequest = { vm.closeChooser() }, sheetState = sheet) {
            LazyColumn(
                Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(S(R.string.choose_track_info), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton({ vm.closeChooser() }) { Text(S(R.string.close)) }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            S(R.string.matches_for_this_disc), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton({ vm.fetchMeta(openChooserAlways = true) }, enabled = !vm.metaSearching && !busy) {
                            Text(S(R.string.retry))
                        }
                    }
                }
                if (vm.metaSearching) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
                } else if (vm.metaError.isNotEmpty()) {
                    item {
                        Text(
                            S(R.string.couldnt_search_due_to_network),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        )
                        Text(vm.metaError, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                } else if (vm.candidates.isEmpty()) {
                    item {
                        Text(
                            S(R.string.no_matches_found_you_can),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(vm.candidates) { c -> CandidateRow(c, cdTracks, !busy, inUse(c)) { vm.applyCandidate(c) } }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(S(R.string.search_by_name), style = MaterialTheme.typography.titleSmall)
                }
                item {
                    OutlinedTextField(
                        album, { album = it }, Modifier.fillMaxWidth(),
                        label = { Text(S(R.string.album)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    )
                }
                item {
                    OutlinedTextField(
                        artist, { artist = it }, Modifier.fillMaxWidth(),
                        label = { Text(S(R.string.artist)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            { vm.searchByName(album, artist) },
                            enabled = !vm.searchBusy && !busy && (album.isNotBlank() || artist.isNotBlank()),
                        ) {
                            Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(S(R.string.search))
                        }
                        if (vm.searchBusy || busy) {
                            Spacer(Modifier.width(12.dp))
                            LinearProgressIndicator(Modifier.weight(1f))
                        }
                    }
                    if (vm.searchMessage.isNotEmpty()) {
                        Text(vm.searchMessage, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                items(vm.searchResults) { c -> CandidateRow(c, cdTracks, !busy, inUse(c)) { vm.applyCandidate(c) } }
            }
    }
}

/** 曲情報がすでに MusicBrainz にあったとき(登録はしない)。CD の画面とライブラリの両方から使う */
@Composable
internal fun MbExistingDialog(vm: MainViewModel) {
    val e = vm.mbExisting ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun open(url: String) {
        runCatching {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
    }
    AlertDialog(
        onDismissRequest = { vm.mbExisting = null },
        title = { Text(S(R.string.mb_upload)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(e.message)
                Text(e.title, style = MaterialTheme.typography.titleMedium)
                if (e.summary.isNotBlank()) {
                    Text(e.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (e.releaseUrl != null) {
                    TextButton({ open(e.releaseUrl) }) { Text(S(R.string.mb_open_release)) }
                }
            }
        },
        confirmButton = {
            if (e.attachUrl != null) {
                TextButton({ open(e.attachUrl); vm.mbExisting = null }) { Text(S(R.string.mb_add_disc_id)) }
            } else {
                TextButton({ vm.mbExisting = null }) { Text(S(R.string.close)) }
            }
        },
        dismissButton = if (e.attachUrl != null) ({ TextButton({ vm.mbExisting = null }) { Text(S(R.string.close)) } }) else null,
    )
}

/** CD の曲情報を編集する画面(画面いっぱい)。詳しい情報と歌詞は、取り込むときにファイルに書く */
@Composable
private fun EditMetaPage(vm: MainViewModel, s: AppSettings, onDone: () -> Unit) {
    val m = vm.meta ?: return
    val audio = vm.audioTracks
    // WAV には詳しい情報と歌詞を書けない
    val detail = s.format != com.uyatame.ucrt.data.AudioFormat.WAV
    val form = remember(m) {
        TagForm(
            m.title, m.artist, m.date, m.genre,
            m.tracks.mapIndexed { i, t ->
                val tt = audio.getOrNull(i)
                TrackForm(
                    trackLabel(tt?.number ?: (i + 1)), t.title, if (t.artist == m.artist) "" else t.artist,
                    tt?.let { fmtTime(it.endLba - it.startLba) }.orEmpty(), detail,
                    t.composer, t.lyricist, t.comment, t.lyrics,
                )
            },
        )
    }
    TagEditorPage(
        S(R.string.edit_track_info), form,
        onCancel = onDone,
        onSave = {
            val artist = form.albumArtist.trim()
            vm.updateMeta(
                m.copy(
                    title = form.album.trim(), artist = artist, date = form.date.trim(), genre = form.genre.trim(),
                    tracks = m.tracks.mapIndexed { i, tm ->
                        val f = form.tracks.getOrNull(i) ?: return@mapIndexed tm
                        tm.copy(
                            title = f.title.trim(), artist = f.artist.trim().ifEmpty { artist },
                            composer = f.composer.trim(), lyricist = f.lyricist.trim(), comment = f.comment.trim(), lyrics = f.lyrics.trim(),
                        )
                    },
                ),
            )
            onDone()
        },
        dateLabel = S(R.string.release_date_e_g_2024),
        note = if (!detail) S(R.string.tag_detail_wav) else null,
    )
}

/** 取り込む前の確認(保存先・曲数・容量・同じ名前のファイル) */
@Composable
private fun RipConfirmDialog(vm: MainViewModel, s: AppSettings, plan: MainViewModel.RipPlan) {
    var skip by remember(plan) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { vm.dismissRipPlan() },
        title = { Text(S(R.string.confirm_ripping)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(S(R.string.tracks_2, plan.count, s.qualityLabel()))
                Text(
                    S(R.string.save_to) + plan.dest,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    S(R.string.size_about) + String.format(java.util.Locale.US, "%.0f MB", plan.sizeBytes / 1048576.0) +
                        S(R.string.make_sure_you_have_enough),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (plan.existing.isNotEmpty()) {
                    Text(
                        S(R.string.files_with_same_name_already, plan.existing.size),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        plan.existing.take(5).joinToString("\n") + if (plan.existing.size > 5) "\n…" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { skip = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(skip, { skip = true })
                        Text(S(R.string.skip_existing_tracks))
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { skip = false },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(!skip, { skip = false })
                        Text(S(R.string.overwrite_replaced_only_after_successful))
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ vm.confirmRip(plan.existing.isNotEmpty() && skip) }) { Text(S(R.string.rip)) }
        },
        dismissButton = { TextButton({ vm.dismissRipPlan() }) { Text(S(R.string.cancel)) } },
    )
}
