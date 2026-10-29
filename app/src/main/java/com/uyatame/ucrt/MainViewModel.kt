package com.uyatame.ucrt

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.AudioFormat
import com.uyatame.ucrt.data.Keys
import com.uyatame.ucrt.data.QualityPreset
import com.uyatame.ucrt.data.SettingsRepository
import com.uyatame.ucrt.data.outputMode
import com.uyatame.ucrt.data.eqActive
import com.uyatame.ucrt.data.eqBandList
import com.uyatame.ucrt.data.eqCurrentBands
import com.uyatame.ucrt.data.darEffective
import com.uyatame.ucrt.data.rgEffective
import com.uyatame.ucrt.tags.TagEditor
import com.uyatame.ucrt.tags.TagValues
import com.uyatame.ucrt.tags.prepareCoverImage
import com.uyatame.ucrt.library.ArtLoader
import com.uyatame.ucrt.library.LibAlbum
import com.uyatame.ucrt.library.Library
import com.uyatame.ucrt.library.UNKNOWN_ALBUM
import com.uyatame.ucrt.library.UNKNOWN_ARTIST
import com.uyatame.ucrt.meta.AlbumMeta
import com.uyatame.ucrt.meta.Cover
import com.uyatame.ucrt.meta.MusicBrainz
import com.uyatame.ucrt.meta.ReleaseCandidate
import com.uyatame.ucrt.meta.TrackMeta
import com.uyatame.ucrt.player.PlayItem
import com.uyatame.ucrt.player.PlayerController
import com.uyatame.ucrt.rip.AacSink
import com.uyatame.ucrt.rip.AudioSink
import com.uyatame.ucrt.rip.CdRipper
import com.uyatame.ucrt.rip.FlacSink
import com.uyatame.ucrt.rip.Id3Tags
import com.uyatame.ucrt.rip.Mp3Sink
import com.uyatame.ucrt.rip.Mp4Tagger
import com.uyatame.ucrt.rip.Mp4Tags
import com.uyatame.ucrt.rip.RipOptions
import com.uyatame.ucrt.rip.WavSink
import com.uyatame.ucrt.usb.ScsiException
import com.uyatame.ucrt.usb.TocTrack
import com.uyatame.ucrt.usb.UsbScsiDrive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** 操作ボタン付きのお知らせ */
sealed class ActionSnack(val text: String, val action: String) {
    class OpenAlbum(text: String, val album: String, val artist: String) : ActionSnack(text, S(R.string.view_in_library))
    class UndoMeta(text: String) : ActionSnack(text, S(R.string.undo))
}

