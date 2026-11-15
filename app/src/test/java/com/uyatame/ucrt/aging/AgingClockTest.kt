package com.uyatame.ucrt.aging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** エージングの時間: 鳴った時間だけ数える・休憩の入り方・区切りをまたぐ進め方・保存と読み直し(時計は偽物で進める) */
class AgingClockTest {
    private val min = 60_000L

    @Test fun countsOnlyWhileSoundingWithoutInterval() {
        val c = AgingClock(2 * 60 * min, interval = false)
        assertTrue(c.advance(30 * min, sounding = true).isEmpty())
        assertTrue(c.advance(30 * min, sounding = false).isEmpty())
        assertEquals(30 * min, c.playedMs)
        assertEquals(-1L, c.untilRestMs)
        assertEquals(listOf(AgingClock.Event.DONE), c.advance(5 * 60 * min, sounding = true))
        assertEquals(2 * 60 * min, c.playedMs)
        assertTrue(c.done)
        assertEquals(1f, c.progress)
        // 終わったあとは進まない
        assertTrue(c.advance(10 * min, true).isEmpty())
    }

    @Test fun restsFiftyOnTenOffAndRestRunsWhileSilent() {
        val c = AgingClock.ofHours(25, interval = true)
        assertEquals(50 * min, c.untilRestMs)
        assertEquals(listOf(AgingClock.Event.REST_START), c.advance(50 * min, true))
        assertTrue(c.resting)
        assertEquals(10 * min, c.restLeftMs)
        // 休憩は音を止めていても進む
        assertTrue(c.advance(4 * min, sounding = false).isEmpty())
        assertEquals(6 * min, c.restLeftMs)
        assertEquals(listOf(AgingClock.Event.REST_END), c.advance(6 * min, false))
        assertFalse(c.resting)
        assertEquals(50 * min, c.playedMs)
        assertEquals(0L, c.segmentMs)
    }

    @Test fun oneBigStepCrossesBoundariesInOrder() {
        val c = AgingClock.ofHours(25, interval = true)
        val ev = c.advance(65 * min, true)
        assertEquals(listOf(AgingClock.Event.REST_START, AgingClock.Event.REST_END), ev)
        assertEquals(55 * min, c.playedMs)
        assertEquals(5 * min, c.segmentMs)
    }

    @Test fun lastSegmentEndsWithoutARest() {
        // 50 分ちょうどで終わるなら、休憩には入らない
        val c = AgingClock(50 * min, interval = true)
        assertEquals(-1L, c.untilRestMs)
        assertEquals(listOf(AgingClock.Event.DONE), c.advance(50 * min, true))
        assertFalse(c.resting)
    }

    @Test fun remainingWallTimeIncludesUpcomingRests() {
        val c = AgingClock.ofHours(25, interval = true)
        // 25 時間 = 50 分 × 30 回。間の休憩は 29 回
        assertEquals(25 * 60 * min + 29 * 10 * min, c.remainingWallMs())
        assertEquals(25 * 60 * min, AgingClock.ofHours(25, false).remainingWallMs())
    }

    @Test fun savesAndRestores() {
        val c = AgingClock.ofHours(50, interval = true)
        c.advance(123 * min, true)
        val r = AgingClock.fromJson(org.json.JSONObject(c.toJson().toString()))
        assertNotNull(r)
        r!!
        assertEquals(c.playedMs, r.playedMs)
        assertEquals(c.segmentMs, r.segmentMs)
        assertEquals(c.resting, r.resting)
        assertEquals(c.restLeftMs, r.restLeftMs)
        assertEquals(c.targetMs, r.targetMs)
        // 壊れた値は読まない・範囲に収める
        assertEquals(null, AgingClock.fromJson(org.json.JSONObject("{}")))
        val odd = AgingClock.fromJson(org.json.JSONObject("""{"targetMs":1000,"playedMs":99999,"interval":false,"resting":true}"""))!!
        assertEquals(1000L, odd.playedMs)
        assertFalse(odd.resting)
    }
}
