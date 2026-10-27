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
        applySound()
        Thread({ com.uyatame.ucrt.player.RgStore.load(this) }, "ucrt-rg").start()
        player.onTrackStart = { item -> store.recordPlay(item.uri) }
        Thread({ store.load() }, "ucrt-store").start()
        lib.start()
        // 再生の状態が変わったら、前回の状態として保存する(続けて変わったときは 1 回にまとめる)
        Bus.on(Bus.PLAYER) {
            if (resumeReady) { main.removeCallbacks(saveResume); main.postDelayed(saveResume, 1500) }
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
        val dar = if (p.sourceDirect) 0 else p.sp.getInt("darMode", 0)
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

    fun writeResume() {
        val q = player.queue
        if (q.isEmpty()) { store.saveResume(null); return }
        store.saveResume(
            ResumeState(q.map { it.uri }, player.index, player.positionMs, player.shuffle, player.repeatMode, player.original.map { it.uri }),
        )
    }

    private fun restore() {
        resumeReady = true
        if (player.current != null) return
        Thread({
            val r = store.loadResume() ?: return@Thread
            main.post {
                if (player.current != null) return@post
                val map = lib.trackMap
                val items = ArrayList<QItem>()
                var idx = 0
                r.uris.forEachIndexed { i, u ->
                    val t = map[u] ?: return@forEachIndexed
                    if (i == r.index) idx = items.size
                    items.add(QItem(t))
                }
                if (items.isEmpty()) return@post
                val pool = HashMap<String, ArrayDeque<QItem>>()
                items.forEach { pool.getOrPut(it.uri) { ArrayDeque() }.addLast(it) }
                val orig = r.original.mapNotNull { u -> pool[u]?.removeFirstOrNull() }
                val sameTrack = map.containsKey(r.uris.getOrNull(r.index))
                player.restore(items, idx, if (sameTrack) r.positionMs else 0L, r.shuffle, r.repeat, if (orig.size == items.size) orig else items)
            }
        }, "ucrt-resume").start()
    }

    companion object {
        lateinit var app: LegacyApp
            private set
    }
}
