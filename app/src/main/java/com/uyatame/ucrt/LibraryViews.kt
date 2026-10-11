@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.uyatame.ucrt

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.uyatame.ucrt.library.LibAlbum
import com.uyatame.ucrt.library.LibTrack
import com.uyatame.ucrt.library.Quality
import com.uyatame.ucrt.library.QualityClass
import com.uyatame.ucrt.player.AudioInfo
import com.uyatame.ucrt.player.PlayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------- 音質のバッジ ----------------

/** ハイレゾの金色 */
val HiResGold = Color(0xFFE2B24A)
private val HiResInk = Color(0xFF2E2200)

/**
 * 形式・音質の小さな札(例: FLAC 96/24)。ハイレゾは金色で塗りつぶし、それ以外は枠だけ。
 */
@Composable
fun QualityBadge(text: String, q: QualityClass, modifier: Modifier = Modifier, onDark: Boolean = false) {
    if (text.isEmpty()) return
    val shape = RoundedCornerShape(4.dp)
    val base = if (onDark) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    val m = when (q) {
        QualityClass.HiRes -> modifier.background(HiResGold, shape)
        else -> modifier.border(0.8.dp, base.copy(alpha = 0.6f), shape)
    }
    Box(m.padding(horizontal = 4.dp, vertical = 1.dp)) {
        Text(
            text, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (q == QualityClass.HiRes) HiResInk else base,
        )
    }
}

@Composable
fun TrackBadge(t: LibTrack, modifier: Modifier = Modifier) = QualityBadge(Quality.badge(t), Quality.classOf(t), modifier)

// ---------------- 一覧の部品 ----------------

