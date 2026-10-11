package com.uyatame.ucrt.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uyatame.ucrt.T
import com.uyatame.ucrt.usb.TocTrack
import com.uyatame.ucrt.usb.UsbScsiDrive
import com.uyatame.ucrt.player.usb.UsbDac
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.media.AudioFormat as AFormat

data class PlayItem(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri? = null,
    val cdTrack: TocTrack? = null,
    val artKey: String? = null,
    val artTrackUri: String? = null,
    val artCoverUri: String? = null,
    /** 曲が入っているフォルダ(歌詞ファイルを探すのに使う) */
    val folderId: String? = null,
    /** 再生リストの中で 1 曲ずつ区別するための番号(同じ曲を 2 回入れても別物として扱う) */
    val id: Long = PlayItem.nextId(),
) {
    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong()
        fun nextId(): Long = counter.incrementAndGet()
    }
}

/**
 * ファイル(OS内蔵デコーダで PCM に変換)と CD(READ CD で読んだ PCM)の両方を、
 * AudioOutput(イコライザー / ビットパーフェクト)を通して再生する。
 */
class PlayerController(private val ctx: Context, scope: CoroutineScope) {
    var queue by mutableStateOf<List<PlayItem>>(emptyList())
        private set
    var index by mutableIntStateOf(-1)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    /** 0 = リピートなし, 1 = 全曲リピート, 2 = 1曲リピート */
    var repeatMode by mutableIntStateOf(0)
        private set
    var shuffle by mutableStateOf(false)
        private set
    private var original: List<PlayItem> = emptyList()
    /** このアプリを開いてから一度でも再生したか(前回の続きを読み込んだだけでは通知を出さない) */
    var started by mutableStateOf(false)
        private set
    /** スリープタイマーの終了時刻(SystemClock.elapsedRealtime。0 ならオフ) */
    var sleepAtMs by mutableLongStateOf(0L)
        private set
    /** 今の曲が終わったら止める */
    var stopAfterTrack by mutableStateOf(false)
        private set

