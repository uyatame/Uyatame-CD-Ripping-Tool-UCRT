package com.uyatame.ucrt.legacy

import android.os.Build
import android.os.Environment
import android.view.View
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.compat.Api19
import com.uyatame.ucrt.legacy.ui.Icons
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.add
import com.uyatame.ucrt.legacy.ui.dp
import com.uyatame.ucrt.legacy.ui.hbox
import com.uyatame.ucrt.legacy.ui.icon
import com.uyatame.ucrt.legacy.ui.tap
import com.uyatame.ucrt.legacy.ui.text
import com.uyatame.ucrt.legacy.ui.vbox
import java.io.File

/**
 * フォルダを選ぶ(Android 4.4 より前は、本家が使う「フォルダを選ぶ画面」が無いので、自分で中をたどって選ぶ)
 */
object FolderPicker {
    /** 保存場所(内部ストレージ・SD カード)のいちばん上のフォルダ */
    fun roots(act: LegacyActivity): List<File> {
        val out = LinkedHashMap<String, File>()
        val primary = Environment.getExternalStorageDirectory()
        out[primary.absolutePath] = primary
        if (Build.VERSION.SDK_INT >= 19) {
            for (d in runCatching { Api19.externalDirs(act) }.getOrDefault(emptyList())) {
                val p = d.absolutePath
                val i = p.indexOf("/Android/data/")
                if (i > 0) { val r = File(p.substring(0, i)); out.putIfAbsent0(r.absolutePath, r) }
            }
        }
        for (base in listOf("/storage", "/mnt")) {
            File(base).listFiles()?.forEach { f ->
                if (f.isDirectory && f.canRead() && f.name !in setOf("emulated", "self", "asec", "obb", "secure", "shell", "user", "runtime")) {
                    if ((f.listFiles()?.isNotEmpty()) == true) out.putIfAbsent0(f.absolutePath, f)
                }
            }
        }
        return out.values.toList()
    }

    private fun <K, V> LinkedHashMap<K, V>.putIfAbsent0(k: K, v: V) { if (!containsKey(k)) put(k, v) }

    /** start から選び始める(null なら「音楽」のフォルダ) */
    fun pick(act: LegacyActivity, start: String?, onPick: (String) -> Unit) {
        val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        var cur: File? = start?.let { File(it) }?.takeIf { it.isDirectory } ?: music.takeIf { it.isDirectory } ?: Environment.getExternalStorageDirectory()
        val d = UDialog(act).title(S(R.string.legacy_choose_folder))
        val pathView = text(act, "", Type.BodySmall, Pal.primary, lines = 2)
        d.view(pathView)
        val box = vbox(act)
        d.view(box)
        fun row(ic: String, label: String, onClick: () -> Unit): View {
            val r = hbox(act)
            r.setPadding(dp(4), dp(12), dp(4), dp(12))
            r.add(icon(act, ic, Pal.onSurfaceVariant), w = 24, h = 24, r = 16)
            r.add(text(act, label, Type.BodyLarge), w = 0, weight = 1f)
            r.tap(8f) { onClick() }
            return r
        }
        lateinit var show: () -> Unit
        show = {
            box.removeAllViews()
            val c = cur
            if (c == null) {
                pathView.text = S(R.string.legacy_storage)
                for (r in roots(act)) box.add(row(Icons.Smartphone, r.absolutePath) { cur = r; show() })
            } else {
                pathView.text = c.absolutePath
                val isRoot = roots(act).any { it.absolutePath == c.absolutePath }
                box.add(row(Icons.ArrowUp, "..") { cur = if (isRoot) null else c.parentFile; show() })
                val dirs = c.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty()
                for (sub in dirs) box.add(row(Icons.Folder, sub.name) { cur = sub; show() })
                if (dirs.isEmpty()) box.add(text(act, S(R.string.legacy_no_subfolders), Type.BodySmall, Pal.onSurfaceVariant).also { it.setPadding(dp(4), dp(8), 0, dp(8)) })
            }
        }
        show()
        d.button(S(R.string.cancel)) { d.dismiss() }
        d.button(S(R.string.legacy_use_this_folder)) {
            val c = cur ?: return@button
            d.dismiss()
            onPick(c.absolutePath)
        }
        d.show()
    }
}
