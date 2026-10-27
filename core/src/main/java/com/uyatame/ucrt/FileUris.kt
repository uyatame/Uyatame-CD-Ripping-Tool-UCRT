package com.uyatame.ucrt

import android.net.Uri
import java.io.File

/**
 * URI からファイルの場所を求める(分からなければ null)。
 * 初めは file: の URI だけ。app は保存場所の URI(「すべてのファイルへのアクセス」があるとき)にも対応させる(UcrtApp で差し替える)
 */
object FileUris {
    @Volatile var resolver: (Uri) -> File? = { u -> if (u.scheme == "file") u.path?.let { File(it) } else null }

    fun fileOf(u: Uri): File? = resolver(u)
}
