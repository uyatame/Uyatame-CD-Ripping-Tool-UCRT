package com.uyatame.ucrt.legacy.compat

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri

/** Android 14 から: フルスクリーン通知(ロック画面を起こす通知)には、設定での許可が要る */
object Api34 {
    fun canUseFullScreen(nm: NotificationManager): Boolean = nm.canUseFullScreenIntent()

    /** フルスクリーン通知の許可の画面 */
    fun fullScreenSettings(pkg: String): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg"))
}