fun fmtTime(sectors: Int): String = "%d:%02d".format(sectors / 75 / 60, sectors / 75 % 60)
fun fmtMs(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application = app
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val repo = SettingsRepository(app)
    /** 前に決めた CD の曲情報(ディスク ID ごと) */
    private val discCache = com.uyatame.ucrt.meta.DiscCache(app)
    /** 今入っている CD のディスク ID */
    private var currentDiscId: String? = null
    val settings = repo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val player = PlayerController(app, viewModelScope)
    private val library = Library(app)
    /** お気に入り・プレイリスト・再生履歴 */
    val store = com.uyatame.ucrt.data.PlayerStore(app)

    /**
     * ライブラリの画面の行き先(空ならトップ)。
     * "cat:albums" などのカテゴリー、"album:キー"、"artist:名前"、"genre:名前"、"year:年"、"playlist:ID"、"search"
     */
    val libStack = androidx.compose.runtime.mutableStateListOf<String>()

    fun libPush(route: String) {
        if (libStack.lastOrNull() != route) libStack.add(route)
    }

    fun libPop() {
        if (libStack.isNotEmpty()) libStack.removeAt(libStack.lastIndex)
    }

    fun libReplaceTop(route: String) {
        if (libStack.isEmpty()) libStack.add(route) else libStack[libStack.lastIndex] = route
    }

    fun libHome() = libStack.clear()

    /** 再生画面などからライブラリの画面を開くとき、ライブラリのタブへ切り替えるための合図 */
    var libNavEvent by mutableIntStateOf(0)
        private set

    fun openRoute(route: String) {
        libPush(route)
        libNavEvent++
    }

    // ---- 曲を選ぶ(まとめてプレイリストに入れる) ----

    /** 曲を選んでいるか(選んでいる間は、ライブラリの曲をタップすると選ぶ) */
    var selecting by mutableStateOf(false)
        private set
    /** 選んだ曲(選んだ順。プレイリストにもこの順で入れる) */
    val selectedSongs = androidx.compose.runtime.mutableStateListOf<String>()
    /** 今のページで「すべて選択」の対象になる曲(そのページが出している間だけ) */
    var selectionPool by mutableStateOf<List<com.uyatame.ucrt.library.LibTrack>?>(null)

    /** 選ぶのを始める(first: 最初から選んでおく曲)。再生画面から始めたときも、ライブラリの画面に切り替える */
    fun startSelection(first: String?) {
        selecting = true
        if (first != null && first !in selectedSongs) selectedSongs.add(first)
        libNavEvent++
    }

    fun isSelected(uri: String) = uri in selectedSongs
    fun toggleSelected(uri: String) { if (!selectedSongs.remove(uri)) selectedSongs.add(uri) }
    fun selectAll(list: List<com.uyatame.ucrt.library.LibTrack>) { list.forEach { if (it.uri !in selectedSongs) selectedSongs.add(it.uri) } }
    fun unselectAll(list: List<com.uyatame.ucrt.library.LibTrack>) {
        val drop = list.mapTo(HashSet()) { it.uri }
        selectedSongs.removeAll { it in drop }
    }

    fun endSelection() {
        selecting = false
        selectedSongs.clear()
    }

    /** 選んだ曲をプレイリストに入れる(新しく作るか、あるプレイリストを選ぶ) */
    fun addSelectedToPlaylist() {
        if (selectedSongs.isEmpty()) return
        playlistPick = selectedSongs.toList()
        endSelection()
    }

    // ---- どの画面からでも開くメニュー ----
    /** 曲の操作メニュー */
    var menuTrack by mutableStateOf<com.uyatame.ucrt.library.LibTrack?>(null)
    /** プレイリストの中の曲のメニューのとき(プレイリスト ID, 位置) */
    var menuTrackPlaylist by mutableStateOf<Pair<String, Int>?>(null)
    /** アルバムの操作メニュー */
    var menuAlbum by mutableStateOf<LibAlbum?>(null)
    /** プレイリストに追加する曲(選ぶ画面を出す) */
    var playlistPick by mutableStateOf<List<String>?>(null)
    /** 曲の詳細情報を出す曲 */
    var infoItem by mutableStateOf<PlayItem?>(null)

    fun showTrackMenu(t: com.uyatame.ucrt.library.LibTrack, playlist: Pair<String, Int>? = null) {
        menuTrackPlaylist = playlist
        menuTrack = t
    }

    // ---- ドライブ / ディスク ----
    var status by mutableStateOf(S(R.string.no_drive_connected_2))
        private set
    var connected by mutableStateOf(false)
        private set
    var driveName by mutableStateOf("")
        private set
    var discText by mutableStateOf("")
        private set
    var discBusy by mutableStateOf(false)
        private set
    var tracks by mutableStateOf(listOf<TocTrack>())
        private set
    private var leadOut = 0

    // ---- 曲情報 ----
    var meta by mutableStateOf<AlbumMeta?>(null)
        private set
    var metaStatus by mutableStateOf("")
        private set
    var candidates by mutableStateOf<List<ReleaseCandidate>>(emptyList())
        private set
    /** 候補選択ダイアログの表示 */
    var showChooser by mutableStateOf(false)
    var metaSearching by mutableStateOf(false)
        private set
    /** 直近の取得エラーの詳細(画面に表示) */
    var metaError by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<ReleaseCandidate>>(emptyList())
        private set
    var searchBusy by mutableStateOf(false)
        private set
    var searchMessage by mutableStateOf("")
        private set
    var applyingCandidate by mutableStateOf(false)
        private set
    private var metaJob: Job? = null
    var cdCover by mutableStateOf<ImageBitmap?>(null)
        private set

    var tagSaving by mutableStateOf(false)
        private set
    /** 設定の「タグの編集」で選んだファイル(アルバムごとにまとめたもの。null = 読み込み中・まだ選んでいない) */
    var fileTagAlbums by mutableStateOf<List<LibAlbum>?>(null)
    private var fileTagUris: List<Uri> = emptyList()

    /** 設定の「タグの編集」: 選んだファイルのタグを読み、アルバムごとにまとめる(ライブラリと同じ編集画面で開く) */
    fun openFilesForTags(uris: List<Uri>) {
        if (uris.isNotEmpty()) fileTagUris = uris
        val list = fileTagUris
        fileTagAlbums = null
        viewModelScope.launch {
            val scan = com.uyatame.ucrt.library.TagScan(ctx, { ctx.contentResolver.openInputStream(it) }, { null })
            val tracks = withContext(Dispatchers.IO) {
                list.mapNotNull { u ->
                    runCatching {
                        val name = ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: u.lastPathSegment.orEmpty().substringAfterLast('/')
                        scan.readTags(u, name, "", null, 0L)
                    }.onFailure { log(S(R.string.cannot_read_file) + ": $u (${it.message})") }.getOrNull()
                }
            }
            fileTagAlbums = tracks.groupBy { it.album to it.albumArtist.ifEmpty { it.artist } }.map { (k, ts) ->
                val sorted = ts.sortedWith(compareBy({ it.disc }, { it.track }))
                LibAlbum("file:" + sorted.first().uri, k.first, k.second, sorted)
            }
        }
    }

    /** 「タグの編集」を閉じる */
    fun closeFileTags() { fileTagAlbums = null; fileTagUris = emptyList() }

    /** 新しい音楽CDを読み込むたびに増える(画面の自動切り替え用) */
    var discEvent by mutableIntStateOf(0)
        private set
    private var coverBytes: ByteArray? = null

    // ---- 取り込み ----
    var busy by mutableStateOf(false)
        private set
    var ripIndex by mutableIntStateOf(0)
        private set
    var ripCount by mutableIntStateOf(0)
        private set
    var ripOverall by mutableFloatStateOf(0f)
        private set
    /** 取り込み開始時刻(残り時間の計算用) */
    var ripStartMs by mutableLongStateOf(0L)
        private set
    var actionSnack by mutableStateOf<ActionSnack?>(null)
    private var undoState: Triple<AlbumMeta?, ByteArray?, ImageBitmap?>? = null
    private var notifPct = -1
    val selected = mutableStateMapOf<Int, Boolean>()
    val progress = mutableStateMapOf<Int, Float>()
    val trackStatus = mutableStateMapOf<Int, String>()

    // ---- ライブラリ ----
    var albums by mutableStateOf<List<LibAlbum>>(emptyList())
        private set
    /** 読み込んだすべての曲(CUE シートで分けたため一覧に出さない元のファイルも含む。次の読み込みで使う) */
    private var libAll: List<com.uyatame.ucrt.library.LibTrack> = emptyList()

    private class LibIndex(
        val tracks: Map<String, com.uyatame.ucrt.library.LibTrack>,
        val albumOf: Map<String, String>,
        val albums: Map<String, LibAlbum>,
    )
    private var indexFor: List<LibAlbum>? = null
    private var indexCache = LibIndex(emptyMap(), emptyMap(), emptyMap())

    private fun libIndex(): LibIndex {
        val a = albums
        if (a !== indexFor) {
            val t = HashMap<String, com.uyatame.ucrt.library.LibTrack>()
            val k = HashMap<String, String>()
            for (al in a) for (tr in al.tracks) { t[tr.uri] = tr; k[tr.uri] = al.key }
            indexCache = LibIndex(t, k, a.associateBy { it.key })
            indexFor = a
        }
        return indexCache
    }

    /** URI から曲を引く */
    val trackMap: Map<String, com.uyatame.ucrt.library.LibTrack> get() = libIndex().tracks

    /** 曲の URI から、その曲のアルバムのキーを引く */
    fun albumKeyOf(uri: String?): String? = uri?.let { libIndex().albumOf[it] }

    fun albumByKey(key: String?): LibAlbum? = key?.let { libIndex().albums[it] }
    var libScanning by mutableStateOf(false)
        private set
    /** すべての曲を読み直しているか(false なら新しい曲だけ) */
    var libFull by mutableStateOf(false)
        private set
    /** 今回タグを読む曲の数 */
    var libNew by mutableIntStateOf(0)
        private set
    var libProgress by mutableStateOf("")
        private set

    var logText by mutableStateOf("")
        private set
    var snack by mutableStateOf<String?>(null)

    /** アプリの画面に戻ってくるたびに増える(ほかのアプリの設定画面から戻ったときに、状態を確かめ直すため) */
    var resumeTick by mutableIntStateOf(0)

    /** ほかの音楽アプリの曲の再生画面を開いているか */
    var externalPlayerOpen by mutableStateOf(false)

    val audioTracks: List<TocTrack> get() = tracks.filter { it.isAudio }
    val albumTitle: String get() = meta?.title ?: UNKNOWN_ALBUM
    val albumArtist: String get() = meta?.artist ?: UNKNOWN_ARTIST

    fun trackTitle(t: TocTrack): String =
        meta?.tracks?.getOrNull(audioTracks.indexOf(t))?.title?.takeIf { it.isNotBlank() } ?: S(R.string.track_2).format(t.number)

    fun trackArtist(t: TocTrack): String =
        meta?.tracks?.getOrNull(audioTracks.indexOf(t))?.artist?.takeIf { it.isNotBlank() } ?: albumArtist

    private var drive: UsbScsiDrive? = null
    private var job: Job? = null
    private val permAction = "com.uyatame.ucrt.USB_PERMISSION"

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Android 12 でも動くよう、互換ライブラリ経由で取り出す
            val dev = androidx.core.content.IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) openDevice(dev)
            else status = S(R.string.usb_access_was_denied)
        }
    }
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // 外れたのが使っているドライブでなければ(USB DAC や USB メモリなど)、何もしない
            val dev = androidx.core.content.IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val d = drive ?: return
            if (dev != null && dev.deviceName != d.deviceName) return
            closeDrive(S(R.string.drive_was_disconnected))
        }
    }

    init {
        com.uyatame.ucrt.library.StorageAccess.init(app)
        ContextCompat.registerReceiver(app, permReceiver, IntentFilter(permAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            app, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        MediaBridge.player = player
        MediaBridge.quit = { quitApp(null) }
        // 起動したら、新しい版が出ていないか確かめる(設定を読み込んで、画面が落ち着いてから)
        viewModelScope.launch {
            if (repo.flow.first().updateCheck) {
                kotlinx.coroutines.delay(2000)
                checkUpdate(manual = false)
            }
        }
        // 再生エンジンの診断メッセージをアプリのログに出す
        com.uyatame.ucrt.player.AudioEngine.logger = { msg -> log(msg) }
        UpsampleJobs.log = { msg -> log(msg) }
        UpsampleJobs.onFinished = { refreshLibrary() }
        // 音量キーで DAC の音量を変えたら保存する
        com.uyatame.ucrt.player.AudioEngine.onUsbVolume = { v -> set(com.uyatame.ucrt.data.Keys.usbVolume, v) }
        // 保存されている音響設定を再生エンジンに反映し続ける
        viewModelScope.launch {
            var lastMode: Int? = null
            var lastEq: Boolean? = null
            // 設定はどれか 1 つ変わるたびに全部届く(音量キーで DAC の音量を変えたときなども)ので、変わったものだけ反映する
            var lastEqState: Any? = null
            var lastExternal: Boolean? = null
            settings.collect { s ->
                val eqState = listOf(s.eqActive, s.eqCurrentBands, s.eqPreamp, s.eqAutoPreamp)
                if (eqState != lastEqState) {
                    lastEqState = eqState
                    com.uyatame.ucrt.player.AudioEngine.setEq(s.eqActive, s.eqCurrentBands, s.eqPreamp, s.eqAutoPreamp)
                }
                val mode = s.outputMode
                com.uyatame.ucrt.player.AudioEngine.setOutputMode(mode)
                // ビットパーフェクト再生がオンなら、USB DAC の使用許可を先にもらっておく
                if (mode == 2 && lastMode != 2) com.uyatame.ucrt.player.usb.UsbDac.requestPermissionIfNeeded()
                player.setUsbVolume(s.usbVolume)
                // (許可の変化は、画面に戻ってきたとき(MainActivity.onResume)に確かめる)
                if (s.externalMedia != lastExternal) {
                    lastExternal = s.externalMedia
                    com.uyatame.ucrt.player.ExternalMedia.setEnabled(ctx, s.externalMedia)
                }
                val keep = com.uyatame.ucrt.player.DacKeepAlive
                keep.target = s.dacKeepAliveTarget
                if (s.dacKeepAlive && !keep.running) keep.start(ctx) else if (!s.dacKeepAlive && keep.running) keep.stop(ctx)
                player.usbVolumeStep = s.usbVolumeStep
                player.autoResume = s.autoResume
                AutoBridge.enabled = s.androidAuto
                val stab = com.uyatame.ucrt.player.usb.UsbStabilizer
                if (stab.enabled != s.usbStabilize) {
                    stab.enabled = s.usbStabilize
                    // 転送している間に切り替えたら、掛け直す(オフでも CPU を起こしておくのは続ける)
                    stab.stop()
                    if (com.uyatame.ucrt.player.usb.UsbDac.streaming) stab.start(ctx)
                }
                com.uyatame.ucrt.player.AudioEngine.appVolume =
                    if (s.fineVolume) com.uyatame.ucrt.player.AudioEngine.appVolumeOf(s.appVolumeStep) else 1f
                player.gapless = s.gapless
                val rg = com.uyatame.ucrt.player.ReplayGain
                val rgMode = s.rgEffective
                // 使う・使わない、曲ごと・アルバムごとを変えたら、今の曲の値を読み直す(足す量などはすぐに効く)
                val rgReload = rg.mode != rgMode && (rg.mode == 0 || rgMode == 0)
                rg.mode = rgMode
                rg.preampDb = s.rgPreamp.toFloat()
                rg.preventClipping = s.rgNoClip
                val dsdChanged = com.uyatame.ucrt.player.AudioEngine.dsdMode != s.dsdMode ||
                    com.uyatame.ucrt.player.AudioEngine.dsdSwap != s.dsdSwap
                com.uyatame.ucrt.player.AudioEngine.dsdMode = s.dsdMode
                com.uyatame.ucrt.player.AudioEngine.dsdSwap = s.dsdSwap
                val devChanged = com.uyatame.ucrt.player.AudioEngine.preferredKey != s.outputDevice
                com.uyatame.ucrt.player.AudioEngine.preferredKey = s.outputDevice
                val eng = com.uyatame.ucrt.player.AudioEngine
                val dar = s.darEffective
                val darChanged = eng.darMode != dar || eng.darPcm != s.darPcm || eng.darDsd != s.darDsd || eng.darInteger != s.darInteger
                // DSD の周波数を選び直したら、自動で下げた上限は解除する
                if (eng.darDsd != s.darDsd) eng.darDsdLimit = 0
                eng.darMode = dar
                eng.darPcm = s.darPcm
                eng.darDsd = s.darDsd
                eng.darInteger = s.darInteger
                // DAR は PCM の曲に使う(PCM アップサンプリングは通常の出力でも)。DAR の DSD 変換は DSD の出力の設定も使う。
                // USB DAC 直接出力でイコライザーを入り切りしたときも、出力の状態(ビットパーフェクトか)を作り直す
                val eqChanged = lastEq != null && lastEq != s.eqActive
                val darReload = !player.isDsd && (darChanged || (mode == 2 && (eqChanged || (dsdChanged && dar == 2))))
                if (lastMode != null && (lastMode != mode || (dsdChanged && player.isDsd) || (devChanged && mode != 2) || darReload || rgReload)) player.reloadOutput()
                lastMode = mode
                lastEq = s.eqActive
            }
        }
        // 以前の版で保存した連絡先メールアドレスは不要になったので消す
        viewModelScope.launch { repo.set(com.uyatame.ucrt.data.Keys.contactEmail, "") }
        player.onNotice = { snack = it }
        player.onQueueEnd = { playNextList() }
        // Android Auto(試験的)から、この再生を使えるようにする
        AutoBridge.register(this)
        player.onStateChanged = {
            updateService()
            loadPlayerArt()
            updateWidget()
            recordHistory()
            saveResume()
        }
        viewModelScope.launch(Dispatchers.IO) { com.uyatame.ucrt.player.RgStore.load(ctx) }
        viewModelScope.launch {
            store.load()
            libAll = withContext(Dispatchers.IO) { library.loadCache() }
            // アルバムへのまとめは曲が多いと時間がかかるので、画面のスレッドの外で行う
            albums = withContext(Dispatchers.Default) { Library.group(libAll) }
            restoreQueue()
            val s = repo.flow.first()
            if (s.outputUri != null || s.libraryFolders.isNotEmpty()) {
                // 以前の版の一覧には音質・ジャンルが無いので、一度だけ全曲を読み直す
                refreshLibrary(fresh = library.needsFullRescan, base = s)
            }
        }
        // 再生中は、位置をときどき保存しておく(アプリが閉じられても続きから再生できるように)
        viewModelScope.launch {
            while (true) {
                delay(15_000)
                if (player.isPlaying) saveResume()
            }
        }
    }

    // ---- ReplayGain の解析(ライブラリの曲をまとめて) ----

    var rgAnalyzing by mutableStateOf(false)
        private set
    /** 解析の進み具合(0〜1)と、今解析している曲 */
    var rgProgress by mutableFloatStateOf(0f)
        private set
    var rgStatus by mutableStateOf("")
        private set
    private var rgJob: kotlinx.coroutines.Job? = null
    @Volatile private var rgStop = false
    /** 解析中の曲(解析のスレッドが書き、画面の表示は 0.25 秒ごとに読む) */
    @Volatile private var rgNow = ""

    /** 解析した 1 曲の結果 */
    private class RgTrack(val uri: String, val gain: Float?, val peak: Float, val blocks: List<Double>)

    /**
     * ライブラリの曲の音の大きさを解析して、ReplayGain の値(曲ごと・アルバムごと)を求める。
     * all: 解析済みの曲も解析し直す(false なら、まだ解析していない曲があるアルバムだけ)。
     * ライブラリの読み込みと同じように、複数のスレッドで同時に曲を解析する(再生中は少ないスレッドで)。
     * アルバムの値はアルバムの全曲を合わせて求めるので、アルバムの全曲が終わってから保存する(途中でやめたアルバムは保存しない)。
     * 値はアプリの中に保存し、曲のファイルは書き換えない
     */
    fun analyzeReplayGain(all: Boolean) {
        if (rgAnalyzing) return
        val targets = albums.filter { a -> all || a.tracks.any { !com.uyatame.ucrt.player.RgStore.has(it.uri) } }
        val total = targets.sumOf { it.tracks.size }
        if (total == 0) {
            snack = S(R.string.rg_all_analyzed)
            return
        }
        rgAnalyzing = true
        rgProgress = 0f
        rgStop = false
        // 再生中は音が途切れないよう少ないスレッド・低い優先度で、止まっているときは全部の CPU を使う
        val playing = player.isPlaying
        val n = if (playing) 2 else Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val prio = if (playing) android.os.Process.THREAD_PRIORITY_BACKGROUND else 5
        rgJob = viewModelScope.launch {
            val done = java.util.concurrent.atomic.AtomicInteger()
            val analyzed = java.util.concurrent.atomic.AtomicInteger()
            val failed = java.util.concurrent.atomic.AtomicInteger()
            val tagged = java.util.concurrent.atomic.AtomicInteger()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(n) { r ->
                Thread({
                    runCatching { android.os.Process.setThreadPriority(prio) }
                    r.run()
                }, "rg-analysis").apply { isDaemon = true }
            }
            // 画面の表示は、曲が終わるたびではなく 0.25 秒ごとにまとめて更新する
            val ui = launch {
                while (true) {
                    rgProgress = done.get().toFloat() / total
                    rgStatus = rgNow
                    delay(250)
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    // アルバムごとに、残りの曲数と結果を数える。最後の曲が終わったスレッドがアルバムの値を求めて保存する
                    val futures = ArrayList<java.util.concurrent.Future<*>>()
                    for (a in targets) {
                        if (rgStop) break
                        // foobar2000 などで解析してタグに書いた値がある曲は、その値をそのまま使うので解析しない(アルバムの全曲にあるとき)
                        if (a.tracks.all { com.uyatame.ucrt.player.ReplayGain.hasTags(ctx, it.uri) }) {
                            tagged.addAndGet(a.tracks.size)
                            done.addAndGet(a.tracks.size)
                            continue
                        }
                        val left = java.util.concurrent.atomic.AtomicInteger(a.tracks.size)
                        val results = java.util.Collections.synchronizedList(ArrayList<RgTrack>())
                        for (t in a.tracks) futures.add(pool.submit {
                            if (rgStop) return@submit
                            rgNow = t.title + S(R.string.separator) + a.title
                            val r = analyzeTrack(t)
                            if (r == null) failed.incrementAndGet() else results.add(r)
                            done.incrementAndGet()
                            if (left.decrementAndGet() == 0 && !rgStop) analyzed.addAndGet(saveAlbumGain(results))
                        })
                    }
                    for (f in futures) {
                        try { f.get() } catch (e: InterruptedException) { break }
                    }
                }
            } finally {
                rgStop = true
                pool.shutdownNow()
                ui.cancel()
                rgAnalyzing = false
                rgStatus = ""
                val msg = S(R.string.rg_analysis_finished, analyzed.get(), failed.get()) +
                    (if (tagged.get() > 0) S(R.string.rg_analysis_used_tags, tagged.get()) else "")
                log(msg)
                snack = msg
            }
        }
    }

    /** 1 曲を解析する。読めなかった曲・DSD・途中でやめたときは null */
    private fun analyzeTrack(t: com.uyatame.ucrt.library.LibTrack): RgTrack? {
        var m: com.uyatame.ucrt.player.LoudnessMeter? = null
        val ok = runCatching {
            player.decodeForAnalysis(itemOf(t), { rgStop }) { buf, frames, ch, rate ->
                val meter = m ?: com.uyatame.ucrt.player.LoudnessMeter(rate, ch).also { m = it }
                meter.feed(buf, frames)
            }
        }.getOrElse { e ->
            if (!rgStop) log(S(R.string.rg_cannot_analyze, t.title, e.message ?: e.javaClass.simpleName))
            false
        }
        val meter = m ?: return null
        if (!ok || rgStop) return null
        val l = com.uyatame.ucrt.player.LoudnessMeter.integrated(meter.blocks)
        return RgTrack(t.uri, l?.let { com.uyatame.ucrt.player.LoudnessMeter.gainDb(it) }, meter.peak, meter.blocks)
    }

    /** アルバムの全曲の結果から、アルバムの値を求めて保存する。値を付けた曲の数を返す */
    private fun saveAlbumGain(tracks: List<RgTrack>): Int {
        val list = synchronized(tracks) { tracks.toList() }
        val blocks = ArrayList<Double>()
        var albumPeak = 0f
        for (r in list) {
            blocks.addAll(r.blocks)
            if (r.peak > albumPeak) albumPeak = r.peak
        }
        val albumGain = com.uyatame.ucrt.player.LoudnessMeter.integrated(blocks)?.let { com.uyatame.ucrt.player.LoudnessMeter.gainDb(it) }
        val values = HashMap<String, com.uyatame.ucrt.player.RgInfo>()
        // ほとんど無音の曲は、値を付けない(そのまま再生する)
        for (r in list) if (r.gain != null) values[r.uri] = com.uyatame.ucrt.player.RgInfo(r.gain, r.peak, albumGain, albumPeak)
        if (values.isNotEmpty()) com.uyatame.ucrt.player.RgStore.putAll(values)
        return values.size
    }

    fun cancelReplayGainAnalysis() {
        rgStop = true
        rgJob?.cancel()
    }

    /** 解析した値をすべて消す(曲に書かれた値はそのまま) */
    fun clearAnalyzedReplayGain() {
        viewModelScope.launch(Dispatchers.IO) { com.uyatame.ucrt.player.RgStore.clear() }
    }

    // ---- USB DAC の解放・アプリの終了 ----

    /** USB DAC を使っているか(「USB DAC を解放」を出すため) */
    val usbDacInUse: Boolean get() = com.uyatame.ucrt.player.usb.UsbDac.inUse

    /**
     * USB DAC の接続を解除する(抜き差ししたときに近い動作)。一時停止して Android に返したうえで、DAC を USB でリセットする。
     * DAC は挿した直後と同じ初期状態からやり直し、Android の USB オーディオのドライバーも DAC を見つけ直す
     */
    fun disconnectUsbDac() {
        player.releaseOutput()
        Thread({ com.uyatame.ucrt.player.usb.UsbDac.disconnect() }, "usb-dac-disconnect").start()
        snack = S(R.string.usb_dac_disconnecting)
    }

    /** USB DAC の使用の許可をもう一度求める(許可の画面が出なかった・断ってしまったとき) */
    fun requestUsbPermission() {
        snack = when (com.uyatame.ucrt.player.usb.UsbDac.requestPermissionAgain()) {
            0 -> S(R.string.usb_dac_not_connected)
            1 -> S(R.string.usb_permission_already)
            else -> S(R.string.usb_permission_requested)
        }
    }

    /** USB DAC がつながっているか(「接続解除」を出すため) */
    val usbDacPresent: Boolean get() = com.uyatame.ucrt.player.usb.UsbDac.findDac() != null

    private var quitting = false

    /**
     * アプリを終了する。再生を止め、USB DAC を Android に返し(返さずに終わると、ほかのアプリの音が USB DAC から出なくなる)、
     * 続きと設定を保存してから、通知・サービスを止めてプロセスを終える。finish: 画面を閉じる(通知から終了するときは null)
     */
    fun quitApp(finish: (() -> Unit)?) {
        if (quitting) return
        quitting = true
        saveJob?.cancel()
        writeResume()
        resumeReady = false
        player.onStateChanged = null
        player.onQueueEnd = null
        AutoBridge.unregister(this)
        player.release()
        runCatching { com.uyatame.ucrt.player.cast.CastManager.disconnect() }
        // DAC を起こしておく無音は、DAC を閉じる前に止め終えておく(閉じたあとにまた流し始めないように)
        runCatching { com.uyatame.ucrt.player.DacKeepAlive.stopNow(ctx) }
        closeAsync(drive)
        drive = null
        store.flush()
        KeepAliveService.hide(ctx)
        finish?.invoke()
        exitAfterDacClosed()
    }

    /**
     * USB DAC を(設定に従って Android に返して)閉じ終えてから、プロセスごと終える(サービスなどが残って DAC を握り続けないように)。
     * 返すときは数秒かかることがあるので別のスレッドで行い、画面は先に閉じる。長くかかっても 8 秒で終える
     */
    private fun exitAfterDacClosed() {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val kill = Runnable { android.os.Process.killProcess(android.os.Process.myPid()) }
        main.postDelayed(kill, 8000)
        Thread({
            runCatching { com.uyatame.ucrt.player.usb.UsbDac.closeForExit() }
            main.removeCallbacks(kill)
            main.postDelayed(kill, 200)
        }, "usb-dac-exit").start()
    }

    override fun onCleared() {
        // 終了処理で再生リストが空になる前に、続きを保存しておく
        saveJob?.cancel()
        writeResume()
        resumeReady = false
        player.onStateChanged = null
        player.onQueueEnd = null
        AutoBridge.unregister(this)
        com.uyatame.ucrt.player.AudioEngine.logger = null
        com.uyatame.ucrt.player.AudioEngine.onUsbVolume = null
        runCatching { ctx.unregisterReceiver(permReceiver) }
        runCatching { ctx.unregisterReceiver(detachReceiver) }
        player.release()
        closeAsync(drive)
        drive = null
        MediaBridge.player = null
        MediaBridge.quit = null
        KeepAliveService.hide(ctx)
    }

    fun log(msg: String) {
        // ドライブの読み取りなど、別のスレッドからも呼ばれるので、書き換えはメインスレッドで行う(同時に書いて消えないように)
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { log(msg) }
            return
        }
        logText = (logText + msg + "\n").takeLast(20000)
    }

    fun <T> set(key: Preferences.Key<T>, v: T) {
        viewModelScope.launch { repo.set(key, v) }
    }

    // ---- 新しい版の確認 ----

    /** 知らせる新しい版(ダイアログを出す)。null なら出さない */
    var updateOffer by mutableStateOf<com.uyatame.ucrt.meta.ReleaseInfo?>(null)
    /** 確認中 */
    var updateChecking by mutableStateOf(false)
        private set
    private var updateChecked = false

    /**
     * 新しい版が出ていないか確かめる。manual でなければ、起動して最初の 1 回だけ、
     * 設定でオンのときに、「この版は通知しない」にした版でなければ知らせる
     */
    fun checkUpdate(manual: Boolean) {
        val s = settings.value
        if (!manual && (updateChecked || !s.updateCheck)) return
        if (updateChecking) return
        updateChecked = true
        updateChecking = true
        viewModelScope.launch {
            val cur = BuildConfigVersion.name(ctx)
            val r = runCatching { withContext(Dispatchers.IO) { com.uyatame.ucrt.meta.UpdateCheck.latest(cur) } }
                .onFailure { log("update check failed: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
            updateChecking = false
            val newer = r != null && com.uyatame.ucrt.meta.UpdateCheck.isNewer(r.version, cur)
            if (r != null) log("update check: latest v${r.version}, this $cur" + if (newer) " (update available)" else "")
            when {
                newer && (manual || r!!.version != settings.value.updateSkip) -> updateOffer = r
                !manual -> {}
                r == null -> snack = S(R.string.couldnt_check_for_updates_check)
                else -> snack = S(R.string.youre_up_to_date_v, cur)
            }
        }
    }

    /** イコライザーの帯域を動かしている途中(保存はせず、音だけすぐ変える) */
    fun previewEq(bands: List<com.uyatame.ucrt.player.EqBand>, preamp: Float) {
        val s = settings.value
        com.uyatame.ucrt.player.AudioEngine.setEq(s.eqActive, bands, preamp, s.eqAutoPreamp)
    }

    /** ビットパーフェクト(USB DAC 直接出力)の切り替え */
    fun setOutputMode(mode: Int) {
        viewModelScope.launch { repo.setOutput(mode == 2, null) }
    }

    /** 出力先を選ぶ(独自ドライバーの USB DAC ならビットパーフェクト再生をオンにする) */
    fun setOutputDevice(key: String) {
        viewModelScope.launch {
            if (key == com.uyatame.ucrt.player.OutputDevices.KEY_USB_DIRECT) repo.setOutput(true, null)
            else repo.setOutput(false, key)
        }
    }

    /** DAC の音量(画面のスライダーから) */
    fun setUsbVolume(v: Int, save: Boolean) {
        // ネイティブ DSD・DoP で音量を変えられないときは変えない(知らせるだけ)
        if (player.usbVolumeLocked()) return
        player.setUsbVolume(v)
        if (save) set(com.uyatame.ucrt.data.Keys.usbVolume, v)
    }

    /** シンプルのイコライザー(10 バンド)を保存する */
    fun saveSimpleEq(gains: FloatArray, preamp: Float) {
        viewModelScope.launch {
            repo.set(Keys.eqSimpleGains, com.uyatame.ucrt.player.EqBands.encode(gains))
            repo.set(Keys.eqPreamp, preamp)
        }
    }

    fun saveEq(bands: List<com.uyatame.ucrt.player.EqBand>, preamp: Float, preset: Int) {
        viewModelScope.launch {
            repo.set(Keys.eqBands, com.uyatame.ucrt.player.ParamEq.encode(bands))
            repo.set(Keys.eqPreamp, preamp)
            repo.set(Keys.eqPreset, preset)
        }
    }

    fun setPreset(p: QualityPreset) {
        viewModelScope.launch { repo.setPreset(p) }
    }

    fun toggle(n: Int) { selected[n] = selected[n] != true }

    fun selectAll(on: Boolean) { audioTracks.forEach { selected[it.number] = on } }

    private fun updateService() {
        val cur = player.current
        val info = when {
            busy -> {
                val pct = (ripOverall * 100).toInt().coerceIn(0, 100)
                NotifInfo(
                    false, if (busyKind == 0) S(R.string.ripping_cd, ripIndex + 1, ripCount) else busyTitle(),
                    "$pct%" + S(R.string.separator) + albumTitle, progress = pct,
                    type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            }
            cur != null && player.started -> NotifInfo(
                true, cur.title, cur.artist + if (cur.album.isNotEmpty()) S(R.string.separator) + cur.album else "",
                playing = player.isPlaying,
                type = if (player.isCd) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            else -> null
        }
        if (info == null) KeepAliveService.hide(ctx) else KeepAliveService.show(ctx, info)
    }

    private var artFor: PlayItem? = null
    /** ウィジェットに出すジャケット */
    private var widgetArt: android.graphics.Bitmap? = null

    /** ホーム画面のウィジェットに、再生中の曲を出す */
    private fun updateWidget() {
        val cur = player.current
        PlayerWidget.update(
            ctx,
            cur?.let {
                PlayerWidget.State(
                    it.title, it.artist + if (it.album.isNotEmpty()) S(R.string.separator) + it.album else "",
                    player.isPlaying, if (artFor == cur) widgetArt else null,
                )
            },
        )
    }

    /** 再生中の曲のジャケットを、通知・ロック画面に渡す */
    private fun loadPlayerArt() {
        val cur = player.current
        if (cur == artFor) return
        artFor = cur
        if (cur == null) { player.setArt(null); widgetArt = null; return }
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    if (cur.cdTrack != null) coverBytes?.let { ArtLoader.decode(it, 512) }
                    else cur.artKey?.let { ArtLoader.load(ctx, it, cur.artTrackUri, cur.artCoverUri, 512, cur.artFallbackUri)?.asAndroidBitmap() }
                }.getOrNull()
            }
            if (player.current == cur) {
                player.setArt(bmp)
                widgetArt = bmp
                updateService()
                updateWidget()
            }
        }
    }

    // ================= 再生履歴・前回の続き =================

    private var lastRecordedId = -1L
    /** 前回の続きを読み込み終えるまでは、保存しない(読み込む前に消してしまわないように) */
    private var resumeReady = false

    private fun recordHistory() {
        val cur = player.current ?: return
        if (!player.isPlaying || cur.id == lastRecordedId) return
        lastRecordedId = cur.id
        cur.uri?.toString()?.let { store.recordPlay(it) }
    }

    private var saveJob: Job? = null

    /** 続きの保存(並べ替えなどで続けて呼ばれるので、少し待ってまとめて書く) */
    private fun saveResume() {
        if (!resumeReady) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            writeResume()
        }
    }

    private fun writeResume() {
        if (!resumeReady) return
        val q = player.queue
        when {
            q.isEmpty() -> store.saveResume(null)
            // CD の曲は次回には読めないので、続きとしては残さない
            q.any { it.uri == null } -> store.saveResume(null)
            else -> store.saveResume(
                com.uyatame.ucrt.data.ResumeState(
                    q.map { it.uri.toString() }, player.index, player.positionMs, player.shuffle, player.repeatMode,
                    player.originalOrder.mapNotNull { it.uri?.toString() },
                ),
            )
        }
    }

    private suspend fun restoreQueue() {
        try {
            val r = store.loadResume() ?: return
            if (player.current != null) return
            val map = trackMap
            val items = ArrayList<PlayItem>()
            var idx = 0
            r.uris.forEachIndexed { i, u ->
                val t = map[u] ?: return@forEachIndexed
                if (i == r.index) idx = items.size
                items.add(itemOf(t))
            }
            if (items.isEmpty()) return
            // シャッフル前の並びは、同じ項目(同じ id)を並べ替えて作る
            val pool = HashMap<String, ArrayDeque<PlayItem>>()
            items.forEach { pool.getOrPut(it.uri.toString()) { ArrayDeque() }.addLast(it) }
            val orig = r.original.mapNotNull { u -> pool[u]?.removeFirstOrNull() }
            val origList = if (orig.size == items.size) orig else items
            val sameTrack = map.containsKey(r.uris.getOrNull(r.index))
            player.restore(items, idx, if (sameTrack) r.positionMs else 0L, r.shuffle, r.repeat, origList)
        } finally {
            resumeReady = true
        }
    }

    // ================= USB接続 =================

    private var scanLogged = false

    /** ドライブを開いている途中か(許可の画面から戻ったときなどに、二重に開かないように) */
    private var opening = false

    fun scan() {
        if (drive != null || opening) return
        val devices = usb.deviceList.values.toList()
        if (!scanLogged && devices.isNotEmpty()) {
            scanLogged = true
            devices.forEach { log(UsbScsiDrive.describe(it)) }
        }
        val dev = devices.firstOrNull { UsbScsiDrive.findInterface(it) != null }
        if (dev == null) {
            status = if (devices.any { UsbScsiDrive.isUasOnly(it) }) {
                S(R.string.this_drive_only_supports_uas)
            } else {
                S(R.string.no_drive_found)
            }
            return
        }
        if (usb.hasPermission(dev)) {
            openDevice(dev)
        } else {
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(permAction).setPackage(ctx.packageName),
                mutablePendingFlags(),
            )
            status = S(R.string.waiting_for_usb_permission)
            usb.requestPermission(dev, pi)
        }
    }

    private fun openDevice(dev: UsbDevice) {
        if (opening || drive?.deviceName == dev.deviceName) return
        opening = true
        viewModelScope.launch {
            try {
                val d = withContext(Dispatchers.IO) { UsbScsiDrive.open(usb, dev) }
                d.logger = { m -> log(m) }
                d.verbose = settings.value.verboseLog
                closeAsync(drive)
                drive = d
                connected = true
                driveName = d.name
                status = S(R.string.connected)
                discText = S(R.string.checking_disc)
                val inq = withContext(Dispatchers.IO) {
                    var r = ""
                    for (a in 0 until 3) {
                        try { r = d.inquiry(); break } catch (e: Exception) { r = ""; Thread.sleep(500) }
                    }
                    r
                }
                if (inq.isNotEmpty()) { driveName = inq.substringBefore(" (type"); log(S(R.string.drive, inq)) }
                lastMedia = 0
                startMonitor(d)
            } catch (e: Exception) {
                status = S(R.string.could_not_connect, e.message)
            } finally {
                opening = false
            }
        }
    }

    private fun clearDisc(text: String) {
        discOk = false
        if (player.isCd) player.stop()
        tracks = emptyList()
        meta = null
        cdCover = null
        coverBytes = null
        candidates = emptyList()
        searchResults = emptyList()
        searchMessage = ""
        showChooser = false
        metaJob?.cancel()
        metaSearching = false
        metaStatus = ""
        discText = text
    }

    /**
     * ドライブを閉じる。ほかのスレッド(取り込み・再生など)のコマンドが終わるのを待ってから閉じるので、
     * メインスレッドを止めないよう別のスレッドで行う
     */
    private fun closeAsync(d: UsbScsiDrive?) {
        if (d != null) Thread({ d.close() }, "ucrt-drive-close").start()
    }

    private fun closeDrive(msg: String) {
        job?.cancel()
        monitorJob?.cancel()
        if (player.isCd) player.stop()
        closeAsync(drive)
        drive = null
        connected = false
        status = msg
        driveName = ""
        clearDisc("")
        busy = false
        discBusy = false
    }

    // ================= ディスクの自動検知 =================

    private var monitorJob: Job? = null
    private var lastMedia = 0
    private var autoRetries = 0
    /** ディスクを読み取れた(または音楽 CD 以外と分かった)か */
    private var discOk = false
    private val mediaReady = 1
    private val mediaNone = 2
    private val mediaLoading = 3
    private val mediaChanged = 4
    private val mediaGone = 5

    private fun mediaState(d: UsbScsiDrive): Int = try {
        d.testUnitReady()
        mediaReady
    } catch (e: ScsiException) {
        when {
            // 通信できず、USB 機器の一覧にもない: 外れている(開いている途中で外れると、外れた知らせを受け取れない)
            e.senseKey == -1 && usb.deviceList.values.none { it.deviceName == d.deviceName } -> mediaGone
            e.senseKey == 6 -> mediaChanged
            e.asc == 0x3A -> mediaNone
            e.asc == 0x04 -> mediaLoading
            else -> 0
        }
    }

    private fun startMonitor(d: UsbScsiDrive) {
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch {
            while (isActive && drive === d) {
                val playingCd = player.isCd && player.current != null
                // 書き込みの画面を出しているあいだは、ディスクを読みに行かない(書き込みのコマンドと重ならないように)
                if (!busy && !discBusy && !playingCd && burn == null) {
                    val st = withContext(Dispatchers.IO) { mediaState(d) }
                    if (st == mediaGone && drive === d) {
                        closeDrive(S(R.string.drive_was_disconnected))
                        break
                    }
                    if (!busy && !discBusy && drive === d) {
                        when (st) {
                            mediaReady, mediaChanged -> if (lastMedia != mediaReady || st == mediaChanged) {
                                lastMedia = mediaReady
                                refreshDisc()
                            }
                            mediaNone -> if (lastMedia != mediaNone) {
                                lastMedia = mediaNone
                                clearDisc(S(R.string.insert_disc))
                            }
                            mediaLoading -> if (lastMedia != mediaLoading) {
                                lastMedia = mediaLoading
                                discText = S(R.string.loading_disc)
                            }
                        }
                    }
                }
                // ディスクを確認できていないあいだは 0.5 秒ごとに確認し直す
                delay(if (lastMedia == mediaReady && discOk) 1500 else 500)
            }
        }
    }

    private class DiscScan(val text: String, val tracks: List<TocTrack>, val leadOut: Int, val retry: Boolean = false)

    private fun why(e: ScsiException): String = when {
        e.senseKey == -1 -> e.message ?: S(R.string.communication_error)
        e.asc == 0x3A -> S(R.string.no_disc)
        e.asc == 0x04 -> S(R.string.loading)
        e.senseKey == 6 -> S(R.string.disc_changed)
        else -> "key=%X asc=%02X/%02X".format(e.senseKey, e.asc, e.ascq)
    }

    private fun readDisc(d: UsbScsiDrive): DiscScan {
        d.verbose = settings.value.verboseLog
        var ready = false
        var noMedia = 0
        var lastErr = ""
        // 回転が安定するまで細かく確認する(最大 約30秒)
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 30_000) {
            try {
                d.testUnitReady()
                ready = true
                break
            } catch (e: ScsiException) {
                lastErr = why(e)
                if (e.senseKey == -1) break
                if (e.asc == 0x3A) { if (++noMedia >= 8) break } else noMedia = 0
                Thread.sleep(250)
            }
        }
        if (ready) log(S(R.string.disc_ready_s).format((System.currentTimeMillis() - t0) / 1000.0))
        if (!ready) {
            if (noMedia >= 3) return DiscScan(S(R.string.insert_disc), emptyList(), 0)
            log(S(R.string.drive_not_ready, lastErr))
        }
        var toc: Pair<List<TocTrack>, Int>? = null
        var tocErr = ""
        val t1 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t1 < 12_000) {
            try {
                toc = d.readToc()
                break
            } catch (e: ScsiException) {
                tocErr = why(e)
                if (e.senseKey == -1) break
                Thread.sleep(300)
            }
        }
        // 音楽 CD なら、ディスクの種類は調べずにすぐ返す
        if (toc != null && toc.first.any { it.isAudio }) return DiscScan("", toc.first, toc.second)
        val p = try { d.currentProfile() } catch (e: ScsiException) { -1 }
        val pname = if (p < 0) S(R.string.unknown_type) else UsbScsiDrive.profileName(p)
        // 空の CD-R / CD-RW
        if (p in 0x09..0x0A && runCatching { d.readDiscInfo().status == 0 }.getOrDefault(false)) {
            return DiscScan(S(R.string.blank_use_it_to_copy, pname), emptyList(), 0)
        }
        if (toc != null) {
            if (p in 0x08..0x0A) return DiscScan(S(R.string.this_is_data_cd_only), emptyList(), 0)
        }
        var capErr = ""
        val cap = try { d.readCapacity() } catch (e: ScsiException) { capErr = why(e); null }
        if (cap != null) {
            return DiscScan(
                S(R.string.this_is_disc_gb_only, pname, "%.1f".format(cap.first * cap.second / 1e9)),
                emptyList(), 0,
            )
        }
        log(S(R.string.cannot_recognize_disc_toc_capacity, tocErr, capErr))
        return DiscScan(if (ready) S(R.string.cannot_read_disc) else S(R.string.drive_isnt_ready), emptyList(), 0, retry = true)
    }

    fun refreshDisc() {
        val d = drive ?: return
        if (busy || discBusy) return
        viewModelScope.launch {
            discBusy = true
            try {
                val r = withContext(Dispatchers.IO) { readDisc(d) }
                val changed = r.tracks.map { it.startLba } != tracks.map { it.startLba }
                discText = r.text
                // 読み取れなかったときは、確認できるまで 0.5 秒ごとに読み直す
                discOk = !r.retry && r.text != S(R.string.insert_disc)
                if (r.retry || !discOk) lastMedia = 0
                if (r.retry) autoRetries++ else autoRetries = 0
                if (changed) {
                    if (player.isCd) player.stop()
                    tracks = r.tracks
                    leadOut = r.leadOut
                    selected.clear(); progress.clear(); trackStatus.clear()
                    audioTracks.forEach { selected[it.number] = true }
                    candidates = emptyList()
                    searchResults = emptyList()
                    searchMessage = ""
                    showChooser = false
                    cdCover = null
                    coverBytes = null
                    metaStatus = ""
                    meta = if (audioTracks.isNotEmpty()) fitMeta(AlbumMeta(UNKNOWN_ALBUM, UNKNOWN_ARTIST)) else null
                    currentDiscId = MusicBrainz.toc(tracks, leadOut)?.discId
                    if (audioTracks.isNotEmpty()) {
                        discEvent++
                        val id = currentDiscId
                        val cached = id?.let { withContext(Dispatchers.IO) { discCache.load(it) } }
                        if (cached != null && id != null) {
                            // 前に決めた曲情報があれば、ネットに問い合わせずにすぐ使う
                            meta = fitMeta(cached)
                            metaStatus = "✓ " + S(R.string.track_info_from_last_time)
                            val img = withContext(Dispatchers.IO) { discCache.loadCover(id) }
                            if (img != null) {
                                coverBytes = img
                                cdCover = withContext(Dispatchers.IO) { ArtLoader.decode(img, 600)?.asImageBitmap() }
                            }
                            log(S(R.string.used_saved_track_info))
                        } else if (settings.value.autoMeta) fetchMeta() else metaStatus = S(R.string.you_can_edit_track_info)
                    }
                }
            } catch (e: Exception) {
                discText = S(R.string.could_not_read_disc, e.message)
            } finally {
                discBusy = false
            }
        }
    }

    fun eject() {
        val d = drive ?: return
        if (busy) return
        if (player.isCd) player.stop()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { d.eject() }
                clearDisc(S(R.string.insert_disc))
                lastMedia = mediaNone
            } catch (e: Exception) {
                snack = S(R.string.could_not_eject)
                log(S(R.string.eject_failed, e.message))
            }
        }
    }

    // ================= 曲情報 =================

    private fun fitMeta(m: AlbumMeta): AlbumMeta {
        val n = audioTracks.size
        return m.copy(tracks = (0 until n).map { i ->
            m.tracks.getOrNull(i) ?: TrackMeta(S(R.string.track_2).format(audioTracks[i].number), m.artist)
        })
    }

    private fun sourceName(src: String) = when (src) {
        MusicBrainz.SOURCE -> "MusicBrainz"
        else -> src
    }

    /**
     * ディスクIDで MusicBrainz を検索し、候補をまとめる。
     * ディスクIDが完全一致する候補が1件だけならそれを使い、それ以外は選択画面を出す。
     */
    fun fetchMeta(openChooserAlways: Boolean = false) {
        val mbToc = MusicBrainz.toc(tracks, leadOut) ?: return
        val s = settings.value
        MusicBrainz.contact = ""
        MusicBrainz.version = BuildConfigVersion.name(ctx)
        val audio = audioTracks
        metaStatus = S(R.string.searching_for_track_info)
        metaSearching = true
        metaError = ""
        candidates = emptyList()
        log("MusicBrainz disc ID: ${mbToc.discId} / TOC: ${mbToc.tocParam}")
        metaJob?.cancel()
        metaJob = viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    listOf(MusicBrainz.lookup(mbToc))
                }
                for (r in results) {
                    if (r.error != null) log(S(R.string.failed_2, sourceName(r.source), r.error))
                    else log(S(R.string.results, sourceName(r.source), r.candidates.size))
                }
                val all = results.flatMap { it.candidates }
                    .sortedWith(compareByDescending<ReleaseCandidate> { it.exact }.thenBy { if (it.source == MusicBrainz.SOURCE) 0 else 1 })
                candidates = all
                val exact = all.filter { it.exact }
                when {
                    all.isEmpty() && results.all { it.error != null } -> {
                        metaStatus = S(R.string.could_not_get_track_info)
                        metaError = results.joinToString("\n") { "${sourceName(it.source)}: ${it.error}" }
                        showChooser = true
                    }
                    all.isEmpty() -> {
                        metaStatus = S(R.string.no_matches_found_you_can_2)
                        showChooser = true
                    }
                    !openChooserAlways && all.size == 1 && exact.size == 1 -> {
                        applyCandidate(all[0])
                    }
                    else -> {
                        metaStatus = S(R.string.choose_from_matches, all.size)
                        showChooser = true
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                metaStatus = S(R.string.could_not_get_track_info)
                metaError = com.uyatame.ucrt.meta.describeNetError(e)
                log(S(R.string.track_info_lookup_failed, e.message))
            } finally {
                if (metaJob === coroutineContext[Job]) metaSearching = false
            }
        }
    }

    // ---- 曲情報を MusicBrainz に登録(アップロード) ----

    /** 登録の前の確認中 */
    var mbUploadBusy by mutableStateOf(false)
        private set
    /** 同じアルバムがすでに MusicBrainz にあったとき(曲情報は登録せず、ディスクIDの追加だけを勧める) */
    var mbExisting by mutableStateOf<MbExisting?>(null)

    /** message: 何が見つかったかの説明。attachUrl: このCDのディスクIDを追加するページ(CD から登録しようとしたときだけ) */
    class MbExisting(val title: String, val summary: String, val releaseUrl: String?, val attachUrl: String?, val message: String)

    /**
     * 入力した曲情報を MusicBrainz に登録する。すでに登録されている(ディスクIDが一致する・同じアルバムがある)ときは登録しない。
     * MusicBrainz にはアプリから直接登録する仕組みが無いので、登録画面を曲情報を入れた状態で開き、送信は利用者が行う
     */
    fun uploadToMusicBrainz() {
        val m = meta ?: return
        val toc = MusicBrainz.toc(tracks, leadOut) ?: return
        if (mbUploadBusy) return
        // 入力していない(「不明なアルバム」「トラック 1」などの仮の名前のまま)ところがあれば、登録しない
        val audio = audioTracks
        val placeholder = m.title.isBlank() || m.artist.isBlank() || m.title == UNKNOWN_ALBUM || m.artist == UNKNOWN_ARTIST ||
            m.tracks.size < toc.audioCount ||
            m.tracks.take(toc.audioCount).withIndex().any { (i, t) ->
                t.title.isBlank() || audio.getOrNull(i)?.let { t.title == S(R.string.track_2).format(it.number) } == true
            }
        if (placeholder) {
            snack = S(R.string.mb_upload_fill_in)
            return
        }
        MusicBrainz.version = BuildConfigVersion.name(ctx)
        mbUploadBusy = true
        viewModelScope.launch {
            try {
                // 1) このCDのディスクIDで見つかる(= すでに登録されている)なら、登録しない
                val byDisc = withContext(Dispatchers.IO) { MusicBrainz.lookup(toc) }
                if (byDisc.error != null) {
                    snack = S(R.string.mb_upload_check_failed, byDisc.error)
                    return@launch
                }
                byDisc.candidates.firstOrNull { it.exact }?.let { c ->
                    mbExisting = MbExisting(
                        c.meta.title, c.summary, c.meta.releaseId?.let { MusicBrainz.releaseUrl(it) }, null, S(R.string.mb_upload_already_desc),
                    )
                    log(S(R.string.mb_upload_already, c.meta.title))
                    return@launch
                }
                // 2) 曲の長さが同じリリース、または同じアルバム名・アーティスト名・曲数のリリースがあれば、曲情報は登録せず、
                //    そのリリースにこのCDのディスクIDを追加するよう勧める(重複して登録しないように)
                val same = byDisc.candidates.firstOrNull { it.trackCount == toc.audioCount }
                    ?: withContext(Dispatchers.IO) { MusicBrainz.findSame(m, toc.audioCount) }
                if (same != null) {
                    mbExisting = MbExisting(
                        same.meta.title, same.summary, same.meta.releaseId?.let { MusicBrainz.releaseUrl(it) }, MusicBrainz.attachUrl(toc),
                        S(R.string.mb_upload_similar_desc),
                    )
                    log(S(R.string.mb_upload_similar, same.meta.title))
                    return@launch
                }
                // 3) 見つからなければ、詳しい情報を入力する画面を出す
                val lengths = audioTracks.take(toc.audioCount).map { (it.endLba - it.startLba).toLong() * 1000 / 75 }
                mbDraft = MbDraft(
                    m.title, m.artist, m.date,
                    listOf(
                        MbDiscDraft(
                            if (m.discTotal > 1) m.discNo else 0, toc.tocParam.replace('+', ' '),
                            lengths.indices.map { i ->
                                val tm = m.tracks[i]
                                // CD の画面で入力した歌詞があれば、LRCLIB にも登録できるようにする
                                MbTrackDraft(tm.title, tm.artist, lengths[i], tm.lyrics.takeIf { it.isNotBlank() }?.let { com.uyatame.ucrt.player.LyricsLoader.parse(it) })
                            },
                        ),
                    ),
                    coverBytes, MusicBrainz.EDIT_NOTE,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                snack = S(R.string.mb_upload_check_failed, com.uyatame.ucrt.meta.describeNetError(e))
            } finally {
                mbUploadBusy = false
            }
        }
    }

    class MbTrackDraft(val title: String, val artist: String, val lengthMs: Long, val lyrics: com.uyatame.ucrt.player.Lyrics?)
    /** position: 何枚目か(0 = 1 枚組)。toc: CD の目次(ライブラリからは無い) */
    class MbDiscDraft(val position: Int, val toc: String?, val tracks: List<MbTrackDraft>)
    /** MusicBrainz に登録する内容(詳しい情報を入力する画面で、利用者が直してから送る) */
    class MbDraft(
        val title: String,
        val artist: String,
        val date: String,
        val discs: List<MbDiscDraft>,
        val cover: ByteArray?,
        val note: String,
        val extra: MusicBrainz.ReleaseExtra = MusicBrainz.ReleaseExtra(),
    )

    /** 詳しい情報を入力する画面に出している内容(null なら出さない) */
    var mbDraft by mutableStateOf<MbDraft?>(null)

    /** 歌詞を LRCLIB に登録している間 */
    var lyricsUploading by mutableStateOf(false)
        private set
    private var lyricsJob: Job? = null

    /**
     * 詳しい情報を入力する画面で「登録」を押したとき。MusicBrainz の登録画面を開き、
     * 選ばれていれば、埋め込まれた歌詞を LRCLIB に登録する(登録済みの曲は登録しない)
     */
    fun submitMbDraft(d: MbDraft, withCover: Boolean, withLyrics: Boolean) {
        mbDraft = null
        val discs = d.discs.map { disc ->
            MusicBrainz.SeedDisc(disc.position, disc.tracks.map { MusicBrainz.SeedTrack(it.title, it.artist, it.lengthMs) }, disc.toc)
        }
        val html = MusicBrainz.seedHtml(d.title, d.artist, d.date, discs, d.note, d.extra)
        log(S(R.string.mb_upload_opening))
        // ジャケット画像があれば、リリースを登録したあとに画像も登録できるようにする
        MusicBrainzSubmitActivity.open(ctx, html, if (withCover) d.cover else null)
        if (withLyrics) uploadLyrics(d)
    }

    /** 曲に埋め込まれた歌詞を LRCLIB に登録する(すでに歌詞がある曲は登録しない) */
    private fun uploadLyrics(d: MbDraft) {
        if (lyricsUploading) return
        val entries = d.discs.flatMap { it.tracks }.mapNotNull { t ->
            val l = t.lyrics ?: return@mapNotNull null
            if (t.lengthMs <= 0) return@mapNotNull null
            val (plain, synced) = com.uyatame.ucrt.meta.Lrclib.fromLyrics(l)
            if (plain.isNullOrBlank() && synced.isNullOrBlank()) return@mapNotNull null
            com.uyatame.ucrt.meta.Lrclib.Entry(t.title, t.artist.ifBlank { d.artist }, d.title, t.lengthMs / 1000.0, plain, synced)
        }
        if (entries.isEmpty()) return
        lyricsUploading = true
        lyricsJob = viewModelScope.launch {
            var sent = 0
            var skipped = 0
            var failed = 0
            try {
                for ((i, e) in entries.withIndex()) {
                    val job = coroutineContext[Job]
                    snack = S(R.string.lyrics_uploading, i + 1, entries.size, e.track)
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            if (com.uyatame.ucrt.meta.Lrclib.hasLyrics(e)) false
                            else { com.uyatame.ucrt.meta.Lrclib.publish(e) { job?.isActive == false }; true }
                        }
                    }
                    r.onSuccess { if (it) sent++ else skipped++ }.onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        failed++
                        log(S(R.string.lyrics_upload_failed, e.track, com.uyatame.ucrt.meta.describeNetError(it)))
                    }
                }
            } finally {
                lyricsUploading = false
                val msg = S(R.string.lyrics_upload_done, sent, skipped, failed)
                log(msg)
                snack = msg
            }
        }
    }

    /**
     * ライブラリのアルバムの曲情報(タグ)を MusicBrainz に登録する。CD の目次が無いのでディスクIDは登録できない。
     * 同じアルバム(アルバム名・アーティスト名・曲数が同じ)がすでにあれば登録しない
     */
    fun uploadAlbumToMusicBrainz(a: com.uyatame.ucrt.library.LibAlbum) {
        if (mbUploadBusy) return
        val discs = a.tracks.groupBy { it.disc }.toSortedMap()
        if (a.title.isBlank() || a.artist.isBlank() || a.title == UNKNOWN_ALBUM || a.artist == UNKNOWN_ARTIST ||
            a.tracks.any { it.title.isBlank() }
        ) {
            snack = S(R.string.mb_upload_fill_in)
            return
        }
        MusicBrainz.version = BuildConfigVersion.name(ctx)
        mbUploadBusy = true
        snack = S(R.string.mb_upload_checking)
        viewModelScope.launch {
            try {
                // 1 枚目の曲数で探す(MusicBrainz の検索は、曲数が合うディスクを選ぶ)
                val firstCount = discs.values.first().size
                val same = withContext(Dispatchers.IO) { MusicBrainz.findSame(AlbumMeta(a.title, a.artist), firstCount) }
                if (same != null) {
                    mbExisting = MbExisting(
                        same.meta.title, same.summary, same.meta.releaseId?.let { MusicBrainz.releaseUrl(it) }, null,
                        S(R.string.mb_upload_library_exists),
                    )
                    log(S(R.string.mb_upload_similar, same.meta.title))
                    return@launch
                }
                // 曲に埋め込まれた歌詞とジャケット画像を読んでから、詳しい情報を入力する画面を出す
                val multi = discs.size > 1
                val draftDiscs = withContext(Dispatchers.IO) {
                    discs.entries.mapIndexed { n, (disc, list) ->
                        MbDiscDraft(
                            if (multi) (if (disc > 0) disc else n + 1) else 0, null,
                            list.sortedBy { it.track }.map {
                                MbTrackDraft(
                                    it.title, it.artist, it.durationMs.coerceAtLeast(0),
                                    com.uyatame.ucrt.player.LyricsLoader.embeddedOnly(ctx, it.uri),
                                )
                            },
                        )
                    }
                }
                val cover = withContext(Dispatchers.IO) {
                    com.uyatame.ucrt.library.ArtLoader.originalBytes(ctx, a.first.uri, a.first.coverUri)
                }
                mbDraft = MbDraft(a.title, a.artist, a.year, draftDiscs, cover, MusicBrainz.EDIT_NOTE_LIBRARY)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                snack = S(R.string.mb_upload_check_failed, com.uyatame.ucrt.meta.describeNetError(e))
            } finally {
                mbUploadBusy = false
            }
        }
    }

    fun openChooser() {
        showChooser = true
        if (candidates.isEmpty() && !metaSearching) fetchMeta(openChooserAlways = true)
    }

    fun closeChooser() {
        showChooser = false
        if (meta?.releaseId == null && !metaStatus.startsWith("✓")) {
            if (!metaSearching) metaStatus = S(R.string.you_can_edit_track_info)
        }
    }

    /** アルバム名・アーティスト名で MusicBrainz を検索する */
    fun searchByName(album: String, artist: String) {
        if (searchBusy) return
        searchBusy = true
        searchMessage = S(R.string.searching)
        val count = audioTracks.size
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { MusicBrainz.search(album, artist, count) }
                searchResults = r.candidates
                searchMessage = when {
                    r.error != null -> S(R.string.search_failed, r.error)
                    r.candidates.isEmpty() -> S(R.string.nothing_found_try_different_spelling)
                    else -> S(R.string.found_matching_track_counts_first, r.candidates.size)
                }
            } finally {
                searchBusy = false
            }
        }
    }

    fun applyCandidate(c: ReleaseCandidate) {
        val loader = c.loader
        if (loader != null) {
            if (applyingCandidate) return
            applyingCandidate = true
            viewModelScope.launch {
                try {
                    val m = withContext(Dispatchers.IO) { runCatching { loader() }.getOrNull() }
                    if (m == null) {
                        snack = S(R.string.could_not_load_track_list)
                    } else {
                        if (m.tracks.size != audioTracks.size) {
                            snack = S(R.string.track_counts_dont_match_cd, audioTracks.size, m.tracks.size)
                        }
                        useMeta(m, c.source)
                    }
                } finally {
                    applyingCandidate = false
                }
            }
            return
        }
        useMeta(c.meta, c.source)
    }

    fun undoMeta() {
        val u = undoState ?: return
        undoState = null
        meta = u.first
        coverBytes = u.second
        cdCover = u.third
        metaStatus = S(R.string.reverted)
    }

    private fun useMeta(m: AlbumMeta, source: String) {
        val hadInfo = meta?.title?.let { it != UNKNOWN_ALBUM } == true
        undoState = Triple(meta, coverBytes, cdCover)
        if (hadInfo) actionSnack = ActionSnack.UndoMeta(S(R.string.track_info_changed_to, m.title))
        showChooser = false
        meta = fitMeta(m)
        metaStatus = "✓ " + S(R.string.track_info, sourceName(source))
        cdCover = null
        coverBytes = null
        val disc = currentDiscId
        if (disc != null) {
            val keep = meta
            viewModelScope.launch(Dispatchers.IO) {
                keep?.let { discCache.save(disc, it) }
                discCache.saveCover(disc, null)
            }
        }
        val id = m.releaseId ?: return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { MusicBrainz.cover(id) }
            val b = r.data
            if (b == null) {
                log(S(R.string.cover_art, r.message))
                return@launch
            }
            if (meta?.releaseId != id) return@launch
            coverBytes = b
            if (disc != null && disc == currentDiscId) withContext(Dispatchers.IO) { discCache.saveCover(disc, b) }
            cdCover = withContext(Dispatchers.IO) { ArtLoader.decode(b, 600)?.asImageBitmap() }
        }
    }

    /** CD のジャケット画像を端末の写真から選ぶ */
    fun pickCdCover(uri: Uri) {
        viewModelScope.launch {
            try {
                val b = withContext(Dispatchers.IO) {
                    val raw = ctx.contentResolver.openInputStream(uri)?.use { com.uyatame.ucrt.tags.readCoverImage(it) } ?: throw IOException(S(R.string.cannot_open_image))
                    prepareCoverImage(raw)
                }
                coverBytes = b
                cdCover = withContext(Dispatchers.IO) { ArtLoader.decode(b, 600)?.asImageBitmap() }
                currentDiscId?.let { id -> withContext(Dispatchers.IO) { discCache.saveCover(id, b) } }
            } catch (e: Exception) {
                snack = S(R.string.could_not_load_image)
            }
        }
    }

    fun dismissCandidates() = closeChooser()

    fun updateMeta(m: AlbumMeta) {
        meta = fitMeta(m)
        metaStatus = S(R.string.track_info_updated)
        val id = currentDiscId
        val keep = meta
        if (id != null && keep != null) viewModelScope.launch(Dispatchers.IO) { discCache.save(id, keep) }
    }

    // ================= 取り込み =================

    /** 取り込みの確認に出す内容 */
    class RipPlan(
        val tree: String,
        val count: Int,
        /** 保存先(「SDカード / Music / アーティスト / アルバム」など) */
        val dest: String,
        val sizeBytes: Long,
        /** 保存先に同じ名前のファイルがある曲 */
        val existing: List<String>,
    )

    /** 取り込みの確認ダイアログ(null なら出さない) */
    var ripPlan by mutableStateOf<RipPlan?>(null)
        private set

    /** 取り込む前に、保存先・容量・同じ名前のファイルを調べて確認を出す */
    fun prepareRip(treeOverride: String? = null) {
        if (drive == null || busy || discBusy) return
        val s = settings.value
        val tree = treeOverride ?: s.outputUri
        if (tree == null) { snack = S(R.string.choose_save_folder_in_settings); return }
        val m = meta ?: fitMeta(AlbumMeta(UNKNOWN_ALBUM, UNKNOWN_ARTIST))
        val targets = audioTracks.filter { selected[it.number] == true }
        if (targets.isEmpty()) { snack = S(R.string.select_tracks_to_rip); return }
        viewModelScope.launch {
            val existing = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = findAlbumDir(tree, s, m) ?: return@runCatching emptyList()
                    val names = dir.listFiles().mapNotNull { it.name }.toHashSet()
                    targets.map { fileName(it, s, m) }.filter { it in names }
                }.getOrDefault(emptyList())
            }
            val sec = targets.sumOf { (it.endLba - it.startLba).toLong() } / 75.0
            val size = when (s.format) {
                AudioFormat.WAV -> sec * 176_400
                AudioFormat.FLAC -> sec * 176_400 * 0.6
                AudioFormat.AAC -> sec * s.aacKbps * 125
                AudioFormat.MP3 -> sec * s.mp3Kbps * 125
            }.toLong()
            val sub = when (s.folderMode) {
                0 -> " / ${safe(m.artist)} / ${safe(m.title)}"
                1 -> " / ${safe(m.title)}"
                else -> ""
            }
            ripPlan = RipPlan(tree, targets.size, folderLabel(tree) + sub, size, existing)
        }
    }

    fun dismissRipPlan() { ripPlan = null }

    /** 確認ダイアログで「取り込む」を押した。skipExisting なら、同じ名前のファイルがある曲は取り込まない */
    fun confirmRip(skipExisting: Boolean) {
        val p = ripPlan ?: return
        ripPlan = null
        startRip(p.tree, skipExisting)
    }

    /** 保存先のアルバムのフォルダ(まだ無ければ null。作らない) */
    private fun findAlbumDir(tree: String, s: AppSettings, m: AlbumMeta): DocumentFile? {
        var dir = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: return null
        val parts = when (s.folderMode) {
            0 -> listOf(safe(m.artist), safe(m.title))
            1 -> listOf(safe(m.title))
            else -> emptyList()
        }
        for (p in parts) dir = dir.findFile(p)?.takeIf { it.isDirectory } ?: return null
        return dir
    }

    /** 取り込む曲のファイル名 */
    private fun fileName(t: TocTrack, s: AppSettings, m: AlbumMeta): String {
        val ai = audioTracks.indexOf(t)
        val title = m.tracks.getOrNull(ai)?.title?.takeIf { it.isNotBlank() } ?: S(R.string.track_2).format(t.number)
        val artist = m.tracks.getOrNull(ai)?.artist?.takeIf { it.isNotBlank() } ?: m.artist
        val n = "%02d".format(t.number)
        val dp = if (m.discTotal > 1) "${m.discNo}-" else ""
        val base = when (s.fileNameMode) {
            0 -> "$dp$n ${safe(title)}"
            1 -> "$dp$n - ${safe(artist)} - ${safe(title)}"
            else -> "${dp}Track$n"
        }
        return "$base.${s.format.ext}"
    }

    fun startRip(treeOverride: String? = null, skipExisting: Boolean = false) {
        val d = drive ?: return
        if (busy || discBusy) return
        val s = settings.value
        val tree = treeOverride ?: s.outputUri
        if (tree == null) { snack = S(R.string.choose_save_folder_in_settings); return }
        val m = meta ?: fitMeta(AlbumMeta(UNKNOWN_ALBUM, UNKNOWN_ARTIST))
        val targets = audioTracks.filter { selected[it.number] == true }
        if (targets.isEmpty()) { snack = S(R.string.select_tracks_to_rip); return }
        if (player.isCd) player.stop()
        d.verbose = s.verboseLog
        val cover = coverBytes?.let { makeCover(it) }
        job = viewModelScope.launch {
            busy = true
            ripCount = targets.size
            ripIndex = 0
            ripOverall = 0f
            ripStartMs = System.currentTimeMillis()
            notifPct = -1
            targets.forEach { trackStatus[it.number] = S(R.string.waiting); progress[it.number] = 0f }
            updateService()
            var errors = 0
            var skipped = 0
            try {
                withContext(Dispatchers.IO) {
                    val dir = albumDir(tree, s, m)
                    if (s.saveCover && cover != null) saveCoverFile(dir, cover)
                    val have = if (skipExisting) dir.listFiles().mapNotNull { it.name }.toHashSet() else emptySet()
                    targets.forEachIndexed { i, t ->
                        ensureActive()
                        ripIndex = i
                        if (fileName(t, s, m) in have) {
                            // すでにある曲は取り込まない(確認で選んだとき)
                            skipped++
                            trackStatus[t.number] = S(R.string.skipped)
                            progress[t.number] = 1f
                            ripOverall = (i + 1f) / targets.size
                            return@forEachIndexed
                        }
                        updateService()
                        errors += ripTrack(d, t, s, m, dir, if (s.embedCover) cover else null, { p ->
                            ripOverall = (i + p) / targets.size
                            val pct = (ripOverall * 100).toInt()
                            if (pct >= notifPct + 2) { notifPct = pct; updateService() }
                        }) { ensureActive() }
                    }
                }
                ripOverall = 1f
                actionSnack = ActionSnack.OpenAlbum(
                    if (errors == 0) S(R.string.finished_ripping, m.title)
                    else S(R.string.ripping_finished_with_read_errors, errors),
                    m.title, m.artist,
                )
                log(S(R.string.ripped_tracks, m.title, targets.size - skipped))
                if (s.ejectAfter) withContext(Dispatchers.IO) { runCatching { d.eject() } }
            } catch (e: CancellationException) {
                snack = S(R.string.ripping_canceled)
                throw e
            } catch (e: Exception) {
                snack = S(R.string.ripping_failed, e.message)
                log(S(R.string.rip_error, e.message))
            } finally {
                busy = false
                updateService()
                refreshLibrary()
            }
        }
    }

    fun cancelRip() { job?.cancel() }

    // ---- CD のコピー・書き込み ----

    /** いま busy の作業: 0 = 取り込み、1 = コピーのための読み取り、2 = 曲の変換、3 = 書き込み、4 = 消去 */
    var busyKind by mutableIntStateOf(0)
        private set

    /** 進み具合の表示(取り込み以外) */
    fun busyTitle(): String = when (busyKind) {
        1 -> S(R.string.reading_cd_to_copy)
        2 -> S(R.string.preparing_track_of, ripIndex + 1, ripCount)
        3 -> S(R.string.writing_to_cd)
        4 -> S(R.string.erasing_cd_rw)
        else -> S(R.string.ripping_track_of, ripIndex + 1, ripCount)
    }

    /** 書き込みの画面(null なら出さない) */
    class BurnSession(
        val title: String,
        val image: com.uyatame.ucrt.rip.BurnImage,
        /** 0 = ディスク待ち、1 = 書き込み済み */
        val stage: Int,
        /** 知らせ(エラーなど)。赤字で出すときは error */
        val message: String = "",
        val error: Boolean = false,
        /** 消去できる CD-RW が入っている */
        val canErase: Boolean = false,
    )

    var burn by mutableStateOf<BurnSession?>(null)
        private set

    /** ライブラリの曲から CD を作る確認(題名と曲)。null なら出さない */
    var burnRequest by mutableStateOf<Pair<String, List<com.uyatame.ucrt.library.LibTrack>>?>(null)

    private val burnFile: File get() = File(ctx.cacheDir, "burn.pcm")

    /** 書き込む内容を置く場所が足りるか(足りなければ知らせて false) */
    private fun burnSpaceOk(sectors: Long): Boolean {
        val need = sectors * 2352 + (50L shl 20)
        val free = ctx.cacheDir.usableSpace + (if (burnFile.exists()) burnFile.length() else 0L)
        if (free >= need) return true
        snack = S(R.string.not_enough_free_storage_about).format(need shr 20)
        return false
    }

    /** いまの音楽 CD をコピーする: まず読み取って端末に置き、そのあと空の CD-R に書き込む */
    fun startCopy() {
        val d = drive ?: return
        if (busy || discBusy) return
        // 先頭から続いている音楽トラック(エンハンスド CD のデータトラックは除く)
        val tr = tracks.takeWhile { it.isAudio }
        if (tr.isEmpty()) { snack = S(R.string.insert_audio_cd); return }
        if (!burnSpaceOk((tr.last().endLba - tr.first().startLba).toLong())) return
        if (player.isCd) player.stop()
        val s = settings.value
        val title = albumTitle
        val lo = leadOut
        job = viewModelScope.launch {
            busy = true
            busyKind = 1
            ripCount = 1
            ripIndex = 0
            ripOverall = 0f
            ripStartMs = System.currentTimeMillis()
            notifPct = -1
            updateService()
            try {
                val img = withContext(Dispatchers.IO) {
                    com.uyatame.ucrt.rip.BurnPrep.fromCd(
                        d, tr, lo, RipOptions(s.speedX, s.sectorsPerRead, s.retries, s.useC2, s.abortOnError, s.offsetSamples),
                        burnFile, { p -> ripOverall = p; val pct = (p * 100).toInt(); if (pct >= notifPct + 2) { notifPct = pct; updateService() } },
                    ) { ensureActive() }
                }
                log(S(R.string.read_for_copy_tracks_min, title, tr.size, "%.1f".format(img.seconds / 60)))
                withContext(Dispatchers.IO) { runCatching { d.eject() } }
                clearDisc(S(R.string.insert_blank_cd_r))
                lastMedia = mediaNone
                burn = BurnSession(title, img, 0)
            } catch (e: CancellationException) {
                snack = S(R.string.copy_canceled)
                runCatching { burnFile.delete() }
                throw e
            } catch (e: Exception) {
                snack = S(R.string.could_not_read_cd, e.message)
                log(S(R.string.copy_read_error, e.message))
                runCatching { burnFile.delete() }
            } finally {
                busy = false
                busyKind = 0
                updateService()
            }
        }
    }

    /** ライブラリの曲から CD を作る。gapSec は曲間の無音(秒) */
    fun startBurnFromTracks(title: String, list: List<com.uyatame.ucrt.library.LibTrack>, gapSec: Int) {
        if (busy || discBusy) return
        if (list.isEmpty()) return
        val secs = list.sumOf { (if (it.endMs > 0) it.endMs - it.startMs else it.durationMs) } / 1000 + gapSec * list.size
        if (secs > 80 * 60) { snack = S(R.string.too_long_for_one_cd); return }
        if (!burnSpaceOk(secs * 75 + 75 * 60)) return
        val src = list.map {
            com.uyatame.ucrt.rip.BurnSource(Uri.parse(com.uyatame.ucrt.library.Cue.fileUri(it.uri)), it.startMs, it.endMs)
        }
        job = viewModelScope.launch {
            busy = true
            busyKind = 2
            ripCount = list.size
            ripIndex = 0
            ripOverall = 0f
            ripStartMs = System.currentTimeMillis()
            notifPct = -1
            updateService()
            try {
                val img = withContext(Dispatchers.IO) {
                    com.uyatame.ucrt.rip.BurnPrep.fromFiles(ctx, src, gapSec * 75, burnFile, { log(it) }, { p ->
                        ripOverall = p
                        ripIndex = (p * list.size).toInt().coerceAtMost(list.size - 1)
                        val pct = (p * 100).toInt()
                        if (pct >= notifPct + 2) { notifPct = pct; updateService() }
                    }) { ensureActive() }
                }
                log(S(R.string.prepared_for_writing_tracks_min, title, list.size, "%.1f".format(img.seconds / 60)))
                burn = BurnSession(title, img, 0)
            } catch (e: CancellationException) {
                snack = S(R.string.preparation_canceled)
                runCatching { burnFile.delete() }
                throw e
            } catch (e: Exception) {
                snack = S(R.string.could_not_prepare_tracks, e.message)
                log(S(R.string.prepare_error, e.message))
                runCatching { burnFile.delete() }
            } finally {
                busy = false
                busyKind = 0
                updateService()
            }
        }
    }

    /** 入っているディスクに書き込む */
    fun writeDisc(speedX: Int, testWrite: Boolean) {
        val b = burn ?: return
        val d = drive
        if (d == null) { burn = b.copyMsg(S(R.string.connect_drive), true); return }
        if (busy) return
        if (player.isCd) player.stop()
        val burner = com.uyatame.ucrt.rip.CdBurner(d, com.uyatame.ucrt.rip.BurnOptions(speedX, testWrite)) { log(it) }
        // ディスクを確かめているあいだも busy にして、「書き込む」を続けて押しても二重に書き込まないようにする
        busy = true
        busyKind = 3
        ripCount = 1
        ripIndex = 0
        ripOverall = 0f
        job = viewModelScope.launch {
            var started = false
            try {
                // ディスクを確かめる(空か・容量が足りるか)。入れた直後で回転が安定していなければ少し待つ
                val err = withContext(Dispatchers.IO) {
                    val t0 = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < 30_000) {
                        ensureActive()
                        val e = runCatching { d.testUnitReady() }.exceptionOrNull() as? ScsiException ?: break
                        if (!(e.senseKey == 6 || (e.senseKey == 2 && e.asc == 0x04))) break
                        Thread.sleep(500)
                    }
                    runCatching { burner.checkDisc(b.image); null }.getOrElse { it }
                }
                if (err != null) {
                    val erasable = withContext(Dispatchers.IO) { runCatching { d.readDiscInfo() }.getOrNull() }
                        ?.let { it.erasable && it.status != 0 } == true
                    burn = b.copyMsg(
                        if (err is ScsiException && err.asc == 0x3A) S(R.string.no_disc_inserted) else err.message ?: "",
                        true, erasable,
                    )
                    return@launch
                }
                started = true
                ripStartMs = System.currentTimeMillis()
                notifPct = -1
                updateService()
                withContext(Dispatchers.IO) {
                    burner.burn(b.image, { p ->
                        ripOverall = p
                        val pct = (p * 100).toInt()
                        if (pct >= notifPct + 2) { notifPct = pct; updateService() }
                    }) { ensureActive() }
                }
                log(S(R.string.written, b.title) + if (testWrite) S(R.string.test_write_2) else "")
                withContext(Dispatchers.IO) { runCatching { d.eject() } }
                burn = BurnSession(
                    b.title, b.image, 1,
                    if (testWrite) S(R.string.test_write_complete_disc_can)
                    else S(R.string.finished_writing_cd),
                )
            } catch (e: CancellationException) {
                if (started) burn = b.copyMsg(S(R.string.writing_stopped_this_disc_may), true)
                throw e
            } catch (e: Exception) {
                log(S(R.string.write_error, e.message))
                burn = b.copyMsg(S(R.string.writing_failed, e.message), true)
            } finally {
                busy = false
                busyKind = 0
                updateService()
                lastMedia = 0
            }
        }
    }

    /** 入っている CD-RW を消去する(目次だけを消す速い消去) */
    fun eraseDisc() {
        val b = burn ?: return
        val d = drive ?: return
        if (busy) return
        job = viewModelScope.launch {
            busy = true
            busyKind = 4
            ripOverall = 0f
            ripStartMs = System.currentTimeMillis()
            updateService()
            try {
                withContext(Dispatchers.IO) {
                    d.blank(minimal = true)
                    // 消し終わるまで待つ
                    val t0 = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < 30 * 60_000) {
                        ensureActive()
                        val ok = runCatching { d.testUnitReady() }.isSuccess
                        if (ok && runCatching { d.readDiscInfo().status == 0 }.getOrDefault(false)) break
                        Thread.sleep(1000)
                    }
                }
                burn = BurnSession(b.title, b.image, 0, S(R.string.erased_tap_write))
            } catch (e: CancellationException) {
                burn = b.copyMsg(S(R.string.stopped_waiting_for_erase_drive), true)
                throw e
            } catch (e: Exception) {
                burn = b.copyMsg(S(R.string.could_not_erase, e.message), true)
            } finally {
                busy = false
                busyKind = 0
                updateService()
            }
        }
    }

    /** もう 1 枚書く(同じ内容のまま、ディスク待ちに戻る) */
    fun burnAgain() {
        val b = burn ?: return
        burn = BurnSession(b.title, b.image, 0, S(R.string.insert_blank_cd_r_and))
    }

    /** 書き込みの画面を閉じる(端末に置いた内容も消す) */
    fun closeBurn() {
        if (busy && busyKind >= 3) return
        burn = null
        runCatching { burnFile.delete() }
    }

    private fun BurnSession.copyMsg(msg: String, error: Boolean, canErase: Boolean = false) =
        BurnSession(title, image, stage, msg, error, canErase)

    private fun safe(s: String): String {
        val t = s.replace(unsafeChars, "_").trim()
        // 80 文字で切る。絵文字など(サロゲートペア)の途中で切ると不正な文字になり、ファイルを作れないので避ける
        val cut = if (t.length > 80 && Character.isHighSurrogate(t[79])) t.take(79) else t.take(80)
        return cut.trimEnd('.', ' ').ifEmpty { "_" }
    }

    private val unsafeChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    private fun subDir(p: DocumentFile, name: String): DocumentFile =
        p.findFile(name)?.takeIf { it.isDirectory } ?: p.createDirectory(name) ?: throw IOException(S(R.string.cannot_create_folder, name))

    private fun albumDir(tree: String, s: AppSettings, m: AlbumMeta): DocumentFile {
        var dir = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: throw IOException(S(R.string.cannot_open_save_folder))
        when (s.folderMode) {
            0 -> { dir = subDir(dir, safe(m.artist)); dir = subDir(dir, safe(m.title)) }
            1 -> dir = subDir(dir, safe(m.title))
        }
        return dir
    }

    private fun makeCover(b: ByteArray): Cover {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        val mime = if (o.outMimeType == "image/png") "image/png" else "image/jpeg"
        return Cover(b, mime, maxOf(o.outWidth, 0), maxOf(o.outHeight, 0))
    }

    private fun openOut(f: DocumentFile): OutputStream =
        ctx.contentResolver.openOutputStream(f.uri, "w") ?: throw IOException(S(R.string.cannot_write_to_file))

    private fun saveCoverFile(dir: DocumentFile, c: Cover) {
        val name = if (c.mime == "image/png") "cover.png" else "cover.jpg"
        dir.findFile(name)?.delete()
        val f = dir.createFile(c.mime, name) ?: return
        runCatching { openOut(f).use { it.write(c.data) } }
    }

    /** 1曲を取り込む。戻り値は読み取りエラーのセクター数 */
    private fun ripTrack(
        d: UsbScsiDrive,
        t: TocTrack,
        s: AppSettings,
        m: AlbumMeta,
        dir: DocumentFile,
        cover: Cover?,
        onP: (Float) -> Unit,
        check: () -> Unit,
    ): Int {
        val ai = audioTracks.indexOf(t)
        val title = m.tracks.getOrNull(ai)?.title?.takeIf { it.isNotBlank() } ?: S(R.string.track_2).format(t.number)
        val artist = m.tracks.getOrNull(ai)?.artist?.takeIf { it.isNotBlank() } ?: m.artist
        val total = audioTracks.size
        trackStatus[t.number] = S(R.string.ripping)
        progress[t.number] = 0f
        val name = fileName(t, s, m)
        // 同じ名前のファイルがあるときは、いったん別の名前で取り込み、うまくいってから置き換える
        // (途中で失敗・中止しても、元のファイルが消えないように)
        val existing = dir.findFile(name)
        val file = if (existing == null) {
            dir.createFile(s.format.mime, name)
        } else {
            dir.findFile("$name.part")?.delete()
            dir.createFile("application/octet-stream", "$name.part")
        } ?: throw IOException(S(R.string.cannot_create_file))
        val bytes = (t.endLba - t.startLba).toLong() * 2352
        // 利用者が入力した詳しい情報(ジャンル・作曲・作詞・コメント・歌詞)。WAV には書かない
        val tm = m.tracks.getOrNull(ai)
        val extra = com.uyatame.ucrt.tags.TagExtra(
            m.genre.ifBlank { null }, tm?.composer?.ifBlank { null }, tm?.lyricist?.ifBlank { null },
            tm?.comment?.ifBlank { null }, tm?.lyrics?.ifBlank { null },
        )
        val tags = buildList {
            add("TITLE" to title)
            add("ARTIST" to artist)
            add("ALBUM" to m.title)
            add("ALBUMARTIST" to m.artist)
            add("TRACKNUMBER" to t.number.toString())
            add("TRACKTOTAL" to total.toString())
            add("DISCNUMBER" to m.discNo.toString())
            add("DISCTOTAL" to m.discTotal.toString())
            if (m.date.isNotEmpty()) add("DATE" to m.date)
            m.releaseId?.let { add("MUSICBRAINZ_ALBUMID" to it) }
            addAll(extra.vorbisPairs())
        }
        var os: OutputStream? = null
        var sink: AudioSink? = null
        /** 元のファイルを消した(このあと失敗しても、取り込んだファイルは消さずに残す) */
        var oldGone = false
        val tmp = File(ctx.cacheDir, "rip_tmp.m4a")
        try {
            val sk: AudioSink = when (s.format) {
                AudioFormat.WAV -> {
                    val o = BufferedOutputStream(openOut(file), 1 shl 16); os = o
                    val meta = com.uyatame.ucrt.tags.WavTags.metaChunks(
                        Id3Tags(title, artist, m.title, m.artist, m.date.take(4), t.number, total, m.discNo, m.discTotal),
                        cover,
                    )
                    WavSink(o, bytes, meta)
                }
                AudioFormat.FLAC -> {
                    val o = BufferedOutputStream(openOut(file), 1 shl 16); os = o
                    ffmpegSink(s, listOf("-c:a", "flac", "-compression_level", s.flacLevel.coerceIn(0, 8).toString(), "-f", "flac"), "flac") { f ->
                        f.inputStream().use { com.uyatame.ucrt.rip.FlacHeader.retag(java.io.BufferedInputStream(it, 1 shl 16), o, tags, cover) }
                        o.flush()
                    } ?: FlacSink(o, bytes / 4, s.flacLevel, tags, cover)
                }
                AudioFormat.AAC -> {
                    tmp.delete(); AacSink(tmp.absolutePath, s.aacKbps * 1000)
                }
                AudioFormat.MP3 -> {
                    val o = BufferedOutputStream(openOut(file), 1 shl 16); os = o
                    val id3 = Id3Tags(title, artist, m.title, m.artist, m.date.take(4), t.number, total, m.discNo, m.discTotal)
                    ffmpegSink(
                        s,
                        listOf(
                            "-c:a", "libmp3lame", "-b:a", "${s.mp3Kbps}k", "-compression_level", s.mp3Quality.coerceIn(0, 9).toString(),
                            "-f", "mp3", "-id3v2_version", "0", "-write_id3v1", "0", "-write_xing", "1",
                        ),
                        "mp3",
                    ) { f ->
                        // 曲の情報(ID3v2)はアプリで書き、その後ろに FFmpeg の書いた MP3 を続ける
                        o.write(com.uyatame.ucrt.rip.Id3v2.build(id3, cover, extraFrames = extra.id3Frames()))
                        f.inputStream().use { it.copyTo(o, 1 shl 16) }
                        o.flush()
                    } ?: Mp3Sink(o, s.mp3Kbps, s.mp3Quality, id3, cover, extra.id3Frames()).also { ms ->
                        if (!ms.bitrateApplied) log(S(R.string.mp3_bitrate_setting_not_supported))
                    }
                }
            }
            sink = sk
            var last = 0f
            val res = CdRipper(
                d, RipOptions(s.speedX, s.sectorsPerRead, s.retries, s.useC2, s.abortOnError, s.offsetSamples),
            ).rip(t.startLba, t.endLba, leadOut, sk, { p ->
                if (p - last >= 0.01f) { last = p; progress[t.number] = p; onP(p) }
            }, check)
            sk.finish()
            // 置き換える前に書き込みを閉じる
            os?.close()
            os = null
            if (s.format == AudioFormat.AAC) {
                Mp4Tagger.tag(
                    tmp,
                    Mp4Tags(title, artist, m.title, m.artist, m.date.take(4), t.number, total, m.discNo, m.discTotal),
                    cover, extra,
                )
                openOut(file).use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
                tmp.delete()
            }
            if (existing != null) replaceFile(dir, existing, file, name, s.format.mime) { oldGone = true }
            progress[t.number] = 1f
            trackStatus[t.number] = if (res.errorSectors == 0) S(R.string.done) else S(R.string.done_errors, res.errorSectors)
            if (res.c2Disabled) log(S(R.string.c2_error_detection_is_not))
            return res.errorSectors
        } catch (e: Exception) {
            runCatching { sink?.abort() }
            runCatching { os?.close() }
            os = null
            if (!oldGone) runCatching { file.delete() }
            tmp.delete()
            trackStatus[t.number] = if (e is CancellationException) S(R.string.canceled) else S(R.string.failed)
            throw e
        } finally {
            runCatching { os?.close() }
        }
    }

    /**
     * FFmpeg で変換する書き出し先。設定でオフ・FFmpeg が動かない・起動できないときは null(アプリ内蔵の変換を使う)
     */
    private fun ffmpegSink(s: AppSettings, args: List<String>, ext: String, done: (File) -> Unit): com.uyatame.ucrt.rip.AudioSink? {
        if (!s.useFfmpeg) return null
        val exe = com.uyatame.ucrt.rip.Ffmpeg.binary(ctx) { log(it) } ?: return null
        return runCatching { com.uyatame.ucrt.rip.FfmpegSink(exe, args, File(ctx.cacheDir, "rip_tmp.$ext"), done) }
            .onFailure { log(S(R.string.ffmpeg_failed_using_builtin, it.message ?: it.javaClass.simpleName)) }
            .getOrNull()
    }

    /** 取り込み終えた一時ファイルで、元のファイルを置き換える */
    private fun replaceFile(dir: DocumentFile, old: DocumentFile, tmp: DocumentFile, name: String, mime: String, onOldDeleted: () -> Unit) {
        if (!old.delete()) {
            tmp.delete()
            throw IOException(S(R.string.cannot_replace_existing_file, name))
        }
        onOldDeleted()
        if (tmp.renameTo(name)) return
        // 名前を変えられない保存先では、中身を写す。写せなかったときは、取り込んだファイル(.part)を残す
        val f = dir.createFile(mime, name) ?: throw IOException(S(R.string.cannot_create_file_saved_as, name))
        try {
            openOut(f).use { o ->
                val inp = ctx.contentResolver.openInputStream(tmp.uri) ?: throw IOException(S(R.string.cannot_read_file))
                inp.use { it.copyTo(o, 1 shl 16) }
            }
        } catch (e: Exception) {
            runCatching { f.delete() }
            throw IOException(S(R.string.cannot_replace_file_saved_as, name, e.message), e)
        }
        tmp.delete()
    }

    // ================= 曲の削除 =================

    /** 削除の確認を出す曲(null なら出さない) */
    var deleteRequest by mutableStateOf<List<com.uyatame.ucrt.library.LibTrack>?>(null)

    /**
     * 曲のファイルを削除する(元に戻せない)。
     * CUE シートで分けた曲は、同じファイルのすべての曲を選んだときだけ、元のファイルと CUE シートを消す
     */
    fun deleteTracks(list: List<com.uyatame.ucrt.library.LibTrack>) {
        deleteRequest = null
        if (list.isEmpty()) return
        val cue = com.uyatame.ucrt.library.Cue
        val chosen = list.map { it.uri }.toHashSet()
        val files = LinkedHashSet<String>()
        var kept = 0
        list.filter { !cue.isCue(it.uri) }.forEach { files.add(it.uri) }
        list.filter { cue.isCue(it.uri) }.groupBy { cue.fileUri(it.uri) }.forEach { (file, parts) ->
            val all = libAll.filter { !it.hidden && cue.isCue(it.uri) && cue.fileUri(it.uri) == file }
            if (all.all { it.uri in chosen }) {
                files.add(file)
                parts.mapNotNull { it.sheet }.distinct().forEach { files.add(it) }
            } else {
                kept += parts.size
            }
        }
        // 再生リストから外す(再生中の曲なら次の曲へ)
        val gone = list.map { it.uri }.toHashSet()
        for (i in player.queue.indices.reversed()) {
            if (player.queue[i].uri?.toString() in gone) player.removeAt(i)
        }
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                for (u in files) {
                    val done = runCatching { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, Uri.parse(u)) }
                        .getOrDefault(false)
                    if (done) ok++ else {
                        failed++
                        log(S(R.string.could_not_delete, Uri.decode(u).substringAfterLast('/')))
                    }
                }
            }
            snack = buildString {
                append(S(R.string.deleted_files, ok))
                if (failed > 0) append(S(R.string.could_not_be_deleted, failed))
                if (kept > 0) append(S(R.string.tracks_in_cue_sheet_cant))
            }
            refreshLibrary()
        }
    }

    // ================= ライブラリ =================

    fun setOutputFolder(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        set(Keys.outputUri, uri.toString())
        albums = emptyList()
        refreshLibrary(fresh = true, base = settings.value.copy(outputUri = uri.toString()))
    }

    fun resetDriveSettings() {
        viewModelScope.launch { repo.resetDrive() }
        snack = S(R.string.cd_drive_settings_reset_to)
    }

    /** 端末内の音楽フォルダをライブラリに追加する */
    fun addLibraryFolder(uri: Uri) {
        val cr = ctx.contentResolver
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (runCatching { cr.takePersistableUriPermission(uri, rw) }.isFailure) {
            runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val u = uri.toString()
        viewModelScope.launch {
            val set = repo.updateFolders { it + u }
            refreshLibrary(u, folders = set)
        }
    }

    fun removeLibraryFolder(u: String) {
        viewModelScope.launch {
            val set = repo.updateFolders { it - u }
            if (u != settings.value.outputUri) {
                runCatching {
                    ctx.contentResolver.releasePersistableUriPermission(
                        Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            refreshLibrary(fresh = true, folders = set)
        }
    }

    fun refreshLibrary(
        treeOverride: String? = null,
        fresh: Boolean = false,
        folders: Set<String>? = null,
        base: AppSettings = settings.value,
    ) {
        val trees = (listOfNotNull(treeOverride, base.outputUri) + (folders ?: base.libraryFolders)).distinct()
        if (trees.isEmpty()) { albums = emptyList(); libAll = emptyList(); return }
        if (libScanning) return
        viewModelScope.launch {
            libScanning = true
            libFull = fresh
            libNew = 0
            try {
                val old = if (fresh) emptyList() else libAll
                val light = player.isPlaying
                val list = withContext(Dispatchers.IO) {
                    val all = ArrayList<com.uyatame.ucrt.library.LibTrack>()
                    for (t in trees) {
                        // そのフォルダの、前に読んだ曲(フォルダを読めないときは、これを残す)
                        val prev = libAll.filter { it.uri.startsWith("$t/document/") }
                        runCatching {
                            val got = library.scan(Uri.parse(t), old, light) { i, n -> libProgress = "$i / $n"; libNew = n }
                            // SD カードを外した・許可が外れたなどで一時的に読めないときは、曲を消さずに前のままにする
                            // (消すと、戻ったときに全曲を読み直すことになる)
                            val gone = got.isEmpty() && prev.isNotEmpty() &&
                                runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(t))?.exists() != true }.getOrDefault(true)
                            all.addAll(if (gone) prev else got)
                        }.onFailure {
                            log(S(R.string.cannot_read_folder, folderLabel(t)))
                            all.addAll(prev)
                        }
                    }
                    // 重なったフォルダから同じファイルが 2 回見つかっても、1 曲として扱う(先に読んだ保存先のものを残す)
                    // 一覧が前と同じなら、保存し直さない(曲が多いと数 MB を書き直すことになる。起動するたびに自動で確かめているため)
                    all.distinctBy { Library.docKey(it.uri) }.also { if (it != libAll || library.needsFullRescan) library.saveCache(it) }
                }
                ArtLoader.clearMisses()
                libAll = list
                albums = withContext(Dispatchers.Default) { Library.group(list) }
            } catch (e: Exception) {
                log(S(R.string.library_scan_failed, e.message))
            } finally {
                libScanning = false
                libProgress = ""
            }
        }
    }

    // ================= 取り込み済みアルバムの曲情報取得 =================

    var libCandidates by mutableStateOf<List<ReleaseCandidate>>(emptyList())
        private set
    var libSearching by mutableStateOf(false)
        private set
    var libMessage by mutableStateOf("")
        private set
    var libApplying by mutableStateOf(false)
        private set
    /** 曲情報を変えたあとに開き直すアルバム名 */
    var pendingAlbumTitle by mutableStateOf<String?>(null)

    /**
     * ファイルの曲の長さからCDの目次(TOC)を組み立て、MusicBrainz で近いアルバムを探す。
     * アルバム名が分かっていれば、名前での検索結果も合わせて出す。
     */
    fun libLookup(a: LibAlbum) {
        if (libSearching) return
        libCandidates = emptyList()
        libMessage = S(R.string.searching)
        libSearching = true
        val n = a.tracks.size
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    val out = ArrayList<com.uyatame.ucrt.meta.SourceResult>()
                    if (a.tracks.all { it.durationMs > 0 }) {
                        val offs = ArrayList<Int>()
                        var pos = 150
                        for (t in a.tracks) { offs.add(pos); pos += (t.durationMs * 75 / 1000).toInt() }
                        val toc = (listOf(1, n, pos) + offs).joinToString("+")
                        out.add(MusicBrainz.lookup(MusicBrainz.DiscToc("-", toc, n)))
                    }
                    val known = a.title != UNKNOWN_ALBUM && a.title != "不明なアルバム" && a.title != "Unknown album"
                    if (known) {
                        out.add(MusicBrainz.search(a.title, if (a.artist == UNKNOWN_ARTIST) "" else a.artist, n))
                    }
                    out
                }
                results.filter { it.error != null }.forEach { log("${sourceName(it.source)}: ${it.error}") }
                val seen = HashSet<String>()
                libCandidates = results.flatMap { it.candidates }
                    .filter { seen.add((it.meta.releaseId ?: it.meta.title) + "/" + it.meta.discNo) }
                    .sortedWith(compareByDescending<ReleaseCandidate> { it.trackCount == n }.thenByDescending { it.loader == null })
                libMessage = when {
                    libCandidates.isNotEmpty() -> S(R.string.matches_matching_track_counts_first, libCandidates.size)
                    results.isNotEmpty() && results.all { it.error != null } -> S(R.string.search_failed_due_to_network)
                    else -> S(R.string.nothing_found_try_searching_by)
                }
            } finally {
                libSearching = false
            }
        }
    }

    fun libSearch(a: LibAlbum, album: String, artist: String) {
        if (libSearching) return
        libSearching = true
        libMessage = S(R.string.searching)
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { MusicBrainz.search(album, artist, a.tracks.size) }
                libCandidates = r.candidates
                libMessage = when {
                    r.error != null -> S(R.string.search_failed, r.error)
                    r.candidates.isEmpty() -> S(R.string.nothing_found_try_different_spelling_2)
                    else -> S(R.string.found_matching_track_counts_first_2, r.candidates.size)
                }
            } finally {
                libSearching = false
            }
        }
    }

    /** 選んだ候補の曲情報(とジャケット)を、取り込み済みのファイルに書き込む */
    fun libApply(a: LibAlbum, c: ReleaseCandidate, onDone: () -> Unit) {
        if (libApplying || tagSaving) return
        libApplying = true
        viewModelScope.launch {
            try {
                val m = withContext(Dispatchers.IO) { c.loader?.let { runCatching { it() }.getOrNull() } ?: c.meta }
                if (m.tracks.isEmpty()) { snack = S(R.string.could_not_load_track_list); return@launch }
                val cover = m.releaseId?.let { id ->
                    withContext(Dispatchers.IO) { MusicBrainz.cover(id).data?.let { runCatching { prepareCoverImage(it) }.getOrNull() } }
                }
                if (m.tracks.size != a.tracks.size) {
                    log(S(R.string.track_counts_differ_files_match, a.tracks.size, m.tracks.size))
                }
                val titles = a.tracks.mapIndexed { i, t -> m.tracks.getOrNull(i)?.title?.takeIf { it.isNotBlank() } ?: t.title }
                val artists = a.tracks.mapIndexed { i, t -> m.tracks.getOrNull(i)?.artist?.takeIf { it.isNotBlank() } ?: m.artist.ifBlank { t.artist } }
                pendingAlbumTitle = m.title
                saveAlbumTags(a, m.title, m.artist, m.date, titles, artists, null, cover)
                onDone()
            } finally {
                libApplying = false
            }
        }
    }

    // ================= タグ編集 =================

    /** 曲情報の編集で入力された番号(曲ごとのトラック番号・ディスク番号と、曲数・枚数。null は今のまま) */
    class TagNumbers(val tracks: List<Int?>, val trackTotal: Int?, val discs: List<Int?>, val discTotal: Int?)

    fun saveAlbumTags(
        a: LibAlbum,
        album: String,
        albumArtist: String,
        year: String,
        titles: List<String>,
        artists: List<String>,
        newCover: Uri?,
        coverData: ByteArray? = null,
        /** 曲ごとの詳しい情報(null・空の TagExtra なら書き換えない) */
        extras: List<com.uyatame.ucrt.tags.TagExtra?>? = null,
        /** 入力されたトラック番号・曲数・ディスク番号・枚数(null・空欄なら今の値のまま) */
        numbers: TagNumbers? = null,
    ) {
        if (tagSaving) return
        viewModelScope.launch {
            tagSaving = true
            try {
                val coverBytes = coverData ?: newCover?.let { u ->
                    withContext(Dispatchers.IO) {
                        val raw = ctx.contentResolver.openInputStream(u)?.use { com.uyatame.ucrt.tags.readCoverImage(it) } ?: throw IOException(S(R.string.cannot_open_image))
                        prepareCoverImage(raw)
                    }
                }
                val trackTotal = numbers?.trackTotal ?: maxOf(a.tracks.size, a.tracks.maxOf { it.track })
                val discTotal = numbers?.discTotal ?: a.tracks.maxOf { it.disc }.coerceAtLeast(1)
                var ok = 0
                var skipped = 0
                var failed = 0
                withContext(Dispatchers.IO) {
                    a.tracks.forEachIndexed { i, t ->
                        try {
                            val v = TagValues(
                                titles.getOrElse(i) { t.title }.trim(), artists.getOrElse(i) { t.artist }.trim(),
                                album.trim(), albumArtist.trim(), year.trim(),
                                numbers?.tracks?.getOrNull(i) ?: t.track, trackTotal, numbers?.discs?.getOrNull(i) ?: t.disc, discTotal,
                                extras?.getOrNull(i) ?: com.uyatame.ucrt.tags.TagExtra(),
                            )
                            if (TagEditor.edit(ctx, Uri.parse(t.uri), v, coverBytes)) ok++ else skipped++
                        } catch (e: Exception) {
                            failed++
                            log(S(R.string.failed_to_save_tags, t.title, e.message))
                        }
                    }
                }
                ArtLoader.forget(a.key)
                // 曲ごとのジャケットも読み直す
                a.tracks.forEach { ArtLoader.forget("t:" + it.uri) }
                snack = buildString {
                    append(S(R.string.saved_track_info_for_tracks, ok))
                    if (skipped > 0) append(S(R.string.skipped_tracks_in_formats_that, skipped))
                    if (failed > 0) append(S(R.string.tracks_failed, failed))
                }
                refreshLibrary()
            } catch (e: Exception) {
                snack = S(R.string.could_not_save, e.message)
            } finally {
                tagSaving = false
            }
        }
    }

    /** 曲ごとの今の詳しい情報(ジャンル・作曲・作詞・コメント・歌詞)を読む。書けない形式・読めなかった曲は null */
    suspend fun readAlbumExtras(a: LibAlbum): List<com.uyatame.ucrt.tags.TagExtra?> = withContext(Dispatchers.IO) {
        a.tracks.map { com.uyatame.ucrt.tags.TagExtra.read(ctx, it.uri) }
    }

    // ================= 再生 =================

    /** ジャケットの読み方(キャッシュの名前・読む曲・フォルダの画像・代わりに読む曲) */
    class ArtRef(val key: String, val trackUri: String, val coverUri: String?, val fallbackUri: String?)

    /**
     * 曲のジャケット。perTrack なら曲ごとに読む(曲に埋め込まれた画像 → アルバムの最初の曲の画像 → フォルダの画像)。
     * そうでなければアルバムのジャケットを使う
     */
    fun artRef(t: com.uyatame.ucrt.library.LibTrack, perTrack: Boolean = settings.value.trackArt): ArtRef {
        val album = albumKeyOf(t.uri)
        return if (perTrack) {
            ArtRef("t:" + t.uri, t.uri, t.coverUri, albumByKey(album)?.first?.uri?.takeIf { it != t.uri })
        } else ArtRef(album ?: t.uri, t.uri, t.coverUri, null)
    }

    /** ライブラリの曲を、再生用の項目にする */
    fun itemOf(t: com.uyatame.ucrt.library.LibTrack): PlayItem {
        val r = artRef(t)
        return PlayItem(
            t.title, t.artist, t.album, t.durationMs, Uri.parse(t.uri), null,
            r.key, r.trackUri, r.coverUri, t.folderId,
            startMs = t.startMs, endMs = t.endMs, artFallbackUri = r.fallbackUri,
        )
    }

    fun playAlbum(a: LibAlbum, start: Int, shuffle: Boolean = false) = playTracks(a.tracks, start, shuffle, PlaySource(PlaySource.ALBUM, a.key))

    /**
     * 再生しているリストの出どころ(アルバム・アーティスト・ジャンル・年・プレイリスト・フォルダ)。
     * 設定の「最後まで再生したら次のリストへ」で、次のリストを探すのに使う。null = それ以外(曲の一覧・検索など)
     */
    class PlaySource(val kind: String, val key: String) {
        companion object {
            const val ALBUM = "album"
            const val ARTIST = "artist"
            const val GENRE = "genre"
            const val YEAR = "year"
            const val PLAYLIST = "playlist"
            const val FOLDER = "folder"
        }
    }

    var playSource: PlaySource? = null
        private set

    /** 曲の一覧を再生する(shuffle なら順番を混ぜる)。source: どのリストか(次のリストへ進むため) */
    fun playTracks(list: List<com.uyatame.ucrt.library.LibTrack>, start: Int, shuffle: Boolean = false, source: PlaySource? = null) {
        if (list.isEmpty()) return
        playSource = source
        val items = list.map { itemOf(it) }
        if (shuffle) {
            if (!player.shuffle) player.toggleShuffle()
            player.playFiles(items, (0 until items.size).random())
        } else {
            player.playFiles(items, start)
        }
    }

    // ---------------- 最後まで再生したら次のリストへ ----------------

    private fun folderOf(t: com.uyatame.ucrt.library.LibTrack): String = Uri.decode(t.folderId).substringAfter(':', "")

    /** アーティストの曲(アーティストのページと同じ並び: そのアーティストのアルバムを新しい順に、そのあと参加した曲) */
    fun artistTracks(name: String): List<com.uyatame.ucrt.library.LibTrack> {
        val main = albums.filter { it.artist == name }.sortedByDescending { it.year }
        val guest = albums.filter { a -> a.artist != name && a.tracks.any { it.artist == name } }
        return main.flatMap { it.tracks } + guest.flatMap { a -> a.tracks.filter { it.artist == name } }
    }

    /** ジャンルの曲(ジャンルのページと同じく、アルバムごとにまとめる) */
    fun genreTracks(g: String): List<com.uyatame.ucrt.library.LibTrack> =
        albums.flatMap { a -> a.tracks.filter { it.genre == g } }.groupBy { albumKeyOf(it.uri) ?: it.album }.values.flatten()

    /** フォルダの曲(子フォルダも含め、フォルダ・曲順に並べる) */
    fun folderTracksOf(path: String): List<com.uyatame.ucrt.library.LibTrack> =
        albums.asSequence().flatMap { it.tracks }
            .filter { val p = folderOf(it); p == path || path.isEmpty() || p.startsWith("$path/") }
            .sortedWith(compareBy({ folderOf(it).lowercase() }, { it.disc }, { it.track }, { Uri.decode(it.uri).lowercase() }))
            .toList()

    /**
     * 今のリストの次のリスト(ライブラリの画面の並び順で次。最後なら最初へ戻る。曲の無いリストは飛ばす)。
     * 次のリストの出どころ・名前・曲を返す。見つからなければ null
     */
    private fun nextList(src: PlaySource): Triple<PlaySource, String, List<com.uyatame.ucrt.library.LibTrack>>? {
        fun <T> after(keys: List<T>, cur: T): List<T> {
            val i = keys.indexOf(cur)
            return if (i < 0) keys else keys.drop(i + 1) + keys.take(i)
        }
        when (src.kind) {
            PlaySource.ALBUM -> {
                val order = albums.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
                val a = after(order.map { it.key }, src.key).firstNotNullOfOrNull { k -> order.firstOrNull { it.key == k && it.tracks.isNotEmpty() } } ?: return null
                return Triple(PlaySource(src.kind, a.key), a.title, a.tracks)
            }
            PlaySource.ARTIST -> {
                val names = albums.map { it.artist }.distinct().sortedBy { it.lowercase() }
                for (n in after(names, src.key)) {
                    val t = artistTracks(n)
                    if (t.isNotEmpty()) return Triple(PlaySource(src.kind, n), n, t)
                }
            }
            PlaySource.GENRE -> {
                val gs = albums.flatMap { it.tracks }.map { it.genre }.distinct().sortedWith(compareBy({ it.isEmpty() }, { it.lowercase() }))
                for (g in after(gs, src.key)) {
                    val t = genreTracks(g)
                    if (t.isNotEmpty()) return Triple(PlaySource(src.kind, g), g.ifEmpty { S(R.string.unknown_genre) }, t)
                }
            }
            PlaySource.YEAR -> {
                val ys = albums.map { it.year }.distinct().sortedWith(compareBy<String> { it.isEmpty() }.thenByDescending { it })
                for (y in after(ys, src.key)) {
                    val t = albums.filter { it.year == y }.flatMap { it.tracks }
                    if (t.isNotEmpty()) return Triple(PlaySource(src.kind, y), y.ifEmpty { S(R.string.unknown_year) }, t)
                }
            }
            PlaySource.PLAYLIST -> {
                val pls = store.playlists
                for (id in after(pls.map { it.id }, src.key)) {
                    val p = pls.firstOrNull { it.id == id } ?: continue
                    val t = playlistTracks(p)
                    if (t.isNotEmpty()) return Triple(PlaySource(src.kind, id), p.name, t)
                }
            }
            PlaySource.FOLDER -> {
                // 曲の入っているフォルダを名前順に並べ、今のフォルダ(とその中)より後ろの最初のフォルダ
                val dirs = albums.asSequence().flatMap { it.tracks }.map { folderOf(it) }.distinct().sortedBy { it.lowercase() }.toList()
                val cur = src.key
                val later = dirs.filter { it.lowercase() > cur.lowercase() && !it.startsWith("$cur/") && it != cur }
                for (d in later + dirs.filter { it !in later && it != cur && !it.startsWith("$cur/") }) {
                    val t = folderTracksOf(d)
                    if (t.isNotEmpty()) return Triple(PlaySource(src.kind, d), d.substringAfterLast('/').ifEmpty { "/" }, t)
                }
            }
        }
        return null
    }

    /** 並びを最後まで再生し終えたとき: 設定がオンなら、次のリストを続けて再生する(シャッフルはそのまま) */
    private fun playNextList(): Boolean {
        if (!settings.value.autoNextList || player.repeatMode != 0) return false
        val src = playSource ?: return false
        val (ns, name, tracks) = nextList(src) ?: return false
        log("auto next list: ${src.kind} -> ${ns.key}")
        playTracks(tracks, 0, player.shuffle, ns)
        snack = S(R.string.auto_next_now, name)
        return true
    }

    /** ライブラリ全体をシャッフル再生 */
    fun shuffleAll() = playTracks(albums.flatMap { it.tracks }, 0, shuffle = true)

    fun playNext(list: List<com.uyatame.ucrt.library.LibTrack>) {
        if (list.isEmpty()) return
        val wasEmpty = player.current == null
        player.playNext(list.map { itemOf(it) })
        if (!wasEmpty) snack = S(R.string.playing_next_songs, list.size)
    }

    fun addToQueue(list: List<com.uyatame.ucrt.library.LibTrack>) {
        if (list.isEmpty()) return
        val wasEmpty = player.current == null
        player.addToQueue(list.map { itemOf(it) })
        if (!wasEmpty) snack = S(R.string.added_to_end_of_queue, list.size)
    }

    // ---- お気に入り・プレイリスト ----

    fun toggleFav(uri: String) {
        val on = !store.isFav(uri)
        store.toggleFav(uri)
        snack = if (on) S(R.string.added_to_favorites) else S(R.string.removed_from_favorites)
    }

    fun setFav(list: List<com.uyatame.ucrt.library.LibTrack>, on: Boolean) {
        store.setFav(list.map { it.uri }, on)
        snack = if (on) S(R.string.added_songs_to_favorites, list.size)
        else S(R.string.removed_from_favorites)
    }

    fun toggleFavAlbum(key: String) {
        val on = !store.isFavAlbum(key)
        store.toggleFavAlbum(key)
        snack = if (on) S(R.string.album_added_to_favorites) else S(R.string.removed_from_favorites)
    }

    /** 音質チェック用の曲に入れる・外す */
    fun toggleCheck(uri: String) {
        val on = !store.isCheck(uri)
        store.setCheck(listOf(uri), on)
        snack = if (on) S(R.string.added_to_sound_check) else S(R.string.removed_from_sound_check)
    }

    fun setCheck(list: List<com.uyatame.ucrt.library.LibTrack>, on: Boolean) {
        store.setCheck(list.map { it.uri }, on)
        snack = if (on) S(R.string.added_songs_to_sound_check, list.size) else S(R.string.removed_from_sound_check)
    }

    fun addToPlaylist(id: String, uris: List<String>) {
        if (uris.isEmpty()) return
        store.addToPlaylist(id, uris)
        val name = store.playlists.firstOrNull { it.id == id }?.name.orEmpty()
        snack = S(R.string.added_songs_to, uris.size, name)
    }

    fun createPlaylist(name: String, uris: List<String>) {
        store.createPlaylist(name, uris)
        snack = S(R.string.created_playlist, name.trim())
    }

    /**
     * ほかのアプリのプレイリスト(M3U・M3U8・PLS・XSPF・WPL)を読み込む。ファイル名・フォルダ名(見つからなければ曲名)で
     * ライブラリの曲に結び付け、ファイルごとに新しいプレイリストを作る。文字コードは UTF-8、だめなら Shift_JIS
     */
    fun importPlaylists(uris: List<Uri>) {
        viewModelScope.launch {
            val all = albums.flatMap { it.tracks }
            var made = 0; var songs = 0; var missing = 0
            for (u in uris) {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val name = UpsampleJobs.nameOf(ctx, u)
                        val bytes = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return@runCatching null
                        val text = runCatching {
                            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                        }.getOrElse { String(bytes, charset("windows-31j")) }
                        val p = com.uyatame.ucrt.library.PlaylistFiles.parse(text, name.substringAfterLast('.', ""))
                        val (found, miss) = com.uyatame.ucrt.library.PlaylistFiles.match(p.entries, all)
                        Triple(p.name ?: name.substringBeforeLast('.'), found, miss)
                    }.onFailure { log("playlist import: ${it.message}") }.getOrNull()
                } ?: continue
                if (r.second.isEmpty()) { missing += r.third; continue }
                store.createPlaylist(r.first, r.second)
                made++; songs += r.second.size; missing += r.third
            }
            snack = if (made == 0) S(R.string.playlist_import_none) else S(R.string.playlist_imported, made, songs) +
                (if (missing > 0) S(R.string.playlist_import_missing, missing) else "")
        }
    }

    /** プレイリストを M3U8 に書き出す(曲の場所は、分かればファイルのパス) */
    fun exportPlaylist(p: com.uyatame.ucrt.data.Playlist, to: Uri) {
        viewModelScope.launch {
            val text = com.uyatame.ucrt.library.PlaylistFiles.toM3u8(p.name, playlistTracks(p)) { u ->
                com.uyatame.ucrt.library.StorageAccess.fileOf(Uri.parse(u))?.path
            }
            snack = runCatching {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(to, "wt")?.use { it.write(text.toByteArray()) } ?: error("open") }
                S(R.string.playlist_exported, p.name)
            }.getOrElse { S(R.string.could_not_save, it.message) }
        }
    }

    /** 今の再生リストをプレイリストとして保存する(CD の曲は除く) */
    fun saveQueueAsPlaylist(name: String) {
        createPlaylist(name, player.queue.mapNotNull { it.uri?.toString() })
    }

    /** プレイリストの曲(ライブラリに見つからない曲は除く) */
    fun playlistTracks(p: com.uyatame.ucrt.data.Playlist): List<com.uyatame.ucrt.library.LibTrack> {
        val map = trackMap
        return p.uris.mapNotNull { map[it] }
    }

    fun playCd(t: TocTrack? = null) {
        val d = drive ?: return
        if (busy) return
        val audio = audioTracks
        if (audio.isEmpty()) return
        val items = audio.map { tr ->
            PlayItem(trackTitle(tr), trackArtist(tr), albumTitle, (tr.endLba - tr.startLba) * 1000L / 75, cdTrack = tr, artKey = "cd")
        }
        val start = if (t == null) 0 else audio.indexOf(t).coerceAtLeast(0)
        player.playCd(d, leadOut, items, start, settings.value.cdPlaySpeed)
    }
}
