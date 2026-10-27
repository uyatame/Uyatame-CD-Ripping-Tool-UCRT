package com.uyatame.ucrt.legacy

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.library.Cue
import com.uyatame.ucrt.library.LibTrack
import com.uyatame.ucrt.player.Decoders
import com.uyatame.ucrt.player.RationalResampler

/** 再生リストの 1 曲(同じ曲を 2 回入れても別物として扱うため、番号を付ける) */
class QItem(val track: LibTrack, val id: Long = nextId()) {
    val title get() = track.title
    val artist get() = track.artist
    val album get() = track.album
    val uri get() = track.uri

    companion object {
        private var counter = 0L
        @Synchronized fun nextId(): Long = ++counter
    }
}

/**
 * 古い Android 向けの再生エンジン(本家の PlayerController)。曲はアプリ内蔵のデコーダー(core の Decoders)で読み、
 * AudioTrack(16 ビット)で鳴らす。AudioTrack が受け付けないサンプリング周波数(Android 4.x は 48 kHz まで、
 * 5.x は 96 kHz まで)は変換し、3 チャンネル以上はステレオにまとめる。
 * 同じ形式の曲が続くときは AudioTrack を作り直さずに続けて鳴らす(ギャップレス)
 */
class LegacyPlayer(ctx: Context) {
    /** 再生できなかったとき(メッセージ)。メインのスレッドで呼ぶ */
    var onError: ((String) -> Unit)? = null
    /** 曲が始まったとき(履歴に記録する)。メインのスレッドで呼ぶ */
    var onTrackStart: ((QItem) -> Unit)? = null
    /** CD の曲(URI が cd: の曲)を開く方法(ディスクの画面が決める) */
    @Volatile internal var cdOpener: ((QItem) -> Decoders.Opened?)? = null

    /** 音の加工(イコライザーなど)。再生のスレッドで呼ぶ。frames × ch の float(-1〜1) */
    @Volatile var dsp: Dsp? = null

    interface Dsp {
        /** 曲が始まるとき(周波数・チャンネル・曲) */
        fun start(rate: Int, channels: Int, item: QItem)
        fun process(buf: FloatArray, frames: Int, channels: Int)
    }

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Object()

    // ---- 画面から見る状態 ----
    @Volatile var queue: List<QItem> = emptyList(); private set
    @Volatile var index = -1; private set
    @Volatile var isPlaying = false; private set
    val current: QItem? get() = queue.getOrNull(index)
    @Volatile var durationMs = 0L; private set
    /** 0 = リピートしない、1 = 全曲、2 = 1 曲 */
    @Volatile var repeatMode = 0; private set
    @Volatile var shuffle = false; private set
    /** シャッフル前の並び */
    @Volatile var original: List<QItem> = emptyList(); private set
    /** スリープタイマーで止める時刻(SystemClock.elapsedRealtime。0 = なし) */
    @Volatile var sleepAtMs = 0L; private set
    /** この曲の終わりで止める */
    @Volatile var stopAfterTrack = false; private set
    /** 曲間を詰める(ギャップレス) */
    @Volatile var gapless = true
    /** DAR(PCM アップサンプリング): 0 = オフ、1 = オン。pcm: 上限の倍率。integer: 整数倍だけ */
    @Volatile var darMode = 0
    @Volatile var darPcm = 0
    @Volatile var darInteger = true
    /** 今の曲の音の形式(例: FLAC 96kHz/24bit → 48kHz/16bit) */
    @Volatile var formatText = ""; private set

    val positionMs: Long get() = synchronized(lock) { posMs() }

    // ---- 再生のスレッドへの指示(lock で守る) ----
    private var reopen = false
    private var seekTo = -1L
    private var continuous = false
    private var quit = false
    private var worker: Thread? = null

    // ---- 再生のスレッドの中の位置(lock で守る) ----
    private var track: AudioTrack? = null
    private var outRate = 0
    private var headBase = 0L
    /** 今の曲の、最初に書いたサンプルの曲の中での位置(ミリ秒。CUE の曲は曲の頭から) */
    private var startMs = 0L
    private var written = 0L
    private var pausedPos = 0L

    // ---- USB DAC への直接出力(使っているときは AudioTrack の代わり) ----
    /** USB DAC の再生セッション(-1 = 使っていない) */
    private var usbSession = -1
    private var usbFmt: com.uyatame.ucrt.legacy.usb.DacFormat? = null
    private var usbBytes = ByteArray(0)
    /** USB DAC に直接出すか(設定) */
    @Volatile var usbDirect = false
    /** DSD の送り方(0 = 自動: ネイティブ DSD → DoP → PCM、1 = DoP、2 = いつも PCM) */
    @Volatile var dsdMode = 0
    /** ネイティブ DSD のバイトの順を入れ替える */
    @Volatile var dsdSwap = false

