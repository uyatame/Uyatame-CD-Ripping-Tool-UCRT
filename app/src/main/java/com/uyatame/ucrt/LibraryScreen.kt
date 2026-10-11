@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.uyatame.ucrt

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.Keys
import com.uyatame.ucrt.library.LibAlbum
import com.uyatame.ucrt.library.LibTrack
import com.uyatame.ucrt.library.Quality
import com.uyatame.ucrt.library.QualityClass
import com.uyatame.ucrt.library.UNKNOWN_ALBUM
import com.uyatame.ucrt.library.UNKNOWN_ARTIST
import com.uyatame.ucrt.meta.ReleaseCandidate
import com.uyatame.ucrt.tags.TagEditor

// =====================================================================
// ライブラリ: トップ(カテゴリーのアイコン)から、カテゴリー → アルバム → 曲 とたどる。
// 行き先は vm.libStack に積む(戻るボタンで 1 つ戻る)。
// =====================================================================

@Composable
fun LibraryScreen(vm: MainViewModel, s: AppSettings) {
    val route = vm.libStack.lastOrNull()
    BackHandler(enabled = route != null) { vm.libPop() }
    // 曲情報を変えるとアルバムのまとまりが変わるので、新しい名前で開き直す
    LaunchedEffect(vm.albums, vm.pendingAlbumTitle, vm.tagSaving, vm.libScanning) {
        val t = vm.pendingAlbumTitle ?: return@LaunchedEffect
        if (vm.tagSaving || vm.libScanning) return@LaunchedEffect
        vm.albums.firstOrNull { it.title == t }?.let { a ->
            if (vm.libStack.lastOrNull()?.startsWith("album:") == true) vm.libReplaceTop("album:" + a.key)
            else vm.libPush("album:" + a.key)
        }
        vm.pendingAlbumTitle = null
    }
    when {
        route == null -> LibraryHome(vm, s)
        route == "search" -> SearchPage(vm)
        route.startsWith("cat:") -> CategoryPage(vm, s, route.removePrefix("cat:"))
        route.startsWith("album:") -> {
            val a = vm.albumByKey(route.removePrefix("album:"))
            if (a != null) AlbumDetail(vm, a) else MissingPage(vm)
        }
        route.startsWith("artist:") -> ArtistPage(vm, route.removePrefix("artist:"))
        route.startsWith("genre:") -> GenrePage(vm, route.removePrefix("genre:"))
        route.startsWith("year:") -> {
            val y = route.removePrefix("year:")
            AlbumCollectionPage(
                vm, s, y.ifEmpty { T("発売年の情報なし", "Unknown year") },
                vm.albums.filter { it.year == y }, sortable = false, key = route,
            )
        }
        route.startsWith("folder:") -> {
            val f = route.removePrefix("folder:")
            AlbumCollectionPage(
                vm, s, f.substringAfterLast('/').ifEmpty { "/" },
                vm.albums.filter { folderGroup(it) == f }.sortedBy { folderName(it).lowercase() },
                sortable = false, key = route, subtitle = f,
            )
        }
        route.startsWith("playlist:") -> PlaylistPage(vm, route.removePrefix("playlist:"))
        else -> LibraryHome(vm, s)
    }
}

// ---------------- 共通の部品 ----------------

/** 戻るボタン付きの見出し */
@Composable
private fun PageTop(vm: MainViewModel, title: String, sub: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ vm.libPop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, T("戻る", "Back")) }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

@Composable
private fun MissingPage(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, "")
        EmptyState(
            AppIcons.Album, T("見つかりません", "Not found"),
            T("曲の読み込み中か、ファイルが移動・削除された可能性があります", "The library may be loading, or the files were moved or deleted"),
        )
    }
}

/** 曲の合計時間と曲数(例: 12曲 ・ 48:10) */
private fun countText(tracks: List<LibTrack>): String {
    val d = fmtMs(tracks.sumOf { it.durationMs })
    return T("${tracks.size}曲 ・ $d", "${tracks.size} songs · $d")
}

/** 検索用に文字をそろえる(全角/半角、大文字/小文字、カタカナ/ひらがな) */
private fun norm(s: String): String {
    val n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).lowercase()
    val sb = StringBuilder(n.length)
    for (c in n) sb.append(if (c in 'ァ'..'ヶ') (c - 0x60) else c)
    return sb.toString()
}

// ---------------- トップ ----------------

private val TILE_IDS = listOf(
    "albums", "artists", "songs", "favorites", "playlists", "recent", "history", "top", "hires", "genres", "years", "folders",
)

private fun tileIcon(id: String): ImageVector = when (id) {
    "albums" -> AppIcons.Album
    "artists" -> Icons.Filled.Person
    "songs" -> AppIcons.MusicNote
    "favorites" -> Icons.Filled.Favorite
    "playlists" -> AppIcons.Queue
    "recent" -> AppIcons.NewAdded
    "history" -> AppIcons.History
    "top" -> AppIcons.Trending
    "hires" -> AppIcons.HiRes
    "genres" -> AppIcons.Genre
    "years" -> Icons.Filled.DateRange
    else -> AppIcons.Folder
}

private fun tileLabel(id: String): String = when (id) {
    "albums" -> T("アルバム", "Albums")
    "artists" -> T("アーティスト", "Artists")
    "songs" -> T("全曲", "Songs")
    "favorites" -> T("お気に入り", "Favorites")
    "playlists" -> T("プレイリスト", "Playlists")
    "recent" -> T("最近追加", "Recently added")
    "history" -> T("最近再生", "Recently played")
    "top" -> T("よく聴く曲", "Most played")
    "hires" -> T("音質別", "By quality")
    "genres" -> T("ジャンル", "Genres")
    "years" -> T("発売年", "Years")
    else -> T("フォルダ", "Folders")
}

