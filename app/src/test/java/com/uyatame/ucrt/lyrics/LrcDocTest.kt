package com.uyatame.ucrt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcDocTest {
    @Test fun parseAndEncode() {
        val d = LrcDoc.parse("[ar:A]\n[ti:T]\n[offset:150]\n[00:01.23]Hello\n[00:05.5]World\n[01:02.345]Ms\n")
        assertEquals(listOf(1230L, 5500L, 62345L), d.lines.map { it.timeMs })
        assertEquals(150L, d.offsetMs)
        assertEquals("A", d.metadata["ar"])
        val out = LrcDoc.encode(d)
        assertTrue(out.contains("[ar:A]"))
        assertTrue(out.contains("[offset:150]"))
        assertTrue(out.contains("[00:01.23]Hello"))
        assertTrue(out.contains("[00:05.50]World"))
        // 1/1000 秒は 1/100 秒に四捨五入
        assertTrue(out.contains("[01:02.35]Ms"))
        // 書いたものを読み直しても同じ
        assertEquals(d.lines.map { it.timeMs to it.text }, LrcDoc.parse(out).lines.map { it.timeMs to it.text }.let { l ->
            l.mapIndexed { i, p -> if (i == 2) 62345L to p.second else p }
        })
    }

    @Test fun multipleTagsSorted() {
        val d = LrcDoc.parse("[00:10.00][00:30.00]Chorus\n[00:20.00]Verse\n")
        assertEquals(listOf(10000L, 20000L, 30000L), d.lines.map { it.timeMs })
        assertEquals(listOf("Chorus", "Verse", "Chorus"), d.lines.map { it.text })
    }

    @Test fun plainAndBracketText() {
        val d = LrcDoc.parse("Line one\n\n[Chorus]\nLine two")
        assertFalse(d.synced)
        // [Chorus] はコロンが無いので情報ではなく歌詞
        assertEquals(listOf("Line one", "[Chorus]", "Line two"), d.lines.map { it.text })
    }

    @Test fun editOps() {
        var l = LrcDoc.fromPlain("a\nb\nc")
        l = LrcDoc.setTime(l, 0, 1000)
        assertEquals(1, LrcDoc.nextUntimed(l, 0))
        l = LrcDoc.setTime(l, 1, 2000)
        l = LrcDoc.nudge(l, 1, -150)
        assertEquals(1850L, l[1].timeMs)
        assertEquals(1, LrcDoc.indexAt(l, 1900))
        assertEquals(0, LrcDoc.indexAt(l, 1500))
        assertEquals(-1, LrcDoc.indexAt(l, 500))
        l = LrcDoc.insertAfter(l, 0, "x")
        assertEquals(listOf("a", "x", "b", "c"), l.map { it.text })
        l = LrcDoc.delete(l, 1)
        assertEquals(listOf("a", "b", "c"), l.map { it.text })
        assertNull(LrcDoc.setTime(l, 2, null)[2].timeMs)
        // 時刻の無い行は、直前の時刻の行に付いたまま並べ替わる
        val s = LrcDoc.sortTimed(listOf(
            LrcLine(1, 3000, "late"), LrcLine(2, null, "late-sub"), LrcLine(3, 1000, "early"),
        ))
        assertEquals(listOf("early", "late", "late-sub"), s.map { it.text })
    }

    @Test fun stampRounding() {
        assertEquals("[00:00.00]", LrcDoc.stamp(0))
        assertEquals("[00:01.00]", LrcDoc.stamp(995))
        assertEquals("[10:00.00]", LrcDoc.stamp(600_000))
    }
}

