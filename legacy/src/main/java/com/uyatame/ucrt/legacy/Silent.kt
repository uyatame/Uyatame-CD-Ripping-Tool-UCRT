package com.uyatame.ucrt.legacy

import android.content.Context
import com.uyatame.ucrt.player.SilentMode

/** サイレントモード(本家と同じ。core の SilentMode を、設定とホーム画面の状態に合わせて使う) */
object Silent {
    /** 0 = オフ、1 = ホーム画面のときだけ、2 = いつも */
    fun mode(): Int = LegacyApp.app.prefs.sp.getInt("silentMode", 0).coerceIn(0, 2)

    fun of(ctx: Context) = SilentMode(ctx) { AppLog.log(it) }

    /** 設定とホーム画面の状態に合わせる(何度呼んでもよい) */
    fun sync(ctx: Context) {
        val m = mode()
        val want = m == 2 || (m == 1 && AppsPage.isDefaultHome(ctx))
        runCatching { of(ctx).sync(want) }
    }
}