/** 設定の文字列 → (カテゴリー, 表示するか) の並び */
private fun tileOrder(setting: String): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    setting.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { e ->
        val id = e.removePrefix("-")
        if (id in TILE_IDS && out.none { it.first == id }) out.add(id to !e.startsWith("-"))
    }
    TILE_IDS.forEach { id -> if (out.none { it.first == id }) out.add(id to true) }
    return out
}

private fun encodeTiles(list: List<Pair<String, Boolean>>): String =
    list.joinToString(",") { (if (it.second) "" else "-") + it.first }

@Composable
private fun LibraryHome(vm: MainViewModel, s: AppSettings) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addLibraryFolder(uri)
    }
    val hasFolders = s.outputUri != null || s.libraryFolders.isNotEmpty()
    var editTiles by remember { mutableStateOf(false) }
    if (editTiles) TileEditDialog(vm, s) { editTiles = false }
    val albums = vm.albums
    val store = vm.store
    val counts = remember(albums, store.favTracks, store.favAlbums, store.playlists, store.history) {
        val tracks = albums.sumOf { it.tracks.size }
        mapOf(
            "albums" to "${albums.size}",
            "artists" to "${albums.map { it.artist }.distinct().size}",
            "songs" to "$tracks",
            "favorites" to "${store.favTracks.size + store.favAlbums.size}",
            "playlists" to "${store.playlists.size}",
            "history" to "${store.history.size}",
            "hires" to T("ハイレゾ ${albums.count { Quality.classOf(it) == QualityClass.HiRes }}", "Hi-Res ${albums.count { Quality.classOf(it) == QualityClass.HiRes }}"),
            "genres" to "${albums.flatMap { a -> a.tracks.map { it.genre } }.filter { it.isNotEmpty() }.distinct().size}",
            "years" to "${albums.map { it.year }.filter { it.isNotEmpty() }.distinct().size}",
            "folders" to "${albums.map { folderGroup(it) }.distinct().size}",
        )
    }
    val tiles = remember(s.libTiles) { tileOrder(s.libTiles).filter { it.second }.map { it.first } }
    val recentAlbums = remember(albums) { albums.sortedByDescending { it.added }.take(15) }
    val playedAlbums = remember(albums, store.history) {
        store.history.mapNotNull { vm.albumKeyOf(it.uri) }.distinct().mapNotNull { k -> albums.firstOrNull { it.key == k } }.take(15)
    }
    val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "head", span = full) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(T("ライブラリ", "Library"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton({ editTiles = true }) { Icon(Icons.Filled.Edit, T("カテゴリーを並べ替え", "Arrange categories")) }
                // 手動の更新: すべての曲を読み直す(普段は新しく入った曲だけを自動で読み込む)
                IconButton({ vm.refreshLibrary(fresh = true) }, enabled = !vm.libScanning && hasFolders) {
                    Icon(Icons.Filled.Refresh, T("すべての曲を読み直す", "Rescan all songs"))
                }
            }
        }
        // 新しい曲が無いときの自動確認では、何も表示しない
        if (vm.libScanning && (vm.libFull || vm.libNew > 0 || albums.isEmpty())) {
            item(key = "scan", span = full) {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        if (vm.libFull) T("すべての曲を読み直しています ${vm.libProgress}", "Rescanning all songs ${vm.libProgress}")
                        else T("新しい曲を読み込んでいます ${vm.libProgress}", "Loading new songs ${vm.libProgress}"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
        if (albums.isEmpty() && !vm.libScanning) {
            item(key = "empty", span = full) {
                EmptyState(
                    AppIcons.Library, T("まだ曲がありません", "No songs yet"),
                    T(
                        "CDを取り込むと、ここに表示されます。端末にある音楽フォルダを追加して、その曲を再生することもできます",
                        "Ripped CDs appear here. You can also add a music folder from your device to play its songs",
                    ),
                    T("音楽フォルダを追加", "Add a music folder"),
                ) { picker.launch(null) }
            }
            return@LazyVerticalGrid
        }
        item(key = "search", span = full) {
            Surface(
                onClick = { vm.libPush("search") },
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        T("曲名・アルバム・アーティストで検索", "Search songs, albums, artists"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item(key = "shuffle", span = full) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    T("${albums.size}枚 ・ ${counts["songs"]}曲", "${albums.size} albums · ${counts["songs"]} songs"),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                FilledTonalButton({ vm.shuffleAll() }) {
                    Icon(AppIcons.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(T("全曲シャッフル", "Shuffle all"))
                }
            }
        }
        items(tiles, key = { "tile:$it" }) { id ->
            CategoryTile(tileIcon(id), tileLabel(id), counts[id].orEmpty()) { vm.libPush("cat:$id") }
        }
        if (recentAlbums.isNotEmpty()) {
            item(key = "recentHead", span = full) {
                ListHeading(T("最近追加したアルバム", "Recently added"), Modifier.padding(start = 4.dp)) {
                    TextButton({ vm.libPush("cat:recent") }) { Text(T("すべて", "All")) }
                }
            }
            item(key = "recentRow", span = full) { AlbumStrip(vm, recentAlbums) }
        }
        if (playedAlbums.isNotEmpty()) {
            item(key = "playedHead", span = full) {
                ListHeading(T("最近聴いたアルバム", "Recently played"), Modifier.padding(start = 4.dp)) {
                    TextButton({ vm.libPush("cat:history") }) { Text(T("履歴", "History")) }
                }
            }
            item(key = "playedRow", span = full) { AlbumStrip(vm, playedAlbums) }
        }
    }
}

/** 横に流れるアルバムの列 */
@Composable
private fun AlbumStrip(vm: MainViewModel, list: List<LibAlbum>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
        items(list, key = { it.key }) { a -> AlbumTile(vm, a, Modifier.width(132.dp)) }
    }
}

/** カテゴリーのアイコン(タップで一覧へ) */
@Composable
private fun CategoryTile(icon: ImageVector, label: String, count: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(
            count.ifEmpty { " " }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** トップに並べるカテゴリーの並べ替え・表示の切り替え */
@Composable
private fun TileEditDialog(vm: MainViewModel, s: AppSettings, onDismiss: () -> Unit) {
    val list = remember { mutableStateListOf<Pair<String, Boolean>>().apply { addAll(tileOrder(s.libTiles)) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(T("カテゴリーの並べ替え", "Arrange categories")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(list, key = { _, it -> it.first }) { i, (id, on) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { list[i] = id to it })
                        Icon(tileIcon(id), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(tileLabel(id), modifier = Modifier.weight(1f))
                        IconButton({ if (i > 0) list.add(i - 1, list.removeAt(i)) }, enabled = i > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, T("上へ", "Up"))
                        }
                        IconButton({ if (i < list.lastIndex) list.add(i + 1, list.removeAt(i)) }, enabled = i < list.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, T("下へ", "Down"))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ vm.set(Keys.libTiles, encodeTiles(list)); onDismiss() }) { Text(T("保存", "Save")) } },
        dismissButton = {
            Row {
                TextButton({ vm.set(Keys.libTiles, ""); onDismiss() }) { Text(T("元に戻す", "Reset")) }
                TextButton(onDismiss) { Text(T("キャンセル", "Cancel")) }
            }
        },
    )
}

// ---------------- カテゴリー ----------------

@Composable
private fun CategoryPage(vm: MainViewModel, s: AppSettings, id: String) {
    when (id) {
        "albums" -> AlbumCollectionPage(vm, s, tileLabel(id), vm.albums, sortable = true, key = "albums")
        "recent" -> AlbumCollectionPage(
            vm, s, tileLabel(id), remember(vm.albums) { vm.albums.sortedByDescending { it.added } }, sortable = false, key = "recent",
            subOf = { a -> java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date(a.added)) },
        )
        "hires" -> AlbumCollectionPage(vm, s, tileLabel(id), vm.albums, sortable = true, key = "hires", initialQuality = 0)
        "artists" -> ArtistsPage(vm)
        "songs" -> SongsPage(vm)
        "favorites" -> FavoritesPage(vm)
        "playlists" -> PlaylistsPage(vm)
        "history" -> HistoryPage(vm)
        "top" -> TopPage(vm)
        "genres" -> GenresPage(vm)
        "years" -> YearsPage(vm)
        else -> FoldersPage(vm)
    }
}

/**
 * アルバムの一覧(グリッド / リスト)。並べ替えと、音質での絞り込みができる。
 * initialQuality: 最初に選んでおく音質(-1 = すべて、0 = ハイレゾ、1 = CD音質、2 = 圧縮音源)
 */
@Composable
private fun AlbumCollectionPage(
    vm: MainViewModel,
    s: AppSettings,
    title: String,
    albums: List<LibAlbum>,
    sortable: Boolean,
    key: String,
    subtitle: String? = null,
    initialQuality: Int = -1,
    subOf: ((LibAlbum) -> String)? = null,
) {
    var sort by rememberSaveable(key) { mutableIntStateOf(0) }
    var qf by rememberSaveable(key) { mutableIntStateOf(initialQuality) }
    val listView = s.libraryList
    val shown = remember(albums, sort, qf, sortable) {
        val f = if (qf < 0) albums else albums.filter { Quality.classOf(it).ordinal == qf }
        if (!sortable) f else when (sort) {
            1 -> f.sortedBy { it.title.lowercase() }
            2 -> f.sortedByDescending { it.added }
            3 -> f.sortedWith(compareByDescending<LibAlbum> { it.year }.thenBy { it.artist.lowercase() })
            else -> f.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
        }
    }
    val labelOf: (LibAlbum) -> String = { a ->
        if (!sortable) initialOf(a.title) else when (sort) {
            1 -> initialOf(a.title)
            2 -> java.text.SimpleDateFormat("yyyy/MM", java.util.Locale.getDefault()).format(java.util.Date(a.added))
            3 -> a.year.ifEmpty { "?" }
            else -> initialOf(a.artist)
        }
    }
    // 高速スクロール用(先頭の操作欄 = 0 番を含む)
    val labels = remember(shown, sort) { listOf(shown.firstOrNull()?.let(labelOf).orEmpty()) + shown.map(labelOf) }
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            if (sortable) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(T("アーティスト", "Artist"), T("アルバム名", "Title"), T("最近追加", "Recent"), T("発売年", "Year"))
                        .forEachIndexed { i, l -> FilterChip(sort == i, { sort = i }, label = { Text(l) }) }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(qf < 0, { qf = -1 }, label = { Text(T("すべて", "All")) })
                QualityClass.entries.forEach { q ->
                    FilterChip(qf == q.ordinal, { qf = q.ordinal }, label = { Text(Quality.label(q)) })
                }
            }
            PlayShuffleButtons(
                onPlay = { vm.playTracks(shown.flatMap { it.tracks }, 0) },
                onShuffle = { vm.playTracks(shown.flatMap { it.tracks }, 0, shuffle = true) },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, title, subtitle ?: T("${shown.size}枚", "${shown.size} albums")) {
            IconButton({ vm.set(Keys.libraryList, !listView) }) {
                Icon(if (listView) AppIcons.Grid else AppIcons.ListView, if (listView) T("グリッド表示", "Grid view") else T("リスト表示", "List view"))
            }
        }
        if (shown.isEmpty() && qf < 0) {
            EmptyState(AppIcons.Album, T("アルバムがありません", "No albums"), "")
            return@Column
        }
        Box(Modifier.fillMaxSize()) {
            if (listView) {
                val st = rememberLazyListState()
                LazyColumn(
                    state = st,
                    contentPadding = PaddingValues(start = 12.dp, end = 24.dp, bottom = 24.dp),
                ) {
                    item(key = "controls") { controls() }
                    items(shown, key = { it.key }) { a -> AlbumListRow(vm, a, subOf?.invoke(a)) }
                }
                FastScroller(
                    total = st.layoutInfo.totalItemsCount, first = st.firstVisibleItemIndex,
                    visible = st.layoutInfo.visibleItemsInfo.size, scrolling = st.isScrollInProgress,
                    label = { labels.getOrElse(it) { "" } },
                ) { st.scrollToItem(it) }
            } else {
                val st = rememberLazyGridState()
                LazyVerticalGrid(
                    state = st,
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "controls", span = { GridItemSpan(maxLineSpan) }) { controls() }
                    items(shown, key = { it.key }) { a -> AlbumTile(vm, a, sub = subOf?.invoke(a)) }
                }
                FastScroller(
                    total = st.layoutInfo.totalItemsCount, first = st.firstVisibleItemIndex,
                    visible = st.layoutInfo.visibleItemsInfo.size, scrolling = st.isScrollInProgress,
                    label = { labels.getOrElse(it) { "" } },
                ) { st.scrollToItem(it) }
            }
        }
    }
}

