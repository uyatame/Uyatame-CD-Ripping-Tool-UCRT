package com.uyatame.ucrt.legacy

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.library.Cue
import com.uyatame.ucrt.library.LibTrack
import com.uyatame.ucrt.library.QualityClass
import com.uyatame.ucrt.legacy.compat.Api21
import com.uyatame.ucrt.legacy.compat.Api23
import com.uyatame.ucrt.legacy.ui.ArtView
import com.uyatame.ucrt.legacy.ui.Btn
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.marquee
import com.uyatame.ucrt.legacy.ui.ListPage
import com.uyatame.ucrt.legacy.ui.MATCH
import com.uyatame.ucrt.legacy.ui.Menu
import com.uyatame.ucrt.legacy.ui.Pager
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.Row
import com.uyatame.ucrt.legacy.ui.Sheet
import com.uyatame.ucrt.legacy.ui.SliderView
import com.uyatame.ucrt.legacy.ui.StaticRow
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.badge
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.bold
import com.uyatame.ucrt.legacy.ui.button
import com.uyatame.ucrt.legacy.ui.circle
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.dpf
import com.uyatame.ucrt.legacy.ui.field
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.icon
import com.uyatame.ucrt.legacy.ui.iconButton
import com.uyatame.ucrt.legacy.ui.pressBg
import com.uyatame.ucrt.legacy.ui.rounded
import com.uyatame.ucrt.legacy.ui.setIcon
import com.uyatame.ucrt.legacy.ui.style
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import com.uyatame.ucrt.player.LyricLine
import com.uyatame.ucrt.player.Lyrics
import com.uyatame.ucrt.player.LyricsCore
import java.io.File
import kotlin.math.abs

/** 再生画面の文字色など(本家の PlayerColors) */
class PlayerColors(val content: Int, val sub: Int, val accent: Int, val ambient: Boolean, val panel: Int) {
    companion object {
        fun of(ambient: Boolean) = if (ambient) {
            PlayerColors(Color.WHITE, Pal.alpha(Color.WHITE, 0.75f), 0xFFF3D58C.toInt(), true, Pal.alpha(Color.WHITE, 0.12f))
        } else PlayerColors(Pal.onSurface, Pal.onSurfaceVariant, Pal.tertiary, false, Pal.secondaryContainer)
    }
}

/**
 * 全画面のプレイヤー(本家の NowPlayingScreen)。左右のページで「お気に入り|再生中|再生リスト」を切り替える。
 * アンビエントモードでは、ジャケットの色で画面を染め、一時停止中は暗くする
 */
/** inPane: タブレットモードで、右に出したままにしているとき(閉じるボタンは出さない) */
class NowPlayingView(private val act: LegacyActivity, private val inPane: Boolean = false) {
    private val app = LegacyApp.app
    private val p = app.player
    val view = FrameLayout(act)
    private val ambient = app.prefs.ambientPlayer
    private val col = PlayerColors.of(ambient)
    private val bgArt = ImageView(act)
    private val tintView = View(act)
    private val dimView = View(act)
    private val pager = Pager(act)
    private val tabLabels = ArrayList<TextView>()
    private val tabMarks = ArrayList<View>()
    private var curId = -1L
    private var showLyrics = false
    private var lyrics: Lyrics? = null
    private var lyricsFor = ""

    // ---- 再生中のページの部品 ----
    private val page = FrameLayout(act)
    private val cover = ArtView(act, 12f)
    private val coverBox = FrameLayout(act)
    private val seekOverlay = text(act, "", Type.HeadlineMedium, Color.WHITE)
    private val lyricsBox = FrameLayout(act)
    private val lyricsList = ListView(act)
    private val lyricsAdapter = com.uyatame.ucrt.legacy.ui.RowAdapter()
    private val quality = text(act, " ", Type.TitleSmall, col.accent)
    private val hiRes = badge(act, "Hi-Res", true)
    private val chips = hbox(act, Gravity.CENTER)
    private val title = text(act, "", Type.HeadlineSmall, col.content, lines = 2)
    private val artist = text(act, "", Type.TitleMedium, col.sub)
    private val album = text(act, "", Type.BodyMedium, col.sub)
    private val favBtn = icon(act, Icons.FavoriteBorder, col.sub)
    private val checkBtn = icon(act, Icons.Headphones, col.sub)
    private val lyricsBtn = icon(act, Icons.Lyrics, col.sub)
    private val seek: SliderView
    private val posText = text(act, "0:00", Type.LabelMedium, col.sub)
    private val leftText = text(act, "-0:00", Type.LabelMedium, col.sub)
    private val repeatBtn = icon(act, Icons.Repeat, col.sub)
    private val shuffleBtn = icon(act, Icons.Shuffle, col.sub)
    private val playBtn = icon(act, Icons.PlayArrow, col.content, 40)
    private val timerBtn = hbox(act, Gravity.CENTER)
    private val countText = text(act, "", Type.LabelMedium, col.sub)
    private var seeking: Float? = null
    private val favPage = ListPage(act)
    private val queuePage = ListPage(act)

