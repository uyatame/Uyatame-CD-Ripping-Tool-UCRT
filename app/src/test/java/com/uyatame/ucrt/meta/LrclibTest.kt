package com.uyatame.ucrt.meta

import com.uyatame.ucrt.player.LyricLine
import com.uyatame.ucrt.player.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LRCLIB の登録の問題(proof of work)の解き方と、送る歌詞の形 */
class LrclibTest {
    @Test fun solvesEasyChallengeAndVerifies() {
        // サーバーの基準(000000FF…)より易しい問題で、解き方と確かめ方が合っているかを見る
        val target = "0000FF" + "F".repeat(58)
        val nonce = Lrclib.solve("abcdefghijklmnopqrstuvwxyz012345", target)!!
        assertTrue(Lrclib.verify("abcdefghijklmnopqrstuvwxyz012345", Lrclib.hex(target), nonce))
        assertFalse(Lrclib.verify("abcdefghijklmnopqrstuvwxyz012345", Lrclib.hex("00" + "0".repeat(62)), nonce))
    }

    @Test fun stopsWhenAsked() {
        assertNull(Lrclib.solve("x", "0".repeat(64)) { true })
    }

    @Test fun syncedAndPlain() {
        val l = Lyrics(listOf(LyricLine(1500, "一行目"), LyricLine(61_230, ""), LyricLine(62_000, "二行目")), true)
        val (plain, synced) = Lrclib.fromLyrics(l)
        assertEquals("一行目\n二行目", plain)
        assertEquals("[00:01.50] 一行目\n[01:01.23]\n[01:02.00] 二行目", synced)
        val p = Lrclib.fromLyrics(Lyrics(listOf(LyricLine(0, "a"), LyricLine(0, "b")), false))
        assertEquals("a\nb", p.first)
        assertNull(p.second)
    }
}
