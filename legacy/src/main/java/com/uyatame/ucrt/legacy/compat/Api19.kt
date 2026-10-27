package com.uyatame.ucrt.legacy.compat

import android.content.Context
import java.io.File

/** Android 4.4 からの API(呼ぶ前に版を確かめる) */
object Api19 {
    /** アプリが使える保存場所(内部ストレージ・SD カード)の、アプリ用のフォルダ */
    fun externalDirs(ctx: Context): List<File> = ctx.getExternalFilesDirs(null).filterNotNull()
}
