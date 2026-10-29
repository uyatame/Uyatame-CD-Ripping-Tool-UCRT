package com.uyatame.ucrt.legacy.compat

import android.view.View

/** Android 6.0 からの API(呼ぶ前に版を確かめる) */
object Api23 {
    /** AudioTrack の音が実際に出ている先の名前(分からなければ null) */
    fun routedName(t: android.media.AudioTrack): String? =
        runCatching { t.routedDevice?.productName?.toString()?.trim()?.ifEmpty { null } }.getOrNull()

    /** 状態バーの文字を暗い色にする(背景が明るいとき) */
    @Suppress("DEPRECATION")
    fun lightStatusBar(v: View, light: Boolean) {
        val f = v.systemUiVisibility
        v.systemUiVisibility = if (light) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
    }
}

/** Android 6.0 からの、実行時の許可 */
object Api23Perm {
    fun granted(a: android.app.Activity, p: String): Boolean = a.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun request(a: android.app.Activity, ps: Array<String>) = a.requestPermissions(ps, 1)
    fun rationale(a: android.app.Activity, p: String): Boolean = a.shouldShowRequestPermissionRationale(p)
}
