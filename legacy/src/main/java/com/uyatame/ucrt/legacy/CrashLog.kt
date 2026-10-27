package com.uyatame.ucrt.legacy

import android.content.Context
import android.os.Build
import android.widget.TextView
import com.uyatame.ucrt.R
import com.uyatame.ucrt.S
import com.uyatame.ucrt.legacy.ui.Pal
import com.uyatame.ucrt.legacy.ui.Type
import com.uyatame.ucrt.legacy.ui.UDialog
import com.uyatame.ucrt.legacy.ui.text
import java.io.File

/**
 * アプリが落ちたときの記録。落ちた理由(例外)と直前のログをファイルに書き、次に開いたときに見せる(コピーして送れるように)
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val ver = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                val log = AppLog.text.takeLast(6000)
                file(app).writeText(
                    "UCRT Legacy $ver / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "thread: ${t.name}\n\n$sw\n---- log ----\n$log",
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** 前に落ちていたら、その記録を見せる(見せたら消す) */
    fun showIfAny(act: LegacyActivity) {
        val f = file(act)
        if (!f.exists()) return
        val txt = runCatching { f.readText() }.getOrNull()
        f.delete()
        if (txt.isNullOrBlank()) return
        val tv: TextView = text(act, txt, Type.BodySmall, Pal.onSurface, lines = 0)
        tv.setTextIsSelectable(true)
        tv.typeface = android.graphics.Typeface.MONOSPACE
        val d = UDialog(act).title(S(R.string.legacy_crashed)).message(S(R.string.legacy_crashed_desc)).view(tv)
        d.button(S(R.string.close)) { d.dismiss() }
        d.button(S(R.string.legacy_copy)) {
            @Suppress("DEPRECATION")
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as android.text.ClipboardManager
            cm.text = txt
            act.snack(S(R.string.legacy_copied))
        }
        d.show()
    }
}
