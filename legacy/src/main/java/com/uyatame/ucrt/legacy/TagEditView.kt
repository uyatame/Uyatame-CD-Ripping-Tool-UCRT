package com.uyatame.ucrt.legacy

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.ui.Btn
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.MATCH
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.ProgressBarView
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.WRAP
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.addDivider
import com.uyatame.ucrt.legacy.ui.button
import com.uyatame.ucrt.legacy.ui.card
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.enabled
import com.uyatame.ucrt.legacy.ui.field
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.icon
import com.uyatame.ucrt.legacy.ui.iconButton
import com.uyatame.ucrt.legacy.ui.setIcon
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import com.uyatame.ucrt.tags.TagExtra

/** 曲情報の編集画面の 1 曲分(入力中の値。本家の TrackForm) */
class TrackForm(
    val number: String,
    var title: String,
    var artist: String,
    val length: String,
    val detailSupported: Boolean,
    var composer: String = "",
    var lyricist: String = "",
    var comment: String = "",
    var lyrics: String = "",
    /** 何曲目か・何枚目か(入力中の値。空なら書き換えない。CD の画面では使わない) */
    var trackNo: String = "",
    var discNo: String = "",
) {
    var open = false
    val hasDetail: Boolean get() = composer.isNotBlank() || lyricist.isNotBlank() || comment.isNotBlank() || lyrics.isNotBlank()

    fun extra(genre: String): TagExtra =
        if (!detailSupported) TagExtra() else TagExtra(genre.trim(), composer.trim(), lyricist.trim(), comment.trim(), lyrics.trim())
}

/**
 * 曲情報の編集画面の全体(本家の TagForm)。
 * numbers: トラック番号・曲数・ディスク番号・枚数も入力できるか(ライブラリ。CD は目次で決まるので、何枚目かと枚数だけ)
 */
class TagForm(
    var album: String, var albumArtist: String, var date: String, var genre: String, val tracks: List<TrackForm>,
    val numbers: Boolean = false,
    /** 曲数(全体) */
    var trackTotal: String = "",
    /** 何枚目か(CD の画面。ライブラリでは曲ごと) */
    var discNo: String = "",
    /** 枚数(全体) */
    var discTotal: String = "",
)

/** 何曲目かの表示 */
fun trackLabel(n: Int): String = S(R.string.track_n, n)

/** 曲の長さの表示(ミリ秒) */
fun lengthText(ms: Long): String = if (ms <= 0) "" else "%d:%02d".format(ms / 60000, ms / 1000 % 60)

/**
 * 曲情報の編集画面(画面いっぱい。本家の TagEditorPage)。ライブラリと CD の画面で使う。
 * 上の帯に「戻る」と「保存」、その下にアルバムの情報、曲ごとの曲名・アーティストと、開くと詳しい情報
 */
