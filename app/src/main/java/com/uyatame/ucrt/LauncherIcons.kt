package com.uyatame.ucrt

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * ホーム画面のアイコンを選ぶ(マニフェストの activity-alias を 1 つだけ有効にする)。
 * 画面を出している間に切り替えると、ホームアプリによってはアプリが閉じられるので、
 * 選んだものを覚えておき、画面が裏に回ったとき(MainActivity.onStop)に切り替える
 */
object LauncherIcons {
    /** 選べるアイコン(id、明るい背景か)。default は今までのアイコン */
    val ids: List<String> = listOf("default") +
        listOf("ucrt", "ucrt2", "ucrt3", "ucrt4", "ucrt5", "ucrt6", "ucrt7").flatMap { listOf("${it}_light", "${it}_dark") } +
        listOf("rainbow_ucrt", "rainbow_unknown")

    private const val PREFS = "launcher_icon"
    private const val PENDING = "pending"
    private const val CURRENT = "current"

    private fun alias(ctx: Context, id: String) = ComponentName(ctx.packageName, "com.uyatame.ucrt.LauncherAlias_$id")

    /** 見本の画像(default は null。画面で今のアイコンを描く) */
    fun preview(id: String): Int? = when (id) {
        "ucrt_light" -> R.drawable.ic_alt_ucrt_light_full
        "ucrt_dark" -> R.drawable.ic_alt_ucrt_dark_full
        "ucrt2_light" -> R.drawable.ic_alt_ucrt2_light_full
        "ucrt2_dark" -> R.drawable.ic_alt_ucrt2_dark_full
        "ucrt3_light" -> R.drawable.ic_alt_ucrt3_light_full
        "ucrt3_dark" -> R.drawable.ic_alt_ucrt3_dark_full
        "ucrt4_light" -> R.drawable.ic_alt_ucrt4_light_full
        "ucrt4_dark" -> R.drawable.ic_alt_ucrt4_dark_full
        "ucrt5_light" -> R.drawable.ic_alt_ucrt5_light_full
        "ucrt5_dark" -> R.drawable.ic_alt_ucrt5_dark_full
        "ucrt6_light" -> R.drawable.ic_alt_ucrt6_light_full
        "ucrt6_dark" -> R.drawable.ic_alt_ucrt6_dark_full
        "ucrt7_light" -> R.drawable.ic_alt_ucrt7_light_full
        "ucrt7_dark" -> R.drawable.ic_alt_ucrt7_dark_full
        "rainbow_ucrt" -> R.drawable.ic_alt_rainbow_ucrt_full
        "rainbow_unknown" -> R.drawable.ic_alt_rainbow_unknown_full
        else -> null
    }

    /** 今のアイコン(切り替え待ちならそれ) */
    fun current(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(PENDING, null) ?: p.getString(CURRENT, null) ?: "default"
    }

    /** 選ぶ(実際に切り替えるのは、画面が裏に回ったとき) */
    fun request(ctx: Context, id: String) {
        val v = id.takeIf { it in ids } ?: "default"
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PENDING, v).apply()
    }

    /** 選んだアイコンに切り替える(MainActivity.onStop から) */
    fun applyPending(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString(PENDING, null) ?: return
        val app = ctx.applicationContext
        val ok = runCatching {
            val pm = app.packageManager
            // 先に新しいものを有効にする(ホーム画面からアプリが消える時間を作らない)
            pm.setComponentEnabledSetting(alias(app, id), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (o in ids) if (o != id) {
                pm.setComponentEnabledSetting(alias(app, o), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }.isSuccess
        if (ok) p.edit().remove(PENDING).putString(CURRENT, id).apply()
    }
}