private class ArtistEntry(val name: String, val albums: List<LibAlbum>, val tracks: Int)

@Composable
private fun ArtistsPage(vm: MainViewModel) {
    val artists = remember(vm.albums) {
        vm.albums.groupBy { it.artist }.map { (n, l) -> ArtistEntry(n, l, l.sumOf { it.tracks.size }) }
            .sortedBy { it.name.lowercase() }
    }
    val labels = remember(artists) { artists.map { initialOf(it.name) } }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("artists"), T("${artists.size}人", "${artists.size} artists"))
        Box(Modifier.fillMaxSize()) {
            val st = rememberLazyListState()
            LazyColumn(state = st, contentPadding = PaddingValues(start = 12.dp, end = 24.dp, bottom = 24.dp)) {
                items(artists, key = { it.name }) { e ->
                    val a = e.albums.first()
                    GroupRow(
                        e.name, T("${e.albums.size}枚 ・ ${e.tracks}曲", "${e.albums.size} albums · ${e.tracks} songs"),
                        { vm.libPush("artist:" + e.name) },
                    ) { ArtImage(a.key, a.first.uri, a.first.coverUri, Modifier.size(48.dp), 24.dp) }
                }
            }
            FastScroller(
                total = st.layoutInfo.totalItemsCount, first = st.firstVisibleItemIndex,
                visible = st.layoutInfo.visibleItemsInfo.size, scrolling = st.isScrollInProgress,
                label = { labels.getOrElse(it) { "" } },
            ) { st.scrollToItem(it) }
        }
    }
}