class TagEditView(
    private val act: LegacyActivity,
    title: String,
    private val form: TagForm,
    private val onCancel: () -> Unit,
    private val onSave: () -> Unit,
    private val dateLabel: String = S(R.string.year),
    private val note: String? = null,
    /** ジャケットとその変更のボタン(上に置く) */
    private val cover: View? = null,
) {
    val view: LinearLayout = vbox(act)
    private val progress = ProgressBarView(act, -1f, 4)
    private lateinit var saveBtn: View
    private val list = vbox(act)
    private var genreField: EditText? = null
    private val trackViews = ArrayList<() -> Unit>()

    /** 曲の詳しい情報を読み込んでいる間 */
    var loading = false
        set(v) {
            field = v
            progress.visibility = if (v) View.VISIBLE else View.GONE
            saveBtn.enabled(!v)
            genreField?.isEnabled = !v
            trackViews.forEach { it() }
        }

    init {
        val bar = hbox(act)
        bar.setPadding(dp(4), dp(4), dp(8), dp(4))
        bar.add(iconButton(act, Icons.ArrowBack, S(R.string.cancel), Pal.onSurface) { onCancel() }, w = 48, h = 48)
        bar.add(text(act, title, Type.TitleLarge), w = 0, weight = 1f, l = 4)
        saveBtn = button(act, S(R.string.save), Btn.Filled) { act.hideKeyboard(view); onSave() }
        bar.add(saveBtn, w = WRAP)
        view.add(bar)
        progress.visibility = View.GONE
        view.add(progress)
        val sc = ScrollView(act)
        sc.isFillViewport = true
        list.setPadding(dp(16), dp(8), dp(16), dp(48))
        sc.addView(list)
        view.add(sc, h = 0, weight = 1f)
        build()
    }

    private fun watch(e: EditText, set: (String) -> Unit) {
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { set(s?.toString().orEmpty()) }
        })
    }

    private fun input(value: String, label: String, singleLine: Boolean = true, set: (String) -> Unit): EditText {
        val f = field(act, value, label, singleLine)
        watch(f, set)
        return f
    }

    private fun labeledInput(parent: LinearLayout, value: String, label: String, w: Int = MATCH, weight: Float = 0f, singleLine: Boolean = true, set: (String) -> Unit): EditText {
        val col = vbox(act)
        col.add(text(act, label, Type.LabelMedium, Pal.primary), l = 4, b = 4)
        val f = input(value, label, singleLine, set)
        col.add(f)
        parent.add(col, w = w, weight = weight)
        return f
    }

    /** 番号の欄(数字だけ) */
    private fun numberInput(parent: LinearLayout, value: String, label: String, set: (String) -> Unit) {
        val f = labeledInput(parent, value, label, w = 0, weight = 1f) { set(it.filter { ch -> ch.isDigit() }) }
        f.inputType = android.text.InputType.TYPE_CLASS_NUMBER
    }

    private fun build() {
        cover?.let { list.add(it, b = 8) }
        if (note != null) list.add(text(act, note, Type.BodySmall, Pal.error, lines = 0), b = 8)
        labeledInput(list, form.album, S(R.string.album)) { form.album = it }
        list.add(View(act), h = 8)
        labeledInput(list, form.albumArtist, S(R.string.album_artist)) { form.albumArtist = it; trackViews.forEach { f -> f() } }
        list.add(View(act), h = 8)
        val row = hbox(act, Gravity.TOP)
        labeledInput(row, form.date, dateLabel, w = 0, weight = 1f) { form.date = it }
        row.add(View(act), w = 8, h = 1)
        genreField = labeledInput(row, form.genre, S(R.string.genre), w = 0, weight = 1f) { form.genre = it }
        list.add(row)
        // 曲数・何枚目か・枚数
        list.add(View(act), h = 8)
        val nums = hbox(act, Gravity.TOP)
        if (form.numbers) numberInput(nums, form.trackTotal, S(R.string.tag_track_total)) { form.trackTotal = it }
        else numberInput(nums, form.discNo, S(R.string.tag_disc_no)) { form.discNo = it }
        nums.add(View(act), w = 8, h = 1)
        numberInput(nums, form.discTotal, S(R.string.tag_disc_total)) { form.discTotal = it }
        list.add(nums)
        list.add(View(act), h = 8)
        list.addDivider()
        list.add(text(act, S(R.string.mb_tracks), Type.TitleMedium), t = 8)
        list.add(text(act, S(R.string.tag_detail_hint), Type.BodySmall, Pal.onSurfaceVariant, lines = 0), b = 10)
        form.tracks.forEach { t -> list.add(trackCard(t), b = 10) }
    }

    private fun trackCard(t: TrackForm): View {
        val c = card(act, Pal.containerHighest)
        c.setPadding(dp(12), dp(12), dp(12), dp(12))
        c.add(text(act, t.number + if (t.length.isNotEmpty()) S(R.string.legacy_paren, t.length) else "", Type.LabelLarge, Pal.primary), b = 6)
        // 何曲目か・何枚目か(ライブラリ)
        if (form.numbers) {
            val nr = hbox(act, Gravity.TOP)
            numberInput(nr, t.trackNo, S(R.string.tag_track_no)) { t.trackNo = it }
            nr.add(View(act), w = 8, h = 1)
            numberInput(nr, t.discNo, S(R.string.tag_disc_no)) { t.discNo = it }
            c.add(nr, b = 6)
        }
        labeledInput(c, t.title, S(R.string.title)) { t.title = it }
        c.add(View(act), h = 6)
        val artist = labeledInput(c, t.artist, S(R.string.artist)) { t.artist = it }
        // 詳しい情報(開いたときだけ出す)
        val head = hbox(act)
        head.setPadding(0, dp(10), 0, dp(6))
        val label = text(act, "", Type.BodyMedium, Pal.primary)
        head.add(label, w = 0, weight = 1f)
        val arrow = icon(act, Icons.ArrowDown, Pal.onSurfaceVariant, 20)
        head.add(arrow, w = 20, h = 20)
        c.add(head)
        val detail = vbox(act)
        val r = hbox(act, Gravity.TOP)
        labeledInput(r, t.composer, S(R.string.composer), w = 0, weight = 1f) { t.composer = it }
        r.add(View(act), w = 8, h = 1)
        labeledInput(r, t.lyricist, S(R.string.lyricist), w = 0, weight = 1f) { t.lyricist = it }
        detail.add(r, b = 6)
        labeledInput(detail, t.comment, S(R.string.comment)) { t.comment = it }
        detail.add(View(act), h = 6)
        val ly = labeledInput(detail, t.lyrics, S(R.string.lyrics), singleLine = false) { t.lyrics = it }
        ly.minLines = 4
        ly.maxLines = 14
        if (t.lyrics.trimStart().startsWith("[")) ly.typeface = android.graphics.Typeface.MONOSPACE
        detail.add(text(act, S(R.string.lyrics_hint), Type.BodySmall, Pal.onSurfaceVariant, lines = 0), t = 4)
        c.add(detail)
        val fields = ArrayList<EditText>()
        fun collect(v: View) { if (v is EditText) fields += v else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i)) }
        collect(detail)
        fun update() {
            artist.hint = form.albumArtist
            label.text = when {
                !t.detailSupported -> S(R.string.tag_detail_unsupported)
                t.hasDetail && !t.open -> S(R.string.tag_detail) + S(R.string.legacy_paren, summary(t))
                else -> S(R.string.tag_detail)
            }
            label.setTextColor(if (t.detailSupported) Pal.primary else Pal.onSurfaceVariant)
            arrow.visibility = if (t.detailSupported) View.VISIBLE else View.GONE
            arrow.setIcon(if (t.open) Icons.ArrowUp else Icons.ArrowDown, Pal.onSurfaceVariant, 20)
            detail.visibility = if (t.open && t.detailSupported) View.VISIBLE else View.GONE
            fields.forEach { it.isEnabled = !loading }
        }
        head.tap(8f) { if (t.detailSupported && !loading) { t.open = !t.open; update() } }
        trackViews += { update() }
        update()
        return c
    }

    /** 読み込んだ詳しい情報を入れて作り直す(form の値で) */
    fun reload() {
        trackViews.clear()
        list.removeAllViews()
        build()
        loading = loading
    }

    private fun summary(t: TrackForm): String = buildList {
        if (t.composer.isNotBlank()) add(S(R.string.composer))
        if (t.lyricist.isNotBlank()) add(S(R.string.lyricist))
        if (t.comment.isNotBlank()) add(S(R.string.comment))
        if (t.lyrics.isNotBlank()) add(S(R.string.lyrics))
    }.joinToString(S(R.string.separator).trim().ifEmpty { "・" })
}
