@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.uyatame.ucrt

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.library.ArtLoader
import com.uyatame.ucrt.spotify.SpItem
import com.uyatame.ucrt.spotify.SpKind
import com.uyatame.ucrt.spotify.SpList
import com.uyatame.ucrt.spotify.SpFetch
import com.uyatame.ucrt.spotify.SpRoute
import com.uyatame.ucrt.spotify.SpotifyApi
import com.uyatame.ucrt.spotify.SpotifyAuth
import com.uyatame.ucrt.spotify.SpotifyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Spotify の画面(ストリーミングのタブで Spotify の機能をオンにすると開ける)。曲を探して選ぶと、公式 Spotify アプリで再生する。
 * pauseLocal: このアプリで再生中の曲を止める(2 つ同時に鳴らないように)
 */
/** Spotify へのログイン(Spotify アプリかブラウザで許可して、結果を受け取る) */
@Composable
private fun rememberSpotifyLogin(sp: SpotifyViewModel): () -> Unit {
    val ctx = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res -> sp.onLoginResult(res.resultCode, res.data) }
    return remember(launcher, ctx) {
        {
            val act = ctx.findActivity()
            if (act != null) runCatching { launcher.launch(sp.loginIntent(act)) }.onFailure { sp.message = it.message }
        }
    }
}

private fun Context.findActivity(): android.app.Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun SpotifyScreen(sp: SpotifyViewModel, onBack: () -> Unit, pauseLocal: () -> Unit) {
    val ctx = LocalContext.current
    if (sp.clientId.isEmpty() || !sp.loggedIn) {
        WideCenter { SpotifySetup(sp, onBack) }
        return
    }
    // 開いたら Spotify アプリにつないでおく(再生中の曲を下に出すため)。アカウントの情報もこのときに読む
    LaunchedEffect(Unit) {
        sp.ensureUser()
        if (sp.spotifyInstalled()) sp.connect(ctx, quiet = true)
    }
    val route = sp.stack.lastOrNull()
    BackHandler(enabled = route != null) { sp.pop() }
    val play = remember(sp) {
        SpPlay(
            context = { uri, index, fallback -> pauseLocal(); sp.playContext(ctx, uri, index, fallback) },
            shuffle = { uri -> pauseLocal(); sp.playContext(ctx, uri, shuffle = true) },
            track = { uri -> pauseLocal(); sp.playTrack(ctx, uri) },
        )
    }
    WideCenter {
        androidx.compose.runtime.saveable.rememberSaveableStateHolder().SaveableStateProvider(route?.toString() ?: "home") {
            when (route) {
                null -> SpotifyHome(sp, play, onBack)
                is SpRoute.Album -> SpTrackPage(
                    sp, play, route.item.title, route.item.sub, route.item.image, route.item.uri,
                    "album:${route.item.id}", saveItem = route.item,
                ) { c, o, _ -> SpotifyApi.albumTracks(c, route.item, o) }
                is SpRoute.Playlist -> SpTrackPage(
                    sp, play, route.item.title, route.item.sub, route.item.image, route.item.uri,
                    "playlist:${route.item.id}",
                    // 自分のプレイリストは保存(フォロー)の対象ではなく、曲を外せる
                    saveItem = route.item.takeUnless { sp.canEdit(it) },
                    editable = route.item.takeIf { sp.canEdit(it) },
                ) { c, o, _ -> SpotifyApi.playlistTracks(c, route.item.id, o) }
                SpRoute.Liked -> SpTrackPage(
                    sp, play, S(R.string.spotify_liked_songs), sp.userName, null, sp.likedUri, "liked",
                ) { c, o, _ -> SpotifyApi.savedTracks(c, o) }
                SpRoute.Recent -> SpTrackPage(
                    sp, play, S(R.string.spotify_recently_played), "", null, null, "recent",
                ) { c, _, _ -> SpotifyApi.recentlyPlayed(c) }
                SpRoute.TopTracks -> SpTrackPage(
                    sp, play, S(R.string.spotify_top_tracks), S(R.string.spotify_last_6_months), null, null, "topTracks",
                ) { c, o, _ -> SpotifyApi.topTracks(c, o) }
                SpRoute.Playlists -> SpCollectionPage(sp, play, S(R.string.playlists), "playlists", canCreate = true) { c, o, _ -> SpotifyApi.myPlaylists(c, o) }
                SpRoute.Albums -> SpCollectionPage(sp, play, S(R.string.albums), "albums") { c, o, _ -> SpotifyApi.savedAlbums(c, o) }
                SpRoute.Artists -> SpCollectionPage(sp, play, S(R.string.spotify_followed_artists), "artists") { c, _, cur -> SpotifyApi.followedArtists(c, cur) }
                SpRoute.TopArtists -> SpCollectionPage(sp, play, S(R.string.spotify_top_artists), "topArtists") { c, o, _ -> SpotifyApi.topArtists(c, o) }
                is SpRoute.Artist -> SpArtistPage(sp, play, route.item)
            }
        }
    }
}