@Composable
private fun ArtistPage(vm: MainViewModel, name: String) {
    val main = remember(vm.albums, name) { vm.albums.filter { it.artist == name }.sortedByDescending { it.year } }
    val guest = remember(vm.albums, name) {
        vm.albums.filter { a -> a.artist != name && a.tracks.any { it.artist == name } }
    }
    val tracks = remember(main, guest, name) { main.flatMap { it.tracks } + guest.flatMap { a -> a.tracks.filter { it.artist == name } } }
    val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, name)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "head", span = full) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InitialAvatar(name, 64)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            T("${main.size + guest.size}枚 ・ ", "${main.size + guest.size} albums · ") + countText(tracks),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PlayShuffleButtons(
                            { vm.playTracks(tracks, 0) }, { vm.playTracks(tracks, 0, shuffle = true) },
                            Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            items(main, key = { it.key }) { a -> AlbumTile(vm, a, sub = a.year.ifEmpty { a.artist }) }
            if (guest.isNotEmpty()) {
                item(key = "guestHead", span = full) { ListHeading(T("参加しているアルバム", "Appears on")) }
                items(guest, key = { "g:" + it.key }) { a -> AlbumTile(vm, a) }
            }
        }
    }
}

@Composable
private fun SongsPage(vm: MainViewModel) {
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val all = remember(vm.albums, sort) {
        val t = vm.albums.flatMap { it.tracks }
        when (sort) {
            1 -> t.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.disc }, { it.track }))
            2 -> t.sortedWith(compareBy({ it.album.lowercase() }, { it.disc }, { it.track }))
            3 -> t.sortedByDescending { it.modified }
            else -> t.sortedBy { it.title.lowercase() }
        }
    }
    val labels = remember(all, sort) {
        val lab: (LibTrack) -> String = { t ->
            when (sort) {
                1 -> initialOf(t.artist)
                2 -> initialOf(t.album)
                3 -> java.text.SimpleDateFormat("yyyy/MM", java.util.Locale.getDefault()).format(java.util.Date(t.modified))
                else -> initialOf(t.title)
            }
        }
        listOf(all.firstOrNull()?.let(lab).orEmpty()) + all.map(lab)
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("songs"), countText(all))
        Box(Modifier.fillMaxSize()) {
            val st = rememberLazyListState()
            LazyColumn(state = st, contentPadding = PaddingValues(start = 8.dp, end = 24.dp, bottom = 24.dp)) {
                item(key = "controls") {
                    Column(Modifier.padding(start = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(T("曲名", "Title"), T("アーティスト", "Artist"), T("アルバム", "Album"), T("最近追加", "Recent"))
                                .forEachIndexed { i, l -> FilterChip(sort == i, { sort = i }, label = { Text(l) }) }
                        }
                        PlayShuffleButtons({ vm.playTracks(all, 0) }, { vm.playTracks(all, 0, shuffle = true) })
                    }
                }
                itemsIndexed(all, key = { _, t -> t.uri }) { i, t ->
                    TrackRow(vm, t, { vm.playTracks(all, i) }, sub = t.artist + " ・ " + t.album)
                }
            }
            FastScroller(
                total = st.layoutInfo.totalItemsCount, first = st.firstVisibleItemIndex,
                visible = st.layoutInfo.visibleItemsInfo.size, scrolling = st.isScrollInProgress,
                label = { labels.getOrElse(it) { "" } },
            ) { st.scrollToItem(it) }
        }
    }
}

