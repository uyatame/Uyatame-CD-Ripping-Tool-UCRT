@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.uyatame.ucrt

import androidx.compose.runtime.collectAsState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.graphics.luminance
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.uyatame.ucrt.player.ExternalMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
    // 行き先ごとに、スクロールの位置などを覚えておく(曲を再生してから戻ったときなどに、元の位置に戻れるように)
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
        // 選んでいる間は、一覧の終わりが下の帯に隠れないよう、その分だけ上げる
        // (リキッドグラスでは、帯はミニプレーヤーと同じ下のガラスの列に出す(Ui.kt)。一覧の終わりはその分だけ自動で上がる)
        val glass = LocalGlass.current
        Box(Modifier.fillMaxSize().padding(bottom = if (vm.selecting && !glass) 72.dp else 0.dp)) {
            holder.SaveableStateProvider(route ?: "home") { LibraryRoute(vm, s, route) }
        }
        if (vm.selecting && !glass) SelectionBar(vm, Modifier.align(Alignment.BottomCenter))
    }
    // 選んでいる間の戻るボタンは、選ぶのをやめる(ページの戻るより先に受け取る)
    BackHandler(enabled = vm.selecting) { vm.endSelection() }
}

@Composable
private fun LibraryRoute(vm: MainViewModel, s: AppSettings, route: String?) {
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
                vm, s, y.ifEmpty { S(R.string.unknown_year) },
                // 一覧を作り直すのは、ライブラリが変わったときだけ(画面を描き直すたびに全アルバムを調べない)
                remember(vm.albums, y) { vm.albums.filter { it.year == y } }, sortable = false, key = route,
            )
        }
        route.startsWith("folder:") -> {
            val f = route.removePrefix("folder:")
            AlbumCollectionPage(
                vm, s, f.substringAfterLast('/').ifEmpty { "/" },
                // そのフォルダの下(子フォルダも含む)のアルバム
                remember(vm.albums, f) {
                    vm.albums.filter { a -> a.tracks.any { trackFolder(it).let { p -> p == f || p.startsWith("$f/") } } }.sortedBy { folderPath(it).lowercase() }
                },
                sortable = false, key = route, subtitle = f,
            )
        }
        route.startsWith("playlist:") -> PlaylistPage(vm, route.removePrefix("playlist:"))
        route.startsWith("dir:") -> FolderBrowsePage(vm, route.removePrefix("dir:"))
        else -> LibraryHome(vm, s)
    }
}

// ---------------- 共通の部品 ----------------

/** 戻るボタン付きの見出し */
@Composable
private fun PageTop(vm: MainViewModel, title: String, sub: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ vm.libPop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S(R.string.back)) }
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
            AppIcons.Album, S(R.string.not_found),
            S(R.string.library_may_still_be_loading),
        )
    }
}

/** 曲の合計時間と曲数(例: 12曲 ・ 48:10) */
private fun countText(tracks: List<LibTrack>): String {
    val d = fmtMs(tracks.sumOf { it.durationMs })
    return S(R.string.songs_6, tracks.size, d)
}

/** 検索用に文字をそろえる(全角/半角、大文字/小文字、カタカナ/ひらがな) */
private fun norm(s: String): String {
    val n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).lowercase()
    val sb = StringBuilder(n.length)
    for (c in n) sb.append(if (c in 'ァ'..'ヶ') (c - 0x60) else c)
    return sb.toString()
}

// ---------------- 曲を選ぶ(まとめてプレイリストへ) ----------------

/** 選ぶモードを始めるボタン(ページの上の帯に置く) */
@Composable
private fun SelectButton(vm: MainViewModel) {
    IconButton({ vm.startSelection(null) }) { Icon(Icons.Filled.CheckCircle, S(R.string.select_songs)) }
}

/** このページの曲を「すべて選択」の対象にする(ページを離れたら外す) */
@Composable
private fun SelectionPool(vm: MainViewModel, all: List<LibTrack>) {
    DisposableEffect(all) {
        vm.selectionPool = all
        onDispose { if (vm.selectionPool === all) vm.selectionPool = null }
    }
}

/**
 * 選ぶモードの帯(ライブラリの下に重ねて出す)。やめる・選んだ数・すべて選択・プレイリストに追加。
 * ページを移っても選んだ曲は残るので、いくつかのアルバムから選んでまとめて入れられる
 */
@Composable
internal fun SelectionBar(vm: MainViewModel, modifier: Modifier) {
    val glass = LocalGlass.current
    val box: @Composable (@Composable () -> Unit) -> Unit = { inner ->
        // リキッドグラスでは浮かぶガラスにする
        if (glass) GlassSurface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), RoundedCornerShape(26.dp)) {
            Box(Modifier.padding(horizontal = 6.dp)) { inner() }
        } else Surface(
            modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shadowElevation = 6.dp,
        ) { inner() }
    }
    box {
        Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.endSelection() }) { Icon(Icons.Filled.Close, S(R.string.cancel)) }
            Text(
                S(R.string.songs_selected, vm.selectedSongs.size), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            vm.selectionPool?.let { pool ->
                val allPicked = pool.isNotEmpty() && pool.all { vm.isSelected(it.uri) }
                TextButton({ if (allPicked) vm.unselectAll(pool) else vm.selectAll(pool) }) {
                    Text(if (allPicked) S(R.string.deselect_all) else S(R.string.select_all))
                }
            }
            FilledTonalButton({ vm.addSelectedToPlaylist() }, enabled = vm.selectedSongs.isNotEmpty()) {
                Icon(AppIcons.PlaylistAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(S(R.string.add_to_playlist))
            }
        }
    }
}

// ---------------- トップ ----------------

private val TILE_IDS = listOf(
    "albums", "artists", "songs", "favorites", "check", "playlists", "recent", "history", "top", "hires", "genres", "years", "folders",
)

