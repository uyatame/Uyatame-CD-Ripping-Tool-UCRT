package com.uyatame.ucrt

import org.junit.Assert.assertEquals
import org.junit.Test

/** 保存したイコライザーの書き方と読み方(名前に区切りの文字や日本語が入っていても元に戻る) */
class EqCustomTest {
    @Test fun roundTrip() {
        val list = listOf(
            EqCustom("マイEQ | 低音 & 高音　", false, "1.0,2.0,0.0,-1.5,0.0,0.0,0.0,0.0,3.0,4.0", -2f),
            EqCustom("PRO;test,1", true, "P,1000.0,3.00,1.410;H,8000.0,-2.00,0.710", 0.5f),
        )
        val back = EqCustom.decode(EqCustom.encode(list))
        assertEquals(list, back)
        assertEquals(3f, back[0].gains()[8], 0f)
        assertEquals(2, back[1].bands().size)
    }

    @Test fun emptyAndBroken() {
        assertEquals(emptyList<EqCustom>(), EqCustom.decode(""))
        assertEquals(1, EqCustom.decode("a|s|0,0|0\nbroken line").size)
    }
}
