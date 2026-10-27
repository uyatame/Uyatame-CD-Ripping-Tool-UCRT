package com.uyatame.ucrt

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import java.util.Locale

/**
 * 表示する文言を、res/values/strings.xml(英語)・res/values-ja/strings.xml(日本語)から表示言語に合わせて読む(日本語以外の環境では英語)。
 * 画面だけでなく、再生エンジンや取り込みの処理からも使えるように、アプリの Context を持っておく。
 * 文言のファイルは core/src/main/res にあり、アプリ(app)と古い Android 向けのアプリ(legacy)の両方で使う
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
        // 言語を指定した Resources は Android 4.2 から。それより前は、アプリの Resources(端末の言語)を使う
        val r = if (Build.VERSION.SDK_INT >= 17) {
            val conf = Configuration(app.resources.configuration)
            conf.setLocale(loc)
            app.createConfigurationContext(conf).resources
        } else {
            app.resources
        }
        cached = loc to r
        return r
    }
}

/** 文言を読む */
fun S(id: Int): String = Strings.res().getString(id)

/** 値を埋め込んだ文言を読む(strings.xml では %1$s のように書く) */
fun S(id: Int, vararg args: Any?): String = Strings.res().getString(id, *args)
