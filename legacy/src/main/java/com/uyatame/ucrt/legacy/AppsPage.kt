package com.uyatame.ucrt.legacy

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.compat.Receivers
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.bgd
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.pressBg
import com.uyatame.ucrt.legacy.ui.rounded
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import java.text.Collator
import java.util.Locale

/**
 * 「アプリ」タブ(UCRT Legacy をホーム画面として使うときだけ、下のタブに出る)。ランチャーに出るアプリを、名前順の格子で出す。
 * タップで起動、長押しでアプリの情報。上の「端末の設定」で、ホーム画面にしたあとでも設定に戻れる。
 *
 * 古い端末でも重くならないように:
 * - 一覧は裏のスレッドで読み、持つのは名前と ComponentName だけ(全アプリのアイコンは持たない)
 * - アイコンは、画面に出たセルの分だけ裏のスレッドで読み、最近の 48 件だけ覚えておく。画面を離れたら全部捨てる
 */
class AppsPage(act: LegacyActivity) : Page(act) {
    private class Entry(val label: String, val component: ComponentName) {
        val key: String = component.flattenToShortString()
    }

    private val root = vbox(act)
    override val view: View get() = root
    override val mask: Int = Bus.SETTINGS
    private val search = EditText(act)
    private val grid = GridView(act)
    private val empty = text(act, S(R.string.apps_loading), Type.BodyLarge, Pal.onSurfaceVariant)
    private val adapter = AppsAdapter()
    private var all = emptyList<Entry>()
    /** 読み込みの番号(新しい読み込みを始めたら、古い読み込みの結果は捨てる) */
    private var gen = 0
    @Volatile private var disposed = false