private fun tileIcon(id: String): ImageVector = when (id) {
    "albums" -> AppIcons.Album
    "artists" -> Icons.Filled.Person
    "songs" -> AppIcons.MusicNote
    "favorites" -> Icons.Filled.Favorite
    "check" -> AppIcons.Headphones
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
    "albums" -> S(R.string.albums)
    "artists" -> S(R.string.artists)
    "songs" -> S(R.string.songs)
    "favorites" -> S(R.string.favorites)
    "check" -> S(R.string.sound_check)
    "playlists" -> S(R.string.playlists)
    "recent" -> S(R.string.recently_added)
    "history" -> S(R.string.recently_played)
    "top" -> S(R.string.most_played)
    "hires" -> S(R.string.by_quality)
    "genres" -> S(R.string.genres)
    "years" -> S(R.string.years)
    else -> S(R.string.folders)
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

/**
 * トップにジャケットの列(最近追加・最近再生)を出すか。カテゴリーと同じ設定の文字列に「-strip:recent」のように書く
 * (書いていなければ出す)
 */
private fun stripOn(setting: String, id: String): Boolean =
    setting.split(',').none { it.trim() == "-strip:$id" }

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
    // ライブラリから数えるもの(全曲を調べるので重い)は、ライブラリが変わったときだけ数える。
    // 再生履歴は曲が変わるたびに増えるので、そちらは別に数える
    val libCounts = remember(albums) {
        val tracks = albums.sumOf { it.tracks.size }
        mapOf(
            "albums" to "${albums.size}",
            "artists" to "${albums.map { it.artist }.distinct().size}",
            "songs" to "$tracks",
            "hires" to S(R.string.hi_res_2, albums.count { Quality.classOf(it) == QualityClass.HiRes }),
            "genres" to "${albums.flatMap { a -> a.tracks.map { it.genre } }.filter { it.isNotEmpty() }.distinct().size}",
            "years" to "${albums.map { it.year }.filter { it.isNotEmpty() }.distinct().size}",
            "folders" to "${albums.flatMap { a -> a.tracks.map { trackFolder(it) } }.distinct().size}",
        )
    }
    val counts = remember(libCounts, store.favTracks, store.favAlbums, store.checkTracks, store.playlists, store.history) {
        libCounts + mapOf(
            "favorites" to "${store.favTracks.size + store.favAlbums.size}",
            "check" to "${store.checkTracks.size}",
            "playlists" to "${store.playlists.size}",
            "history" to "${store.history.size}",
        )
    }
    val tiles = remember(s.libTiles) { tileOrder(s.libTiles).filter { it.second }.map { it.first } }
    // ジャケットの列は、そのカテゴリーを表示していて、列もオフにしていないときだけ出す
    val showRecent = "recent" in tiles && stripOn(s.libTiles, "recent")
    val showPlayed = "history" in tiles && stripOn(s.libTiles, "history")
    val recentAlbums = remember(albums, showRecent) { if (showRecent) albums.sortedByDescending { it.added }.take(15) else emptyList() }
    // 曲が変わるたびに作り直すので、アルバムはキーの表で引き、15 枚そろったらやめる
    val albumByKey = remember(albums) { albums.associateBy { it.key } }
    val playedAlbums = remember(albumByKey, store.history, showPlayed) {
        if (!showPlayed) return@remember emptyList()
        store.history.asSequence().mapNotNull { vm.albumKeyOf(it.uri) }.distinct().mapNotNull { k -> albumByKey[k] }.take(15).toList()
    }
    val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    val homeState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        state = homeState,
        columns = GridCells.Adaptive(96.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + barInset()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "head", span = full) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(S(R.string.library), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton({ editTiles = true }) { Icon(Icons.Filled.Edit, S(R.string.reorder_categories)) }
                // 手動の更新: すべての曲を読み直す(普段は新しく入った曲だけを自動で読み込む)
                IconButton({ vm.refreshLibrary(fresh = true) }, enabled = !vm.libScanning && hasFolders) {
                    Icon(Icons.Filled.Refresh, S(R.string.rescan_library))
                }
            }
        }
        // 新しい曲が無いときの自動確認では、何も表示しない
        if (vm.libScanning && (vm.libFull || vm.libNew > 0 || albums.isEmpty())) {
            item(key = "scan", span = full) {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        if (vm.libFull) S(R.string.rescanning_library, vm.libProgress)
                        else S(R.string.scanning_for_new_songs, vm.libProgress),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
        if (albums.isEmpty() && !vm.libScanning) {
            item(key = "empty", span = full) {
                EmptyState(
                    AppIcons.Library, S(R.string.no_songs_yet),
                    S(R.string.ripped_cds_appear_here_you),
                    S(R.string.add_music_folder),
                ) { picker.launch(null) }
            }
            return@LazyVerticalGrid
        }
        item(key = "search", span = full) {
            Surface(
                onClick = { vm.libPush("search") },
                shape = RoundedCornerShape(28.dp),
                color = if (LocalGlass.current) MaterialTheme.colorScheme.surface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (LocalGlass.current) androidx.compose.foundation.BorderStroke(1.dp, glassRim(MaterialTheme.colorScheme.surface.luminance() < 0.5f)) else null,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        S(R.string.search_songs_albums_and_artists),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item(key = "shuffle", span = full) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    S(R.string.albums_songs, albums.size, counts["songs"]),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                FilledTonalButton({ vm.shuffleAll() }) {
                    Icon(AppIcons.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(S(R.string.shuffle_all))
                }
            }
        }
        items(tiles, key = { "tile:$it" }) { id ->
            CategoryTile(tileIcon(id), tileLabel(id), counts[id].orEmpty()) { vm.libPush("cat:$id") }
        }
        if (recentAlbums.isNotEmpty()) {
            item(key = "recentHead", span = full) {
                ListHeading(S(R.string.recently_added_2), Modifier.padding(start = 4.dp)) {
                    TextButton({ vm.libPush("cat:recent") }) { Text(S(R.string.all)) }
                }
            }
            item(key = "recentRow", span = full) { AlbumStrip(vm, recentAlbums) }
        }
        if (playedAlbums.isNotEmpty()) {
            item(key = "playedHead", span = full) {
                ListHeading(S(R.string.recently_played_2), Modifier.padding(start = 4.dp)) {
                    TextButton({ vm.libPush("cat:history") }) { Text(S(R.string.history)) }
                }
            }
            item(key = "playedRow", span = full) { AlbumStrip(vm, playedAlbums) }
        }
    }
    ScrollTopButton(remember(homeState) { derivedStateOf { homeState.firstVisibleItemIndex > 6 } }.value) { homeState.scrollToItem(0) }
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
    var recentStrip by remember { mutableStateOf(stripOn(s.libTiles, "recent")) }
    var playedStrip by remember { mutableStateOf(stripOn(s.libTiles, "history")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S(R.string.reorder_categories_2)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(list, key = { _, it -> it.first }) { i, (id, on) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { list[i] = id to it })
                        Icon(tileIcon(id), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(tileLabel(id), modifier = Modifier.weight(1f))
                        IconButton({ if (i > 0) list.add(i - 1, list.removeAt(i)) }, enabled = i > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, S(R.string.up))
                        }
                        IconButton({ if (i < list.lastIndex) list.add(i + 1, list.removeAt(i)) }, enabled = i < list.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, S(R.string.down))
                        }
                    }
                }
                // ---- トップのジャケットの列(カテゴリーをオフにすると、列も出なくなる) ----
                item(key = "strips") {
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(S(R.string.show_as_artwork_rows), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        val recentTile = list.firstOrNull { it.first == "recent" }?.second == true
                        val playedTile = list.firstOrNull { it.first == "history" }?.second == true
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(recentStrip && recentTile, { recentStrip = it }, enabled = recentTile)
                            Text(S(R.string.recently_added_2))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(playedStrip && playedTile, { playedStrip = it }, enabled = playedTile)
                            Text(S(R.string.recently_played_2))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val strips = (if (recentStrip) "" else ",-strip:recent") + (if (playedStrip) "" else ",-strip:history")
                vm.set(Keys.libTiles, encodeTiles(list) + strips)
                onDismiss()
            }) { Text(S(R.string.save)) }
        },
        dismissButton = {
            Row {
                TextButton({ vm.set(Keys.libTiles, ""); onDismiss() }) { Text(S(R.string.reset_2)) }
                TextButton(onDismiss) { Text(S(R.string.cancel)) }
            }
        },
    )
}

