package com.uyatame.ucrt.aging

import android.content.Context
import android.media.AudioAttributes
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.player.AudioEngine
import com.uyatame.ucrt.player.AudioOutput
import com.uyatame.ucrt.player.PcmOut
import com.uyatame.ucrt.player.PlayItem
import com.uyatame.ucrt.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.media.AudioFormat as AFormat

/**
 * エージング(イヤホン・ヘッドホンの慣らし)を進める。
 * - 作った音(UCRT のエージング音・ノイズ・スイープ)は、再生とは別の出力で途切れなく鳴らす(1 曲リピートは繰り返すたびに出力を作り直すので使わない)
 * - ライブラリの曲は、いつもの再生で流す。その間は再生履歴・再生回数・前回の続きに残さず、終わったら元の再生リストと設定に戻す
 * - 時間は実際に鳴った分だけ数え(AgingClock)、保存しておく(アプリが止められても、続きから始められる)
 */
object Aging {
    var active by mutableStateOf(false)
        private set
    var source by mutableStateOf(AgingSource.UCRT)
        private set
    /** 使う人が一時停止したか */
    var paused by mutableStateOf(false)
        private set
    var resting by mutableStateOf(false)
        private set
    /** 終わった(画面に「終わりました」を出す) */
    var finished by mutableStateOf(false)
    var playedMs by mutableLongStateOf(0L)
        private set
    var targetMs by mutableLongStateOf(0L)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var untilRestMs by mutableLongStateOf(-1L)
        private set
    var restLeftMs by mutableLongStateOf(0L)
        private set
    var remainingWallMs by mutableLongStateOf(0L)
        private set
    /** 出力の周波数(作った音のとき。ライブラリの曲なら 0) */
    var rate by mutableStateOf(0)
        private set
    /** 出力の説明 */
    var outputLabel by mutableStateOf("")
        private set
    /** 今鳴らしている部分(UCRT のエージング音の部分・曲名など) */
    var detail by mutableStateOf("")
        private set
    /** 保存してある続き(進み具合 0〜1。無ければ null) */
    var saved by mutableStateOf<Float?>(null)
        private set

    /** ライブラリの曲を、いつもの再生で流しているか(再生履歴などに残さないための目印) */
    val usesPlayer: Boolean get() = active && !source.generated

    /** 作った音のスペクトラム(ハイレゾ帯域まで)。ライブラリの曲のときは null */
    @Volatile var analyzer: AgingAnalyzer? = null
        private set
    /** 作った音の波形(左右 256 点ずつ。画面の表示用) */
    val waveL = FloatArray(256)
    val waveR = FloatArray(256)

    /** 状態が変わったとき(通知を出し直す) */
    @Volatile var onChanged: (() -> Unit)? = null

    private var clock: AgingClock? = null
    private var gen: GenThread? = null
    private var job: Job? = null
    private var player: PlayerController? = null
    private var restore: SavedQueue? = null
    private var wake: PowerManager.WakeLock? = null
    private var appCtx: Context? = null
    /** ライブラリの曲(保存して、続きのときに同じ曲を流す) */
    private var uris: List<String> = emptyList()
    /** エージングで流している曲の番号(使う人がほかの曲を流し始めたかを見分ける) */
    private var agingIds: Set<Long> = emptySet()

    private class SavedQueue(
        val items: List<PlayItem>, val index: Int, val posMs: Long, val shuffle: Boolean, val repeat: Int, val original: List<PlayItem>,
    )
    /** 始める前のリピート・シャッフル(元の再生リストが無いときも、これは戻す) */
    private var savedModes: Pair<Int, Boolean>? = null
    /** 終えたあとも、止め終えるまでは出力を使っているスレッド(その間は再生に出力を作らせない) */
    @Volatile private var lastGen: GenThread? = null
    private var noisy: android.content.BroadcastReceiver? = null

    private const val PREFS = "aging"
    private const val KEY = "session"

    /** 保存してある続きを読む(画面を開いたとき) */
    fun loadSaved(ctx: Context) {
        if (active) return
        val o = readSaved(ctx)
        saved = o?.optJSONObject("clock")?.let { AgingClock.fromJson(it) }?.takeIf { !it.done }?.progress
    }

    private fun readSaved(ctx: Context): JSONObject? = runCatching {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { JSONObject(it) }
    }.getOrNull()

    fun discardSaved(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        saved = null
    }

