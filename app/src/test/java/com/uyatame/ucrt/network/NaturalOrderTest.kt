package com.uyatame.ucrt.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ネットワークのフォルダのファイルの並び(数字は数の大きさで比べる) */
class NaturalOrderTest {
    private fun sorted(vararg names: String) = names.toList().sortedWith(NaturalOrder)

    @Test fun numbersAreComparedByValue() {
        assertEquals(listOf("1.flac", "2.flac", "10.flac", "11.flac"), sorted("10.flac", "2.flac", "11.flac", "1.flac"))
        assertEquals(listOf("Track 2", "Track 10", "Track 100"), sorted("Track 100", "Track 2", "Track 10"))
    }

    @Test fun zeroPaddedNamesKeepTheirOrder() {
        assertEquals(listOf("01 a.flac", "02 b.flac", "10 c.flac"), sorted("10 c.flac", "01 a.flac", "02 b.flac"))
    }

    @Test fun caseIsIgnored() {
        assertEquals(0, NaturalOrder.compare("Abc", "aBC"))
        assertTrue(NaturalOrder.compare("a2", "B1") < 0)
    }

    @Test fun equalNumbersWithDifferentZerosAreStable() {
        assertTrue(NaturalOrder.compare("1", "01") < 0)
        assertTrue(NaturalOrder.compare("01", "1") > 0)
        assertEquals(0, NaturalOrder.compare("007", "007"))
    }

    @Test fun veryLongNumbersDoNotOverflow() {
        val a = "x" + "9".repeat(40)
        val b = "x1" + "0".repeat(40)
        assertTrue(NaturalOrder.compare(a, b) < 0)
    }

    @Test fun prefixSortsFirstAndZeroIsAllowed() {
        assertEquals(listOf("a", "a1", "a1b"), sorted("a1b", "a", "a1"))
        assertTrue(NaturalOrder.compare("0", "00") < 0)
        assertTrue(NaturalOrder.compare("0", "1") < 0)
    }

    @Test fun orderIsConsistent() {
        val names = listOf("1", "01", "2", "10", "a", "A1", "a01", "a1", "b", "1-2", "1-02", "01-2", "")
        for (x in names) for (y in names) {
            val c = NaturalOrder.compare(x, y)
            assertEquals("$x vs $y", Integer.signum(c), -Integer.signum(NaturalOrder.compare(y, x)))
        }
        // 推移律(a<b かつ b<c なら a<c)
        for (x in names) for (y in names) for (z in names) {
            if (NaturalOrder.compare(x, y) < 0 && NaturalOrder.compare(y, z) < 0) {
                assertTrue("$x < $y < $z", NaturalOrder.compare(x, z) < 0)
            }
        }
    }
}
