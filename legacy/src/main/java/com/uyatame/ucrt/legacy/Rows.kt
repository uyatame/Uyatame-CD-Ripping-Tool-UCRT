package com.uyatame.ucrt.legacy

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.library.LibAlbum
import com.uyatame.ucrt.library.LibTrack
import com.uyatame.ucrt.library.Quality
import com.uyatame.ucrt.library.QualityClass
import com.uyatame.ucrt.legacy.ui.ArtView
import com.uyatame.ucrt.legacy.ui.Btn
import com.uyatame.ucrt.legacy.ui.CheckView
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.MATCH
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.Row
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.badge
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.bold
import com.uyatame.ucrt.legacy.ui.button
import com.uyatame.ucrt.legacy.ui.circle
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.icon
import com.uyatame.ucrt.legacy.ui.iconButton
import com.uyatame.ucrt.legacy.ui.pressBg
import com.uyatame.ucrt.legacy.ui.rounded
import com.uyatame.ucrt.legacy.ui.setIcon
import com.uyatame.ucrt.legacy.ui.style
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox

// =====================================================================
// 一覧の行(本家の TrackRow・AlbumTile・AlbumListRow・GroupRow・ListHeading など)
// =====================================================================

/** 曲の行。長押し(または右端のボタン)で操作メニューを開く */
class TrackRowItem(
    private val act: LegacyActivity,
    val t: LibTrack,
    private val onClick: () -> Unit,
    private val number: String? = null,
    private val showArt: Boolean = true,
    private val sub: String? = null,
    private val trailing: String? = null,
    private val playlist: Pair<String, Int>? = null,
    private val handle: ((View) -> Unit)? = null,
    override val label: String = "",
    /** プレイリストの「選んで並べ替え」で選んでいるか(null なら選ぶモードではない) */
    private val pick: Boolean? = null,
    private val onPick: (() -> Unit)? = null,
) : Row(TRACK) {
    private class H(
        val root: LinearLayout, val handle: ImageView, val check: CheckView, val num: TextView, val artBox: FrameLayout, val art: ArtView,
        val title: TextView, val badge: TextView, val sub: TextView, val trail: TextView, val more: View, val bars: ImageView,
    )

    override fun create(ctx: Context): View {
        val row = hbox(ctx)
        row.setPadding(dp(8), dp(6), 0, dp(6))
        val hd = icon(ctx, Icons.DragHandle, Pal.onSurfaceVariant)
        row.add(hd, w = 40, h = 40)
        val ck = CheckView(ctx, false, null)
        ck.isClickable = false
        row.add(ck, w = 40, h = 40, r = 4)
        val num = text(ctx, "", Type.LabelLarge, Pal.onSurfaceVariant)
        num.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val bars = icon(ctx, Icons.Bars, Pal.primary, 18)
        val numBox = FrameLayout(ctx)
        numBox.add(num, w = MATCH, h = MATCH)
        numBox.add(bars, w = MATCH, h = MATCH)
        row.add(numBox, w = 30, h = 40)
        val artBox = FrameLayout(ctx)
        val art = ArtView(ctx, 6f)
        artBox.add(art, w = 48, h = 48)
        row.add(artBox, w = 48, h = 48)
        val col = vbox(ctx)
        val title = text(ctx, "", Type.BodyLarge)
        col.add(title)
        val line = hbox(ctx)
        val bd = badge(ctx, "", false)
        line.add(bd, w = WRAP, r = 6)
        val sb = text(ctx, "", Type.BodySmall, Pal.onSurfaceVariant)
        line.add(sb, w = 0, weight = 1f)
        col.add(line)
        row.add(col, w = 0, weight = 1f, l = 12)
        val tr = text(ctx, "", Type.LabelMedium, Pal.onSurfaceVariant)
        row.add(tr, w = WRAP, l = 8)
        val more = iconButton(ctx, Icons.MoreVert, S(R.string.more_options)) {}
        row.add(more, w = 48, h = 48)
        row.tag = H(row, hd, ck, num, artBox, art, title, bd, sb, tr, more, bars)
        return row
    }

    override fun bind(v: View) {
        val h = v.tag as H
        val playing = LegacyApp.app.player.current?.uri == t.uri
        val sel: Boolean? = pick ?: if (act.selecting) t.uri in act.selected else null
        h.root.bgd(pressBg(if (playing) rounded(Pal.secondaryContainer, 12f) else null, 12f))
        val toggle = { if (pick != null) onPick?.invoke() else act.toggleSelected(t.uri) }
        h.root.setOnClickListener { if (sel != null) toggle() else onClick() }
        h.root.setOnLongClickListener { if (sel != null) toggle() else Actions.trackMenu(act, t, playlist); true }
        h.handle.visibility = if (handle != null && pick == null) View.VISIBLE else View.GONE
        handle?.invoke(h.handle)
        h.check.visibility = if (sel != null) View.VISIBLE else View.GONE
        h.check.checked = sel == true
        val numBox = h.num.parent as View
        if (number != null) {
            numBox.visibility = View.VISIBLE
            h.artBox.visibility = View.GONE
            h.num.text = number
            h.num.visibility = if (playing) View.INVISIBLE else View.VISIBLE
            h.bars.visibility = if (playing) View.VISIBLE else View.GONE
        } else if (showArt) {
            numBox.visibility = View.GONE
            h.artBox.visibility = View.VISIBLE
            h.art.enabledArt = LegacyApp.app.prefs.listArt
            h.art.bind(Actions.artRef(act, t), dp(48))
            h.art.playing = playing
        } else {
            numBox.visibility = View.GONE
            h.artBox.visibility = View.GONE
        }
        h.title.text = t.title
        h.title.style(Type.BodyLarge, if (playing) Pal.onSecondaryContainer else Pal.onSurface)
        if (playing) h.title.bold()
        val b = Quality.badge(t)
        h.badge.text = b
        h.badge.visibility = if (b.isEmpty()) View.GONE else View.VISIBLE
        val hi = Quality.classOf(t) == QualityClass.HiRes
        h.badge.setTextColor(if (hi) Pal.HIRES_INK else Pal.onSurfaceVariant)
        h.badge.bgd(if (hi) rounded(Pal.HIRES_GOLD, 4f) else rounded(0, 4f, Pal.alpha(Pal.onSurfaceVariant, 0.6f), 0.8f))
        h.sub.text = sub ?: t.artist
        h.trail.text = trailing ?: fmtMs(t.durationMs)
        h.more.setOnClickListener { Actions.trackMenu(act, t, playlist) }
    }
}

