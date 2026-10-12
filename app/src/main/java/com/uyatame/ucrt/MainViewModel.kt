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
    class OpenAlbum(text: String, val album: String, val artist: String) : ActionSnack(text, T("ライブラリで見る", "View in library"))
    class UndoMeta(text: String) : ActionSnack(text, T("元に戻す", "Undo"))
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
    var status by mutableStateOf(T("ドライブ未接続", "No drive connected"))
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

    private class LibIndex(val tracks: Map<String, com.uyatame.ucrt.library.LibTrack>, val albumOf: Map<String, String>)
    private var indexFor: List<LibAlbum>? = null
    private var indexCache = LibIndex(emptyMap(), emptyMap())

    private fun libIndex(): LibIndex {
        val a = albums
        if (a !== indexFor) {
            val t = HashMap<String, com.uyatame.ucrt.library.LibTrack>()
            val k = HashMap<String, String>()
            for (al in a) for (tr in al.tracks) { t[tr.uri] = tr; k[tr.uri] = al.key }
            indexCache = LibIndex(t, k)
            indexFor = a
        }
        return indexCache
    }

    /** URI から曲を引く */
    val trackMap: Map<String, com.uyatame.ucrt.library.LibTrack> get() = libIndex().tracks

    /** 曲の URI から、その曲のアルバムのキーを引く */
    fun albumKeyOf(uri: String?): String? = uri?.let { libIndex().albumOf[it] }

    fun albumByKey(key: String?): LibAlbum? = key?.let { k -> albums.firstOrNull { it.key == k } }
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

    val audioTracks: List<TocTrack> get() = tracks.filter { it.isAudio }
    val albumTitle: String get() = meta?.title ?: UNKNOWN_ALBUM
    val albumArtist: String get() = meta?.artist ?: UNKNOWN_ARTIST

    fun trackTitle(t: TocTrack): String =
        meta?.tracks?.getOrNull(audioTracks.indexOf(t))?.title?.takeIf { it.isNotBlank() } ?: T("トラック %02d", "Track %02d").format(t.number)

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
            else status = T("USBの使用が許可されませんでした", "USB access was not allowed")
        }
    }
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // 外れたのが使っているドライブでなければ(USB DAC や USB メモリなど)、何もしない
            val dev = androidx.core.content.IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val d = drive ?: return
            if (dev != null && dev.deviceName != d.deviceName) return
            closeDrive(T("ドライブが取り外されました", "The drive was disconnected"))
        }
    }

    init {
        com.uyatame.ucrt.library.StorageAccess.init(app)
        ContextCompat.registerReceiver(app, permReceiver, IntentFilter(permAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            app, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        MediaBridge.player = player
        // 再生エンジンの診断メッセージをアプリのログに出す
        com.uyatame.ucrt.player.AudioEngine.logger = { msg -> log(msg) }
        // 音量キーで DAC の音量を変えたら保存する
        com.uyatame.ucrt.player.AudioEngine.onUsbVolume = { v -> set(com.uyatame.ucrt.data.Keys.usbVolume, v) }
        // 保存されている音響設定を再生エンジンに反映し続ける
        viewModelScope.launch {
            var lastMode: Int? = null
            settings.collect { s ->
                com.uyatame.ucrt.player.AudioEngine.setEq(
                    s.eqEnabled, com.uyatame.ucrt.player.EqBands.decode(s.eqGains), s.eqPreamp, s.eqAutoPreamp,
                )
                val mode = s.outputMode
                com.uyatame.ucrt.player.AudioEngine.setOutputMode(mode)
                // ビットパーフェクト再生がオンなら、USB DAC の使用許可を先にもらっておく
                if (mode == 2 && lastMode != 2) com.uyatame.ucrt.player.usb.UsbDac.requestPermissionIfNeeded()
                player.setUsbVolume(s.usbVolume)
                player.gapless = s.gapless
                val dsdChanged = com.uyatame.ucrt.player.AudioEngine.dsdMode != s.dsdMode ||
                    com.uyatame.ucrt.player.AudioEngine.dsdSwap != s.dsdSwap
                com.uyatame.ucrt.player.AudioEngine.dsdMode = s.dsdMode
                com.uyatame.ucrt.player.AudioEngine.dsdSwap = s.dsdSwap
                val devChanged = com.uyatame.ucrt.player.AudioEngine.preferredKey != s.outputDevice
                com.uyatame.ucrt.player.AudioEngine.preferredKey = s.outputDevice
                val eng = com.uyatame.ucrt.player.AudioEngine
                val darChanged = eng.darMode != s.darMode || eng.darPcm != s.darPcm || eng.darDsd != s.darDsd
                eng.darMode = s.darMode
                eng.darPcm = s.darPcm
                eng.darDsd = s.darDsd
                // DAR は USB DAC 直接出力の PCM の曲だけに使う。DAR の DSD 変換は DSD の出力の設定も使う
                val darReload = mode == 2 && !player.isDsd && (darChanged || (dsdChanged && s.darMode == 2))
                if (lastMode != null && (lastMode != mode || (dsdChanged && player.isDsd) || (devChanged && mode != 2) || darReload)) player.reloadOutput()
                lastMode = mode
            }
        }
        // 以前の版で保存した連絡先メールアドレスは不要になったので消す
        viewModelScope.launch { repo.set(com.uyatame.ucrt.data.Keys.contactEmail, "") }
        player.onStateChanged = {
            updateService()
            loadPlayerArt()
            recordHistory()
            saveResume()
        }
        viewModelScope.launch {
            store.load()
            albums = Library.group(withContext(Dispatchers.IO) { library.loadCache() })
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

    override fun onCleared() {
        // 終了処理で再生リストが空になる前に、続きを保存しておく
        saveJob?.cancel()
        writeResume()
        resumeReady = false
        player.onStateChanged = null
        com.uyatame.ucrt.player.AudioEngine.logger = null
        com.uyatame.ucrt.player.AudioEngine.onUsbVolume = null
        runCatching { ctx.unregisterReceiver(permReceiver) }
        runCatching { ctx.unregisterReceiver(detachReceiver) }
        player.release()
        drive?.close()
        MediaBridge.player = null
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

    /** イコライザーの帯域を動かしている途中(保存はせず、音だけすぐ変える) */
    fun previewEq(gains: FloatArray, preamp: Float) {
        val s = settings.value
        com.uyatame.ucrt.player.AudioEngine.setEq(s.eqEnabled, gains, preamp, s.eqAutoPreamp)
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
        player.setUsbVolume(v)
        if (save) set(com.uyatame.ucrt.data.Keys.usbVolume, v)
    }

    fun saveEq(gains: FloatArray, preamp: Float, preset: Int) {
        viewModelScope.launch {
            repo.set(Keys.eqGains, com.uyatame.ucrt.player.EqBands.encode(gains))
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
                    false, T("CDを取り込み中 ${ripIndex + 1}/$ripCount", "Ripping CD ${ripIndex + 1}/$ripCount"),
                    "$pct% ・ " + albumTitle, progress = pct,
                    type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            }
            cur != null && player.started -> NotifInfo(
                true, cur.title, cur.artist + if (cur.album.isNotEmpty()) " ・ " + cur.album else "",
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

    /** 再生中の曲のジャケットを、通知・ロック画面に渡す */
    private fun loadPlayerArt() {
        val cur = player.current
        if (cur == artFor) return
        artFor = cur
        if (cur == null) { player.setArt(null); return }
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    if (cur.cdTrack != null) coverBytes?.let { ArtLoader.decode(it, 512) }
                    else cur.artKey?.let { ArtLoader.load(ctx, it, cur.artTrackUri, cur.artCoverUri)?.asAndroidBitmap() }
                }.getOrNull()
            }
            if (player.current == cur) {
                player.setArt(bmp)
                updateService()
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
                T("このドライブはUAS専用のため使用できません", "This drive supports UAS only and cannot be used")
            } else {
                T("ドライブが見つかりません", "No drive found")
            }
            return
        }
        if (usb.hasPermission(dev)) {
            openDevice(dev)
        } else {
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(permAction).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            status = T("USBの使用許可を待っています…", "Waiting for USB permission…")
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
                drive?.close()
                drive = d
                connected = true
                driveName = d.name
                status = T("接続済み", "Connected")
                discText = T("ディスクを確認中…", "Checking disc…")
                val inq = withContext(Dispatchers.IO) {
                    var r = ""
                    for (a in 0 until 3) {
                        try { r = d.inquiry(); break } catch (e: Exception) { r = ""; Thread.sleep(500) }
                    }
                    r
                }
                if (inq.isNotEmpty()) { driveName = inq.substringBefore(" (type"); log(T("ドライブ: $inq", "Drive: $inq")) }
                lastMedia = 0
                startMonitor(d)
            } catch (e: Exception) {
                status = T("接続できませんでした: ${e.message}", "Could not connect: ${e.message}")
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

    private fun closeDrive(msg: String) {
        job?.cancel()
        monitorJob?.cancel()
        if (player.isCd) player.stop()
        drive?.close()
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

    private fun mediaState(d: UsbScsiDrive): Int = try {
        d.testUnitReady()
        mediaReady
    } catch (e: ScsiException) {
        when {
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
                if (!busy && !discBusy && !playingCd) {
                    val st = withContext(Dispatchers.IO) { mediaState(d) }
                    if (!busy && !discBusy && drive === d) {
                        when (st) {
                            mediaReady, mediaChanged -> if (lastMedia != mediaReady || st == mediaChanged) {
                                lastMedia = mediaReady
                                refreshDisc()
                            }
                            mediaNone -> if (lastMedia != mediaNone) {
                                lastMedia = mediaNone
                                clearDisc(T("ディスクを入れてください", "Insert a disc"))
                            }
                            mediaLoading -> if (lastMedia != mediaLoading) {
                                lastMedia = mediaLoading
                                discText = T("ディスクを読み込み中…", "Loading disc…")
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
        e.senseKey == -1 -> e.message ?: T("通信エラー", "Communication error")
        e.asc == 0x3A -> T("ディスクなし", "No disc")
        e.asc == 0x04 -> T("読み込み中", "Loading")
        e.senseKey == 6 -> T("ディスク交換を検知", "Disc change detected")
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
        if (ready) log(T("ディスクの準備完了(%.1f 秒)", "Disc ready (%.1f s)").format((System.currentTimeMillis() - t0) / 1000.0))
        if (!ready) {
            if (noMedia >= 3) return DiscScan(T("ディスクを入れてください", "Insert a disc"), emptyList(), 0)
            log(T("ドライブ未準備: $lastErr", "Drive not ready: $lastErr"))
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
        val pname = if (p < 0) T("種別不明", "Unknown type") else UsbScsiDrive.profileName(p)
        if (toc != null) {
            if (p in 0x08..0x0A) return DiscScan(T("データCDです。音楽CDのみ再生・取り込みできます", "This is a data CD. Only audio CDs can be played or ripped"), emptyList(), 0)
        }
        var capErr = ""
        val cap = try { d.readCapacity() } catch (e: ScsiException) { capErr = why(e); null }
        if (cap != null) {
            return DiscScan(
                T("$pname(%.1f GB)のディスクです。音楽CDのみ再生・取り込みできます", "This is a $pname (%.1f GB) disc. Only audio CDs can be played or ripped").format(cap.first * cap.second / 1e9),
                emptyList(), 0,
            )
        }
        log(T("ディスクを認識できません(TOC: $tocErr / 容量: $capErr)", "Cannot recognize the disc (TOC: $tocErr / capacity: $capErr)"))
        return DiscScan(if (ready) T("ディスクを読み取れません", "Cannot read the disc") else T("ドライブの準備ができません", "The drive is not ready"), emptyList(), 0, retry = true)
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
                discOk = !r.retry && r.text != T("ディスクを入れてください", "Insert a disc")
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
                            metaStatus = "✓ " + T("曲情報: 前回の内容(選び直すこともできます)", "Track info: saved from last time (you can choose again)")
                            val img = withContext(Dispatchers.IO) { discCache.loadCover(id) }
                            if (img != null) {
                                coverBytes = img
                                cdCover = withContext(Dispatchers.IO) { ArtLoader.decode(img, 600)?.asImageBitmap() }
                            }
                            log(T("保存済みの曲情報を使いました", "Used saved track info"))
                        } else if (settings.value.autoMeta) fetchMeta() else metaStatus = T("曲情報は手動で編集できます", "You can edit track info manually")
                    }
                }
            } catch (e: Exception) {
                discText = T("読み取りに失敗しました: ${e.message}", "Read failed: ${e.message}")
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
                clearDisc(T("ディスクを入れてください", "Insert a disc"))
                lastMedia = mediaNone
            } catch (e: Exception) {
                snack = T("取り出せませんでした", "Could not eject")
                log(T("イジェクト失敗: ${e.message}", "Eject failed: ${e.message}"))
            }
        }
    }

    // ================= 曲情報 =================

    private fun fitMeta(m: AlbumMeta): AlbumMeta {
        val n = audioTracks.size
        return m.copy(tracks = (0 until n).map { i ->
            m.tracks.getOrNull(i) ?: TrackMeta(T("トラック %02d", "Track %02d").format(audioTracks[i].number), m.artist)
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
        metaStatus = T("曲情報を検索しています…", "Searching track info…")
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
                    if (r.error != null) log(T("${sourceName(r.source)}: 取得失敗 (${r.error})", "${sourceName(r.source)}: failed (${r.error})"))
                    else log(T("${sourceName(r.source)}: ${r.candidates.size}件", "${sourceName(r.source)}: ${r.candidates.size} results"))
                }
                val all = results.flatMap { it.candidates }
                    .sortedWith(compareByDescending<ReleaseCandidate> { it.exact }.thenBy { if (it.source == MusicBrainz.SOURCE) 0 else 1 })
                candidates = all
                val exact = all.filter { it.exact }
                when {
                    all.isEmpty() && results.all { it.error != null } -> {
                        metaStatus = T("曲情報を取得できませんでした", "Could not get track info")
                        metaError = results.joinToString("\n") { "${sourceName(it.source)}: ${it.error}" }
                        showChooser = true
                    }
                    all.isEmpty() -> {
                        metaStatus = T("候補が見つかりませんでした。名前で検索できます", "No matches found. You can search by name")
                        showChooser = true
                    }
                    !openChooserAlways && all.size == 1 && exact.size == 1 -> {
                        applyCandidate(all[0])
                    }
                    else -> {
                        metaStatus = T("${all.size}件の候補から選んでください", "Choose from ${all.size} matches")
                        showChooser = true
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                metaStatus = T("曲情報を取得できませんでした", "Could not get track info")
                metaError = com.uyatame.ucrt.meta.describeNetError(e)
                log(T("曲情報の取得失敗: ${e.message}", "Track info lookup failed: ${e.message}"))
            } finally {
                if (metaJob === coroutineContext[Job]) metaSearching = false
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
            if (!metaSearching) metaStatus = T("曲情報は手動で編集できます", "You can edit track info manually")
        }
    }

    /** アルバム名・アーティスト名で MusicBrainz を検索する */
    fun searchByName(album: String, artist: String) {
        if (searchBusy) return
        searchBusy = true
        searchMessage = T("検索しています…", "Searching…")
        val count = audioTracks.size
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { MusicBrainz.search(album, artist, count) }
                searchResults = r.candidates
                searchMessage = when {
                    r.error != null -> T("検索できませんでした: ${r.error}", "Search failed: ${r.error}")
                    r.candidates.isEmpty() -> T("見つかりませんでした。表記(漢字・かな・英字)を変えて試してください", "Nothing found. Try a different spelling")
                    else -> T("${r.candidates.size}件見つかりました(曲数が一致するものが上に表示されます)", "${r.candidates.size} found (matching track counts are listed first)")
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
                        snack = T("曲目を取得できませんでした", "Could not load the track list")
                    } else {
                        if (m.tracks.size != audioTracks.size) {
                            snack = T(
                                "曲数が一致しません(CD ${audioTracks.size}曲 / 候補 ${m.tracks.size}曲)。内容を確認してください",
                                "Track count differs (CD ${audioTracks.size} / match ${m.tracks.size}). Please check",
                            )
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
        metaStatus = T("元に戻しました", "Reverted")
    }

    private fun useMeta(m: AlbumMeta, source: String) {
        val hadInfo = meta?.title?.let { it != UNKNOWN_ALBUM } == true
        undoState = Triple(meta, coverBytes, cdCover)
        if (hadInfo) actionSnack = ActionSnack.UndoMeta(T("曲情報を「${m.title}」に変更しました", "Track info changed to \"${m.title}\""))
        showChooser = false
        meta = fitMeta(m)
        metaStatus = "✓ " + T("曲情報: ${sourceName(source)}", "Track info: ${sourceName(source)}")
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
                log(T("ジャケット画像: ${r.message}", "Cover art: ${r.message}"))
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
                    val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException(T("画像を開けません", "Cannot open the image"))
                    prepareCoverImage(raw)
                }
                coverBytes = b
                cdCover = withContext(Dispatchers.IO) { ArtLoader.decode(b, 600)?.asImageBitmap() }
                currentDiscId?.let { id -> withContext(Dispatchers.IO) { discCache.saveCover(id, b) } }
            } catch (e: Exception) {
                snack = T("画像を読み込めませんでした", "Could not load the image")
            }
        }
    }

    fun dismissCandidates() = closeChooser()

    fun updateMeta(m: AlbumMeta) {
        meta = fitMeta(m)
        metaStatus = T("曲情報を編集しました", "Track info edited")
        val id = currentDiscId
        val keep = meta
        if (id != null && keep != null) viewModelScope.launch(Dispatchers.IO) { discCache.save(id, keep) }
    }

    // ================= 取り込み =================

    fun startRip(treeOverride: String? = null) {
        val d = drive ?: return
        if (busy || discBusy) return
        val s = settings.value
        val tree = treeOverride ?: s.outputUri
        if (tree == null) { snack = T("設定で保存先フォルダを選んでください", "Choose a save folder in Settings"); return }
        val m = meta ?: fitMeta(AlbumMeta(UNKNOWN_ALBUM, UNKNOWN_ARTIST))
        val targets = audioTracks.filter { selected[it.number] == true }
        if (targets.isEmpty()) { snack = T("取り込む曲を選んでください", "Select tracks to rip"); return }
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
            targets.forEach { trackStatus[it.number] = T("待機中", "Waiting"); progress[it.number] = 0f }
            updateService()
            var errors = 0
            try {
                withContext(Dispatchers.IO) {
                    val dir = albumDir(tree, s, m)
                    if (s.saveCover && cover != null) saveCoverFile(dir, cover)
                    targets.forEachIndexed { i, t ->
                        ensureActive()
                        ripIndex = i
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
                    if (errors == 0) T("「${m.title}」の取り込みが完了しました", "Finished ripping \"${m.title}\"")
                    else T("取り込み完了(読み取りエラー ${errors}セクター)", "Ripping finished (${errors} sectors had read errors)"),
                    m.title, m.artist,
                )
                log(T("取り込み完了: ${m.title}(${targets.size}曲)", "Ripped: ${m.title} (${targets.size} tracks)"))
                if (s.ejectAfter) withContext(Dispatchers.IO) { runCatching { d.eject() } }
            } catch (e: CancellationException) {
                snack = T("取り込みを中止しました", "Ripping cancelled")
                throw e
            } catch (e: Exception) {
                snack = T("取り込みに失敗しました: ${e.message}", "Ripping failed: ${e.message}")
                log(T("取り込みエラー: ${e.message}", "Rip error: ${e.message}"))
            } finally {
                busy = false
                updateService()
                refreshLibrary()
            }
        }
    }

    fun cancelRip() { job?.cancel() }

    private fun safe(s: String): String {
        val t = s.replace(unsafeChars, "_").trim()
        // 80 文字で切る。絵文字など(サロゲートペア)の途中で切ると不正な文字になり、ファイルを作れないので避ける
        val cut = if (t.length > 80 && Character.isHighSurrogate(t[79])) t.take(79) else t.take(80)
        return cut.trimEnd('.', ' ').ifEmpty { "_" }
    }

    private val unsafeChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    private fun subDir(p: DocumentFile, name: String): DocumentFile =
        p.findFile(name)?.takeIf { it.isDirectory } ?: p.createDirectory(name) ?: throw IOException(T("フォルダを作成できません: $name", "Cannot create folder: $name"))

    private fun albumDir(tree: String, s: AppSettings, m: AlbumMeta): DocumentFile {
        var dir = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: throw IOException(T("保存先フォルダを開けません", "Cannot open the save folder"))
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
        ctx.contentResolver.openOutputStream(f.uri, "w") ?: throw IOException(T("ファイルに書き込めません", "Cannot write to the file"))

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
        val title = m.tracks.getOrNull(ai)?.title?.takeIf { it.isNotBlank() } ?: T("トラック %02d", "Track %02d").format(t.number)
        val artist = m.tracks.getOrNull(ai)?.artist?.takeIf { it.isNotBlank() } ?: m.artist
        val total = audioTracks.size
        trackStatus[t.number] = T("取り込み中", "Ripping")
        progress[t.number] = 0f
        val n = "%02d".format(t.number)
        val dp = if (m.discTotal > 1) "${m.discNo}-" else ""
        val base = when (s.fileNameMode) {
            0 -> "$dp$n ${safe(title)}"
            1 -> "$dp$n - ${safe(artist)} - ${safe(title)}"
            else -> "${dp}Track$n"
        }
        val name = "$base.${s.format.ext}"
        dir.findFile(name)?.delete()
        val file = dir.createFile(s.format.mime, name) ?: throw IOException(T("ファイルを作成できません", "Cannot create the file"))
        val bytes = (t.endLba - t.startLba).toLong() * 2352
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
        }
        var os: OutputStream? = null
        var sink: AudioSink? = null
        val tmp = File(ctx.cacheDir, "rip_tmp.m4a")
        try {
            val sk: AudioSink = when (s.format) {
                AudioFormat.WAV -> {
                    val o = BufferedOutputStream(openOut(file), 1 shl 16); os = o; WavSink(o, bytes)
                }
                AudioFormat.FLAC -> {
                    val o = BufferedOutputStream(openOut(file), 1 shl 16); os = o; FlacSink(o, bytes / 4, s.flacLevel, tags, cover)
                }
                AudioFormat.AAC -> {
                    tmp.delete(); AacSink(tmp.absolutePath, s.aacKbps * 1000)
                }
                AudioFormat.MP3 -> {
                    val o = BufferedOutputStream(openOut(file), 1 shl 16); os = o
                    val ms = Mp3Sink(
                        o, s.mp3Kbps, s.mp3Quality,
                        Id3Tags(title, artist, m.title, m.artist, m.date.take(4), t.number, total, m.discNo, m.discTotal),
                        cover,
                    )
                    if (!ms.bitrateApplied) log(T("MP3: ビットレート指定に未対応のため既定値で保存しました", "MP3: bitrate setting not supported, saved with the default"))
                    ms
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
            if (s.format == AudioFormat.AAC) {
                Mp4Tagger.tag(
                    tmp,
                    Mp4Tags(title, artist, m.title, m.artist, m.date.take(4), t.number, total, m.discNo, m.discTotal),
                    cover,
                )
                openOut(file).use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
                tmp.delete()
            }
            progress[t.number] = 1f
            trackStatus[t.number] = if (res.errorSectors == 0) T("完了", "Done") else T("完了(エラー${res.errorSectors})", "Done (${res.errorSectors} errors)")
            if (res.c2Disabled) log(T("このドライブはC2エラー検出に非対応のため無効にしました", "C2 error detection is not supported by this drive and was turned off"))
            return res.errorSectors
        } catch (e: Exception) {
            runCatching { sink?.abort() }
            runCatching { os?.close() }
            os = null
            runCatching { file.delete() }
            tmp.delete()
            trackStatus[t.number] = if (e is CancellationException) T("中止", "Cancel") else T("失敗", "Failed")
            throw e
        } finally {
            runCatching { os?.close() }
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
        snack = T("CDドライブの設定を初期値に戻しました", "CD drive settings were reset")
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
        if (trees.isEmpty()) { albums = emptyList(); return }
        if (libScanning) return
        viewModelScope.launch {
            libScanning = true
            libFull = fresh
            libNew = 0
            try {
                val old = if (fresh) emptyList() else albums.flatMap { it.tracks }
                val light = player.isPlaying
                val list = withContext(Dispatchers.IO) {
                    val all = ArrayList<com.uyatame.ucrt.library.LibTrack>()
                    for (t in trees) {
                        runCatching {
                            all.addAll(library.scan(Uri.parse(t), old, light) { i, n -> libProgress = "$i / $n"; libNew = n })
                        }.onFailure { log(T("フォルダを読めません: ${folderLabel(t)}", "Cannot read folder: ${folderLabel(t)}")) }
                    }
                    // 重なったフォルダから同じファイルが 2 回見つかっても、1 曲として扱う(先に読んだ保存先のものを残す)
                    all.distinctBy { Library.docKey(it.uri) }.also { library.saveCache(it) }
                }
                ArtLoader.clearMisses()
                albums = Library.group(list)
            } catch (e: Exception) {
                log(T("ライブラリの読み込み失敗: ${e.message}", "Library scan failed: ${e.message}"))
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
        libMessage = T("検索しています…", "Searching…")
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
                    libCandidates.isNotEmpty() -> T("${libCandidates.size}件の候補(曲数が合うものが上)", "${libCandidates.size} matches (matching track counts first)")
                    results.isNotEmpty() && results.all { it.error != null } -> T("通信エラーのため検索できませんでした", "Lookup failed due to a network error")
                    else -> T("見つかりませんでした。名前で検索してください", "Nothing found. Try searching by name")
                }
            } finally {
                libSearching = false
            }
        }
    }

    fun libSearch(a: LibAlbum, album: String, artist: String) {
        if (libSearching) return
        libSearching = true
        libMessage = T("検索しています…", "Searching…")
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { MusicBrainz.search(album, artist, a.tracks.size) }
                libCandidates = r.candidates
                libMessage = when {
                    r.error != null -> T("検索できませんでした: ${r.error}", "Search failed: ${r.error}")
                    r.candidates.isEmpty() -> T("見つかりませんでした。表記を変えて試してください", "Nothing found. Try a different spelling")
                    else -> T("${r.candidates.size}件見つかりました(曲数が合うものが上)", "${r.candidates.size} found (matching track counts first)")
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
                if (m.tracks.isEmpty()) { snack = T("曲目を取得できませんでした", "Could not load the track list"); return@launch }
                val cover = m.releaseId?.let { id ->
                    withContext(Dispatchers.IO) { MusicBrainz.cover(id).data?.let { runCatching { prepareCoverImage(it) }.getOrNull() } }
                }
                if (m.tracks.size != a.tracks.size) {
                    log(T("曲数が一致しません(ファイル ${a.tracks.size}曲 / 候補 ${m.tracks.size}曲)。先頭から順に当てはめました", "Track counts differ (files ${a.tracks.size} / match ${m.tracks.size}); applied in order"))
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

    fun saveAlbumTags(
        a: LibAlbum,
        album: String,
        albumArtist: String,
        year: String,
        titles: List<String>,
        artists: List<String>,
        newCover: Uri?,
        coverData: ByteArray? = null,
    ) {
        if (tagSaving) return
        viewModelScope.launch {
            tagSaving = true
            try {
                val coverBytes = coverData ?: newCover?.let { u ->
                    withContext(Dispatchers.IO) {
                        val raw = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw IOException(T("画像を開けません", "Cannot open the image"))
                        prepareCoverImage(raw)
                    }
                }
                val trackTotal = maxOf(a.tracks.size, a.tracks.maxOf { it.track })
                val discTotal = a.tracks.maxOf { it.disc }.coerceAtLeast(1)
                var ok = 0
                var skipped = 0
                var failed = 0
                withContext(Dispatchers.IO) {
                    a.tracks.forEachIndexed { i, t ->
                        try {
                            val v = TagValues(
                                titles.getOrElse(i) { t.title }.trim(), artists.getOrElse(i) { t.artist }.trim(),
                                album.trim(), albumArtist.trim(), year.trim(), t.track, trackTotal, t.disc, discTotal,
                            )
                            if (TagEditor.edit(ctx, Uri.parse(t.uri), v, coverBytes)) ok++ else skipped++
                        } catch (e: Exception) {
                            failed++
                            log(T("タグ保存失敗(${t.title}): ${e.message}", "Failed to save tags (${t.title}): ${e.message}"))
                        }
                    }
                }
                ArtLoader.forget(a.key)
                snack = buildString {
                    append(T("${ok}曲の曲情報を保存しました", "Saved track info for ${ok} tracks"))
                    if (skipped > 0) append(T("(WAVなど${skipped}曲は対象外)", " (${skipped} WAV or other tracks skipped)"))
                    if (failed > 0) append(T("。${failed}曲は失敗しました", ". ${failed} tracks failed"))
                }
                refreshLibrary()
            } catch (e: Exception) {
                snack = T("保存できませんでした: ${e.message}", "Could not save: ${e.message}")
            } finally {
                tagSaving = false
            }
        }
    }

    // ================= 再生 =================

    /** ライブラリの曲を、再生用の項目にする */
    fun itemOf(t: com.uyatame.ucrt.library.LibTrack): PlayItem =
        PlayItem(
            t.title, t.artist, t.album, t.durationMs, Uri.parse(t.uri), null,
            albumKeyOf(t.uri) ?: t.uri, t.uri, t.coverUri, t.folderId,
        )

    fun playAlbum(a: LibAlbum, start: Int, shuffle: Boolean = false) = playTracks(a.tracks, start, shuffle)

    /** 曲の一覧を再生する(shuffle なら順番を混ぜる) */
    fun playTracks(list: List<com.uyatame.ucrt.library.LibTrack>, start: Int, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        val items = list.map { itemOf(it) }
        if (shuffle) {
            if (!player.shuffle) player.toggleShuffle()
            player.playFiles(items, (0 until items.size).random())
        } else {
            player.playFiles(items, start)
        }
    }

    /** ライブラリ全体をシャッフル再生 */
    fun shuffleAll() = playTracks(albums.flatMap { it.tracks }, 0, shuffle = true)

    fun playNext(list: List<com.uyatame.ucrt.library.LibTrack>) {
        if (list.isEmpty()) return
        val wasEmpty = player.current == null
        player.playNext(list.map { itemOf(it) })
        if (!wasEmpty) snack = T("次に再生します(${list.size}曲)", "Playing next (${list.size} songs)")
    }

    fun addToQueue(list: List<com.uyatame.ucrt.library.LibTrack>) {
        if (list.isEmpty()) return
        val wasEmpty = player.current == null
        player.addToQueue(list.map { itemOf(it) })
        if (!wasEmpty) snack = T("再生リストの最後に追加しました(${list.size}曲)", "Added to the end of the queue (${list.size} songs)")
    }

    // ---- お気に入り・プレイリスト ----

    fun toggleFav(uri: String) {
        val on = !store.isFav(uri)
        store.toggleFav(uri)
        snack = if (on) T("お気に入りに追加しました", "Added to favorites") else T("お気に入りから外しました", "Removed from favorites")
    }

    fun setFav(list: List<com.uyatame.ucrt.library.LibTrack>, on: Boolean) {
        store.setFav(list.map { it.uri }, on)
        snack = if (on) T("${list.size}曲をお気に入りに追加しました", "Added ${list.size} songs to favorites")
        else T("お気に入りから外しました", "Removed from favorites")
    }

    fun toggleFavAlbum(key: String) {
        val on = !store.isFavAlbum(key)
        store.toggleFavAlbum(key)
        snack = if (on) T("アルバムをお気に入りに追加しました", "Album added to favorites") else T("お気に入りから外しました", "Removed from favorites")
    }

    fun addToPlaylist(id: String, uris: List<String>) {
        if (uris.isEmpty()) return
        store.addToPlaylist(id, uris)
        val name = store.playlists.firstOrNull { it.id == id }?.name.orEmpty()
        snack = T("「$name」に${uris.size}曲を追加しました", "Added ${uris.size} songs to \"$name\"")
    }

    fun createPlaylist(name: String, uris: List<String>) {
        store.createPlaylist(name, uris)
        snack = T("プレイリスト「${name.trim()}」を作りました", "Created playlist \"${name.trim()}\"")
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