/** 曲の一覧(アルバムごとに見出しを付ける)。ジャンルなどで使う */
@Composable
private fun GroupedTracks(vm: MainViewModel, title: String, tracks: List<LibTrack>) {
    val groups = remember(tracks) {
        tracks.groupBy { vm.albumKeyOf(it.uri) ?: it.album }.toList()
    }
    val flat = remember(groups) { groups.flatMap { it.second } }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, title, countText(tracks))
        LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
            item(key = "controls") {
                PlayShuffleButtons(
                    { vm.playTracks(flat, 0) }, { vm.playTracks(flat, 0, shuffle = true) },
                    Modifier.padding(start = 8.dp, bottom = 8.dp),
                )
            }
            groups.forEach { (k, list) ->
                val first = list.first()
                item(key = "h:$k") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { vm.albumKeyOf(first.uri)?.let { vm.libPush("album:$it") } }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtImage(vm.albumKeyOf(first.uri) ?: first.uri, first.uri, first.coverUri, Modifier.size(40.dp), 6.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(first.album, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                artistOf(first), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(list, key = { "t:" + it.uri }) { t ->
                    TrackRow(
                        vm, t, { vm.playTracks(flat, flat.indexOf(t)) },
                        number = if (t.track > 0) "${t.track}" else "", showArt = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun GenresPage(vm: MainViewModel) {
    val genres = remember(vm.albums) {
        vm.albums.flatMap { it.tracks }.groupBy { it.genre }.map { (g, l) -> Triple(g, l.size, l.map { it.album }.distinct().size) }
            .sortedWith(compareBy({ it.first.isEmpty() }, { it.first.lowercase() }))
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("genres"), T("${genres.count { it.first.isNotEmpty() }}種類", "${genres.count { it.first.isNotEmpty() }} genres"))
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            items(genres, key = { it.first }) { (g, n, al) ->
                GroupRow(
                    g.ifEmpty { T("ジャンルの情報なし", "No genre") }, T("${al}枚 ・ ${n}曲", "$al albums · $n songs"),
                    { vm.libPush("genre:$g") },
                ) { RoundIcon(AppIcons.Genre) }
            }
        }
    }
}

@Composable
private fun GenrePage(vm: MainViewModel, g: String) {
    val tracks = remember(vm.albums, g) {
        vm.albums.flatMap { a -> a.tracks.filter { it.genre == g } }
    }
    GroupedTracks(vm, g.ifEmpty { T("ジャンルの情報なし", "No genre") }, tracks)
}

@Composable
private fun YearsPage(vm: MainViewModel) {
    val years = remember(vm.albums) {
        vm.albums.groupBy { it.year }.map { (y, l) -> Triple(y, l.size, l.sumOf { it.tracks.size }) }
            .sortedWith(compareBy<Triple<String, Int, Int>> { it.first.isEmpty() }.thenByDescending { it.first })
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("years"))
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            items(years, key = { it.first }) { (y, n, t) ->
                GroupRow(
                    y.ifEmpty { T("発売年の情報なし", "Unknown year") }, T("${n}枚 ・ ${t}曲", "$n albums · $t songs"),
                    { vm.libPush("year:$y") },
                ) { RoundIcon(Icons.Filled.DateRange) }
            }
        }
    }
}

@Composable
private fun FoldersPage(vm: MainViewModel) {
    val folders = remember(vm.albums) {
        vm.albums.groupBy { folderGroup(it) }.map { (f, l) -> Pair(f, l) }.sortedBy { it.first.lowercase() }
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("folders"))
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            items(folders, key = { it.first }) { (f, l) ->
                GroupRow(
                    f.substringAfterLast('/').ifEmpty { "/" },
                    f + T(" ・ ${l.size}枚", " · ${l.size} albums"),
                    { vm.libPush("folder:$f") },
                ) { RoundIcon(AppIcons.Folder) }
            }
        }
    }
}

@Composable
private fun FavoritesPage(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val map = vm.trackMap
    val songs = remember(vm.store.favTracks, map) { vm.store.favTracks.mapNotNull { map[it] } }
    val albums = remember(vm.store.favAlbums, vm.albums) { vm.store.favAlbums.mapNotNull { k -> vm.albumByKey(k) } }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("favorites"))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, label = { Text(T("曲 ${songs.size}", "Songs ${songs.size}")) })
            FilterChip(tab == 1, { tab = 1 }, label = { Text(T("アルバム ${albums.size}", "Albums ${albums.size}")) })
        }
        if (tab == 0) {
            if (songs.isEmpty()) {
                EmptyState(
                    Icons.Filled.FavoriteBorder, T("お気に入りの曲はまだありません", "No favorite songs yet"),
                    T("再生画面のハートのボタンや、曲の長押しメニューから追加できます", "Add songs with the heart button on the player or from a song's menu"),
                )
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
                item(key = "controls") {
                    PlayShuffleButtons(
                        { vm.playTracks(songs, 0) }, { vm.playTracks(songs, 0, shuffle = true) },
                        Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
                itemsIndexed(songs, key = { _, t -> t.uri }) { i, t -> TrackRow(vm, t, { vm.playTracks(songs, i) }) }
            }
        } else {
            if (albums.isEmpty()) {
                EmptyState(
                    Icons.Filled.FavoriteBorder, T("お気に入りのアルバムはまだありません", "No favorite albums yet"),
                    T("アルバムの画面のハートのボタンから追加できます", "Add albums with the heart button on the album page"),
                )
                return@Column
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(albums, key = { it.key }) { a -> AlbumTile(vm, a) }
            }
        }
    }
}

