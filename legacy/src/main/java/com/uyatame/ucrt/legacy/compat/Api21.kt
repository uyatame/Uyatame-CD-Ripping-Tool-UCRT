package com.uyatame.ucrt.legacy.compat

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.Window

/** Android 5.0 からの API(呼ぶ前に版を確かめる) */
object Api21 {
    /** 触ったときに波紋が広がる背景 */
    fun ripple(color: Int, content: Drawable?, mask: Drawable?): Drawable = RippleDrawable(ColorStateList.valueOf(color), content, mask)

    fun statusBar(w: Window, color: Int) {
        w.statusBarColor = color
    }

    fun navBar(w: Window, color: Int) {
        w.navigationBarColor = color
    }

    fun elevation(v: View, px: Float) {
        v.elevation = px
    }
}
