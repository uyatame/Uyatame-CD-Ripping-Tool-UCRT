package com.uyatame.ucrt.legacy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.uyatame.ucrt.legacy.compat.Api23Perm

/** 曲のファイルを読む許可(Android 6.0 から、実行時に求める) */
object Permissions {
    private var asked = false

    private fun needed(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf("android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_IMAGES", "android.permission.POST_NOTIFICATIONS")
        // 取り込んだ曲を書くので、Android 10 までは書き込みの許可も求める
        Build.VERSION.SDK_INT >= 30 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        Build.VERSION.SDK_INT >= 23 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> emptyArray()
    }

    /** 曲を読む許可があるか */
    fun granted(a: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val p = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else Manifest.permission.READ_EXTERNAL_STORAGE
        return Api23Perm.granted(a, p)
    }

    /** 許可を求める(force: 断られていたら、アプリの設定の画面を開く) */
    fun ask(a: Activity, force: Boolean = false) {
        if (Build.VERSION.SDK_INT < 23) return
        val missing = needed().filter { !Api23Perm.granted(a, it) }
        if (missing.isEmpty()) return
        if (asked && !force) return
        if (force && asked && missing.none { Api23Perm.rationale(a, it) }) {
            // 「今後表示しない」で断られている: アプリの設定の画面へ
            runCatching {
                a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)))
            }
            return
        }
        asked = true
        Api23Perm.request(a, missing.toTypedArray())
    }
}
