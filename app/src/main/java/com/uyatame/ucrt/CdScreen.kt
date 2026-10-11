@file:OptIn(ExperimentalMaterial3Api::class)

package com.uyatame.ucrt

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
            vm.startRip(uri.toString())
        }
    }
    val requestRip: () -> Unit = {
        when {
            vm.audioTracks.none { vm.selected[it.number] == true } -> vm.snack = T("取り込む曲を選んでください", "Select tracks to rip")
            vm.meta?.title == null || vm.meta?.title == UNKNOWN_ALBUM -> confirmUnknown = true
            s.outputUri == null -> folderPicker.launch(null)
            else -> vm.startRip()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader(T("ディスク", "Disc")) {
                IconButton({ vm.refreshDisc() }, enabled = vm.connected && !vm.busy && !vm.discBusy) {
                    Icon(Icons.Filled.Refresh, T("ディスクを読み直す", "Reload disc"))
                }
                IconButton({ vm.eject() }, enabled = vm.connected && !vm.busy) { Icon(AppIcons.Eject, T("取り出す", "Eject")) }
            }
        }
        when {
            !vm.connected -> item {
                EmptyState(
                    AppIcons.Album, T("ドライブが接続されていません", "No drive connected"),
                    T("${vm.status}\n外付けドライブをUSBで接続してください。電力が足りない場合はセルフパワーのUSBハブを使ってください。", "${vm.status}\nConnect an external drive via USB. If power is insufficient, use a self-powered USB hub."),
                    T("もう一度探す", "Search again"),
                ) { vm.scan() }
            }
            audio.isEmpty() -> item {
                if (vm.discBusy) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(Modifier.width(160.dp))
                        Spacer(Modifier.size(16.dp))
                        Text(T("ディスクを読み込んでいます…", "Reading disc…"), style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    val loading = vm.discText == T("ディスクを読み込み中…", "Loading disc…")
                    EmptyState(
                        AppIcons.Album,
                        vm.discText.ifEmpty { T("ディスクを入れてください", "Insert a disc") },
                        if (loading) T("回転が安定するまで、数秒お待ちください", "Please wait a few seconds for the disc to spin up")
                        else T("音楽CDを入れると、自動で読み込みます", "Insert an audio CD and it will be read automatically") +
                            "\n" + T("表示されないときは「読み直す」を押してください", "If nothing appears, tap \"Reload\""),
                        T("読み直す", "Reload"),
                    ) { vm.refreshDisc() }
                }
            }
            else -> {
                item { AlbumHeader(vm, s, { showEdit = true }, { showQuality = true }, onOpenSettings, requestRip) }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(T("曲目", "Tracks"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        val all = audio.all { vm.selected[it.number] == true }
                        TextButton({ vm.selectAll(!all) }, enabled = !vm.busy) { Text(if (all) T("選択を解除", "Deselect all") else T("すべて選択", "Select all")) }
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
            title = { Text(T("曲情報が未設定です", "No track info")) },
            text = {
                Text(
                    T(
                        "このまま取り込むと、曲名は「トラック 01」などになります。取り込んだ後でもライブラリから曲情報を変更できます。",
                        "Tracks will be named \"Track 01\" and so on. You can still edit the info later in the Library.",
                    ),
                )
            },
            confirmButton = {
                TextButton({
                    confirmUnknown = false
                    if (s.outputUri == null) folderPicker.launch(null) else vm.startRip()
                }) { Text(T("このまま取り込む", "Rip anyway")) }
            },
            dismissButton = {
                TextButton({ confirmUnknown = false; vm.openChooser() }) { Text(T("曲情報を選ぶ", "Choose info")) }
            },
        )
    }
    if (showEdit) EditMetaDialog(vm) { showEdit = false }
    if (showQuality) {
        AlertDialog(
            onDismissRequest = { showQuality = false },
            confirmButton = { TextButton({ showQuality = false }) { Text(T("閉じる", "Close")) } },
            title = { Text(T("取り込みの音質", "Rip quality")) },
            text = { QualityOptions(vm, s) },
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
                        listOf(year, T("${audio.size}曲", "${audio.size} tracks"), fmtTime(totalSec)).filter { it.isNotEmpty() }.joinToString(T(" ・ ", " · ")),
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
                            T("タップで曲情報を選び直す", "Tap to choose different info"),
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
                    Text(T("取り込む", "Rip"))
                }
                FilledTonalButton({ vm.playCd() }, Modifier.weight(1f), enabled = !vm.busy) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(T("再生", "Play"))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = onQuality,
                    enabled = !vm.busy,
                    label = { Text(s.qualityLabel()) },
                    leadingIcon = { Icon(AppIcons.MusicNote, null, Modifier.size(18.dp)) },
                )
                Spacer(Modifier.weight(1f))
                IconButton(onEdit, enabled = !vm.busy) { Icon(Icons.Filled.Edit, T("曲情報を編集", "Edit track info")) }
                IconButton({ vm.openChooser() }, enabled = !vm.busy) { Icon(Icons.Filled.Search, T("曲情報の候補・検索", "Matches & search")) }
            }
            if (s.outputUri == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        T("保存先フォルダが未設定です", "No save folder selected"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f),
                    )
                    TextButton(onOpenSettings) { Text(T("設定する", "Set up")) }
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
                        T("使用中", "In use"), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold,
                    )
                }
                if (c.exact) {
                    Text(
                        T("ディスク一致", "Disc match"), style = MaterialTheme.typography.labelSmall,
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
                        T("曲数が違います(CDは${cdTracks}曲)", "Different track count (CD has $cdTracks)"),
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
                        Text(T("曲情報を選ぶ", "Choose track info"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton({ vm.closeChooser() }) { Text(T("閉じる", "Close")) }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            T("ディスクから見つかった候補", "Matches for this disc"), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton({ vm.fetchMeta(openChooserAlways = true) }, enabled = !vm.metaSearching && !busy) {
                            Text(T("再検索", "Retry"))
                        }
                    }
                }
                if (vm.metaSearching) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
                } else if (vm.metaError.isNotEmpty()) {
                    item {
                        Text(
                            T("通信エラーのため検索できませんでした", "Lookup failed due to a network error"),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        )
                        Text(vm.metaError, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                } else if (vm.candidates.isEmpty()) {
                    item {
                        Text(
                            T("見つかりませんでした。下で名前から検索できます", "None found. You can search by name below"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(vm.candidates) { c -> CandidateRow(c, cdTracks, !busy, inUse(c)) { vm.applyCandidate(c) } }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(T("名前で検索", "Search by name"), style = MaterialTheme.typography.titleSmall)
                }
                item {
                    OutlinedTextField(
                        album, { album = it }, Modifier.fillMaxWidth(),
                        label = { Text(T("アルバム名", "Album")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    )
                }
                item {
                    OutlinedTextField(
                        artist, { artist = it }, Modifier.fillMaxWidth(),
                        label = { Text(T("アーティスト", "Artist")) }, singleLine = true,
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
                            Text(T("検索", "Search"))
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

@Composable
private fun EditMetaDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val m = vm.meta ?: return
    var title by remember { mutableStateOf(m.title) }
    var artist by remember { mutableStateOf(m.artist) }
    var date by remember { mutableStateOf(m.date) }
    val titles = remember { mutableStateListOf<String>().apply { addAll(m.tracks.map { it.title }) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                vm.updateMeta(
                    m.copy(
                        title = title.trim(), artist = artist.trim(), date = date.trim(),
                        tracks = m.tracks.mapIndexed { i, tm ->
                            tm.copy(
                                title = titles.getOrElse(i) { tm.title }.trim(),
                                artist = if (tm.artist == m.artist) artist.trim() else tm.artist,
                            )
                        },
                    ),
                )
                onDismiss()
            }) { Text(T("保存", "Save")) }
        },
        dismissButton = { TextButton(onDismiss) { Text(T("キャンセル", "Cancel")) } },
        title = { Text(T("曲情報を編集", "Edit track info")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(T("アルバム名", "Album")) }, singleLine = true)
                }
                item {
                    OutlinedTextField(artist, { artist = it }, Modifier.fillMaxWidth(), label = { Text(T("アーティスト", "Artist")) }, singleLine = true)
                }
                item {
                    OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text(T("発売日(例: 2024 / 2024-05-01)", "Release date (e.g. 2024 / 2024-05-01)")) }, singleLine = true)
                }
                items(titles.size) { i ->
                    OutlinedTextField(
                        titles[i], { titles[i] = it }, Modifier.fillMaxWidth(),
                        label = { Text(T("${i + 1}曲目", "Track ${i + 1}")) }, singleLine = true,
                    )
                }
            }
        },
    )
}
