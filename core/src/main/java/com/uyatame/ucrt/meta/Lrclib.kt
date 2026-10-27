package com.uyatame.ucrt.meta

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * LRCLIB(https://lrclib.net。誰でも使える歌詞のデータベース)に歌詞を登録する。
 * MusicBrainz は歌詞を扱わないので、歌詞はこちらに登録する。
 * 登録には、サーバーが出す「計算の問題」(proof of work)を解いた答えが要る(大量の登録を防ぐため。1 曲に数秒〜数十秒かかる)
 */
object Lrclib {
    private const val BASE = "https://lrclib.net/api"

    /** 登録する 1 曲 */
    class Entry(val track: String, val artist: String, val album: String, val durationSec: Double, val plain: String?, val synced: String?)

    private fun ua() = "UCRT/${MusicBrainz.version} (${MusicBrainz.PROJECT_URL})"

    private fun open(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", ua())
            setRequestProperty("Lrclib-Client", ua())
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** すでに歌詞が登録されているか(曲名・アーティスト・アルバム・長さで探す。LRCLIB は長さの違いを 2 秒まで許す) */
    fun hasLyrics(e: Entry): Boolean {
        val c = open(
            "$BASE/get?track_name=${enc(e.track)}&artist_name=${enc(e.artist)}&album_name=${enc(e.album)}" +
                "&duration=${Math.round(e.durationSec)}",
        )
        try {
            return when (val code = c.responseCode) {
                404 -> false
                200 -> {
                    val o = JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
                    // 曲だけ登録されていて歌詞が無いこともある
                    !o.optString("plainLyrics").let { it.isEmpty() || it == "null" } ||
                        !o.optString("syncedLyrics").let { it.isEmpty() || it == "null" } ||
                        o.optBoolean("instrumental")
                }
                else -> throw IOException("HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }

    /** 歌詞を登録する。stop() が true になったら、問題を解くのをやめる(IOException) */
    fun publish(e: Entry, stop: () -> Boolean = { false }) {
        // 1) 問題をもらう
        val ch = open("$BASE/request-challenge", "POST")
        val (prefix, target) = try {
            ch.doOutput = true
            ch.outputStream.close()
            if (ch.responseCode != 200) throw IOException("HTTP ${ch.responseCode}")
            val o = JSONObject(ch.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
            o.getString("prefix") to o.getString("target")
        } finally {
            ch.disconnect()
        }
        // 2) 解く
        val nonce = solve(prefix, target, stop) ?: throw IOException("canceled")
        // 3) 送る
        val body = JSONObject()
            .put("trackName", e.track)
            .put("artistName", e.artist)
            .put("albumName", e.album)
            .put("duration", e.durationSec)
            .put("plainLyrics", e.plain ?: "")
            .put("syncedLyrics", e.synced ?: "")
            .toString().toByteArray(Charsets.UTF_8)
        val c = open("$BASE/publish", "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Publish-Token", "$prefix:$nonce")
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code != 201 && code != 200) {
                val msg = runCatching { c.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull().orEmpty()
                throw IOException("HTTP $code ${msg.take(200)}")
            }
        } finally {
            c.disconnect()
        }
    }

    /** 答えとして正しいか: SHA-256(prefix + nonce) が target 以下(バイトごとに上から比べる) */
    fun verify(prefix: String, target: ByteArray, nonce: String): Boolean =
        below(MessageDigest.getInstance("SHA-256").digest((prefix + nonce).toByteArray(Charsets.UTF_8)), target)

    private fun below(h: ByteArray, target: ByteArray): Boolean {
        if (h.size != target.size) return false
        for (i in h.indices) {
            val a = h[i].toInt() and 0xFF
            val b = target[i].toInt() and 0xFF
            if (a > b) return false
            if (a < b) return true
        }
        return true
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /**
     * 答え(nonce。10 進の数)を探す。CPU のコアの数だけ同時に、別々の数から探す。
     * stop() が true になったら null
     */
    fun solve(prefix: String, targetHex: String, stop: () -> Boolean = { false }): String? {
        val target = hex(targetHex)
        val n = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val found = AtomicLong(-1)
        val quit = AtomicBoolean(false)
        val threads = (0 until n).map { k ->
            Thread({
                val md = MessageDigest.getInstance("SHA-256")
                val pre = prefix.toByteArray(Charsets.UTF_8)
                var x = k.toLong()
                var count = 0
                while (!quit.get()) {
                    md.update(pre)
                    md.update(x.toString().toByteArray(Charsets.US_ASCII))
                    if (below(md.digest(), target)) {
                        if (found.compareAndSet(-1, x)) quit.set(true)
                        break
                    }
                    x += n
                    if (++count and 0xFFFF == 0 && stop()) quit.set(true)
                }
            }, "lrclib-pow-$k").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
        }
        threads.forEach { it.join() }
        val v = found.get()
        return if (v >= 0) v.toString() else null
    }

    /**
     * 曲の歌詞(LRC か普通の文章)から、LRCLIB に送る形(時刻なしの歌詞・時刻付きの歌詞)を作る。
     * 時刻付きなら両方、そうでなければ時刻なしだけ
     */
    fun fromLyrics(l: com.uyatame.ucrt.player.Lyrics): Pair<String?, String?> {
        val plain = l.lines.joinToString("\n") { it.text }.trim()
        if (!l.synced) return plain to null
        val synced = l.lines.joinToString("\n") { line ->
            val t = line.timeMs.coerceAtLeast(0)
            "[%02d:%02d.%02d] %s".format(java.util.Locale.US, t / 60000, t / 1000 % 60, t % 1000 / 10, line.text).trimEnd()
        }
        // 時刻付きの歌詞から作った時刻なしの歌詞は、空の行(間奏)を除く
        return l.lines.map { it.text }.filter { it.isNotBlank() }.joinToString("\n") to synced
    }
}