// ---------------- カテゴリー ----------------

@Composable
private fun CategoryPage(vm: MainViewModel, s: AppSettings, id: String) {
    when (id) {
        "albums" -> AlbumCollectionPage(vm, s, tileLabel(id), vm.albums, sortable = true, key = "albums")
        "recent" -> {
            val ymdFmt = remember { java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()) }
            AlbumCollectionPage(
                vm, s, tileLabel(id), remember(vm.albums) { vm.albums.sortedByDescending { it.added } }, sortable = false, key = "recent",
                subOf = { a -> ymdFmt.format(java.util.Date(a.added)) },
            )
        }
        "hires" -> AlbumCollectionPage(vm, s, tileLabel(id), vm.albums, sortable = true, key = "hires", initialQuality = 0)
        "artists" -> ArtistsPage(vm)
        "songs" -> SongsPage(vm)
        "favorites" -> FavoritesPage(vm)
        "check" -> SoundCheckPage(vm)
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
    // 音質の区分の中の細かい分類(ハイレゾなら "DSD256"・"96kHz/24bit"、圧縮音源なら "AAC" など。空 = すべて)
    var qd by rememberSaveable(key) { mutableStateOf("") }
    val listView = s.libraryList
    val inClass = remember(albums, qf) { if (qf < 0) albums else albums.filter { Quality.classOf(it).ordinal == qf } }
    val details = remember(inClass, qf) {
        if (qf < 0) emptyList() else {
            val counts = inClass.groupingBy { Quality.detail(it) }.eachCount()
            val order = inClass.groupBy { Quality.detail(it) }.mapValues { (_, l) -> l.minOf { Quality.detailOrder(it) } }
            counts.keys.sortedWith(
                if (qf == QualityClass.HiRes.ordinal) compareBy({ order[it] }, { it })
                else compareBy({ -counts.getValue(it) }, { it }),
            ).map { it to counts.getValue(it) }
        }
    }
    val shown = remember(inClass, sort, qd, sortable) {
        val f = if (qd.isEmpty()) inClass else inClass.filter { Quality.detail(it) == qd }
        if (!sortable) f else when (sort) {
            1 -> f.sortedBy { it.title.lowercase() }
            2 -> f.sortedByDescending { it.added }
            3 -> f.sortedWith(compareByDescending<LibAlbum> { it.year }.thenBy { it.artist.lowercase() })
            else -> f.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
        }
    }
    // 日付の書式は 1 つを使い回す(アルバムごとに作ると、大きなライブラリでは重い)
    val ymFmt = remember { java.text.SimpleDateFormat("yyyy/MM", java.util.Locale.getDefault()) }
    val labelOf: (LibAlbum) -> String = { a ->
        if (!sortable) initialOf(a.title) else when (sort) {
            1 -> initialOf(a.title)
            2 -> ymFmt.format(java.util.Date(a.added))
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
                    listOf(S(R.string.artist), S(R.string.title), S(R.string.date_added), S(R.string.year))
                        .forEachIndexed { i, l -> FilterChip(sort == i, { sort = i }, label = { Text(l) }) }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(qf < 0, { qf = -1; qd = "" }, label = { Text(S(R.string.all)) })
                QualityClass.entries.forEach { q ->
                    FilterChip(qf == q.ordinal, { qf = q.ordinal; qd = "" }, label = { Text(Quality.label(q)) })
                }
            }
            // 区分を選んでいるときは、その中をさらに絞り込める(種類が 2 つ以上あるときだけ)
            if (details.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(qd.isEmpty(), { qd = "" }, label = { Text(S(R.string.all)) })
                    details.forEach { (d, n) ->
                        FilterChip(qd == d, { qd = if (qd == d) "" else d }, label = { Text("$d ($n)") })
                    }
                }
            }
            // 年・フォルダのページは、最後まで再生したら次の年・フォルダへ進めるように、どのリストかを覚えておく
            val src = when {
                key.startsWith("year:") -> MainViewModel.PlaySource(MainViewModel.PlaySource.YEAR, key.removePrefix("year:"))
                key.startsWith("folder:") -> MainViewModel.PlaySource(MainViewModel.PlaySource.FOLDER, key.removePrefix("folder:"))
                else -> null
            }
            PlayShuffleButtons(
                onPlay = { vm.playTracks(shown.flatMap { it.tracks }, 0, source = src) },
                onShuffle = { vm.playTracks(shown.flatMap { it.tracks }, 0, shuffle = true, source = src) },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, title, subtitle ?: S(R.string.albums_3, shown.size)) {
            IconButton({ vm.set(Keys.libraryList, !listView) }) {
                Icon(if (listView) AppIcons.Grid else AppIcons.ListView, if (listView) S(R.string.grid_view) else S(R.string.list_view))
            }
        }
        if (shown.isEmpty() && qf < 0) {
            EmptyState(AppIcons.Album, S(R.string.no_albums), "")
            return@Column
        }
        Box(Modifier.fillMaxSize()) {
            if (listView) {
                val st = rememberLazyListState()
                LazyColumn(
                    state = st,
                    contentPadding = PaddingValues(start = 12.dp, end = 24.dp, bottom = 24.dp + barInset()),
                ) {
                    item(key = "controls") { controls() }
                    items(shown, key = { it.key }) { a -> AlbumListRow(vm, a, subOf?.invoke(a)) }
                }
                FastScroller(st) { labels.getOrElse(it) { "" } }
            } else {
                val st = rememberLazyGridState()
                LazyVerticalGrid(
                    state = st,
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = 24.dp + barInset()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "controls", span = { GridItemSpan(maxLineSpan) }) { controls() }
                    items(shown, key = { it.key }) { a -> AlbumTile(vm, a, sub = subOf?.invoke(a)) }
                }
                FastScroller(st) { labels.getOrElse(it) { "" } }
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
        PageTop(vm, tileLabel("artists"), S(R.string.artists_2, artists.size))
        Box(Modifier.fillMaxSize()) {
            val st = rememberLazyListState()
            LazyColumn(state = st, contentPadding = PaddingValues(start = 12.dp, end = 24.dp, bottom = 24.dp + barInset())) {
                items(artists, key = { it.name }) { e ->
                    val a = e.albums.first()
                    GroupRow(
                        e.name, S(R.string.albums_songs, e.albums.size, e.tracks),
                        { vm.libPush("artist:" + e.name) },
                    ) { ArtImage(a.key, a.first.uri, a.first.coverUri, Modifier.size(48.dp), 24.dp, 48.dp) }
                }
            }
            FastScroller(st) { labels.getOrElse(it) { "" } }
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + barInset()),
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
                            S(R.string.albums_4, main.size + guest.size) + countText(tracks),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val src = MainViewModel.PlaySource(MainViewModel.PlaySource.ARTIST, name)
                        PlayShuffleButtons(
                            { vm.playTracks(tracks, 0, source = src) }, { vm.playTracks(tracks, 0, shuffle = true, source = src) },
                            Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            items(main, key = { it.key }) { a -> AlbumTile(vm, a, sub = a.year.ifEmpty { a.artist }) }
            if (guest.isNotEmpty()) {
                item(key = "guestHead", span = full) { ListHeading(S(R.string.appears_on)) }
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
        // 日付の書式は 1 つを使い回す(曲ごとに作ると、曲の多いライブラリでは重い)
        val ymFmt = java.text.SimpleDateFormat("yyyy/MM", java.util.Locale.getDefault())
        val lab: (LibTrack) -> String = { t ->
            when (sort) {
                1 -> initialOf(t.artist)
                2 -> initialOf(t.album)
                3 -> ymFmt.format(java.util.Date(t.modified))
                else -> initialOf(t.title)
            }
        }
        listOf(all.firstOrNull()?.let(lab).orEmpty()) + all.map(lab)
    }
    SelectionPool(vm, all)
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("songs"), remember(all) { countText(all) }) { SelectButton(vm) }
        Box(Modifier.fillMaxSize()) {
            val st = rememberLazyListState()
            LazyColumn(state = st, contentPadding = PaddingValues(start = 8.dp, end = 24.dp, bottom = 24.dp + barInset())) {
                item(key = "controls") {
                    Column(Modifier.padding(start = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(S(R.string.title_2), S(R.string.artist), S(R.string.album_2), S(R.string.date_added))
                                .forEachIndexed { i, l -> FilterChip(sort == i, { sort = i }, label = { Text(l) }) }
                        }
                        PlayShuffleButtons({ vm.playTracks(all, 0) }, { vm.playTracks(all, 0, shuffle = true) })
                    }
                }
                itemsIndexed(all, key = { _, t -> t.uri }) { i, t ->
                    TrackRow(
                        vm, t, { vm.playTracks(all, i) },
                        sub = t.artist + S(R.string.separator) + t.album,
                    )
                }
            }
            FastScroller(st) { labels.getOrElse(it) { "" } }
        }
    }
}

