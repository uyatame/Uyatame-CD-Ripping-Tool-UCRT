package com.uyatame.ucrt.legacy

import android.view.View

/**
 * 1 つの画面(ライブラリの各ページ・ディスク・設定の各ページ)。
 * Bus で知らせが来たら refresh で中身を作り直す(mask に含まれる知らせだけ)
 */
abstract class Page(val act: LegacyActivity) {
    abstract val view: View
    /** 作り直す知らせ */
    open val mask: Int = Bus.LIBRARY or Bus.STORE or Bus.PLAYER or Bus.SETTINGS or Bus.ART
    open fun refresh(m: Int) {}
    /** 戻るボタンをこの画面で受け取ったら true */
    open fun back(): Boolean = false
    open fun scrollState(): IntArray? = null
    open fun restoreScroll(p: IntArray?) {}
    open fun dispose() {}
}