/** 区切りの見出し(右側に「すべて表示」などを置ける) */
@Composable
fun ListHeading(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** 曲の行。長押し(または右端のボタン)で操作メニューを開く */
@Composable
fun TrackRow(
    vm: MainViewModel,
    t: LibTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    number: String? = null,
    showArt: Boolean = true,
    sub: String? = null,
    trailingText: String? = null,
    playlist: Pair<String, Int>? = null,
    handle: (@Composable () -> Unit)? = null,
) {
    val playing = vm.player.current?.uri?.toString() == t.uri
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (playing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = { vm.showTrackMenu(t, playlist) })
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (handle != null) handle()
        when {
            number != null -> Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterEnd) {
                if (playing) Icon(AppIcons.Bars, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                else Text(number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            showArt -> Box {
                ArtImage(vm.albumKeyOf(t.uri) ?: t.uri, t.uri, t.coverUri, Modifier.size(48.dp), 6.dp)
                if (playing) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(AppIcons.Bars, null, Modifier.size(20.dp), tint = Color.White) }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (playing) FontWeight.SemiBold else null,
                color = if (playing) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrackBadge(t)
                Spacer(Modifier.width(6.dp))
                Text(
                    sub ?: t.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            trailingText ?: fmtMs(t.durationMs), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
        )
        IconButton({ vm.showTrackMenu(t, playlist) }) {
            Icon(Icons.Filled.MoreVert, T("その他の操作", "More"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** アルバムのタイル(グリッド表示)。長押しで操作メニュー */
@Composable
fun AlbumTile(vm: MainViewModel, a: LibAlbum, modifier: Modifier = Modifier, sub: String? = null) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = { vm.libPush("album:" + a.key) }, onLongClick = { vm.menuAlbum = a }),
    ) {
        Box {
            ArtImage(a.key, a.first.uri, a.first.coverUri, Modifier.fillMaxWidth().aspectRatio(1f), 12.dp)
            val q = Quality.classOf(a)
            if (q == QualityClass.HiRes) {
                QualityBadge(Quality.badge(a), q, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            FilledIconButton(
                { vm.playAlbum(a, 0) },
                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(38.dp),
            ) { Icon(Icons.Filled.PlayArrow, T("再生", "Play")) }
        }
        Text(
            a.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        Text(
            sub ?: a.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
    }
}

/** アルバムの行(リスト表示) */
@Composable
fun AlbumListRow(vm: MainViewModel, a: LibAlbum, sub: String? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = { vm.libPush("album:" + a.key) }, onLongClick = { vm.menuAlbum = a })
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(a.key, a.first.uri, a.first.coverUri, Modifier.size(56.dp), 8.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                QualityBadge(Quality.badge(a), Quality.classOf(a))
                Spacer(Modifier.width(6.dp))
                Text(
                    sub ?: (a.artist + T(" ・ ${a.tracks.size}曲", " · ${a.tracks.size} tracks")),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton({ vm.playAlbum(a, 0) }) { Icon(Icons.Filled.PlayArrow, T("再生", "Play")) }
    }
}

/** カテゴリー一覧の行(アーティスト・ジャンル・年・フォルダなど) */
@Composable
fun GroupRow(
    title: String,
    sub: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** 丸いアイコン(ジャンル・年などの頭に置く) */
@Composable
fun RoundIcon(icon: ImageVector, size: Int = 48) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size((size * 0.5f).dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
}

/** 頭文字の丸(アーティスト) */
@Composable
fun InitialAvatar(name: String, size: Int = 48) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" }, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.Bold,
        )
    }
}

// ---------------- 並べ替え(ドラッグ) ----------------

/**
 * LazyColumn の項目を、つまみをドラッグして並べ替える。
 * headerCount は、並べ替える項目より前にある項目(見出しなど)の数。
 */
class ReorderState(private val list: LazyListState, private val headerCount: Int, private val onMove: (Int, Int) -> Unit) {
    var dragging by mutableStateOf<Int?>(null)
        private set
    var dy by mutableFloatStateOf(0f)
        private set

    fun start(i: Int) {
        dragging = i
        dy = 0f
    }

    fun drag(delta: Float) {
        val i = dragging ?: return
        dy += delta
        val items = list.layoutInfo.visibleItemsInfo
        val me = items.firstOrNull { it.index == i + headerCount } ?: return
        val center = me.offset + dy + me.size / 2f
        val target = items.firstOrNull {
            it.index != me.index && it.index >= headerCount && center >= it.offset && center <= it.offset + it.size
        } ?: return
        val to = target.index - headerCount
        onMove(i, to)
        dy += (me.offset - target.offset).toFloat()
        dragging = to
    }

    fun end() {
        dragging = null
        dy = 0f
    }
}

@Composable
fun rememberReorder(list: LazyListState, headerCount: Int, onMove: (Int, Int) -> Unit): ReorderState {
    val move by rememberUpdatedState(onMove)
    return remember(list, headerCount) { ReorderState(list, headerCount) { a, b -> move(a, b) } }
}

/** ドラッグ中の項目を浮かせて指に追従させる */
fun Modifier.reorderItem(state: ReorderState, index: Int): Modifier =
    if (state.dragging == index) this.zIndex(1f).graphicsLayer { translationY = state.dy; shadowElevation = 12f } else this

/** 並べ替えのつまみ */
@Composable
fun DragHandle(state: ReorderState, index: Int, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val idx by rememberUpdatedState(index)
    Box(
        Modifier.size(40.dp).pointerInput(state) {
            detectDragGestures(
                onDragStart = { state.start(idx) },
                onDragEnd = { state.end() },
                onDragCancel = { state.end() },
            ) { change, amount ->
                change.consume()
                state.drag(amount.y)
            }
        },
        contentAlignment = Alignment.Center,
    ) { Icon(AppIcons.DragHandle, T("ドラッグして並べ替え", "Drag to reorder"), tint = tint) }
}

// ---------------- どの画面からでも開くメニュー ----------------

@Composable
private fun SheetAction(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 曲・アルバムの操作メニュー、プレイリストの選択、曲の詳細情報 */
@Composable
fun GlobalSheets(vm: MainViewModel) {
    TrackMenuSheet(vm)
    AlbumMenuSheet(vm)
    PlaylistPickDialog(vm)
    TrackInfoDialog(vm)
}

/** 曲の「アーティストを表示」で開くアーティスト(アルバムアーティストがあればそちら) */
fun artistOf(t: LibTrack): String = t.albumArtist.ifEmpty { t.artist }

@Composable
private fun TrackMenuSheet(vm: MainViewModel) {
    val t = vm.menuTrack ?: return
    val ctxPl = vm.menuTrackPlaylist
    val close = { vm.menuTrack = null; vm.menuTrackPlaylist = null }
    ModalBottomSheet(onDismissRequest = close) {
        Row(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtImage(vm.albumKeyOf(t.uri) ?: t.uri, t.uri, t.coverUri, Modifier.size(56.dp), 8.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    t.artist + " ・ " + t.album, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TrackBadge(t, Modifier.padding(top = 4.dp))
            }
        }
        HorizontalDivider()
        SheetAction(AppIcons.PlayNext, T("次に再生", "Play next")) { vm.playNext(listOf(t)); close() }
        SheetAction(AppIcons.Queue, T("再生リストの最後に追加", "Add to queue")) { vm.addToQueue(listOf(t)); close() }
        val fav = vm.store.isFav(t.uri)
        SheetAction(
            if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            if (fav) T("お気に入りから外す", "Remove from favorites") else T("お気に入りに追加", "Add to favorites"),
        ) { vm.toggleFav(t.uri); close() }
        SheetAction(AppIcons.PlaylistAdd, T("プレイリストに追加…", "Add to playlist…")) { vm.playlistPick = listOf(t.uri); close() }
        if (ctxPl != null) {
            SheetAction(Icons.Filled.Delete, T("このプレイリストから外す", "Remove from this playlist")) {
                vm.store.removeFromPlaylist(ctxPl.first, ctxPl.second); close()
            }
        }
        vm.albumKeyOf(t.uri)?.let { k ->
            SheetAction(AppIcons.Album, T("アルバムを表示", "Go to album")) { close(); vm.openRoute("album:$k") }
        }
        SheetAction(Icons.Filled.Person, T("アーティストを表示", "Go to artist")) { close(); vm.openRoute("artist:" + artistOf(t)) }
        SheetAction(Icons.Filled.Info, T("曲の詳細情報", "Song details")) { vm.infoItem = vm.itemOf(t); close() }
        Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun AlbumMenuSheet(vm: MainViewModel) {
    val a = vm.menuAlbum ?: return
    val close = { vm.menuAlbum = null }
    ModalBottomSheet(onDismissRequest = close) {
        Row(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtImage(a.key, a.first.uri, a.first.coverUri, Modifier.size(56.dp), 8.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    a.artist + T(" ・ ${a.tracks.size}曲", " · ${a.tracks.size} tracks"), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QualityBadge(Quality.badge(a), Quality.classOf(a), Modifier.padding(top = 4.dp))
            }
        }
        HorizontalDivider()
        SheetAction(Icons.Filled.PlayArrow, T("再生", "Play")) { vm.playAlbum(a, 0); close() }
        SheetAction(AppIcons.Shuffle, T("シャッフル再生", "Shuffle")) { vm.playAlbum(a, 0, shuffle = true); close() }
        SheetAction(AppIcons.PlayNext, T("次に再生", "Play next")) { vm.playNext(a.tracks); close() }
        SheetAction(AppIcons.Queue, T("再生リストの最後に追加", "Add to queue")) { vm.addToQueue(a.tracks); close() }
        val fav = vm.store.isFavAlbum(a.key)
        SheetAction(
            if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            if (fav) T("お気に入りから外す", "Remove from favorites") else T("アルバムをお気に入りに追加", "Add album to favorites"),
        ) { vm.toggleFavAlbum(a.key); close() }
        SheetAction(AppIcons.PlaylistAdd, T("プレイリストに追加…", "Add to playlist…")) { vm.playlistPick = a.tracks.map { it.uri }; close() }
        SheetAction(Icons.Filled.Person, T("アーティストを表示", "Go to artist")) { close(); vm.openRoute("artist:" + a.artist) }
        if (vm.libStack.lastOrNull() != "album:" + a.key) {
            SheetAction(AppIcons.Album, T("アルバムを開く", "Open album")) { close(); vm.openRoute("album:" + a.key) }
        }
        Spacer(Modifier.size(16.dp))
    }
}

/** プレイリストを選んで追加(新しく作ることもできる) */
@Composable
private fun PlaylistPickDialog(vm: MainViewModel) {
    val uris = vm.playlistPick ?: return
    var creating by remember { mutableStateOf(vm.store.playlists.isEmpty()) }
    var name by remember { mutableStateOf("") }
    val close = { vm.playlistPick = null }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(T("プレイリストに追加", "Add to playlist")) },
        text = {
            Column {
                Text(
                    T("${uris.size}曲を追加します", "${uris.size} songs will be added"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                if (creating) {
                    OutlinedTextField(
                        name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(T("新しいプレイリストの名前", "New playlist name")) },
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { creating = true }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(16.dp))
                                Text(T("新しいプレイリスト", "New playlist"), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        items(vm.store.playlists, key = { it.id }) { p ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable { vm.addToPlaylist(p.id, uris); close() }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(AppIcons.Queue, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(16.dp))
                                Text(p.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    T("${p.uris.size}曲", "${p.uris.size}"), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton({ vm.createPlaylist(name, uris); close() }, enabled = name.isNotBlank()) { Text(T("作成して追加", "Create")) }
            }
        },
        dismissButton = { TextButton(close) { Text(T("キャンセル", "Cancel")) } },
    )
}

/** 曲の詳細情報 */
@Composable
private fun TrackInfoDialog(vm: MainViewModel) {
    val item = vm.infoItem ?: return
    val ctx = LocalContext.current
    val detail by produceState<AudioInfo.Detail?>(null, item) {
        value = withContext(Dispatchers.IO) { runCatching { AudioInfo.detail(ctx, item) }.getOrNull() }
    }
    val t = item.uri?.toString()?.let { vm.trackMap[it] }
    val rows = buildList {
        add(T("曲名", "Title") to item.title)
        add(T("アーティスト", "Artist") to item.artist)
        add(T("アルバム", "Album") to item.album)
        if (t != null) {
            if (t.albumArtist.isNotEmpty()) add(T("アルバムアーティスト", "Album artist") to t.albumArtist)
            if (t.track > 0) add(T("トラック", "Track") to (if (t.disc > 1) "${t.disc}-${t.track}" else "${t.track}"))
            if (t.year.isNotEmpty()) add(T("発売年", "Year") to t.year)
            if (t.genre.isNotEmpty()) add(T("ジャンル", "Genre") to t.genre)
        }
        add(T("長さ", "Length") to fmtMs(item.durationMs))
        detail?.let { d ->
            add(T("形式", "Format") to d.text)
            if (d.hiRes) add(T("音質", "Quality") to T("ハイレゾ", "Hi-Res"))
            if (d.sizeBytes > 0) add(T("ファイルサイズ", "File size") to String.format(java.util.Locale.US, "%.1f MB", d.sizeBytes / 1048576.0))
        }
        item.uri?.let { u ->
            val path = runCatching { Uri.decode(DocumentsContract.getDocumentId(u)).substringAfter(':') }.getOrNull()
            if (!path.isNullOrEmpty()) add(T("場所", "Location") to path)
        }
        if (item.cdTrack != null) add(T("場所", "Location") to T("音楽CD ${item.cdTrack.number}曲目", "Audio CD track ${item.cdTrack.number}"))
    }
    AlertDialog(
        onDismissRequest = { vm.infoItem = null },
        title = { Text(T("曲の詳細情報", "Song details")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(rows) { (k, v) ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton({ vm.infoItem = null }) { Text(T("閉じる", "Close")) } },
    )
}

/** PlayItem から、ライブラリの曲を引く(CD の曲なら null) */
fun MainViewModel.libTrackOf(item: PlayItem?): LibTrack? = item?.uri?.toString()?.let { trackMap[it] }

/** 再生・シャッフルの 2 つのボタン */
@Composable
fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.Button(onPlay) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(T("再生", "Play"))
        }
        androidx.compose.material3.FilledTonalButton(onShuffle) {
            Icon(AppIcons.Shuffle, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(T("シャッフル", "Shuffle"))
        }
    }
}