/** 曲の一覧(アルバムごとに見出しを付ける)。ジャンルなどで使う */
@Composable
private fun GroupedTracks(vm: MainViewModel, title: String, tracks: List<LibTrack>, source: MainViewModel.PlaySource? = null) {
    val groups = remember(tracks) {
        tracks.groupBy { vm.albumKeyOf(it.uri) ?: it.album }.toList()
    }
    val flat = remember(groups) { groups.flatMap { it.second } }
    SelectionPool(vm, flat)
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, title, countText(tracks)) { SelectButton(vm) }
        LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp + barInset())) {
            item(key = "controls") {
                PlayShuffleButtons(
                    { vm.playTracks(flat, 0, source = source) }, { vm.playTracks(flat, 0, shuffle = true, source = source) },
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
                        ArtImage(vm.albumKeyOf(first.uri) ?: first.uri, first.uri, first.coverUri, Modifier.size(40.dp), 6.dp, 40.dp)
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
                        vm, t, { vm.playTracks(flat, flat.indexOf(t), source = source) },
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
        PageTop(vm, tileLabel("genres"), S(R.string.genres_2, genres.count { it.first.isNotEmpty() }))
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp + barInset())) {
            items(genres, key = { it.first }) { (g, n, al) ->
                GroupRow(
                    g.ifEmpty { S(R.string.unknown_genre) }, S(R.string.albums_songs, al, n),
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
    GroupedTracks(vm, g.ifEmpty { S(R.string.unknown_genre) }, tracks, MainViewModel.PlaySource(MainViewModel.PlaySource.GENRE, g))
}

@Composable
private fun YearsPage(vm: MainViewModel) {
    val years = remember(vm.albums) {
        vm.albums.groupBy { it.year }.map { (y, l) -> Triple(y, l.size, l.sumOf { it.tracks.size }) }
            .sortedWith(compareBy<Triple<String, Int, Int>> { it.first.isEmpty() }.thenByDescending { it.first })
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("years"))
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp + barInset())) {
            items(years, key = { it.first }) { (y, n, t) ->
                GroupRow(
                    y.ifEmpty { S(R.string.unknown_year) }, S(R.string.albums_songs, n, t),
                    { vm.libPush("year:$y") },
                ) { RoundIcon(Icons.Filled.DateRange) }
            }
        }
    }
}

/** フォルダの木の 1 つ(path はルートからの道筋。例: "Music/Artist") */
private class FolderNode(val path: String, val name: String) {
    val children = java.util.TreeMap<String, FolderNode>(String.CASE_INSENSITIVE_ORDER)
    /** このフォルダにじかに入っているアルバム */
    val albums = ArrayList<LibAlbum>()
    /** この下(子フォルダも含む)のアルバム(数を数えるため)と曲の数 */
    val albumKeys = HashSet<String>()
    val albumCount: Int get() = albumKeys.size
    var trackCount = 0
}

/** 曲の入っているフォルダ(例: Music/Artist/Album/disc1) */
private fun trackFolder(t: LibTrack): String = Uri.decode(t.folderId).substringAfter(':', "")

/** フォルダ(子フォルダも含む)の曲を、フォルダ・ディスク・曲順に並べる(フォルダ単位の再生) */
private fun folderTracks(vm: MainViewModel, path: String): List<LibTrack> =
    vm.albums.asSequence().flatMap { it.tracks }
        .filter { val p = trackFolder(it); p == path || path.isEmpty() || p.startsWith("$path/") }
        .sortedWith(compareBy({ trackFolder(it).lowercase() }, { it.disc }, { it.track }, { Uri.decode(it.uri).lowercase() }))
        .toList()

/**
 * 曲の入っているフォルダから、実際のフォルダの階層どおりの木を作る。
 * 1 つのアルバムが disc1 / disc2 のように複数のフォルダに分かれていれば、それぞれのフォルダに出す
 */
private fun buildFolderTree(albums: List<LibAlbum>): FolderNode {
    val root = FolderNode("", "")
    for (a in albums) {
        for ((folder, tracks) in a.tracks.groupBy { trackFolder(it) }) {
            val parts = folder.split('/').filter { it.isNotEmpty() }
            var n = root
            n.albumKeys.add(a.key); n.trackCount += tracks.size
            for (p in parts) {
                n = n.children.getOrPut(p) { FolderNode(if (n.path.isEmpty()) p else n.path + "/" + p, p) }
                n.albumKeys.add(a.key); n.trackCount += tracks.size
            }
            if (n.albums.none { it.key == a.key }) n.albums.add(a)
        }
    }
    // 実際のフォルダの階層どおりに出す(まとめない)。アルバムはフォルダの名前の順
    fun sortAlbums(n: FolderNode) {
        n.albums.sortBy { folderName(it).lowercase() }
        n.children.values.forEach { sortAlbums(it) }
    }
    sortAlbums(root)
    return root
}

