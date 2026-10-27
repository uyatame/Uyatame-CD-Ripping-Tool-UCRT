package com.uyatame.ucrt.legacy.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.SectionIndexer

/**
 * 一覧の 1 行。kind が同じ行は、画面から外れた行の部品を使い回す(長い一覧でも軽いように)。
 * kind が STATIC の行は使い回さない(見出し・操作の欄など)
 */
abstract class Row(val kind: Int) {
    abstract fun create(ctx: Context): View
    abstract fun bind(v: View)
    /** 高速スクロールで出す見出し(頭文字など) */
    open val label: String = ""
    open val clickable: Boolean = false

    companion object {
        const val STATIC = -1
        const val TRACK = 0
        const val ALBUM = 1
        const val TILES = 2
        const val GROUP = 3
        const val HEADING = 4
        const val PANEL = 5
        const val FOLDER = 6
        const val CANDIDATE = 7
        const val OTHER = 8
        const val KINDS = 9
    }
}

/** 作った部品をそのまま出す行 */
class StaticRow(private val v: View, override val label: String = "") : Row(STATIC) {
    override fun create(ctx: Context): View = v
    override fun bind(v: View) {}
}

/** 一覧の中身(行の並び)。高速スクロールの見出しも出す */
class RowAdapter : BaseAdapter(), SectionIndexer {
    var rows: List<Row> = emptyList()
        set(v) { field = v; buildSections(); notifyDataSetChanged() }
    private var sections: Array<String> = emptyArray()
    private var sectionStart = IntArray(0)
    private var sectionOf = IntArray(0)

    private fun buildSections() {
        val names = ArrayList<String>()
        val starts = ArrayList<Int>()
        val of = IntArray(rows.size)
        var last: String? = null
        rows.forEachIndexed { i, r ->
            val l = r.label
            if (l.isNotEmpty() && l != last) { names.add(l); starts.add(i); last = l }
            of[i] = (names.size - 1).coerceAtLeast(0)
        }
        sections = names.toTypedArray()
        sectionStart = starts.toIntArray()
        sectionOf = of
    }

    override fun getCount() = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = Row.KINDS
    override fun getItemViewType(position: Int) = rows[position].kind.let { if (it == Row.STATIC) android.widget.Adapter.IGNORE_ITEM_VIEW_TYPE else it }
    override fun areAllItemsEnabled() = false
    override fun isEnabled(position: Int) = false
    override fun hasStableIds() = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val r = rows[position]
        val v = if (r.kind != Row.STATIC && convertView != null && convertView.getTag(KIND_TAG) == r.kind) convertView
        else r.create(parent.context).also { it.setTag(KIND_TAG, r.kind) }
        r.bind(v)
        return v
    }

    override fun getSections(): Array<Any> = sections.map { it as Any }.toTypedArray()
    override fun getPositionForSection(s: Int): Int = sectionStart.getOrElse(s.coerceIn(0, (sectionStart.size - 1).coerceAtLeast(0))) { 0 }
    override fun getSectionForPosition(p: Int): Int = sectionOf.getOrElse(p) { 0 }

    companion object {
        /** 部品に付ける印(行の種類) */
        val KIND_TAG = com.uyatame.ucrt.R.id.row_kind
    }
}

/**
 * 一覧(ListView)と「上へ戻る」ボタン。区切り線なし・押したときの色なし(行ごとに付ける)
 */
class ListPage(ctx: Context, fast: Boolean = false) : FrameLayout(ctx) {
    val list = ListView(ctx)
    val adapter = RowAdapter()
    private val top: View

    init {
        list.divider = null
        list.dividerHeight = 0
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.cacheColorHint = Color.TRANSPARENT
        list.isVerticalScrollBarEnabled = !fast
        list.isFastScrollEnabled = fast
        list.clipToPadding = false
        list.adapter = adapter
        addView(list, LayoutParams(MATCH, MATCH))
        // 上へ戻るボタン(本家の ScrollTopButton)
        top = icon(ctx, Icons.ArrowUp, Pal.onSecondaryContainer, 24)
        top.bgd(pressBg(circle(Pal.secondaryContainer), 24f, oval = true))
        top.isClickable = true
        top.setOnClickListener { list.setSelection(0) }
        top.visibility = View.GONE
        addView(top, LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16)) })
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, s: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                val show = first > 8
                if ((top.visibility == View.VISIBLE) != show) top.visibility = if (show) View.VISIBLE else View.GONE
            }
        })
    }

    fun setRows(rows: List<Row>) { adapter.rows = rows }

    /** 下の余白(帯に隠れないように) */
    fun padBottom(dpv: Int) { list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, dp(dpv)) }

    /** スクロールの位置(戻ってきたときに戻すため) */
    fun position(): IntArray {
        val c = list.getChildAt(0)
        return intArrayOf(list.firstVisiblePosition, c?.top ?: 0)
    }

    fun restore(p: IntArray?) {
        if (p == null) return
        list.post { list.setSelectionFromTop(p[0], p[1]) }
    }
}
