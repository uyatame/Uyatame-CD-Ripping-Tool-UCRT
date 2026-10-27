package com.uyatame.ucrt.legacy.compat

import android.view.View

/**
 * Android 4.4W(API 20)からの API。Android 15 からは画面が状態バー・ナビゲーションバーの下まで広がるので、
 * その分だけ中身を内側に寄せる(それより前の版では、もともと寄せてあるので 0 が届く)
 */
object Insets20 {
    @Suppress("DEPRECATION")
    fun apply(v: View) {
        v.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        v.requestApplyInsets()
    }
}
