package com.uyatame.ucrt.player

import android.content.Context

/** 内蔵 DAC への DoP(実験的)は、テスト版(testtools)にだけある。リリース版では、いつも使えない(PCM に変換して鳴らす) */
internal object BuiltinDop {
    @Suppress("UNUSED_PARAMETER")
    fun canTry(ctx: Context, rate: Int): Boolean = false
}
