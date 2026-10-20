package com.uyatame.ucrt

import android.app.Application

/** アプリ全体の初期化(どの画面・サービス・ウィジェットよりも先に動く) */
class UcrtApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
    }
}
