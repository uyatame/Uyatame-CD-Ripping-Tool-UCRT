package com.uyatame.ucrt.spotify

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.PlayerState
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.meta.describeNetError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Spotify の画面の行き先 */
sealed class SpRoute {
    data class Album(val item: SpItem) : SpRoute()
    data class Playlist(val item: SpItem) : SpRoute()
    data class Artist(val item: SpItem) : SpRoute()
    data object Liked : SpRoute()
    data object Playlists : SpRoute()
    data object Albums : SpRoute()
    data object Artists : SpRoute()
    data object Recent : SpRoute()
    data object TopTracks : SpRoute()
    data object TopArtists : SpRoute()
}

/** ページごとに読み込む一覧の状態 */
class SpList {
    val items = mutableStateListOf<SpItem>()
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var hasMore by mutableStateOf(true)
    /** 曲の一覧を取得できない(他人のプレイリスト)。再生はできる */
    var forbidden by mutableStateOf(false)
    /** 次のページの目印(位置ではなく目印で続きを指す一覧のとき) */
    var cursor: String? = null
}

/**
 * Spotify の画面の状態。閲覧は Web API、再生は公式 Spotify アプリ(App Remote SDK)に任せる。
 * 音は Spotify アプリから出る(このアプリの USB DAC 出力やイコライザーは通らない)
 */
class SpotifyViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()

    // ---------------- ログイン ----------------
    var clientId by mutableStateOf(SpotifyAuth.clientId(app))
        private set
    var loggedIn by mutableStateOf(SpotifyAuth.isLoggedIn(app))
        private set
    var userName by mutableStateOf("")
        private set
    private var userId = ""
    var loginBusy by mutableStateOf(false)
        private set
    /** 使う許可が足りない(前の版でログインした・Spotify アプリに拒否された)。もう一度ログインしてもらう */
    var needsReauth by mutableStateOf(SpotifyAuth.isLoggedIn(app) && !SpotifyAuth.hasAllScopes(app))
        private set
    /** 画面の下に出すお知らせ */
    var message by mutableStateOf<String?>(null)

    init {
        if (loggedIn) onLoggedIn()
    }

    /** ログインの画面(Spotify アプリかブラウザ)から戻ってきたとき */
    fun onLoginResult(resultCode: Int, data: android.content.Intent?) {
        val r = com.spotify.sdk.android.auth.AuthorizationClient.getResponse(resultCode, data)
        Log.i(TAG, "login result: ${r.type} ${r.error.orEmpty()} refresh=${!r.refreshToken.isNullOrEmpty()}")
        val err = runCatching { SpotifyAuth.finishLogin(ctx, r) }.exceptionOrNull()
        loggedIn = SpotifyAuth.isLoggedIn(ctx)
        needsReauth = loggedIn && !SpotifyAuth.hasAllScopes(ctx)
        if (err != null) { message = err.message; return }
        // 許可し直したので、Spotify アプリにつなぎ直す
        disconnect()
        onLoggedIn()
    }

    fun saveClientId(id: String) {
        SpotifyAuth.setClientId(ctx, id)
        clientId = SpotifyAuth.clientId(ctx)
        loggedIn = SpotifyAuth.isLoggedIn(ctx)
    }

    /** ログインの画面を開く Intent(Spotify アプリが入っていれば、Spotify アプリで許可する) */
    fun loginIntent(activity: android.app.Activity): android.content.Intent =
        com.spotify.sdk.android.auth.AuthorizationClient.createLoginActivityIntent(activity, SpotifyAuth.request(ctx))

    fun logout() {
        SpotifyAuth.logout(ctx)
        loggedIn = false
        userName = ""
        userId = ""
        lists.clear()
        stack.clear()
        search = null
        saved.clear()
        myPlaylistsCache = null
        disconnect()
    }

    private fun onLoggedIn() {
        viewModelScope.launch {
            val me = withContext(Dispatchers.IO) { runCatching { SpotifyApi.me(ctx) } }
            me.onSuccess { userName = it.name; userId = it.id }
                .onFailure { handleError(it) }
        }
    }

    private fun handleError(e: Throwable): String {
        if (e is SpotifyLoginRequired) {
            loggedIn = false
            return S(R.string.spotify_please_log_in_again)
        }
        return describeNetError(e)
    }

    // ---------------- 画面の行き先 ----------------
    val stack = mutableStateListOf<SpRoute>()
    fun push(r: SpRoute) { stack.add(r) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun home() { stack.clear() }

    // ---------------- 一覧 ----------------
    // 一覧そのものは普通の Map に置き、中身(SpList の各値)の変化で画面を更新する(画面を組み立てる途中で読んでもよいように)
    private val lists = HashMap<String, SpList>()
    private val listJobs = HashMap<String, Job>()

    /** 一覧(最初のページは、画面の一番下の読み込み中の表示が出たときに読む) */
    fun list(key: String): SpList = lists.getOrPut(key) { SpList() }

    /** 続きのページを読む */
    fun more(key: String, fetch: SpFetch) {
        val l = list(key)
        if (l.loading || !l.hasMore || l.error != null || listJobs[key]?.isActive == true) return
        l.loading = true
        listJobs[key] = viewModelScope.launch {
            val offset = l.items.size
            val cursor = l.cursor
            val r = withContext(Dispatchers.IO) { runCatching { fetch(ctx, offset, cursor) } }
            l.loading = false
            r.onSuccess { page ->
                l.items.addAll(page.items)
                l.cursor = page.cursor
                // 次のページがあると言われても、1 件も増えなければ止める(読み込みを繰り返さないように)
                l.hasMore = page.next && page.items.isNotEmpty()
            }.onFailure { e ->
                if (e is SpotifyForbidden) { l.forbidden = true; l.hasMore = false }
                else l.error = handleError(e)
            }
        }
    }

    fun retry(key: String, fetch: SpFetch) {
        list(key).error = null
        more(key, fetch)
    }

    /** 一覧を最初から読み直す */
    fun reload(key: String, fetch: SpFetch) {
        listJobs[key]?.cancel()
        val l = list(key)
        l.items.clear()
        l.loading = false
        l.error = null
        l.forbidden = false
        l.hasMore = true
        l.cursor = null
        more(key, fetch)
    }

    // ---------------- 検索 ----------------
    var query by mutableStateOf("")
    var search by mutableStateOf<SpSearch?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    private var searchJob: Job? = null

    fun runSearch() {
        val q = query.trim()
        searchJob?.cancel()
        if (q.isEmpty()) { search = null; searchError = null; return }
        searching = true
        searchError = null
        searchJob = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SpotifyApi.search(ctx, q) } }
            searching = false
            r.onSuccess { search = it }.onFailure { searchError = handleError(it) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        query = ""
        search = null
        searchError = null
        searching = false
    }

    /** お気に入りの曲(Liked Songs)を、Spotify アプリで続けて再生するときの URI */
    val likedUri: String? get() = userId.takeIf { it.isNotEmpty() }?.let { "spotify:user:$it:collection" }

    // ---------------- 再生(公式 Spotify アプリ) ----------------
    private var remote: SpotifyAppRemote? = null
    private var stateSub: Subscription<PlayerState>? = null
    private var contextSub: Subscription<com.spotify.protocol.types.PlayerContext>? = null
    private var volumeSub: Subscription<com.spotify.protocol.types.VolumeState>? = null
    var connected by mutableStateOf(false)
        private set
    var connecting by mutableStateOf(false)
        private set
    private val pending = ArrayList<(SpotifyAppRemote) -> Unit>()

    var trackTitle by mutableStateOf("")
        private set
    var trackArtist by mutableStateOf("")
        private set
    var trackUri by mutableStateOf("")
        private set
    var trackAlbum by mutableStateOf("")
        private set
    var trackAlbumUri by mutableStateOf("")
        private set
    var trackArtistUri by mutableStateOf("")
        private set
    /** 再生元(アルバム・プレイリスト・アーティストなど) */
    var contextTitle by mutableStateOf("")
        private set
    var contextUri by mutableStateOf("")
        private set
    /** Spotify アプリの音量(0〜1)。操作できないときは null */
    var volume by mutableStateOf<Float?>(null)
        private set
    var shuffle by mutableStateOf(false)
        private set
    /** Spotify の決まり: 0 = オフ、1 = 1 曲、2 = すべて */
    var repeatMode by mutableStateOf(0)
        private set
    var trackArt by mutableStateOf<ImageBitmap?>(null)
        private set
    private var artUri = ""
    var paused by mutableStateOf(true)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    /** 位置は、届いたときの値と時刻から計算する(再生中は進める) */
    private var basePos = 0L
    private var baseAt = 0L
    var canSkipNext by mutableStateOf(true)
        private set
    var canSkipPrev by mutableStateOf(true)
        private set
    var canSeek by mutableStateOf(true)
        private set

    fun positionMs(): Long =
        if (paused) basePos else (basePos + SystemClock.elapsedRealtime() - baseAt).coerceAtMost(durationMs.coerceAtLeast(basePos))

    val hasTrack: Boolean get() = trackUri.isNotEmpty()

    fun spotifyInstalled(): Boolean = SpotifyAppRemote.isSpotifyInstalled(ctx)

    /**
     * Spotify アプリにつなぐ。初めてのときは、Spotify アプリが連携の許可を求める画面を出すので、
     * 画面(Activity)の Context を渡す
     */
    fun connect(activity: Context, then: ((SpotifyAppRemote) -> Unit)? = null, quiet: Boolean = false) {
        val r = remote
        if (r != null && r.isConnected) { then?.invoke(r); return }
        if (then != null) pending.add(then)
        if (connecting) return
        if (clientId.isEmpty()) return
        connecting = true
        // 操作したときだけ、つないでいることを知らせる(初回は Spotify アプリの許可の画面が出ることもある)
        if (!quiet) message = S(R.string.spotify_connecting)
        val attempt = ++connectAttempt
        Log.i(TAG, "connect #$attempt quiet=$quiet")
        SpotifyAppRemote.setDebugMode(debuggable)
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(SpotifyAuth.REDIRECT_URI)
            .showAuthView(true)
            .build()
        // 返事がないまま待ち続けると、曲を選んでも何も起きなくなるので、時間を区切る
        connectTimeout?.cancel()
        connectTimeout = viewModelScope.launch {
            kotlinx.coroutines.delay(CONNECT_TIMEOUT_MS)
            if (connectAttempt == attempt && connecting) {
                Log.w(TAG, "connect #$attempt timed out")
                connectAttempt++
                failConnect(S(R.string.spotify_connect_timed_out), notify = true)
            }
        }
        SpotifyAppRemote.connect(activity, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                Log.i(TAG, "connect #$attempt connected")
                if (attempt != connectAttempt) {
                    // 時間切れのあとに届いた古い接続は使わない
                    SpotifyAppRemote.disconnect(appRemote)
                    return
                }
                connectTimeout?.cancel()
                connecting = false
                remote = appRemote
                connected = true
                subscribe(appRemote)
                val list = pending.toList()
                pending.clear()
                list.forEach { f -> runCatching { f(appRemote) }.onFailure { Log.w(TAG, "action failed", it) } }
            }

            override fun onFailure(error: Throwable) {
                Log.w(TAG, "connect #$attempt failed", error)
                if (attempt != connectAttempt) return
                connectTimeout?.cancel()
                // タブを開いたときに自動でつなぎにいって失敗しただけ・切れただけのとき(操作していないとき)は、知らせない
                val notify = pending.isNotEmpty() ||
                    !quiet && error !is com.spotify.android.appremote.api.error.SpotifyDisconnectedException
                if (error is UserNotAuthorizedException) needsReauth = true
                failConnect(remoteError(error), notify)
            }
        })
    }

    private var connectAttempt = 0
    private var connectTimeout: Job? = null
    private val debuggable: Boolean
        get() = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun failConnect(msg: String, notify: Boolean) {
        connecting = false
        connected = false
        remote = null
        stateSub = null
        pending.clear()
        if (notify) message = msg
    }

    private fun remoteError(e: Throwable): String {
        Log.w(TAG, "Spotify app error", e)
        return remoteErrorText(e)
    }

    private fun remoteErrorText(e: Throwable): String = when (e) {
        is CouldNotFindSpotifyApp -> S(R.string.spotify_app_is_not_installed)
        is NotLoggedInException -> S(R.string.spotify_log_in_to_the_spotify_app)
        is UserNotAuthorizedException -> S(R.string.spotify_playback_not_authorized)
        else -> S(R.string.spotify_could_not_connect_app, e.message ?: e.javaClass.simpleName)
    }

    private fun subscribe(r: SpotifyAppRemote) {
        contextSub?.cancel()
        contextSub = r.playerApi.subscribeToPlayerContext().setEventCallback { c ->
            contextTitle = c.title.orEmpty()
            contextUri = c.uri.orEmpty()
        }
        volumeSub?.cancel()
        volumeSub = r.connectApi.subscribeToVolumeState().setEventCallback { v ->
            volume = if (v.mControllable) v.mVolume else null
        }
        stateSub?.cancel()
        stateSub = r.playerApi.subscribeToPlayerState()
            .setEventCallback { st -> onState(r, st) }
            .also { sub ->
                sub.setErrorCallback {
                    Log.w(TAG, "player state subscription error", it)
                    connected = r.isConnected
                }
            }
    }

    private fun onState(r: SpotifyAppRemote, st: PlayerState) {
        val t = st.track
        paused = st.isPaused
        basePos = st.playbackPosition
        baseAt = SystemClock.elapsedRealtime()
        st.playbackOptions?.let {
            shuffle = it.isShuffling
            repeatMode = it.repeatMode
        }
        st.playbackRestrictions?.let {
            canSkipNext = it.canSkipNext
            canSkipPrev = it.canSkipPrev
            canSeek = it.canSeek
        }
        if (t == null) {
            trackUri = ""
            return
        }
        trackTitle = t.name.orEmpty()
        trackArtist = t.artists?.mapNotNull { it.name }?.joinToString(", ")?.ifEmpty { null } ?: t.artist?.name.orEmpty()
        trackUri = t.uri.orEmpty()
        trackAlbum = t.album?.name.orEmpty()
        trackAlbumUri = t.album?.uri.orEmpty()
        trackArtistUri = t.artist?.uri ?: t.artists?.firstOrNull()?.uri.orEmpty()
        // 曲が変わったら、お気に入りかどうかを確かめる
        if (trackUri.isNotEmpty() && trackUri !in saved) checkSaved(listOf(trackUri))
        durationMs = t.duration
        val img = t.imageUri
        if (img != null && img.raw != artUri) {
            artUri = img.raw.orEmpty()
            // 再生画面でも使うので、大きな画像を読む
            r.imagesApi.getImage(img, Image.Dimension.LARGE)
                .setResultCallback { bmp -> if (artUri == img.raw) trackArt = bmp?.asImageBitmap() }
        } else if (img == null) {
            artUri = ""
            trackArt = null
        }
    }

    /** つないでから操作する(つながっていなければ、つないでから) */
    private fun withRemote(activity: Context, f: (SpotifyAppRemote) -> Unit) {
        if (!spotifyInstalled()) { message = S(R.string.spotify_app_is_not_installed); return }
        connect(activity, f)
    }

    /** アルバム・プレイリストなどを最初から(index があればその曲から)再生する */
    fun playContext(activity: Context, contextUri: String, index: Int = -1, fallbackTrack: String? = null, shuffle: Boolean? = null) =
        withRemote(activity) { r ->
            // シャッフル再生: 先にシャッフルをオンにしてから再生する
            if (shuffle != null) r.playerApi.setShuffle(shuffle)
            val call = if (index >= 0) r.playerApi.skipToIndex(contextUri, index) else r.playerApi.play(contextUri)
            call.setErrorCallback { e ->
                // 位置を指定した再生に失敗したら、その曲だけを再生する
                if (fallbackTrack != null) r.playerApi.play(fallbackTrack).setErrorCallback { message = remoteError(it) }
                else message = remoteError(e)
            }
        }

    fun playTrack(activity: Context, uri: String) = withRemote(activity) { r ->
        r.playerApi.play(uri).setErrorCallback { message = remoteError(it) }
    }

    fun queue(activity: Context, item: SpItem) = withRemote(activity) { r ->
        r.playerApi.queue(item.uri)
            .setResultCallback { message = S(R.string.spotify_added_to_queue, item.title) }
            .setErrorCallback { message = remoteError(it) }
    }

    fun toggle(activity: Context) = withRemote(activity) { r ->
        (if (paused) r.playerApi.resume() else r.playerApi.pause()).setErrorCallback { message = remoteError(it) }
    }

    fun pause() {
        val r = remote ?: return
        if (r.isConnected && !paused) r.playerApi.pause()
    }

    fun toggleShuffle(activity: Context) = withRemote(activity) { it.playerApi.toggleShuffle() }
    fun toggleRepeat(activity: Context) = withRemote(activity) { it.playerApi.toggleRepeat() }
    fun next(activity: Context) = withRemote(activity) { it.playerApi.skipNext() }
    fun prev(activity: Context) = withRemote(activity) { it.playerApi.skipPrevious() }
    fun seekTo(activity: Context, ms: Long) = withRemote(activity) { r ->
        basePos = ms
        baseAt = SystemClock.elapsedRealtime()
        r.playerApi.seekTo(ms)
    }

    // ---------------- 次に再生される曲 ----------------
    val upNext = mutableStateListOf<SpItem>()
    var upNextLoading by mutableStateOf(false)
        private set
    var upNextError by mutableStateOf<String?>(null)
        private set
    private var upNextJob: Job? = null

    /** 次に再生される曲を読む(Spotify アプリの再生待ち。キューに追加した曲と、アルバムなどの続き) */
    fun loadUpNext() {
        upNextJob?.cancel()
        upNextLoading = true
        upNextError = null
        upNextJob = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SpotifyApi.queue(ctx) } }
            upNextLoading = false
            r.onSuccess { q ->
                upNext.clear()
                upNext.addAll(q.next)
            }.onFailure { upNextError = handleError(it) }
        }
    }

    /**
     * 次に再生される曲の index 番目まで飛ぶ(Spotify には指定の曲へ飛ぶ操作がないので、次の曲へを繰り返す)
     */
    fun skipTo(activity: Context, index: Int) = withRemote(activity) { r ->
        val n = (index + 1).coerceIn(1, 50)
        fun step(left: Int) {
            if (left <= 0) { viewModelScope.launch { kotlinx.coroutines.delay(400); loadUpNext() }; return }
            r.playerApi.skipNext()
                .setResultCallback { step(left - 1) }
                .setErrorCallback { message = remoteError(it) }
        }
        step(n)
    }

    // ---------------- お気に入り(ライブラリへの保存・フォロー) ----------------
    /** ライブラリに入っているか(URI ごと。確かめたものだけ) */
    val saved = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    fun checkSaved(uris: List<String>) {
        val need = uris.filter { it.isNotEmpty() && it !in saved }.distinct()
        if (need.isEmpty() || !loggedIn) return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SpotifyApi.contains(ctx, need) } }
            r.onSuccess { list -> need.forEachIndexed { i, u -> saved[u] = list.getOrElse(i) { false } } }
                .onFailure { Log.w(TAG, "contains failed", it) }
        }
    }

    /**
     * お気に入りに入れる・外す。曲は「お気に入りの曲」、アルバムとプレイリストは保存、アーティストはフォロー。
     * アーティストは Web API では扱えないので、Spotify アプリに頼む
     */
    fun setSaved(activity: Context, uri: String, kind: SpKind, title: String, on: Boolean) {
        val before = saved[uri]
        saved[uri] = on
        val done = S(if (on) R.string.spotify_saved_to_library else R.string.spotify_removed_from_library, title)
        if (kind == SpKind.ARTIST) {
            withRemote(activity) { r ->
                (if (on) r.userApi.addToLibrary(uri) else r.userApi.removeFromLibrary(uri))
                    .setResultCallback { message = done }
                    .setErrorCallback { e -> if (before == null) saved.remove(uri) else saved[uri] = before; message = remoteError(e) }
            }
            return
        }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { if (on) SpotifyApi.save(ctx, uri) else SpotifyApi.unsave(ctx, uri) } }
            r.onSuccess {
                message = done
                // 一覧(お気に入りの曲・保存したアルバムなど)は、次に開いたときに読み直す
                lists.remove(if (kind == SpKind.TRACK) "liked" else if (kind == SpKind.ALBUM) "albums" else "playlists")
                if (kind == SpKind.PLAYLIST) myPlaylistsCache = null
            }.onFailure { e ->
                if (before == null) saved.remove(uri) else saved[uri] = before
                message = handleError(e)
            }
        }
    }

    // ---------------- プレイリストの編集 ----------------
    /** 「プレイリストに追加」で選んだ曲(追加先を選ぶダイアログを出している間だけ) */
    var pickPlaylistFor by mutableStateOf<List<String>?>(null)

    private var myPlaylistsCache: List<SpItem>? = null
    /** 曲を追加できるプレイリスト(自分が作成したもの・共同編集のもの) */
    val editablePlaylists = mutableStateListOf<SpItem>()
    var editableLoading by mutableStateOf(false)
        private set

    fun loadEditablePlaylists() {
        myPlaylistsCache?.let { cached ->
            editablePlaylists.clear(); editablePlaylists.addAll(cached); return
        }
        if (editableLoading) return
        editableLoading = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val all = ArrayList<SpItem>()
                    var offset = 0
                    while (offset < 1000) {
                        val page = SpotifyApi.myPlaylists(ctx, offset)
                        all.addAll(page.items)
                        if (!page.next || page.items.isEmpty()) break
                        offset += page.items.size
                    }
                    all.filter { it.ownerId == userId || it.collaborative }
                }
            }
            editableLoading = false
            r.onSuccess { list ->
                myPlaylistsCache = list
                editablePlaylists.clear()
                editablePlaylists.addAll(list)
            }.onFailure { message = handleError(it) }
        }
    }

    /** 自分のプレイリストか(曲を追加・削除できるか) */
    fun canEdit(playlist: SpItem): Boolean = playlist.ownerId == userId || playlist.collaborative

    fun addToPlaylist(playlist: SpItem, uris: List<String>) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SpotifyApi.addToPlaylist(ctx, playlist.id, uris) } }
            r.onSuccess {
                message = S(R.string.spotify_added_to_playlist, playlist.title)
                lists.remove("playlist:${playlist.id}")
            }.onFailure { message = handleError(it) }
        }
    }

    fun createPlaylistAndAdd(name: String, uris: List<String>) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val p = SpotifyApi.createPlaylist(ctx, name, public = false) ?: throw java.io.IOException("create failed")
                    if (uris.isNotEmpty()) SpotifyApi.addToPlaylist(ctx, p.id, uris)
                    p
                }
            }
            r.onSuccess { p ->
                message = S(R.string.spotify_created_playlist, p.title)
                myPlaylistsCache = null
                lists.remove("playlists")
            }.onFailure { message = handleError(it) }
        }
    }

    /** プレイリストから曲を外す(Spotify では、同じ曲が何度入っていても、すべて外れる) */
    fun removeFromPlaylist(playlist: SpItem, track: SpItem) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SpotifyApi.removeFromPlaylist(ctx, playlist.id, track.uri) } }
            r.onSuccess {
                message = S(R.string.spotify_removed_from_playlist, track.title, playlist.title)
                // 位置で再生を始めるので、一覧は読み直す
                lists.remove("playlist:${playlist.id}")
                val top = stack.lastOrNull()
                if (top is SpRoute.Playlist && top.item.id == playlist.id) { stack.removeAt(stack.lastIndex); stack.add(top) }
            }.onFailure { message = handleError(it) }
        }
    }

    // ---------------- 再生先(Spotify Connect) ----------------
    val devices = mutableStateListOf<SpDevice>()
    var devicesLoading by mutableStateOf(false)
        private set

    fun loadDevices() {
        devicesLoading = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SpotifyApi.devices(ctx) } }
            devicesLoading = false
            r.onSuccess { devices.clear(); devices.addAll(it) }.onFailure { message = handleError(it) }
        }
    }

    fun transferTo(device: SpDevice) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SpotifyApi.transfer(ctx, device.id) } }
            r.onSuccess {
                message = S(R.string.spotify_now_playing_on, device.name)
                kotlinx.coroutines.delay(800)
                loadDevices()
            }.onFailure { message = handleError(it) }
        }
    }

    /** この端末(の Spotify アプリ)で再生する */
    fun playOnThisPhone(activity: Context) = withRemote(activity) { r ->
        r.connectApi.connectSwitchToLocalDevice()
            .setResultCallback { viewModelScope.launch { kotlinx.coroutines.delay(800); loadDevices() } }
            .setErrorCallback { message = remoteError(it) }
    }

    /** 音量(0〜1)。Spotify アプリで操作できればアプリに、できなければ再生中の機器に Web API で伝える */
    fun setVolume(activity: Context, v: Float) {
        val r = remote
        if (r != null && r.isConnected && volume != null) {
            volume = v
            r.connectApi.connectSetVolume(v)
            return
        }
        val dev = devices.firstOrNull { it.active } ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { SpotifyApi.setVolume(ctx, (v * 100).toInt(), dev.id) } }
                .onFailure { message = handleError(it) }
        }
    }

    // ---------------- 再生中の曲から、アルバムやアーティストのページへ ----------------
    /** URI(spotify:album:… など)のページを開く。名前や画像は Web API で取得する */
    fun openUri(uri: String, fallbackTitle: String = "") {
        val id = SpotifyApi.idOf(uri)
        val kind = when {
            uri.startsWith("spotify:album:") -> SpKind.ALBUM
            uri.startsWith("spotify:artist:") -> SpKind.ARTIST
            uri.startsWith("spotify:playlist:") -> SpKind.PLAYLIST
            uri.contains(":collection") -> { push(SpRoute.Liked); return }
            else -> return
        }
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) {
                runCatching {
                    when (kind) {
                        SpKind.ALBUM -> SpotifyApi.album(ctx, id)
                        SpKind.ARTIST -> SpotifyApi.artist(ctx, id)
                        else -> SpotifyApi.playlist(ctx, id)
                    }
                }.getOrNull()
            } ?: SpItem(kind, id, uri, fallbackTitle, "", null)
            push(
                when (kind) {
                    SpKind.ALBUM -> SpRoute.Album(item)
                    SpKind.ARTIST -> SpRoute.Artist(item)
                    else -> SpRoute.Playlist(item)
                },
            )
        }
    }

    fun disconnect() {
        stateSub?.cancel()
        stateSub = null
        contextSub?.cancel()
        contextSub = null
        volumeSub?.cancel()
        volumeSub = null
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        connected = false
        connecting = false
        connectAttempt++
        connectTimeout?.cancel()
        pending.clear()
    }

    companion object {
        private const val TAG = "UCRT-Spotify"
        private const val CONNECT_TIMEOUT_MS = 30_000L
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}