    // ---- キャスト(Chromecast)。つないでいる間は端末では鳴らさず、操作を送る ----
    /** キャストしている(端末の再生のスレッドは止めておく) */
    @Volatile private var remote = false
    val casting: Boolean get() = com.uyatame.ucrt.legacy.cast.CastManager.active

    private fun posMs(): Long {
        if (remote) return com.uyatame.ucrt.legacy.cast.CastManager.positionMs().coerceIn(0L, maxOf(durationMs, 0L))
        if (usbSession >= 0) {
            val played = (com.uyatame.ucrt.legacy.usb.UsbDac.played(usbSession) - headBase).coerceAtLeast(0)
            return (startMs + played * 1000 / maxOf(outRate, 1)).coerceAtMost(maxOf(durationMs, startMs))
        }
        val t = track ?: return startMs
        if (!isPlaying && t.playState != AudioTrack.PLAYSTATE_PLAYING) return pausedPos.coerceAtLeast(startMs)
        val head = (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
        val played = (head - headBase).coerceAtLeast(0)
        return (startMs + played * 1000 / maxOf(outRate, 1)).coerceAtMost(maxOf(durationMs, startMs))
    }

    // =====================================================================
    // 操作(メインのスレッドから)
    // =====================================================================

    /** 曲を並べて再生する(shuffleOn なら選んだ曲を先頭にして残りを混ぜる) */
    fun play(items: List<LibTrack>, start: Int, shuffleOn: Boolean = false) {
        if (items.isEmpty()) return
        val q = items.map { QItem(it) }
        val st = start.coerceIn(0, q.lastIndex)
        original = q
        if (shuffleOn) {
            val first = q[st]
            val rest = (q.toMutableList().apply { removeAt(st) }).shuffled()
            setQueue(listOf(first) + rest, 0)
            shuffle = true
        } else {
            shuffle = false
            setQueue(q, st)
        }
        startAt(0, true)
    }

    private fun setQueue(q: List<QItem>, at: Int) {
        synchronized(lock) {
            queue = q
            index = at
        }
    }

    /** 前回の状態に戻す(再生はしない) */
    fun restore(items: List<QItem>, at: Int, posMs: Long, shuffleOn: Boolean, repeat: Int, orig: List<QItem>) {
        if (items.isEmpty()) return
        original = orig
        shuffle = shuffleOn
        repeatMode = repeat
        setQueue(items, at.coerceIn(0, items.lastIndex))
        synchronized(lock) {
            startMs = posMs.coerceAtLeast(0)
            pausedPos = startMs
            durationMs = current?.track?.durationMs ?: 0L
            seekTo = startMs
            reopen = false
        }
        restoredAt = posMs
        changed()
    }

    /** 戻したあと、最初に再生するときの位置 */
    private var restoredAt = -1L

    private fun startAt(fromMs: Long, play: Boolean) {
        if (com.uyatame.ucrt.legacy.cast.CastManager.active) { castStart(fromMs, play); return }
        synchronized(lock) {
            reopen = true
            seekTo = fromMs
            isPlaying = play
            continuous = false
            startMs = fromMs
            pausedPos = fromMs
            durationMs = current?.track?.durationMs ?: 0L
            unblock()
            lock.notifyAll()
        }
        restoredAt = -1
        if (play) requestFocus()
        ensureWorker()
        changed()
    }

    fun toggle() { if (isPlaying) pause() else resume() }

    /** キャスト先で曲を始める(端末の出力は止める) */
    private fun castStart(fromMs: Long, play: Boolean) {
        val cur = current ?: return
        synchronized(lock) {
            remote = true
            reopen = false
            seekTo = -1
            isPlaying = play
            durationMs = cur.track.durationMs
            startMs = fromMs
            pausedPos = fromMs
            track?.pause()
            track?.flush()
            if (usbSession >= 0) com.uyatame.ucrt.legacy.usb.UsbDac.setPaused(usbSession, true)
            lock.notifyAll()
        }
        restoredAt = -1
        formatText = ""
        val t = cur.track
        com.uyatame.ucrt.legacy.cast.CastManager.load(
            app,
            com.uyatame.ucrt.legacy.cast.CastItem(cur.id, cur.uri, cur.title, cur.artist, t.album, t.durationMs, t.startMs, t.endMs, Actions.artRef(app, t)),
            fromMs, play,
        )
        main.post { onTrackStart?.invoke(cur) }
        changed()
    }

    /** Chromecast につないだ・切れたとき: 今の曲を、つないだ先(または端末)で同じ位置から続ける */
    private fun castChanged(on: Boolean) {
        val pos = positionMs
        val play = isPlaying
        if (on) {
            if (current != null) castStart(pos, play)
        } else {
            synchronized(lock) { remote = false }
            // 端末に戻ったら、一時停止した状態で同じ位置に戻す
            if (current != null) startAt(pos, false)
        }
    }

    init {
        com.uyatame.ucrt.legacy.cast.CastManager.onFinished = {
            if (remote && current != null) {
                if (repeatMode == 2) startAt(0, true)
                else if (index + 1 < queue.size || repeatMode == 1) next(auto = true)
                else { synchronized(lock) { isPlaying = false; pausedPos = durationMs }; changed() }
            }
        }
        com.uyatame.ucrt.legacy.cast.CastManager.onConnectionChanged = { on -> castChanged(on) }
        com.uyatame.ucrt.legacy.cast.CastManager.onError = { reason ->
            onError?.invoke(
                when (reason) {
                    "cd" -> S(R.string.cast_unsupported_cd)
                    "dsd" -> S(R.string.cast_unsupported_dsd)
                    "cue", "format" -> S(R.string.cast_unsupported_format)
                    "connect", "timeout" -> S(R.string.cast_connect_failed)
                    else -> S(R.string.cast_load_failed)
                },
            )
            if (remote && isPlaying) {
                synchronized(lock) { isPlaying = false }
                changed()
            }
        }
    }

    /** 出力先を作り直す(USB DAC の直接出力を入り切りしたとき・DAC をつないだ/外したとき)。今の位置から鳴らし直す */
    fun reopenOutput() {
        if (current == null) return
        if (restoredAt >= 0) return
        startAt(positionMs, isPlaying)
    }

    fun pause() {
        if (remote) {
            synchronized(lock) { pausedPos = posMs(); isPlaying = false }
            com.uyatame.ucrt.legacy.cast.CastManager.pause()
            changed()
            return
        }
        synchronized(lock) {
            pausedPos = posMs()
            isPlaying = false
            track?.pause()
            if (usbSession >= 0) com.uyatame.ucrt.legacy.usb.UsbDac.setPaused(usbSession, true)
            lock.notifyAll()
        }
        changed()
    }

    fun resume() {
        val cur = current ?: return
        if (remote) {
            if (com.uyatame.ucrt.legacy.cast.CastManager.isLoaded(cur.id)) {
                synchronized(lock) { isPlaying = true }
                com.uyatame.ucrt.legacy.cast.CastManager.play()
                changed()
            } else castStart(if (restoredAt >= 0) restoredAt else positionMs, true)
            return
        }
        if (restoredAt >= 0 || worker?.isAlive != true && track == null) {
            // 前回の続き(まだ開いていない)
            val at = if (restoredAt >= 0) restoredAt else 0L
            startAt(at, true)
            return
        }
        requestFocus()
        synchronized(lock) {
            isPlaying = true
            track?.play()
            if (usbSession >= 0) com.uyatame.ucrt.legacy.usb.UsbDac.setPaused(usbSession, false)
            lock.notifyAll()
        }
        ensureWorker()
        changed()
    }

    fun next(auto: Boolean = false) {
        val q = queue
        if (q.isEmpty()) return
        val n = index + 1
        if (n >= q.size) {
            if (repeatMode == 1 || !auto) {
                if (repeatMode == 1) { setQueue(q, 0); startAt(0, isPlaying || auto) }
                return
            }
            return
        }
        setQueue(q, n)
        startAt(0, isPlaying || auto)
    }

    fun prev() {
        // 3 秒より後ろなら曲の頭へ、前なら前の曲へ
        if (positionMs > 3000 || index <= 0) {
            if (index <= 0 && repeatMode == 1 && queue.size > 1 && positionMs <= 3000) {
                setQueue(queue, queue.lastIndex); startAt(0, isPlaying); return
            }
            seek(0); return
        }
        setQueue(queue, index - 1)
        startAt(0, isPlaying)
    }

    fun seek(ms: Long) {
        if (current == null) return
        startAt(ms.coerceIn(0, maxOf(durationMs, 0)), isPlaying)
    }

    fun jumpTo(i: Int) {
        if (i !in queue.indices) return
        setQueue(queue, i)
        startAt(0, true)
    }

    fun stop() {
        if (remote) com.uyatame.ucrt.legacy.cast.CastManager.stopMedia()
        synchronized(lock) {
            queue = emptyList()
            index = -1
            isPlaying = false
            reopen = true
            unblock()
            lock.notifyAll()
        }
        original = emptyList()
        sleepAtMs = 0; stopAfterTrack = false
        am.abandonAudioFocus(focus)
        changed()
    }

    fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        changed()
    }

