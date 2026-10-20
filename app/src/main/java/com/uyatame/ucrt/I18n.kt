package com.uyatame.ucrt

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/**
 * 表示する文言を、res/values/strings.xml(英語)・res/values-ja/strings.xml(日本語)から表示言語に合わせて読む(日本語以外の環境では英語)。
 * 画面だけでなく、再生エンジンや取り込みの処理からも使えるように、アプリの Context を持っておく
 */
object Strings {
    private lateinit var app: Context
    @Volatile private var cached: Pair<Locale, Resources>? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /** 今の表示言語(Locale.getDefault())の Resources。アプリ別の言語設定にも合わせる */
    fun res(): Resources {
        val loc = Locale.getDefault()
        cached?.let { if (it.first == loc) return it.second }
        val conf = Configuration(app.resources.configuration)
        conf.setLocale(loc)
        val r = app.createConfigurationContext(conf).resources
        cached = loc to r
        return r
    }
}

/** 文言を読む */
fun S(@StringRes id: Int): String = Strings.res().getString(id)

/** 値を埋め込んだ文言を読む(strings.xml では %1$s のように書く) */
fun S(@StringRes id: Int, vararg args: Any?): String = Strings.res().getString(id, *args)

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