/** 作ったフォルダの木(同じライブラリなら使い回す。フォルダを 1 つ潜るたびに全曲から作り直さないように。画面の処理からだけ使う) */
private var folderTreeCache: Pair<List<LibAlbum>, FolderNode>? = null

private fun folderTreeOf(albums: List<LibAlbum>): FolderNode {
    folderTreeCache?.let { (a, t) -> if (a === albums) return t }
    return buildFolderTree(albums).also { folderTreeCache = albums to it }
}

/**
 * フォルダの中(エクスプローラーのように潜っていく)。上に今いる場所(タップでその階層へ)、
 * その下に中のフォルダとアルバムを並べる。フォルダをタップすると 1 つ下へ、戻るで 1 つ上へ
 */
@Composable
private fun FolderBrowsePage(vm: MainViewModel, path: String) {
    val node = remember(vm.albums, path) {
        var n: FolderNode? = folderTreeOf(vm.albums)
        for (p in path.split('/').filter { it.isNotEmpty() }) n = n?.children?.get(p)
        n
    }
    val parts = path.split('/').filter { it.isNotEmpty() }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, parts.lastOrNull() ?: "/", node?.let { S(R.string.albums_songs, it.albumCount, it.trackCount) }) {
            // このフォルダの曲をまとめて再生する(子フォルダも含む)
            val src = MainViewModel.PlaySource(MainViewModel.PlaySource.FOLDER, path)
            IconButton({ folderTracks(vm, path).takeIf { it.isNotEmpty() }?.let { vm.playTracks(it, 0, source = src) } }) {
                Icon(Icons.Filled.PlayArrow, S(R.string.play_folder))
            }
            IconButton({ folderTracks(vm, path).takeIf { it.isNotEmpty() }?.let { vm.playTracks(it, 0, shuffle = true, source = src) } }) {
                Icon(AppIcons.Shuffle, S(R.string.shuffle_folder))
            }
            // この下のアルバムをまとめて一覧にする
            IconButton({ vm.libPush("folder:$path") }) { Icon(AppIcons.ListView, S(R.string.show_all_albums_in_this)) }
        }
        // 今いる場所(パンくず)。タップでその階層へ移る
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            parts.forEachIndexed { i, p ->
                if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                val target = parts.take(i + 1).joinToString("/")
                Text(
                    p, style = MaterialTheme.typography.labelLarge,
                    color = if (i == parts.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = i < parts.lastIndex) { vm.libReplaceTop("dir:$target") }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        if (node == null) {
            EmptyState(AppIcons.Folder, S(R.string.folder_not_found), "")
            return@Column
        }
        val children = remember(node) { node.children.values.toList() }
        Box(Modifier.fillMaxSize()) {
            val st = rememberLazyListState()
            LazyColumn(state = st, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp + barInset())) {
                items(children, key = { "f:" + it.path }) { c ->
                    GroupRow(
                        c.name, S(R.string.albums_songs, c.albumCount, c.trackCount),
                        { vm.libPush("dir:" + c.path) },
                    ) { RoundIcon(AppIcons.Folder) }
                }
                items(node.albums, key = { "a:" + it.key }) { a -> AlbumListRow(vm, a) }
            }
            ScrollTopButton(remember(st) { derivedStateOf { st.firstVisibleItemIndex > 8 } }.value) { st.scrollToItem(0) }
        }
    }
}

/** 表示する 1 行(フォルダかアルバム)。parent はその行が入っているフォルダ(同じアルバムが複数のフォルダに出るので、キーに使う) */
private class FolderRow(val depth: Int, val folder: FolderNode?, val album: LibAlbum?, val parent: String = "")

/**
 * フォルダ別: フォルダの階層をたどって開いていく。行をタップすると中のフォルダとアルバムを開き、もう一度タップで閉じる。
 * 右のボタンで、そのフォルダの下のアルバムをまとめて一覧にする
 */
@Composable
private fun FoldersPage(vm: MainViewModel) {
    val tree = remember(vm.albums) { folderTreeOf(vm.albums) }
    // 開いているフォルダ(戻ってきたときも同じように開いておく)
    val open = rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(
        save = { it.toList() }, restore = { mutableStateListOf<String>().apply { addAll(it) } },
    )) {
        // 一番上のフォルダが 1 つだけなら、最初から開いておく
        mutableStateListOf<String>().apply { if (tree.children.size == 1) add(tree.children.values.first().path) }
    }
    val rows = remember(tree, open.toList()) {
        val out = ArrayList<FolderRow>()
        fun walk(n: FolderNode, depth: Int) {
            for (c in n.children.values) {
                out.add(FolderRow(depth, c, null))
                if (c.path in open) walk(c, depth + 1)
            }
            for (a in n.albums) out.add(FolderRow(depth, null, a, n.path))
        }
        walk(tree, 0)
        out
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("folders"))
        Box(Modifier.fillMaxSize()) {
            val st = rememberLazyListState()
            LazyColumn(state = st, contentPadding = PaddingValues(start = 8.dp, end = 12.dp, bottom = 24.dp + barInset())) {
                // disc1 / disc2 のように分かれたアルバムは複数のフォルダに出るので、入っているフォルダもキーに含める
                items(rows, key = { r -> r.folder?.let { "f:" + it.path } ?: ("a:" + r.parent + "\u0000" + r.album!!.key) }) { r ->
                    val f = r.folder
                    if (f != null) {
                        val isOpen = f.path in open
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { if (isOpen) open.remove(f.path) else open.add(f.path) }
                                .padding(start = (r.depth * 20).dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (isOpen) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                if (isOpen) S(R.string.collapse) else S(R.string.expand),
                                Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            RoundIcon(AppIcons.Folder, 40)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    S(R.string.albums_songs, f.albumCount, f.trackCount),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                )
                            }
                            // このフォルダの曲を再生する(子フォルダも含む)
                            IconButton({ folderTracks(vm, f.path).takeIf { it.isNotEmpty() }?.let { vm.playTracks(it, 0, source = MainViewModel.PlaySource(MainViewModel.PlaySource.FOLDER, f.path)) } }) {
                                Icon(Icons.Filled.PlayArrow, S(R.string.play_folder))
                            }
                            // このフォルダの中へ入る(エクスプローラーのように、階層を潜っていく)
                            IconButton({ vm.libPush("dir:" + f.path) }) {
                                Icon(AppIcons.Grid, S(R.string.open_folder))
                            }
                        }
                    } else {
                        Box(Modifier.padding(start = (r.depth * 20 + 20).dp)) { AlbumListRow(vm, r.album!!) }
                    }
                }
            }
            ScrollTopButton(remember(st) { derivedStateOf { st.firstVisibleItemIndex > 8 } }.value) { st.scrollToItem(0) }
        }
    }
}

