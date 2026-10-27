package com.uyatame.ucrt.legacy.compat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/** Android 11 からの「すべてのファイルへのアクセス」(呼ぶ前に版を確かめる) */
object Api30 {
    fun allFiles(): Boolean = Environment.isExternalStorageManager()

    fun request(a: Activity) {
        runCatching { a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.packageName))) }
            .onFailure { runCatching { a.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
    }
}