/** 再生の操作(画面の部品に渡す) */
private class SpPlay(
    val context: (uri: String, index: Int, fallbackTrack: String?) -> Unit,
    val shuffle: (uri: String) -> Unit,
    val track: (uri: String) -> Unit,
)

/** Spotify の画面の見出し(左の矢印でストリーミングのタブのトップへ戻る) */
@Composable
private fun SpHeader(onBack: () -> Unit, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S(R.string.back)) }
        Text("Spotify", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
        actions()
    }
}

// ---------------- 最初の設定 ----------------

@Composable
private fun SpotifySetup(sp: SpotifyViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    // Spotify アプリが入っているか(調べるのに時間がかかるので、画面を開いたときに 1 回だけ)
    val installed = remember { sp.spotifyInstalled() }
    val login = rememberSpotifyLogin(sp)
    var id by rememberSaveable { mutableStateOf(sp.clientId) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp + barInset()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SpHeader(onBack)
        Text(
            S(R.string.spotify_setup_intro),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!installed) {
            UCard(Modifier.fillMaxWidth()) {
                Text(S(R.string.spotify_app_is_not_installed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        }
        Section(S(R.string.spotify_setup_step1_title)) {
            Text(S(R.string.spotify_setup_step1), style = MaterialTheme.typography.bodyMedium)
            CopyRow(S(R.string.spotify_redirect_uri), SpotifyAuth.REDIRECT_URI)
            CopyRow(S(R.string.spotify_package_name), ctx.packageName)
            CopyRow(S(R.string.spotify_sha1_fingerprint), remember { SpotifyAuth.signingSha1(ctx) })
            Text(
                S(R.string.spotify_setup_step1_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton({ openUri(ctx, "https://developer.spotify.com/dashboard") }) {
                Text(S(R.string.spotify_open_dashboard))
            }
        }
        Section(S(R.string.spotify_setup_step2_title)) {
            OutlinedTextField(
                id, { id = it.trim() },
                label = { Text(S(R.string.spotify_client_id)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                S(R.string.spotify_client_id_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val valid = id.length == 32 && id.all { it.isLetterOrDigit() }
            if (id.isNotEmpty() && !valid) {
                Text(S(R.string.spotify_client_id_looks_wrong), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                {
                    sp.saveClientId(id)
                    login()
                },
                enabled = valid && !sp.loginBusy,
            ) {
                if (sp.loginBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(S(R.string.spotify_log_in))
            }
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String) {
    val clip = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        TextButton({ clip.setText(AnnotatedString(value)) }) { Text(S(R.string.spotify_copy)) }
    }
}

private fun openUri(ctx: Context, uri: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ---------------- トップ(検索・ライブラリ) ----------------

@Composable
private fun SpotifyHome(sp: SpotifyViewModel, play: SpPlay, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val installed = remember { sp.spotifyInstalled() }
    val login = rememberSpotifyLogin(sp)
    val focus = LocalFocusManager.current
    var menu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = barInset())) {
        item {
            SpHeader(onBack) {
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, S(R.string.spotify_account)) }
                    DropdownMenu(menu, { menu = false }) {
                        if (sp.userName.isNotEmpty()) {
                            DropdownMenuItem({ Text(sp.userName, style = MaterialTheme.typography.labelLarge) }, {}, enabled = false)
                        }
                        DropdownMenuItem({ Text(S(R.string.spotify_open_spotify_app)) }, {
                            menu = false
                            ctx.packageManager.getLaunchIntentForPackage("com.spotify.music")?.let { runCatching { ctx.startActivity(it) } }
                        })
                        DropdownMenuItem({ Text(S(R.string.spotify_log_out)) }, { menu = false; sp.logout() })
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                sp.query, { sp.query = it },
                placeholder = { Text(S(R.string.spotify_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (sp.query.isNotEmpty()) IconButton({ sp.clearSearch() }) { Icon(Icons.Filled.Clear, S(R.string.spotify_clear)) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); sp.runSearch() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(28.dp),
            )
        }
        if (sp.needsReauth) {
            item {
                UCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(S(R.string.spotify_please_allow_again), style = MaterialTheme.typography.bodyMedium)
                        Button(login) { Text(S(R.string.spotify_log_in_again)) }
                    }
                }
            }
        }
        if (!installed) {
            item {
                Text(
                    S(R.string.spotify_app_is_not_installed),
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        val res = sp.search
        when {
            sp.searching -> item { Loading() }
            sp.searchError != null -> item { ErrorRow(sp.searchError.orEmpty()) { sp.runSearch() } }
            res != null -> {
                section(S(R.string.spotify_songs), res.tracks) { SpRow(sp, it, play) }
                section(S(R.string.artists), res.artists) { SpRow(sp, it, play) }
                section(S(R.string.albums), res.albums) { SpRow(sp, it, play) }
                section(S(R.string.playlists), res.playlists) { SpRow(sp, it, play) }
                if (res.tracks.isEmpty() && res.albums.isEmpty() && res.artists.isEmpty() && res.playlists.isEmpty()) {
                    item { EmptyState(Icons.Filled.Search, S(R.string.spotify_no_results), "") }
                }
            }
            else -> {
                item { HomeLink(Icons.Filled.Favorite, S(R.string.spotify_liked_songs)) { sp.push(SpRoute.Liked) } }
                item { HomeLink(AppIcons.ListView, S(R.string.playlists)) { sp.push(SpRoute.Playlists) } }
                item { HomeLink(AppIcons.Album, S(R.string.albums)) { sp.push(SpRoute.Albums) } }
                item { HomeLink(AppIcons.Headphones, S(R.string.spotify_followed_artists)) { sp.push(SpRoute.Artists) } }
                item { HomeLink(AppIcons.History, S(R.string.spotify_recently_played)) { sp.push(SpRoute.Recent) } }
                item { HomeLink(AppIcons.MusicNote, S(R.string.spotify_top_tracks)) { sp.push(SpRoute.TopTracks) } }
                item { HomeLink(Icons.Filled.Star, S(R.string.spotify_top_artists)) { sp.push(SpRoute.TopArtists) } }
                item {
                    Text(
                        S(R.string.spotify_playback_note),
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun LazyListScope.section(title: String, list: List<SpItem>, row: @Composable (SpItem) -> Unit) {
    if (list.isEmpty()) return
    item {
        Text(
            title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    // 同じ曲が別の見出しにも出ることがあるので、見出しの名前と並びの番号も付けて区別する(同じ key が 2 つあると落ちるため)
    itemsIndexed(list, key = { i, it -> "$title:$i:${it.uri}" }, contentType = { _, _ -> "sp" }) { _, it -> row(it) }
}

@Composable
private fun HomeLink(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

// ---------------- 一覧の行 ----------------

/** お気に入りの文言(曲はお気に入り、アルバムとプレイリストは保存、アーティストはフォロー) */
fun spSaveLabel(kind: SpKind, saved: Boolean): String = S(
    when (kind) {
        SpKind.TRACK -> if (saved) R.string.spotify_remove_from_liked else R.string.spotify_add_to_liked
        SpKind.ARTIST -> if (saved) R.string.spotify_unfollow else R.string.spotify_follow
        else -> if (saved) R.string.spotify_remove_from_library else R.string.spotify_save_to_library
    },
)

/**
 * 1 行。長押しか右のボタンでメニュー(キュー・プレイリスト・お気に入り・アルバムやアーティストへの移動など)。
 * playlist: 自分のプレイリストの中の行なら、そのプレイリスト(曲を外せる)
 */
@Composable
private fun SpRow(sp: SpotifyViewModel, item: SpItem, play: SpPlay, playlist: SpItem? = null, onClick: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val open: () -> Unit = onClick ?: {
        when (item.kind) {
            SpKind.TRACK -> play.track(item.uri)
            SpKind.ALBUM -> sp.push(SpRoute.Album(item))
            SpKind.PLAYLIST -> sp.push(SpRoute.Playlist(item))
            SpKind.ARTIST -> sp.push(SpRoute.Artist(item))
        }
    }
    // メニューを開いたら、お気に入りに入っているかを確かめる
    LaunchedEffect(menu) { if (menu) sp.checkSaved(listOf(item.uri)) }
    val playing = item.kind == SpKind.TRACK && item.uri == sp.trackUri
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = open, onLongClick = { menu = true })
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpotifyImage(item.image, Modifier.size(48.dp), if (item.kind == SpKind.ARTIST) 24.dp else 6.dp, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            val sub = listOfNotNull(
                kindLabel(item.kind).takeIf { item.kind != SpKind.TRACK },
                item.sub.ifEmpty { null },
                if (item.kind == SpKind.TRACK && item.durationMs > 0) fmtMs(item.durationMs) else null,
            ).joinToString(S(R.string.separator))
            if (sub.isNotEmpty()) {
                Text(
                    sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, S(R.string.spotify_more)) }
            DropdownMenu(menu, { menu = false }) {
                val isSaved = sp.saved[item.uri] == true
                if (item.kind == SpKind.TRACK) {
                    DropdownMenuItem({ Text(S(R.string.spotify_add_to_queue)) }, { menu = false; sp.queue(ctx, item) })
                    DropdownMenuItem({ Text(S(R.string.spotify_add_to_playlist)) }, { menu = false; sp.pickPlaylistFor = listOf(item.uri) })
                } else {
                    DropdownMenuItem({ Text(S(R.string.play)) }, { menu = false; play.context(item.uri, -1, null) })
                    DropdownMenuItem({ Text(S(R.string.spotify_shuffle_play)) }, { menu = false; play.shuffle(item.uri) })
                }
                // 自分のプレイリストは、保存ではなく作成者のもの(保存・解除は出さない)
                if (!(item.kind == SpKind.PLAYLIST && sp.canEdit(item))) {
                    DropdownMenuItem({ Text(spSaveLabel(item.kind, isSaved)) }, {
                        menu = false
                        sp.setSaved(ctx, item.uri, item.kind, item.title, !isSaved)
                    })
                }
                if (playlist != null && item.kind == SpKind.TRACK) {
                    DropdownMenuItem({ Text(S(R.string.spotify_remove_from_this_playlist)) }, {
                        menu = false
                        sp.removeFromPlaylist(playlist, item)
                    })
                }
                item.albumUri?.let { a ->
                    DropdownMenuItem({ Text(S(R.string.spotify_go_to_album)) }, { menu = false; sp.openUri(a, item.albumName.orEmpty()) })
                }
                item.artistUri?.let { a ->
                    DropdownMenuItem({ Text(S(R.string.spotify_go_to_artist)) }, { menu = false; sp.openUri(a, item.artistName.orEmpty()) })
                }
                DropdownMenuItem({ Text(S(R.string.spotify_open_in_spotify)) }, { menu = false; openUri(ctx, item.uri) })
            }
        }
    }
}

private fun kindLabel(k: SpKind): String = when (k) {
    SpKind.TRACK -> S(R.string.spotify_song)
    SpKind.ALBUM -> S(R.string.spotify_album)
    SpKind.PLAYLIST -> S(R.string.spotify_playlist)
    SpKind.ARTIST -> S(R.string.spotify_artist)
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun ErrorRow(text: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onRetry) { Text(S(R.string.spotify_retry)) }
    }
}

/** 一覧の終わりまで来たら、続きを読む */
private fun LazyListScope.pagedFooter(l: SpList, onMore: () -> Unit, onRetry: () -> Unit) {
    item {
        when {
            l.loading -> Loading()
            l.error != null -> ErrorRow(l.error.orEmpty(), onRetry)
            l.hasMore -> {
                LaunchedEffect(l.items.size) { onMore() }
                Loading()
            }
        }
    }
}

/** 再生・シャッフル再生・お気に入りのボタン(ページの上) */
@Composable
private fun PlayButtons(sp: SpotifyViewModel, play: SpPlay, contextUri: String?, saveItem: SpItem?) {
    val ctx = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (contextUri != null) {
            Button({ play.context(contextUri, -1, null) }) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(S(R.string.play))
            }
            FilledTonalIconButton({ play.shuffle(contextUri) }) { Icon(AppIcons.Shuffle, S(R.string.spotify_shuffle_play)) }
        }
        if (saveItem != null) {
            LaunchedEffect(saveItem.uri) { sp.checkSaved(listOf(saveItem.uri)) }
            val isSaved = sp.saved[saveItem.uri] == true
            IconButton({ sp.setSaved(ctx, saveItem.uri, saveItem.kind, saveItem.title, !isSaved) }) {
                Icon(
                    if (isSaved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    spSaveLabel(saveItem.kind, isSaved),
                    tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------- 曲の一覧(アルバム・プレイリスト・お気に入りなど) ----------------

@Composable
private fun SpTrackPage(
    sp: SpotifyViewModel,
    play: SpPlay,
    title: String,
    sub: String,
    image: String?,
    contextUri: String?,
    key: String,
    saveItem: SpItem? = null,
    editable: SpItem? = null,
    fetch: SpFetch,
) {
    val l = sp.list(key)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = barInset())) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PageHeader(title) { sp.pop() } }
                TextButton({ sp.reload(key, fetch) }) { Text(S(R.string.spotify_reload)) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (image != null) {
                    SpotifyImage(image, Modifier.size(120.dp), 8.dp, 120.dp)
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sub.isNotEmpty()) {
                        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PlayButtons(sp, play, contextUri, saveItem)
                }
            }
        }
        if (l.forbidden) {
            item {
                Text(
                    S(R.string.spotify_playlist_tracks_unavailable),
                    Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(l.items, key = { i, it -> "$i:${it.uri}" }) { _, t ->
            SpRow(sp, t, play, playlist = editable) {
                // アルバムやプレイリストの中の曲は、その曲からアルバム・プレイリストを続けて再生する
                if (contextUri != null && t.index >= 0) play.context(contextUri, t.index, t.uri) else play.track(t.uri)
            }
        }
        pagedFooter(l, { sp.more(key, fetch) }, { sp.retry(key, fetch) })
        if (!l.loading && !l.hasMore && l.items.isEmpty() && l.error == null && !l.forbidden) {
            item { EmptyState(AppIcons.MusicNote, S(R.string.spotify_nothing_here), "") }
        }
    }
}

// ---------------- プレイリスト・アルバム・アーティストの一覧 ----------------

@Composable
private fun SpCollectionPage(
    sp: SpotifyViewModel,
    play: SpPlay,
    title: String,
    key: String,
    canCreate: Boolean = false,
    fetch: SpFetch,
) {
    val l = sp.list(key)
    var creating by remember { mutableStateOf(false) }
    if (creating) NewPlaylistDialog({ creating = false }) { name -> creating = false; sp.createPlaylistAndAdd(name, emptyList()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = barInset())) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PageHeader(title) { sp.pop() } }
                if (canCreate) TextButton({ creating = true }) { Text(S(R.string.spotify_new_playlist)) }
                TextButton({ sp.reload(key, fetch) }) { Text(S(R.string.spotify_reload)) }
            }
        }
        itemsIndexed(l.items, key = { i, it -> "$i:${it.uri}" }) { _, it -> SpRow(sp, it, play) }
        pagedFooter(l, { sp.more(key, fetch) }, { sp.retry(key, fetch) })
        if (!l.loading && !l.hasMore && l.items.isEmpty() && l.error == null) {
            item { EmptyState(AppIcons.Album, S(R.string.spotify_nothing_here), "") }
        }
    }
}

// ---------------- アーティスト ----------------

@Composable
private fun SpArtistPage(sp: SpotifyViewModel, play: SpPlay, artist: SpItem) {
    val key = "artist:${artist.id}"
    val fetch: SpFetch = { c, o, _ -> SpotifyApi.artistAlbums(c, artist.id, o) }
    val l = sp.list(key)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = barInset())) {
        item { PageHeader(artist.title) { sp.pop() } }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SpotifyImage(artist.image, Modifier.size(96.dp), 48.dp, 96.dp)
                Spacer(Modifier.width(16.dp))
                PlayButtons(sp, play, artist.uri, artist)
            }
        }
        item {
            Text(
                S(R.string.albums), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        itemsIndexed(l.items, key = { i, it -> "$i:${it.uri}" }) { _, it -> SpRow(sp, it, play) }
        pagedFooter(l, { sp.more(key, fetch) }, { sp.retry(key, fetch) })
    }
}

// ---------------- プレイリストを選ぶ(曲を追加する先) ----------------

@Composable
private fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S(R.string.spotify_new_playlist)) },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text(S(R.string.spotify_playlist_name)) }, singleLine = true)
        },
        confirmButton = { TextButton({ onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text(S(R.string.spotify_create)) } },
        dismissButton = { TextButton(onDismiss) { Text(S(R.string.cancel)) } },
    )
}

/** 「プレイリストに追加」で開く、追加先の選択(どの画面からでも開けるように、アプリ全体に置く) */
@Composable
fun SpotifyDialogs(sp: SpotifyViewModel) {
    val uris = sp.pickPlaylistFor ?: return
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { sp.loadEditablePlaylists() }
    if (creating) {
        NewPlaylistDialog({ creating = false }) { name ->
            creating = false
            sp.pickPlaylistFor = null
            sp.createPlaylistAndAdd(name, uris)
        }
        return
    }
    AlertDialog(
        onDismissRequest = { sp.pickPlaylistFor = null },
        title = { Text(S(R.string.spotify_add_to_playlist)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(12.dp))
                        Text(S(R.string.spotify_new_playlist), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (sp.editableLoading) item { Loading() }
                itemsIndexed(sp.editablePlaylists, key = { i, it -> "$i:${it.uri}" }) { _, p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { sp.pickPlaylistFor = null; sp.addToPlaylist(p, uris) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SpotifyImage(p.image, Modifier.size(40.dp), 4.dp, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(p.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ sp.pickPlaylistFor = null }) { Text(S(R.string.cancel)) } },
    )
}

// ---------------- 画像 ----------------

/** Spotify の画像(URL)を読む。メモリーに少しだけ覚えておく */
private object SpotifyImages {
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(4 shl 20)) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val gate = Semaphore(4)

    fun peek(url: String?, px: Int): ImageBitmap? = url?.let { cache.get("$it@$px") }

    suspend fun load(url: String, px: Int): ImageBitmap? {
        peek(url, px)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                runCatching { ArtLoader.decode(SpotifyApi.fetchBytes(url), px)?.asImageBitmap() }.getOrNull()
            }
        }?.also { cache.put("$url@$px", it) }
    }
}

@Composable
private fun SpotifyImage(url: String?, modifier: Modifier, corner: Dp, size: Dp) {
    if (!LocalShowArt.current || url == null) {
        CoverBox(null, modifier, corner)
        return
    }
    val px = with(LocalDensity.current) { size.roundToPx() }.let { if (it <= 160) 160 else 400 }
    val img by produceState(SpotifyImages.peek(url, px), url, px) {
        if (value == null) value = SpotifyImages.load(url, px)
    }
    CoverBox(img, modifier, corner)
}

// ---------------- 再生中の曲(画面の下) ----------------

/** Spotify アプリで再生中の曲。タップすると Spotify の再生画面へ */
@Composable
fun SpotifyBar(sp: SpotifyViewModel, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var pos by remember { mutableLongStateOf(sp.positionMs()) }
    LaunchedEffect(sp.paused, sp.trackUri) {
        while (true) {
            pos = sp.positionMs()
            if (sp.paused) break
            delay(500)
        }
    }
    BarSurface(onOpen) {
        Column {
            LinearProgressIndicator(
                progress = { if (sp.durationMs > 0) (pos.toFloat() / sp.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverBox(sp.trackArt, Modifier.size(44.dp), 6.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(sp.trackTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Spotify" + S(R.string.separator) + sp.trackArtist,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.72f),
                    )
                }
                IconButton({ sp.prev(ctx) }, enabled = sp.canSkipPrev) { Icon(AppIcons.SkipPrevious, S(R.string.spotify_previous_track)) }
                IconButton({ sp.toggle(ctx) }) {
                    Icon(if (sp.paused) Icons.Filled.PlayArrow else AppIcons.Pause, if (sp.paused) S(R.string.play) else S(R.string.pause))
                }
                IconButton({ sp.next(ctx) }, enabled = sp.canSkipNext) { Icon(AppIcons.SkipNext, S(R.string.next_track)) }
            }
        }
    }
}