    /** 読んだアイコン(最近の 48 件)。メインのスレッドだけで触る */
    private val icons = android.util.LruCache<String, Drawable>(48)
    /** 読み込み中のアイコン(同じアイコンを二重に読まない) */
    private val loading = HashSet<String>()
    /** アイコンを読むスレッド(1 本だけ。画面を離れたら止める) */
    private val iconPool = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // アプリが更新されるとアイコンも変わることがあるので、覚えたものは捨てて読み直す
            icons.evictAll()
            load()
        }
    }

    init {
        val head = hbox(act)
        head.setPadding(dp(20), dp(16), dp(12), dp(8))
        head.addView(text(act, S(R.string.apps), Type.TitleLarge, Pal.onSurface), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settings = text(act, S(R.string.apps_device_settings), Type.LabelLarge, Pal.primary)
        settings.setPadding(dp(12), dp(8), dp(12), dp(8))
        settings.tap { openDeviceSettings() }
        head.addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        search.hint = S(R.string.apps_search)
        search.setSingleLine(true)
        search.setTextColor(Pal.onSurface)
        search.setHintTextColor(Pal.onSurfaceVariant)
        search.setPadding(dp(14), dp(8), dp(14), dp(8))
        search.bgd(rounded(Pal.surfaceVariant, 12f))
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(dp(16), 0, dp(16), dp(8))
        })

        val body = FrameLayout(act)
        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = dp(88)
        grid.horizontalSpacing = dp(4)
        grid.verticalSpacing = dp(8)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.gravity = Gravity.CENTER
        grid.clipToPadding = false
        grid.setPadding(dp(8), dp(8), dp(8), dp(24))
        grid.adapter = adapter
        grid.emptyView = empty
        body.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        empty.gravity = Gravity.CENTER
        body.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter(s?.toString().orEmpty())
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        grid.setOnItemClickListener { _, _, position, _ -> adapter.items.getOrNull(position)?.let { launch(it) } }
        grid.setOnItemLongClickListener { _, _, position, _ -> adapter.items.getOrNull(position)?.let { openInfo(it) }; true }

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        Receivers.register(act, packageReceiver, f)
        load()
    }

    private fun load() {
        val my = ++gen
        Thread({
            val list = runCatching { read() }.getOrDefault(emptyList())
            act.runOnUiThread {
                if (disposed || my != gen) return@runOnUiThread
                all = list
                empty.text = S(R.string.apps_none)
                filter(search.text?.toString().orEmpty())
            }
        }, "ucrt-apps").start()
    }

    /** ランチャーに出るアプリを、名前順に(UCRT Legacy 自身は除く)。裏のスレッドで呼ぶ */
    private fun read(): List<Entry> {
        val pm = act.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val entries = runCatching { pm.queryIntentActivities(query, 0) }.getOrDefault(emptyList()).mapNotNull { r ->
            val a = r.activityInfo ?: return@mapNotNull null
            if (a.packageName == act.packageName) return@mapNotNull null
            val label = runCatching { r.loadLabel(pm).toString() }.getOrDefault(a.packageName)
            Entry(label, ComponentName(a.packageName, a.name))
        }.distinctBy { it.key }
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        return entries.sortedWith(java.util.Comparator { a, b ->
            val c = collator.compare(a.label, b.label)
            if (c != 0) c else a.key.compareTo(b.key)
        })
    }

    private fun filter(raw: String) {
        val q = raw.trim().lowercase(Locale.getDefault())
        adapter.items = if (q.isEmpty()) all else all.filter {
            it.label.lowercase(Locale.getDefault()).contains(q) || it.component.packageName.lowercase(Locale.US).contains(q)
        }
        adapter.notifyDataSetChanged()
    }

    /** アイコンを裏のスレッドで読み、読めたら、まだそのアプリを出しているセルにだけ入れる */
    private fun loadIcon(e: Entry, holder: Holder) {
        if (!loading.add(e.key)) return
        val pm = act.packageManager
        runCatching {
            iconPool.execute {
                val d = runCatching { pm.getActivityIcon(e.component) }.getOrNull() ?: runCatching { pm.defaultActivityIcon }.getOrNull()
                act.runOnUiThread {
                    loading.remove(e.key)
                    if (disposed || d == null) return@runOnUiThread
                    icons.put(e.key, d)
                    if (holder.key == e.key) holder.icon.setImageDrawable(d)
                }
            }
        }.onFailure { loading.remove(e.key) }
    }

    private fun launch(e: Entry) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(e.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { act.startActivity(i) }.onFailure { act.snack(S(R.string.apps_cannot_launch)) }
    }

    private fun openInfo(e: Entry) {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.component.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { act.startActivity(i) }.onFailure { act.snack(S(R.string.apps_cannot_launch)) }
    }

    /** 端末の設定を開く(ホーム画面にしたあとでも、設定に戻れるように) */
    private fun openDeviceSettings() {
        runCatching { act.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { act.snack(S(R.string.apps_cannot_open_settings)) }
    }

    override fun dispose() {
        disposed = true
        runCatching { act.unregisterReceiver(packageReceiver) }
        iconPool.shutdownNow()
        icons.evictAll()
        loading.clear()
    }

    override fun scrollState(): IntArray = intArrayOf(grid.firstVisiblePosition)
    override fun restoreScroll(p: IntArray?) { p?.getOrNull(0)?.let { pos -> grid.post { grid.setSelection(pos) } } }

    private inner class AppsAdapter : BaseAdapter() {
        var items: List<Entry> = emptyList()
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()
        override fun hasStableIds(): Boolean = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell: LinearLayout
            val holder: Holder
            if (convertView is LinearLayout && convertView.tag is Holder) {
                cell = convertView
                holder = convertView.tag as Holder
            } else {
                cell = vbox(act, Gravity.CENTER)
                cell.minimumHeight = dp(92)
                cell.setPadding(dp(4), dp(6), dp(4), dp(6))
                cell.bgd(pressBg(rounded(0x00000000, 12f), 12f))
                val image = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                val label = text(act, "", Type.LabelMedium, Pal.onSurface, lines = 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
                cell.addView(image, LinearLayout.LayoutParams(dp(52), dp(52)))
                cell.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                holder = Holder(image, label)
                cell.tag = holder
            }
            val e = items[position]
            // 使い回したセルに、前のアプリのアイコンが残らないようにする
            holder.key = e.key
            val cached = icons.get(e.key)
            holder.icon.setImageDrawable(cached)
            if (cached == null) loadIcon(e, holder)
            holder.label.text = e.label
            cell.contentDescription = e.label
            return cell
        }
    }

    private class Holder(val icon: ImageView, val label: TextView) {
        /** このセルが今出しているアプリ */
        var key: String? = null
    }

    companion object {
        /** UCRT Legacy が今の既定のホーム画面(ホームボタンで開かれるアプリ)か */
        fun isDefaultHome(ctx: Context): Boolean {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val ri = runCatching { ctx.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
            return ri?.activityInfo?.packageName == ctx.packageName
        }
    }
}
