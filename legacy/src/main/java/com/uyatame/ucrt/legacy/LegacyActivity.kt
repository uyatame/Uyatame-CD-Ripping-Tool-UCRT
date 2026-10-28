package com.uyatame.ucrt.legacy

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.compat.Api21
import com.uyatame.ucrt.legacy.compat.Api23
import com.uyatame.ucrt.legacy.compat.Insets20
import com.uyatame.ucrt.legacy.ui.ArtView
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.MATCH
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.ProgressBarView
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.icon
import com.uyatame.ucrt.legacy.ui.iconButton
import com.uyatame.ucrt.legacy.ui.rounded
import com.uyatame.ucrt.legacy.ui.setIcon
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import kotlin.math.abs

/**
 * UCRT Legacy の画面(本家の App と同じ作り)。
 * 下のタブ(ライブラリ・ディスク・設定)、取り込みの帯、ミニプレーヤー、全画面の再生画面。
 * 横向きでは、下のタブの代わりに左のレールで切り替える
 */
class LegacyActivity : Activity() {
    val app: LegacyApp get() = LegacyApp.app
    private val main = Handler(Looper.getMainLooper())

    lateinit var root: FrameLayout; private set
    private lateinit var content: FrameLayout
    private lateinit var bottom: LinearLayout
    private var rail: LinearLayout? = null
    private var navBar: LinearLayout? = null
    private var miniBox: LinearLayout? = null
    private var miniArt: ArtView? = null
    private var miniTitle: TextView? = null
    private var miniSub: TextView? = null
    private var miniPlay: android.widget.ImageView? = null
    private var miniProgress: ProgressBarView? = null
    private var snackView: LinearLayout? = null
    private var snackText: TextView? = null
    private var snackAct: TextView? = null
    var ripBar: View? = null

    /** 今のタブ(0 = ライブラリ、1 = ディスク、2 = 設定) */
    var tab = 0; private set
    /** ライブラリの行き先(本家の libStack と同じ文字列) */
    val libStack = ArrayList<String>()
    /** 設定のページ(0 = トップ) */
    var settingsPage = 0
    private var page: Page? = null
    private var pageKey = ""
    private val scroll = HashMap<String, IntArray>()
    /** 全画面の再生画面 */
    private var nowPlaying: NowPlayingView? = null
    private val unsubs = ArrayList<() -> Unit>()