@Composable
private fun HistoryPage(vm: MainViewModel) {
    val map = vm.trackMap
    val list = remember(vm.store.history, map) { vm.store.history.mapNotNull { h -> map[h.uri]?.let { it to h.time } } }
    val tracks = remember(list) { list.map { it.first } }
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(T("再生履歴を消去しますか?", "Clear play history?")) },
            text = { Text(T("「最近再生」と「よく聴く曲」の記録が消えます", "Recently played and Most played will be reset")) },
            confirmButton = { TextButton({ vm.store.clearHistory(); confirm = false }) { Text(T("消去", "Clear")) } },
            dismissButton = { TextButton({ confirm = false }) { Text(T("キャンセル", "Cancel")) } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("history"), T("${list.size}曲", "${list.size} songs")) {
            if (list.isNotEmpty()) IconButton({ confirm = true }) { Icon(Icons.Filled.Delete, T("履歴を消去", "Clear history")) }
        }
        if (list.isEmpty()) {
            EmptyState(AppIcons.History, T("まだ再生した曲がありません", "Nothing played yet"), "")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
            itemsIndexed(list, key = { _, p -> p.first.uri }) { i, (t, time) ->
                TrackRow(
                    vm, t, { vm.playTracks(tracks, i) },
                    sub = t.artist,
                    trailingText = android.text.format.DateUtils.getRelativeTimeSpanString(
                        time, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
                        android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
                    ).toString(),
                )
            }
        }
    }
}

@Composable
private fun TopPage(vm: MainViewModel) {
    val map = vm.trackMap
    val list = remember(vm.store.counts, map) {
        vm.store.counts.entries.filter { it.value > 0 }.sortedByDescending { it.value }
            .mapNotNull { e -> map[e.key]?.let { it to e.value } }.take(100)
    }
    val tracks = remember(list) { list.map { it.first } }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("top"))
        if (list.isEmpty()) {
            EmptyState(AppIcons.Trending, T("まだ再生した曲がありません", "Nothing played yet"), "")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
            item(key = "controls") {
                PlayShuffleButtons(
                    { vm.playTracks(tracks, 0) }, { vm.playTracks(tracks, 0, shuffle = true) },
                    Modifier.padding(start = 8.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(list, key = { _, p -> p.first.uri }) { i, (t, n) ->
                TrackRow(vm, t, { vm.playTracks(tracks, i) }, number = "${i + 1}", showArt = true, trailingText = T("${n}回", "${n}×"))
            }
        }
    }
}

// ---------------- プレイリスト ----------------

@Composable
private fun PlaylistNameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(T("名前", "Name")) }) },
        confirmButton = { TextButton({ onDone(name); onDismiss() }, enabled = name.isNotBlank()) { Text(T("OK", "OK")) } },
        dismissButton = { TextButton(onDismiss) { Text(T("キャンセル", "Cancel")) } },
    )
}

@Composable
private fun PlaylistsPage(vm: MainViewModel) {
    var creating by remember { mutableStateOf(false) }
    if (creating) PlaylistNameDialog(T("新しいプレイリスト", "New playlist"), "", { creating = false }) { vm.createPlaylist(it, emptyList()) }
    val map = vm.trackMap
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("playlists")) {
            IconButton({ creating = true }) { Icon(Icons.Filled.Add, T("新しいプレイリスト", "New playlist")) }
        }
        if (vm.store.playlists.isEmpty()) {
            EmptyState(
                AppIcons.Queue, T("プレイリストはまだありません", "No playlists yet"),
                T("曲やアルバムの長押しメニュー、または再生リストの「保存」から作れます", "Create one from a song or album menu, or save the queue"),
                T("新しいプレイリスト", "New playlist"),
            ) { creating = true }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            items(vm.store.playlists, key = { it.id }) { p ->
                val first = p.uris.firstNotNullOfOrNull { map[it] }
                val ts = p.uris.mapNotNull { map[it] }
                GroupRow(p.name, countText(ts), { vm.libPush("playlist:" + p.id) }) {
                    if (first != null) ArtImage(vm.albumKeyOf(first.uri) ?: first.uri, first.uri, first.coverUri, Modifier.size(48.dp), 8.dp)
                    else RoundIcon(AppIcons.Queue)
                }
            }
        }
    }
}

@Composable
private fun PlaylistPage(vm: MainViewModel, id: String) {
    val p = vm.store.playlists.firstOrNull { it.id == id }
    if (p == null) {
        LaunchedEffect(Unit) { vm.libPop() }
        return
    }
    val map = vm.trackMap
    // (プレイリストの中の位置, 曲)。ライブラリに見つからない曲は表示しない
    val entries = remember(p, map) { p.uris.mapIndexedNotNull { i, u -> map[u]?.let { i to it } } }
    val tracks = remember(entries) { entries.map { it.second } }
    // 並べ替えても変わらない項目のキー(同じ曲が何回目に出てくるか)
    val keys = remember(entries) {
        val seen = HashMap<String, Int>()
        entries.map { (_, t) -> val n = (seen[t.uri] ?: 0) + 1; seen[t.uri] = n; t.uri + "#" + n }
    }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (renaming) PlaylistNameDialog(T("名前を変更", "Rename"), p.name, { renaming = false }) { vm.store.renamePlaylist(p.id, it) }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(T("「${p.name}」を削除しますか?", "Delete \"${p.name}\"?")) },
            text = { Text(T("曲のファイルは削除されません", "The song files are not deleted")) },
            confirmButton = { TextButton({ deleting = false; vm.store.deletePlaylist(p.id) }) { Text(T("削除", "Delete")) } },
            dismissButton = { TextButton({ deleting = false }) { Text(T("キャンセル", "Cancel")) } },
        )
    }
    val st = rememberLazyListState()
    val reorder = rememberReorder(st, 1) { from, to ->
        // 続けて動かしたときに古い位置を使わないよう、今の中身から位置を求め直す
        val now = vm.store.playlists.firstOrNull { it.id == id } ?: return@rememberReorder
        val m = vm.trackMap
        val pos = now.uris.indices.filter { m[now.uris[it]] != null }
        val a = pos.getOrNull(from)
        val b = pos.getOrNull(to)
        if (a != null && b != null) vm.store.movePlaylistItem(id, a, b)
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, p.name, countText(tracks)) {
            IconButton({ renaming = true }) { Icon(Icons.Filled.Edit, T("名前を変更", "Rename")) }
            IconButton({ deleting = true }) { Icon(Icons.Filled.Delete, T("削除", "Delete")) }
        }
        LazyColumn(state = st, contentPadding = PaddingValues(start = 4.dp, end = 8.dp, bottom = 24.dp)) {
            item(key = "controls") {
                if (tracks.isEmpty()) {
                    Text(
                        T("曲の長押しメニューの「プレイリストに追加」から曲を入れられます", "Add songs with \"Add to playlist\" in a song's menu"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    PlayShuffleButtons(
                        { vm.playTracks(tracks, 0) }, { vm.playTracks(tracks, 0, shuffle = true) },
                        Modifier.padding(start = 12.dp, bottom = 8.dp),
                    )
                }
            }
            itemsIndexed(entries, key = { i, _ -> keys[i] }) { i, (pos, t) ->
                TrackRow(
                    vm, t, { vm.playTracks(tracks, i) },
                    modifier = Modifier.reorderItem(reorder, i),
                    playlist = p.id to pos,
                    handle = { DragHandle(reorder, i) },
                )
            }
        }
    }
}