    private fun persist(ctx: Context) {
        val c = clock ?: return
        val o = JSONObject().put("source", source.id).put("clock", c.toJson()).put("uris", JSONArray(uris))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    /**
     * 始める。items はライブラリの曲(作った音なら null)。始められなければ false
     */
    fun start(
        ctx: Context, scope: CoroutineScope, p: PlayerController, src: AgingSource, hours: Int, interval: Boolean,
        items: List<PlayItem>?, resumeClock: AgingClock? = null,
    ): Boolean {
        if (active) return false
        if (!src.generated && items.isNullOrEmpty()) return false
        val app = ctx.applicationContext
        appCtx = app
        player = p
        source = src
        clock = resumeClock ?: AgingClock.ofHours(hours, interval)
        finished = false
        paused = false
        rate = 0
        detail = ""
        outputLabel = ""
        // 作った音を鳴らしている間と、終えたあと出力を止め終えるまでは、再生に出力を作らせない
        p.externalBusy = { (active && source.generated) || lastGen?.isAlive == true }
        p.externalBusyNotice = { S(R.string.aging_player_busy) }
        active = true
        saved = null
        if (src.generated) {
            uris = emptyList()
            // 鳴っている曲を止めて、出力を手放してもらう(再生リストと位置は残る)
            p.yieldOutput()
            gen = GenThread(app, src, p).also { it.start() }
        } else {
            val list = items!!
            uris = list.mapNotNull { it.uri?.toString() }
            savedModes = p.repeatMode to p.shuffle
            // 元の再生リストを覚えておく(CD の曲は戻せないので覚えない)
            val q = p.queue
            restore = if (q.isNotEmpty() && q.none { it.uri == null }) {
                SavedQueue(q, p.index, p.positionMs, p.shuffle, p.repeatMode, p.originalOrder)
            } else null
            agingIds = list.map { it.id }.toHashSet()
            p.playFiles(list, 0)
            p.setModes(1, false)
        }
        // イヤホンを抜いたなど(端末のスピーカーに切り替わる): 作った音は一時停止する(ライブラリの曲は、再生のほうで止まる)
        if (src.generated && noisy == null) {
            noisy = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: android.content.Intent) {
                    // USB DAC を直接使っているときは、Android から DAC が消えたように見えるため無視する(再生と同じ)
                    if (com.uyatame.ucrt.player.usb.UsbDac.active || SystemClock.elapsedRealtime() < com.uyatame.ucrt.player.usb.UsbDac.ignoreNoisyUntil) return
                    if (active && !paused) togglePause()
                }
            }
            runCatching {
                app.registerReceiver(noisy, android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            }.onFailure { noisy = null }
        }
        // 休憩中(音が出ていない間)も、時間を進められるように
        runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ucrt:aging").apply { setReferenceCounted(false); acquire() }
        }
        applyClock()
        persist(app)
        job?.cancel()
        job = scope.launch { loop(app) }
        onChanged?.invoke()
        return true
    }

    /** 保存してある続きから始める(ライブラリの曲は、itemsOf で今のライブラリから探す) */
    fun resumeSaved(ctx: Context, scope: CoroutineScope, p: PlayerController, itemsOf: (List<String>) -> List<PlayItem>): Boolean {
        val o = readSaved(ctx) ?: return false
        val c = o.optJSONObject("clock")?.let { AgingClock.fromJson(it) } ?: return false
        val src = AgingSource.of(o.optString("source"))
        val list = o.optJSONArray("uris")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        val items = if (src.generated) null else itemsOf(list)
        return start(ctx, scope, p, src, 0, c.interval, items, c)
    }

    private suspend fun loop(ctx: Context) {
        var last = SystemClock.elapsedRealtime()
        var lastSave = last
        var lastNotify = 0L
        while (active) {
            delay(500)
            val c = clock ?: break
            val now = SystemClock.elapsedRealtime()
            val dt = now - last
            last = now
            val p = player
            val g = gen
            val sounding = !paused && if (source.generated) g?.sounding == true else p?.isPlaying == true
            val ev = c.advance(dt, sounding)
            for (e in ev) when (e) {
                AgingClock.Event.REST_START -> setAudio(false)
                AgingClock.Event.REST_END -> if (!paused) setAudio(true)
                AgingClock.Event.DONE -> {}
            }
            if (g != null && g.failed) {
                // 出力が止まった(USB DAC を抜いたなど): 一時停止にする
                g.failed = false
                paused = true
                setAudio(false)
                p?.onNotice?.invoke(S(R.string.aging_output_failed))
            }
            if (source.generated) {
                val sig = g?.signal
                detail = when (sig) {
                    is UcrtAgingSignal -> partName(sig.part)
                    is Sweep -> "%.0f Hz".format(java.util.Locale.ROOT, sig.currentHz)
                    else -> ""
                }
                if (g != null && g.rate > 0) {
                    rate = g.rate
                    // 作った音の周波数と、出力の実際の状態(USB DAC 直接出力なら、DAC に送っている形式)
                    outputLabel = AudioEngine.khz(g.rate) +
                        (AudioEngine.outputStatus.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                }
            } else {
                // 使う人がほかの曲を流し始めた・再生を止めた: エージングはそこで終える(その曲は止めず、元の再生リストにも戻さない)
                val cur = p?.current
                if (p != null && (cur == null || cur.id !in agingIds)) {
                    finish(ctx, completed = false, restorePlayer = false)
                    break
                }
                detail = cur?.title.orEmpty()
                outputLabel = AudioEngine.outputStatus
            }
            applyClock()
            if (c.done) {
                finish(ctx, completed = true)
                break
            }
            if (ev.isNotEmpty() || now - lastSave > 15_000) {
                persist(ctx)
                lastSave = now
            }
            if (ev.isNotEmpty() || now - lastNotify > 10_000) {
                onChanged?.invoke()
                lastNotify = now
            }
        }
    }

    private fun partName(p: UcrtAgingSignal.Part): String = when (p) {
        UcrtAgingSignal.Part.PINK -> S(R.string.aging_part_pink)
        UcrtAgingSignal.Part.SWEEP -> S(R.string.aging_part_sweep)
        UcrtAgingSignal.Part.BROWN -> S(R.string.aging_part_brown)
        UcrtAgingSignal.Part.WIDE -> S(R.string.aging_part_wide)
        UcrtAgingSignal.Part.QUIET -> S(R.string.aging_part_quiet)
    }

    private fun applyClock() {
        val c = clock ?: return
        playedMs = c.playedMs
        targetMs = c.targetMs
        progress = c.progress
        untilRestMs = c.untilRestMs
        restLeftMs = c.restLeftMs
        remainingWallMs = c.remainingWallMs()
        resting = c.resting
    }

    /** 音を出す・止める(休憩・一時停止) */
    private fun setAudio(on: Boolean) {
        if (source.generated) {
            val g = gen
            if (on && (g == null || !g.isAlive)) {
                // 出力が止まって終わっていたら、作り直す
                val c = appCtx ?: return
                val p = player ?: return
                gen = GenThread(c, source, p).also { it.start() }
            } else g?.audioPaused = !on
        } else player?.let { if (on) it.resume() else it.pause() }
    }

    fun togglePause() {
        if (!active) return
        paused = !paused
        // 休憩中は音を出さないまま(休憩が終わったときに、一時停止していなければ鳴らす)
        if (!resting) setAudio(!paused)
        onChanged?.invoke()
    }

    /**
     * アプリを終えるときに止める。保存した続きは残す(次に開いたとき、続きから始められる)。
     * ライブラリの曲を流していたときの元の再生リストは、前回の続きとして保存したものが残っている
     */
    fun shutdown(ctx: Context) {
        if (!active) return
        persist(ctx.applicationContext)
        stopGen()
        job?.cancel()
        job = null
        runCatching { wake?.release() }
        wake = null
        clock = null
        restore = null
        active = false
        resting = false
        paused = false
    }

    /** 使う人が終える(保存した続きも消す) */
    fun stop(ctx: Context) = finish(ctx.applicationContext, completed = false)

    private fun stopGen() {
        gen?.let { g ->
            g.stopFlag = true
            g.interrupt()
            lastGen = g
        }
        gen = null
        analyzer = null
        noisy?.let { r -> runCatching { appCtx?.unregisterReceiver(r) } }
        noisy = null
    }

    private fun finish(ctx: Context, completed: Boolean, restorePlayer: Boolean = true) {
        if (!active) return
        stopGen()
        val p = player
        if (p != null && !source.generated && restorePlayer) {
            // 元の再生リストと設定に戻す(鳴らさない)
            p.stop()
            val r = restore
            if (r != null) p.restore(r.items, r.index, r.posMs, r.shuffle, r.repeat, r.original)
            else savedModes?.let { (rep, sh) -> p.restoreModes(rep, sh) }
        }
        restore = null
        savedModes = null
        agingIds = emptySet()
        job?.cancel()
        job = null
        runCatching { wake?.release() }
        wake = null
        discardSaved(ctx)
        clock = null
        uris = emptyList()
        active = false
        resting = false
        paused = false
        finished = completed
        onChanged?.invoke()
    }

    /** 波形を写す(作った音の、書き込んだ塊の頭から) */
    private fun captureWave(f: FloatArray, frames: Int) {
        val n = waveL.size
        if (frames < n) return
        val step = (frames / n).coerceAtLeast(1).coerceAtMost(4)
        for (i in 0 until n) {
            waveL[i] = f[2 * i * step]
            waveR[i] = f[2 * i * step + 1]
        }
    }

    /** 作った音を鳴らすスレッド */
    private class GenThread(val ctx: Context, val src: AgingSource, val player: PlayerController) : Thread("aging-signal") {
        @Volatile var stopFlag = false
        @Volatile var audioPaused = false
        @Volatile var sounding = false
        @Volatile var failed = false
        @Volatile var rate = 0
        @Volatile var signal: AgingSignal? = null

        /**
         * 試す周波数(高い順)。USB DAC 直接出力は、DAC が対応しているものだけ(対応していない周波数を頼むと、
         * DAC を Android に返して通常の出力になるため)。通常の出力は、出力先が申告している周波数から。
         * 分からなければ 48 kHz(多くの出力先がそのまま受け付ける)
         */
        private fun rates(): List<Int> {
            val pref = listOf(192000, 176400, 96000, 88200, 48000, 44100)
            if (AudioEngine.wantUsbDirect) {
                val ok = pref.filter { com.uyatame.ucrt.player.usb.UsbDac.canPlay(it, 2) == true }
                return ok.ifEmpty { listOf(48000) }
            }
            val dev = com.uyatame.ucrt.player.OutputDevices.route(ctx, AudioEngine.preferredKey)
            val listed = dev?.rates?.filter { it in 44100..192000 }?.distinct()?.sortedDescending().orEmpty()
            return (listed + 48000).distinct()
        }

        override fun run() {
            // 再生の出力が手放されるのと、前に鳴らしていたスレッドが止まるのを待つ(USB DAC を取り合わないように)
            player.awaitOutputReleased()
            lastGen?.takeIf { it !== this }?.let { runCatching { it.join(2000) } }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            // 出力先が受け付ける、いちばん高い周波数(192 kHz まで)で鳴らす(ハイレゾ帯域まで出せるように)。
            // イコライザーは掛けない(音量は掛ける)
            var out: PcmOut? = null
            for (r in rates()) {
                if (stopFlag) return
                out = runCatching { AudioOutput(ctx, attrs, r, 2, 32, noEq = true) }.getOrNull()
                if (out != null) { rate = r; break }
            }
            val o = out ?: run { failed = true; return }
            val sig = signalFor(src, rate) ?: run { o.release(); return }
            signal = sig
            val an = AgingAnalyzer(rate)
            analyzer = an
            // 20 ミリ秒ずつ作って書く
            val frames = rate / 50
            val f = FloatArray(frames * 2)
            val bb = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
            var playing = false
            try {
                while (!stopFlag) {
                    if (audioPaused) {
                        if (playing) { o.pause(); playing = false }
                        sounding = false
                        try { sleep(50) } catch (_: InterruptedException) {}
                        continue
                    }
                    // 出力が止まった(USB DAC を抜いたなど): このスレッドは終える(再開すると、新しく出力を作る)
                    if (o.failed) { failed = true; break }
                    if (!playing) { o.play(); playing = true }
                    sig.fill(f, frames)
                    an.push(f, frames)
                    captureWave(f, frames)
                    bb.clear()
                    bb.asFloatBuffer().put(f, 0, frames * 2)
                    bb.position(0)
                    bb.limit(frames * 8)
                    o.write(bb, AFormat.ENCODING_PCM_FLOAT) { stopFlag || audioPaused }
                    sounding = !audioPaused && !stopFlag
                }
            } catch (e: Exception) {
                AudioEngine.log("aging: ${e.javaClass.simpleName}: ${e.message}")
                failed = true
            } finally {
                sounding = false
                runCatching { o.pause() }
                runCatching { o.release() }
            }
        }
    }
}
