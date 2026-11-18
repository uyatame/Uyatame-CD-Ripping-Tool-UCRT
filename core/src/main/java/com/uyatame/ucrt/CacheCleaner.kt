package com.uyatame.ucrt

import java.io.File

/**
 * アプリのキャッシュ(端末の一時的な置き場)の片付け。Android の部品には頼らない(PC 上の単体テストで確かめられるように)。
 * - NAS の曲の一時コピー(net-cache): 合計 NET_LIMIT まで。NET_MAX_AGE のあいだ使っていないものは消す
 * - ジャケットの縮小版(art): 合計 ART_LIMIT まで(使った時刻の古いものから消す)
 * - 作業の一時ファイル(取り込み・タグの書き換え・CD の書き込み): 作業中でなければ消す
 */
object CacheCleaner {
    /** NAS の曲の一時コピーの上限(約 500 MB) */
    const val NET_LIMIT = 500L shl 20
    /** NAS の曲の一時コピーを、使わなければ消すまでの期間(7 日) */
    const val NET_MAX_AGE_MS = 7L * 24 * 60 * 60_000
    /** ジャケットの縮小版の上限(約 200 MB) */
    const val ART_LIMIT = 200L shl 20

    const val NET_DIR = "net-cache"
    const val ART_DIR = "art"

    /** 作業の一時ファイル(キャッシュの直下。作業が終われば消すもの。途中で止められたときに残る) */
    private val TEMP_NAMES = Regex("^(burn\\.pcm|rip_tmp\\..+|tag_in\\..+|tag_out\\..+)$")
    private const val TEMP_DIRS = "mb-cover"

    fun dirSize(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /**
     * dir の中を片付ける。maxAgeMs(0 なら見ない)より長く使っていないものを消し、そのあと合計が limit 以下になるまで、
     * 使った時刻の古いものから消す。keep が true のものは消さない。消した大きさを返す
     */
    fun trim(dir: File, limit: Long, maxAgeMs: Long, now: Long, keep: (File) -> Boolean = { false }): Long {
        val files = (dir.listFiles() ?: return 0).filter { it.isFile }.sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        var freed = 0L
        for (f in files) {
            if (keep(f)) continue
            val old = maxAgeMs > 0 && now - f.lastModified() > maxAgeMs
            if (!old && total <= limit) continue
            val n = f.length()
            if (f.delete()) { total -= n; freed += n }
        }
        return freed
    }

    /** NAS の曲の一時コピーの片付け(10 分以内に使ったもの(取り込み中・再生中かもしれない)は消さない) */
    fun trimNet(cacheDir: File, now: Long = System.currentTimeMillis(), limit: Long = NET_LIMIT): Long =
        trim(File(cacheDir, NET_DIR), limit, NET_MAX_AGE_MS, now) { now - it.lastModified() < 10 * 60_000L }

    fun trimArt(cacheDir: File, now: Long = System.currentTimeMillis()): Long =
        trim(File(cacheDir, ART_DIR), ART_LIMIT, 0, now) { it.name.endsWith(".tmp") && now - it.lastModified() < 60_000L }

    /** 作業の一時ファイル(作業中でないときだけ呼ぶ) */
    fun clearTemp(cacheDir: File): Long {
        var freed = 0L
        cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && TEMP_NAMES.matches(f.name)) { val n = f.length(); if (f.delete()) freed += n }
            if (f.isDirectory && f.name == TEMP_DIRS) { val n = dirSize(f); if (f.deleteRecursively()) freed += n }
        }
        return freed
    }

    fun tempSize(cacheDir: File): Long = (cacheDir.listFiles() ?: emptyArray()).sumOf { f ->
        when {
            f.isFile && TEMP_NAMES.matches(f.name) -> f.length()
            f.isDirectory && f.name == TEMP_DIRS -> dirSize(f)
            else -> 0L
        }
    }

    /** 1 つの種類を全部消す(設定の「消す」) */
    fun clearDir(cacheDir: File, name: String): Long {
        val d = File(cacheDir, name)
        val n = dirSize(d)
        d.listFiles()?.forEach { it.deleteRecursively() }
        return n
    }
}
