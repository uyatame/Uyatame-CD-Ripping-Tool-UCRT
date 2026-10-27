package com.uyatame.ucrt.legacy

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.data.AudioFormat
import com.uyatame.ucrt.library.ArtSource
import com.uyatame.ucrt.library.LibTrack
import com.uyatame.ucrt.library.UNKNOWN_ALBUM
import com.uyatame.ucrt.library.UNKNOWN_ARTIST
import com.uyatame.ucrt.meta.AlbumMeta
import com.uyatame.ucrt.meta.Cover
import com.uyatame.ucrt.meta.DiscCache
import com.uyatame.ucrt.meta.MusicBrainz
import com.uyatame.ucrt.meta.ReleaseCandidate
import com.uyatame.ucrt.meta.TrackMeta
import com.uyatame.ucrt.player.Decoders
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
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** 取り込みなどを止めた */
class Canceled : RuntimeException()

/**
 * 外付けの CD ドライブと音楽 CD(本家の MainViewModel のディスクの部分)。
 * USB でつないだドライブに SCSI のコマンドを直接送って、目次を読み、曲情報を探し、取り込み・再生・コピーをする
 */
object Disc {
    private val app get() = LegacyApp.app
    private val p get() = app.prefs
    private val main = Handler(Looper.getMainLooper())
    private lateinit var usb: UsbManager
    private lateinit var cache: DiscCache
    private const val PERM = "com.uyatame.ucrt.legacy.USB_PERMISSION"

    // ---- 画面から見る状態 ----
    @Volatile var status = ""; private set
    @Volatile var connected = false; private set
    @Volatile var driveName = ""; private set
    @Volatile var discText = ""; private set
    @Volatile var discBusy = false; private set
    @Volatile var tracks: List<TocTrack> = emptyList(); private set
    @Volatile var leadOut = 0; private set
    @Volatile var meta: AlbumMeta? = null; private set
    @Volatile var metaStatus = ""; private set
    @Volatile var candidates: List<ReleaseCandidate> = emptyList(); private set
    @Volatile var showChooser = false
    @Volatile var metaSearching = false; private set
    @Volatile var metaError = ""; private set
    @Volatile var searchResults: List<ReleaseCandidate> = emptyList(); private set
    @Volatile var searchBusy = false; private set
    @Volatile var searchMessage = ""; private set
    @Volatile var applying = false; private set
    @Volatile var cover: Bitmap? = null; private set
    @Volatile private var coverBytes: ByteArray? = null
    /** 新しい音楽 CD を読み込んだら増える(ディスクのタブを自動で開く) */
    @Volatile var discEvent = 0; private set
    @Volatile private var currentDiscId: String? = null
    private var undoState: Triple<AlbumMeta?, ByteArray?, Bitmap?>? = null

    // ---- 取り込み・コピーなど ----
    @Volatile var busy = false; private set
    /** 0 = 取り込み、1 = コピーのための読み取り、2 = 曲の変換、3 = 書き込み、4 = 消去 */
    @Volatile var busyKind = 0; private set
    @Volatile var ripIndex = 0; private set
    @Volatile var ripCount = 0; private set
    @Volatile var ripOverall = 0f; private set
    @Volatile var ripStartMs = 0L; private set
    @Volatile private var cancel = false
    val selected = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    val progress = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    val trackStatus = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private var drive: UsbScsiDrive? = null
    private var opening = false
    private var wake: PowerManager.WakeLock? = null

    val audioTracks: List<TocTrack> get() = tracks.filter { it.isAudio }
    val albumTitle: String get() = meta?.title ?: UNKNOWN_ALBUM
    val albumArtist: String get() = meta?.artist ?: UNKNOWN_ARTIST

    fun trackTitle(t: TocTrack): String = meta?.tracks?.getOrNull(audioTracks.indexOf(t))?.title?.takeIf { it.isNotBlank() } ?: S(R.string.track_2).format(t.number)
    fun trackArtist(t: TocTrack): String = meta?.tracks?.getOrNull(audioTracks.indexOf(t))?.artist?.takeIf { it.isNotBlank() } ?: albumArtist

    private fun changed() = Bus.emit(Bus.DISC)
    private fun log(m: String) = AppLog.log(m)
    private fun ui(f: () -> Unit) = main.post(f)
    private fun bg(name: String, f: () -> Unit) = Thread({ runCatching { f() } }, name).start()

