package com.uyatame.ucrt.legacy

import android.content.Intent
import android.net.Uri
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.ui.Btn
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.button
import com.uyatame.ucrt.meta.UpdateCheck

/** 新しい版が出ていないか確かめる(本家の checkUpdate・UpdateDialog) */
object Update {
    @Volatile var checking = false; private set
    private var checked = false

    fun check(act: LegacyActivity, manual: Boolean) {
        val p = LegacyApp.app.prefs
        if (checking || (!manual && (checked || !p.updateCheck))) return
        checked = true
        checking = true
        Bus.emit(Bus.SETTINGS)
        val cur = SettingsPages.version(act)
        Thread({
            // UCRT Legacy のリリース(legacy-v…)だけを見る(本家のリリースは Android 6.0 から)
            val r = runCatching { UpdateCheck.latestLegacy(cur) }.getOrNull()
            act.runOnUiThread {
                checking = false
                Bus.emit(Bus.SETTINGS)
                val newer = r != null && UpdateCheck.isNewer(r.version, cur)
                when {
                    newer && (manual || r!!.version != p.updateSkip) -> dialog(act, r!!, cur)
                    manual && r == null -> act.snack(S(R.string.couldnt_check_for_updates_check))
                    manual -> act.snack(S(R.string.youre_up_to_date_v, cur))
                }
            }
        }, "ucrt-update").start()
    }

    private fun dialog(act: LegacyActivity, r: com.uyatame.ucrt.meta.ReleaseInfo, cur: String) {
        val d = UDialog(act).title(S(R.string.update_available))
        d.message(S(R.string.version_is_available_current_version, r.version, cur), Pal.onSurface)
        d.view(button(act, r.pageUrl, Btn.Text) { open(act, r.pageUrl) })
        if (r.notes.isNotEmpty()) {
            d.message(S(R.string.whats_new), Pal.onSurface)
            d.message(notesText(r.notes))
        }
        d.button(S(R.string.skip_this_version)) { LegacyApp.app.prefs.set("updateSkip", r.version); d.dismiss() }
        d.button(S(R.string.later)) { d.dismiss() }
        d.button(S(R.string.open_download_page)) { open(act, r.pageUrl); d.dismiss() }
        d.show()
    }

    private fun open(act: LegacyActivity, url: String) {
        runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun notesText(md: String): String =
        md.lines().map { it.trimEnd() }.filter { !it.startsWith("#") }.map { l ->
            val t = l.trimStart()
            val indent = l.length - t.length
            val body = t.replace("**", "").replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
            if (t.startsWith("- ") || t.startsWith("* ")) (if (indent > 0) "　・" else "・") + body.drop(2) else body
        }.joinToString("\n").trim().take(3000)
}