    fun toggleShuffle() {
        val cur = current ?: return
        if (!shuffle) {
            original = queue
            val rest = queue.filter { it.id != cur.id }.shuffled()
            setQueue(listOf(cur) + rest, 0)
            shuffle = true
        } else {
            val orig = original.ifEmpty { queue }
            val i = orig.indexOfFirst { it.id == cur.id }.coerceAtLeast(0)
            setQueue(orig, i)
            shuffle = false
        }
        changed()
    }

    fun playNext(items: List<LibTrack>) {
        val add = items.map { QItem(it) }
        if (current == null) { play(items, 0); return }
        val q = queue.toMutableList().apply { addAll(index + 1, add) }
        if (shuffle) {
            val cur = current
            val o = original.toMutableList()
            val ci = o.indexOfFirst { it.id == cur?.id }.let { if (it < 0) o.lastIndex else it }
            o.addAll(ci + 1, add)
            original = o
        } else original = q
        setQueue(q, index)
        changed()
    }

    fun addToQueue(items: List<LibTrack>) {
        val add = items.map { QItem(it) }
        if (current == null) { play(items, 0); return }
        val q = queue + add
        original = if (shuffle) original + add else q
        setQueue(q, index)
        changed()
    }

    fun removeAt(i: Int) {
        if (i !in queue.indices) return
        val gone = queue[i]
        val q = queue.toMutableList().apply { removeAt(i) }
        original = original.filter { it.id != gone.id }
        when {
            q.isEmpty() -> { stop(); return }
            i < index -> setQueue(q, index - 1)
            i == index -> {
                val play = isPlaying
                setQueue(q, i.coerceAtMost(q.lastIndex))
                startAt(0, play)
                return
            }
            else -> setQueue(q, index)
        }
        changed()
    }