@Composable
private fun FavoritesPage(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val map = vm.trackMap
    val sort = vm.settings.collectAsState().value.favSort
    // 並べ替え(追加した順・曲名・アーティスト・アルバム)。大文字・小文字は区別しない
    val songs = remember(vm.store.favTracks, map, sort) {
        val l = vm.store.favTracks.mapNotNull { map[it] }
        when (sort) {
            1 -> l.sortedBy { it.title.lowercase() }
            2 -> l.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.disc }, { it.track }))
            3 -> l.sortedWith(compareBy({ it.album.lowercase() }, { it.disc }, { it.track }))
            else -> l
        }
    }
    val albums = remember(vm.store.favAlbums, vm.albums, sort) {
        val l = vm.store.favAlbums.mapNotNull { k -> vm.albumByKey(k) }
        when (sort) {
            1 -> l.sortedBy { it.title.lowercase() }
            2 -> l.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            3 -> l.sortedWith(compareByDescending<LibAlbum> { it.year }.thenBy { it.title.lowercase() })
            else -> l
        }
    }
    var sortMenu by remember { mutableStateOf(false) }
    val sortNames = listOf(
        S(R.string.fav_sort_added), S(R.string.fav_sort_title), S(R.string.fav_sort_artist),
        if (tab == 0) S(R.string.fav_sort_album) else S(R.string.fav_sort_year),
    )
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("favorites"))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(tab == 0, { tab = 0 }, label = { Text(S(R.string.songs_2, songs.size)) })
            FilterChip(tab == 1, { tab = 1 }, label = { Text(S(R.string.albums_2, albums.size)) })
            Spacer(Modifier.weight(1f))
            Box {
                TextButton({ sortMenu = true }) { Text(sortNames[sort]) }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    sortNames.forEachIndexed { i, n ->
                        DropdownMenuItem(text = { Text(n) }, onClick = { sortMenu = false; vm.set(com.uyatame.ucrt.data.Keys.favSort, i) })
                    }
                }
            }
        }
        if (tab == 0) {
            if (songs.isEmpty()) {
                EmptyState(
                    Icons.Filled.FavoriteBorder, S(R.string.no_favorite_songs_yet),
                    S(R.string.add_songs_with_heart_button),
                )
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp + barInset())) {
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
                    Icons.Filled.FavoriteBorder, S(R.string.no_favorite_albums_yet),
                    S(R.string.add_albums_with_heart_button),
                )
                return@Column
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + barInset()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(albums, key = { it.key }) { a -> AlbumTile(vm, a) }
            }
        }
    }
}

/**
 * 音質チェック用の曲。イヤホン・DAC・設定を変えたときに聴き比べる決まった曲を、お気に入りと同じ手軽さで集めておく
 */
