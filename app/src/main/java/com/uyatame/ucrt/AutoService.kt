package com.uyatame.ucrt

import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import com.uyatame.ucrt.library.LibTrack

/**
 * Android Auto(試験的)。車の画面に、ライブラリ(アルバム・アーティスト・プレイリスト・お気に入り)を並べ、選んだ曲を再生する。
 * 再生と操作は、いつもの再生(PlayerController の MediaSession)をそのまま使う。
 * 再生の部品は画面(MainViewModel)が持っているので、スマートフォンで UCRT を起動しているときに使える
 * (起動していなければ、起動するよう案内を出す)。設定の「Android Auto(試験的)」をオフにすると、車には何も出さない
 */
class AutoService : MediaBrowserService() {
    private val main = Handler(Looper.getMainLooper())
    private var tokenSet = false

    override fun onCreate() {
        super.onCreate()
        current = this
        attach()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    /** 再生の部品があれば、その MediaSession を車に渡す(1 回だけ) */
    internal fun attach() {
        if (tokenSet) return
        val vm = AutoBridge.vm ?: return
        runCatching { sessionToken = vm.player.session.sessionToken; tokenSet = true }
        notifyChildrenChanged(ROOT)
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        // ライブラリの中身を見せるのは、Android Auto(と、このアプリ自身)だけ
        if (clientPackageName !in ALLOWED && clientPackageName != packageName) return null
        if (AutoBridge.enabled == false) return null
        return BrowserRoot(ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val vm = AutoBridge.vm
        if (vm == null) {
            // 再生の部品が無い(スマートフォンで UCRT を起動していない)
            result.sendResult(mutableListOf(item(NONE, S(R.string.auto_open_app), S(R.string.auto_open_app_desc), browsable = false, playable = false)))
            return
        }
        result.detach()
        // ライブラリを読むのは画面のスレッドで(一覧は画面と同じものを使う)
        main.post { result.sendResult(children(vm, parentId).toMutableList()) }
    }

    private fun item(id: String, title: String, sub: String?, browsable: Boolean, playable: Boolean): MediaBrowser.MediaItem {
        val d = MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build()
        var flags = 0
        if (browsable) flags = flags or MediaBrowser.MediaItem.FLAG_BROWSABLE
        if (playable) flags = flags or MediaBrowser.MediaItem.FLAG_PLAYABLE
        return MediaBrowser.MediaItem(d, flags)
    }

    private fun trackItems(prefix: String, list: List<LibTrack>): List<MediaBrowser.MediaItem> =
        list.take(MAX).mapIndexed { i, t -> item("t|$prefix|$i", t.title, t.artist, browsable = false, playable = true) }

    private fun children(vm: MainViewModel, id: String): List<MediaBrowser.MediaItem> = when {
        id == ROOT -> listOf(
            item(SHUFFLE, S(R.string.shuffle_all), null, browsable = false, playable = true),
            item(ALBUMS, S(R.string.albums), null, browsable = true, playable = false),
            item(ARTISTS, S(R.string.artists), null, browsable = true, playable = false),
            item(PLAYLISTS, S(R.string.playlists), null, browsable = true, playable = false),
            item(FAVORITES, S(R.string.favorites), null, browsable = true, playable = false),
        )
        id == ALBUMS -> vm.albums.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() })).take(MAX)
            .map { item("album:" + it.key, it.title, it.artist, browsable = true, playable = false) }
        id == ARTISTS -> vm.albums.map { it.artist }.distinct().sortedBy { it.lowercase() }.take(MAX)
            .map { item("artist:$it", it, null, browsable = true, playable = false) }
        id == PLAYLISTS -> vm.store.playlists.map { item("playlist:" + it.id, it.name, null, browsable = true, playable = false) }
        id == FAVORITES -> trackItems("fav", favTracks(vm))
        id.startsWith("album:") || id.startsWith("artist:") || id.startsWith("playlist:") -> trackItems(id, tracksOf(vm, id))
        else -> emptyList()
    }

    companion object {
        @Volatile internal var current: AutoService? = null

        const val ROOT = "root"
        const val NONE = "none"
        const val SHUFFLE = "shuffle"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val PLAYLISTS = "playlists"
        const val FAVORITES = "favorites"
        /** 1 つの一覧に出す数の上限(車の画面が重くならないように) */
        const val MAX = 500

        /** ライブラリを見せてよい相手(Android Auto と、Google アシスタント) */
        private val ALLOWED = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.carassistant",
            "com.google.android.googlequicksearchbox",
        )

        private fun favTracks(vm: MainViewModel): List<LibTrack> {
            val map = vm.trackMap
            return vm.store.favTracks.mapNotNull { map[it] }
        }

        /** 一覧の ID(album:キー・artist:名前・playlist:ID・fav)の曲と、次のリストへ進むための出どころ */
        internal fun tracksOf(vm: MainViewModel, id: String): List<LibTrack> = when {
            id.startsWith("album:") -> vm.albumByKey(id.removePrefix("album:"))?.tracks.orEmpty()
            id.startsWith("artist:") -> vm.artistTracks(id.removePrefix("artist:"))
            id.startsWith("playlist:") -> vm.store.playlists.firstOrNull { it.id == id.removePrefix("playlist:") }?.let { vm.playlistTracks(it) }.orEmpty()
            id == "fav" -> favTracks(vm)
            else -> emptyList()
        }

        private fun sourceOf(id: String): MainViewModel.PlaySource? = when {
            id.startsWith("album:") -> MainViewModel.PlaySource(MainViewModel.PlaySource.ALBUM, id.removePrefix("album:"))
            id.startsWith("artist:") -> MainViewModel.PlaySource(MainViewModel.PlaySource.ARTIST, id.removePrefix("artist:"))
            id.startsWith("playlist:") -> MainViewModel.PlaySource(MainViewModel.PlaySource.PLAYLIST, id.removePrefix("playlist:"))
            else -> null
        }

        /** 車の画面で選んだものを再生する(メインのスレッドで) */
        internal fun play(vm: MainViewModel, mediaId: String) {
            if (mediaId == SHUFFLE) { vm.shuffleAll(); return }
            if (!mediaId.startsWith("t|")) return
            val parts = mediaId.removePrefix("t|")
            val list = parts.substringBeforeLast('|')
            val i = parts.substringAfterLast('|').toIntOrNull() ?: 0
            val tracks = tracksOf(vm, list)
            if (tracks.isNotEmpty()) vm.playTracks(tracks, i.coerceIn(0, tracks.lastIndex), source = sourceOf(list))
        }

        /** 声で探したもの(曲名・アルバム名・アーティスト名。空なら全曲シャッフル)を再生する */
        internal fun search(vm: MainViewModel, query: String) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) { vm.shuffleAll(); return }
            vm.albums.firstOrNull { it.title.lowercase() == q }?.let { vm.playAlbum(it, 0); return }
            vm.albums.map { it.artist }.firstOrNull { it.lowercase() == q }?.let { a ->
                vm.playTracks(vm.artistTracks(a), 0, source = MainViewModel.PlaySource(MainViewModel.PlaySource.ARTIST, a)); return
            }
            val all = vm.albums.flatMap { it.tracks }
            val hit = all.firstOrNull { it.title.lowercase() == q } ?: all.firstOrNull { it.title.lowercase().contains(q) }
            if (hit != null) {
                vm.albumByKey(vm.albumKeyOf(hit.uri) ?: "")?.let { a -> vm.playAlbum(a, a.tracks.indexOf(hit).coerceAtLeast(0)); return }
                vm.playTracks(listOf(hit), 0)
                return
            }
            vm.albums.firstOrNull { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }?.let { vm.playAlbum(it, 0) }
        }
    }
}

/** Android Auto(AutoService)と、再生の部品を持つ画面(MainViewModel)のつなぎ */
object AutoBridge {
    @Volatile var vm: MainViewModel? = null
        private set
    /** 設定の「Android Auto(試験的)」 */
    @Volatile var enabled: Boolean? = null

    fun register(v: MainViewModel) {
        vm = v
        v.player.onPlayMediaId = { id -> AutoService.play(v, id) }
        v.player.onPlaySearch = { q -> AutoService.search(v, q) }
        AutoService.current?.attach()
    }

    fun unregister(v: MainViewModel) {
        if (vm === v) vm = null
        v.player.onPlayMediaId = null
        v.player.onPlaySearch = null
    }
}
