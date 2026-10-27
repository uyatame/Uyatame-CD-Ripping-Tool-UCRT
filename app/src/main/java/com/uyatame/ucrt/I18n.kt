package com.uyatame.ucrt

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/** アプリの表示言語(Android 13以降のアプリ別言語設定を利用)。"" はシステムに従う */
object AppLanguage {
    fun current(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.getSystemService(ctx, LocaleManager::class.java)!!.applicationLocales.toLanguageTags()
        } else ""

    fun set(ctx: Context, tag: String) {
        if (Build.VERSION.SDK_INT < 33) return
        androidx.core.content.ContextCompat.getSystemService(ctx, LocaleManager::class.java)!!.applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33
}

/**
 * USB の使用許可の結果を受け取る PendingIntent のフラグ(結果をシステムが書き込むので、書き換えられる必要がある)。
 * FLAG_MUTABLE は Android 12 から。それより前は指定しなければ書き換えられる
 */
fun mutablePendingFlags(): Int =
    if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
    else android.app.PendingIntent.FLAG_UPDATE_CURRENT

/** アプリのバージョン名(build.gradle の versionName) */
object BuildConfigVersion {
    fun name(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0.1"
}