// ---------------- 検索 ----------------

@Composable
private fun SearchPage(vm: MainViewModel) {
    var q by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val key = norm(q.trim())
    val albums = vm.albums
    val result = remember(key, albums) {
        if (key.isEmpty()) null else {
            val artists = albums.map { it.artist }.distinct().filter { norm(it).contains(key) }.take(10)
            val al = albums.filter { norm(it.title).contains(key) || norm(it.artist).contains(key) }.take(30)
            val songs = albums.flatMap { it.tracks }.filter { norm(it.title).contains(key) || norm(it.artist).contains(key) }.take(200)
            Triple(artists, al, songs)
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.libPop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, T("戻る", "Back")) }
            OutlinedTextField(
                q, { q = it }, Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(T("曲名・アルバム・アーティスト", "Songs, albums, artists")) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Filled.Clear, T("消去", "Clear")) } },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
            )
        }
        val r = result
        if (r == null) {
            Text(
                T("ひらがな・カタカナ、全角・半角の違いは気にせず探せます", "Search ignores width and kana differences"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        val (artists, al, songs) = r
        if (artists.isEmpty() && al.isEmpty() && songs.isEmpty()) {
            EmptyState(Icons.Filled.Search, T("見つかりませんでした", "No results"), "")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
            if (artists.isNotEmpty()) {
                item(key = "ah") { ListHeading(T("アーティスト", "Artists"), Modifier.padding(start = 8.dp)) }
                items(artists, key = { "a:$it" }) { name ->
                    GroupRow(name, "", { vm.libPush("artist:$name") }) { InitialAvatar(name) }
                }
            }
            if (al.isNotEmpty()) {
                item(key = "alh") { ListHeading(T("アルバム", "Albums"), Modifier.padding(start = 8.dp)) }
                items(al, key = { "al:" + it.key }) { a -> AlbumListRow(vm, a) }
            }
            if (songs.isNotEmpty()) {
                item(key = "sh") { ListHeading(T("曲", "Songs"), Modifier.padding(start = 8.dp)) }
                itemsIndexed(songs, key = { _, t -> "s:" + t.uri }) { i, t ->
                    TrackRow(vm, t, { vm.playTracks(songs, i) }, sub = t.artist + " ・ " + t.album)
                }
            }
        }
    }
}

// ---------------- アルバム ----------------

@Composable
private fun AlbumDetail(vm: MainViewModel, a: LibAlbum) {
    var editing by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf(false) }
    if (editing) EditTagsDialog(vm, a) { editing = false }
    if (lookup) LookupSheet(vm, a) { lookup = false }
    val discs = remember(a) { a.tracks.groupBy { it.disc }.toList().sortedBy { it.first } }
    val multiDisc = discs.size > 1
    val fav = vm.store.isFavAlbum(a.key)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "bar") {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.libPop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, T("戻る", "Back")) }
                Spacer(Modifier.weight(1f))
                if (vm.tagSaving) {
                    Text(T("保存中…", "Saving…"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 12.dp))
                } else {
                    IconButton({ lookup = true; vm.libLookup(a) }) { Icon(Icons.Filled.Search, T("曲情報を取得", "Get track info")) }
                    IconButton({ editing = true }) { Icon(Icons.Filled.Edit, T("曲情報を編集", "Edit track info")) }
                    IconButton({ vm.menuAlbum = a }) { Icon(Icons.Filled.MoreVert, T("その他の操作", "More")) }
                }
            }
        }
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ArtImage(a.key, a.first.uri, a.first.coverUri, Modifier.fillMaxWidth(0.66f).aspectRatio(1f), 16.dp)
                Spacer(Modifier.height(20.dp))
                Text(a.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    a.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.libPush("artist:" + a.artist) }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    QualityBadge(Quality.badge(a), Quality.classOf(a))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (if (a.year.isNotEmpty()) a.year + " ・ " else "") + countText(a.tracks),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayShuffleButtons({ vm.playAlbum(a, 0) }, { vm.playAlbum(a, 0, shuffle = true) })
                    Spacer(Modifier.width(4.dp))
                    IconButton({ vm.toggleFavAlbum(a.key) }) {
                        Icon(
                            if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            if (fav) T("お気に入りから外す", "Remove from favorites") else T("お気に入りに追加", "Add to favorites"),
                            tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        discs.forEach { (disc, list) ->
            if (multiDisc) {
                item(key = "disc:$disc") {
                    Text(
                        "Disc $disc", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            items(list, key = { it.uri }) { t ->
                val i = a.tracks.indexOf(t)
                TrackRow(
                    vm, t, { vm.playAlbum(a, i) }, Modifier.padding(horizontal = 8.dp),
                    number = if (t.track > 0) "${t.track}" else "${i + 1}", showArt = false,
                    sub = if (t.artist != a.artist) t.artist else "",
                )
            }
        }
    }
}

@Composable
private fun EditTagsDialog(vm: MainViewModel, a: LibAlbum, onDismiss: () -> Unit) {
    var album by remember { mutableStateOf(a.title) }
    var albumArtist by remember { mutableStateOf(a.artist) }
    var year by remember { mutableStateOf(a.first.year) }
    val titles = remember { mutableStateListOf<String>().apply { addAll(a.tracks.map { it.title }) } }
    val artists = remember { mutableStateListOf<String>().apply { addAll(a.tracks.map { it.artist }) } }
    var cover by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) cover = it }
    val unsupported = a.tracks.count { !TagEditor.supported(it.uri) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                vm.saveAlbumTags(a, album, albumArtist, year, titles.toList(), artists.toList(), cover)
                onDismiss()
            }) { Text(T("保存", "Save")) }
        },
        dismissButton = { TextButton(onDismiss) { Text(T("キャンセル", "Cancel")) } },
        title = { Text(T("曲情報を編集", "Edit track info")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ArtImage(a.key, a.first.uri, a.first.coverUri, Modifier.size(64.dp), 8.dp)
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton({
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text(if (cover == null) T("ジャケットを変更", "Change cover") else T("新しい画像を選択済み", "New image selected")) }
                    }
                }
                item { OutlinedTextField(album, { album = it }, Modifier.fillMaxWidth(), label = { Text(T("アルバム名", "Album")) }, singleLine = true) }
                item {
                    OutlinedTextField(albumArtist, { albumArtist = it }, Modifier.fillMaxWidth(), label = { Text(T("アルバムアーティスト", "Album artist")) }, singleLine = true)
                }
                item { OutlinedTextField(year, { year = it }, Modifier.fillMaxWidth(), label = { Text(T("発売年", "Year")) }, singleLine = true) }
                if (unsupported > 0) {
                    item {
                        Text(
                            T("WAVなど${unsupported}曲は、曲情報を保存できない形式のため変更されません", "${unsupported} tracks (such as WAV) use a format that cannot store tags and will not be changed"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                items(titles.size) { i ->
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val n = a.tracks[i].track
                        OutlinedTextField(
                            titles[i], { titles[i] = it }, Modifier.fillMaxWidth(),
                            label = { Text(if (n > 0) T("${n}曲目 曲名", "Track ${n} title") else T("曲名", "Title")) }, singleLine = true,
                        )
                        OutlinedTextField(
                            artists[i], { artists[i] = it }, Modifier.fillMaxWidth(),
                            label = { Text(T("アーティスト", "Artist")) }, singleLine = true,
                        )
                    }
                }
            }
        },
    )
}