    init {
        view.setBackgroundColor(if (ambient) Color.BLACK else Pal.surface)
        view.isClickable = true // 下の画面へタッチが抜けないように
        // ---- 背景 ----
        if (ambient) {
            bgArt.scaleType = ImageView.ScaleType.CENTER_CROP
            bgArt.alpha = 0.85f
            view.add(bgArt, h = MATCH)
        }
        view.add(tintView, h = MATCH)
        dimView.setBackgroundColor(Color.BLACK)
        dimView.alpha = 0f
        if (ambient) view.add(dimView, h = MATCH)
        // ---- 中身 ----
        val colv = vbox(act)
        view.add(colv, h = MATCH)
        colv.add(topBar())
        colv.add(pager, h = 0, weight = 1f)
        seek = SliderView(act, 0f, { v -> seeking = v; showSeek() }, { v ->
            val d = p.durationMs
            seeking = null
            if (d > 0) p.seek((v * d).toLong())
            showSeek()
        })
        if (ambient) { seek.activeColor = Color.WHITE; seek.thumbColor = Color.WHITE; seek.inactiveColor = Pal.alpha(Color.WHITE, 0.28f) }
        buildPlayerPage()
        pager.pages(favPage, page, queuePage)
        pager.onPage = { updateTabs() }
        pager.post { pager.go(1, false); updateTabs() }
        if (!inPane) statusBar()
        refresh(-1)
    }

    private fun statusBar() {
        val w = act.window
        if (Build.VERSION.SDK_INT >= 21) Api21.statusBar(w, if (ambient) Color.BLACK else Pal.surface)
        if (Build.VERSION.SDK_INT >= 23) Api23.lightStatusBar(act.root, !ambient && !Pal.dark)
    }

    // =====================================================================
    // 上の帯: 閉じる・ページの切り替え・メニュー
    // =====================================================================

    private fun topBar(): View {
        val row = hbox(act)
        row.setPadding(dp(4), dp(4), dp(4), 0)
        if (inPane) row.add(View(act), w = 48, h = 48)
        else row.add(iconButton(act, Icons.ArrowDown, S(R.string.close), col.content) { act.closePlayer() }, w = 48, h = 48)
        val tabs = hbox(act, Gravity.CENTER)
        listOf(S(R.string.favorites), S(R.string.playing), S(R.string.queue)).forEachIndexed { i, l ->
            val c = vbox(act, Gravity.CENTER_HORIZONTAL)
            c.setPadding(dp(8), dp(4), dp(8), dp(4))
            val t = text(act, l, Type.LabelLarge, col.content)
            c.add(t, w = WRAP)
            val mark = View(act)
            mark.bgd(rounded(col.accent, 2f))
            c.add(mark, w = 16, h = 3, t = 3)
            c.tap(8f) { pager.go(i) }
            tabs.add(c, w = WRAP)
            tabLabels.add(t); tabMarks.add(mark)
        }
        row.add(tabs, w = 0, weight = 1f)
        // キャスト(Chromecast。機器を探すのは Android 4.1 から)
        if (com.uyatame.ucrt.legacy.cast.CastManager.canDiscover) {
            val cb = iconButton(act, Icons.Cast, S(R.string.cast_title), col.content) { CastSheet.show(act) }
            castBtn = cb
            updateCast()
            row.add(cb, w = 48, h = 48)
        }
        lateinit var more: View
        more = iconButton(act, Icons.MoreVert, S(R.string.menu), col.content) { menu(more) }
        row.add(more, w = 48, h = 48)
        return row
    }

    private var castBtn: android.widget.ImageView? = null

    private fun updateCast() {
        val b = castBtn ?: return
        val on = com.uyatame.ucrt.legacy.cast.CastManager.connected != null
        b.setIcon(if (on) Icons.CastConnected else Icons.Cast, if (on) col.accent else col.content)
    }