    fun init(ctx: Context) {
        usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        cache = DiscCache(ctx)
        status = S(R.string.no_drive_connected_2)
        com.uyatame.ucrt.legacy.compat.Receivers.register(ctx, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val dev = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) openDevice(dev)
                else { status = S(R.string.usb_access_was_denied); changed() }
            }
        }, IntentFilter(PERM))
        com.uyatame.ucrt.legacy.compat.Receivers.register(ctx, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val dev = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val d = drive ?: return
                if (dev == null || dev.deviceName == d.deviceName) closeDrive(S(R.string.drive_was_disconnected))
            }
        }, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        // CD の曲の再生
        app.player.cdOpener = { item -> openCd(item) }
    }

    // ================= USB 接続 =================

    private var scanLogged = false

    /** つながっているドライブを探して開く(許可がなければ求める) */
    fun scan() {
        if (drive != null || opening) return
        val devices = usb.deviceList.values.toList()
        if (!scanLogged && devices.isNotEmpty()) {
            scanLogged = true
            devices.forEach { log(UsbScsiDrive.describe(it)) }
        }
        val dev = devices.firstOrNull { UsbScsiDrive.findInterface(it) != null }
        if (dev == null) {
            status = if (devices.any { UsbScsiDrive.isUasOnly(it) }) S(R.string.this_drive_only_supports_uas) else S(R.string.no_drive_found)
            changed()
            return
        }
        if (usb.hasPermission(dev)) openDevice(dev) else {
            val flags = if (Build.VERSION.SDK_INT >= 31) 0x02000000 else 0
            val pi = PendingIntent.getBroadcast(app, 0, Intent(PERM).setPackage(app.packageName), flags)
            status = S(R.string.waiting_for_usb_permission)
            changed()
            usb.requestPermission(dev, pi)
        }
    }

    private fun openDevice(dev: UsbDevice) {
        if (opening || drive?.deviceName == dev.deviceName) return
        opening = true
        bg("ucrt-drive-open") {
            try {
                val d = UsbScsiDrive.open(usb, dev)
                d.logger = { m -> log(m) }
                d.verbose = p.verboseLog
                drive?.let { old -> Thread({ old.close() }, "ucrt-drive-close").start() }
                drive = d
                connected = true
                driveName = d.name
                status = S(R.string.connected)
                discText = S(R.string.checking_disc)
                changed()
                var inq = ""
                for (a in 0 until 3) {
                    try { inq = d.inquiry(); break } catch (e: Exception) { inq = ""; Thread.sleep(500) }
                }
                if (inq.isNotEmpty()) { driveName = inq.substringBefore(" (type"); log(S(R.string.drive, inq)) }
                lastMedia = 0
                startMonitor(d)
            } catch (e: Exception) {
                status = S(R.string.could_not_connect, e.message)
            } finally {
                opening = false
                changed()
            }
        }
    }

    private fun clearDisc(text: String) {
        discOk = false
        if (playingCd()) ui { app.player.stop() }
        tracks = emptyList()
        meta = null
        cover = null
        coverBytes = null
        candidates = emptyList()
        searchResults = emptyList()
        searchMessage = ""
        showChooser = false
        metaGen++
        metaSearching = false
        metaStatus = ""
        discText = text
        changed()
    }

    private fun closeDrive(msg: String) {
        cancel = true
        monitorGen++
        if (playingCd()) app.player.stop()
        drive?.let { d -> Thread({ d.close() }, "ucrt-drive-close").start() }
        drive = null
        connected = false
        status = msg
        driveName = ""
        clearDisc("")
        busy = false
        discBusy = false
        changed()
    }

    fun playingCd(): Boolean = app.player.current?.uri?.startsWith("cd:") == true

    // ================= ディスクの自動検知 =================

    @Volatile private var monitorGen = 0
    @Volatile private var lastMedia = 0
    @Volatile private var discOk = false
    private const val READY = 1
    private const val NONE = 2
    private const val LOADING = 3
    private const val CHANGED = 4
    private const val GONE = 5

    private fun mediaState(d: UsbScsiDrive): Int = try {
        d.testUnitReady()
        READY
    } catch (e: ScsiException) {
        when {
            e.senseKey == -1 && usb.deviceList.values.none { it.deviceName == d.deviceName } -> GONE
            e.senseKey == 6 -> CHANGED
            e.asc == 0x3A -> NONE
            e.asc == 0x04 -> LOADING
            else -> 0
        }
    }

    private fun startMonitor(d: UsbScsiDrive) {
        val gen = ++monitorGen
        Thread({
            while (monitorGen == gen && drive === d) {
                if (!busy && !discBusy && !playingCd() && Burn.session == null) {
                    val st = mediaState(d)
                    if (st == GONE && drive === d) { ui { closeDrive(S(R.string.drive_was_disconnected)) }; break }
                    if (!busy && !discBusy && drive === d) {
                        when (st) {
                            READY, CHANGED -> if (lastMedia != READY || st == CHANGED) { lastMedia = READY; refreshDisc() }
                            NONE -> if (lastMedia != NONE) { lastMedia = NONE; clearDisc(S(R.string.insert_disc)) }
                            LOADING -> if (lastMedia != LOADING) { lastMedia = LOADING; discText = S(R.string.loading_disc); changed() }
                        }
                    }
                }
                try { Thread.sleep(if (lastMedia == READY && discOk) 1500L else 500L) } catch (e: InterruptedException) { break }
            }
        }, "ucrt-disc-monitor").start()
    }

    private class Scan(val text: String, val tracks: List<TocTrack>, val leadOut: Int, val retry: Boolean = false)

    private fun why(e: ScsiException): String = when {
        e.senseKey == -1 -> e.message ?: S(R.string.communication_error)
        e.asc == 0x3A -> S(R.string.no_disc)
        e.asc == 0x04 -> S(R.string.loading)
        e.senseKey == 6 -> S(R.string.disc_changed)
        else -> "key=%X asc=%02X/%02X".format(e.senseKey, e.asc, e.ascq)
    }

    private fun readDisc(d: UsbScsiDrive): Scan {
        d.verbose = p.verboseLog
        var ready = false
        var noMedia = 0
        var lastErr = ""
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 30_000) {
            try {
                d.testUnitReady(); ready = true; break
            } catch (e: ScsiException) {
                lastErr = why(e)
                if (e.senseKey == -1) break
                if (e.asc == 0x3A) { if (++noMedia >= 8) break } else noMedia = 0
                Thread.sleep(250)
            }
        }
        if (ready) log(S(R.string.disc_ready_s).format((System.currentTimeMillis() - t0) / 1000.0))
        if (!ready) {
            if (noMedia >= 3) return Scan(S(R.string.insert_disc), emptyList(), 0)
            log(S(R.string.drive_not_ready, lastErr))
        }
        var toc: Pair<List<TocTrack>, Int>? = null
        var tocErr = ""
        val t1 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t1 < 12_000) {
            try { toc = d.readToc(); break } catch (e: ScsiException) {
                tocErr = why(e)
                if (e.senseKey == -1) break
                Thread.sleep(300)
            }
        }
        if (toc != null && toc.first.any { it.isAudio }) return Scan("", toc.first, toc.second)
        val pf = try { d.currentProfile() } catch (e: ScsiException) { -1 }
        val pname = if (pf < 0) S(R.string.unknown_type) else UsbScsiDrive.profileName(pf)
        if (pf in 0x09..0x0A && runCatching { d.readDiscInfo().status == 0 }.getOrDefault(false)) return Scan(S(R.string.blank_use_it_to_copy, pname), emptyList(), 0)
        if (toc != null && pf in 0x08..0x0A) return Scan(S(R.string.this_is_data_cd_only), emptyList(), 0)
        var capErr = ""
        val cap = try { d.readCapacity() } catch (e: ScsiException) { capErr = why(e); null }
        if (cap != null) return Scan(S(R.string.this_is_disc_gb_only, pname, "%.1f".format(cap.first * cap.second / 1e9)), emptyList(), 0)
        log(S(R.string.cannot_recognize_disc_toc_capacity, tocErr, capErr))
        return Scan(if (ready) S(R.string.cannot_read_disc) else S(R.string.drive_isnt_ready), emptyList(), 0, retry = true)
    }

    /** ディスクを読み直す */
    fun refreshDisc() {
        val d = drive ?: return
        if (busy || discBusy) return
        discBusy = true
        changed()
        bg("ucrt-disc-read") {
            try {
                val r = readDisc(d)
                val changedTracks = r.tracks.map { it.startLba } != tracks.map { it.startLba }
                discText = r.text
                discOk = !r.retry && r.text != S(R.string.insert_disc)
                if (r.retry || !discOk) lastMedia = 0
                if (changedTracks) {
                    if (playingCd()) ui { app.player.stop() }
                    tracks = r.tracks
                    leadOut = r.leadOut
                    selected.clear(); progress.clear(); trackStatus.clear()
                    audioTracks.forEach { selected[it.number] = true }
                    candidates = emptyList(); searchResults = emptyList(); searchMessage = ""; showChooser = false
                    cover = null; coverBytes = null; metaStatus = ""
                    meta = if (audioTracks.isNotEmpty()) fitMeta(AlbumMeta(UNKNOWN_ALBUM, UNKNOWN_ARTIST)) else null
                    currentDiscId = MusicBrainz.toc(tracks, leadOut)?.discId
                    if (audioTracks.isNotEmpty()) {
                        discEvent++
                        val id = currentDiscId
                        val cached = id?.let { cache.load(it) }
                        if (cached != null && id != null) {
                            meta = fitMeta(cached)
                            metaStatus = "✓ " + S(R.string.track_info_from_last_time)
                            cache.loadCover(id)?.let { img -> coverBytes = img; cover = ArtSource.decode(img, 600, false) }
                            log(S(R.string.used_saved_track_info))
                        } else if (p.autoMeta) ui { fetchMeta() } else metaStatus = S(R.string.you_can_edit_track_info)
                    }
                }
            } catch (e: Exception) {
                discText = S(R.string.could_not_read_disc, e.message)
            } finally {
                discBusy = false
                changed()
            }
        }
    }

    fun eject() {
        val d = drive ?: return
        if (busy) return
        if (playingCd()) app.player.stop()
        bg("ucrt-eject") {
            try {
                d.eject()
                clearDisc(S(R.string.insert_disc))
                lastMedia = NONE
            } catch (e: Exception) {
                ui { LegacyActivity.current?.snack(S(R.string.could_not_eject)) }
                log(S(R.string.eject_failed, e.message))
            }
        }
    }

    // ================= 曲情報 =================

    private fun fitMeta(m: AlbumMeta): AlbumMeta {
        val n = audioTracks.size
        return m.copy(tracks = (0 until n).map { i -> m.tracks.getOrNull(i) ?: TrackMeta(S(R.string.track_2).format(audioTracks[i].number), m.artist) })
    }

    private fun sourceName(src: String) = if (src == MusicBrainz.SOURCE) "MusicBrainz" else src

    @Volatile private var metaGen = 0

    /** ディスクIDで MusicBrainz を検索する。ディスクIDが完全に一致する候補が 1 件だけならそれを使う */
    fun fetchMeta(openChooserAlways: Boolean = false) {
        val toc = MusicBrainz.toc(tracks, leadOut) ?: return
        MusicBrainz.contact = ""
        MusicBrainz.version = SettingsPages.version(app)
        metaStatus = S(R.string.searching_for_track_info)
        metaSearching = true
        metaError = ""
        candidates = emptyList()
        log("MusicBrainz disc ID: ${toc.discId} / TOC: ${toc.tocParam}")
        val gen = ++metaGen
        changed()
        bg("ucrt-meta") {
            try {
                val r = MusicBrainz.lookup(toc)
                if (gen != metaGen) return@bg
                if (r.error != null) log(S(R.string.failed_2, sourceName(r.source), r.error)) else log(S(R.string.results, sourceName(r.source), r.candidates.size))
                val all = r.candidates.sortedByDescending { it.exact }
                candidates = all
                val exact = all.filter { it.exact }
                when {
                    all.isEmpty() && r.error != null -> { metaStatus = S(R.string.could_not_get_track_info); metaError = "${sourceName(r.source)}: ${r.error}"; showChooser = true }
                    all.isEmpty() -> { metaStatus = S(R.string.no_matches_found_you_can_2); showChooser = true }
                    !openChooserAlways && all.size == 1 && exact.size == 1 -> ui { applyCandidate(all[0]) }
                    else -> { metaStatus = S(R.string.choose_from_matches, all.size); showChooser = true }
                }
            } catch (e: Exception) {
                metaStatus = S(R.string.could_not_get_track_info)
                metaError = com.uyatame.ucrt.meta.describeNetError(e)
                log(S(R.string.track_info_lookup_failed, e.message))
            } finally {
                if (gen == metaGen) metaSearching = false
                changed()
            }
        }
    }

    fun openChooser() {
        showChooser = true
        changed()
        if (candidates.isEmpty() && !metaSearching) fetchMeta(openChooserAlways = true)
    }

    fun closeChooser() {
        showChooser = false
        if (meta?.releaseId == null && !metaStatus.startsWith("✓") && !metaSearching) metaStatus = S(R.string.you_can_edit_track_info)
        changed()
    }

    /** アルバム名・アーティスト名で MusicBrainz を検索する */
    fun searchByName(album: String, artist: String) {
        if (searchBusy) return
        searchBusy = true
        searchMessage = S(R.string.searching)
        changed()
        val count = audioTracks.size
        bg("ucrt-search") {
            try {
                val r = MusicBrainz.search(album, artist, count)
                searchResults = r.candidates
                searchMessage = when {
                    r.error != null -> S(R.string.search_failed, r.error)
                    r.candidates.isEmpty() -> S(R.string.nothing_found_try_different_spelling)
                    else -> S(R.string.found_matching_track_counts_first, r.candidates.size)
                }
            } finally {
                searchBusy = false
                changed()
            }
        }
    }

    fun applyCandidate(c: ReleaseCandidate) {
        val loader = c.loader
        if (loader == null) { useMeta(c.meta, c.source); return }
        if (applying) return
        applying = true
        changed()
        bg("ucrt-apply") {
            try {
                val m = runCatching { loader() }.getOrNull()
                ui {
                    if (m == null) LegacyActivity.current?.snack(S(R.string.could_not_load_track_list)) else {
                        if (m.tracks.size != audioTracks.size) LegacyActivity.current?.snack(S(R.string.track_counts_dont_match_cd, audioTracks.size, m.tracks.size))
                        useMeta(m, c.source)
                    }
                }
            } finally {
                applying = false
                changed()
            }
        }
    }

    fun undoMeta() {
        val u = undoState ?: return
        undoState = null
        meta = u.first; coverBytes = u.second; cover = u.third
        metaStatus = S(R.string.reverted)
        changed()
    }

    private fun useMeta(m: AlbumMeta, source: String) {
        val hadInfo = meta?.title?.let { it != UNKNOWN_ALBUM } == true
        undoState = Triple(meta, coverBytes, cover)
        if (hadInfo) LegacyActivity.current?.snackAction(S(R.string.track_info_changed_to, m.title), S(R.string.undo)) { undoMeta() }
        showChooser = false
        meta = fitMeta(m)
        metaStatus = "✓ " + S(R.string.track_info, sourceName(source))
        cover = null
        coverBytes = null
        changed()
        val disc = currentDiscId
        val keep = meta
        bg("ucrt-meta-save") {
            if (disc != null && keep != null) { cache.save(disc, keep); cache.saveCover(disc, null) }
            val id = m.releaseId ?: return@bg
            val r = MusicBrainz.cover(id)
            val b = r.data
            if (b == null) { log(S(R.string.cover_art, r.message)); return@bg }
            if (meta?.releaseId != id) return@bg
            coverBytes = b
            if (disc != null && disc == currentDiscId) cache.saveCover(disc, b)
            cover = ArtSource.decode(b, 600, false)
            changed()
        }
    }

    /** CD のジャケット画像を、端末の画像から選んだもので置き換える */
    fun setCover(raw: ByteArray) {
        bg("ucrt-cover") {
            try {
                val b = com.uyatame.ucrt.tags.prepareCoverImage(raw)
                coverBytes = b
                cover = ArtSource.decode(b, 600, false)
                currentDiscId?.let { cache.saveCover(it, b) }
                changed()
            } catch (e: Exception) {
                ui { LegacyActivity.current?.snack(S(R.string.could_not_load_image)) }
            }
        }
    }

    fun updateMeta(m: AlbumMeta) {
        meta = fitMeta(m)
        metaStatus = S(R.string.track_info_updated)
        val id = currentDiscId
        val keep = meta
        if (id != null && keep != null) bg("ucrt-meta-save") { cache.save(id, keep) }
        changed()
    }

    fun toggle(n: Int) { selected[n] = selected[n] != true; changed() }
    fun selectAll(on: Boolean) { audioTracks.forEach { selected[it.number] = on }; changed() }

    // ================= CD の再生 =================

    /** CD を再生する(t の曲から。null なら最初から) */
    fun playCd(t: TocTrack? = null) {
        if (drive == null || busy) return
        val audio = audioTracks
        if (audio.isEmpty()) return
        val items = audio.map { tr ->
            LibTrack("cd:${tr.number}", trackTitle(tr), trackArtist(tr), albumTitle, albumArtist, tr.number, 1, (tr.endLba - tr.startLba) * 1000L / 75, "", null, 0L)
        }
        val start = if (t == null) 0 else audio.indexOf(t).coerceAtLeast(0)
        app.player.play(items, start)
    }

    private fun openCd(item: QItem): Decoders.Opened? {
        val d = drive ?: return null
        val n = item.uri.removePrefix("cd:").toIntOrNull() ?: return null
        val t = tracks.firstOrNull { it.number == n } ?: return null
        val end = if (leadOut > 0) minOf(t.endLba, leadOut) else t.endLba
        val dec = CdDecoder(d, t.startLba, end, p.cdPlaySpeed)
        dec.open()
        return Decoders.Opened(dec, java.io.Closeable { dec.stop = true })
    }

    // ================= 取り込み =================

    /** 取り込みの確認に出す内容 */
    class RipPlan(val dir: String, val count: Int, val dest: String, val sizeBytes: Long, val existing: List<String>)

    @Volatile var ripPlan: RipPlan? = null

    private fun safe(s: String): String {
        val t = s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
        val cut = if (t.length > 80 && Character.isHighSurrogate(t[79])) t.take(79) else t.take(80)
        return cut.trimEnd('.', ' ').ifEmpty { "_" }
    }

    private fun albumDir(root: String, m: AlbumMeta): File = when (p.folderMode) {
        0 -> File(File(root, safe(m.artist)), safe(m.title))
        1 -> File(root, safe(m.title))
        else -> File(root)
    }

    private fun fileName(t: TocTrack, m: AlbumMeta): String {
        val ai = audioTracks.indexOf(t)
        val title = m.tracks.getOrNull(ai)?.title?.takeIf { it.isNotBlank() } ?: S(R.string.track_2).format(t.number)
        val artist = m.tracks.getOrNull(ai)?.artist?.takeIf { it.isNotBlank() } ?: m.artist
        val n = "%02d".format(t.number)
        val dp = if (m.discTotal > 1) "${m.discNo}-" else ""
        val base = when (p.fileNameMode) {
            0 -> "$dp$n ${safe(title)}"
            1 -> "$dp$n - ${safe(artist)} - ${safe(title)}"
            else -> "${dp}Track$n"
        }
        return "$base.${p.format.ext}"
    }

    /** 取り込む前に、保存先・容量・同じ名前のファイルを調べて確認を出す */
    fun prepareRip(act: LegacyActivity, dirOverride: String? = null) {
        if (drive == null || busy || discBusy) return
        val root = dirOverride ?: p.outputDir ?: run { act.snack(S(R.string.choose_save_folder_in_settings)); return }
        val m = meta ?: fitMeta(AlbumMeta(UNKNOWN_ALBUM, UNKNOWN_ARTIST))
        val targets = audioTracks.filter { selected[it.number] == true }
        if (targets.isEmpty()) { act.snack(S(R.string.select_tracks_to_rip)); return }
        val dir = albumDir(root, m)
        val names = dir.list()?.toHashSet() ?: emptySet()
        val existing = targets.map { fileName(it, m) }.filter { it in names }
        val sec = targets.sumOf { (it.endLba - it.startLba).toLong() } / 75.0
        val size = when (p.format) {
            AudioFormat.WAV -> sec * 176_400
            AudioFormat.FLAC -> sec * 176_400 * 0.6
            AudioFormat.AAC -> sec * p.aacKbps * 125
            AudioFormat.MP3 -> sec * p.mp3Kbps * 125
        }.toLong()
        ripPlan = RipPlan(root, targets.size, dir.path, size, existing)
        changed()
    }

    fun confirmRip(skipExisting: Boolean) {
        val plan = ripPlan ?: return
        ripPlan = null
        startRip(plan.dir, skipExisting)
    }

    private fun lock(on: Boolean) {
        if (on) {
            if (wake == null) wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ucrt:rip")
            runCatching { wake?.acquire() }
        } else runCatching { if (wake?.isHeld == true) wake?.release() }
    }

    private fun begin(kind: Int, count: Int) {
        busy = true
        busyKind = kind
        ripCount = count
        ripIndex = 0
        ripOverall = 0f
        ripStartMs = System.currentTimeMillis()
        cancel = false
        lock(true)
        Bus.emit(Bus.RIP)
    }

    private fun end() {
        busy = false
        busyKind = 0
        lock(false)
        Bus.emit(Bus.RIP or Bus.DISC)
    }

    /** 止める確認ができたかどうか(取り込み・コピーの処理の中で呼ぶ) */
    fun check() { if (cancel) throw Canceled() }

    fun cancelRip() { cancel = true }

    private var lastEmit = 0L
    private fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastEmit > 250) { lastEmit = now; Bus.emit(Bus.RIP) }
    }

    private fun makeCover(b: ByteArray): Cover {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        val mime = if (o.outMimeType == "image/png") "image/png" else "image/jpeg"
        return Cover(b, mime, maxOf(o.outWidth, 0), maxOf(o.outHeight, 0))
    }

    fun startRip(root: String, skipExisting: Boolean = false) {
        val d = drive ?: return
        if (busy || discBusy) return
        val m = meta ?: fitMeta(AlbumMeta(UNKNOWN_ALBUM, UNKNOWN_ARTIST))
        val targets = audioTracks.filter { selected[it.number] == true }
        if (targets.isEmpty()) return
        if (playingCd()) app.player.stop()
        d.verbose = p.verboseLog
        val cov = coverBytes?.let { makeCover(it) }
        begin(0, targets.size)
        targets.forEach { trackStatus[it.number] = S(R.string.waiting); progress[it.number] = 0f }
        Thread({
            var errors = 0
            var skipped = 0
            var msg: String
            var ok = false
            try {
                val dir = albumDir(root, m)
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException(S(R.string.cannot_create_folder, dir.path))
                if (p.saveCover && cov != null) runCatching { File(dir, if (cov.mime == "image/png") "cover.png" else "cover.jpg").writeBytes(cov.data) }
                val have = if (skipExisting) dir.list()?.toHashSet() ?: emptySet() else emptySet()
                targets.forEachIndexed { i, t ->
                    check()
                    ripIndex = i
                    if (fileName(t, m) in have) {
                        skipped++
                        trackStatus[t.number] = S(R.string.skipped)
                        progress[t.number] = 1f
                        ripOverall = (i + 1f) / targets.size
                        return@forEachIndexed
                    }
                    tick()
                    errors += ripTrack(d, t, m, dir, if (p.embedCover) cov else null) { pr -> ripOverall = (i + pr) / targets.size; tick() }
                }
                ripOverall = 1f
                msg = if (errors == 0) S(R.string.finished_ripping, m.title) else S(R.string.ripping_finished_with_read_errors, errors)
                ok = true
                log(S(R.string.ripped_tracks, m.title, targets.size - skipped))
                if (p.ejectAfter) runCatching { d.eject() }
            } catch (e: Canceled) {
                msg = S(R.string.ripping_canceled)
            } catch (e: Exception) {
                msg = S(R.string.ripping_failed, e.message)
                log(S(R.string.rip_error, e.message))
            }
            end()
            val done = ok
            ui {
                val act = LegacyActivity.current
                if (done) act?.snackAction(msg, S(R.string.view_in_library)) { act.openAlbum(m.title, m.artist) } else act?.snack(msg)
                app.lib.refresh(false)
            }
        }, "ucrt-rip").start()
    }

    /** 1 曲を取り込む。戻り値は読み取りエラーのセクター数 */
    private fun ripTrack(d: UsbScsiDrive, t: TocTrack, m: AlbumMeta, dir: File, cov: Cover?, onP: (Float) -> Unit): Int {
        val ai = audioTracks.indexOf(t)
        val title = m.tracks.getOrNull(ai)?.title?.takeIf { it.isNotBlank() } ?: S(R.string.track_2).format(t.number)
        val artist = m.tracks.getOrNull(ai)?.artist?.takeIf { it.isNotBlank() } ?: m.artist
        val total = audioTracks.size
        trackStatus[t.number] = S(R.string.ripping)
        progress[t.number] = 0f
        val name = fileName(t, m)
        val target = File(dir, name)
        // 同じ名前のファイルがあるときは、いったん別の名前で取り込み、うまくいってから置き換える
        val file = if (target.exists()) File(dir, "$name.part") else target
        val bytes = (t.endLba - t.startLba).toLong() * 2352
        val tm = m.tracks.getOrNull(ai)
        val extra = com.uyatame.ucrt.tags.TagExtra(m.genre.ifBlank { null }, tm?.composer?.ifBlank { null }, tm?.lyricist?.ifBlank { null }, tm?.comment?.ifBlank { null }, tm?.lyrics?.ifBlank { null })
        val tags = ArrayList<Pair<String, String>>().apply {
            add("TITLE" to title); add("ARTIST" to artist); add("ALBUM" to m.title); add("ALBUMARTIST" to m.artist)
            add("TRACKNUMBER" to t.number.toString()); add("TRACKTOTAL" to total.toString())
            add("DISCNUMBER" to m.discNo.toString()); add("DISCTOTAL" to m.discTotal.toString())
            if (m.date.isNotEmpty()) add("DATE" to m.date)
            m.releaseId?.let { add("MUSICBRAINZ_ALBUMID" to it) }
            addAll(extra.vorbisPairs())
        }
        var os: OutputStream? = null
        var sink: AudioSink? = null
        val tmp = File(app.cacheDir, "rip_tmp.m4a")
        try {
            val sk: AudioSink = when (p.format) {
                AudioFormat.WAV -> {
                    val o = BufferedOutputStream(file.outputStream(), 1 shl 16); os = o
                    WavSink(o, bytes, com.uyatame.ucrt.tags.WavTags.metaChunks(Id3Tags(title, artist, m.title, m.artist, m.date.take(4), t.number, total, m.discNo, m.discTotal), cov))
                }
                AudioFormat.FLAC -> {
                    val o = BufferedOutputStream(file.outputStream(), 1 shl 16); os = o
                    FlacSink(o, bytes / 4, p.flacLevel, tags, cov)
                }
                AudioFormat.AAC -> {
                    // AAC の書き出し(MediaMuxer)は Android 4.3 から
                    if (Build.VERSION.SDK_INT < 18) throw IOException(S(R.string.legacy_aac_needs_43))
                    tmp.delete(); AacSink(tmp.absolutePath, p.aacKbps * 1000)
                }
                AudioFormat.MP3 -> {
                    val o = BufferedOutputStream(file.outputStream(), 1 shl 16); os = o
                    val id3 = Id3Tags(title, artist, m.title, m.artist, m.date.take(4), t.number, total, m.discNo, m.discTotal)
                    Mp3Sink(o, p.mp3Kbps, p.mp3Quality, id3, cov, extra.id3Frames()).also { ms -> if (!ms.bitrateApplied) log(S(R.string.mp3_bitrate_setting_not_supported)) }
                }
            }
            sink = sk
            var last = 0f
            val res = CdRipper(d, RipOptions(p.speedX, p.sectorsPerRead, p.retries, p.useC2, p.abortOnError, p.offsetSamples))
                .rip(t.startLba, t.endLba, leadOut, sk, { pr -> if (pr - last >= 0.01f) { last = pr; progress[t.number] = pr; onP(pr) } }) { check() }
            sk.finish()
            os?.close(); os = null
            if (p.format == AudioFormat.AAC) {
                Mp4Tagger.tag(tmp, Mp4Tags(title, artist, m.title, m.artist, m.date.take(4), t.number, total, m.discNo, m.discTotal), cov, extra)
                file.outputStream().use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
                tmp.delete()
            }
            if (file !== target) {
                if (!target.delete() || !file.renameTo(target)) throw IOException(S(R.string.cannot_replace_existing_file, name))
            }
            progress[t.number] = 1f
            trackStatus[t.number] = if (res.errorSectors == 0) S(R.string.done) else S(R.string.done_errors, res.errorSectors)
            if (res.c2Disabled) log(S(R.string.c2_error_detection_is_not))
            return res.errorSectors
        } catch (e: Exception) {
            runCatching { sink?.abort() }
            runCatching { os?.close() }
            os = null
            runCatching { file.delete() }
            tmp.delete()
            trackStatus[t.number] = if (e is Canceled) S(R.string.canceled) else S(R.string.failed)
            throw e
        } finally {
            runCatching { os?.close() }
        }
    }

    /** いま busy の作業の名前 */
    fun busyTitle(): String = when (busyKind) {
        1 -> S(R.string.reading_cd_to_copy)
        2 -> S(R.string.preparing_track_of, ripIndex + 1, ripCount)
        3 -> S(R.string.writing_to_cd)
        4 -> S(R.string.erasing_cd_rw)
        else -> S(R.string.ripping_track_of, ripIndex + 1, ripCount)
    }

    // ================= CD のコピー・書き込み(Burn から使う) =================

    val driveOrNull: UsbScsiDrive? get() = drive
    val discCacheId: String? get() = currentDiscId
    val coverData: ByteArray? get() = coverBytes

    internal fun beginBusy(kind: Int, count: Int) = begin(kind, count)
    internal fun endBusy() = end()
    internal fun setProgress(f: Float, index: Int = ripIndex) { ripOverall = f; ripIndex = index; tick() }
    internal fun afterEject() { clearDisc(S(R.string.insert_blank_cd_r)); lastMedia = NONE }
    internal fun resetMedia() { lastMedia = 0 }
}
