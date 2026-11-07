package com.uyatame.ucrt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable

/**
 * ホーム画面として使うときの、アプリ一覧(「アプリ」タブ)の部品。
 * UCRT が今の既定のホームか、ランチャーに出るアプリの一覧、アプリの起動を受け持つ。
 */
object HomeApps {
    /** 一覧の 1 件 */
    class Entry(val label: String, val packageName: String, val component: ComponentName)

    /** UCRT が今の既定のホーム画面(ホームボタンで開かれるアプリ)か */
    fun isDefaultHome(ctx: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = runCatching { ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull() ?: return false
        return ri.activityInfo?.packageName == ctx.packageName
    }

    /**
     * ホームアプリの役割を求める(Android 10 から。システムが「ホームアプリにしますか」と聞く画面を出す)。
     * 出せなかったら(すでにホーム・役割が無い端末・Android 9 以前)false
     */
    fun requestHomeRole(activity: android.app.Activity): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val rm = activity.getSystemService(android.app.role.RoleManager::class.java) ?: return false
            if (!rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) || rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) return false
            @Suppress("DEPRECATION")
            activity.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 4201)
            true
        }.getOrDefault(false)
    }

    /** ホーム画面の選択の画面を開く(Android 10 からは「既定のホームアプリ」の画面。それ以前は、ホームの選択の画面) */
    fun openHomeSettings(ctx: Context) {
        // まず、システムの「ホームアプリにしますか」を出す(出せなければ設定の画面)
        (ctx as? android.app.Activity)?.let { if (requestHomeRole(it)) return }
        val intents = listOf(
            Intent(android.provider.Settings.ACTION_HOME_SETTINGS),
            Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (i in intents) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    /** 端末の設定を開く(ホーム画面にしたあとでも、設定に戻れるように)。開けなかったら false */
    fun openDeviceSettings(ctx: Context): Boolean =
        runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    /** ランチャーに出るアプリを、名前順に(UCRT 自身は除く)。時間がかかるので、メインのスレッド以外で呼ぶ */
    fun list(ctx: Context): List<Entry> {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = runCatching { pm.queryIntentActivities(q, 0) }.getOrDefault(emptyList())
        return infos.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            if (ai.packageName == ctx.packageName) return@mapNotNull null
            Entry(ri.loadLabel(pm).toString(), ai.packageName, ComponentName(ai.packageName, ai.name))
        }.sortedWith(compareBy(java.text.Collator.getInstance()) { it.label.lowercase() })
    }

    /** アプリを起動する。起動できなかったら false */
    fun launch(ctx: Context, e: Entry): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(e.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { ctx.startActivity(i) }.isSuccess
    }

    /** アプリの情報の画面(長押しメニュー用。アンインストールなどはそこから) */
    fun openInfo(ctx: Context, e: Entry) {
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${e.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** アプリのアイコンを、画面に出す大きさ(px)の画像にする。時間がかかるので、メインのスレッド以外で呼ぶ */
    fun icon(ctx: Context, e: Entry, px: Int): Bitmap? = runCatching {
        val d: Drawable = ctx.packageManager.getActivityIcon(e.component)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, px, px)
        d.draw(c)
        bmp
    }.getOrNull()
}
