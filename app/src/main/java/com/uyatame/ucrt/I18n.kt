package com.uyatame.ucrt

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** 表示言語に合わせて日本語/英語を返す(日本語以外の環境では英語) */
fun T(ja: String, en: String): String = if (Locale.getDefault().language == "ja") ja else en

/** アプリの表示言語(Android 13以降のアプリ別言語設定を利用)。"" はシステムに従う */
object AppLanguage {
    fun current(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        } else ""

    fun set(ctx: Context, tag: String) {
        if (Build.VERSION.SDK_INT < 33) return
        ctx.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33
}

/** アプリのバージョン名(build.gradle の versionName) */
object BuildConfigVersion {
    fun name(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0.1"
}