    fun move(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val cur = current
        val q = queue.toMutableList().apply { add(to, removeAt(from)) }
        if (!shuffle) original = q
        setQueue(q, q.indexOfFirst { it.id == cur?.id }.coerceAtLeast(0))
        changed()
    }

    fun clearUpcoming() {
        if (index < 0) return
        val keep = queue.subList(0, index + 1).toList()
        val dropped = queue.subList(index + 1, queue.size).map { it.id }.toHashSet()
        original = original.filter { it.id !in dropped }
        setQueue(keep, index)
        changed()
    }

    // ---- スリープタイマー ----

    private val sleepCheck = object : Runnable {
        override fun run() {
            val at = sleepAtMs
            if (at <= 0) return
            if (SystemClock.elapsedRealtime() >= at) {
                sleepAtMs = 0
                pause()
                return
            }
            main.postDelayed(this, 1000)
        }
    }

    fun setSleep(minutes: Int) {
        stopAfterTrack = false
        sleepAtMs = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
        main.removeCallbacks(sleepCheck)
        if (minutes > 0) main.post(sleepCheck)
        changed()
    }

    fun stopAtTrackEnd(on: Boolean) {
        stopAfterTrack = on
        if (on) { sleepAtMs = 0; main.removeCallbacks(sleepCheck) }
        changed()
    }

    fun release() {
        synchronized(lock) {
            quit = true
            lock.notifyAll()
        }
        worker?.join(2000)
        worker = null
        am.abandonAudioFocus(focus)
    }

    /** 一時停止中に書き込みで止まっている再生のスレッドを動かす(書いた分は捨てる) */
    private fun unblock() {
        track?.let { if (it.playState == AudioTrack.PLAYSTATE_PAUSED) it.flush() }
    }

    private fun changed() = Bus.emit(Bus.PLAYER)

