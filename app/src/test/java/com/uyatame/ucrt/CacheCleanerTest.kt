package com.uyatame.ucrt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** キャッシュの片付け: 古いものから上限まで消す・期間を過ぎたものを消す・途中のものは残す・作業の一時ファイル */
class CacheCleanerTest {
    private val day = 24 * 60 * 60_000L
    private val now = 1_800_000_000_000L

    private fun file(dir: File, name: String, size: Int, ageMs: Long): File =
        File(dir, name).apply { writeBytes(ByteArray(size)); setLastModified(now - ageMs) }

    @Test fun trimsOldestUntilUnderLimit() {
        val d = Files.createTempDirectory("cc").toFile()
        val a = file(d, "a", 100, 3 * day)
        val b = file(d, "b", 100, 2 * day)
        val c = file(d, "c", 100, 1 * day)
        val freed = CacheCleaner.trim(d, 150, 0, now)
        assertEquals(200L, freed)
        assertFalse(a.exists()); assertFalse(b.exists()); assertTrue(c.exists())
    }

    @Test fun netCacheDropsFilesUnusedForAWeekAndKeepsRecentParts() {
        val cache = Files.createTempDirectory("cc").toFile()
        val d = File(cache, CacheCleaner.NET_DIR).apply { mkdirs() }
        val old = file(d, "old.flac", 10, 8 * day)
        val recent = file(d, "new.flac", 10, 1 * day)
        val part = file(d, "x.part", 10, 60_000)
        val playing = file(d, "playing.flac", 10, 5 * 60_000)
        CacheCleaner.trimNet(cache, now, limit = 5)
        assertFalse(old.exists())
        // 上限を超えていれば、新しいものも消す。ただし 10 分以内に使ったもの(取り込み中・再生中)は残す
        assertFalse(recent.exists())
        assertTrue(part.exists())
        assertTrue(playing.exists())
        // 上限に収まっていれば、1 週間以内のものは残る
        val keep = file(d, "keep.flac", 10, 2 * day)
        CacheCleaner.trimNet(cache, now)
        assertTrue(keep.exists())
    }

    @Test fun clearsOnlyWorkTempFiles() {
        val cache = Files.createTempDirectory("cc").toFile()
        file(cache, "burn.pcm", 50, 0)
        file(cache, "rip_tmp.flac", 20, 0)
        file(cache, "tag_out.dsf", 30, 0)
        val keep = file(cache, "something.bin", 40, 0)
        val mb = File(cache, "mb-cover").apply { mkdirs() }
        file(mb, "c.jpg", 5, 0)
        assertEquals(105L, CacheCleaner.tempSize(cache))
        assertEquals(105L, CacheCleaner.clearTemp(cache))
        assertTrue(keep.exists())
        assertFalse(mb.exists())
    }
}