    /** ロック画面・通知・Bluetooth機器からの操作用 */
    val session = MediaSession(ctx, "UCRT").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = this@PlayerController.resume()
            override fun onPause() = this@PlayerController.pause()
            override fun onSkipToNext() = this@PlayerController.next()
            override fun onSkipToPrevious() = this@PlayerController.prev()
            override fun onSeekTo(pos: Long) = this@PlayerController.seek(pos)
            override fun onStop() = this@PlayerController.stop()
        })
    }
    private var art: Bitmap? = null

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // USB DAC を直接使うときは、Android から DAC が消えたように見えるため無視する
            if (UsbDac.active || android.os.SystemClock.elapsedRealtime() < UsbDac.ignoreNoisyUntil) return
            pause()
        }
    }
    private var noisyRegistered = false

    val current: PlayItem? get() = queue.getOrNull(index)
    val isCd: Boolean get() = current?.cdTrack != null
    /** 再生中の曲が DSD か */
    val isDsd: Boolean get() = current?.uri?.let { isDsdUri(it) } == true

    private fun isDsdUri(uri: Uri): Boolean {
        val s = Uri.decode(uri.toString())
        if (com.uyatame.ucrt.player.dsd.DsdFile.isDsd(s)) return true
        // 拡張子が分かるなら、ファイル名の問い合わせはしない
        if (s.substringAfterLast('/').contains('.')) return false
        val name = runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, uri)?.name }.getOrNull()
        return name != null && com.uyatame.ucrt.player.dsd.DsdFile.isDsd(name)
    }

    var onStateChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener(
            AudioManager.OnAudioFocusChangeListener { ch ->
                if (ch == AudioManager.AUDIOFOCUS_LOSS || ch == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            },
            main,
        )
        .build()

    private var file: PlayStream? = null
    private var cd: CdStream? = null
    private var drive: UsbScsiDrive? = null
    private var leadOut = 0
    private var cdSpeedX = 4

    private var volProvider: android.media.VolumeProvider? = null

    /** USB DAC 直接出力中は、音量キーで DAC の音量を変える */
    private fun setVolumeRouting(active: Boolean) {
        if (active) {
            val vp = object : android.media.VolumeProvider(android.media.VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, UsbDac.volume()) {
                override fun onSetVolumeTo(volume: Int) { setUsbVolume(volume, true) }
                override fun onAdjustVolume(direction: Int) { setUsbVolume(UsbDac.volume() + direction * 4, true) }
            }
            volProvider = vp
            runCatching { session.setPlaybackToRemote(vp) }
        } else {
            volProvider = null
            runCatching { session.setPlaybackToLocal(attrs) }
        }
    }

    /** DAC の音量(0〜100)を変える。fromKeys なら設定にも保存する */
    fun setUsbVolume(v: Int, fromKeys: Boolean = false) {
        // -1 は自動(DAC に合わせた初期値)
        val x = if (v < 0) -1 else v.coerceIn(0, 100)
        UsbDac.setVolume(x)
        volProvider?.currentVolume = UsbDac.volume()
        if (fromKeys) AudioEngine.onUsbVolume?.invoke(x)
    }

    init {
        UsbDac.init(ctx)
        UsbDac.onChanged = { main.post { if (AudioEngine.wantUsbDirect && current != null) reloadOutput() } }
        UsbDac.onDetached = {
            main.post {
                if (current != null) {
                    pause()
                    reloadOutput()
                }
            }
        }
        UsbDac.onActiveChanged = { a -> setVolumeRouting(a) }
        scope.launch {
            while (true) {
                delay(250)
                val c = cd
                val f = file
                if (c != null) {
                    applySwitches(c, c.seg, c.head())
                    if (cd === c) positionMs = c.positionMs()
                } else if (f != null) {
                    f.seg?.let { applySwitches(f, it, f.head()) }
                    if (file === f) positionMs = f.positionMs()
                }
                val sl = sleepAtMs
                if (sl > 0 && android.os.SystemClock.elapsedRealtime() >= sl) {
                    sleepAtMs = 0L
                    pause()
                }
            }
        }
    }

    fun playFiles(items: List<PlayItem>, start: Int) {
        if (items.isEmpty()) return
        halt()
        drive = null
        setQueue(items, start.coerceIn(0, items.lastIndex))
        startCurrent(0, true)
    }

    private fun setQueue(items: List<PlayItem>, start: Int) {
        original = items
        if (shuffle) {
            val first = items[start]
            queue = listOf(first) + (items - first).shuffled()
            index = 0
        } else {
            queue = items
            index = start
        }
    }

    /** 前回の再生状態を読み込む(再生はしない。再生ボタンで続きから始まる) */
    fun restore(items: List<PlayItem>, start: Int, posMs: Long, shuffleOn: Boolean, repeat: Int, originalOrder: List<PlayItem> = items) {
        if (items.isEmpty() || current != null) return
        halt()
        drive = null
        queue = items
        original = if (shuffleOn) originalOrder else items
        shuffle = shuffleOn
        repeatMode = repeat.coerceIn(0, 2)
        index = start.coerceIn(0, items.lastIndex)
        val d = items[index].durationMs
        durationMs = d
        positionMs = if (d > 0) posMs.coerceIn(0L, d) else posMs.coerceAtLeast(0L)
        isPlaying = false
        changed()
    }

    /** 今の曲の次に入れる */
    fun playNext(items: List<PlayItem>) {
        if (items.isEmpty()) return
        syncSwitches()
        val cur = current
        if (cur == null) { playFiles(items, 0); return }
        queue = queue.toMutableList().apply { addAll(index + 1, items) }
        original = if (shuffle) {
            val o = original.toMutableList()
            val ci = o.indexOfFirst { it.id == cur.id }.let { if (it < 0) o.lastIndex else it }
            o.addAll(ci + 1, items)
            o
        } else queue
        changed()
        rejoinIfStale()
    }

    /** 再生リストの最後に入れる */
    fun addToQueue(items: List<PlayItem>) {
        if (items.isEmpty()) return
        if (current == null) { playFiles(items, 0); return }
        syncSwitches()
        queue = queue + items
        original = if (shuffle) original + items else queue
        changed()
        rejoinIfStale()
    }

    /** 再生リストから 1 曲外す */
    fun removeAt(i: Int) {
        if (i !in queue.indices) return
        if (queue.size == 1) { stop(); return }
        syncSwitches()
        val gone = queue[i]
        val q = queue.toMutableList().apply { removeAt(i) }
        original = if (shuffle) original.filter { it.id != gone.id } else q
        when {
            i < index -> { queue = q; index-- }
            i > index -> queue = q
            else -> {
                // 再生中の曲を外したら、次の曲へ(最後の曲なら 1 つ前へ)
                val play = isPlaying
                queue = q
                index = index.coerceAtMost(q.lastIndex)
                startCurrent(0, play)
                return
            }
        }
        changed()
        rejoinIfStale()
    }

    /** 再生リストの並べ替え */
    fun move(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices || from == to) return
        syncSwitches()
        val cur = current
        val q = queue.toMutableList()
        q.add(to, q.removeAt(from))
        queue = q
        if (cur != null) index = q.indexOfFirst { it.id == cur.id }.coerceAtLeast(0)
        if (!shuffle) original = q
        changed()
        rejoinIfStale()
    }

    /** 再生リストの i 番目から再生する */
    fun jumpTo(i: Int) {
        if (i !in queue.indices) return
        index = i
        startCurrent(0, true)
    }

    /** 今の曲より後ろをすべて外す */
    fun clearUpcoming() {
        syncSwitches()
        if (index < 0 || index >= queue.lastIndex) return
        val keep = queue.subList(0, index + 1).toList()
        val dropped = queue.subList(index + 1, queue.size).map { it.id }.toHashSet()
        queue = keep
        original = if (shuffle) original.filter { it.id !in dropped } else keep
        changed()
        rejoinIfStale()
    }

    // ---------------- ギャップレス再生 ----------------

    /** ギャップレス再生(同じ形式の曲が続くときは、出力を閉じずにそのままつなぐ) */
    @Volatile var gapless = true

    // 再生スレッドから読むための写し(Compose の状態はメインスレッドで書き換える)
    @Volatile private var snapQueue: List<PlayItem> = emptyList()
    @Volatile private var snapRepeat = 0
    @Volatile private var snapStopAfter = false

    private fun snapshot() {
        snapQueue = queue
        snapRepeat = repeatMode
        snapStopAfter = stopAfterTrack
    }

    /** 曲 id の次にギャップレスでつなげる曲(つなげられなければ null)。再生スレッドから呼ぶ */
    private fun gaplessAfter(id: Long, cdMode: Boolean): PlayItem? {
        if (!gapless || snapStopAfter || snapRepeat == 2) return null
        val q = snapQueue
        val i = q.indexOfFirst { it.id == id }
        if (i < 0) return null
        val n = when {
            i < q.lastIndex -> q[i + 1]
            snapRepeat == 1 && q.size > 1 -> q[0]
            else -> null
        } ?: return null
        if (cdMode) return n.takeIf { it.cdTrack != null }
        val u = n.uri ?: return null
        if (n.cdTrack != null || isDsdUri(u)) return null
        return n
    }

    /** 出力が切り替わり位置まで進んだら、画面の曲を次の曲にする */
    private fun applySwitches(owner: Any, seg: Segments, head: Long) {
        while (true) {
            val sw = seg.pending.peek() ?: break
            if (head < sw.frame) break
            seg.pending.poll()
            seg.startFrame = sw.frame
            seg.baseMs = sw.baseMs
            if (owner !== file && owner !== cd) continue
            val i = queue.indexOfFirst { it.id == sw.item.id }
            if (i >= 0) index = i
            durationMs = if (sw.durationMs > 0) sw.durationMs else sw.item.durationMs
            positionMs = sw.baseMs
            changed()
        }
    }

    /** 曲の切り替わりを今すぐ画面に反映する(操作の前に、今どの曲が鳴っているかを正しくするため) */
    private fun syncSwitches() {
        val c = cd
        val f = file
        if (c != null) {
            applySwitches(c, c.seg, c.head())
            positionMs = c.positionMs()
        } else if (f != null) {
            val sg = f.seg ?: return
            applySwitches(f, sg, f.head())
            positionMs = f.positionMs()
        }
    }

    /**
     * 次の曲をもうつないでしまった後で、再生リストや設定が変わり、
     * つないだ曲が「本当の次の曲」でなくなったら、今の位置から出力を作り直す。
     */
    private fun rejoinIfStale() {
        val sg = cd?.seg ?: file?.seg ?: return
        val sw = sg.pending.peek() ?: return
        val nextId = queue.getOrNull(index + 1)?.id
            ?: if (repeatMode == 1 && queue.size > 1) queue[0].id else null
        if (sw.item.id != nextId || repeatMode == 2 || stopAfterTrack || !gapless) {
            startCurrent(positionMs, isPlaying)
        }
    }

    /** スリープタイマー(分。0 で解除) */
    fun setSleep(minutes: Int) {
        sleepAtMs = if (minutes > 0) android.os.SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
        stopAfterTrack = false
        snapshot()
    }

    fun stopAtTrackEnd(on: Boolean) {
        syncSwitches()
        stopAfterTrack = on
        snapshot()
        if (on) sleepAtMs = 0L
        rejoinIfStale()
    }

    /** 保存用: シャッフル前の並び(シャッフルしていなければ今の並び) */
    val originalOrder: List<PlayItem> get() = original

    fun cycleRepeat() {
        syncSwitches()
        repeatMode = (repeatMode + 1) % 3
        publish()
        rejoinIfStale()
    }

    fun toggleShuffle() {
        syncSwitches()
        val cur = current
        shuffle = !shuffle
        if (cur != null && original.isNotEmpty()) {
            if (shuffle) {
                queue = listOf(cur) + (original - cur).shuffled()
                index = 0
            } else {
                queue = original
                index = original.indexOf(cur).coerceAtLeast(0)
            }
        }
        publish()
        rejoinIfStale()
    }

    /** ジャケット画像(通知・ロック画面用) */
    fun setArt(b: Bitmap?) {
        art = b
        publish()
    }

    private fun publish() {
        snapshot()
        val cur = current
        if (cur == null) {
            runCatching { session.isActive = false }
            return
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    positionMs, if (isPlaying) 1f else 0f,
                )
                .build(),
        )
        val mb = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, cur.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, cur.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, cur.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        art?.let { mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(mb.build())
        runCatching { session.isActive = true }
    }

    private fun changed() {
        if (isPlaying && !noisyRegistered) {
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(
                    ctx, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
            noisyRegistered = true
        } else if (!isPlaying && noisyRegistered) {
            runCatching { ctx.unregisterReceiver(noisy) }
            noisyRegistered = false
        }
        publish()
        onStateChanged?.invoke()
    }

    fun playCd(d: UsbScsiDrive, lo: Int, items: List<PlayItem>, start: Int, speedX: Int) {
        if (items.isEmpty()) return
        halt()
        drive = d
        leadOut = lo
        cdSpeedX = speedX.coerceIn(1, 52)
        setQueue(items, start.coerceIn(0, items.lastIndex))
        startCurrent(0, true)
    }

    private fun startCurrent(fromMs: Long, play: Boolean) {
        val item = current ?: return
        halt()
        if (play) am.requestAudioFocus(focus)
        durationMs = item.durationMs
        positionMs = fromMs
        isPlaying = play
        if (play) started = true
        val t = item.cdTrack
        val d = drive
        if (t != null && d != null) {
            val c = CdStream(d, item, t, (fromMs * 75 / 1000).toInt(), !play)
            cd = c
            c.start()
        } else if (item.uri != null) {
            startFile(item, item.uri, fromMs)
        } else {
            isPlaying = false
        }
        changed()
    }

    private fun startFile(item: PlayItem, uri: Uri, fromMs: Long) {
        val f: PlayStream = if (isDsdUri(uri)) DsdStream(uri, fromMs, !isPlaying)
        else FileStream(item, fromMs, !isPlaying)
        file = f
        f.start()
    }

    /**
     * PCM の曲(ファイル・CD)の出力を開く。
     * DAR の DSD 変換がオンで、USB DAC へ DSD で送れるならその出力、そうでなければ通常の出力(PCM アップサンプリングを含む)。
     * 再生スレッドから呼ぶ
     */
    private fun openOutput(rate: Int, channels: Int, bits: Int): PcmOut {
        if (AudioEngine.wantUsbDirect && AudioEngine.darMode == 2 && channels in 1..2) {
            com.uyatame.ucrt.player.dar.DsdConvertOutput.open(ctx, attrs, rate, channels)?.let { return it }
            AudioEngine.log("dar: DSD conversion not available for ${AudioEngine.khz(rate)}, playing as PCM")
        }
        return AudioOutput(ctx, attrs, rate, channels, bits, dar = true)
    }

    private var failAt = 0L
    private var failCount = 0

    /**
     * 出力に書き込めなくなったとき(音声サーバーの再起動など)、今の位置から出力を作り直す。
     * 短い間に何度も失敗するときは、作り直しを繰り返さずに一時停止する
     */
    private fun recoverOutput() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - failAt > 10_000) failCount = 0
        failAt = now
        if (++failCount <= 3) reloadOutput() else pause()
    }

    /** 音響設定(ビットパーフェクトの切り替えなど)を反映するため、今の位置から再生し直す */
    fun reloadOutput() {
        if (current == null) {
            AudioEngine.releaseUnused(am, attrs)
            return
        }
        AudioEngine.releaseUnused(am, attrs)
        syncSwitches()
        startCurrent(positionMs, isPlaying)
    }

    fun toggle() { if (isPlaying) pause() else resume() }

    fun pause() {
        if (!isPlaying) return
        syncSwitches()
        file?.pauseAudio()
        cd?.pauseAudio()
        isPlaying = false
        changed()
    }

    fun resume() {
        if (current == null) return
        am.requestAudioFocus(focus)
        val f = file
        val c = cd
        when {
            f != null && f.isAlive -> f.resumeAudio()
            c != null && c.isAlive -> c.resumeAudio()
            else -> { startCurrent(positionMs, true); return }
        }
        isPlaying = true
        changed()
    }

    fun next(auto: Boolean = false) {
        if (auto && stopAfterTrack) {
            // スリープタイマー「この曲の終わりで停止」
            stopAfterTrack = false
            if (index < queue.lastIndex) {
                index++
                startCurrent(0, false)
            } else {
                halt()
                isPlaying = false
                positionMs = 0
                changed()
            }
            return
        }
        if (!auto) syncSwitches()
        if (auto && repeatMode == 2) {
            startCurrent(0, true)
        } else if (index < queue.lastIndex) {
            val play = isPlaying || auto
            index++
            startCurrent(0, play)
        } else if (repeatMode == 1 && queue.isNotEmpty()) {
            val play = isPlaying || auto
            index = 0
            startCurrent(0, play)
        } else if (auto) {
            halt()
            isPlaying = false
            positionMs = 0
            changed()
        }
    }

    fun prev() {
        syncSwitches()
        if (positionMs > 3000 || index <= 0) { seek(0); return }
        val play = isPlaying
        index--
        startCurrent(0, play)
    }

    fun seek(ms: Long) {
        // 曲の切り替わりの直後でも、今鳴っている曲の中で動かす
        syncSwitches()
        if (current != null) startCurrent(ms.coerceAtLeast(0), isPlaying)
    }

    fun stop() {
        halt()
        sleepAtMs = 0L
        stopAfterTrack = false
        queue = emptyList()
        original = emptyList()
        index = -1
        isPlaying = false
        positionMs = 0
        durationMs = 0
        runCatching { am.abandonAudioFocusRequest(focus) }
        AudioEngine.release(am, attrs)
        changed()
    }

    fun release() {
        stop()
        runCatching { session.release() }
    }

    private fun halt() {
        file?.halt()
        file = null
        cd?.halt()
        cd = null
    }

    /** 端末内のファイルを、OS内蔵のデコーダで PCM にして再生する */
    /** ファイル再生のスレッド(PCM と DSD で共通の操作) */
    /** ギャップレスで次の曲へ切り替わる位置(出力の何フレーム目から次の曲か) */
    private class Switch(val item: PlayItem, val frame: Long, val baseMs: Long, val durationMs: Long)

    /** 今再生している曲が、出力の何フレーム目から始まったか */
    private class Segments {
        @Volatile var startFrame = 0L
        @Volatile var baseMs = 0L
        val pending = java.util.concurrent.ConcurrentLinkedQueue<Switch>()
    }

    private abstract inner class PlayStream(name: String) : Thread(name) {
        /** ギャップレス対応の流れなら、曲の切り替わり位置 */
        open val seg: Segments? get() = null
        open fun head(): Long = 0L
        abstract fun positionMs(): Long
        abstract fun pauseAudio()
        abstract fun resumeAudio()
        abstract fun halt()
    }

    /** DSD(DSF / DFF)の再生。ネイティブ DSD → DoP → PCM 変換の順に使える方法で出力する */
    private inner class DsdStream(
        val uri: Uri,
        val fromMs: Long,
        startPaused: Boolean,
    ) : PlayStream("dsd-play") {
        @Volatile private var stopFlag = false
        @Volatile private var sink: com.uyatame.ucrt.player.dsd.DsdSink? = null
        @Volatile private var paused = startPaused
        @Volatile private var baseMs = fromMs

        override fun positionMs(): Long {
            val s = sink ?: return baseMs
            return baseMs + s.headFrames() * 1000L / s.sampleRate.coerceAtLeast(1)
        }

        override fun pauseAudio() { paused = true; sink?.pause() }
        override fun resumeAudio() { paused = false; sink?.play() }

        override fun halt() {
            stopFlag = true
            sink?.pause()
        }

        private fun openSink(info: com.uyatame.ucrt.player.dsd.DsdInfo, ch: Int): com.uyatame.ucrt.player.dsd.DsdSink {
            val mode = AudioEngine.dsdMode
            // 1) ネイティブ DSD(USB DAC 直接出力のとき)
            if (mode == 0 && AudioEngine.wantUsbDirect) {
                val f = runCatching { UsbDac.prepareDsd(info.rate, ch) }.getOrNull()
                if (f != null) return com.uyatame.ucrt.player.dsd.NativeDsdSink(f, ch, AudioEngine.dsdSwap)
            }
            // 2) DoP(加工なしで出せる出力先があるとき)
            if (mode != 2 && AudioEngine.wantUsbDirect && info.rate % 16 == 0) {
                val o = runCatching { AudioOutput(ctx, attrs, info.rate / 16, ch, 24, exact = true) }.getOrNull()
                if (o != null && o.exactOk) return com.uyatame.ucrt.player.dsd.DopSink(o, ch)
                o?.release()
                AudioEngine.log("dsd: DoP not available at ${info.rate / 16} Hz")
            }
            // 3) PCM に変換
            val conv = com.uyatame.ucrt.player.dsd.DsdToPcm(info.rate, ch)
            val o = AudioOutput(ctx, attrs, conv.outRate, ch, 24)
            return com.uyatame.ucrt.player.dsd.PcmSink(o, conv, ch, info.rate)
        }

        override fun run() {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            val rf = com.uyatame.ucrt.player.dsd.RandomFile.open(ctx, uri)
            if (rf == null) {
                AudioEngine.log("dsd: cannot open file")
                if (!stopFlag) main.post { if (file === this) next(true) }
                return
            }
            try {
                val info = com.uyatame.ucrt.player.dsd.DsdFile.parse(rf)
                if (info.compressed) throw java.io.IOException(T("DST 圧縮の DSDIFF には対応していません", "DST-compressed DSDIFF is not supported"))
                val d = info.durationMs
                main.post { if (file === this) { durationMs = d; publish() } }
                // 多チャンネルの場合は前方の左右だけを使う
                val ch = info.channels.coerceAtMost(2)
                val reader = com.uyatame.ucrt.player.dsd.DsdReader(rf, info)
                val start = (fromMs * info.rate / 8000L) and 7L.inv()
                reader.seek(start)
                baseMs = start * 8000L / info.rate
                val s = openSink(info, ch)
                sink = s
                if (stopFlag) return
                if (!paused) s.play()
                val how = when (s.label) {
                    "Native DSD" -> T("ネイティブ DSD", "Native DSD")
                    "DoP" -> "DoP"
                    else -> T("PCM に変換", "Converted to PCM") + " (${AudioEngine.khz(s.sampleRate)})"
                }
                AudioEngine.report(s.bitPerfect, info.label() + " · " + how)
                AudioEngine.log("dsd: ${info.label()} ${info.channels}ch ${if (info.dff) "DFF" else "DSF"} -> $how")
                val chunk = 8192
                val bufs = Array(info.channels) { ByteArray(chunk) }
                val use = Array(ch) { bufs[it] }
                while (!stopFlag && !s.failed) {
                    val n = reader.read(bufs, chunk)
                    if (n <= 0) break
                    s.write(use, n) { stopFlag }
                }
                if (s.failed) {
                    if (!stopFlag) main.post { if (file === this) recoverOutput() }
                    return
                }
                while (!stopFlag && s.headFrames() < s.framesWritten) Thread.sleep(50)
                if (!stopFlag) main.post { if (file === this) next(true) }
            } catch (e: Exception) {
                AudioEngine.log("dsd: playback error: ${e.javaClass.simpleName}: ${e.message}")
                if (!stopFlag) main.post { if (file === this) next(true) }
            } finally {
                sink?.release()
                rf.close()
            }
        }
    }

    /** 再生する PCM の元(WAV を直接読む / OS のデコーダーで変換する) */
    private abstract class PcmSource {
        var rate = 0
        var channels = 2
        var bits = 16
        var durationMs = 0L
        /** 最初に渡した音声の時刻(マイクロ秒。まだなら -1) */
        var firstPtsUs = -1L

        /** 次の音声を sink に渡す。終わりに達したら false(最後の分は渡してから false を返す) */
        abstract fun read(sink: (java.nio.ByteBuffer, Int) -> Unit): Boolean
        abstract fun close()
    }

    /** WAV を直接読む(デコーダーを通すと 24bit が 16bit に丸められることがあるため) */
    private inner class WavSource(private val inp: java.io.InputStream, w: WavInfo, fromMs: Long) : PcmSource() {
        private val enc = w.enc
        private val align = w.align
        private val buf: ByteArray = ByteArray(w.align * (w.rate / 20).coerceAtLeast(256))
        private val bb = java.nio.ByteBuffer.wrap(buf)
        private var left: Long

        init {
            rate = w.rate
            channels = w.channels
            bits = w.bits
            val total = if (w.dataLen in 1 until 0xFFFFFFFFL) w.dataLen / w.align else Long.MAX_VALUE
            if (total != Long.MAX_VALUE) durationMs = total * 1000L / w.rate
            val start = (fromMs * w.rate / 1000).coerceIn(0L, total)
            skipFully(inp, start * w.align)
            firstPtsUs = start * 1_000_000L / w.rate
            left = if (total == Long.MAX_VALUE) Long.MAX_VALUE else (total - start) * w.align
        }

        override fun read(sink: (java.nio.ByteBuffer, Int) -> Unit): Boolean {
            if (left <= 0) return false
            val want = minOf(buf.size.toLong(), left).toInt()
            val n = readFully(inp, buf, want)
            val usable = n - n % align
            if (usable <= 0) { left = 0; return false }
            bb.clear()
            bb.limit(usable)
            sink(bb, enc)
            if (left != Long.MAX_VALUE) left -= usable
            if (n < want) { left = 0; return false }
            return left > 0
        }

        override fun close() { runCatching { inp.close() } }
    }

    /** WAV なら直接読む元を作る。WAV として扱えなければ null(理由はログに出す) */
    private fun openWav(uri: Uri, fromMs: Long): PcmSource? {
        val name = runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, uri)?.name }.getOrNull()
        val looksWav = name?.lowercase()?.endsWith(".wav") == true ||
            runCatching { ctx.contentResolver.getType(uri) }.getOrNull()?.contains("wav") == true
        if (!looksWav) return null
        val raw = runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull()
        if (raw == null) { AudioEngine.log("wav: cannot open, using decoder"); return null }
        val inp = java.io.BufferedInputStream(raw, 1 shl 16)
        val w = runCatching { parseWav(inp) }.getOrNull()
        if (w == null) {
            AudioEngine.log("wav: unsupported header ($lastWavReason), using decoder")
            runCatching { inp.close() }
            return null
        }
        AudioEngine.log("wav: ${w.rate} Hz ${w.channels}ch ${AudioEngine.encName(w.enc)} (direct)")
        return runCatching { WavSource(inp, w, fromMs) }.getOrElse { runCatching { inp.close() }; null }
    }

    /** OS 内蔵のデコーダーで PCM にする */
    private inner class CodecSource(uri: Uri, fromMs: Long) : PcmSource() {
        private val ex = MediaExtractor()
        private var codec: MediaCodec? = null
        private val info = MediaCodec.BufferInfo()
        private var inEos = false
        private var outEos = false
        private var outFmt: MediaFormat? = null
        /** 準備のときに取り出した、まだ渡していない出力 */
        private var pending = -1

        init {
            try {
                ex.setDataSource(ctx, uri, null)
                val ti = (0 until ex.trackCount).first {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                ex.selectTrack(ti)
                val fmt = ex.getTrackFormat(ti)
                val mime = fmt.getString(MediaFormat.KEY_MIME)!!
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) durationMs = fmt.getLong(MediaFormat.KEY_DURATION) / 1000
                val srcBits = AudioInfo.bitsOf(ctx, uri)
                bits = srcBits
                if (fromMs > 0) ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val c = MediaCodec.createDecoderByType(mime)
                codec = c
                // 24bit などの音源は、精度を落とさないよう float で受け取る
                // audio/raw(WAV など)では KEY_PCM_ENCODING は「入力データの形式」を表すので書き換えない
                val raw = mime == MediaFormat.MIMETYPE_AUDIO_RAW
                val wantFloat = !raw && srcBits != 16 && !fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                if (wantFloat) fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AFormat.ENCODING_PCM_FLOAT)
                AudioEngine.log(
                    "decode: $mime ${runCatching { fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(0)} Hz" +
                        " srcBits=$srcBits" + (if (wantFloat) " (float requested)" else ""),
                )
                try {
                    c.configure(fmt, null, null, 0)
                } catch (e: Exception) {
                    if (!wantFloat) throw e
                    fmt.removeKey(MediaFormat.KEY_PCM_ENCODING)
                    c.configure(fmt, null, null, 0)
                }
                c.start()
                AudioEngine.log("decoder: ${c.name}")
                prime()
            } catch (e: Exception) {
                close()
                throw e
            }
        }

        /** 入力を 1 回入れて、出力を 1 回取り出す */
        private fun step(): Int {
            val c = codec ?: return -1
            if (!inEos) {
                val i = c.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val b = c.getInputBuffer(i)!!
                    val n = ex.readSampleData(b, 0)
                    if (n < 0) {
                        c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inEos = true
                    } else {
                        c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val o = c.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFmt = c.outputFormat
            return o
        }

        /** 出力の形式(周波数・チャンネル数)が分かるまで進める */
        private fun prime() {
            val c = codec ?: return
            var guard = 0
            while (outFmt == null && pending < 0) {
                val o = step()
                if (o >= 0) pending = o
                if (++guard > 3000) throw java.io.IOException("decoder did not start")
            }
            val f = outFmt ?: c.outputFormat
            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pe = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AFormat.ENCODING_PCM_16BIT
            AudioEngine.log("decoded: $rate Hz ${channels}ch ${AudioEngine.encName(pe)}")
        }

        override fun read(sink: (java.nio.ByteBuffer, Int) -> Unit): Boolean {
            val c = codec ?: return false
            if (outEos) return false
            val o = if (pending >= 0) pending.also { pending = -1 } else step()
            // まだ出力が無い(待ち時間切れ・形式の変更)ときは、止める指示を確かめられるよう一度戻る
            if (o < 0) return true
            val f = outFmt ?: c.outputFormat
            val enc = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AFormat.ENCODING_PCM_16BIT
            if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs.coerceAtLeast(0)
            if (info.size > 0) {
                val bb = c.getOutputBuffer(o)!!
                bb.position(info.offset)
                bb.limit(info.offset + info.size)
                sink(bb, enc)
            }
            c.releaseOutputBuffer(o, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
            return !outEos
        }

        override fun close() {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            runCatching { ex.release() }
        }
    }

    /**
     * 端末内のファイルを再生する。曲の終わりで、次の曲が同じ形式(周波数・チャンネル数・ビット数)なら、
     * 出力を閉じずにそのまま続けて書き込む(ギャップレス再生)。
     */
    private inner class FileStream(
        val first: PlayItem,
        val fromMs: Long,
        startPaused: Boolean,
    ) : PlayStream("file-play") {
        @Volatile private var stopFlag = false
        @Volatile private var out: PcmOut? = null
        @Volatile private var paused = startPaused
        private val segs = Segments().also { it.baseMs = fromMs }
        override val seg: Segments get() = segs

        override fun head(): Long = out?.headFrames() ?: 0L

        override fun positionMs(): Long {
            val o = out ?: return segs.baseMs
            return segs.baseMs + (o.headFrames() - segs.startFrame).coerceAtLeast(0L) * 1000L / o.sampleRate.coerceAtLeast(1)
        }

        override fun pauseAudio() { paused = true; out?.pause() }
        override fun resumeAudio() { paused = false; out?.play() }

        override fun halt() {
            stopFlag = true
            out?.pause()
        }

        private fun open(item: PlayItem, from: Long): PcmSource {
            val uri = item.uri ?: throw java.io.IOException("no file")
            return openWav(uri, from) ?: CodecSource(uri, from)
        }

        override fun run() {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            var src: PcmSource? = null
            try {
                var item = first
                var cur = open(item, fromMs)
                src = cur
                val d0 = cur.durationMs
                if (d0 > 0) main.post { if (file === this) { durationMs = d0; publish() } }
                val o = openOutput(cur.rate, cur.channels, cur.bits)
                out = o
                if (stopFlag) return
                if (!paused) o.play()
                var firstData = true
                val sink: (java.nio.ByteBuffer, Int) -> Unit = { bb, enc -> o.write(bb, enc) { stopFlag } }
                prefetch(item)
                while (!stopFlag && !o.failed) {
                    while (!stopFlag && !o.failed) {
                        val more = cur.read(sink)
                        if (firstData && cur.firstPtsUs >= 0) {
                            firstData = false
                            segs.baseMs = cur.firstPtsUs / 1000
                        }
                        if (!more) break
                    }
                    if (stopFlag || o.failed) break
                    // ギャップレス: 次の曲が同じ形式なら、出力を閉じずに続ける
                    val nx = gaplessAfter(item.id, false) ?: break
                    // 先に開いておいた次の曲を使う(違う曲になっていたら開き直す)
                    val ready = takePrefetched(nx)
                    val opened: PcmSource? = ready ?: try {
                        open(nx, 0)
                    } catch (e: Exception) {
                        AudioEngine.log("gapless: cannot open next (${e.javaClass.simpleName})")
                        null
                    }
                    val ns = opened ?: break
                    if (ns.rate != cur.rate || ns.channels != cur.channels || ns.bits != cur.bits) {
                        AudioEngine.log("gapless: format changes (${ns.rate} Hz ${ns.bits}bit), reopening output")
                        ns.close()
                        break
                    }
                    cur.close()
                    segs.pending.add(Switch(nx, o.framesWritten, 0L, ns.durationMs))
                    AudioEngine.log("gapless: next track joined")
                    cur = ns
                    src = ns
                    item = nx
                    prefetch(item)
                }
                if (o.failed) {
                    if (!stopFlag) main.post { if (file === this) recoverOutput() }
                    return
                }
                while (!stopFlag && o.headFrames() < o.framesWritten) Thread.sleep(50)
                if (!stopFlag) main.post { if (file === this) { applySwitches(this, segs, Long.MAX_VALUE); next(true) } }
            } catch (e: Exception) {
                AudioEngine.log("playback error: ${e.javaClass.simpleName}: ${e.message}")
                // 書き込み済みの分(前の曲の終わり)は鳴らし切ってから次へ
                out?.let { o -> while (!stopFlag && o.headFrames() < o.framesWritten) Thread.sleep(50) }
                if (!stopFlag) main.post { if (file === this) { applySwitches(this, segs, Long.MAX_VALUE); next(true) } }
            } finally {
                src?.close()
                discardPrefetch()
                prefetchExec.shutdown()
                out?.release()
            }
        }

        // ---- 次の曲の先読み(曲の境目で開く時間を待たないように) ----
        private val prefetchExec = java.util.concurrent.Executors.newSingleThreadExecutor()
        private var pre: Pair<Long, java.util.concurrent.Future<PcmSource?>>? = null

        private fun prefetch(after: PlayItem) {
            discardPrefetch()
            val nx = gaplessAfter(after.id, false) ?: return
            pre = nx.id to prefetchExec.submit(java.util.concurrent.Callable<PcmSource?> {
                if (stopFlag) null else runCatching { open(nx, 0) }.getOrNull()
            })
        }

        private fun takePrefetched(nx: PlayItem): PcmSource? {
            val p = pre ?: return null
            pre = null
            if (p.first != nx.id) {
                discard(p.second)
                return null
            }
            return runCatching { p.second.get() }.getOrNull()
        }

        private fun discardPrefetch() {
            val p = pre ?: return
            pre = null
            discard(p.second)
        }

        /** 使わない先読みは、開き終わってから閉じる */
        private fun discard(f: java.util.concurrent.Future<PcmSource?>) {
            runCatching { prefetchExec.execute { runCatching { f.get()?.close() } } }
        }
    }

    private class WavInfo(val enc: Int, val channels: Int, val rate: Int, val bits: Int, val align: Int, val dataLen: Long)

    private fun readFully(inp: java.io.InputStream, b: ByteArray, n: Int): Int {
        var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun skipFully(inp: java.io.InputStream, n0: Long) {
        var n = n0
        val tmp = ByteArray(8192)
        while (n > 0) {
            val s = inp.skip(n)
            if (s > 0) { n -= s; continue }
            val r = inp.read(tmp, 0, minOf(tmp.size.toLong(), n).toInt())
            if (r < 0) return
            n -= r
        }
    }

    /** WAV のヘッダーを読み、data チャンクの先頭まで進める。扱えない形式なら null */
    /** WAV を直接読めなかった理由(ログ用) */
    @Volatile private var lastWavReason = ""

    private fun parseWav(inp: java.io.InputStream): WavInfo? {
        lastWavReason = "header"
        val iso = Charsets.ISO_8859_1
        fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        fun le32(b: ByteArray, i: Int): Long = (le16(b, i).toLong()) or (le16(b, i + 2).toLong() shl 16)
        val h = ByteArray(12)
        if (readFully(inp, h, 12) < 12) return null
        if (String(h, 0, 4, iso) != "RIFF" || String(h, 8, 4, iso) != "WAVE") return null
        var tag = 0; var ch = 0; var sr = 0; var bits = 0; var align = 0
        var haveFmt = false
        val ck = ByteArray(8)
        while (true) {
            if (readFully(inp, ck, 8) < 8) return null
            val id = String(ck, 0, 4, iso)
            val len = le32(ck, 4)
            when (id) {
                "fmt " -> {
                    if (len < 16 || len > 4096) return null
                    val f = ByteArray(len.toInt())
                    if (readFully(inp, f, f.size) < f.size) return null
                    if (len % 2 == 1L) skipFully(inp, 1)
                    tag = le16(f, 0); ch = le16(f, 2); sr = le32(f, 4).toInt(); align = le16(f, 12); bits = le16(f, 14)
                    if (tag == 0xFFFE && len >= 26) tag = le16(f, 24)
                    haveFmt = true
                }
                "data" -> {
                    lastWavReason = "tag=$tag ch=$ch rate=$sr bits=$bits align=$align"
                    if (!haveFmt) return null
                    val enc = when {
                        tag == 1 && bits == 8 -> AFormat.ENCODING_PCM_8BIT
                        tag == 1 && bits == 16 -> AFormat.ENCODING_PCM_16BIT
                        tag == 1 && bits == 24 -> AFormat.ENCODING_PCM_24BIT_PACKED
                        tag == 1 && bits == 32 -> AFormat.ENCODING_PCM_32BIT
                        tag == 3 && bits == 32 -> AFormat.ENCODING_PCM_FLOAT
                        else -> return null
                    }
                    lastWavReason = "tag=$tag ch=$ch rate=$sr bits=$bits align=$align"
                    if (ch !in 1..2 || sr <= 0 || align != ch * bits / 8) return null
                    return WavInfo(enc, ch, sr, bits, align, len)
                }
                else -> skipFully(inp, len + (len and 1L))
            }
        }
    }

    private inner class CdStream(
        val d: UsbScsiDrive,
        val firstItem: PlayItem,
        val firstTrack: TocTrack,
        val baseSector: Int,
        val startPaused: Boolean,
    ) : Thread("cd-play") {
        @Volatile private var stopFlag = false
        // 出力はスレッドの中で作る(USB DAC の準備で画面が止まらないように)
        @Volatile private var out: PcmOut? = null
        @Volatile private var paused = startPaused
        val seg = Segments().also { it.baseMs = baseSector * 1000L / 75 }

        fun head(): Long = out?.headFrames() ?: 0L

        fun positionMs(): Long = seg.baseMs + (head() - seg.startFrame).coerceAtLeast(0L) * 1000L / 44100L

        fun pauseAudio() { paused = true; out?.pause() }
        fun resumeAudio() { paused = false; out?.play() }

        fun halt() {
            stopFlag = true
            out?.pause()
        }

        private fun readSafe(lba: Int, n: Int): ByteArray {
            for (a in 0 until 2) {
                try {
                    val r = d.readCd(lba, n, false)
                    if (r.size >= n * 2352) return r
                } catch (e: Exception) {
                    if (stopFlag) break
                }
            }
            return ByteArray(n * 2352)
        }

        private fun endOf(t: TocTrack) = if (leadOut > 0) minOf(t.endLba, leadOut) else t.endLba

        override fun run() {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            runCatching { d.setSpeed(cdSpeedX * 176) }
            var item = firstItem
            var end = endOf(firstTrack)
            var lba = firstTrack.startLba + baseSector
            try {
                val o = openOutput(44100, 2, 16)
                out = o
                if (stopFlag) return
                if (!paused) o.play()
                while (!stopFlag && !o.failed) {
                    while (!stopFlag && !o.failed && lba < end) {
                        val n = minOf(27, end - lba)
                        val data = readSafe(lba, n)
                        o.write(java.nio.ByteBuffer.wrap(data, 0, n * 2352), AFormat.ENCODING_PCM_16BIT) { stopFlag }
                        lba += n
                    }
                    if (stopFlag || o.failed) break
                    // ギャップレス: 次の曲も CD の曲なら、そのまま読み続ける
                    val nx = gaplessAfter(item.id, true) ?: break
                    val t = nx.cdTrack ?: break
                    seg.pending.add(Switch(nx, o.framesWritten, 0L, nx.durationMs))
                    item = nx
                    lba = t.startLba
                    end = endOf(t)
                }
                if (o.failed) {
                    if (!stopFlag) main.post { if (cd === this) recoverOutput() }
                    return
                }
                while (!stopFlag && o.headFrames() < o.framesWritten) Thread.sleep(50)
                if (!stopFlag) main.post { if (cd === this) { applySwitches(this, seg, Long.MAX_VALUE); next(true) } }
            } catch (e: Exception) {
                // 停止・切断時
            } finally {
                out?.release()
            }
        }
    }
}