/** アルバムのタイルを横に並べた 1 行(本家の AlbumTile のグリッド) */
class TilesRow(
    private val act: LegacyActivity,
    private val albums: List<LibAlbum>,
    private val cols: Int,
    private val subOf: ((LibAlbum) -> String)? = null,
    override val label: String = "",
) : Row(TILES) {
    override fun create(ctx: Context): View {
        val row = hbox(ctx, Gravity.TOP)
        row.setPadding(dp(16), 0, dp(16), dp(16))
        for (i in 0 until MAX) {
            val t = tile(ctx)
            row.add(t, w = 0, weight = 1f, r = 0)
        }
        return row
    }

    override fun bind(v: View) {
        val row = v as LinearLayout
        for (i in 0 until MAX) {
            val t = row.getChildAt(i)
            val lp = t.layoutParams as LinearLayout.LayoutParams
            lp.rightMargin = if (i < cols - 1) dp(12) else 0
            if (i >= cols) { t.visibility = View.GONE; continue }
            val a = albums.getOrNull(i)
            if (a == null) { t.visibility = View.INVISIBLE; continue }
            t.visibility = View.VISIBLE
            val w = v.resources.displayMetrics.widthPixels - dp(32 + 12 * (cols - 1))
            bindTile(act, t, a, subOf?.invoke(a), w / cols)
        }
    }

    companion object {
        const val MAX = 6

        fun tile(ctx: Context): View {
            val col = vbox(ctx)
            val box = FrameLayout(ctx)
            val art = ArtView(ctx, 12f)
            box.add(art, w = MATCH, h = WRAP)
            val bd = badge(ctx, "", true)
            box.add(bd, w = WRAP, h = WRAP, l = 6, t = 6, gravity = Gravity.TOP or Gravity.START)
            val play = icon(ctx, Icons.PlayArrow, Pal.onPrimary, 22)
            play.bgd(pressBg(circle(Pal.primary), 19f, Pal.alpha(Pal.onPrimary, 0.2f), oval = true))
            play.isClickable = true
            box.add(play, w = 38, h = 38, r = 8, b = 8, gravity = Gravity.BOTTOM or Gravity.END)
            col.add(box)
            col.add(text(ctx, "", Type.TitleSmall), l = 4, t = 8)
            col.add(text(ctx, "", Type.BodySmall, Pal.onSurfaceVariant), l = 4, b = 4)
            return col
        }

        fun bindTile(act: LegacyActivity, t: View, a: LibAlbum, sub: String?, px: Int) {
            val col = t as LinearLayout
            val box = col.getChildAt(0) as FrameLayout
            val art = box.getChildAt(0) as ArtView
            val bd = box.getChildAt(1) as TextView
            val play = box.getChildAt(2)
            art.enabledArt = LegacyApp.app.prefs.listArt
            art.bind(Actions.albumArt(a), px)
            val hi = Quality.classOf(a) == QualityClass.HiRes
            bd.text = Quality.badge(a)
            bd.visibility = if (hi && bd.text.isNotEmpty()) View.VISIBLE else View.GONE
            play.setOnClickListener { Actions.playAlbum(a, 0) }
            (col.getChildAt(1) as TextView).text = a.title
            (col.getChildAt(2) as TextView).text = sub ?: a.artist
            col.bgd(pressBg(null, 12f))
            col.isClickable = true
            col.setOnClickListener { act.libPush("album:" + a.key) }
            col.setOnLongClickListener { Actions.albumMenu(act, a); true }
        }
    }
}

