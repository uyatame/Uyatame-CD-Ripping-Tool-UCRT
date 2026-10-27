package com.uyatame.ucrt

import android.app.Application

/** アプリ全体の初期化(どの画面・サービス・ウィジェットよりも先に動く) */
class UcrtApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
        // 曲情報の書き換えなどで、保存場所の URI から実際のファイルを求められるようにする
        FileUris.resolver = { com.uyatame.ucrt.library.StorageAccess.fileOf(it) }
    }
}
