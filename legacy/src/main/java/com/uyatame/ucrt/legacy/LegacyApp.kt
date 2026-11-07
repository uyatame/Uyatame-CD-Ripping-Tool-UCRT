package com.uyatame.ucrt.legacy

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.uyatame.ucrt.Strings
import com.uyatame.ucrt.legacy.ui.Ui

/**
 * UCRT Legacy のアプリ。設定・ライブラリ・お気に入りなど・再生エンジンはアプリに 1 つだけ持つ
 * (画面を閉じても鳴り続けるように)
 */
class LegacyApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var store: Store; private set
    lateinit var lib: Lib; private set
    lateinit var player: LegacyPlayer; private set
    lateinit var dsp: SoundDsp; private set
    private val main = Handler(Looper.getMainLooper())
    private var resumeReady = false

    override fun onCreate() {
        super.onCreate()
        app = this
        CrashLog.install(this)
        Strings.init(this)
        Ui.init(this)
        Art.init(this)
        com.uyatame.ucrt.legacy.compat.Tls.install(this)
        prefs = Prefs(this)
        store = Store(this)
        lib = Lib(this, prefs)
        player = LegacyPlayer(this)
        dsp = SoundDsp(this)
        player.dsp = dsp
        Disc.init(this)
        UsbAudio.init(this)
        // ロック画面(画面の点灯・消灯を、アプリが動いている間ずっと受け取る)
        LockGuard.register(this)
        applySound()
        Thread({ com.uyatame.ucrt.player.RgStore.load(this) }, "ucrt-rg").start()
        player.onTrackStart = { item -> store.recordPlay(item.uri) }
        Thread({ store.load() }, "ucrt-store").start()
        lib.start()
        // 再生の状態が変わったら、前回の状態として保存する(続けて変わったときは 1 回にまとめる)
        Bus.on(Bus.PLAYER) {
            // リピート・シャッフルは、変わったらすぐ保存する(続きの保存は、続けて変わったときに 1 回にまとめる)
            saveModes()
            if (restored) { main.removeCallbacks(saveResume); main.postDelayed(saveResume, 1500) }
            PlaybackService.update(this, player)
            MediaControls.update(this)
        }
        Bus.on(Bus.SETTINGS) { applySound() }
        Bus.on(Bus.RIP) { Rip.notify(this, Disc.busy) }
        // ライブラリを読み込んだら、前回の再生リストを戻す(1 回だけ)
        Bus.on(Bus.LIBRARY) { if (!resumeReady && lib.loaded) restore() }
    }

    private var darSig = ""

    /** 音の設定を再生エンジンに反映する */
    private fun applySound() {
        val p = prefs
        player.gapless = p.gapless
        dsp.apply(p)
        player.onQueueEnd = { ListQueue.onQueueEnd() }
        player.onSkipPastEnd = { ListQueue.skipToNextList() }
        player.onSkipBeforeStart = { ListQueue.skipToPrevList() }
        player.swipePrevToTrack = prefs.sp.getInt("swipePrevMode", 0) == 1
        // (DSD への変換は Legacy には無いので、前に選んでいてもオフ)
        val dar = if (p.sourceDirect) 0 else p.sp.getInt("darMode", 0).let { if (it == 1) 1 else 0 }
        val pcm = p.sp.getInt("darPcm", 0)
        val integer = p.sp.getBoolean("darInteger", true)
        player.darMode = dar
        player.darPcm = pcm
        player.darInteger = integer
        player.dsdMode = p.dsdMode
        player.dsdSwap = p.sp.getBoolean("dsdSwap", false)
        // DAR を変えたら、今の曲を同じ位置から開き直す
        val sig = "$dar/$pcm/$integer"
        if (darSig.isNotEmpty() && sig != darSig && player.current != null) player.seek(player.positionMs)
        darSig = sig
    }

    private val saveResume = Runnable { writeResume() }

    /** 最後に保存したリピート・シャッフル */
    private var savedModes: Pair<Int, Boolean>? = null
    /** 前回の再生リストとリピート・シャッフルを戻し終えたか(戻す前に、空の再生リストや初めの値で上書きしないように) */
    private var restored = false

    /** リピート・シャッフルを、変わったときだけすぐ保存する(前回の値を戻し終える前は、初めの値で上書きしないよう保存しない) */
    private fun saveModes() {
        if (!restored) return
        val m = player.repeatMode to player.shuffle
        if (m == savedModes) return
        savedModes = m
        store.saveModes(m.first, m.second)
    }

    fun writeResume() {
        // 前回の続きを戻し終える前は保存しない(画面を離れたときに、まだ空の再生リストで続きを消してしまわないように)
        if (!restored) return
        saveModes()
        val q = player.queue
        if (q.isEmpty()) { store.saveResume(null); return }
        store.saveResume(
            ResumeState(q.map { it.uri }, player.index, player.positionMs, player.shuffle, player.repeatMode, player.original.map { it.uri }),
        )
    }

    private fun restore() {
        // リピート・シャッフルは、続きとは別に保存した値を使う(続きが無い・読めないときも戻す)
        val modes = store.loadModes()
        savedModes = modes
        resumeReady = true
        if (player.current != null) { restored = true; return }
        Thread({
            val r = store.loadResume()
            main.post {
                try {
                    restoreQueue(r, modes)
                } finally {
                    restored = true
                }
            }
        }, "ucrt-resume").start()
    }

    /** 前回の再生リストと、リピート・シャッフルを戻す(メインのスレッドで) */
    /** 前回の再生リストと、リピート・シャッフルを戻す(メインのスレッドで) */
    private fun restoreQueue(r: ResumeState?, modes: Pair<Int, Boolean>?) {
        if (player.current != null) return
        if (r == null) {
            modes?.let { (rep, sh) -> player.restoreModes(rep, sh) }
            return
        }
        val map = lib.trackMap
        val items = ArrayList<QItem>()
        var idx = 0
        r.uris.forEachIndexed { i, u ->
            val t = map[u] ?: return@forEachIndexed
            if (i == r.index) idx = items.size
            items.add(QItem(t))
        }
        if (items.isEmpty()) {
            // 続きの曲がライブラリから無くなっていても、リピート・シャッフルは戻す
            modes?.let { (rep, sh) -> player.restoreModes(rep, sh) }
            return
        }
        val pool = HashMap<String, ArrayDeque<QItem>>()
        items.forEach { pool.getOrPut(it.uri) { ArrayDeque() }.addLast(it) }
        val orig = r.original.mapNotNull { u -> pool[u]?.removeFirstOrNull() }
        val sameTrack = map.containsKey(r.uris.getOrNull(r.index))
        // 並び順は続きを保存したときのシャッフルの状態で戻し、そのあとで別に保存したリピート・シャッフルに合わせる
        player.restore(items, idx, if (sameTrack) r.positionMs else 0L, r.shuffle, modes?.first ?: r.repeat, if (orig.size == items.size) orig else items)
        if (modes != null && modes.second != player.shuffle) player.toggleShuffle()
    }

    companion object {
        lateinit var app: LegacyApp
            private set
    }
}