    // ---- 曲を選ぶ(まとめてプレイリストへ) ----
    var selecting = false; private set
    val selected = LinkedHashSet<String>()
    var selectionPool: List<com.uyatame.ucrt.library.LibTrack>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        lastDiscEvent = Disc.discEvent
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        savedInstanceState?.let {
            tab = it.getInt("tab")
            settingsPage = it.getInt("settingsPage")
            it.getStringArrayList("libStack")?.let { s -> libStack.addAll(s) }
        }
        build()
        unsubs += Bus.on(-1) { m -> onEvent(m) }
        app.player.onError = { snack(it) }
        Permissions.ask(this)
        CrashLog.showIfAny(this)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("tab", tab)
        out.putInt("settingsPage", settingsPage)
        out.putStringArrayList("libStack", ArrayList(libStack))
    }

    override fun onResume() {
        super.onResume()
        current = this
        Disc.scan()
        // カメラ・決済アプリなどに音を取られて止まっていたら、続きを再生する
        app.player.resumeAfterInterruption()
        if (Burn.session != null) Burn.dialog()
        keepOn()
    }

    /** 取り込み中は画面を消さない(設定がオンのとき) */
    private fun keepOn() {
        val on = Disc.busy && app.prefs.keepScreenOn
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        if (current === this) current = null
        unsubs.forEach { it() }
        page?.dispose()
        app.player.onError = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        app.writeResume()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 向きが変わったら、並びを作り直す(横向きでは左のレール)
        val wasPlayer = nowPlaying != null
        savePageScroll()
        build()
        if (wasPlayer) openPlayer(false)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        app.lib.refresh(false)
        Bus.emit(Bus.LIBRARY)
    }

    /** 横向き(幅が高さより広い) */
    val landscape: Boolean get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** タブレットモード(設定: 0 = 画面の大きさで決める、1 = オン、2 = オフ) */
    val tablet: Boolean get() = when (app.prefs.sp.getInt("tabletMode", 0)) {
        1 -> true
        2 -> false
        else -> resources.configuration.smallestScreenWidthDp >= 600
    }

    /** 下のタブの代わりに左のレールで切り替えるか(横向き・タブレットモード) */
    val useRail: Boolean get() = landscape || tablet

    /** タブレットモードで幅が広ければ、再生画面を右に出したままにする */
    private val wantPane: Boolean get() = tablet && resources.configuration.screenWidthDp >= 840 && app.player.current != null

    /** 右に出したままの再生画面の幅(dp。出していなければ 0) */
    val paneWidthDp: Int get() = if (paneView != null) (resources.configuration.screenWidthDp * 0.36f).toInt().coerceIn(340, 440) else 0
    private var paneView: NowPlayingView? = null

    // =====================================================================
    // 画面の組み立て
    // =====================================================================

    private fun build() {
        Pal.apply(this, app.prefs.themeMode, app.prefs.themeColor, app.prefs.textColor)
        themeSig = app.prefs.let { "${it.themeMode}/${it.themeColor}/${it.textColor}" }
        layoutSig = "$tablet/${app.prefs.sp.getBoolean("hideNavBar", false)}"
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Pal.surface))
        if (Build.VERSION.SDK_INT >= 21) {
            Api21.statusBar(window, Pal.surface)
            Api21.navBar(window, Pal.container)
        }
        root = FrameLayout(this)
        root.setBackgroundColor(Pal.surface)
        if (Build.VERSION.SDK_INT >= 23) Api23.lightStatusBar(root, !Pal.dark)
        content = FrameLayout(this)
        bottom = vbox(this)
        val shell: LinearLayout
        paneView?.dispose()
        paneView = null
        if (useRail) {
            shell = hbox(this, Gravity.TOP)
            rail = buildRail()
            shell.add(rail!!, w = 80, h = MATCH)
            val col = vbox(this)
            col.add(content, h = 0, weight = 1f)
            col.add(bottom)
            shell.add(col, w = 0, h = MATCH, weight = 1f)
            navBar = null
            if (wantPane) {
                val pv = NowPlayingView(this, inPane = true)
                paneView = pv
                shell.add(pv.view, w = paneWidthDp, h = MATCH)
            }
        } else {
            shell = vbox(this)
            shell.add(content, h = 0, weight = 1f)
            shell.add(bottom)
            rail = null
        }
        root.add(shell, h = MATCH)
        buildBottom()
        val snack = hbox(this)
        snack.bgd(rounded(Pal.inverseSurface, 4f))
        snack.setPadding(dp(16), dp(4), dp(8), dp(4))
        snack.minimumHeight = dp(48)
        val st = text(this, "", Type.BodyMedium, Pal.inverseOnSurface, lines = 3)
        st.setPadding(0, dp(10), 0, dp(10))
        snack.add(st, w = 0, weight = 1f)
        val sa = text(this, "", Type.LabelLarge, if (Pal.dark) Pal.onPrimary else Pal.primaryContainer)
        sa.setPadding(dp(12), dp(12), dp(12), dp(12))
        snack.add(sa, w = WRAP)
        snack.visibility = View.GONE
        root.add(snack, h = WRAP, l = 12, r = 12, b = 96, gravity = Gravity.BOTTOM)
        snackView = snack; snackText = st; snackAct = sa
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 20) Insets20.apply(root)
        applyNavBar()
        nowPlaying = null
        pageKey = ""
        show()
        // 向きが変わっても、編集中の画面はそのまま出しておく
        for (o in overlays) {
            (o.view.parent as? ViewGroup)?.removeView(o.view)
            root.addView(o.view, root.indexOfChild(snack), FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    private val tabs: List<Triple<String, String, Int>>
        get() = listOf(
            Triple(Icons.Library, S(R.string.library), 0),
            Triple(Icons.Album, S(R.string.disc), 1),
            Triple(Icons.Settings, S(R.string.settings), 2),
        )

    /** 下のタブ(本家の NavigationBar) */
    private fun buildNav(): LinearLayout {
        val bar = hbox(this, Gravity.CENTER)
        bar.setBackgroundColor(Pal.container)
        for ((ic, label, i) in tabs) {
            val item = vbox(this, Gravity.CENTER)
            val pill = FrameLayout(this)
            val sel = tab == i
            pill.bgd(rounded(if (sel) Pal.secondaryContainer else 0, 16f))
            pill.add(icon(this, ic, if (sel) Pal.onSecondaryContainer else Pal.onSurfaceVariant, 24), w = 24, h = 24, gravity = Gravity.CENTER)
            item.add(pill, w = 64, h = 32)
            item.add(text(this, label, Type.LabelMedium, if (sel) Pal.onSurface else Pal.onSurfaceVariant), w = WRAP, t = 4)
            item.setPadding(0, dp(12), 0, dp(16))
            item.tap(0f) { onTab(i) }
            bar.add(item, w = 0, weight = 1f)
        }
        return bar
    }

    /** 横向きの左のレール(本家の NavigationRail) */
    private fun buildRail(): LinearLayout {
        val bar = vbox(this, Gravity.CENTER)
        bar.setBackgroundColor(Pal.container)
        for ((ic, label, i) in tabs) {
            val item = vbox(this, Gravity.CENTER)
            val pill = FrameLayout(this)
            val sel = tab == i
            pill.bgd(rounded(if (sel) Pal.secondaryContainer else 0, 16f))
            pill.add(icon(this, ic, if (sel) Pal.onSecondaryContainer else Pal.onSurfaceVariant, 24), w = 24, h = 24, gravity = Gravity.CENTER)
            item.add(pill, w = 56, h = 32)
            item.add(text(this, label, Type.LabelMedium, if (sel) Pal.onSurface else Pal.onSurfaceVariant), w = WRAP, t = 4)
            item.setPadding(0, dp(6), 0, dp(10))
            item.tap(0f) { onTab(i) }
            bar.add(item, w = MATCH)
        }
        return bar
    }

    private fun buildBottom() {
        bottom.removeAllViews()
        ripBar = Rip.bar(this)?.also { bottom.add(it) }
        buildMini()
        if (!useRail) {
            navBar = buildNav()
            bottom.add(navBar!!)
        } else {
            val r = rail
            if (r != null) {
                val parent = r.parent as ViewGroup
                val idx = parent.indexOfChild(r)
                parent.removeView(r)
                rail = buildRail()
                parent.addView(rail, idx, LinearLayout.LayoutParams(dp(80), MATCH))
            }
        }
    }

    // ---- ミニプレーヤー(本家の MiniPlayer。左右にスワイプで前後の曲) ----

    private fun buildMini() {
        val p = app.player
        val cur = p.current
        // 右に再生画面を出しているときは、ミニプレーヤーはいらない
        if (cur == null || paneView != null) { miniBox = null; return }
        val box = vbox(this)
        box.setBackgroundColor(Pal.containerLow)
        val prog = ProgressBarView(this, 0f, 2)
        prog.trackColor = Pal.secondaryContainer
        box.add(prog)
        val row = hbox(this)
        row.setPadding(dp(12), dp(8), dp(4), dp(8))
        val art = ArtView(this, 6f)
        row.add(art, w = 44, h = 44, r = 12)
        val col = vbox(this)
        val t = text(this, cur.title, Type.TitleSmall)
        val s = text(this, cur.artist, Type.BodySmall, Pal.onSurfaceVariant)
        col.add(t); col.add(s)
        row.add(col, w = 0, weight = 1f)
        val play = iconButton(this, if (p.isPlaying) Icons.Pause else Icons.PlayArrow, S(R.string.play), Pal.onSurface) { p.toggle() }
        row.add(play, w = 48, h = 48)
        row.add(iconButton(this, Icons.SkipNext, S(R.string.next_track), Pal.onSurface) { p.next() }, w = 48, h = 48)
        box.add(row)
        box.isClickable = true
        box.setOnClickListener { openPlayer(true) }
        swipe(row, onLeft = { p.next() }, onRight = { p.prev() }, onTap = { openPlayer(true) })
        bottom.addView(box, if (ripBar != null) 1 else 0)
        miniBox = box; miniArt = art; miniTitle = t; miniSub = s; miniPlay = play; miniProgress = prog
        updateMini()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun updateMini() {
        val p = app.player
        val cur = p.current ?: return
        miniTitle?.text = cur.title
        miniSub?.text = cur.artist
        miniPlay?.setIcon(if (p.isPlaying) Icons.Pause else Icons.PlayArrow, Pal.onSurface)
        miniPlay?.contentDescription = S(if (p.isPlaying) R.string.pause else R.string.play)
        miniArt?.enabledArt = app.prefs.listArt
        miniArt?.bind(Actions.artRef(this, cur.track), dp(44))
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = app.player
            val d = p.durationMs
            miniProgress?.progress = if (d > 0) (p.positionMs.toFloat() / d).coerceIn(0f, 1f) else 0f
            nowPlaying?.tick()
            paneView?.tick()
            if (miniBox != null || nowPlaying != null || paneView != null) main.postDelayed(this, if (p.isPlaying) 250 else 1000)
        }
    }

    /** 左右のスワイプとタップ(ミニプレーヤー) */
    private fun swipe(v: View, onLeft: () -> Unit, onRight: () -> Unit, onTap: () -> Unit) {
        var x0 = 0f
        var moved = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { x0 = e.rawX; moved = false; false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0
                    if (abs(dx) > dp(16)) { moved = true; view.translationX = dx * 0.6f; view.alpha = 1f - (abs(dx) / dp(240)).coerceIn(0f, 0.7f) }
                    moved
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - x0
                    view.animate().translationX(0f).alpha(1f).setDuration(150).start()
                    if (moved) {
                        if (dx <= -dp(72)) onLeft() else if (dx >= dp(72)) onRight()
                        true
                    } else {
                        if (e.actionMasked == android.view.MotionEvent.ACTION_UP && abs(dx) < dp(8)) onTap()
                        false
                    }
                }
                else -> false
            }
        }
    }

    // =====================================================================
    // 画面の切り替え
    // =====================================================================

    fun onTab(t: Int) {
        if (t == 0 && tab == 0) { libHome(); return }
        if (t == 2 && tab == 2 && settingsPage != 0) { settingsPage = 0; show(); return }
        savePageScroll()
        tab = t
        buildBottom()
        show()
    }

    private fun currentKey(): String = when (tab) {
        0 -> "lib:" + (libStack.lastOrNull() ?: "home")
        1 -> "disc"
        else -> "set:$settingsPage"
    }

    private fun savePageScroll() {
        val p = page ?: return
        p.scrollState()?.let { scroll[pageKey] = it }
    }

    /** 今の行き先の画面を作って出す */
    fun show() {
        val key = currentKey()
        savePageScroll()
        page?.dispose()
        content.removeAllViews()
        val pg = when (tab) {
            0 -> LibraryPages.make(this, libStack.lastOrNull())
            1 -> DiscPage(this)
            else -> SettingsPages.make(this, settingsPage)
        }
        page = pg
        pageKey = key
        content.addView(pg.view, FrameLayout.LayoutParams(MATCH, MATCH))
        pg.restoreScroll(scroll[key])
    }

    fun libPush(route: String) {
        savePageScroll()
        libStack.add(route)
        show()
    }

    fun libPop() {
        if (libStack.isEmpty()) return
        scroll.remove(currentKey())
        libStack.removeAt(libStack.lastIndex)
        show()
    }

    fun libReplaceTop(route: String) {
        if (libStack.isNotEmpty()) libStack.removeAt(libStack.lastIndex)
        libStack.add(route)
        show()
    }

    fun libHome() {
        libStack.clear()
        show()
    }

    /** 再生画面やメニューから「アルバムを表示」などを選んだとき: ライブラリのタブへ */
    fun openRoute(route: String) {
        closePlayer()
        if (tab != 0) { savePageScroll(); tab = 0; buildBottom() }
        if (libStack.lastOrNull() != route) libStack.add(route)
        show()
    }

    fun openSettings(p: Int) {
        if (tab != 2) { savePageScroll(); tab = 2; buildBottom() }
        settingsPage = p
        show()
    }

    // =====================================================================
    // 画面いっぱいに重ねる画面(曲情報の編集など)
    // =====================================================================

    private class Overlay(val view: View, val onBack: () -> Unit)
    private val overlays = ArrayList<Overlay>()

    /** 画面いっぱいに重ねて出す(戻るボタンで onBack) */
    fun openOverlay(v: View, onBack: () -> Unit) {
        v.setBackgroundColor(Pal.surface)
        v.isClickable = true
        overlays += Overlay(v, onBack)
        // スナックより下に置く
        val idx = snackView?.let { root.indexOfChild(it) }?.takeIf { it >= 0 } ?: root.childCount
        root.addView(v, idx, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    fun closeOverlay(v: View) {
        val o = overlays.firstOrNull { it.view === v } ?: return
        overlays.remove(o)
        hideKeyboard(v)
        root.removeView(v)
    }

    // ---- 画像を選ぶ(ジャケットの変更) ----

    private var imageCb: ((android.net.Uri) -> Unit)? = null

    fun pickImage(cb: (android.net.Uri) -> Unit) {
        imageCb = cb
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(i, null), REQ_IMAGE)
        } catch (e: Exception) {
            imageCb = null
            snack(S(R.string.could_not_load_image))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMAGE) {
            val cb = imageCb
            imageCb = null
            val u = data?.data
            if (resultCode == RESULT_OK && u != null && cb != null) cb(u)
        }
    }

    /** 選んだ画像を読む(大きすぎるものは断る) */
    fun readImage(u: android.net.Uri): ByteArray? = runCatching {
        contentResolver.openInputStream(u)?.use { ins ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > 40 shl 20) throw java.io.IOException("too large")
            }
            out.toByteArray()
        }
    }.getOrNull()

    fun hideKeyboard(v: View) {
        runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        }
    }

    // =====================================================================
    // 全画面の再生画面
    // =====================================================================

    fun openPlayer(animate: Boolean) {
        if (app.player.current == null || nowPlaying != null || paneView != null) return
        val v = NowPlayingView(this)
        nowPlaying = v
        root.addView(v.view, FrameLayout.LayoutParams(MATCH, MATCH))
        if (animate) {
            v.view.translationY = resources.displayMetrics.heightPixels.toFloat()
            v.view.animate().translationY(0f).setDuration(260).start()
        }
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun closePlayer() {
        val v = nowPlaying ?: return
        nowPlaying = null
        v.dispose()
        v.view.animate().translationY(resources.displayMetrics.heightPixels.toFloat()).setDuration(220)
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { root.removeView(v.view) }
            }).start()
        if (Build.VERSION.SDK_INT >= 21) Api21.statusBar(window, Pal.surface)
        if (Build.VERSION.SDK_INT >= 23) Api23.lightStatusBar(root, !Pal.dark)
    }

    // =====================================================================
    // 知らせ
    // =====================================================================

    private var hadCurrent = false

    private var lastDiscEvent = 0

    private fun onEvent(m: Int) {
        if (m and Bus.LIBRARY != 0 && pendingAlbum != null && !app.lib.scanning) tryPendingAlbum()
        if (m and Bus.DISC != 0 && Disc.discEvent != lastDiscEvent) {
            // 新しい音楽 CD を読み込んだら、ディスクのタブを開く(本家と同じ)
            lastDiscEvent = Disc.discEvent
            if (tab != 1) { closePlayer(); onTab(1) }
        }
        if (m and Bus.SETTINGS != 0) {
            // テーマが変わったら作り直す
            val p = app.prefs
            val sig = "${p.themeMode}/${p.themeColor}/${p.textColor}"
            if (sig != themeSig) { themeSig = sig; savePageScroll(); build(); return }
            // タブレットモード・ナビゲーションバーの設定が変わったら作り直す
            val lay = "$tablet/${p.sp.getBoolean("hideNavBar", false)}"
            if (lay != layoutSig) { layoutSig = lay; savePageScroll(); build(); return }
        }
        if (m and Bus.PLAYER != 0 && wantPane != (paneView != null)) { savePageScroll(); build(); return }
        if (m and (Bus.PLAYER or Bus.RIP) != 0) {
            val has = app.player.current != null
            if (m and Bus.RIP != 0) keepOn()
            // 取り込みの帯は、出す・消すときだけ作り直す(進み具合は書き換えるだけ)
            if (has != (miniBox != null) || Disc.busy != (ripBar != null)) buildBottom() else { updateMini(); Rip.update(this, ripBar) }
            if (!has && hadCurrent) closePlayer()
            hadCurrent = has
        }
        if (m and (Bus.SETTINGS or Bus.ART) != 0) updateMini()
        nowPlaying?.refresh(m)
        paneView?.refresh(m)
        val pg = page ?: return
        if (pg.mask and m != 0) pg.refresh(m)
    }

    private var themeSig = ""
    private var layoutSig = ""

    /** ナビゲーションバーを隠す(設定。Android 4.4 から。画面の端からスワイプで一時的に出せる) */
    private fun applyNavBar() {
        if (Build.VERSION.SDK_INT < 19) return
        val hide = app.prefs.sp.getBoolean("hideNavBar", false)
        @Suppress("DEPRECATION")
        // 中身をナビゲーションバーの場所まで広げる(LAYOUT_HIDE_NAVIGATION。これが無いと、隠した場所に余白が残る)
        window.decorView.systemUiVisibility = if (hide) {
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or 0x00001000 /* SYSTEM_UI_FLAG_IMMERSIVE_STICKY */ or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else 0
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyNavBar()
    }

    // ---- お知らせ(本家の Snackbar) ----

    private val hideSnack = Runnable { snackView?.visibility = View.GONE }

    fun snack(msg: String) = snackAction(msg, null, null)

    /** ボタンつきのお知らせ(「元に戻す」など) */
    fun snackAction(msg: String, action: String?, onAction: (() -> Unit)?) {
        val v = snackView ?: return
        snackText?.text = msg
        val a = snackAct!!
        if (action != null && onAction != null) {
            a.text = action
            a.visibility = View.VISIBLE
            a.tap(4f) { v.visibility = View.GONE; onAction() }
        } else a.visibility = View.GONE
        v.visibility = View.VISIBLE
        v.bringToFront()
        main.removeCallbacks(hideSnack)
        main.postDelayed(hideSnack, if (action != null) 7000 else 4000)
    }

    /** 取り込みのあと「ライブラリで見る」: 読み込み直しが終わったら、そのアルバムを開く */
    private var pendingAlbum: Pair<String, String>? = null

    fun openAlbum(title: String, artist: String) {
        pendingAlbum = title to artist
        if (!tryPendingAlbum()) openRoute("cat:albums")
    }

    private fun tryPendingAlbum(): Boolean {
        val (t, a) = pendingAlbum ?: return false
        val al = app.lib.albums.firstOrNull { it.title == t && it.artist == a } ?: app.lib.albums.firstOrNull { it.title == t } ?: return false
        pendingAlbum = null
        if (tab == 0 && libStack.lastOrNull() == "cat:albums") libStack.removeAt(libStack.lastIndex)
        openRoute("album:${al.key}")
        return true
    }

    // ---- 曲を選ぶモード ----

    fun startSelection(first: String?) {
        selecting = true
        selected.clear()
        if (first != null) selected.add(first)
        Bus.emit(Bus.STORE)
    }

    fun toggleSelected(uri: String) {
        if (!selected.remove(uri)) selected.add(uri)
        Bus.emit(Bus.STORE)
    }

    fun endSelection() {
        selecting = false
        selected.clear()
        Bus.emit(Bus.STORE)
    }

    // ---- 戻るボタン ----

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (nowPlaying?.back() == true) return
        if (nowPlaying != null) { closePlayer(); return }
        overlays.lastOrNull()?.let { it.onBack(); return }
        if (selecting) { endSelection(); return }
        if (page?.back() == true) return
        if (tab == 0 && libStack.isNotEmpty()) { libPop(); return }
        if (tab == 2 && settingsPage != 0) {
            settingsPage = if (settingsPage in 81..89) 8 else 0
            show(); return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("openPlayer", false)) openPlayer(false)
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) Disc.scan()
    }

    companion object {
        /** いま出ている画面(取り込みなどの知らせを出す先) */
        @Volatile var current: LegacyActivity? = null
        private const val REQ_IMAGE = 7101
    }
}
