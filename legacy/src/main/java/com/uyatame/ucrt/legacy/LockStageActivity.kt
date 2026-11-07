package com.uyatame.ucrt.legacy

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.ui.ArtView
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.MATCH
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.bold
import com.uyatame.ucrt.legacy.ui.marquee
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.setIcon
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import kotlin.math.abs

/**
 * ロック画面(本家の LockScreenActivity と同じ役目。View で作った軽い版)。システムのロックの上に出し、
 * 再生画面と同じ見た目(アンビエントの背景・音質・出力先・スペクトラム・再生位置)で、時計と、再生位置のバー・前へ・再生/一時停止・次へを出す。
 * ロック画面で使えないもの(お気に入り・リピートなど)は、並びがずれないよう、場所だけ空けて出さない。
 * 設定の向きにスワイプすると、システムのロックの解除へ進む
 * (PIN・指紋などがあれば、システムが求める。UCRT は認証しない)
 */
class LockStageActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var unsub: (() -> Unit)? = null
    private var present: BroadcastReceiver? = null
    private lateinit var root: FrameLayout
    private val ambient = LegacyApp.app.prefs.ambientPlayer
    private val col = PlayerColors.of(ambient)
    private lateinit var art: ArtView
    private lateinit var bgArt: android.widget.ImageView
    private lateinit var tintView: View
    private lateinit var dimView: View
    private lateinit var quality: TextView
    private lateinit var hiRes: TextView
    private lateinit var chips: LinearLayout
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private lateinit var clock: TextView
    private lateinit var seek: com.uyatame.ucrt.legacy.ui.SliderView
    private lateinit var posText: TextView
    private lateinit var leftText: TextView
    private lateinit var play: android.widget.ImageView
    private var seeking: Float? = null
    private var playSize = 80
    private var dismissing = false
    private var shownUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = LegacyApp.app
        if (app.player.current == null && LockGuard.enabled) { finish(); return }
        // システムのロックの上に出す(画面は点けない。画面が消えたときに先に出しておくため)
        if (Build.VERSION.SDK_INT >= 27) com.uyatame.ucrt.legacy.compat.Api27.showWhenLocked(this)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        // 全画面(状態バー・ナビゲーションバーを隠す)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= 19) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or 0x00001000 // IMMERSIVE_STICKY
        }
        LockGuard.showing = true
        LockGuard.cancelNotification(this)
        // 画面の向き(設定): 0 = 端末の設定、1 = 縦、2 = 横、3 = 端末の向きに合わせて回す
        requestedOrientation = when (app.prefs.sp.getInt("lockOrientation", 0)) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            3 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        build()
        setContentView(root)
        unsub = Bus.on(Bus.PLAYER) { refresh() }
        // 指紋など、ほかの方法でシステムのロックが解けたら閉じる
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { LockGuard.showing = false; finish() }
        }
        present = r
        runCatching { com.uyatame.ucrt.legacy.compat.Receivers.register(this, r, IntentFilter(Intent.ACTION_USER_PRESENT)) }
        refresh()
        tick.run()
        AppLog.log("lock screen: opened")
    }

    private val tick = object : Runnable {
        override fun run() {
            clock.text = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
            main.postDelayed(this, 15_000)
        }
    }

    /** 再生位置(0.25 秒ごと。前に出ている間だけ) */
    private val posTick = object : Runnable {
        override fun run() {
            updatePos()
            main.postDelayed(this, 250)
        }
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(posTick)
        posTick.run()
    }

    override fun onPause() {
        main.removeCallbacks(posTick)
        super.onPause()
    }

    /** 再生画面(NowPlayingView の再生中のページ)と同じ並び。使えない部品は INVISIBLE で場所だけ空ける */
    private fun build() {
        root = FrameLayout(this)
        root.setBackgroundColor(if (ambient) Color.BLACK else com.uyatame.ucrt.legacy.ui.Pal.surface)
        // ---- 背景(アンビエント: ジャケットを敷いて、その色で染める。一時停止中は暗く) ----
        bgArt = android.widget.ImageView(this)
        bgArt.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        bgArt.alpha = 0.85f
        if (ambient) root.add(bgArt, h = MATCH)
        tintView = View(this)
        root.add(tintView, h = MATCH)
        dimView = View(this)
        dimView.setBackgroundColor(Color.BLACK)
        dimView.alpha = 0f
        if (ambient) root.add(dimView, h = MATCH)

        val dm = resources.displayMetrics
        val small = dm.heightPixels / com.uyatame.ucrt.legacy.ui.Ui.density < 640 || dm.widthPixels / com.uyatame.ucrt.legacy.ui.Ui.density < 340
        // 左右に分けて並べるか(横向きか、タブレットモード。自動なら、画面の短い辺が 600dp 以上の端末)
        val shortDp = minOf(dm.widthPixels, dm.heightPixels) / com.uyatame.ucrt.legacy.ui.Ui.density
        val landscape = when (LegacyApp.app.prefs.sp.getInt("lockTablet", 0)) {
            1 -> true
            2 -> dm.widthPixels > dm.heightPixels
            else -> dm.widthPixels > dm.heightPixels || shortDp >= 600
        }
        playSize = if (small) 64 else 80
        val skipSize = if (small) 50 else 60
        // 時計の場所(設定): 0 = 上、1 = 下、2 = 出さない
        val clockPos = LegacyApp.app.prefs.sp.getInt("lockClock", 0).coerceIn(0, 2)
        // 時計の色(設定。0 なら再生画面の文字の色)
        val clockColor = LegacyApp.app.prefs.sp.getInt("lockClockColor", 0)
        clock = text(this, "", Type.HeadlineMedium, if (clockColor == 0) col.content else clockColor)
        if (!small) clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, com.uyatame.ucrt.legacy.ui.Ui.sp(40f))
        clock.gravity = Gravity.CENTER

        // ---- ジャケットと、その上の鍵のマーク ----
        art = ArtView(this, 12f)
        art.square = false
        val artPane = FrameLayout(this)
        artPane.add(art, h = MATCH)
        val lockMark = FrameLayout(this)
        lockMark.bgd(com.uyatame.ucrt.legacy.ui.rounded(0x73000000, 32f))
        lockMark.add(com.uyatame.ucrt.legacy.ui.icon(this, LOCK_ICON, Color.WHITE, 32), w = 32, h = 32, gravity = Gravity.CENTER)
        lockMark.contentDescription = S(R.string.lock_locked)
        artPane.add(lockMark, w = 64, h = 64, gravity = Gravity.CENTER)

        // ---- 曲の情報と操作 ----
        val ctl = vbox(this, Gravity.CENTER_HORIZONTAL)
        val ql = hbox(this, Gravity.CENTER)
        hiRes = com.uyatame.ucrt.legacy.ui.badge(this, "Hi-Res", true)
        ql.add(hiRes, w = WRAP, r = 8)
        quality = text(this, " ", Type.TitleSmall, col.accent)
        quality.typeface = Type.mediumFace
        ql.add(quality, w = WRAP)
        ctl.add(ql, w = WRAP)
        chips = hbox(this, Gravity.CENTER)
        ctl.add(chips, w = WRAP, t = if (small) 2 else 6)
        // 曲名(左右のお気に入り・歌詞のボタンの場所は空けておく)
        val tr = hbox(this)
        tr.add(View(this), w = 48, h = 48)
        val tcol = vbox(this, Gravity.CENTER_HORIZONTAL)
        title = text(this, "", if (small) Type.TitleLarge else Type.HeadlineSmall, col.content, lines = 2)
        title.gravity = Gravity.CENTER
        title.bold()
        artist = text(this, "", Type.TitleMedium, col.sub)
        artist.gravity = Gravity.CENTER
        album = text(this, "", Type.BodyMedium, col.sub)
        album.gravity = Gravity.CENTER
        title.marquee(); artist.marquee(); album.marquee()
        tcol.add(title); tcol.add(artist); tcol.add(album)
        tr.add(tcol, w = 0, weight = 1f)
        tr.add(View(this), w = 48, h = 48)
        ctl.add(tr, t = if (small) 4 else 10)
        // スペクトラムアナライザー(再生画面で出す設定のとき)
        val sp = LegacyApp.app.prefs.sp
        if (sp.getBoolean("spectrum", false) && !(small && landscape)) {
            val c = sp.getInt("spectrumColor", 0).let { if (it == 0) col.accent else it }
            addMeters(ctl, this, c, col.sub, small)
        }
        // 再生位置(動かせる)
        val pl = LegacyApp.app.player
        seek = com.uyatame.ucrt.legacy.ui.SliderView(this, 0f, { v -> seeking = v; updatePos() }, { v ->
            val d = pl.durationMs
            seeking = null
            if (d > 0) pl.seek((v * d).toLong())
            updatePos()
        })
        if (ambient) { seek.activeColor = Color.WHITE; seek.thumbColor = Color.WHITE; seek.inactiveColor = com.uyatame.ucrt.legacy.ui.Pal.alpha(Color.WHITE, 0.28f) }
        ctl.add(seek, t = if (small) 4 else 12)
        val times = hbox(this)
        posText = text(this, "0:00", Type.LabelMedium, col.sub)
        leftText = text(this, "-0:00", Type.LabelMedium, col.sub)
        times.add(posText, w = 0, weight = 1f)
        times.add(leftText, w = WRAP)
        ctl.add(times, l = 10, r = 10)
        // 再生操作(並びは再生画面の設定と同じ。リピートとシャッフルは場所だけ)
        val ops = hbox(this)
        val prev = com.uyatame.ucrt.legacy.ui.icon(this, Icons.SkipPrevious, col.content, (skipSize * 0.47f).toInt())
        prev.contentDescription = S(R.string.previous_track)
        prev.tap(16f, com.uyatame.ucrt.legacy.ui.rounded(0, 16f, col.content, 2f)) { pl.prev() }
        play = com.uyatame.ucrt.legacy.ui.icon(this, Icons.PlayArrow, col.content, (playSize * 0.52f).toInt())
        // 塗りの色を決めないと、Android 4.x では黒く塗られる
        val circ = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0); setStroke(dp(2.5f), col.content) }
        play.tap(oval = true, base = circ) { pl.toggle() }
        val next = com.uyatame.ucrt.legacy.ui.icon(this, Icons.SkipNext, col.content, (skipSize * 0.47f).toInt())
        next.contentDescription = S(R.string.next_track)
        next.tap(16f, com.uyatame.ucrt.legacy.ui.rounded(0, 16f, col.content, 2f)) { pl.next() }
        controlOrder(sp.getString("controlOrder", null)).forEachIndexed { i, c ->
            if (i > 0) ops.add(View(this), w = 0, h = 1, weight = 1f)
            when (c) {
                'P' -> ops.add(prev, w = skipSize, h = skipSize)
                'X' -> ops.add(play, w = playSize, h = playSize)
                'N' -> ops.add(next, w = skipSize, h = skipSize)
                else -> ops.add(View(this), w = 48, h = 48)
            }
        }
        ctl.add(ops, t = if (small) 2 else 10)
        // 下の帯の場所(時計を下に出す設定なら、そこに)
        if (clockPos == 1) ctl.add(clock, t = if (small) 4 else 10, b = if (small) 8 else 16)
        else ctl.add(View(this), h = 48, t = if (small) 0 else 6, b = if (small) 2 else 6)

        val top = if (clockPos == 0) clock else View(this)
        if (!landscape) {
            val v = vbox(this, Gravity.CENTER_HORIZONTAL)
            v.setPadding(dp(if (small) 16 else 24), dp(8), dp(if (small) 16 else 24), 0)
            if (clockPos == 0) v.add(top, t = if (small) 8 else 20) else v.add(top, h = 48)
            v.add(artPane, h = 0, weight = 1f, t = if (small) 6 else 14, b = if (small) 6 else 14)
            v.add(ctl)
            root.add(v, h = MATCH)
        } else {
            val h = hbox(this)
            h.setPadding(dp(16), 0, dp(16), 0)
            val left = vbox(this, Gravity.CENTER_HORIZONTAL)
            if (clockPos == 0) left.add(top, t = 8)
            left.add(artPane, h = 0, weight = 1f, t = 8, b = 8)
            h.add(left, w = 0, h = MATCH, weight = 0.42f)
            ctl.gravity = Gravity.CENTER
            h.add(ctl, w = 0, h = MATCH, weight = 0.58f, l = 20)
            root.add(h, h = MATCH)
        }
        // 解除のスワイプ(設定の向きへ、画面の 25% 以上)
        root.setOnTouchListener(SwipeToUnlock())
    }

    /** 出力先・EQ などの札(再生画面と同じ。ロック画面では見せるだけ) */
    private fun buildChips() {
        chips.removeAllViews()
        val pal = com.uyatame.ucrt.legacy.ui.Pal
        fun chip(label: String?, d: String?, selected: Boolean) {
            val c = hbox(this, Gravity.CENTER)
            c.minimumHeight = dp(32)
            c.setPadding(dp(if (label == null) 8 else 12), 0, dp(if (label == null) 8 else 16), 0)
            val fg = if (selected) (if (col.ambient) Color.BLACK else pal.onSecondaryContainer) else (if (col.ambient) col.sub else pal.onSurfaceVariant)
            if (d != null) c.add(com.uyatame.ucrt.legacy.ui.icon(this, d, if (col.ambient) (if (selected) Color.BLACK else col.sub) else pal.primary, 18), w = 18, h = 18, r = if (label != null) 8 else 0)
            if (label != null) c.add(text(this, label, Type.LabelLarge, fg), w = WRAP)
            c.bgd(
                if (selected) com.uyatame.ucrt.legacy.ui.rounded(if (col.ambient) col.accent else pal.secondaryContainer, 8f)
                else com.uyatame.ucrt.legacy.ui.rounded(0, 8f, if (col.ambient) col.sub else pal.outlineVariant, 1f),
            )
            chips.add(c, w = WRAP, r = 8)
        }
        val s = LegacyApp.app.prefs
        chip(Output.name(this), Output.icon(this), false)
        if (s.sourceDirect) chip(S(R.string.chip_source_direct), null, true)
        if (!s.sourceDirect && s.eqEnabled) chip(S(R.string.chip_eq, Sound.eqName()), null, true)
    }

    private fun updatePos() {
        val pl = LegacyApp.app.player
        val d = pl.durationMs.coerceAtLeast(1)
        val frac = seeking ?: (pl.positionMs.toFloat() / d).coerceIn(0f, 1f)
        if (!seek.dragging) seek.value = frac
        posText.text = fmtMs((frac * d).toLong())
        leftText.text = "-" + fmtMs(((1f - frac) * d).toLong().coerceAtLeast(0))
    }

    /** ジャケットと背景の色 */
    private fun loadArt(t: com.uyatame.ucrt.library.LibTrack) {
        val ref = Actions.artRef(this, t)
        val dm = resources.displayMetrics
        val px = maxOf(dm.widthPixels, dm.heightPixels)
        art.bind(ref, px)
        Art.load(this, ref, px) { b ->
            if (isFinishing) return@load
            if (ambient) bgArt.setImageBitmap(b)
            val tint = if (b == null) 0xFF2A2A2A.toInt() else runCatching { dominantColor(b) }.getOrDefault(0xFF2A2A2A.toInt())
            val pal = com.uyatame.ucrt.legacy.ui.Pal
            tintView.bgd(
                if (ambient) android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pal.alpha(tint, 0.55f), pal.alpha(tint, 0.35f), pal.alpha(Color.BLACK, 0.8f)))
                else android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pal.alpha(tint, 0.22f), 0)),
            )
        }
    }

    private companion object {
        /** 鍵のマーク(Material の Lock) */
        const val LOCK_ICON = "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z"
    }

    private inner class SwipeToUnlock : View.OnTouchListener {
        private var x0 = 0f
        private var y0 = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY }
                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - x0
                    val dy = e.rawY - y0
                    val dir = LegacyApp.app.prefs.sp.getInt("lockSwipe", 0).coerceIn(0, 3)
                    val h = v.height.toFloat().coerceAtLeast(1f)
                    val w = v.width.toFloat().coerceAtLeast(1f)
                    val ok = when (dir) {
                        0 -> -dy > h * 0.25f && abs(dy) > abs(dx)
                        1 -> dy > h * 0.25f && abs(dy) > abs(dx)
                        2 -> -dx > w * 0.25f && abs(dx) > abs(dy)
                        else -> dx > w * 0.25f && abs(dx) > abs(dy)
                    }
                    if (ok) unlock()
                }
            }
            return true
        }
    }

    private fun refresh() {
        val pl = LegacyApp.app.player
        val cur = pl.current
        if (cur == null) { finish(); return }
        // 音質(再生画面と同じ)
        hiRes.visibility = if (com.uyatame.ucrt.library.Quality.classOf(cur.track) == com.uyatame.ucrt.library.QualityClass.HiRes) View.VISIBLE else View.GONE
        quality.text = pl.formatText.ifEmpty { com.uyatame.ucrt.library.Quality.badge(cur.track) }
        buildChips()
        title.text = cur.title
        artist.text = cur.artist
        album.text = cur.album
        album.visibility = if (cur.album.isEmpty()) View.GONE else View.VISIBLE
        play.setIcon(if (pl.isPlaying) Icons.Pause else Icons.PlayArrow, col.content, (playSize * 0.52f).toInt())
        play.contentDescription = S(if (pl.isPlaying) R.string.pause else R.string.play)
        dimView.animate().alpha(if (pl.isPlaying) 0f else 0.4f).setDuration(500).start()
        if (shownUri != cur.uri) {
            shownUri = cur.uri
            loadArt(cur.track)
        }
        updatePos()
    }

    /** システムのロックを解除して閉じる(ロックが無ければ、そのまま閉じる) */
    private fun unlock() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.inKeyguardRestrictedInputMode()) { LockGuard.showing = false; finish(); return }
        if (Build.VERSION.SDK_INT >= 26) {
            dismissing = true
            // 端末によっては、システムが解除の結果を返さない。少し待っても返事が無ければ、この画面を閉じて、
            // システムのロック画面で解除してもらう(固まって見えないように。本家と同じ)
            val giveUp = Runnable {
                if (!dismissing) return@Runnable
                AppLog.log("lock screen: no answer to the unlock request, closing")
                dismissing = false
                LockGuard.showing = false
                finish()
            }
            val secure = com.uyatame.ucrt.legacy.compat.Api26.secure(km)
            if (secure) {
                // PIN などのロック: 解除を頼むと PIN の入力の画面が出る(普通のロック画面を挟まない)。入力の画面がこの画面の
                // 後ろに隠れないよう、少ししたらロックの上から外す(取り消されたら、また上に出す。本家と同じ)
                main.postDelayed({
                    if (dismissing && Build.VERSION.SDK_INT >= 27) {
                        AppLog.log("lock screen: stepping aside for the system PIN screen")
                        com.uyatame.ucrt.legacy.compat.Api27.showWhenLocked(this, false)
                    }
                }, 400)
            } else {
                main.postDelayed(giveUp, 2500)
            }
            com.uyatame.ucrt.legacy.compat.Api26.dismissKeyguard(this, km) { ok ->
                main.removeCallbacks(giveUp)
                dismissing = false
                AppLog.log("lock screen: unlock ${if (ok) "succeeded" else "not done"}")
                if (ok) { LockGuard.showing = false; finish() }
                else if (Build.VERSION.SDK_INT >= 27) com.uyatame.ucrt.legacy.compat.Api27.showWhenLocked(this, true)
            }
        } else {
            // Android 7 以前: ロックが「スワイプだけ」なら解ける。PIN などがあれば、システムが求める
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            LockGuard.showing = false
            finish()
        }
    }

    /** 向きが変わったら、並びを作り直す(向きの変化では Activity を作り直さない設定なので) */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isFinishing) return
        shownUri = null
        build()
        setContentView(root)
        refresh()
        main.removeCallbacks(tick)
        tick.run()
    }

    override fun onBackPressed() { /* 戻るボタンでは閉じない(閉じるのは解除のスワイプだけ) */ }

    override fun onStop() {
        super.onStop()
        // 画面が消えて止まったときは閉じない(次に画面を点けたとき、そのまま前に出ている)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val on = if (Build.VERSION.SDK_INT >= 20) com.uyatame.ucrt.legacy.compat.Api20.interactive(pm) else pm.isScreenOn
        if (!on) {
            // 解除の返事を待っている途中なら閉じる(待ったまま残らないように)
            if (dismissing) { dismissing = false; LockGuard.showing = false; finish() }
            return
        }
        if (!dismissing && !isChangingConfigurations) { LockGuard.showing = false; finish() }
    }

    override fun onDestroy() {
        LockGuard.showing = false
        main.removeCallbacks(tick)
        main.removeCallbacks(posTick)
        unsub?.invoke()
        present?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }
}