@Composable
private fun SoundCheckPage(vm: MainViewModel) {
    val map = vm.trackMap
    val songs = remember(vm.store.checkTracks, map) { vm.store.checkTracks.mapNotNull { map[it] } }
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(S(R.string.clear_sound_check_title)) },
            text = { Text(S(R.string.clear_sound_check_body)) },
            confirmButton = { TextButton({ vm.setCheck(songs, false); confirm = false }) { Text(S(R.string.clear)) } },
            dismissButton = { TextButton({ confirm = false }) { Text(S(R.string.cancel)) } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("check"), S(R.string.sound_check_desc)) {
            if (songs.isNotEmpty()) {
                IconButton({ confirm = true }) { Icon(Icons.Filled.Delete, S(R.string.clear_sound_check_title)) }
            }
        }
        if (songs.isEmpty()) {
            EmptyState(AppIcons.Headphones, S(R.string.no_sound_check_songs_yet), S(R.string.add_sound_check_hint))
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp + barInset())) {
            item(key = "controls") {
                PlayShuffleButtons(
                    { vm.playTracks(songs, 0) }, { vm.playTracks(songs, 0, shuffle = true) },
                    Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(songs, key = { _, t -> t.uri }) { i, t -> TrackRow(vm, t, { vm.playTracks(songs, i) }) }
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
            title = { Text(S(R.string.clear_play_history)) },
            text = { Text(S(R.string.this_resets_recently_played_and)) },
            confirmButton = { TextButton({ vm.store.clearHistory(); confirm = false }) { Text(S(R.string.clear)) } },
            dismissButton = { TextButton({ confirm = false }) { Text(S(R.string.cancel)) } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("history"), S(R.string.songs_4, list.size)) {
            if (list.isNotEmpty()) IconButton({ confirm = true }) { Icon(Icons.Filled.Delete, S(R.string.clear_history)) }
        }
        if (list.isEmpty()) {
            EmptyState(AppIcons.History, S(R.string.nothing_played_yet), "")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp + barInset())) {
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
            EmptyState(AppIcons.Trending, S(R.string.nothing_played_yet), "")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp + barInset())) {
            item(key = "controls") {
                PlayShuffleButtons(
                    { vm.playTracks(tracks, 0) }, { vm.playTracks(tracks, 0, shuffle = true) },
                    Modifier.padding(start = 8.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(list, key = { _, p -> p.first.uri }) { i, (t, n) ->
                TrackRow(vm, t, { vm.playTracks(tracks, i) }, number = "${i + 1}", showArt = true, trailingText = S(R.string.label, n))
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
        text = { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(S(R.string.name)) }) },
        confirmButton = { TextButton({ onDone(name); onDismiss() }, enabled = name.isNotBlank()) { Text(S(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(S(R.string.cancel)) } },
    )
}

@Composable
private fun PlaylistsPage(vm: MainViewModel) {
    var creating by remember { mutableStateOf(false) }
    if (creating) PlaylistNameDialog(S(R.string.new_playlist), "", { creating = false }) { vm.createPlaylist(it, emptyList()) }
    val map = vm.trackMap
    // ほかのアプリのプレイリスト(M3U など)を読み込む
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.importPlaylists(it) }
    Column(Modifier.fillMaxSize()) {
        PageTop(vm, tileLabel("playlists")) {
            IconButton({ importer.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "audio/x-scpls", "application/xspf+xml", "application/vnd.ms-wpl", "text/*", "application/octet-stream")) }) {
                Icon(AppIcons.Download, S(R.string.playlist_import))
            }
            IconButton({ creating = true }) { Icon(Icons.Filled.Add, S(R.string.new_playlist)) }
        }
        if (vm.store.playlists.isEmpty()) {
            EmptyState(
                AppIcons.Queue, S(R.string.no_playlists_yet),
                S(R.string.create_one_from_song_or),
                S(R.string.new_playlist),
            ) { creating = true }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp + barInset())) {
            items(vm.store.playlists, key = { it.id }) { p ->
                // 曲の一覧から作るものは、プレイリストかライブラリが変わったときだけ作り直す(行を描き直すたびに作らない)
                val (first, count) = remember(p.uris, map) {
                    val ts = p.uris.mapNotNull { map[it] }
                    ts.firstOrNull() to countText(ts)
                }
                GroupRow(p.name, count, { vm.libPush("playlist:" + p.id) }) {
                    if (first != null) ArtImage(vm.albumKeyOf(first.uri) ?: first.uri, first.uri, first.coverUri, Modifier.size(48.dp), 8.dp, 48.dp)
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
    if (renaming) PlaylistNameDialog(S(R.string.rename), p.name, { renaming = false }) { vm.store.renamePlaylist(p.id, it) }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(S(R.string.delete_2, p.name)) },
            text = { Text(S(R.string.song_files_wont_be_deleted)) },
            confirmButton = { TextButton({ deleting = false; vm.store.deletePlaylist(p.id) }) { Text(S(R.string.delete)) } },
            dismissButton = { TextButton({ deleting = false }) { Text(S(R.string.cancel)) } },
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
    // 並べ替えのために選んだ曲(プレイリストの中の位置)。null なら選んでいない
    var picked by remember(id) { mutableStateOf<Set<Int>?>(null) }
    Column(Modifier.fillMaxSize()) {
        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { u -> if (u != null) vm.exportPlaylist(p, u) }
        PageTop(vm, p.name, countText(tracks)) {
            IconButton({ picked = if (picked == null) emptySet() else null }) {
                Icon(Icons.Filled.CheckCircle, S(R.string.playlist_arrange), tint = if (picked != null) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
            IconButton({ exporter.launch(p.name + ".m3u8") }) { Icon(AppIcons.Upload, S(R.string.playlist_export)) }
            IconButton({ renaming = true }) { Icon(Icons.Filled.Edit, S(R.string.rename)) }
            IconButton({ deleting = true }) { Icon(Icons.Filled.Delete, S(R.string.delete)) }
        }
        // 選んだ曲をまとめて動かす帯
        picked?.let { sel ->
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(S(R.string.playlist_picked, sel.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
                val on = sel.isNotEmpty()
                FilledTonalButton({ picked = vm.store.movePlaylistItems(id, sel, -2) }, enabled = on) { Text(S(R.string.move_top)) }
                FilledTonalButton({ picked = vm.store.movePlaylistItems(id, sel, -1) }, enabled = on) { Text(S(R.string.move_up)) }
                FilledTonalButton({ picked = vm.store.movePlaylistItems(id, sel, 1) }, enabled = on) { Text(S(R.string.move_down)) }
                FilledTonalButton({ picked = vm.store.movePlaylistItems(id, sel, 2) }, enabled = on) { Text(S(R.string.move_bottom)) }
                TextButton({ vm.store.removePlaylistItems(id, sel); picked = emptySet() }, enabled = on) { Text(S(R.string.remove_from_playlist_short)) }
                TextButton({ picked = null }) { Text(S(R.string.done)) }
            }
        }
        LazyColumn(state = st, contentPadding = PaddingValues(start = 4.dp, end = 8.dp, bottom = 24.dp + barInset())) {
            item(key = "controls") {
                if (tracks.isEmpty()) {
                    Text(
                        S(R.string.use_add_to_playlist_in),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    val src = MainViewModel.PlaySource(MainViewModel.PlaySource.PLAYLIST, id)
                    PlayShuffleButtons(
                        { vm.playTracks(tracks, 0, source = src) }, { vm.playTracks(tracks, 0, shuffle = true, source = src) },
                        Modifier.padding(start = 12.dp, bottom = 8.dp),
                    )
                }
            }
            itemsIndexed(entries, key = { i, _ -> keys[i] }) { i, (pos, t) ->
                val sel = picked
                TrackRow(
                    vm, t, {
                        if (sel != null) picked = if (pos in sel) sel - pos else sel + pos
                        else vm.playTracks(tracks, i, source = MainViewModel.PlaySource(MainViewModel.PlaySource.PLAYLIST, id))
                    },
                    modifier = Modifier.reorderItem(reorder, i),
                    playlist = p.id to pos,
                    handle = {
                        if (sel != null) androidx.compose.material3.Checkbox(pos in sel, { picked = if (it) sel + pos else sel - pos })
                        else DragHandle(reorder, i)
                    },
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
    // 検索用にそろえた文字は、ライブラリが変わったときだけ作る(1 文字打つたびに全曲を Normalizer にかけない)。
    // 最初に文字を打ったときに一度だけ作る
    val index = remember(albums) {
        lazy {
            val artists = albums.map { it.artist }.distinct().map { it to norm(it) }
            val al = albums.map { it to (norm(it.title) + "\u0000" + norm(it.artist)) }
            val songs = albums.flatMap { it.tracks }.map { it to (norm(it.title) + "\u0000" + norm(it.artist)) }
            Triple(artists, al, songs)
        }
    }
    // 探すのは画面のスレッドの外で(曲の多いライブラリでも、文字を打つたびに画面が止まらないように)。
    // 次の文字を打つと、前の検索は取り消される。結果が出るまでは前の結果を出しておく
    val result by produceState<Triple<List<String>, List<LibAlbum>, List<LibTrack>>?>(null, key, index) {
        value = if (key.isEmpty()) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val (ia, ial, isg) = index.value
            val artists = ia.filter { it.second.contains(key) }.take(10).map { it.first }
            val al = ial.filter { it.second.contains(key) }.take(30).map { it.first }
            val songs = isg.filter { it.second.contains(key) }.take(200).map { it.first }
            Triple(artists, al, songs)
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.libPop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S(R.string.back)) }
            OutlinedTextField(
                q, { q = it }, Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(S(R.string.songs_albums_artists)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Filled.Clear, S(R.string.clear)) } },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
            )
        }
        val r = result
        if (r == null) {
            Text(
                S(R.string.search_ignores_kana_and_character),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        val (artists, al, songs) = r
        if (artists.isEmpty() && al.isEmpty() && songs.isEmpty()) {
            EmptyState(Icons.Filled.Search, S(R.string.no_results), "")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp + barInset())) {
            if (artists.isNotEmpty()) {
                item(key = "ah") { ListHeading(S(R.string.artists), Modifier.padding(start = 8.dp)) }
                items(artists, key = { "a:$it" }) { name ->
                    GroupRow(name, "", { vm.libPush("artist:$name") }) { InitialAvatar(name) }
                }
            }
            if (al.isNotEmpty()) {
                item(key = "alh") { ListHeading(S(R.string.albums), Modifier.padding(start = 8.dp)) }
                items(al, key = { "al:" + it.key }) { a -> AlbumListRow(vm, a) }
            }
            if (songs.isNotEmpty()) {
                item(key = "sh") { ListHeading(S(R.string.songs_3), Modifier.padding(start = 8.dp)) }
                itemsIndexed(songs, key = { _, t -> "s:" + t.uri }) { i, t ->
                    TrackRow(vm, t, { vm.playTracks(songs, i) }, sub = t.artist + S(R.string.separator) + t.album)
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
    // 編集は画面いっぱいに出す(ダイアログだと狭くて入力しにくいため)
    if (editing) {
        EditTagsPage(vm, a) { editing = false }
        return
    }
    if (lookup) LookupSheet(vm, a) { lookup = false }
    val discs = remember(a) { a.tracks.groupBy { it.disc }.toList().sortedBy { it.first } }
    SelectionPool(vm, a.tracks)
    val multiDisc = discs.size > 1
    val fav = vm.store.isFavAlbum(a.key)
    val land = isLandscape()
    val favButton: @Composable () -> Unit = {
        IconButton({ vm.toggleFavAlbum(a.key) }) {
            Icon(
                if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (fav) S(R.string.remove_from_favorites) else S(R.string.add_to_favorites),
                tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val bar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.libPop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S(R.string.back)) }
            Spacer(Modifier.weight(1f))
            if (vm.tagSaving) {
                Text(S(R.string.saving), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 12.dp))
            } else {
                // 横向きでは左の列が狭いので、お気に入りのボタンは上の帯に出す
                if (land) favButton()
                IconButton({ lookup = true; vm.libLookup(a) }) { Icon(Icons.Filled.Search, S(R.string.get_track_info)) }
                IconButton({ editing = true }) { Icon(Icons.Filled.Edit, S(R.string.edit_track_info)) }
                IconButton({ vm.menuAlbum = a }) { Icon(Icons.Filled.MoreVert, S(R.string.more_options)) }
            }
        }
    }
    val head: @Composable (Modifier) -> Unit = { artMod ->
        Column(Modifier.fillMaxWidth().padding(horizontal = if (land) 16.dp else 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ArtImage(a.key, a.first.uri, a.first.coverUri, artMod, 16.dp)
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
                    (if (a.year.isNotEmpty()) a.year + S(R.string.separator) else "") + countText(a.tracks),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayShuffleButtons({ vm.playAlbum(a, 0) }, { vm.playAlbum(a, 0, shuffle = true) })
                if (!land) {
                    Spacer(Modifier.width(4.dp))
                    favButton()
                }
            }
        }
    }
    val tracks: LazyListScope.() -> Unit = {
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
    if (land) {
        // 横向き: 左にジャケットと操作、右に曲の一覧(それぞれでスクロール)。
        // 左の列は再生・シャッフルのボタンが並ぶ幅(300dp)より狭くしない
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val left = (maxWidth * 0.42f).coerceAtLeast(300.dp)
            Column(Modifier.fillMaxSize()) {
                bar()
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(left).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp + barInset())) {
                        head(Modifier.fillMaxWidth(0.8f).widthIn(max = 360.dp).aspectRatio(1f))
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(end = 8.dp, bottom = 24.dp + barInset()), content = tracks)
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + barInset())) {
            item(key = "bar") { bar() }
            item(key = "head") { head(Modifier.fillMaxWidth(0.66f).aspectRatio(1f)) }
            tracks()
        }
    }
}

/** 取り込み済みのアルバムの曲情報を編集する画面(画面いっぱい)。詳しい情報と歌詞も書ける(FLAC・MP3・M4A) */
@Composable
internal fun EditTagsPage(vm: MainViewModel, a: LibAlbum, onDone: () -> Unit) {
    val form = remember(a) {
        TagForm(
            a.title, a.artist, a.first.year, a.first.genre,
            a.tracks.mapIndexed { i, t ->
                TrackForm(
                    trackLabel(if (t.track > 0) t.track else i + 1), t.title, t.artist, lengthText(t.durationMs),
                    com.uyatame.ucrt.tags.TagExtra.supported(t.uri),
                    trackNo = if (t.track > 0) t.track.toString() else "", discNo = if (t.disc > 0) t.disc.toString() else "",
                )
            },
            numbers = true,
            trackTotal = maxOf(a.tracks.size, a.tracks.maxOf { it.track }).toString(),
            discTotal = a.tracks.maxOf { it.disc }.coerceAtLeast(1).toString(),
        )
    }
    // 曲ごとの詳しい情報(今の値)を読んでから入力できるようにする。読めなかった曲は書き換えない
    var extras by remember(a) { mutableStateOf<List<com.uyatame.ucrt.tags.TagExtra?>?>(null) }
    LaunchedEffect(a) {
        val list = vm.readAlbumExtras(a)
        list.forEachIndexed { i, e ->
            val f = form.tracks.getOrNull(i) ?: return@forEachIndexed
            if (e == null) return@forEachIndexed
            f.composer = e.composer.orEmpty(); f.lyricist = e.lyricist.orEmpty()
            f.comment = e.comment.orEmpty(); f.lyrics = e.lyrics.orEmpty()
        }
        list.firstOrNull { it != null }?.genre?.takeIf { it.isNotBlank() }?.let { form.genre = it }
        extras = list
    }
    var cover by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) cover = it }
    val unsupported = a.tracks.count { !TagEditor.supported(it.uri) }
    TagEditorPage(
        S(R.string.edit_track_info), form,
        onCancel = onDone,
        onSave = {
            val read = extras
            vm.saveAlbumTags(
                a, form.album, form.albumArtist, form.date,
                form.tracks.map { it.title }, form.tracks.map { it.artist }, cover,
                extras = form.tracks.mapIndexed { i, t -> if (read?.getOrNull(i) == null) null else t.extra(form.genre) },
                numbers = MainViewModel.TagNumbers(
                    form.tracks.map { it.trackNo.toIntOrNull() }, form.trackTotal.toIntOrNull(),
                    form.tracks.map { it.discNo.toIntOrNull() }, form.discTotal.toIntOrNull(),
                ),
            )
            onDone()
        },
        loading = extras == null,
        note = if (unsupported > 0) S(R.string.songs_cant_be_edited_because, unsupported) else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtImage(a.key, a.first.uri, a.first.coverUri, Modifier.size(72.dp), 8.dp, 72.dp)
            Spacer(Modifier.width(12.dp))
            FilledTonalButton({
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(if (cover == null) S(R.string.change_cover) else S(R.string.new_image_selected)) }
        }
    }
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
            contentPadding = PaddingValues(bottom = 32.dp + barInset()),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(S(R.string.get_track_info), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onDismiss) { Text(S(R.string.close)) }
                }
                Text(
                    S(R.string.finds_matching_cds_by_song, n),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    album, { album = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(S(R.string.album)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
            }
            item {
                OutlinedTextField(
                    artist, { artist = it }, Modifier.fillMaxWidth(),
                    label = { Text(S(R.string.artist)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(search, enabled = !vm.libSearching && (album.isNotBlank() || artist.isNotBlank())) {
                        Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(S(R.string.search_by_name))
                    }
                    TextButton({ vm.libLookup(a) }, enabled = !vm.libSearching) { Text(S(R.string.match_by_length)) }
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
            title = { Text(S(R.string.overwrite_with_these_details)) },
            text = {
                Text(
                    "${c.meta.title}\n${c.meta.artist}\n\n" + S(R.string.this_rewrites_title_artist_album, n),
                )
            },
            confirmButton = {
                TextButton({
                    pick = null
                    vm.libApply(a, c) { onDismiss() }
                }) { Text(S(R.string.overwrite)) }
            },
            dismissButton = { TextButton({ pick = null }) { Text(S(R.string.cancel)) } },
        )
    }
}

/** アルバムが入っているフォルダの名前(例: Music/Artist/Album の Album) */
private fun folderPath(a: LibAlbum): String = Uri.decode(a.first.folderId).substringAfter(':', "")

private fun folderName(a: LibAlbum): String = folderPath(a).substringAfterLast('/')