/** アルバムの行(リスト表示。本家の AlbumListRow) */
class AlbumRowItem(private val act: LegacyActivity, val a: LibAlbum, private val sub: String? = null, override val label: String = "", private val indentDp: Int = 0) : Row(ALBUM) {
    override fun create(ctx: Context): View {
        val outer = FrameLayout(ctx)
        val row = hbox(ctx)
        row.setPadding(dp(8), dp(6), dp(8), dp(6))
        row.add(ArtView(ctx, 8f), w = 56, h = 56, r = 14)
        val col = vbox(ctx)
        col.add(text(ctx, "", Type.BodyLarge))
        val line = hbox(ctx)
        line.add(badge(ctx, "", false), w = WRAP, r = 6)
        line.add(text(ctx, "", Type.BodySmall, Pal.onSurfaceVariant), w = 0, weight = 1f)
        col.add(line)
        row.add(col, w = 0, weight = 1f)
        row.add(iconButton(ctx, Icons.PlayArrow, S(R.string.play), Pal.onSurfaceVariant) {}, w = 48, h = 48)
        outer.add(row, h = WRAP)
        return outer
    }

    override fun bind(v: View) {
        val outer = v as FrameLayout
        val row = outer.getChildAt(0) as LinearLayout
        (row.layoutParams as FrameLayout.LayoutParams).leftMargin = dp(indentDp)
        val art = row.getChildAt(0) as ArtView
        art.enabledArt = LegacyApp.app.prefs.listArt
        art.bind(Actions.albumArt(a), dp(56))
        val col = row.getChildAt(1) as LinearLayout
        (col.getChildAt(0) as TextView).text = a.title
        val line = col.getChildAt(1) as LinearLayout
        val bd = line.getChildAt(0) as TextView
        val hi = Quality.classOf(a) == QualityClass.HiRes
        bd.text = Quality.badge(a)
        bd.visibility = if (bd.text.isEmpty()) View.GONE else View.VISIBLE
        bd.setTextColor(if (hi) Pal.HIRES_INK else Pal.onSurfaceVariant)
        bd.bgd(if (hi) rounded(Pal.HIRES_GOLD, 4f) else rounded(0, 4f, Pal.alpha(Pal.onSurfaceVariant, 0.6f), 0.8f))
        (line.getChildAt(1) as TextView).text = sub ?: (a.artist + S(R.string.songs_5, a.tracks.size))
        row.getChildAt(2).setOnClickListener { Actions.playAlbum(a, 0) }
        row.bgd(pressBg(null, 12f))
        row.isClickable = true
        row.setOnClickListener { act.libPush("album:" + a.key) }
        row.setOnLongClickListener { Actions.albumMenu(act, a); true }
    }
}

/** 頭のアイコンの種類(本家の RoundIcon・InitialAvatar・ジャケット) */
sealed class Lead {
    class Round(val d: String, val sizeDp: Int = 48) : Lead()
    class Initial(val name: String, val sizeDp: Int = 48) : Lead()
    class Art(val ref: ArtRef?, val corner: Float) : Lead()
}

