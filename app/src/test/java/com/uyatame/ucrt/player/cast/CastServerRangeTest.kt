package com.uyatame.ucrt.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chromecast 用の HTTP サーバーの Range の読み取り(不正な値でも例外にならず、満たせない範囲になる) */
class CastServerRangeTest {
    @Test fun normalRanges() {
        assertEquals(0L..99L, CastServer.parseRange("bytes=0-99", 1000))
        assertEquals(500L..999L, CastServer.parseRange("bytes=500-", 1000))
        assertEquals(900L..999L, CastServer.parseRange("bytes=-100", 1000))
        // ファイルより先を指していたら、終わりはファイルの終わりに収める
        assertEquals(0L..999L, CastServer.parseRange("bytes=0-5000", 1000))
        assertNull(CastServer.parseRange(null, 1000))
    }

    @Test fun invalidRangesAreUnsatisfiableNotExceptions() {
        for (r in listOf("bytes=abc-def", "bytes=1-x", "bytes=x-", "bytes=-y", "bytes=99999999999999999999-")) {
            val x = CastServer.parseRange(r, 1000)!!
            // 始めが終わりより後(= 416 になる範囲)
            assertTrue(r, x.first > x.last)
        }
    }
}