/** 取り込み済みアルバムの曲情報を、インターネットから探して書き込む */
@Composable
private fun LookupSheet(vm: MainViewModel, a: LibAlbum, onDismiss: () -> Unit) {
    var album by remember { mutableStateOf(a.title.takeIf { it != UNKNOWN_ALBUM }.orEmpty()) }
    var artist by remember { mutableStateOf(a.artist.takeIf { it != UNKNOWN_ARTIST }.orEmpty()) }
    var pick by remember { mutableStateOf<ReleaseCandidate?>(null) }
    val n = a.tracks.size
    val search = { if (album.isNotBlank() || artist.isNotBlank()) vm.libSearch(a, album, artist) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(T("曲情報を取得", "Get track info"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onDismiss) { Text(T("閉じる", "Close")) }
                }
                Text(
                    T(
                        "曲の長さからCDを推定して探します。選んだ情報は、このアルバムの${n}曲のファイルに書き込まれます。",
                        "Matches are estimated from song lengths. The chosen info is written to the $n files of this album.",
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    album, { album = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(T("アルバム名", "Album")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
            }
            item {
                OutlinedTextField(
                    artist, { artist = it }, Modifier.fillMaxWidth(),
                    label = { Text(T("アーティスト", "Artist")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(search, enabled = !vm.libSearching && (album.isNotBlank() || artist.isNotBlank())) {
                        Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(T("名前で検索", "Search by name"))
                    }
                    TextButton({ vm.libLookup(a) }, enabled = !vm.libSearching) { Text(T("曲の長さで探す", "Match by length")) }
                }
                if (vm.libSearching || vm.libApplying) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                if (vm.libMessage.isNotEmpty()) {
                    Text(vm.libMessage, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            items(vm.libCandidates) { c ->
                CandidateRow(c, n, !vm.libApplying && !vm.tagSaving, false) { pick = c }
            }
        }
    }
    val c = pick
    if (c != null) {
        AlertDialog(
            onDismissRequest = { pick = null },
            title = { Text(T("この曲情報で上書きしますか?", "Overwrite with this info?")) },
            text = {
                Text(
                    "${c.meta.title}\n${c.meta.artist}\n\n" + T(
                        "${n}曲のファイルの曲名・アーティスト・アルバム名・年を書き換えます。ジャケット画像が見つかれば差し替えます。WAVファイルは対象外です。",
                        "Titles, artists, album and year of the $n files will be rewritten. Cover art is replaced if found. WAV files are skipped.",
                    ),
                )
            },
            confirmButton = {
                TextButton({
                    pick = null
                    vm.libApply(a, c) { onDismiss() }
                }) { Text(T("上書きする", "Overwrite")) }
            },
            dismissButton = { TextButton({ pick = null }) { Text(T("キャンセル", "Cancel")) } },
        )
    }
}

/** アルバムが入っているフォルダの名前(例: Music/Artist/Album の Album) */
private fun folderPath(a: LibAlbum): String = Uri.decode(a.first.folderId).substringAfter(':', "")

private fun folderName(a: LibAlbum): String = folderPath(a).substringAfterLast('/')

/** フォルダ別表示の見出し(アルバムのフォルダの1つ上。例: Music/Artist) */
private fun folderGroup(a: LibAlbum): String {
    val p = folderPath(a)
    return if (p.contains('/')) p.substringBeforeLast('/') else p.ifEmpty { "/" }
}