    // ---- オーディオフォーカス(ほかのアプリが鳴らすときは止める) ----
    private var duckedPause = false
    private val focus = AudioManager.OnAudioFocusChangeListener { f ->
        main.post {
            when (f) {
                AudioManager.AUDIOFOCUS_LOSS -> pause()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (isPlaying) { duckedPause = true; pause() }
                AudioManager.AUDIOFOCUS_GAIN -> if (duckedPause) { duckedPause = false; resume() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }

    // =====================================================================
    // 再生のスレッド
    // =====================================================================

    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        quit = false
        worker = Thread({ run() }, "ucrt-legacy-player").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private var errorRun = 0

    private fun run() {
        var opened: Decoders.Opened? = null
        var conv: Converter? = null
        /** DSD を USB DAC に DSD のまま送っているとき */
        var dsd: DsdUsb? = null
        /** 今の曲の終わり(サンプルの番号。CUE の曲。0 = ファイルの終わりまで) */
        var endSample = 0L
        var item: QItem? = null
        try {
            while (true) {
                var toOpen: QItem? = null
                var seek = -1L
                synchronized(lock) {
                    while (!quit && (!isPlaying || current == null || remote) && !reopen) lock.wait()
                    if (quit) return
                    if (reopen) {
                        reopen = false
                        toOpen = current
                        seek = seekTo.coerceAtLeast(0)
                        seekTo = -1
                        if (toOpen == null) {
                            opened?.close(); opened = null; conv = null
                            dsd?.close(); dsd = null
                            track?.pause(); track?.flush()
                            endUsb()
                        }
                    }
                }
                val t = toOpen
                if (t != null) {
                    opened?.close()
                    opened = null
                    conv = null
                    dsd?.close()
                    dsd = null
                    item = t
                    try {
                        val file = Cue.fileUri(t.uri)
                        val name = android.net.Uri.decode(file).substringAfterLast('/')
                        // DSD の曲は、USB DAC に DSD のまま送れればそうする(ネイティブ DSD → DoP)
                        if (usbDirect && dsdMode != 2 && !t.uri.startsWith("cd:") && t.track.startMs == 0L && t.track.endMs == 0L &&
                            com.uyatame.ucrt.player.dsd.DsdFile.isDsd(name)
                        ) {
                            val du = DsdUsb.open(app, android.net.Uri.parse(file), seek, dsdMode, dsdSwap)
                            if (du != null) {
                                dsd = du
                                durationMs = du.durationMs
                                formatText = du.label
                                dsp?.start(du.fmt.rate, 2, t)
                                startUsbRaw(du.fmt, du.startMs)
                                errorRun = 0
                                main.post { onTrackStart?.invoke(t) }
                                changed()
                                continue
                            }
                        }
                        val o = if (t.uri.startsWith("cd:")) cdOpener?.invoke(t) ?: throw java.io.IOException("CD")
                        else Decoders.open(app, android.net.Uri.parse(file), name)
                        opened = o
                        val d = o.decoder
                        val rate = d.sampleRate.coerceAtLeast(1)
                        val base = t.track.startMs * rate / 1000
                        endSample = if (t.track.endMs > 0) t.track.endMs * rate / 1000 else 0L
                        val fileDur = if (d.totalSamples > 0) d.totalSamples * 1000 / rate else t.track.durationMs
                        durationMs = if (t.track.endMs > 0 || t.track.startMs > 0) {
                            (if (t.track.endMs > 0) t.track.endMs else fileDur) - t.track.startMs
                        } else fileDur
                        // USB DAC に直接出すときは、DAC が受け付ける形式を先に決める(使えなければ AudioTrack)
                        val outCh = if (d.channels == 1) 1 else 2
                        val dac = if (usbDirect) {
                            val darMax = if (darMode == 1) (if (darPcm == 0) 768000 else d.sampleRate * (1 shl darPcm)) else 0
                            com.uyatame.ucrt.legacy.usb.UsbDac.prepare(d.sampleRate, outCh, d.bitsPerSample, darMaxRate = darMax)
                        } else null
                        val c = Converter(d.sampleRate, d.channels, d.bitsPerSample, if (dac != null) 0 else SoundDsp.darRate(d.sampleRate, darMode, darPcm, darInteger), dac?.rate ?: 0)
                        c.dac = dac
                        conv = c
                        val skipTo = base + seek * rate / 1000
                        if (skipTo > 0) d.seekSample(skipTo)
                        c.skipBefore = skipTo
                        dsp?.start(c.outRate, c.outChannels, t)
                        startTrack(c, seek)
                        formatText = formatOf(name, d, c)
                        errorRun = 0
                        main.post { onTrackStart?.invoke(t) }
                        changed()
                    } catch (e: Exception) {
                        val msg = S(R.string.legacy_cannot_play, t.title, e.message ?: e.javaClass.simpleName)
                        main.post { onError?.invoke(msg) }
                        opened?.close(); opened = null
                        if (++errorRun < 5 && advance(true)) continue
                        synchronized(lock) { isPlaying = false }
                        changed()
                        continue
                    }
                }
                val du = dsd
                if (du != null) {
                    if (!isPlaying) continue
                    val id = synchronized(lock) { usbSession }
                    val k = du.step(id) { synchronized(lock) { reopen || quit || !isPlaying } }
                    if (k > 0) { synchronized(lock) { written += k }; continue }
                    if (k < 0) continue
                    // 曲の終わり
                    du.close()
                    dsd = null
                    if (stopAfterTrack) {
                        stopAfterTrack = false
                        drainThenPause()
                        synchronized(lock) { if (index + 1 < queue.size) { index++; reopen = false; seekTo = 0; startMs = 0; pausedPos = 0 } }
                        restoredAt = 0
                        changed()
                        continue
                    }
                    drainUsb()
                    if (!advance(false)) {
                        drainThenPause()
                        changed()
                    }
                    continue
                }
                val o = opened ?: continue
                val c = conv ?: continue
                if (!isPlaying) continue
                val d = o.decoder
                val ended = if (endSample > 0 && d.frameSample >= endSample) true else !d.decodeNext()
                if (ended) {
                    o.close()
                    opened = null
                    if (stopAfterTrack) {
                        stopAfterTrack = false
                        drainThenPause()
                        synchronized(lock) { if (index + 1 < queue.size) { index++; reopen = false; seekTo = 0; startMs = 0; pausedPos = 0 } }
                        restoredAt = 0
                        changed()
                        continue
                    }
                    if (!advance(gapless)) {
                        drainThenPause()
                        changed()
                    }
                    continue
                }
                var n = c.convert(d, if (endSample > 0) endSample else Long.MAX_VALUE)
                if (n > 0) {
                    dsp?.process(c.mixed, n, c.outChannels)
                    if (c.dac != null) {
                        n = c.resample(n)
                        writeUsb(c, n)
                    } else if (trackFloat) {
                        n = c.resample(n)
                        writeFloat(c, n)
                    } else {
                        n = c.finish(n)
                        write(c.out, n * c.outChannels)
                    }
                }
            }
        } catch (e: InterruptedException) {
        } finally {
            opened?.close()
            dsd?.close()
            synchronized(lock) {
                track?.release()
                track = null
                endUsb()
            }
        }
    }

    /** 書いた分を鳴らし終えてから止める(最後の曲・この曲で止める) */
    private fun drainThenPause() {
        if (usbSession >= 0) {
            val until = SystemClock.elapsedRealtime() + 3000
            while (SystemClock.elapsedRealtime() < until) {
                if (com.uyatame.ucrt.legacy.usb.UsbDac.played(usbSession) >= written || synchronized(lock) { reopen || quit }) break
                Thread.sleep(20)
            }
            synchronized(lock) {
                pausedPos = posMs()
                isPlaying = false
                com.uyatame.ucrt.legacy.usb.UsbDac.setPaused(usbSession, true)
            }
            return
        }
        val t = synchronized(lock) { track } ?: return
        val until = SystemClock.elapsedRealtime() + 3000
        while (SystemClock.elapsedRealtime() < until) {
            val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head >= written || synchronized(lock) { reopen || quit }) break
            Thread.sleep(20)
        }
        synchronized(lock) {
            pausedPos = posMs()
            isPlaying = false
            t.pause()
        }
    }

    /** 次の曲へ進める(進めたら true)。 cont: 同じ形式なら続けて鳴らす */
    private fun advance(cont: Boolean): Boolean {
        synchronized(lock) {
            val q = queue
            val n = when {
                repeatMode == 2 -> index
                index + 1 < q.size -> index + 1
                repeatMode == 1 && q.isNotEmpty() -> 0
                else -> return false
            }
            index = n
            reopen = true
            seekTo = 0
            continuous = cont
        }
        changed()
        return true
    }

    private fun formatOf(name: String, d: com.uyatame.ucrt.player.PcmDecoder, c: Converter): String {
        val ext = if (name.startsWith("cd:")) "CD" else name.substringAfterLast('.', "").uppercase().let { if (it == "M4A" || it == "MP4") "M4A" else it }
        fun khz(r: Int) = if (r % 1000 == 0) "${r / 1000}kHz" else String.format(java.util.Locale.US, "%.1fkHz", r / 1000.0)
        val src = ext + " " + khz(d.sampleRate) + (if (d.bitsPerSample > 0 && ext != "MP3" && ext != "OGG" && ext != "AAC") "/${d.bitsPerSample}bit" else "")
        val dac = c.dac
        if (dac != null) {
            val out = khz(dac.rate) + "/${dac.bits}bit"
            return src + " → USB DAC " + out
        }
        // float(Android 5.0 から)なら 24bit もそのまま。周波数を変えたときだけ書く
        if (trackFloat) return if (c.outRate != d.sampleRate) src + " → " + khz(c.outRate) + "/32bit float" else src
        return if (c.outRate != d.sampleRate || d.bitsPerSample > 16) src + " → " + khz(c.outRate) + "/16bit" else src
    }

    /** DSD のまま送るとき: いつも新しく始める(形式が PCM と違うので、続けて鳴らさない) */
    private fun startUsbRaw(f: com.uyatame.ucrt.legacy.usb.DacFormat, fromMs: Long) {
        synchronized(lock) {
            track?.release()
            track = null
            if (usbSession >= 0) com.uyatame.ucrt.legacy.usb.UsbDac.end(usbSession)
            usbSession = com.uyatame.ucrt.legacy.usb.UsbDac.begin()
            usbFmt = f
            written = 0
            headBase = 0
            continuous = false
            outRate = f.rate
            startMs = fromMs
            pausedPos = fromMs
            com.uyatame.ucrt.legacy.usb.UsbDac.setPaused(usbSession, !isPlaying)
        }
    }

    /** 送った分を鳴らし終えるまで待つ(DSD の曲の終わり。次の曲で作り直す前に) */
    private fun drainUsb() {
        if (usbSession < 0) return
        val until = SystemClock.elapsedRealtime() + 3000
        while (SystemClock.elapsedRealtime() < until) {
            if (com.uyatame.ucrt.legacy.usb.UsbDac.played(usbSession) >= written || synchronized(lock) { reopen || quit || !isPlaying }) break
            Thread.sleep(20)
        }
    }

    private fun endUsb() {
        if (usbSession >= 0) com.uyatame.ucrt.legacy.usb.UsbDac.end(usbSession)
        usbSession = -1
        usbFmt = null
    }

    private fun startTrack(c: Converter, fromMs: Long) {
        val dac = c.dac
        if (dac != null) {
            synchronized(lock) {
                track?.release()
                track = null
                if (usbSession < 0 || !continuous || !dac.same(usbFmt)) {
                    usbSession = com.uyatame.ucrt.legacy.usb.UsbDac.begin()
                    written = 0
                    headBase = 0
                } else headBase = written
                usbFmt = dac
                continuous = false
                outRate = c.outRate
                startMs = fromMs
                pausedPos = fromMs
                com.uyatame.ucrt.legacy.usb.UsbDac.setPaused(usbSession, !isPlaying)
            }
            return
        }
        synchronized(lock) { endUsb() }
        synchronized(lock) {
            var t = track
            if (t == null || !continuous || t.sampleRate != c.outRate || t.channelCount != c.outChannels) {
                t?.release()
                val cfg = if (c.outChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                // Android 5.0 からは float で渡す(24bit の曲を 16bit に丸めない)。作れなければ 16bit
                val ft = if (Build.VERSION.SDK_INT >= 21) com.uyatame.ucrt.legacy.compat.Api21Track.create(c.outRate, cfg) else null
                if (ft != null) t = ft else {
                    val min = AudioTrack.getMinBufferSize(c.outRate, cfg, AudioFormat.ENCODING_PCM_16BIT)
                    val size = maxOf(min * 2, c.outRate / 4 * c.outChannels * 2)
                    @Suppress("DEPRECATION")
                    t = AudioTrack(AudioManager.STREAM_MUSIC, c.outRate, cfg, AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM)
                }
                trackFloat = ft != null
                track = t
                written = 0
                headBase = 0
            } else {
                headBase = written
            }
            continuous = false
            outRate = c.outRate
            startMs = fromMs
            pausedPos = fromMs
            if (isPlaying) t.play()
        }
    }

    /** 今の AudioTrack が float か */
    @Volatile private var trackFloat = false
    private var fout = FloatArray(0)

    /** float の AudioTrack に書く(音は -1〜1 に収める) */
    private fun writeFloat(c: Converter, frames: Int) {
        val t = synchronized(lock) { track } ?: return
        val len = frames * c.outChannels
        if (fout.size < len) fout = FloatArray(len)
        val src = c.resampled
        for (i in 0 until len) {
            val v = src[i]
            fout[i] = if (v > 1f) 1f else if (v < -1f) -1f else v
        }
        var off = 0
        while (off < len) {
            val r = com.uyatame.ucrt.legacy.compat.Api21Track.write(t, fout, off, len - off)
            if (r <= 0) break
            off += r
            synchronized(lock) {
                written += r / maxOf(t.channelCount, 1)
                if (reopen || quit || !isPlaying || remote) return
            }
        }
    }

    private fun write(buf: ShortArray, len: Int) {
        val t = synchronized(lock) { track } ?: return
        var off = 0
        while (off < len) {
            val r = t.write(buf, off, len - off)
            if (r <= 0) break
            off += r
            synchronized(lock) {
                written += r / maxOf(t.channelCount, 1)
                if (reopen || quit || !isPlaying || remote) return
            }
        }
    }

    /** USB DAC の形式(整数・リトルエンディアン)にして送る。音量はハードウェアで変えられなければここで掛ける */
    private fun writeUsb(c: Converter, frames: Int) {
        val f = c.dac ?: return
        val id = synchronized(lock) { usbSession }
        if (id < 0 || frames <= 0) return
        val src = c.resampled
        val inCh = c.outChannels
        val fb = f.frameBytes
        val need = frames * fb
        if (usbBytes.size < need) usbBytes = ByteArray(need)
        val b = usbBytes
        val gain = com.uyatame.ucrt.legacy.usb.UsbDac.softGain
        val full = (1L shl (f.bits - 1)).toDouble()
        val max = (1L shl (f.bits - 1)) - 1
        val shift = f.subslot * 8 - f.bits
        var o = 0
        for (i in 0 until frames) {
            for (ch in 0 until f.channels) {
                val sc = if (ch < inCh) ch else inCh - 1
                val v = if (ch < 2 || inCh > 2) src[i * inCh + sc].toDouble() * gain else 0.0
                var q = Math.round(v * full)
                if (q > max) q = max else if (q < -max - 1) q = -max - 1
                val w = q shl shift
                for (k in 0 until f.subslot) { b[o++] = (w shr (8 * k)).toByte() }
            }
        }
        val ok = com.uyatame.ucrt.legacy.usb.UsbDac.write(id, b, need) { synchronized(lock) { reopen || quit || !isPlaying || remote } }
        if (ok) synchronized(lock) { written += frames }
    }

    /**
     * デコーダーの出力(右詰めの整数)を、AudioTrack に書く 16 ビットにする。
     * 3 チャンネル以上のステレオへのまとめ(mixed に float で入れる)→ 音の加工 → 周波数の変換 → ディザ
     */
    private class Converter(private val inRate: Int, private val inCh: Int, private val bits: Int, darRate: Int, dacRate: Int = 0) {
        val outChannels = if (inCh == 1) 1 else 2
        /** USB DAC に直接出すときの形式(null なら AudioTrack) */
        var dac: com.uyatame.ucrt.legacy.usb.DacFormat? = null
        /** resample のあとの音(float・チャンネル交互) */
        var resampled = FloatArray(0); private set
        val outRate: Int
        private val resampler: RationalResampler?
        /** DAR の整数倍のアップサンプリング */
        private val upsampler: com.uyatame.ucrt.player.Upsampler?
        private var up = FloatArray(0)
        private val scale = 1f / (1L shl (bits - 1)).toFloat()
        var mixed = FloatArray(0); private set
        private var res = FloatArray(0)
        var out = ShortArray(0); private set
        var skipBefore = 0L
        private var rnd = 0x12345678

        init {
            val max = if (dacRate > 0) Int.MAX_VALUE else SoundDsp.trackMax
            var r = if (dacRate > 0) dacRate else inRate
            if (r > max) {
                while (r > max && r % 2 == 0) r /= 2
                if (r > max) r = if (inRate % 44100 == 0) 44100 else 48000
            }
            var u: com.uyatame.ucrt.player.Upsampler? = null
            if (dacRate > inRate && dacRate % inRate == 0) {
                u = com.uyatame.ucrt.player.Upsampler(dacRate / inRate, outChannels)
            } else if (r == inRate && darRate > inRate) {
                if (darRate % inRate == 0) { u = com.uyatame.ucrt.player.Upsampler(darRate / inRate, outChannels); r = darRate } else r = darRate
            }
            outRate = r
            upsampler = u
            resampler = if (r != inRate && u == null) RationalResampler(inRate, r, outChannels) else null
        }

        /** mixed に入れて、フレームの数を返す(end のサンプルより後ろは捨てる) */
        fun convert(d: com.uyatame.ucrt.player.PcmDecoder, end: Long): Int {
            var first = 0
            if (d.frameSample < skipBefore) first = minOf(d.blockSize.toLong(), skipBefore - d.frameSample).toInt()
            var last = d.blockSize
            if (d.frameSample + last > end) last = (end - d.frameSample).toInt().coerceAtLeast(first)
            val n = last - first
            if (n <= 0) return 0
            if (mixed.size < n * outChannels) mixed = FloatArray(n * outChannels)
            val s = d.samples
            if (inCh == 1) {
                for (i in 0 until n) mixed[i] = s[0][first + i] * scale
            } else if (inCh == 2) {
                for (i in 0 until n) {
                    mixed[2 * i] = s[0][first + i] * scale
                    mixed[2 * i + 1] = s[1][first + i] * scale
                }
            } else {
                for (i in 0 until n) {
                    val c = if (inCh >= 3) s[2][first + i] * scale * 0.7071f else 0f
                    var l = s[0][first + i] * scale + c
                    var r = s[1][first + i] * scale + c
                    var k = 4
                    while (k + 1 < inCh) {
                        l += s[k][first + i] * scale * 0.7071f
                        r += s[k + 1][first + i] * scale * 0.7071f
                        k += 2
                    }
                    mixed[2 * i] = l * 0.5f
                    mixed[2 * i + 1] = r * 0.5f
                }
            }
            return n
        }

        /** mixed(n フレーム)を周波数の変換だけして resampled に入れ、フレームの数を返す(USB DAC) */
        fun resample(n: Int): Int {
            val us = upsampler
            if (us != null) {
                val f = outRate / inRate
                if (up.size < n * f * outChannels) up = FloatArray(n * f * outChannels)
                us.process(mixed, n, up)
                resampled = up
                return n * f
            }
            val rs = resampler
            if (rs != null) {
                val need = rs.maxOut(n) * outChannels
                if (res.size < need) res = FloatArray(need)
                val k = rs.process(mixed, n, res)
                resampled = res
                return k
            }
            resampled = mixed
            return n
        }

        /** mixed(n フレーム)を周波数の変換とディザで out にし、フレームの数を返す */
        fun finish(n: Int): Int {
            var src = mixed
            var frames = n
            val us = upsampler
            if (us != null) {
                val f = outRate / inRate
                if (up.size < n * f * outChannels) up = FloatArray(n * f * outChannels)
                us.process(mixed, n, up)
                src = up
                frames = n * f
            }
            val rs = resampler
            if (rs != null) {
                val need = rs.maxOut(n) * outChannels
                if (res.size < need) res = FloatArray(need)
                frames = rs.process(mixed, n, res)
                src = res
            }
            val total = frames * outChannels
            if (out.size < total) out = ShortArray(total)
            val dither = bits > 16 || rs != null || us != null || inCh > 2
            for (i in 0 until total) {
                var v = src[i] * 32767f
                if (dither) {
                    rnd = rnd * 1664525 + 1013904223
                    val a = (rnd ushr 16) / 65536f
                    rnd = rnd * 1664525 + 1013904223
                    val b = (rnd ushr 16) / 65536f
                    v += a - b
                }
                val q = Math.round(v)
                out[i] = (if (q > 32767) 32767 else if (q < -32768) -32768 else q).toShort()
            }
            return frames
        }
    }
}
