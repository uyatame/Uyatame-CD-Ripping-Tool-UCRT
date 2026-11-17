package com.uyatame.ucrt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** イコライザーの数値入力: 書き方の揺れ(単位・カンマ・全角・k)を読み、読めないものは null */
class EqNumberTest {
    @Test fun readsPlainNumbersAndUnits() {
        assertEquals(1.5f, EqNumber.parse("1.5")!!, 0f)
        assertEquals(-3f, EqNumber.parse(" -3 dB")!!, 0f)
        assertEquals(2.5f, EqNumber.parse("+2,5")!!, 0f)
        assertEquals(-4.5f, EqNumber.parse("−4.5")!!, 0f)
        assertEquals(12f, EqNumber.parse("１２")!!, 0f)
        assertEquals(0.71f, EqNumber.parse("Q 0.71")!!, 0f)
        assertEquals(440f, EqNumber.parse("440Hz")!!, 0f)
    }

    @Test fun readsKiloForFrequency() {
        assertEquals(1200f, EqNumber.parse("1.2k", allowKilo = true)!!, 0.01f)
        assertEquals(1200f, EqNumber.parse("1k2", allowKilo = true)!!, 0.01f)
        assertEquals(16000f, EqNumber.parse("16kHz", allowKilo = true)!!, 0.01f)
        // k を許さないところでは読まない
        assertNull(EqNumber.parse("1.2k"))
    }

    @Test fun rejectsGarbage() {
        for (bad in listOf("", "abc", "1.2.3", "k", "1.2k3", "NaN", "Infinity", "--1")) assertNull(bad, EqNumber.parse(bad, allowKilo = true))
    }
}