    private fun updateTabs() {
        tabLabels.forEachIndexed { i, t ->
            val sel = pager.page == i
            t.setTextColor(if (sel) col.content else Pal.alpha(col.sub, 0.6f))
            if (sel) t.bold() else t.style(Type.LabelLarge, Pal.alpha(col.sub, 0.6f))
            tabMarks[i].visibility = if (sel) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun menu(anchor: View) {
        val cur = p.current ?: return
        val lib = app.lib.trackMap[cur.uri]
        val m = Menu(anchor)
        m.item(S(if (showLyrics) R.string.hide_lyrics else R.string.show_lyrics), Icons.Lyrics) { toggleLyrics(); pager.go(1) }
        m.item(S(R.string.sleep_timer), Icons.Timer) { sleepDialog() }
        m.item(S(R.string.song_details), Icons.Info) { Actions.trackInfo(act, cur.track) }
        if (lib != null) {
            app.lib.albumKeyOf(lib.uri)?.let { k -> m.item(S(R.string.go_to_album), Icons.Album) { act.openRoute("album:$k") } }
            m.item(S(R.string.go_to_artist), Icons.Person) { act.openRoute("artist:" + artistOf(lib)) }
            m.item(S(R.string.add_to_playlist_2), Icons.PlaylistAdd) { Actions.playlistPick(act, listOf(lib.uri)) }
            m.item(S(R.string.select_multiple_add_to_playlist), Icons.CheckCircle) { act.closePlayer(); act.startSelection(lib.uri) }
            val check = app.store.isCheck(lib.uri)
            m.item(S(if (check) R.string.remove_from_sound_check else R.string.add_to_sound_check), Icons.Headphones) { Actions.toggleCheck(act, lib.uri) }
        }
        m.item(S(R.string.sound_settings), Icons.Tune) { openSound() }
        m.item(S(R.string.gesture_guide), Icons.Info) { guide() }
        m.divider()
        m.item(S(R.string.stop_playback), Icons.Close) { p.stop(); act.closePlayer() }
        m.show()
    }

    /** 音質設定(下から出す。本家の AudioSettingsSheet) */
    private fun openSound() = Sound.sheet(act)

    // =====================================================================
    // 再生中のページ
    // =====================================================================

    private fun buildPlayerPage() {
        val portrait = inPane || !act.landscape
        val small = act.resources.displayMetrics.let { it.heightPixels / com.uyatame.ucrt.legacy.ui.Ui.density < 640 || it.widthPixels / com.uyatame.ucrt.legacy.ui.Ui.density < 340 }
        val playSize = if (small) 64 else 80
        val skipSize = if (small) 50 else 60
        // ジャケット(または歌詞)
        cover.square = false
        coverBox.add(cover, h = MATCH)
        seekOverlay.gravity = Gravity.CENTER
        seekOverlay.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, com.uyatame.ucrt.legacy.ui.Ui.sp(44f))
        seekOverlay.bold()
        seekOverlay.bgd(rounded(0x73000000, 12f))
        seekOverlay.visibility = View.GONE
        coverBox.add(seekOverlay, h = MATCH)
        lyricsList.divider = null
        lyricsList.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        lyricsList.cacheColorHint = Color.TRANSPARENT
        lyricsList.adapter = lyricsAdapter
        lyricsList.isVerticalScrollBarEnabled = false
        lyricsBox.add(lyricsList, h = MATCH)
        lyricsBox.visibility = View.GONE
        val artPane = FrameLayout(act)
        artPane.add(coverBox, w = MATCH, h = MATCH)
        artPane.add(lyricsBox, h = MATCH)
        coverGestures()

        // 曲の情報と操作
        val ctl = vbox(act, Gravity.CENTER_HORIZONTAL)
        val ql = hbox(act, Gravity.CENTER)
        ql.add(hiRes, w = WRAP, r = 8)
        quality.typeface = com.uyatame.ucrt.legacy.ui.Type.mediumFace
        ql.add(quality, w = WRAP)
        ctl.add(ql, w = WRAP)
        val chipScroll = android.widget.HorizontalScrollView(act)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chips)
        ctl.add(chipScroll, w = WRAP, t = if (small) 2 else 6)
        // 曲名とお気に入り
        val tr = hbox(act)
        val marks = if (small) hbox(act) else vbox(act)
        favBtn.tap(oval = true) { app.lib.trackMap[p.current?.uri]?.let { Actions.toggleFav(act, it.uri) } }
        checkBtn.tap(oval = true) { app.lib.trackMap[p.current?.uri]?.let { Actions.toggleCheck(act, it.uri) } }
        marks.add(favBtn, w = 48, h = 48)
        marks.add(checkBtn, w = 48, h = 48)
        tr.add(marks, w = WRAP)
        val tcol = vbox(act, Gravity.CENTER_HORIZONTAL)
        title.gravity = Gravity.CENTER
        title.bold()
        if (small) { title.style(Type.TitleLarge, col.content); title.bold() }
        // 長い曲名・狭い画面では 1 行に収めて流す
        title.marquee()
        artist.marquee()
        album.marquee()
        tcol.add(title)
        artist.gravity = Gravity.CENTER
        tcol.add(artist)
        album.gravity = Gravity.CENTER
        tcol.add(album)
        tcol.tap(12f) { related(tcol) }
        tr.add(tcol, w = 0, weight = 1f)
        if (small) tr.add(View(act), w = 48, h = 48)
        lyricsBtn.tap(oval = true) { toggleLyrics() }
        tr.add(lyricsBtn, w = 48, h = 48)
        ctl.add(tr, t = if (small) 4 else 10)
        // 再生位置
        ctl.add(seek, t = if (small) 4 else 12)
        val times = hbox(act)
        times.add(posText, w = 0, weight = 1f)
        times.add(leftText, w = WRAP)
        ctl.add(times, l = 10, r = 10)
        // 再生操作
        val ops = hbox(act)
        repeatBtn.tap(oval = true) { p.cycleRepeat() }
        ops.add(repeatBtn, w = 48, h = 48)
        ops.add(View(act), w = 0, h = 1, weight = 1f)
        val prev = icon(act, Icons.SkipPrevious, col.content, (skipSize * 0.47f).toInt())
        prev.contentDescription = S(R.string.previous_track)
        prev.tap(16f, rounded(0, 16f, col.content, 2f)) { p.prev() }
        ops.add(prev, w = skipSize, h = skipSize)
        ops.add(View(act), w = 0, h = 1, weight = 1f)
        playBtn.setIcon(Icons.PlayArrow, col.content, (playSize * 0.52f).toInt())
        // 塗りの色を決めないと、Android 4.x では黒く塗られる
        val circ = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(2.5f), col.content) }
        playBtn.tap(oval = true, base = circ) { p.toggle() }
        ops.add(playBtn, w = playSize, h = playSize)
        ops.add(View(act), w = 0, h = 1, weight = 1f)
        val next = icon(act, Icons.SkipNext, col.content, (skipSize * 0.47f).toInt())
        next.contentDescription = S(R.string.next_track)
        next.tap(16f, rounded(0, 16f, col.content, 2f)) { p.next() }
        ops.add(next, w = skipSize, h = skipSize)
        ops.add(View(act), w = 0, h = 1, weight = 1f)
        shuffleBtn.tap(oval = true) { p.toggleShuffle() }
        ops.add(shuffleBtn, w = 48, h = 48)
        ctl.add(ops, t = if (small) 2 else 10)
        // 下の帯: 音量・タイマー・曲番号・再生リスト
        val bot = hbox(act)
        bot.add(iconButton(act, Icons.Volume, S(R.string.volume), col.sub) { volume() }, w = 48, h = 48)
        timerBtn.tap(20f) { sleepDialog() }
        bot.add(timerBtn, w = WRAP, h = 48)
        bot.add(View(act), w = 0, h = 1, weight = 1f)
        bot.add(countText, w = WRAP)
        bot.add(View(act), w = 0, h = 1, weight = 1f)
        bot.add(iconButton(act, Icons.Queue, S(R.string.queue), col.sub) { pager.go(2) }, w = 48, h = 48)
        ctl.add(bot, t = if (small) 0 else 6, b = if (small) 2 else 6)

        if (portrait) {
            val v = vbox(act, Gravity.CENTER_HORIZONTAL)
            v.setPadding(dp(if (small) 16 else 24), 0, dp(if (small) 16 else 24), 0)
            v.add(artPane, h = 0, weight = 1f, t = if (small) 6 else 14, b = if (small) 6 else 14)
            v.add(ctl)
            page.add(v, h = MATCH)
        } else {
            val h = hbox(act)
            h.setPadding(dp(16), 0, dp(16), 0)
            h.add(artPane, w = 0, h = MATCH, weight = 0.42f, t = 8, b = 8)
            val sc = android.widget.ScrollView(act)
            sc.addView(ctl)
            sc.isFillViewport = true
            ctl.gravity = Gravity.CENTER
            h.add(sc, w = 0, h = MATCH, weight = 0.58f, l = 20)
            page.add(h, h = MATCH)
        }
        // 下へスワイプで閉じる、上へスワイプで音の設定
        verticalSwipe(page)
    }

    /** ジャケット: 左右にスワイプで前後の曲へ、タップで歌詞(本家の SwipeCover) */
    private fun coverGestures() {
        var x0 = 0f
        var y0 = 0f
        var dragging = false
        coverBox.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; dragging = false; v.parent?.requestDisallowInterceptTouchEvent(true); true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (!dragging && abs(dx) > dp(12) && abs(dx) > abs(dy)) dragging = true
                    if (!dragging && abs(dy) > dp(24) && abs(dy) > abs(dx)) {
                        // 縦のスワイプは画面に任せる
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        if (dy > dp(110)) { act.closePlayer() } else if (dy < -dp(110)) openSound()
                        return@setOnTouchListener false
                    }
                    if (dragging) {
                        v.translationX = dx
                        v.rotation = dx / v.width.coerceAtLeast(1) * 5f
                        v.alpha = 1f - (abs(dx) / v.width.coerceAtLeast(1) * 0.7f).coerceIn(0f, 0.7f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - x0
                    val w = v.width.toFloat().coerceAtLeast(1f)
                    if (dragging && abs(dx) >= w * 0.22f) {
                        val next = dx < 0
                        v.animate().translationX(if (next) -w else w).setDuration(150).setListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(a: android.animation.Animator) {
                                v.animate().setListener(null)
                                if (next) p.next() else p.prev()
                                v.translationX = if (next) w * 0.6f else -w * 0.6f
                                v.rotation = 0f
                                v.animate().translationX(0f).alpha(1f).setDuration(220).start()
                            }
                        }).start()
                    } else {
                        v.animate().translationX(0f).rotation(0f).alpha(1f).setDuration(200).start()
                        if (!dragging && e.actionMasked == MotionEvent.ACTION_UP && abs(e.rawY - y0) < dp(12)) toggleLyrics()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun verticalSwipe(v: View) {
        val gd = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val s = e1 ?: return false
                val dy = e2.rawY - s.rawY
                if (abs(dy) < abs(e2.rawX - s.rawX)) return false
                if (dy > dp(110)) act.closePlayer() else if (dy < -dp(110)) openSound()
                return true
            }
        })
        v.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
    }

    private fun related(anchor: View) {
        val lib = app.lib.trackMap[p.current?.uri] ?: return
        val m = Menu(anchor)
        app.lib.albumKeyOf(lib.uri)?.let { k -> m.item(S(R.string.go_to_album_2, lib.album), Icons.Album) { act.openRoute("album:$k") } }
        val ar = artistOf(lib)
        m.item(S(R.string.go_to_artist_2, ar), Icons.Person) { act.openRoute("artist:$ar") }
        m.show()
    }

    private fun toggleLyrics() {
        showLyrics = !showLyrics
        updateLyrics()
    }

    private fun showSeek() {
        val s = seeking
        if (s != null) {
            seekOverlay.text = fmtMs((s * p.durationMs).toLong())
            seekOverlay.visibility = if (showLyrics) View.GONE else View.VISIBLE
        } else seekOverlay.visibility = View.GONE
        tick()
    }

    // =====================================================================
    // 表示の更新
    // =====================================================================

    fun refresh(m: Int) {
        if (m and Bus.PLAYER != 0) updateCast()
        val cur = p.current ?: return
        val lib = app.lib.trackMap[cur.uri]
        if (cur.id != curId) {
            curId = cur.id
            loadArt(cur.track)
            loadLyrics(cur.track)
        }
        // 音質
        val hi = com.uyatame.ucrt.library.Quality.classOf(cur.track) == QualityClass.HiRes
        hiRes.visibility = if (hi) View.VISIBLE else View.GONE
        quality.text = p.formatText.ifEmpty { com.uyatame.ucrt.library.Quality.badge(cur.track) }
        buildChips()
        title.text = cur.title
        artist.text = cur.artist
        album.text = cur.album
        album.visibility = if (cur.album.isEmpty()) View.GONE else View.VISIBLE
        val fav = lib != null && app.store.isFav(lib.uri)
        val check = lib != null && app.store.isCheck(lib.uri)
        favBtn.setIcon(if (fav) Icons.Favorite else Icons.FavoriteBorder, when { lib == null -> Pal.alpha(col.sub, 0.3f); fav -> col.accent; else -> col.sub })
        favBtn.contentDescription = S(if (fav) R.string.remove_from_favorites else R.string.add_to_favorites)
        checkBtn.setIcon(Icons.Headphones, when { lib == null -> Pal.alpha(col.sub, 0.3f); check -> col.accent; else -> col.sub })
        lyricsBtn.setIcon(Icons.Lyrics, when { showLyrics -> col.accent; lyrics != null -> col.content; else -> Pal.alpha(col.sub, 0.4f) })
        repeatBtn.setIcon(if (p.repeatMode == 2) Icons.RepeatOne else Icons.Repeat, if (p.repeatMode != 0) col.accent else col.sub)
        repeatBtn.contentDescription = S(when (p.repeatMode) { 1 -> R.string.repeat_all; 2 -> R.string.repeat_one; else -> R.string.repeat_off })
        shuffleBtn.setIcon(Icons.Shuffle, if (p.shuffle) col.accent else col.sub)
        playBtn.setIcon(if (p.isPlaying) Icons.Pause else Icons.PlayArrow, col.content, ((playBtn.layoutParams?.width ?: dp(80)) * 0.52f / com.uyatame.ucrt.legacy.ui.Ui.density).toInt())
        playBtn.contentDescription = S(if (p.isPlaying) R.string.pause else R.string.play)
        countText.text = S(R.string.label_3, p.index + 1, p.queue.size)
        dimView.animate().alpha(if (p.isPlaying) 0f else 0.4f).setDuration(500).start()
        updateTimer()
        if (m and (Bus.STORE or Bus.LIBRARY or Bus.PLAYER) != 0 || m == -1) {
            buildFavorites()
            buildQueue()
        }
        tick()
    }

    /** 出力先・EQ・音の設定のチップ(本家の SoundStatus) */
    private fun buildChips() {
        chips.removeAllViews()
        fun chipView(label: String?, d: String?, selected: Boolean, onClick: () -> Unit): View {
            val c = hbox(act, Gravity.CENTER)
            c.minimumHeight = dp(32)
            c.setPadding(dp(if (label == null) 8 else 12), 0, dp(if (label == null) 8 else 16), 0)
            val fg = if (selected) (if (col.ambient) Color.BLACK else Pal.onSecondaryContainer) else (if (col.ambient) col.sub else Pal.onSurfaceVariant)
            if (d != null) c.add(icon(act, d, if (col.ambient) (if (selected) Color.BLACK else col.sub) else Pal.primary, 18), w = 18, h = 18, r = if (label != null) 8 else 0)
            if (label != null) c.add(text(act, label, Type.LabelLarge, fg), w = WRAP)
            val bg = if (selected) rounded(if (col.ambient) col.accent else Pal.secondaryContainer, 8f)
            else rounded(0, 8f, if (col.ambient) col.sub else Pal.outlineVariant, 1f)
            c.tap(8f, bg) { onClick() }
            return c
        }
        val s = app.prefs
        chips.add(chipView(Output.name(act), Output.icon(act), false) { Output.sheet(act) }, w = WRAP, r = 8)
        if (s.sourceDirect) chips.add(chipView(S(R.string.chip_source_direct), null, true) { openSound() }, w = WRAP, r = 8)
        if (!s.sourceDirect && s.eqEnabled) chips.add(chipView(S(R.string.chip_eq, Sound.eqName()), null, true) { openSound() }, w = WRAP, r = 8)
        chips.add(chipView(null, Icons.Tune, false) { openSound() }.also { it.contentDescription = S(R.string.sound_settings) }, w = WRAP)
    }

    private fun updateTimer() {
        timerBtn.removeAllViews()
        val at = p.sleepAtMs
        val left = if (at > 0) ((at - SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(1) else 0L
        if (left > 0 || p.stopAfterTrack) {
            timerBtn.setPadding(dp(8), 0, dp(12), 0)
            timerBtn.add(icon(act, Icons.Timer, col.accent, 18), w = 18, h = 18, r = 4)
            timerBtn.add(text(act, if (p.stopAfterTrack) S(R.string.end_of_song) else S(R.string.min, left), Type.LabelMedium, col.accent), w = WRAP)
        } else {
            timerBtn.setPadding(0, 0, 0, 0)
            timerBtn.add(icon(act, Icons.Timer, col.sub), w = 48, h = 48)
            timerBtn.contentDescription = S(R.string.sleep_timer)
        }
    }

    private var lastTimerMin = -1L

    /** 0.25 秒ごと(再生位置・歌詞) */
    fun tick() {
        val d = p.durationMs.coerceAtLeast(1)
        val frac = seeking ?: (p.positionMs.toFloat() / d).coerceIn(0f, 1f)
        if (!seek.dragging) seek.value = frac
        posText.text = fmtMs((frac * d).toLong())
        leftText.text = "-" + fmtMs(((1f - frac) * d).toLong().coerceAtLeast(0))
        val at = p.sleepAtMs
        val m = if (at > 0) (at - SystemClock.elapsedRealtime()) / 60_000L else -1L
        if (m != lastTimerMin) { lastTimerMin = m; updateTimer() }
        tickLyrics()
    }

    // ---- ジャケットと背景 ----

    private fun loadArt(t: LibTrack) {
        val ref = Actions.artRef(act, t)
        cover.enabledArt = true
        val dm = act.resources.displayMetrics
        cover.bind(ref, maxOf(dm.widthPixels, dm.heightPixels))
        Art.load(act, ref, maxOf(dm.widthPixels, dm.heightPixels)) { b -> applyArt(b) }
    }

    private fun applyArt(b: Bitmap?) {
        if (ambient) bgArt.setImageBitmap(b)
        val tint = if (b == null) 0xFF2A2A2A.toInt() else runCatching { dominant(b) }.getOrDefault(0xFF2A2A2A.toInt())
        tintView.bgd(
            if (ambient) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Pal.alpha(tint, 0.55f), Pal.alpha(tint, 0.35f), Pal.alpha(Color.BLACK, 0.8f)))
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Pal.alpha(tint, 0.22f), 0)),
        )
    }

    /** ジャケットの代表色(本家の dominantColor と同じ) */
    private fun dominant(src: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(src, 24, 24, true)
        val hsv = FloatArray(3)
        var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            Color.colorToHSV(c, hsv)
            val wt = 0.05 + hsv[1] * hsv[2]
            r += Color.red(c) * wt; g += Color.green(c) * wt; b += Color.blue(c) * wt; w += wt
        }
        if (small !== src) small.recycle()
        if (w <= 0) return 0xFF2A2A2A.toInt()
        val avg = Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
        Color.colorToHSV(avg, hsv)
        hsv[1] = hsv[1].coerceAtMost(0.75f)
        hsv[2] = hsv[2].coerceIn(0.32f, 0.58f)
        return Color.HSVToColor(hsv)
    }

    // ---- 歌詞 ----

    private fun loadLyrics(t: LibTrack) {
        lyrics = null
        lyricsFor = t.uri
        updateLyrics()
        if (Cue.isCue(t.uri)) return
        Thread({
            val l = runCatching { sidecar(t) }.getOrNull() ?: runCatching { LyricsCore.embedded(act, android.net.Uri.parse(t.uri)) }.getOrNull()
            act.runOnUiThread {
                if (lyricsFor == t.uri) { lyrics = l; updateLyrics(); refresh(0) }
            }
        }, "ucrt-lyrics").start()
    }

    /** 曲と同じフォルダにある同じ名前の .lrc / .txt */
    private fun sidecar(t: LibTrack): Lyrics? {
        val f = Lib.fileOf(t.uri) ?: return null
        val base = f.name.substringBeforeLast('.')
        val dir = f.parentFile ?: return null
        val lrc = dir.listFiles()?.firstOrNull { it.name.equals("$base.lrc", true) }
            ?: dir.listFiles()?.firstOrNull { it.name.equals("$base.txt", true) } ?: return null
        return LyricsCore.parse(LyricsCore.decode(lrc.readBytes()))
    }

    private var nowLine = -1

    private fun updateLyrics() {
        lyricsBox.visibility = if (showLyrics) View.VISIBLE else View.GONE
        coverBox.visibility = if (showLyrics) View.GONE else View.VISIBLE
        val l = lyrics
        if (!showLyrics) return
        if (l == null) {
            val c = vbox(act, Gravity.CENTER_HORIZONTAL)
            c.setPadding(dp(24), dp(24), dp(24), dp(24))
            c.add(icon(act, Icons.Lyrics, col.sub, 40), w = 40, h = 40, b = 12)
            c.add(text(act, S(R.string.no_lyrics_found), Type.TitleMedium, col.content), w = WRAP, b = 6)
            val b = text(act, S(R.string.add_lrc_file_with_same), Type.BodySmall, col.sub, lines = 0)
            b.gravity = Gravity.CENTER
            c.add(b)
            c.add(button(act, S(R.string.back_to_artwork), Btn.Text) { toggleLyrics() }.also { (it.getChildAt(it.childCount - 1) as TextView).setTextColor(col.accent) }, w = WRAP)
            lyricsAdapter.rows = listOf(StaticRow(c))
            return
        }
        nowLine = -1
        lyricsAdapter.rows = l.lines.mapIndexed { i, line -> LyricRow(line, l.synced, i) }
        tickLyrics()
    }

    private inner class LyricRow(val line: LyricLine, val synced: Boolean, val i: Int) : Row(OTHER) {
        override fun create(ctx: android.content.Context): View = text(ctx, "", Type.TitleLarge, col.content, lines = 0)
        override fun bind(v: View) {
            val t = v as TextView
            t.text = line.text.ifEmpty { if (synced) "♪" else "" }
            t.style(if (synced) Type.TitleLarge else Type.BodyLarge, when { !synced -> col.content; i == nowLine -> col.content; else -> Pal.alpha(col.sub, 0.5f) })
            if (i == nowLine) t.bold()
            t.setPadding(dp(8), dp(if (synced) 7 else 2), dp(8), dp(if (synced) 7 else 2))
            if (synced) t.tap(8f) { p.seek(line.timeMs) } else t.setOnClickListener(null)
        }
    }

    private fun tickLyrics() {
        val l = lyrics ?: return
        if (!showLyrics || !l.synced) return
        val pos = p.positionMs + 250
        val now = l.lines.indexOfLast { it.timeMs <= pos }
        if (now != nowLine) {
            nowLine = now
            lyricsAdapter.notifyDataSetChanged()
            if (now >= 0) lyricsList.smoothScrollToPositionFromTop((now - 2).coerceAtLeast(0), dp(32))
        }
    }

    // =====================================================================
    // お気に入り・再生リストのページ
    // =====================================================================

    /** 再生画面の一覧の行(本家の PanelRow) */
    private inner class PanelRow(val t: LibTrack, val active: Boolean, val onClick: () -> Unit, val handle: ((View) -> Unit)?, val trailing: Pair<String, () -> Unit>?, val trailColor: Int) : Row(PANEL) {
        override fun create(ctx: android.content.Context): View {
            val r = hbox(ctx)
            r.setPadding(dp(4), dp(6), 0, dp(6))
            r.add(icon(ctx, Icons.DragHandle, col.sub), w = 40, h = 40)
            r.add(ArtView(ctx, 6f), w = 44, h = 44, r = 12)
            val c = vbox(ctx)
            c.add(text(ctx, "", Type.BodyLarge, col.content))
            c.add(text(ctx, "", Type.BodySmall, col.sub))
            r.add(c, w = 0, weight = 1f)
            r.add(text(ctx, "", Type.LabelMedium, col.sub), w = WRAP, l = 6, r = 6)
            r.add(icon(ctx, Icons.Close, col.sub), w = 48, h = 48)
            return r
        }

        override fun bind(v: View) {
            val r = v as LinearLayout
            val hd = r.getChildAt(0)
            hd.visibility = if (handle != null) View.VISIBLE else View.GONE
            handle?.invoke(hd)
            val art = r.getChildAt(1) as ArtView
            art.enabledArt = app.prefs.listArt
            art.bind(Actions.artRef(act, t), dp(44))
            art.playing = active
            val c = r.getChildAt(2) as LinearLayout
            val tt = c.getChildAt(0) as TextView
            tt.text = t.title
            if (active) tt.bold() else tt.style(Type.BodyLarge, col.content)
            (c.getChildAt(1) as TextView).text = t.artist
            (r.getChildAt(3) as TextView).text = fmtMs(t.durationMs)
            val tr = r.getChildAt(4) as ImageView
            if (trailing != null) {
                tr.visibility = View.VISIBLE
                tr.setIcon(trailing.first, trailColor)
                tr.tap(oval = true) { trailing.second() }
            } else tr.visibility = View.GONE
            r.bgd(pressBg(if (active) rounded(col.panel, 12f) else null, 12f))
            r.isClickable = true
            r.setOnClickListener { onClick() }
        }
    }

    private fun buildFavorites() {
        val map = app.lib.trackMap
        val songs = app.store.favTracks.mapNotNull { map[it] }
        val playing = p.current?.uri
        if (songs.isEmpty()) {
            val c = vbox(act, Gravity.CENTER_HORIZONTAL)
            c.setPadding(dp(32), dp(96), dp(32), dp(32))
            c.add(icon(act, Icons.FavoriteBorder, col.sub, 48), w = 48, h = 48, b = 12)
            c.add(text(act, S(R.string.no_favorite_songs_yet), Type.TitleMedium, col.content), w = WRAP, b = 6)
            val b = text(act, S(R.string.tap_heart_in_player_to), Type.BodySmall, col.sub, lines = 0)
            b.gravity = Gravity.CENTER
            c.add(b)
            favPage.setRows(listOf(StaticRow(c)))
            return
        }
        val rows = ArrayList<Row>()
        val head = hbox(act)
        head.setPadding(dp(4), dp(8), 0, dp(8))
        val hc = vbox(act)
        hc.add(text(act, S(R.string.favorite_songs), Type.TitleMedium, col.content))
        hc.add(text(act, S(R.string.songs_4, songs.size), Type.BodySmall, col.sub))
        head.add(hc, w = 0, weight = 1f)
        head.add(iconButton(act, Icons.Shuffle, S(R.string.shuffle_2), col.content) { Actions.playTracks(songs, 0, true) }, w = 48, h = 48)
        head.add(iconButton(act, Icons.PlayArrow, S(R.string.play), col.content) { Actions.playTracks(songs, 0) }, w = 48, h = 48)
        rows.add(StaticRow(head))
        songs.forEachIndexed { i, t ->
            rows.add(PanelRow(t, t.uri == playing, { Actions.playTracks(songs, i) }, null, Icons.Favorite to { Actions.toggleFav(act, t.uri) }, col.accent))
        }
        favPage.list.setPadding(dp(12), 0, dp(12), dp(24))
        favPage.setRows(rows)
    }

    private var queueScrolled = false

    private fun buildQueue() {
        val q = p.queue
        val rows = ArrayList<Row>()
        val head = hbox(act)
        head.setPadding(dp(8), dp(8), 0, dp(8))
        val hc = vbox(act)
        val after = q.drop(p.index + 1).sumOf { it.track.durationMs }
        val left = after + (p.durationMs - p.positionMs).coerceAtLeast(0)
        hc.add(text(act, S(R.string.queue), Type.TitleMedium, col.content))
        hc.add(text(act, S(R.string.of_left, p.index + 1, q.size, fmtMs(left)), Type.BodySmall, col.sub))
        head.add(hc, w = 0, weight = 1f)
        if (q.isNotEmpty()) head.add(iconButton(act, Icons.PlaylistAdd, S(R.string.save_as_playlist), col.content) { saveQueue() }, w = 48, h = 48)
        if (p.index < q.lastIndex) head.add(iconButton(act, Icons.Delete, S(R.string.clear_upcoming), col.content) {
            val d = UDialog(act).title(S(R.string.clear_songs_after_this_one))
            d.button(S(R.string.cancel)) { d.dismiss() }
            d.button(S(R.string.clear)) { p.clearUpcoming(); d.dismiss() }
            d.show()
        }, w = 48, h = 48)
        rows.add(StaticRow(head))
        q.forEachIndexed { i, it ->
            rows.add(PanelRow(it.track, i == p.index, { p.jumpTo(i) }, { h -> Drag.attach(queuePage.list, h, i + 1) { a, b -> p.move(a - 1, b - 1) } },
                Icons.Close to { p.removeAt(i) }, col.sub))
        }
        queuePage.list.setPadding(dp(8), 0, dp(8), dp(24))
        queuePage.setRows(rows)
        if (!queueScrolled) { queueScrolled = true; queuePage.list.setSelection(p.index.coerceAtLeast(0)) }
    }

    private fun saveQueue() {
        val name = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val d = UDialog(act).title(S(R.string.save_as_playlist)).message(S(R.string.saves_songs_in_queue_except))
        val f = field(act, name, S(R.string.name))
        d.view(f)
        d.button(S(R.string.cancel)) { d.dismiss() }
        d.button(S(R.string.save)) {
            val n = f.text.toString()
            if (n.isNotBlank()) { Actions.createPlaylist(act, n, p.queue.map { it.uri }); d.dismiss() }
        }
        d.show()
    }

    // =====================================================================
    // 音量・スリープタイマー・操作ガイド
    // =====================================================================

    private fun volume() = Volume.sheet(act)

    private fun sleepDialog() {
        val at = p.sleepAtMs
        val left = if (at > 0) ((at - SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(1) else 0L
        val d = UDialog(act).title(S(R.string.sleep_timer))
        d.message(when {
            p.stopAfterTrack -> S(R.string.stops_at_end_of_this)
            left > 0 -> S(R.string.stops_in_about_min, left)
            else -> S(R.string.pauses_playback_after_selected_time)
        }, Pal.primary)
        for (m in listOf(15, 30, 45, 60, 90, 120)) {
            val t = text(act, S(R.string.min_2, m), Type.BodyLarge)
            t.setPadding(dp(8), dp(12), dp(8), dp(12))
            t.tap(8f) { p.setSleep(m); d.dismiss() }
            d.view(t)
        }
        val e = text(act, S(R.string.at_end_of_this_song), Type.BodyLarge)
        e.setPadding(dp(8), dp(12), dp(8), dp(12))
        e.tap(8f) { p.stopAtTrackEnd(true); d.dismiss() }
        d.view(e)
        d.button(S(R.string.close)) { d.dismiss() }
        if (left > 0 || p.stopAfterTrack) d.button(S(R.string.turn_off_timer)) { p.setSleep(0); d.dismiss() }
        d.show()
    }

    private fun guide() {
        val rows = listOf(
            S(R.string.swipe_artwork_left_or_right) to S(R.string.previous_next_song),
            S(R.string.tap_artwork) to S(R.string.show_or_hide_lyrics),
            S(R.string.swipe_screen_left_or_right) to S(R.string.favorites_queue),
            S(R.string.swipe_down) to S(R.string.close_player),
            S(R.string.swipe_up) to S(R.string.sound_settings),
            S(R.string.tap_title) to S(R.string.go_to_album_or_artist),
            S(R.string.swipe_mini_player_left_or) to S(R.string.previous_next_song),
            S(R.string.drag_handle_in_queue) to S(R.string.reorder_songs),
            S(R.string.long_press_song_or_album) to S(R.string.play_next_favorites_playlists_and),
        )
        val d = UDialog(act).title(S(R.string.gesture_guide))
        for ((k, v) in rows) {
            val c = vbox(act)
            c.setPadding(0, dp(6), 0, dp(6))
            c.add(text(act, k, Type.LabelLarge, Pal.primary))
            c.add(text(act, v, Type.BodyMedium, Pal.onSurface, lines = 0))
            d.view(c)
        }
        d.close(S(R.string.close))
        d.show()
    }

    /** 戻るボタン: 左右のページを表示中なら、再生中のページへ */
    fun back(): Boolean {
        if (pager.page != 1) { pager.go(1); return true }
        return false
    }

    fun dispose() {}
}