/** カテゴリーの行(アーティスト・ジャンル・年・フォルダなど。本家の GroupRow) */
class GroupRowItem(
    private val title: String, private val sub: String, private val lead: Lead, override val label: String = "",
    private val trailing: ((LinearLayout) -> Unit)? = null, private val onClick: () -> Unit,
) : Row(GROUP) {
    override fun create(ctx: Context): View {
        val row = hbox(ctx)
        row.setPadding(dp(8), dp(8), dp(8), dp(8))
        val lead = FrameLayout(ctx)
        row.add(lead, w = 48, h = 48, r = 14)
        val col = vbox(ctx)
        col.add(text(ctx, "", Type.BodyLarge))
        col.add(text(ctx, "", Type.BodySmall, Pal.onSurfaceVariant))
        row.add(col, w = 0, weight = 1f)
        val tr = hbox(ctx)
        row.add(tr, w = WRAP)
        return row
    }

    override fun bind(v: View) {
        val row = v as LinearLayout
        val leadBox = row.getChildAt(0) as FrameLayout
        leadBox.removeAllViews()
        leadView(row.context, lead, leadBox)
        val col = row.getChildAt(1) as LinearLayout
        (col.getChildAt(0) as TextView).text = title
        val s = col.getChildAt(1) as TextView
        s.text = sub
        s.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        val tr = row.getChildAt(2) as LinearLayout
        tr.removeAllViews()
        trailing?.invoke(tr)
        row.bgd(pressBg(null, 12f))
        row.isClickable = true
        row.setOnClickListener { onClick() }
    }

    companion object {
        fun leadView(ctx: Context, lead: Lead, box: FrameLayout) {
            when (lead) {
                is Lead.Round -> {
                    val c = FrameLayout(ctx)
                    c.bgd(circle(Pal.secondaryContainer))
                    c.add(icon(ctx, lead.d, Pal.onSecondaryContainer, lead.sizeDp / 2), w = lead.sizeDp / 2, h = lead.sizeDp / 2, gravity = Gravity.CENTER)
                    box.add(c, w = lead.sizeDp, h = lead.sizeDp, gravity = Gravity.CENTER)
                }
                is Lead.Initial -> {
                    val t = text(ctx, lead.name.trim().take(1).uppercase().ifEmpty { "?" }, Type.TitleMedium, Pal.onTertiaryContainer)
                    t.bold()
                    t.gravity = Gravity.CENTER
                    t.bgd(circle(Pal.tertiaryContainer))
                    box.add(t, w = lead.sizeDp, h = lead.sizeDp, gravity = Gravity.CENTER)
                }
                is Lead.Art -> {
                    val a = ArtView(ctx, lead.corner)
                    a.enabledArt = LegacyApp.app.prefs.listArt
                    box.add(a, w = 48, h = 48)
                    if (lead.ref != null) a.bind(lead.ref, dp(48)) else a.bind(null)
                }
            }
        }
    }
}

/** 区切りの見出し(右側に「すべて表示」などを置ける。本家の ListHeading) */
fun headingView(ctx: Context, s: String, action: String? = null, padStart: Int = 4, onAction: (() -> Unit)? = null): View {
    val row = hbox(ctx)
    row.setPadding(dp(padStart), dp(12), 0, dp(4))
    row.add(text(ctx, s, Type.TitleMedium).bold(), w = 0, weight = 1f)
    if (action != null && onAction != null) row.add(button(ctx, action, Btn.Text, onClick = onAction), w = WRAP)
    return row
}

/** 再生・シャッフルの 2 つのボタン(本家の PlayShuffleButtons) */
fun playShuffle(ctx: Context, onPlay: () -> Unit, onShuffle: () -> Unit): View {
    val row = hbox(ctx)
    row.add(button(ctx, S(R.string.play), Btn.Filled, Icons.PlayArrow, onPlay), w = WRAP, r = 12)
    row.add(button(ctx, S(R.string.shuffle_2), Btn.Tonal, Icons.Shuffle, onShuffle), w = WRAP)
    return row
}

/** 何も無いときの表示(本家の EmptyState) */
fun emptyState(ctx: Context, d: String, title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null): View {
    val col = vbox(ctx, Gravity.CENTER_HORIZONTAL)
    col.setPadding(dp(24), dp(48), dp(24), dp(48))
    val c = FrameLayout(ctx)
    c.bgd(circle(Pal.secondaryContainer))
    c.add(icon(ctx, d, Pal.onSecondaryContainer, 52), w = 52, h = 52, gravity = Gravity.CENTER)
    col.add(c, w = 104, h = 104, b = 12)
    val t = text(ctx, title, Type.TitleLarge, Pal.onSurface, lines = 0)
    t.gravity = Gravity.CENTER
    col.add(t, b = 12)
    if (body.isNotEmpty()) {
        val b = text(ctx, body, Type.BodyMedium, Pal.onSurfaceVariant, lines = 0)
        b.gravity = Gravity.CENTER
        col.add(b, b = 12)
    }
    if (action != null && onAction != null) col.add(button(ctx, action, Btn.Tonal, onClick = onAction), w = WRAP)
    return col
}

/** 戻るボタン付きの見出し(本家の PageTop) */
fun pageTop(act: LegacyActivity, title: String, sub: String? = null, onBack: () -> Unit = { act.libPop() }, actions: (LinearLayout) -> Unit = {}): View {
    val row = hbox(act)
    row.setPadding(dp(4), dp(4), dp(4), dp(4))
    row.add(iconButton(act, Icons.ArrowBack, S(R.string.back), Pal.onSurface) { onBack() }, w = 48, h = 48)
    val col = vbox(act)
    col.add(text(act, title, Type.TitleLarge))
    if (!sub.isNullOrEmpty()) col.add(text(act, sub, Type.BodySmall, Pal.onSurfaceVariant))
    row.add(col, w = 0, weight = 1f, l = 4)
    val acts = hbox(act)
    actions(acts)
    row.add(acts, w = WRAP)
    return row
}
